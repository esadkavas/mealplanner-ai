package mealplanner.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;

import mealplanner.domain.AppState;
import mealplanner.domain.IngredientAmount;
import mealplanner.domain.PreferenceProfile;
import mealplanner.domain.Quantities;
import mealplanner.domain.Recipe;
import mealplanner.persistence.Json;

/**
 * Recipe business rules: parsing (F03), search (F04), scaling (F09) and applying a substitution
 * draft (F08). Every operation that changes a recipe works on a {@link Recipe#deepCopy()}.
 */
public class RecipeService {

    /** Bounded JSON import size from the Stage 1 design. */
    public static final int MAX_IMPORT_BYTES = 100 * 1024;

    private final ConstraintValidator validator;

    public RecipeService(ConstraintValidator validator) {
        this.validator = validator;
    }

    // ---- F03 parse ----------------------------------------------------------------------------

    /** Parses a structured JSON recipe. Malformed JSON or unknown fields are rejected. */
    public Recipe parse(String json) {
        if (json == null || json.isBlank()) {
            throw new DomainException("The recipe file is empty.");
        }
        if (json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_IMPORT_BYTES) {
            throw new DomainException("Recipe imports are limited to 100 KB.");
        }
        RecipeDocument document;
        try {
            document = Json.mapper().readValue(json, RecipeDocument.class);
        } catch (UnrecognizedPropertyException e) {
            throw new DomainException("Unsupported field '" + e.getPropertyName()
                    + "'. Allowed fields: title, servings, minutes, ingredients, steps.");
        } catch (JsonProcessingException e) {
            throw new DomainException("Malformed recipe JSON: " + e.getOriginalMessage());
        }
        if (document == null) {
            throw new DomainException("The recipe file is empty.");
        }
        return fromDocument(document);
    }

    /** Converts form or JSON data into an unsaved recipe (no ID yet). Missing fields are errors. */
    public Recipe fromDocument(RecipeDocument document) {
        List<String> errors = new ArrayList<>();
        if (document.title() == null) {
            errors.add("title: is required.");
        }
        if (document.servings() == null) {
            errors.add("servings: is required.");
        }
        if (document.minutes() == null) {
            errors.add("minutes: is required.");
        }
        if (document.ingredients() == null) {
            errors.add("ingredients: is required.");
        }
        if (document.steps() == null) {
            errors.add("steps: is required.");
        }
        if (!errors.isEmpty()) {
            throw new DomainException(errors);
        }
        List<IngredientAmount> amounts = new ArrayList<>();
        for (RecipeDocument.Line line : document.ingredients()) {
            if (line == null) {
                throw new DomainException("ingredients: contains an empty entry.");
            }
            String id = line.ingredientId() == null ? null : line.ingredientId().trim().toLowerCase(Locale.ROOT);
            amounts.add(new IngredientAmount(id, line.quantity()));
        }
        List<String> steps = new ArrayList<>();
        for (String step : document.steps()) {
            steps.add(step == null ? null : step.trim());
        }
        return new Recipe(null, document.title().trim(), document.servings(), document.minutes(), amounts, steps);
    }

    // ---- F04 search ---------------------------------------------------------------------------

    public ValidationResult validateFilter(SearchFilter filter) {
        ValidationResult result = ValidationResult.ok();
        for (String id : filter.ingredientIds()) {
            if (!validator.catalogue().contains(id)) {
                result.error("ingredient: '" + id + "' is not a known ingredient ID.");
            }
        }
        if (filter.maxMinutes() != null
                && (filter.maxMinutes() < ConstraintValidator.MIN_MINUTES || filter.maxMinutes() > ConstraintValidator.MAX_MINUTES)) {
            result.error("max-minutes: must be between 1 and 240.");
        }
        return result;
    }

    /**
     * Returns stored recipes that are eligible for the saved profile and match every filter.
     * Constraints are never relaxed; an empty list is a valid result.
     */
    public List<Recipe> search(AppState state, SearchFilter filter) {
        ValidationResult filterCheck = validateFilter(filter);
        if (!filterCheck.isStructurallyValid()) {
            throw new DomainException(filterCheck.structuralErrors());
        }
        PreferenceProfile profile = state.getProfile();
        int limit = filter.maxMinutes() == null ? profile.maxMinutes() : Math.min(filter.maxMinutes(), profile.maxMinutes());
        String text = filter.text().toLowerCase(Locale.ROOT);
        List<Recipe> matches = new ArrayList<>();
        for (Recipe recipe : state.getRecipes().values()) {
            if (!validator.isEligible(recipe, profile) || recipe.getMinutes() > limit) {
                continue;
            }
            if (!text.isEmpty() && !recipe.getTitle().toLowerCase(Locale.ROOT).contains(text)) {
                continue;
            }
            if (!filter.ingredientIds().stream().allMatch(recipe::usesIngredient)) {
                continue;
            }
            if (filter.pantryOnly() && !shortages(recipe, state).isEmpty()) {
                continue;
            }
            matches.add(recipe);
        }
        return matches;
    }

    /** Missing quantities to cook the recipe once at its base servings (pantry is not changed). */
    public List<Shortage> shortages(Recipe recipe, AppState state) {
        List<Shortage> missing = new ArrayList<>();
        for (IngredientAmount amount : recipe.getIngredients()) {
            BigDecimal have = state.pantryQuantity(amount.ingredientId());
            BigDecimal gap = amount.quantity().subtract(have, Quantities.MATH);
            if (gap.signum() > 0) {
                missing.add(new Shortage(amount.ingredientId(), amount.quantity(), have, gap));
            }
        }
        return missing;
    }

    /** One missing ingredient: required at base servings, available in the pantry, and the gap. */
    public record Shortage(String ingredientId, BigDecimal required, BigDecimal available, BigDecimal missing) {
    }

    // ---- F05 recommendations --------------------------------------------------------------------

    /**
     * Independently validates the agent's suggestions: each recipe must exist, be unique, have
     * been retrieved through the tools, satisfy the saved profile and the constraints the model
     * reported from the request. Any problem rejects the whole answer. Shortages are calculated
     * here, not by the model.
     */
    public RecommendationList recommendations(RecommendationDraft draft, AppState state) {
        List<String> problems = new ArrayList<>();
        if (draft.maxMinutes() < 0 || draft.maxMinutes() > ConstraintValidator.MAX_MINUTES) {
            problems.add("The AI reported an invalid time limit (" + draft.maxMinutes() + ").");
        }
        for (String id : concat(draft.mustIncludeIngredientIds(), draft.avoidIngredientIds())) {
            if (!validator.catalogue().contains(id)) {
                problems.add("The AI referred to unknown ingredient '" + id + "'.");
            }
        }
        if (draft.items().size() > 5) {
            problems.add("The AI returned more than five recommendations.");
        }
        List<RecommendationList.Recommendation> accepted = new ArrayList<>();
        java.util.Set<UUID> seen = new java.util.HashSet<>();
        for (RecommendationDraft.Item item : draft.items()) {
            Recipe recipe = state.findRecipe(item.recipeRef()).orElse(null);
            if (recipe == null) {
                problems.add("The AI recommended '" + item.recipeRef() + "', which is not a stored recipe.");
                continue;
            }
            String alias = state.recipeAlias(recipe.getId());
            if (!seen.add(recipe.getId())) {
                problems.add(alias + " was recommended twice.");
                continue;
            }
            if (!draft.retrievedRecipeIds().contains(recipe.getId())) {
                problems.add(alias + " was not retrieved through searchRecipes, so it cannot be recommended.");
                continue;
            }
            ValidationResult check = validator.recipe(recipe, state.getProfile());
            if (!check.isValid()) {
                problems.addAll(check.allMessages());
                continue;
            }
            if (draft.maxMinutes() > 0 && recipe.getMinutes() > draft.maxMinutes()) {
                problems.add(alias + " takes " + recipe.getMinutes() + " minutes, over the requested " + draft.maxMinutes() + ".");
            }
            for (String id : draft.mustIncludeIngredientIds()) {
                if (!recipe.usesIngredient(id)) {
                    problems.add(alias + " does not contain requested ingredient " + id + ".");
                }
            }
            for (String id : draft.avoidIngredientIds()) {
                if (recipe.usesIngredient(id)) {
                    problems.add(alias + " contains " + id + ", which the request rules out.");
                }
            }
            accepted.add(new RecommendationList.Recommendation(alias, recipe, item.reason(), shortages(recipe, state)));
        }
        if (!problems.isEmpty()) {
            throw new DomainException(problems);
        }
        List<String> constraintParts = new ArrayList<>();
        if (draft.maxMinutes() > 0) {
            constraintParts.add("max " + draft.maxMinutes() + " min");
        }
        if (!draft.mustIncludeIngredientIds().isEmpty()) {
            constraintParts.add("must include " + String.join(", ", draft.mustIncludeIngredientIds()));
        }
        if (!draft.avoidIngredientIds().isEmpty()) {
            constraintParts.add("avoid " + String.join(", ", draft.avoidIngredientIds()));
        }
        return new RecommendationList(accepted, draft.message(),
                constraintParts.isEmpty() ? "none beyond your saved preferences" : String.join("; ", constraintParts), List.of());
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> all = new ArrayList<>(a);
        all.addAll(b);
        return all;
    }

    // ---- F09 scale ----------------------------------------------------------------------------

    /**
     * Returns a scaled copy. Each quantity becomes quantity x target / base using DECIMAL128.
     * The source recipe is not modified; steps and preparation time are kept with a note.
     */
    public Recipe scale(Recipe source, int targetServings, PreferenceProfile profile) {
        if (targetServings < ConstraintValidator.MIN_SERVINGS || targetServings > ConstraintValidator.MAX_SERVINGS) {
            throw new DomainException("servings: must be a whole number between 1 and 12.");
        }
        if (source.getServings() < 1) {
            throw new DomainException("The source recipe has invalid base servings.");
        }
        Recipe copy = source.deepCopy();
        BigDecimal target = BigDecimal.valueOf(targetServings);
        BigDecimal base = BigDecimal.valueOf(source.getServings());
        List<IngredientAmount> scaled = new ArrayList<>();
        for (IngredientAmount amount : source.getIngredients()) {
            BigDecimal quantity = amount.quantity().multiply(target, Quantities.MATH).divide(base, Quantities.MATH);
            scaled.add(new IngredientAmount(amount.ingredientId(), quantity.stripTrailingZeros()));
        }
        copy.setIngredients(scaled);
        copy.setServings(targetServings);
        copy.setId(UUID.randomUUID());
        copy.setDerivedFrom(source.getId());
        copy.setTitle(baseTitle(source.getTitle()) + " (" + targetServings + " servings)");
        copy.setNote("Scaled from " + source.getServings() + " to " + targetServings
                + " servings. Preparation time and steps are unchanged; cooking time may need judgement.");
        ValidationResult check = validator.recipe(copy, null);
        if (!check.isStructurallyValid()) {
            throw new DomainException(check.structuralErrors());
        }
        return copy;
    }

    private static String baseTitle(String title) {
        return title.replaceAll("\\s*\\(\\d+ servings\\)$", "");
    }

    // ---- F08 substitute -------------------------------------------------------------------------

    /**
     * Applies a substitution draft to a copy of the recipe. Only the selected ingredient and the
     * listed steps change; servings stay the same. The full variant must be valid and eligible.
     */
    public Recipe substitute(Recipe source, SubstitutionDraft draft, PreferenceProfile profile) {
        List<String> errors = new ArrayList<>();
        String selected = draft.selectedIngredientId();
        String replacement = draft.replacementIngredientId();
        if (!source.usesIngredient(selected)) {
            errors.add("The recipe does not use '" + selected + "'.");
        }
        if (replacement == null || !validator.catalogue().contains(replacement)) {
            errors.add("The suggested replacement '" + replacement + "' is not in the ingredient catalogue.");
        } else if (replacement.equals(selected)) {
            errors.add("The suggested replacement is the same ingredient.");
        } else if (source.usesIngredient(replacement)) {
            errors.add("The recipe already contains '" + replacement + "'; choose a different replacement.");
        }
        if (draft.quantity() == null || draft.quantity().signum() <= 0) {
            errors.add("The replacement quantity must be positive.");
        }
        for (SubstitutionDraft.StepChange change : draft.stepChanges()) {
            if (change.stepNumber() < 1 || change.stepNumber() > source.getSteps().size()) {
                errors.add("Step " + change.stepNumber() + " does not exist in this recipe.");
            } else if (change.newText() == null || change.newText().isBlank()) {
                errors.add("The new text for step " + change.stepNumber() + " is empty.");
            }
        }
        if (!errors.isEmpty()) {
            throw new DomainException(errors);
        }
        Recipe copy = source.deepCopy();
        List<IngredientAmount> amounts = new ArrayList<>();
        for (IngredientAmount amount : source.getIngredients()) {
            amounts.add(amount.ingredientId().equals(selected)
                    ? new IngredientAmount(replacement, draft.quantity())
                    : amount);
        }
        List<String> steps = new ArrayList<>(source.getSteps());
        for (SubstitutionDraft.StepChange change : draft.stepChanges()) {
            steps.set(change.stepNumber() - 1, change.newText().trim());
        }
        copy.setIngredients(amounts);
        copy.setSteps(steps);
        copy.setId(UUID.randomUUID());
        copy.setDerivedFrom(source.getId());
        copy.setTitle(source.getTitle() + " (with " + validator.catalogue().nameOf(replacement).toLowerCase(Locale.ROOT) + ")");
        copy.setNote("Substituted " + validator.catalogue().nameOf(selected) + " with "
                + validator.catalogue().nameOf(replacement) + ". Suggested by AI; review before cooking.");
        ValidationResult check = validator.recipe(copy, profile);
        if (!check.isValid()) {
            throw new DomainException(check.allMessages());
        }
        return copy;
    }
}
