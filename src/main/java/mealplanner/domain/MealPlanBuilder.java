package mealplanner.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Builder pattern: assembles a complete {@link MealPlan} step by step.
 *
 * <p>{@link #start(PlanRequest)} records the fixed request, {@link #addMeal} adds one entry at a
 * time and rejects duplicate, unrequested or resized slots, and {@link #build()} returns a plan
 * only when every requested slot is filled. Callers never see a partially built plan.
 * {@code PlanService} acts as the director.</p>
 */
public final class MealPlanBuilder {

    private final PlanRequest request;
    private final List<MealEntry> meals = new ArrayList<>();
    private UUID derivedFrom;
    private String explanation;

    private MealPlanBuilder(PlanRequest request) {
        this.request = request;
    }

    public static MealPlanBuilder start(PlanRequest request) {
        if (request == null || request.startDate() == null) {
            throw new IllegalArgumentException("A plan needs a start date.");
        }
        if (request.days() < 1 || request.days() > 7) {
            throw new IllegalArgumentException("A plan must cover 1 to 7 days.");
        }
        if (request.slots().isEmpty()) {
            throw new IllegalArgumentException("Choose at least one meal slot.");
        }
        if (request.servings() < 1 || request.servings() > 12) {
            throw new IllegalArgumentException("Servings must be between 1 and 12.");
        }
        return new MealPlanBuilder(request);
    }

    /** Adds one meal; the recipe is deep-copied into an independent snapshot. */
    public MealPlanBuilder addMeal(int day, MealSlot slot, Recipe recipe, int servings) {
        if (day < 1 || day > request.days()) {
            throw new IllegalArgumentException("Day " + day + " is outside the plan (1-" + request.days() + ").");
        }
        if (slot == null || !request.slots().contains(slot)) {
            throw new IllegalArgumentException("Slot " + slot + " was not requested for this plan.");
        }
        if (servings != request.servings()) {
            throw new IllegalArgumentException("Servings are fixed at " + request.servings() + " for this plan.");
        }
        if (recipe == null) {
            throw new IllegalArgumentException("Day " + day + " " + slot.label() + " has no recipe.");
        }
        boolean duplicate = meals.stream().anyMatch(m -> m.getDay() == day && m.getSlot() == slot);
        if (duplicate) {
            throw new IllegalArgumentException("Day " + day + " " + slot.label() + " was filled twice.");
        }
        meals.add(new MealEntry(day, slot, servings, recipe.deepCopy()));
        return this;
    }

    public MealPlanBuilder derivedFrom(UUID sourcePlanId) {
        this.derivedFrom = sourcePlanId;
        return this;
    }

    public MealPlanBuilder explanation(String text) {
        this.explanation = text;
        return this;
    }

    /** Returns the plan only if every requested day/slot pair is present. */
    public MealPlan build() {
        List<String> missing = new ArrayList<>();
        for (int day = 1; day <= request.days(); day++) {
            for (MealSlot slot : request.slots()) {
                final int d = day;
                if (meals.stream().noneMatch(m -> m.getDay() == d && m.getSlot() == slot)) {
                    missing.add("day " + day + " " + slot.label());
                }
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("The plan is incomplete; missing " + String.join(", ", missing) + ".");
        }
        List<MealEntry> ordered = new ArrayList<>(meals);
        ordered.sort((a, b) -> a.getDay() != b.getDay()
                ? Integer.compare(a.getDay(), b.getDay())
                : a.getSlot().compareTo(b.getSlot()));
        MealPlan plan = new MealPlan(UUID.randomUUID(), request.startDate(), request.days(),
                request.slots(), ordered, request.noRepeat());
        plan.setDerivedFrom(derivedFrom);
        plan.setExplanation(explanation);
        return plan;
    }
}
