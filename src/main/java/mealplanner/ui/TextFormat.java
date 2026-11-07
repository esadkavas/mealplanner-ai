package mealplanner.ui;

import java.math.BigDecimal;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import mealplanner.domain.AppState;
import mealplanner.domain.Catalogue;
import mealplanner.domain.IngredientAmount;
import mealplanner.domain.MealEntry;
import mealplanner.domain.MealPlan;
import mealplanner.domain.MealSlot;
import mealplanner.domain.PreferenceProfile;
import mealplanner.domain.Quantities;
import mealplanner.domain.Recipe;
import mealplanner.facade.Proposal;
import mealplanner.facade.ProposalKind;
import mealplanner.facade.Result;
import mealplanner.service.PlanService;
import mealplanner.service.RecipeService;
import mealplanner.service.RecommendationList;
import mealplanner.service.ShoppingList;

/** Plain-text rendering shared by the CLI and the GUI's read-only text panes. */
public final class TextFormat {

    private TextFormat() {
    }

    public static String profile(PreferenceProfile p) {
        return "Diet: " + p.diet().label()
                + "\nExcluded ingredients: " + (p.excludedIds().isEmpty() ? "none" : String.join(", ", p.excludedIds()))
                + "\nHousehold servings: " + p.householdServings()
                + "\nMaximum preparation time: " + p.maxMinutes() + " minutes";
    }

    public static String pantry(Map<String, BigDecimal> pantry, Catalogue catalogue) {
        if (pantry.isEmpty()) {
            return "The pantry is empty.";
        }
        StringBuilder text = new StringBuilder();
        pantry.forEach((id, qty) -> text.append(String.format(Locale.ROOT, "%-16s %10s %-5s %s%n", id,
                Quantities.display(qty), catalogue.unitLabel(id), qty.signum() == 0 ? "(unavailable)" : "")));
        return text.toString().stripTrailing();
    }

    public static String recipeLine(AppState state, Recipe recipe, boolean eligible) {
        return String.format(Locale.ROOT, "%-4s %-40s %2d serv  %3d min%s", state.recipeAlias(recipe.getId()),
                recipe.getTitle(), recipe.getServings(), recipe.getMinutes(), eligible ? "" : "  [ineligible]");
    }

    public static String recipe(AppState state, Recipe recipe, Catalogue catalogue, boolean eligible) {
        StringBuilder text = new StringBuilder();
        String alias = recipe.getId() != null && state.getRecipes().containsKey(recipe.getId())
                ? state.recipeAlias(recipe.getId()) + "  " : "";
        text.append(alias).append(recipe.getTitle()).append('\n');
        text.append(recipe.getServings()).append(" servings, ").append(recipe.getMinutes()).append(" minutes");
        if (!eligible) {
            text.append("   [ineligible for your current preferences]");
        }
        text.append("\n\nIngredients:\n");
        for (IngredientAmount amount : recipe.getIngredients()) {
            text.append(String.format(Locale.ROOT, "  - %-34s %8s %s%n", catalogue.nameOf(amount.ingredientId())
                    + " (" + amount.ingredientId() + ")", Quantities.display(amount.quantity()),
                    catalogue.unitLabel(amount.ingredientId())));
        }
        text.append("\nSteps:\n");
        for (int i = 0; i < recipe.getSteps().size(); i++) {
            text.append("  ").append(i + 1).append(". ").append(recipe.getSteps().get(i)).append('\n');
        }
        if (recipe.getNote() != null) {
            text.append("\nNote: ").append(recipe.getNote()).append('\n');
        }
        if (recipe.getDerivedFrom() != null && state.getRecipes().containsKey(recipe.getDerivedFrom())) {
            text.append("Variant of ").append(state.recipeAlias(recipe.getDerivedFrom())).append('\n');
        }
        return text.toString().stripTrailing();
    }

    public static String planLine(AppState state, MealPlan plan) {
        List<String> slots = new ArrayList<>();
        for (MealSlot slot : MealSlot.values()) {
            if (plan.getSlots().contains(slot)) {
                slots.add(slot.label().toLowerCase(Locale.ROOT));
            }
        }
        return String.format(Locale.ROOT, "%-4s from %s, %d day(s), %s%s%s", state.planAlias(plan.getId()),
                plan.getStartDate(), plan.getDays(), String.join("+", slots), plan.isNoRepeat() ? ", no repeats" : "",
                plan.getDerivedFrom() != null && state.getPlans().containsKey(plan.getDerivedFrom())
                        ? ", revised from " + state.planAlias(plan.getDerivedFrom()) : "");
    }

    public static String plan(AppState state, MealPlan plan) {
        StringBuilder text = new StringBuilder();
        String alias = plan.getId() != null && state.getPlans().containsKey(plan.getId())
                ? state.planAlias(plan.getId()) : "New plan";
        int servings = plan.getMeals().isEmpty() ? 0 : plan.getMeals().get(0).getServings();
        text.append(alias).append(" - starts ").append(plan.getStartDate()).append(", ").append(plan.getDays())
                .append(" day(s), ").append(servings).append(" serving(s) per meal")
                .append(plan.isNoRepeat() ? ", no repeats" : "").append('\n');
        for (int day = 1; day <= plan.getDays(); day++) {
            var date = plan.dateOf(day);
            text.append(String.format(Locale.ROOT, "%nDay %d  %s %s%n", day,
                    date.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH), date));
            for (MealSlot slot : MealSlot.values()) {
                MealEntry entry = plan.find(day, slot).orElse(null);
                if (entry != null) {
                    text.append(String.format(Locale.ROOT, "  %-10s %s (%d min)%n", slot.label(),
                            entry.getRecipeSnapshot().getTitle(), entry.getRecipeSnapshot().getMinutes()));
                }
            }
        }
        if (plan.getExplanation() != null && !plan.getExplanation().isBlank()) {
            text.append("\nWhy: ").append(plan.getExplanation()).append('\n');
        }
        return text.toString().stripTrailing();
    }

    public static String shopping(AppState state, ShoppingList list) {
        StringBuilder text = new StringBuilder(ShoppingList.LABEL + " for " + state.planAlias(list.sourcePlanId())
                + " (pantry stock is NOT deducted)\n\n");
        for (ShoppingList.Line line : list.lines()) {
            text.append(String.format(Locale.ROOT, "  %-34s %10s %s%n", line.name() + " (" + line.ingredientId() + ")",
                    Quantities.display(line.total()), line.unit()));
        }
        return text.toString().stripTrailing();
    }

    public static String recommendations(RecommendationList list, Catalogue catalogue) {
        StringBuilder text = new StringBuilder();
        if (!list.message().isBlank()) {
            text.append(list.message()).append("\n");
        }
        text.append("Constraints applied from your request: ").append(list.appliedConstraints()).append("\n");
        int rank = 1;
        for (RecommendationList.Recommendation rec : list.items()) {
            Recipe r = rec.recipe();
            text.append(String.format(Locale.ROOT, "%n%d. %s  %s (%d servings, %d min)%n   Why: %s%n", rank++,
                    rec.alias(), r.getTitle(), r.getServings(), r.getMinutes(), rec.reason()));
            if (rec.missing().isEmpty()) {
                text.append("   Missing from pantry: nothing\n");
            } else {
                List<String> parts = new ArrayList<>();
                for (RecipeService.Shortage s : rec.missing()) {
                    parts.add(s.ingredientId() + " " + Quantities.display(s.missing()) + " " + catalogue.unitLabel(s.ingredientId()));
                }
                text.append("   Missing from pantry (at base servings): ").append(String.join(", ", parts)).append('\n');
            }
        }
        text.append(trace(list.agentTrace()));
        return text.toString().stripTrailing();
    }

    public static String proposal(AppState state, Proposal proposal, Catalogue catalogue) {
        StringBuilder text = new StringBuilder();
        text.append("PREVIEW (").append(describeKind(proposal.kind())).append(") - not saved yet\n\n");
        if (proposal.kind() == ProposalKind.PLAN) {
            if (!proposal.changes().isEmpty()) {
                text.append("Changed meals:\n");
                for (PlanService.SlotChange change : proposal.changes()) {
                    text.append(String.format(Locale.ROOT, "  * Day %d %s: %s  ->  %s%n", change.day(),
                            change.slot().label(), change.before(), change.after()));
                }
                text.append('\n');
            }
            text.append(plan(state, proposal.plan()));
        } else {
            text.append(recipe(state, proposal.recipe(), catalogue, proposal.warnings().stream()
                    .noneMatch(w -> w.startsWith("Ineligible"))));
            if (proposal.explanation() != null && !proposal.explanation().isBlank()
                    && proposal.kind() == ProposalKind.SUBSTITUTE_RECIPE) {
                text.append("\n\nWhy: ").append(proposal.explanation());
            }
        }
        for (String warning : proposal.warnings()) {
            text.append("\n\nWarning: ").append(warning);
        }
        text.append(trace(proposal.agentTrace()));
        return text.toString().stripTrailing();
    }

    public static String trace(List<String> trace) {
        if (trace == null || trace.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder("\n\nAI agent steps:\n");
        trace.forEach(line -> text.append("  ").append(line).append('\n'));
        return text.toString();
    }

    public static String errors(Result<?> result) {
        StringBuilder text = new StringBuilder();
        for (String error : result.errors()) {
            text.append("Error: ").append(error).append('\n');
        }
        text.append(trace(result.details()));
        return text.toString().stripTrailing();
    }

    private static String describeKind(ProposalKind kind) {
        return switch (kind) {
            case IMPORT_RECIPE -> "new recipe";
            case SCALE_RECIPE -> "scaled recipe variant";
            case SUBSTITUTE_RECIPE -> "recipe variant with substitution";
            case PLAN -> "meal plan";
        };
    }
}
