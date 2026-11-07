package mealplanner.service;

/** One pantry change: SET an ingredient's quantity, or REMOVE its entry. */
public record PantryInput(Action action, String ingredientId, String quantity) {

    public enum Action { SET, REMOVE }

    public static PantryInput set(String ingredientId, String quantity) {
        return new PantryInput(Action.SET, ingredientId, quantity);
    }

    public static PantryInput remove(String ingredientId) {
        return new PantryInput(Action.REMOVE, ingredientId, null);
    }
}
