package mealplanner.agent;

import java.time.format.TextStyle;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;

import mealplanner.domain.AppState;
import mealplanner.domain.Catalogue;
import mealplanner.domain.IngredientAmount;
import mealplanner.domain.MealPlan;
import mealplanner.domain.MealSlot;
import mealplanner.domain.PlanRequest;
import mealplanner.domain.Quantities;
import mealplanner.domain.Recipe;

/**
 * Prompt text and output schemas for each agent task. Fixed facts (dates, slots, servings) are
 * stated as non-negotiable; the model only chooses among retrieved recipes or ingredients.
 */
public final class AgentPrompts {

    private AgentPrompts() {
    }

    public static final String SYSTEM = """
            You are the planning assistant inside MealPlanner, a desktop app for one home cook.
            Use ONLY data returned by the tools; never invent recipes, ingredients or IDs.
            Rules:
            - Work step by step. In each step call exactly ONE tool, then wait for its result.
            - Recipe IDs look like R07 and plan IDs like P01. Ingredient IDs look like "lentils" or "soy-milk". \
            Copy IDs exactly from tool results.
            - The saved dietary preferences are enforced by the application. searchRecipes only returns recipes that \
            already satisfy them, and you cannot relax them.
            - When you have enough information, stop calling tools and reply with the single word READY. \
            You will then be asked for the final answer as JSON.
            - If the request is ambiguous, contradictory, or needs something the tools cannot provide \
            (for example nutrition facts, prices or recipes that are not stored), reply READY and explain this \
            in the final answer using the status and message fields.
            """;

    private static final String[] STATUS = {"OK", "NO_MATCH", "NEEDS_CLARIFICATION", "UNSUPPORTED"};

    // ---- F05 recommend -------------------------------------------------------------------------

    public static String recommend(String request) {
        return """
                TASK: Recommend up to 5 stored recipes for the user's request below.
                Suggested approach: read the pantry, then call searchRecipes with filters that match the request \
                (for example maxMinutes or ingredientIds). Prefer recipes that use what is already in the pantry. \
                You may search more than once.
                User request: \"\"\"%s\"\"\"
                Final answer: status (OK, NO_MATCH if nothing suitable was found, NEEDS_CLARIFICATION, or UNSUPPORTED), \
                message (1-2 sentences for the user), appliedConstraints (limits you took from the request: maxMinutes, \
                use 0 for none; mustIncludeIngredientIds; avoidIngredientIds), and recommendations (best first, each with \
                recipeId and a short reason).""".formatted(request);
    }

    public static JsonNode recommendSchema() {
        return Schemas.object(new String[] {"status", "message", "appliedConstraints", "recommendations"},
                "status", Schemas.enumeration("Outcome.", STATUS),
                "message", Schemas.string("Short message for the user."),
                "appliedConstraints", Schemas.object(new String[] {"maxMinutes", "mustIncludeIngredientIds", "avoidIngredientIds"},
                        "maxMinutes", Schemas.integer("Time limit taken from the request, 0 if none."),
                        "mustIncludeIngredientIds", Schemas.array("Ingredient IDs the request requires.", Schemas.string(null)),
                        "avoidIngredientIds", Schemas.array("Ingredient IDs the request rules out.", Schemas.string(null))),
                "recommendations", Schemas.array("Best first.", Schemas.object(new String[] {"recipeId", "reason"},
                        "recipeId", Schemas.string("Recipe ID like R07 from searchRecipes."),
                        "reason", Schemas.string("Why it fits, one sentence.")), 5));
    }

    // ---- F06 plan --------------------------------------------------------------------------------

    public static String plan(PlanRequest request, String wishes) {
        StringBuilder meals = new StringBuilder();
        for (int day = 1; day <= request.days(); day++) {
            var date = request.startDate().plusDays(day - 1L);
            meals.append("  day ").append(day).append(" = ")
                    .append(date.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH)).append(' ').append(date)
                    .append(": ");
            boolean first = true;
            for (MealSlot slot : request.slots()) {
                meals.append(first ? "" : ", ").append(slot.name());
                first = false;
            }
            meals.append('\n');
        }
        return """
                TASK: Choose one stored recipe for every meal of a new meal plan.
                Fixed by the application (you cannot change these): %d day(s), %d serving(s) per meal, \
                repeats are %s.
                Meals to fill, exactly one recipe each:
                %s\
                User wishes (may be empty): \"\"\"%s\"\"\"
                Suggested approach: read the pantry, search for suitable recipes (you may search several times, \
                for example quick breakfast dishes), then call checkPlan with your COMPLETE selection and fix any \
                problems it reports before replying READY. Choose dishes that suit the meal (breakfast-style dishes \
                for breakfast) and prefer recipes that use pantry items.
                Final answer: status, message, explanation (2-4 sentences on why this plan fits), and entries \
                (one per meal: day number, slot, recipeId).""".formatted(request.days(), request.servings(),
                request.noRepeat() ? "FORBIDDEN (every meal needs a different recipe)" : "allowed",
                meals, wishes == null ? "" : wishes);
    }

    public static JsonNode planSchema() {
        return Schemas.object(new String[] {"status", "message", "explanation", "entries"},
                "status", Schemas.enumeration("Outcome.", STATUS),
                "message", Schemas.string("Short message for the user."),
                "explanation", Schemas.string("Why this plan fits."),
                "entries", Schemas.array("One entry per meal.", mealEntrySchema()));
    }

    // ---- F07 revise --------------------------------------------------------------------------------

    public static String revise(AppState snapshot, MealPlan plan, String request) {
        String planId = snapshot.planAlias(plan.getId());
        StringBuilder days = new StringBuilder();
        for (int day = 1; day <= plan.getDays(); day++) {
            var date = plan.dateOf(day);
            days.append("  day ").append(day).append(" = ")
                    .append(date.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH)).append(' ').append(date).append('\n');
        }
        return """
                TASK: Revise saved meal plan %s according to the user's request. Replace ONLY the meals the request \
                asks to change, plus any meal that readPlan reports as no longer fitting the preferences. Keep \
                everything else. The dates, slots, servings and repeat rule cannot change.
                Plan days:
                %s\
                User request: \"\"\"%s\"\"\"
                Suggested approach: call readPlan with planId "%s", search for replacement recipes (replacements must \
                come from searchRecipes), call checkPlan with ONLY the replaced meals, fix any problems, then reply READY.
                If the request names a day or meal that is not in the plan, or it is unclear which meal to change, use \
                status NEEDS_CLARIFICATION and explain.
                Final answer: status, message, explanation, and replacements (only changed meals: day number, slot, \
                recipeId).""".formatted(planId, days, request, planId);
    }

    public static JsonNode reviseSchema() {
        return Schemas.object(new String[] {"status", "message", "explanation", "replacements"},
                "status", Schemas.enumeration("Outcome.", STATUS),
                "message", Schemas.string("Short message for the user."),
                "explanation", Schemas.string("What changed and why."),
                "replacements", Schemas.array("Only the changed meals.", mealEntrySchema()));
    }

    // ---- F08 substitute ----------------------------------------------------------------------------

    public static String substitute(AppState snapshot, Recipe recipe, String ingredientId, String request, Catalogue catalogue) {
        StringBuilder text = new StringBuilder();
        text.append("TASK: Suggest a replacement for one ingredient in a stored recipe.\n");
        text.append("Recipe ").append(snapshot.recipeAlias(recipe.getId())).append(" '").append(recipe.getTitle())
                .append("' (").append(recipe.getServings()).append(" servings, ").append(recipe.getMinutes()).append(" minutes)\n");
        text.append("Ingredients:\n");
        for (IngredientAmount amount : recipe.getIngredients()) {
            text.append("  - ").append(amount.ingredientId()).append(": ").append(Quantities.display(amount.quantity()))
                    .append(' ').append(catalogue.unitLabel(amount.ingredientId())).append('\n');
        }
        text.append("Steps:\n");
        for (int i = 0; i < recipe.getSteps().size(); i++) {
            text.append("  ").append(i + 1).append(". ").append(recipe.getSteps().get(i)).append('\n');
        }
        text.append("Ingredient to replace: ").append(ingredientId).append(" (").append(catalogue.nameOf(ingredientId))
                .append(", ").append(catalogue.unitLabel(ingredientId)).append(")\n");
        text.append("User request: \"\"\"").append(request).append("\"\"\"\n");
        text.append("""
                Suggested approach: call readProfile, then listIngredients (optionally with a query or diet) to find \
                a catalogue ingredient that fits the request, the user's diet and exclusions. The quantity must be a \
                positive number in the replacement's OWN unit; the application does not convert units. Rewrite only \
                the steps whose text mentions or depends on the replaced ingredient.
                If no catalogue ingredient is a sensible replacement, use status NO_MATCH and explain.
                Final answer: status, message, replacementIngredientId, quantity, stepChanges (stepNumber and full \
                newText, only for changed steps), and explanation.""");
        return text.toString();
    }

    public static JsonNode substituteSchema() {
        return Schemas.object(new String[] {"status", "message", "replacementIngredientId", "quantity", "stepChanges", "explanation"},
                "status", Schemas.enumeration("Outcome.", STATUS),
                "message", Schemas.string("Short message for the user."),
                "replacementIngredientId", Schemas.string("Catalogue ingredient ID from listIngredients."),
                "quantity", Schemas.number("Positive quantity in the replacement's canonical unit."),
                "stepChanges", Schemas.array("Only steps whose text changes.", Schemas.object(new String[] {"stepNumber", "newText"},
                        "stepNumber", Schemas.integer("1-based step number."),
                        "newText", Schemas.string("The complete new text of that step."))),
                "explanation", Schemas.string("Why this replacement works and what to watch for."));
    }

    private static JsonNode mealEntrySchema() {
        return Schemas.object(new String[] {"day", "slot", "recipeId"},
                "day", Schemas.integer("Day number starting at 1."),
                "slot", Schemas.enumeration("Meal slot.", "BREAKFAST", "LUNCH", "DINNER"),
                "recipeId", Schemas.string("Recipe ID like R07."));
    }
}
