package mealplanner.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import mealplanner.domain.AppState;
import mealplanner.domain.MealEntry;
import mealplanner.domain.MealPlan;
import mealplanner.domain.MealPlanBuilder;
import mealplanner.domain.MealSlot;
import mealplanner.domain.PlanRequest;
import mealplanner.domain.PreferenceProfile;
import mealplanner.domain.Recipe;

/**
 * Plan rules (F06, F07). PlanService is the director of the Builder pattern: it resolves the
 * model's recipe choices and tells {@link MealPlanBuilder} what to add, using the fixed
 * {@link PlanRequest} so the model cannot change dates, slots or servings.
 */
public class PlanService {

    private final ConstraintValidator validator;

    public PlanService(ConstraintValidator validator) {
        this.validator = validator;
    }

    public ValidationResult validate(MealPlan plan, PreferenceProfile profile) {
        return validator.plan(plan, profile);
    }

    public ValidationResult validateRequest(PlanRequest request) {
        ValidationResult result = ValidationResult.ok();
        if (request.startDate() == null) {
            result.error("start: a start date is required (YYYY-MM-DD).");
        }
        if (request.days() < 1 || request.days() > 7) {
            result.error("days: must be between 1 and 7.");
        }
        if (request.slots().isEmpty()) {
            result.error("slots: choose at least one of breakfast, lunch, dinner.");
        }
        if (request.servings() < 1 || request.servings() > 12) {
            result.error("servings: must be between 1 and 12.");
        }
        return result;
    }

    /** Recipes that satisfy the current profile, i.e. the only legal plan choices. */
    public List<Recipe> eligibleRecipes(AppState state) {
        List<Recipe> eligible = new ArrayList<>();
        for (Recipe recipe : state.getRecipes().values()) {
            if (validator.isEligible(recipe, state.getProfile())) {
                eligible.add(recipe);
            }
        }
        return eligible;
    }

    /**
     * Deterministic pre-check before any model call: explains impossible requests (no eligible
     * recipes, or too few for a no-repeat plan) instead of letting the agent try.
     */
    public void checkFeasible(AppState state, PlanRequest request) {
        int eligible = eligibleRecipes(state).size();
        if (eligible == 0) {
            throw new DomainException("No stored recipe fits your current preferences, so no plan can be made. "
                    + "Relax the profile or add eligible recipes.");
        }
        if (request.noRepeat() && eligible < request.mealCount()) {
            throw new DomainException("A no-repeat plan needs " + request.mealCount() + " different recipes, but only "
                    + eligible + " stored recipes fit your preferences. Allow repeats, choose fewer days or slots, "
                    + "or add recipes.");
        }
    }

    /**
     * Builds a complete plan from the agent's draft. Every recipe must exist in the snapshot and
     * must have been retrieved through the tools; the result must pass full validation.
     */
    public MealPlan fromDraft(PlanDraft draft, AppState state, PlanRequest request) {
        List<String> problems = new ArrayList<>();
        MealPlanBuilder builder = MealPlanBuilder.start(request);
        for (PlanDraft.Entry entry : draft.entries()) {
            MealSlot slot = MealSlot.parse(entry.slot());
            String where = "Day " + entry.day() + " " + (slot == null ? entry.slot() : slot.label());
            Recipe recipe = state.findRecipe(entry.recipeRef()).orElse(null);
            if (slot == null) {
                problems.add(where + ": unknown meal slot.");
                continue;
            }
            if (recipe == null) {
                problems.add(where + ": recipe '" + entry.recipeRef() + "' does not exist.");
                continue;
            }
            if (!draft.retrievedRecipeIds().contains(recipe.getId())) {
                problems.add(where + ": recipe " + state.recipeAlias(recipe.getId())
                        + " was not retrieved through searchRecipes, so it cannot be used.");
                continue;
            }
            try {
                builder.addMeal(entry.day(), slot, recipe, request.servings());
            } catch (IllegalArgumentException e) {
                problems.add(e.getMessage());
            }
        }
        MealPlan plan = null;
        if (problems.isEmpty()) {
            try {
                plan = builder.explanation(draft.explanation()).build();
            } catch (IllegalStateException e) {
                problems.add(e.getMessage());
            }
        }
        if (!problems.isEmpty()) {
            throw new DomainException(problems);
        }
        ValidationResult check = validate(plan, state.getProfile());
        if (!check.isValid()) {
            throw new DomainException(check.allMessages());
        }
        return plan;
    }

    /**
     * F07: copies the saved plan (Prototype), then rebuilds it with only the requested slots
     * replaced. Dates, servings, noRepeat and all other meals are kept. The complete result is
     * validated against the current profile, including the unchanged meals.
     */
    public MealPlan revise(MealPlan source, PlanDraft replacements, AppState state) {
        MealPlan copy = source.deepCopy();
        PlanRequest request = PlanRequest.of(copy);
        List<String> problems = new ArrayList<>();
        Map<String, Recipe> chosen = new HashMap<>();
        if (replacements.entries().isEmpty()) {
            throw new DomainException("The revision did not replace any meal.");
        }
        for (PlanDraft.Entry entry : replacements.entries()) {
            MealSlot slot = MealSlot.parse(entry.slot());
            String where = "Day " + entry.day() + " " + (slot == null ? entry.slot() : slot.label());
            if (slot == null || copy.find(entry.day(), slot).isEmpty()) {
                problems.add(where + " is not a meal in this plan.");
                continue;
            }
            Recipe recipe = state.findRecipe(entry.recipeRef()).orElse(null);
            if (recipe == null) {
                problems.add(where + ": recipe '" + entry.recipeRef() + "' does not exist.");
                continue;
            }
            if (!replacements.retrievedRecipeIds().contains(recipe.getId())) {
                problems.add(where + ": recipe " + state.recipeAlias(recipe.getId())
                        + " was not retrieved through searchRecipes, so it cannot be used.");
                continue;
            }
            if (chosen.put(entry.day() + "/" + slot, recipe) != null) {
                problems.add(where + " was replaced twice.");
            }
        }
        if (!problems.isEmpty()) {
            throw new DomainException(problems);
        }
        MealPlanBuilder builder = MealPlanBuilder.start(request)
                .derivedFrom(source.getId())
                .explanation(replacements.explanation());
        boolean changed = false;
        for (MealEntry entry : copy.getMeals()) {
            Recipe replacement = chosen.get(entry.getDay() + "/" + entry.getSlot());
            if (replacement != null && !replacement.getId().equals(entry.getRecipeSnapshot().getId())) {
                changed = true;
            }
            builder.addMeal(entry.getDay(), entry.getSlot(),
                    replacement != null ? replacement : entry.getRecipeSnapshot(), entry.getServings());
        }
        if (!changed) {
            throw new DomainException("The revision chose the same recipes, so nothing would change.");
        }
        MealPlan revised = builder.build();
        ValidationResult check = validate(revised, state.getProfile());
        if (!check.isValid()) {
            throw new DomainException(check.allMessages());
        }
        return revised;
    }

    /** Lists the slots whose recipe differs between two plans of the same shape. */
    public List<SlotChange> diff(MealPlan before, MealPlan after) {
        List<SlotChange> changes = new ArrayList<>();
        for (MealEntry entry : after.getMeals()) {
            MealEntry old = before.find(entry.getDay(), entry.getSlot()).orElse(null);
            UUID oldId = old == null ? null : old.getRecipeSnapshot().getId();
            if (old == null || !entry.getRecipeSnapshot().getId().equals(oldId)) {
                changes.add(new SlotChange(entry.getDay(), entry.getSlot(),
                        old == null ? "(none)" : old.getRecipeSnapshot().getTitle(),
                        entry.getRecipeSnapshot().getTitle()));
            }
        }
        return changes;
    }

    public record SlotChange(int day, MealSlot slot, String before, String after) {
    }
}
