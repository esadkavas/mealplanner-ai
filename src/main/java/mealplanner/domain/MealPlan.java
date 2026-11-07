package mealplanner.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * A meal plan of 1 to 7 days. Instances are created by {@code MealPlanBuilder}.
 *
 * <p>Prototype pattern: {@link #deepCopy()} copies every entry and its recipe snapshot, which
 * lets plan revision work on a copy while the saved source plan stays unchanged.</p>
 */
public class MealPlan {

    private UUID id;
    private LocalDate startDate;
    private int days;
    private EnumSet<MealSlot> slots = EnumSet.noneOf(MealSlot.class);
    private ArrayList<MealEntry> meals = new ArrayList<>();
    private boolean noRepeat;
    /** The saved plan this one was revised from, or null. */
    private UUID derivedFrom;
    /** The model's explanation shown with the plan. */
    private String explanation;

    private MealPlan() {
        // for JSON
    }

    /** Package-private: only the builder (and deepCopy) create plans. */
    MealPlan(UUID id, LocalDate startDate, int days, Set<MealSlot> slots, List<MealEntry> meals, boolean noRepeat) {
        this.id = id;
        this.startDate = startDate;
        this.days = days;
        this.slots = slots.isEmpty() ? EnumSet.noneOf(MealSlot.class) : EnumSet.copyOf(slots);
        this.meals = new ArrayList<>(meals);
        this.noRepeat = noRepeat;
    }

    public MealPlan deepCopy() {
        List<MealEntry> copiedMeals = new ArrayList<>();
        for (MealEntry entry : meals) {
            copiedMeals.add(entry.deepCopy());
        }
        MealPlan copy = new MealPlan(id, startDate, days, slots, copiedMeals, noRepeat);
        copy.derivedFrom = derivedFrom;
        copy.explanation = explanation;
        return copy;
    }

    /** Read-only view of the entries. */
    public List<MealEntry> getMeals() {
        return Collections.unmodifiableList(meals);
    }

    public Optional<MealEntry> find(int day, MealSlot slot) {
        return meals.stream().filter(m -> m.getDay() == day && m.getSlot() == slot).findFirst();
    }

    public LocalDate dateOf(int day) {
        return startDate.plusDays(day - 1L);
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public int getDays() {
        return days;
    }

    public Set<MealSlot> getSlots() {
        return Collections.unmodifiableSet(slots);
    }

    public boolean isNoRepeat() {
        return noRepeat;
    }

    public UUID getDerivedFrom() {
        return derivedFrom;
    }

    public void setDerivedFrom(UUID derivedFrom) {
        this.derivedFrom = derivedFrom;
    }

    public String getExplanation() {
        return explanation;
    }

    public void setExplanation(String explanation) {
        this.explanation = explanation;
    }
}
