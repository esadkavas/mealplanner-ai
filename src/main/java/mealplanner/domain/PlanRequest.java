package mealplanner.domain;

import java.time.LocalDate;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * The fixed shape of a plan: start date, number of days, slots, servings and the no-repeat flag.
 * It is decided before any model call; the model can only choose recipes for these slots.
 */
public record PlanRequest(LocalDate startDate, int days, Set<MealSlot> slots, int servings, boolean noRepeat) {

    public PlanRequest {
        slots = slots == null || slots.isEmpty()
                ? Collections.unmodifiableSet(EnumSet.noneOf(MealSlot.class))
                : Collections.unmodifiableSet(EnumSet.copyOf(slots));
    }

    public int mealCount() {
        return days * slots.size();
    }

    /** The request that reproduces an existing plan's fixed fields (used when revising). */
    public static PlanRequest of(MealPlan plan) {
        int servings = plan.getMeals().isEmpty() ? 1 : plan.getMeals().get(0).getServings();
        return new PlanRequest(plan.getStartDate(), plan.getDays(), plan.getSlots(), servings, plan.isNoRepeat());
    }
}
