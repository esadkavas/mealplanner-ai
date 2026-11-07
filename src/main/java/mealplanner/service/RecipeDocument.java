package mealplanner.service;

import java.math.BigDecimal;
import java.util.List;

/**
 * The structured recipe format used for JSON import, the GUI/CLI recipe form and the seed file.
 * Boxed fields let validation report missing values instead of silently using zero.
 */
public record RecipeDocument(String title, Integer servings, Integer minutes,
                             List<Line> ingredients, List<String> steps) {

    /** One ingredient line: a catalogue ID and a quantity in that ingredient's canonical unit. */
    public record Line(String ingredientId, BigDecimal quantity) {
    }
}
