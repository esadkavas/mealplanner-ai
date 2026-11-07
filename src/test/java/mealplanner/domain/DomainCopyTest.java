package mealplanner.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.junit.Test;

import mealplanner.TestRecipes;

/** Prototype (deep copy) independence and Builder completeness. */
public class DomainCopyTest {

    @Test
    public void recipeDeepCopyIsIndependent() {
        Recipe source = TestRecipes.recipe("Rice", 2, 20, "rice", "200");
        Recipe copy = source.deepCopy();
        copy.setIngredients(List.of(new IngredientAmount("rice", new BigDecimal("999"))));
        copy.setSteps(List.of("Changed"));
        copy.setTitle("Changed");

        assertEquals(new BigDecimal("200"), source.getIngredients().get(0).quantity());
        assertEquals(List.of("Cook it."), source.getSteps());
        assertEquals("Rice", source.getTitle());
    }

    @Test
    public void recipeListsAreReadOnlyViews() {
        Recipe source = TestRecipes.recipe("Rice", 2, 20, "rice", "200");
        assertThrows(UnsupportedOperationException.class,
                () -> source.getIngredients().add(new IngredientAmount("onion", BigDecimal.ONE)));
    }

    @Test
    public void planDeepCopyCopiesSnapshots() {
        Recipe rice = TestRecipes.recipe("Rice", 2, 20, "rice", "200");
        MealPlan plan = MealPlanBuilder.start(new PlanRequest(LocalDate.of(2026, 11, 9), 1, Set.of(MealSlot.DINNER), 2, false))
                .addMeal(1, MealSlot.DINNER, rice, 2)
                .build();
        MealPlan copy = plan.deepCopy();
        copy.getMeals().get(0).getRecipeSnapshot().setTitle("Changed");

        assertEquals("Rice", plan.getMeals().get(0).getRecipeSnapshot().getTitle());
        assertNotSame(plan.getMeals().get(0), copy.getMeals().get(0));
    }

    @Test
    public void builderSnapshotIsIndependentOfStoredRecipe() {
        Recipe rice = TestRecipes.recipe("Rice", 2, 20, "rice", "200");
        MealPlan plan = MealPlanBuilder.start(new PlanRequest(LocalDate.of(2026, 11, 9), 1, Set.of(MealSlot.DINNER), 2, false))
                .addMeal(1, MealSlot.DINNER, rice, 2)
                .build();
        rice.setTitle("Edited later");
        assertEquals("Rice", plan.getMeals().get(0).getRecipeSnapshot().getTitle());
    }

    @Test
    public void builderRejectsDuplicateUnrequestedAndIncompleteSlots() {
        Recipe rice = TestRecipes.recipe("Rice", 2, 20, "rice", "200");
        PlanRequest request = new PlanRequest(LocalDate.of(2026, 11, 9), 2,
                EnumSet.of(MealSlot.LUNCH, MealSlot.DINNER), 2, false);
        MealPlanBuilder builder = MealPlanBuilder.start(request).addMeal(1, MealSlot.LUNCH, rice, 2);

        assertThrows(IllegalArgumentException.class, () -> builder.addMeal(1, MealSlot.LUNCH, rice, 2));
        assertThrows(IllegalArgumentException.class, () -> builder.addMeal(1, MealSlot.BREAKFAST, rice, 2));
        assertThrows(IllegalArgumentException.class, () -> builder.addMeal(3, MealSlot.DINNER, rice, 2));
        assertThrows(IllegalArgumentException.class, () -> builder.addMeal(1, MealSlot.DINNER, rice, 5));
        IllegalStateException e = assertThrows(IllegalStateException.class, builder::build);
        assertTrue(e.getMessage().contains("day 2 Dinner"));
    }

    @Test
    public void appStateDeepCopyIsIndependent() {
        AppState state = sampleState();
        AppState copy = state.deepCopy();
        copy.setPantryQuantity("rice", new BigDecimal("5"));
        copy.addRecipe(TestRecipes.recipe("New", 1, 5, "rice", "1"));
        copy.getRecipes().values().iterator().next().setTitle("Changed");
        copy.incrementRevision();

        assertTrue(state.getPantry().isEmpty());
        assertEquals(3, state.getRecipes().size());
        assertEquals("First", state.getRecipes().values().iterator().next().getTitle());
        assertEquals(0, state.getRevision());
    }

    @Test
    public void aliasesResolveInInsertionOrder() {
        AppState state = sampleState();
        Recipe first = state.getRecipes().values().iterator().next();
        assertEquals("R01", state.recipeAlias(first.getId()));
        assertEquals(first, state.findRecipe("r01").orElseThrow());
        assertEquals(first, state.findRecipe(first.getId().toString()).orElseThrow());
        assertTrue(state.findRecipe("R99").isEmpty());
    }

    private static AppState sampleState() {
        AppState state = new AppState();
        state.addRecipe(TestRecipes.recipe("First", 2, 10, "rice", "100"));
        state.addRecipe(TestRecipes.recipe("Second", 2, 10, "pasta", "100"));
        state.addRecipe(TestRecipes.recipe("Third", 2, 10, "oats", "100"));
        return state;
    }
}
