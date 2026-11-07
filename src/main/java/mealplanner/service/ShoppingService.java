package mealplanner.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import mealplanner.domain.Catalogue;
import mealplanner.domain.IngredientAmount;
import mealplanner.domain.MealEntry;
import mealplanner.domain.MealPlan;
import mealplanner.domain.Quantities;
import mealplanner.domain.Recipe;

/**
 * F10: sums every ingredient a saved plan needs. It reads the plan's own recipe snapshots, so
 * later recipe edits cannot change the totals. Pantry stock is not deducted.
 */
public class ShoppingService {

    private final Catalogue catalogue;

    public ShoppingService(Catalogue catalogue) {
        this.catalogue = catalogue;
    }

    public ShoppingList generate(MealPlan plan) {
        if (plan == null) {
            throw new DomainException("No such plan.");
        }
        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        for (MealEntry entry : plan.getMeals()) {
            Recipe snapshot = entry.getRecipeSnapshot();
            if (snapshot == null || snapshot.getServings() < 1 || snapshot.getIngredients().isEmpty()) {
                throw new DomainException("Day " + entry.getDay() + " " + entry.getSlot().label()
                        + " has a corrupt recipe snapshot; the shopping list cannot be calculated.");
            }
            BigDecimal mealServings = BigDecimal.valueOf(entry.getServings());
            BigDecimal baseServings = BigDecimal.valueOf(snapshot.getServings());
            for (IngredientAmount amount : snapshot.getIngredients()) {
                if (!catalogue.contains(amount.ingredientId()) || amount.quantity() == null) {
                    throw new DomainException("Day " + entry.getDay() + " " + entry.getSlot().label()
                            + " uses unknown ingredient '" + amount.ingredientId() + "'.");
                }
                BigDecimal needed = amount.quantity().multiply(mealServings, Quantities.MATH)
                        .divide(baseServings, Quantities.MATH);
                totals.merge(amount.ingredientId(), needed, (a, b) -> a.add(b, Quantities.MATH));
            }
        }
        List<ShoppingList.Line> lines = new ArrayList<>();
        totals.forEach((id, total) -> lines.add(new ShoppingList.Line(id, catalogue.nameOf(id),
                catalogue.unitLabel(id), total)));
        lines.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        return new ShoppingList(plan.getId(), lines);
    }
}
