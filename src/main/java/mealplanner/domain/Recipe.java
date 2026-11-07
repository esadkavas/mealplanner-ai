package mealplanner.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * A structured recipe. Quantities are for {@link #getServings()} base servings.
 *
 * <p>Prototype pattern: {@link #deepCopy()} returns an independent copy whose ingredient and
 * step lists can be changed without affecting this object. Scaling, substitution and meal-plan
 * snapshots all start from a deep copy so the source recipe is never modified.</p>
 */
public class Recipe {

    private UUID id;
    private String title;
    private int servings;
    private int minutes;
    private ArrayList<IngredientAmount> ingredients = new ArrayList<>();
    private ArrayList<String> steps = new ArrayList<>();
    /** The stored recipe this one was scaled or substituted from, or null for an original. */
    private UUID derivedFrom;
    /** Optional note shown with variants, e.g. that cooking time may need judgement. */
    private String note;

    private Recipe() {
        // for JSON
    }

    public Recipe(UUID id, String title, int servings, int minutes,
                  List<IngredientAmount> ingredients, List<String> steps) {
        this.id = id;
        this.title = title;
        this.servings = servings;
        this.minutes = minutes;
        this.ingredients = new ArrayList<>(ingredients == null ? List.of() : ingredients);
        this.steps = new ArrayList<>(steps == null ? List.of() : steps);
    }

    /** Prototype operation: copies every mutable child so the result is fully independent. */
    public Recipe deepCopy() {
        Recipe copy = new Recipe(id, title, servings, minutes, ingredients, steps);
        copy.derivedFrom = derivedFrom;
        copy.note = note;
        return copy;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public int getServings() {
        return servings;
    }

    public void setServings(int servings) {
        this.servings = servings;
    }

    public int getMinutes() {
        return minutes;
    }

    public void setMinutes(int minutes) {
        this.minutes = minutes;
    }

    /** Read-only view; use {@link #setIngredients(List)} on a copy to change it. */
    public List<IngredientAmount> getIngredients() {
        return Collections.unmodifiableList(ingredients);
    }

    public void setIngredients(List<IngredientAmount> ingredients) {
        this.ingredients = new ArrayList<>(ingredients);
    }

    /** Read-only view; use {@link #setSteps(List)} on a copy to change it. */
    public List<String> getSteps() {
        return Collections.unmodifiableList(steps);
    }

    public void setSteps(List<String> steps) {
        this.steps = new ArrayList<>(steps);
    }

    public UUID getDerivedFrom() {
        return derivedFrom;
    }

    public void setDerivedFrom(UUID derivedFrom) {
        this.derivedFrom = derivedFrom;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public boolean usesIngredient(String ingredientId) {
        return ingredients.stream().anyMatch(a -> a.ingredientId().equals(ingredientId));
    }
}
