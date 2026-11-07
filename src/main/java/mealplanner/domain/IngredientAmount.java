package mealplanner.domain;

import java.math.BigDecimal;

/**
 * Immutable quantity of one catalogue ingredient, always in that ingredient's canonical unit.
 * Because it is immutable, copying a list of amounts is enough for a deep copy.
 */
public record IngredientAmount(String ingredientId, BigDecimal quantity) {
}
