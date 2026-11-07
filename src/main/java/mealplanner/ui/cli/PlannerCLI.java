package mealplanner.ui.cli;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import mealplanner.app.AppContext;
import mealplanner.domain.AppState;
import mealplanner.domain.Ingredient;
import mealplanner.domain.MealPlan;
import mealplanner.domain.MealSlot;
import mealplanner.domain.PlanRequest;
import mealplanner.domain.PreferenceProfile;
import mealplanner.domain.Recipe;
import mealplanner.facade.PlannerFacade;
import mealplanner.facade.Proposal;
import mealplanner.facade.Result;
import mealplanner.llm.LoggingLLMClient;
import mealplanner.service.PantryInput;
import mealplanner.service.ProfileInput;
import mealplanner.service.RecipeDocument;
import mealplanner.service.RecommendationList;
import mealplanner.service.SearchFilter;
import mealplanner.service.ShoppingList;
import mealplanner.ui.TextFormat;

/**
 * Interactive command session. Every command is parsed here and turned into one PlannerFacade
 * call; the CLI has no business rules of its own. A preview stays pending until "accept" saves it
 * or "reject" (or a newer preview) discards it.
 */
public class PlannerCLI {

    private final PlannerFacade facade;
    private final LoggingLLMClient llm;
    private PrintStream out;
    private Proposal pending;

    public PlannerCLI(AppContext context) {
        this(context.facade(), context.llm());
    }

    public PlannerCLI(PlannerFacade facade, LoggingLLMClient llm) {
        this.facade = facade;
        this.llm = llm;
    }

    public void run(BufferedReader in, PrintStream out) {
        this.out = out;
        out.println("MealPlanner CLI. Type 'help' for commands, 'quit' to exit.");
        out.println(facade.aiUnavailableReason() == null
                ? "AI: " + facade.aiDescription() : "AI: unavailable - " + facade.aiUnavailableReason());
        while (true) {
            out.print("mealplanner> ");
            out.flush();
            String line;
            try {
                line = in.readLine();
            } catch (IOException e) {
                break;
            }
            if (line == null) {
                break;
            }
            if (!execute(line)) {
                break;
            }
        }
        if (pending != null) {
            out.println("Unsaved preview discarded.");
        }
        out.println("Bye.");
    }

    /** Runs one command line. Returns false when the session should end. */
    public boolean execute(String line) {
        List<String> tokens;
        try {
            tokens = tokenize(line);
        } catch (IllegalArgumentException e) {
            out.println("Error: " + e.getMessage());
            return true;
        }
        if (tokens.isEmpty()) {
            return true;
        }
        String command = tokens.get(0).toLowerCase(Locale.ROOT);
        String sub = tokens.size() > 1 ? tokens.get(1).toLowerCase(Locale.ROOT) : "";
        try {
            Options o = Options.parse(tokens.subList(Math.min(2, tokens.size()), tokens.size()));
            switch (command) {
                case "quit", "exit" -> {
                    return false;
                }
                case "help" -> out.println(HELP);
                case "profile" -> profile(sub, o);
                case "pantry" -> pantry(sub, o);
                case "ingredients" -> ingredients(Options.parse(tokens.subList(1, tokens.size())));
                case "recipe" -> recipe(sub, o);
                case "agent" -> agent(sub, o);
                case "plan" -> plan(sub, o);
                case "shopping" -> shopping(sub, o);
                case "accept" -> accept();
                case "reject" -> reject();
                case "status", "usage" -> status();
                default -> out.println("Unknown command '" + command + "'. Type 'help'.");
            }
        } catch (IllegalArgumentException e) {
            out.println("Error: " + e.getMessage());
        }
        return true;
    }

    // ---- F01 ---------------------------------------------------------------------------------------

    private void profile(String sub, Options o) {
        PreferenceProfile current = facade.snapshot().getProfile();
        switch (sub) {
            case "show", "" -> out.println(TextFormat.profile(current));
            case "set" -> {
                List<String> excluded = o.has("exclude") ? o.list("exclude") : new ArrayList<>(current.excludedIds());
                if (excluded.size() == 1 && excluded.get(0).equalsIgnoreCase("none")) {
                    excluded = List.of();
                }
                Result<PreferenceProfile> r = facade.updateProfile(new ProfileInput(
                        o.get("diet", current.diet().label()), excluded,
                        o.get("servings", String.valueOf(current.householdServings())),
                        o.get("max-minutes", String.valueOf(current.maxMinutes()))));
                if (show(r)) {
                    out.println(TextFormat.profile(r.data()));
                }
            }
            default -> out.println("Use: profile show | profile set [--diet D] [--servings N] [--max-minutes M] [--exclude id,id|none]");
        }
    }

    // ---- F02 ---------------------------------------------------------------------------------------

    private void pantry(String sub, Options o) {
        switch (sub) {
            case "show", "" -> out.println(TextFormat.pantry(facade.snapshot().getPantry(), facade.catalogue()));
            case "set" -> {
                Result<Map<String, BigDecimal>> r = facade.updatePantry(PantryInput.set(o.required("ingredient"), o.required("quantity")));
                show(r);
            }
            case "remove" -> show(facade.updatePantry(PantryInput.remove(o.required("ingredient"))));
            default -> out.println("Use: pantry show | pantry set --ingredient ID --quantity Q | pantry remove --ingredient ID");
        }
    }

    private void ingredients(Options o) {
        String query = o.get("query", "").toLowerCase(Locale.ROOT);
        for (Ingredient i : facade.catalogue().all()) {
            if (query.isEmpty() || i.id().contains(query) || i.name().toLowerCase(Locale.ROOT).contains(query)) {
                List<String> diets = new ArrayList<>();
                i.dietTags().forEach(d -> diets.add(d.label()));
                out.printf(Locale.ROOT, "%-16s %-22s %-5s %s%n", i.id(), i.name(), i.unit().label(), String.join("/", diets));
            }
        }
    }

    // ---- F03 F04 F09 ---------------------------------------------------------------------------------

    private void recipe(String sub, Options o) {
        AppState state = facade.snapshot();
        switch (sub) {
            case "list" -> {
                for (Recipe r : state.getRecipes().values()) {
                    out.println(TextFormat.recipeLine(state, r, facade.isEligible(r)));
                }
            }
            case "show" -> {
                Recipe r = state.findRecipe(o.required("id")).orElseThrow(() -> new IllegalArgumentException("No such recipe."));
                out.println(TextFormat.recipe(state, r, facade.catalogue(), facade.isEligible(r)));
            }
            case "search" -> {
                Integer max = o.has("max-minutes") ? o.integer("max-minutes") : null;
                Result<List<Recipe>> r = facade.search(new SearchFilter(o.get("text", ""), o.list("ingredient"), max, o.flag("pantry-only")));
                if (show(r)) {
                    r.data().forEach(recipe -> out.println(TextFormat.recipeLine(state, recipe, true)));
                }
            }
            case "import" -> preview(facade.previewRecipeFile(Path.of(o.required("file"))));
            case "create" -> {
                List<RecipeDocument.Line> lines = new ArrayList<>();
                for (String item : o.all("ingredient")) {
                    int eq = item.indexOf('=');
                    if (eq <= 0) {
                        throw new IllegalArgumentException("--ingredient must look like id=quantity, e.g. rice=200");
                    }
                    BigDecimal qty;
                    try {
                        qty = new BigDecimal(item.substring(eq + 1).trim());
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("'" + item.substring(eq + 1) + "' is not a number.");
                    }
                    lines.add(new RecipeDocument.Line(item.substring(0, eq).trim(), qty));
                }
                preview(facade.previewRecipe(new RecipeDocument(o.get("title", null),
                        o.has("servings") ? o.integer("servings") : null, o.has("minutes") ? o.integer("minutes") : null,
                        lines, o.all("step"))));
            }
            case "scale" -> preview(facade.scale(o.required("id"), o.integer("servings")));
            default -> out.println("Use: recipe list | show --id R01 | search [...] | import --file F | create [...] | scale --id R01 --servings N");
        }
    }

    // ---- F05-F08 ---------------------------------------------------------------------------------------

    private void agent(String sub, Options o) {
        switch (sub) {
            case "recommend" -> {
                working();
                Result<RecommendationList> r = facade.recommend(o.required("request"));
                if (r.isSuccess()) {
                    out.println(r.message());
                    out.println(TextFormat.recommendations(r.data(), facade.catalogue()));
                } else {
                    out.println(TextFormat.errors(r));
                }
            }
            case "plan" -> {
                LocalDate start;
                try {
                    start = o.has("start") ? LocalDate.parse(o.get("start", "")) : LocalDate.now().plusDays(1);
                } catch (DateTimeParseException e) {
                    throw new IllegalArgumentException("--start must be a date like 2026-11-09.");
                }
                Set<MealSlot> slots = EnumSet.noneOf(MealSlot.class);
                for (String s : o.has("slots") ? o.list("slots") : List.of("dinner")) {
                    MealSlot slot = MealSlot.parse(s);
                    if (slot == null) {
                        throw new IllegalArgumentException("Unknown slot '" + s + "' (use breakfast, lunch, dinner).");
                    }
                    slots.add(slot);
                }
                int servings = o.has("servings") ? o.integer("servings") : facade.snapshot().getProfile().householdServings();
                PlanRequest request = new PlanRequest(start, o.has("days") ? o.integer("days") : 7, slots, servings, o.flag("no-repeat"));
                out.printf(Locale.ROOT, "Plan request: start %s, %d day(s), slots %s, %d serving(s), repeats %s%n",
                        request.startDate(), request.days(), request.slots(), request.servings(),
                        request.noRepeat() ? "forbidden" : "allowed");
                working();
                preview(facade.createPlan(request, o.get("request", "")));
            }
            case "revise" -> {
                working();
                preview(facade.revisePlan(o.required("plan"), o.required("request")));
            }
            case "substitute" -> {
                working();
                preview(facade.substitute(o.required("recipe"), o.required("ingredient"), o.required("request")));
            }
            default -> out.println("Use: agent recommend | plan | revise | substitute  (see 'help')");
        }
    }

    // ---- plans and F10 --------------------------------------------------------------------------------

    private void plan(String sub, Options o) {
        AppState state = facade.snapshot();
        switch (sub) {
            case "list", "" -> {
                if (state.getPlans().isEmpty()) {
                    out.println("No saved plans yet. Create one with 'agent plan'.");
                }
                state.getPlans().values().forEach(p -> out.println(TextFormat.planLine(state, p)));
            }
            case "show" -> {
                String ref = o.has("plan") ? o.get("plan", "") : o.required("id");
                MealPlan plan = state.findPlan(ref).orElseThrow(() -> new IllegalArgumentException("No such plan."));
                out.println(TextFormat.plan(state, plan));
            }
            default -> out.println("Use: plan list | plan show --plan P01");
        }
    }

    private void shopping(String sub, Options o) {
        if (!sub.equals("generate")) {
            out.println("Use: shopping generate --plan P01");
            return;
        }
        Result<ShoppingList> r = facade.shopping(o.required("plan"));
        if (r.isSuccess()) {
            out.println(TextFormat.shopping(facade.snapshot(), r.data()));
        } else {
            out.println(TextFormat.errors(r));
        }
    }

    // ---- previews ---------------------------------------------------------------------------------------

    private void preview(Result<Proposal> r) {
        if (!r.isSuccess()) {
            out.println(TextFormat.errors(r));
            return;
        }
        if (pending != null) {
            facade.reject(pending);
            out.println("(The previous unsaved preview was discarded.)");
        }
        pending = r.data();
        out.println(TextFormat.proposal(facade.snapshot(), pending, facade.catalogue()));
        out.println("\nType 'accept' to save or 'reject' to discard.");
    }

    private void accept() {
        if (pending == null) {
            out.println("There is no preview waiting.");
            return;
        }
        Result<String> r = facade.accept(pending);
        pending = null;
        out.println(r.isSuccess() ? r.data() : TextFormat.errors(r));
    }

    private void reject() {
        if (pending == null) {
            out.println("There is no preview waiting.");
            return;
        }
        out.println(facade.reject(pending).message());
        pending = null;
    }

    private void status() {
        AppState state = facade.snapshot();
        out.println("Recipes: " + state.getRecipes().size() + ", plans: " + state.getPlans().size()
                + ", pantry items: " + state.getPantry().size() + ", revision: " + state.getRevision());
        if (facade.aiUnavailableReason() != null) {
            out.println("AI: unavailable - " + facade.aiUnavailableReason());
        } else if (llm != null) {
            LoggingLLMClient.UsageSummary u = llm.usage();
            out.println("AI: " + facade.aiDescription() + " - this session: " + u.calls() + " model call(s), "
                    + u.failures() + " failed, " + u.inputTokens() + " input + " + u.outputTokens() + " output tokens");
        }
    }

    private void working() {
        if (facade.aiUnavailableReason() == null) {
            out.println("Working with the AI agent (up to 90 seconds)...");
            out.flush();
        }
    }

    private boolean show(Result<?> r) {
        if (r.isSuccess()) {
            if (!r.message().isBlank()) {
                out.println(r.message());
            }
            return true;
        }
        out.println(TextFormat.errors(r));
        return false;
    }

    // ---- parsing ---------------------------------------------------------------------------------------

    /** Splits a command line on spaces, keeping "quoted text" together. */
    static List<String> tokenize(String line) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        boolean inToken = false;
        for (char c : line.toCharArray()) {
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else {
                    current.append(c);
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
                inToken = true;
            } else if (Character.isWhitespace(c)) {
                if (inToken) {
                    tokens.add(current.toString());
                    current.setLength(0);
                    inToken = false;
                }
            } else {
                current.append(c);
                inToken = true;
            }
        }
        if (quote != 0) {
            throw new IllegalArgumentException("Missing closing quote.");
        }
        if (inToken) {
            tokens.add(current.toString());
        }
        return tokens;
    }

    /** "--name value" options; repeated names collect values; "--flag" alone is boolean. */
    static final class Options {
        private final Map<String, List<String>> values = new HashMap<>();

        static Options parse(List<String> tokens) {
            Options o = new Options();
            for (int i = 0; i < tokens.size(); i++) {
                String token = tokens.get(i);
                if (!token.startsWith("--") || token.length() == 2) {
                    throw new IllegalArgumentException("Unexpected '" + token + "'. Options look like --name value.");
                }
                String name = token.substring(2).toLowerCase(Locale.ROOT);
                String value = i + 1 < tokens.size() && !tokens.get(i + 1).startsWith("--") ? tokens.get(++i) : "true";
                o.values.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
            }
            return o;
        }

        boolean has(String name) {
            return values.containsKey(name);
        }

        String get(String name, String fallback) {
            List<String> list = values.get(name);
            return list == null ? fallback : list.get(list.size() - 1);
        }

        String required(String name) {
            String value = get(name, null);
            if (value == null || value.isBlank() || value.equals("true")) {
                throw new IllegalArgumentException("--" + name + " is required.");
            }
            return value;
        }

        int integer(String name) {
            String value = required(name);
            try {
                return Integer.parseInt(value.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("--" + name + " must be a whole number (was '" + value + "').");
            }
        }

        boolean flag(String name) {
            return "true".equalsIgnoreCase(get(name, "false"));
        }

        /** Every value given for a repeated option, without comma splitting. */
        List<String> all(String name) {
            return values.getOrDefault(name, List.of());
        }

        /** Values for a repeatable option; each value may also be a comma-separated list. */
        List<String> list(String name) {
            List<String> result = new ArrayList<>();
            for (String value : values.getOrDefault(name, List.of())) {
                for (String part : value.split(",")) {
                    if (!part.isBlank()) {
                        result.add(part.trim());
                    }
                }
            }
            return result;
        }
    }

    static final String HELP = """
            Preferences (F01)
              profile show
              profile set --diet vegetarian --servings 2 --max-minutes 30 --exclude peanut   (--exclude none clears)
            Pantry (F02)
              pantry show | pantry set --ingredient rice --quantity 500 | pantry remove --ingredient rice
              ingredients [--query milk]                      list catalogue IDs and units
            Recipes (F03, F04, F09)
              recipe list | recipe show --id R01
              recipe import --file samples/lentil-soup.json
              recipe create --title "Garlic Rice" --servings 2 --minutes 15 --ingredient rice=200 --ingredient garlic=2 \\
                            --step "Cook the rice." --step "Fry the garlic and mix."
              recipe search [--text soup] [--ingredient lentils] [--max-minutes 30] [--pantry-only]
              recipe scale --id R01 --servings 5
            AI agent (F05-F08)
              agent recommend --request "Use my lentils and keep meals under 30 minutes"
              agent plan --start 2026-11-09 --days 7 --slots dinner --servings 2 --no-repeat [--request "..."]
              agent revise --plan P01 --request "Replace Tuesday dinner with a lentil dish"
              agent substitute --recipe R03 --ingredient milk --request "Use a vegan alternative"
            Plans and shopping (F10)
              plan list | plan show --plan P01
              shopping generate --plan P01
            Previews:  accept | reject        Other:  status | help | quit""";
}
