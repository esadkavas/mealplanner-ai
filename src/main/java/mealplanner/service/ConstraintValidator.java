package mealplanner.service;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import mealplanner.domain.Catalogue;
import mealplanner.domain.Diet;
import mealplanner.domain.Ingredient;
import mealplanner.domain.IngredientAmount;
import mealplanner.domain.MealEntry;
import mealplanner.domain.MealPlan;
import mealplanner.domain.MealSlot;
import mealplanner.domain.PreferenceProfile;
import mealplanner.domain.Quantities;
import mealplanner.domain.Recipe;

/**
 * Checks every supported constraint in one place. All services, the facade and the agent tools
 * reuse it, so the GUI, CLI and AI paths apply exactly the same rules.
 */
public class ConstraintValidator {

    public static final int MIN_SERVINGS = 1;
    public static final int MAX_SERVINGS = 12;
    public static final int MIN_MINUTES = 1;
    public static final int MAX_MINUTES = 240;
    public static final int MAX_TITLE_LENGTH = 120;
    public static final int MAX_STEP_LENGTH = 1000;
    public static final int MAX_INGREDIENTS = 50;
    public static final int MAX_STEPS = 40;
    public static final BigDecimal MAX_RECIPE_QUANTITY = new BigDecimal("100000");
    public static final BigDecimal MAX_PANTRY_QUANTITY = new BigDecimal("1000000");

    private final Catalogue catalogue;

    public ConstraintValidator(Catalogue catalogue) {
        this.catalogue = catalogue;
    }

    public Catalogue catalogue() {
        return catalogue;
    }

    // ---- F01 preferences ----------------------------------------------------------------------

    public ValidationResult profile(ProfileInput input) {
        ValidationResult result = ValidationResult.ok();
        if (Diet.parse(input.diet()) == null) {
            result.error("diet: '" + input.diet() + "' is not supported (use unrestricted, vegetarian or vegan).");
        }
        for (String id : input.excludedIds()) {
            if (!catalogue.contains(id)) {
                result.error("exclude: '" + id + "' is not a known ingredient ID.");
            }
        }
        wholeNumberInRange(result, "servings", input.servings(), MIN_SERVINGS, MAX_SERVINGS);
        wholeNumberInRange(result, "max-minutes", input.maxMinutes(), MIN_MINUTES, MAX_MINUTES);
        return result;
    }

    /** Converts already validated input into a profile. */
    public PreferenceProfile toProfile(ProfileInput input) {
        return new PreferenceProfile(Diet.parse(input.diet()), new HashSet<>(input.excludedIds()),
                Integer.parseInt(input.servings().trim()), Integer.parseInt(input.maxMinutes().trim()));
    }

    public ValidationResult profile(PreferenceProfile profile) {
        ValidationResult result = ValidationResult.ok();
        if (profile == null || profile.diet() == null) {
            return result.error("profile: diet is missing.");
        }
        for (String id : profile.excludedIds()) {
            if (!catalogue.contains(id)) {
                result.error("profile: excluded ingredient '" + id + "' is not in the catalogue.");
            }
        }
        if (profile.householdServings() < MIN_SERVINGS || profile.householdServings() > MAX_SERVINGS) {
            result.error("profile: servings must be between 1 and 12.");
        }
        if (profile.maxMinutes() < MIN_MINUTES || profile.maxMinutes() > MAX_MINUTES) {
            result.error("profile: max minutes must be between 1 and 240.");
        }
        return result;
    }

    // ---- F02 pantry ---------------------------------------------------------------------------

    public ValidationResult pantry(PantryInput input) {
        ValidationResult result = ValidationResult.ok();
        if (input.action() == null) {
            return result.error("action: choose SET or REMOVE.");
        }
        if (!catalogue.contains(input.ingredientId())) {
            result.error("ingredient: '" + input.ingredientId() + "' is not a known ingredient ID.");
        }
        if (input.action() == PantryInput.Action.SET) {
            BigDecimal quantity = Quantities.parse(input.quantity());
            if (quantity == null) {
                result.error("quantity: '" + input.quantity() + "' is not a number.");
            } else if (quantity.signum() < 0) {
                result.error("quantity: must not be negative.");
            } else if (quantity.compareTo(MAX_PANTRY_QUANTITY) > 0) {
                result.error("quantity: must be at most " + MAX_PANTRY_QUANTITY.toPlainString() + ".");
            }
        }
        return result;
    }

    // ---- F03/F08/F09 recipes ------------------------------------------------------------------

    /**
     * Structural checks always run. Eligibility checks (diet, exclusions, time limit) run when a
     * profile is given; they do not stop a recipe from being stored, only from being recommended
     * or planned.
     */
    public ValidationResult recipe(Recipe recipe, PreferenceProfile profile) {
        ValidationResult result = ValidationResult.ok();
        if (recipe == null) {
            return result.error("recipe: missing.");
        }
        String title = recipe.getTitle();
        if (title == null || title.isBlank()) {
            result.error("title: is required.");
        } else if (title.length() > MAX_TITLE_LENGTH) {
            result.error("title: must be at most " + MAX_TITLE_LENGTH + " characters.");
        }
        if (recipe.getServings() < MIN_SERVINGS || recipe.getServings() > MAX_SERVINGS) {
            result.error("servings: must be between 1 and 12 (was " + recipe.getServings() + ").");
        }
        if (recipe.getMinutes() < MIN_MINUTES || recipe.getMinutes() > MAX_MINUTES) {
            result.error("minutes: must be between 1 and 240 (was " + recipe.getMinutes() + ").");
        }
        List<IngredientAmount> ingredients = recipe.getIngredients();
        if (ingredients.isEmpty()) {
            result.error("ingredients: at least one ingredient is required.");
        } else if (ingredients.size() > MAX_INGREDIENTS) {
            result.error("ingredients: at most " + MAX_INGREDIENTS + " are allowed.");
        }
        Set<String> seen = new HashSet<>();
        for (IngredientAmount amount : ingredients) {
            String id = amount == null ? null : amount.ingredientId();
            if (id == null || !catalogue.contains(id)) {
                result.error("ingredients: '" + id + "' is not a known ingredient ID.");
                continue;
            }
            if (!seen.add(id)) {
                result.error("ingredients: '" + id + "' is listed more than once.");
            }
            BigDecimal quantity = amount.quantity();
            if (quantity == null || quantity.signum() <= 0) {
                result.error("ingredients: quantity for '" + id + "' must be positive.");
            } else if (quantity.compareTo(MAX_RECIPE_QUANTITY) > 0) {
                result.error("ingredients: quantity for '" + id + "' is unrealistically large.");
            }
        }
        List<String> steps = recipe.getSteps();
        if (steps.isEmpty()) {
            result.error("steps: at least one instruction step is required.");
        } else if (steps.size() > MAX_STEPS) {
            result.error("steps: at most " + MAX_STEPS + " are allowed.");
        }
        for (int i = 0; i < steps.size(); i++) {
            String step = steps.get(i);
            if (step == null || step.isBlank()) {
                result.error("steps: step " + (i + 1) + " is empty.");
            } else if (step.length() > MAX_STEP_LENGTH) {
                result.error("steps: step " + (i + 1) + " is too long.");
            }
        }
        if (profile != null && result.isStructurallyValid()) {
            result.merge(eligibility(recipe, profile));
        }
        return result;
    }

    /** Eligibility only: diet tags, excluded ingredient IDs and the saved time limit. */
    public ValidationResult eligibility(Recipe recipe, PreferenceProfile profile) {
        ValidationResult result = ValidationResult.ok();
        for (IngredientAmount amount : recipe.getIngredients()) {
            Ingredient ingredient = catalogue.find(amount.ingredientId()).orElse(null);
            if (ingredient == null) {
                continue;
            }
            if (!ingredient.allows(profile.diet())) {
                result.violation("'" + recipe.getTitle() + "' contains " + ingredient.name()
                        + ", which is not " + profile.diet().label() + ".");
            }
            if (profile.excludedIds().contains(ingredient.id())) {
                result.violation("'" + recipe.getTitle() + "' contains excluded ingredient " + ingredient.id() + ".");
            }
        }
        if (recipe.getMinutes() > profile.maxMinutes()) {
            result.violation("'" + recipe.getTitle() + "' takes " + recipe.getMinutes()
                    + " minutes; the limit is " + profile.maxMinutes() + ".");
        }
        return result;
    }

    public boolean isEligible(Recipe recipe, PreferenceProfile profile) {
        return recipe(recipe, profile).isValid();
    }

    // ---- F06/F07 plans ------------------------------------------------------------------------

    /** Complete plan check: coverage, servings, snapshot structure, eligibility and repetition. */
    public ValidationResult plan(MealPlan plan, PreferenceProfile profile) {
        ValidationResult result = ValidationResult.ok();
        if (plan == null) {
            return result.error("plan: missing.");
        }
        if (plan.getStartDate() == null) {
            result.error("plan: start date is missing.");
        }
        if (plan.getDays() < 1 || plan.getDays() > 7) {
            result.error("plan: must cover 1 to 7 days.");
        }
        if (plan.getSlots().isEmpty()) {
            result.error("plan: at least one meal slot is required.");
        }
        Set<String> filled = new HashSet<>();
        for (MealEntry entry : plan.getMeals()) {
            String key = entry.getDay() + "/" + entry.getSlot();
            String where = "Day " + entry.getDay() + " " + (entry.getSlot() == null ? "?" : entry.getSlot().label());
            if (entry.getDay() < 1 || entry.getDay() > plan.getDays() || !plan.getSlots().contains(entry.getSlot())) {
                result.error(where + " was not requested.");
            }
            if (!filled.add(key)) {
                result.error(where + " appears more than once.");
            }
            if (entry.getServings() < MIN_SERVINGS || entry.getServings() > MAX_SERVINGS) {
                result.error(where + ": servings must be between 1 and 12.");
            }
            ValidationResult snapshot = recipe(entry.getRecipeSnapshot(), profile);
            for (String message : snapshot.structuralErrors()) {
                result.error(where + ": " + message);
            }
            for (String message : snapshot.eligibilityViolations()) {
                result.violation(where + ": " + message);
            }
        }
        for (int day = 1; day <= plan.getDays(); day++) {
            for (MealSlot slot : plan.getSlots()) {
                if (!filled.contains(day + "/" + slot)) {
                    result.error("Day " + day + " " + slot.label() + " has no meal.");
                }
            }
        }
        if (plan.isNoRepeat()) {
            Set<Object> used = new HashSet<>();
            for (MealEntry entry : plan.getMeals()) {
                Recipe snapshot = entry.getRecipeSnapshot();
                if (snapshot != null && !used.add(snapshot.getId())) {
                    result.violation("'" + snapshot.getTitle() + "' is repeated, but this plan forbids repeats.");
                }
            }
        }
        return result;
    }

    // ---- helpers ------------------------------------------------------------------------------

    private static void wholeNumberInRange(ValidationResult result, String field, String text, int min, int max) {
        if (text == null || text.isBlank()) {
            result.error(field + ": is required.");
            return;
        }
        try {
            int value = Integer.parseInt(text.trim());
            if (value < min || value > max) {
                result.error(field + ": must be between " + min + " and " + max + " (was " + value + ").");
            }
        } catch (NumberFormatException e) {
            result.error(field + ": '" + text + "' is not a whole number.");
        }
    }
}
