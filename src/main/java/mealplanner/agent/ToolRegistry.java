package mealplanner.agent;

import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import mealplanner.domain.AppState;
import mealplanner.domain.Catalogue;
import mealplanner.domain.Diet;
import mealplanner.domain.Ingredient;
import mealplanner.domain.IngredientAmount;
import mealplanner.domain.MealEntry;
import mealplanner.domain.MealPlan;
import mealplanner.domain.PreferenceProfile;
import mealplanner.domain.Quantities;
import mealplanner.domain.Recipe;
import mealplanner.service.ConstraintValidator;
import mealplanner.service.DomainException;
import mealplanner.service.PlanDraft;
import mealplanner.service.PlanService;
import mealplanner.service.RecipeService;
import mealplanner.service.SearchFilter;
import mealplanner.service.ValidationResult;

/**
 * The approved local tools the model may request. execute() checks the tool name against the
 * task's allowed set and the arguments against the tool schema before dispatching; invalid
 * requests become error results without running anything. Tools only read the request snapshot
 * or call validation; none can call accept() or save.
 */
public class ToolRegistry {

    static final int MAX_SEARCH_RESULTS = 15;

    private final RecipeService recipeService;
    private final PlanService planService;
    private final ConstraintValidator validator;
    private final Map<String, ToolSpec> specs = new LinkedHashMap<>();
    private final Map<TaskType, List<String>> allowedNames = Map.of(
            TaskType.RECOMMEND, List.of("readProfile", "readPantry", "searchRecipes", "listIngredients"),
            TaskType.PLAN, List.of("readProfile", "readPantry", "searchRecipes", "checkPlan"),
            TaskType.REVISE, List.of("readPlan", "readProfile", "searchRecipes", "checkPlan"),
            TaskType.SUBSTITUTE, List.of("readProfile", "listIngredients"));

    public ToolRegistry(RecipeService recipeService, PlanService planService, ConstraintValidator validator) {
        this.recipeService = recipeService;
        this.planService = planService;
        this.validator = validator;
        String[] none = {};
        register(new ToolSpec("readProfile",
                "Returns the user's saved dietary preferences (diet, excluded ingredient IDs, household servings, maximum minutes).",
                Schemas.object(none)));
        register(new ToolSpec("readPantry",
                "Returns the ingredients currently in the pantry with quantities in their canonical units.",
                Schemas.object(none)));
        register(new ToolSpec("searchRecipes",
                "Searches stored recipes. Only recipes that satisfy the saved preferences are returned. "
                        + "Returns recipe IDs like R07 with servings, minutes, ingredients and what is missing from the pantry.",
                Schemas.object(none,
                        "text", Schemas.string("Words that must appear in the recipe title, e.g. 'soup'."),
                        "ingredientIds", Schemas.array("Ingredient IDs that every result must contain.", Schemas.string(null)),
                        "excludeIngredientIds", Schemas.array("Ingredient IDs that results must not contain.", Schemas.string(null)),
                        "maxMinutes", Schemas.integer("Maximum preparation minutes (can only tighten the saved limit)."),
                        "pantryOnly", Schemas.bool("If true, only recipes fully covered by pantry stock at base servings."))));
        register(new ToolSpec("readPlan",
                "Returns a saved meal plan: each meal's day, date, weekday, slot, recipe ID and whether it still fits the current preferences.",
                Schemas.object(new String[] {"planId"}, "planId", Schemas.string("Plan ID like P01."))));
        register(new ToolSpec("listIngredients",
                "Lists catalogue ingredients with their IDs, canonical units and compatible diets.",
                Schemas.object(none,
                        "query", Schemas.string("Optional text to match in the ingredient ID or name, e.g. 'milk'."),
                        "diet", Schemas.enumeration("Only ingredients compatible with this diet.", "unrestricted", "vegetarian", "vegan"))));
        ObjectNode entry = Schemas.object(new String[] {"day", "slot", "recipeId"},
                "day", Schemas.integer("Day number starting at 1."),
                "slot", Schemas.enumeration("Meal slot.", "BREAKFAST", "LUNCH", "DINNER"),
                "recipeId", Schemas.string("Recipe ID like R07."));
        register(new ToolSpec("checkPlan",
                "Validates proposed meal choices without saving them. For a new plan pass every meal; for a revision pass "
                        + "only the replaced meals. Returns whether the result is valid and the problems to fix.",
                Schemas.object(new String[] {"entries"}, "entries", Schemas.array("Meal choices.", entry))));
    }

    private void register(ToolSpec spec) {
        specs.put(spec.name(), spec);
    }

    public List<ToolSpec> specsFor(TaskType task) {
        List<ToolSpec> list = new ArrayList<>();
        for (String name : allowedNames.getOrDefault(task, List.of())) {
            list.add(specs.get(name));
        }
        return list;
    }

    /** Checks the name and arguments, then runs the tool against the request snapshot. */
    public ToolResult execute(ToolCall call, ToolScope scope) {
        String name = call.name();
        if (!allowedNames.getOrDefault(scope.task(), List.of()).contains(name)) {
            return ToolResult.error(name, call.callId(), "Unknown or unavailable tool '" + name + "' for this task. Available: "
                    + allowedNames.getOrDefault(scope.task(), List.of()) + ".");
        }
        JsonNode args = call.arguments() == null || call.arguments().isNull() || call.arguments().isMissingNode()
                ? Schemas.MAPPER.createObjectNode() : call.arguments();
        List<String> problems = new ArrayList<>();
        Schemas.check(specs.get(name).parameters(), args, "arguments", problems);
        if (!problems.isEmpty()) {
            return new ToolResult(name, call.callId(), false, null, problems, Set.of(), Set.of(),
                    "rejected invalid arguments");
        }
        try {
            ToolResult result = switch (name) {
                case "readProfile" -> readProfile(call, scope);
                case "readPantry" -> readPantry(call, scope);
                case "searchRecipes" -> searchRecipes(call, args, scope);
                case "readPlan" -> readPlan(call, args, scope);
                case "listIngredients" -> listIngredients(call, args, scope);
                case "checkPlan" -> checkPlan(call, args, scope);
                default -> ToolResult.error(name, call.callId(), "Unknown tool.");
            };
            scope.recordRetrieval(result);
            return result;
        } catch (DomainException e) {
            return new ToolResult(name, call.callId(), false, null, e.messages(), Set.of(), Set.of(), "error");
        }
    }

    // ---- tools ---------------------------------------------------------------------------------

    private ToolResult readProfile(ToolCall call, ToolScope scope) {
        PreferenceProfile profile = scope.snapshot().getProfile();
        ObjectNode data = Schemas.MAPPER.createObjectNode();
        data.put("diet", profile.diet().label());
        ArrayNode excluded = data.putArray("excludedIngredientIds");
        profile.excludedIds().forEach(excluded::add);
        data.put("householdServings", profile.householdServings());
        data.put("maxMinutes", profile.maxMinutes());
        return ok(call, data, Set.of(), Set.of(), "diet " + profile.diet().label() + ", max " + profile.maxMinutes() + " min");
    }

    private ToolResult readPantry(ToolCall call, ToolScope scope) {
        ObjectNode data = Schemas.MAPPER.createObjectNode();
        ArrayNode items = data.putArray("items");
        Catalogue catalogue = validator.catalogue();
        scope.snapshot().getPantry().forEach((id, quantity) -> {
            if (quantity.signum() > 0) {
                ObjectNode item = items.addObject();
                item.put("ingredientId", id);
                item.put("name", catalogue.nameOf(id));
                item.put("quantity", Quantities.display(quantity));
                item.put("unit", catalogue.unitLabel(id));
            }
        });
        if (items.isEmpty()) {
            data.put("note", "The pantry is empty.");
        }
        return ok(call, data, Set.of(), Set.of(), items.size() + " pantry item(s)");
    }

    private ToolResult searchRecipes(ToolCall call, JsonNode args, ToolScope scope) {
        AppState snapshot = scope.snapshot();
        List<String> include = strings(args.path("ingredientIds"));
        List<String> exclude = strings(args.path("excludeIngredientIds"));
        for (String id : exclude) {
            if (!validator.catalogue().contains(id)) {
                throw new DomainException("excludeIngredientIds: '" + id + "' is not a known ingredient ID. Use listIngredients if unsure.");
            }
        }
        Integer maxMinutes = args.hasNonNull("maxMinutes") ? args.get("maxMinutes").asInt() : null;
        SearchFilter filter = new SearchFilter(args.path("text").asText(""), include, maxMinutes,
                args.path("pantryOnly").asBoolean(false));
        List<Recipe> found = new ArrayList<>();
        for (Recipe recipe : recipeService.search(snapshot, filter)) {
            if (exclude.stream().noneMatch(recipe::usesIngredient)) {
                found.add(recipe);
            }
        }
        ObjectNode data = Schemas.MAPPER.createObjectNode();
        data.put("count", found.size());
        ArrayNode recipes = data.putArray("recipes");
        Set<UUID> retrieved = new HashSet<>();
        List<String> aliases = new ArrayList<>();
        for (Recipe recipe : found.subList(0, Math.min(found.size(), MAX_SEARCH_RESULTS))) {
            String alias = snapshot.recipeAlias(recipe.getId());
            retrieved.add(recipe.getId());
            aliases.add(alias);
            ObjectNode node = recipes.addObject();
            node.put("id", alias);
            node.put("title", recipe.getTitle());
            node.put("servings", recipe.getServings());
            node.put("minutes", recipe.getMinutes());
            ArrayNode ingredients = node.putArray("ingredients");
            for (IngredientAmount amount : recipe.getIngredients()) {
                ingredients.add(amount.ingredientId() + " " + Quantities.display(amount.quantity())
                        + " " + validator.catalogue().unitLabel(amount.ingredientId()));
            }
            ArrayNode missing = node.putArray("missingFromPantry");
            for (RecipeService.Shortage shortage : recipeService.shortages(recipe, snapshot)) {
                missing.add(shortage.ingredientId() + " " + Quantities.display(shortage.missing())
                        + " " + validator.catalogue().unitLabel(shortage.ingredientId()));
            }
        }
        if (found.size() > MAX_SEARCH_RESULTS) {
            data.put("note", "Showing the first " + MAX_SEARCH_RESULTS + " of " + found.size() + " matches; refine the filters to see others.");
        } else if (found.isEmpty()) {
            data.put("note", "No stored recipe matches. Saved preferences are always applied and cannot be relaxed.");
        }
        return ok(call, data, retrieved, Set.of(), found.size() + " recipe(s)" + (aliases.isEmpty() ? "" : " " + aliases));
    }

    private ToolResult readPlan(ToolCall call, JsonNode args, ToolScope scope) {
        AppState snapshot = scope.snapshot();
        MealPlan plan = snapshot.findPlan(args.get("planId").asText()).orElse(null);
        if (plan == null) {
            throw new DomainException("No plan '" + args.get("planId").asText() + "'.");
        }
        ObjectNode data = Schemas.MAPPER.createObjectNode();
        data.put("planId", snapshot.planAlias(plan.getId()));
        data.put("startDate", plan.getStartDate().toString());
        data.put("days", plan.getDays());
        data.put("noRepeat", plan.isNoRepeat());
        ArrayNode meals = data.putArray("meals");
        int changeNeeded = 0;
        for (MealEntry entry : plan.getMeals()) {
            Recipe snapshotRecipe = entry.getRecipeSnapshot();
            ValidationResult check = validator.recipe(snapshotRecipe, snapshot.getProfile());
            ObjectNode meal = meals.addObject();
            meal.put("day", entry.getDay());
            meal.put("date", plan.dateOf(entry.getDay()).toString());
            meal.put("weekday", plan.dateOf(entry.getDay()).getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH));
            meal.put("slot", entry.getSlot().name());
            meal.put("recipeId", snapshot.getRecipes().containsKey(snapshotRecipe.getId())
                    ? snapshot.recipeAlias(snapshotRecipe.getId()) : "(snapshot only)");
            meal.put("title", snapshotRecipe.getTitle());
            meal.put("servings", entry.getServings());
            meal.put("fitsCurrentPreferences", check.isValid());
            if (!check.isValid()) {
                changeNeeded++;
                ArrayNode problems = meal.putArray("problems");
                check.allMessages().forEach(problems::add);
            }
        }
        return ok(call, data, Set.of(), Set.of(), plan.getMeals().size() + " meal(s)"
                + (changeNeeded > 0 ? ", " + changeNeeded + " no longer fit preferences" : ""));
    }

    private ToolResult listIngredients(ToolCall call, JsonNode args, ToolScope scope) {
        String query = args.path("query").asText("").trim().toLowerCase(Locale.ROOT);
        Diet diet = args.hasNonNull("diet") ? Diet.parse(args.get("diet").asText()) : null;
        PreferenceProfile profile = scope.snapshot().getProfile();
        ObjectNode data = Schemas.MAPPER.createObjectNode();
        ArrayNode list = data.putArray("ingredients");
        Set<String> retrieved = new HashSet<>();
        for (Ingredient ingredient : validator.catalogue().all()) {
            boolean matches = query.isEmpty() || ingredient.id().contains(query)
                    || ingredient.name().toLowerCase(Locale.ROOT).contains(query);
            if (!matches || (diet != null && !ingredient.allows(diet))) {
                continue;
            }
            retrieved.add(ingredient.id());
            ObjectNode node = list.addObject();
            node.put("id", ingredient.id());
            node.put("name", ingredient.name());
            node.put("unit", ingredient.unit().label());
            ArrayNode diets = node.putArray("diets");
            ingredient.dietTags().forEach(d -> diets.add(d.label()));
            node.put("excludedByProfile", profile.excludedIds().contains(ingredient.id()));
        }
        if (list.isEmpty()) {
            data.put("note", "No catalogue ingredient matches; try a shorter query or no query.");
        }
        return ok(call, data, Set.of(), retrieved, retrieved.size() + " ingredient(s)");
    }

    private ToolResult checkPlan(ToolCall call, JsonNode args, ToolScope scope) {
        List<PlanDraft.Entry> entries = new ArrayList<>();
        for (JsonNode entry : args.get("entries")) {
            entries.add(new PlanDraft.Entry(entry.get("day").asInt(), entry.get("slot").asText().toUpperCase(Locale.ROOT),
                    entry.get("recipeId").asText()));
        }
        PlanDraft draft = new PlanDraft(entries, "", scope.retrievedRecipeIds());
        ObjectNode data = Schemas.MAPPER.createObjectNode();
        try {
            if (scope.task() == TaskType.REVISE) {
                planService.revise(scope.sourcePlan(), draft, scope.snapshot());
            } else {
                planService.fromDraft(draft, scope.snapshot(), scope.planRequest());
            }
            data.put("valid", true);
            return ok(call, data, Set.of(), Set.of(), "valid");
        } catch (DomainException | IllegalArgumentException e) {
            data.put("valid", false);
            ArrayNode problems = data.putArray("problems");
            List<String> messages = e instanceof DomainException d ? d.messages() : List.of(e.getMessage());
            messages.forEach(problems::add);
            return ok(call, data, Set.of(), Set.of(), messages.size() + " problem(s)");
        }
    }

    // ---- helpers -------------------------------------------------------------------------------

    private static ToolResult ok(ToolCall call, JsonNode data, Set<UUID> recipes, Set<String> ingredients, String summary) {
        return new ToolResult(call.name(), call.callId(), true, data, List.of(), recipes, ingredients, summary);
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        if (array != null && array.isArray()) {
            for (JsonNode node : array) {
                values.add(node.asText().trim().toLowerCase(Locale.ROOT));
            }
        }
        return values;
    }

    /** For tests and the report: every tool name this registry knows. */
    public Set<String> toolNames() {
        return specs.keySet();
    }
}
