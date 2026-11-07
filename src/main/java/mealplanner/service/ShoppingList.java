package mealplanner.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * F10 output: one line per canonical ingredient ID. Pantry stock is NOT deducted, so the list is
 * always labelled "Total ingredients required".
 */
public record ShoppingList(UUID sourcePlanId, List<Line> lines) {

    public static final String LABEL = "Total ingredients required";

    public ShoppingList {
        lines = List.copyOf(lines);
    }

    public record Line(String ingredientId, String name, String unit, BigDecimal total) {
    }
}
