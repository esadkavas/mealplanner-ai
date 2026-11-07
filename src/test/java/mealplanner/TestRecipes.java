package mealplanner;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import mealplanner.domain.IngredientAmount;
import mealplanner.domain.Recipe;

/** Builds small recipes for tests using only the domain model. */
public final class TestRecipes {

    private TestRecipes() {
    }

    public static Recipe recipe(String title, int servings, int minutes, Object... idQuantityPairs) {
        List<IngredientAmount> amounts = new ArrayList<>();
        for (int i = 0; i < idQuantityPairs.length; i += 2) {
            amounts.add(new IngredientAmount((String) idQuantityPairs[i], new BigDecimal(idQuantityPairs[i + 1].toString())));
        }
        return new Recipe(UUID.randomUUID(), title, servings, minutes, amounts, List.of("Cook it."));
    }
}
