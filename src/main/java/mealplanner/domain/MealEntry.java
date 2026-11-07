package mealplanner.domain;

/**
 * One meal in a plan. It owns its own recipe snapshot, so later edits to the stored recipe
 * collection can never change a saved plan or its shopping list.
 */
public class MealEntry {

    private int day;
    private MealSlot slot;
    private int servings;
    private Recipe recipeSnapshot;

    private MealEntry() {
        // for JSON
    }

    public MealEntry(int day, MealSlot slot, int servings, Recipe recipeSnapshot) {
        this.day = day;
        this.slot = slot;
        this.servings = servings;
        this.recipeSnapshot = recipeSnapshot;
    }

    public MealEntry deepCopy() {
        return new MealEntry(day, slot, servings, recipeSnapshot == null ? null : recipeSnapshot.deepCopy());
    }

    public int getDay() {
        return day;
    }

    public MealSlot getSlot() {
        return slot;
    }

    public int getServings() {
        return servings;
    }

    public Recipe getRecipeSnapshot() {
        return recipeSnapshot;
    }
}
