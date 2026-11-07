package mealplanner.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import org.junit.Test;

import mealplanner.Fixtures;
import mealplanner.TestRecipes;
import mealplanner.domain.AppState;
import mealplanner.domain.Diet;
import mealplanner.domain.MealPlan;
import mealplanner.domain.MealPlanBuilder;
import mealplanner.domain.MealSlot;
import mealplanner.domain.PlanRequest;
import mealplanner.domain.PreferenceProfile;
import mealplanner.domain.Quantities;
import mealplanner.domain.Recipe;

/** Arithmetic, constraint and search checks from the Stage 1 verification plan. */
public class DeterministicServicesTest {

    private final Fixtures f = new Fixtures();

    @Test
    public void scalingTwoServingsOf200gRiceToFiveGives500g() {
        Recipe rice = TestRecipes.recipe("Rice", 2, 20, "rice", "200", "onion", "1");
        Recipe scaled = f.recipes.scale(rice, 5, PreferenceProfile.defaults());

        assertEquals(0, new BigDecimal("500").compareTo(scaled.getIngredients().get(0).quantity()));
        assertEquals("2.5", Quantities.display(scaled.getIngredients().get(1).quantity()));
        assertEquals(5, scaled.getServings());
        assertEquals(2, rice.getServings());
        assertEquals(0, new BigDecimal("200").compareTo(rice.getIngredients().get(0).quantity()));
        assertEquals(rice.getId(), scaled.getDerivedFrom());
    }

    @Test
    public void scalingKeepsPrecisionAndRoundsOnlyForDisplay() {
        Recipe soup = TestRecipes.recipe("Soup", 3, 20, "onion", "1");
        Recipe scaled = f.recipes.scale(soup, 5, PreferenceProfile.defaults());
        BigDecimal stored = scaled.getIngredients().get(0).quantity();
        assertTrue(stored.scale() > 2);
        assertEquals("1.67", Quantities.display(stored));
    }

    @Test
    public void scalingRejectsOutOfRangeServings() {
        Recipe rice = TestRecipes.recipe("Rice", 2, 20, "rice", "200");
        assertThrows(DomainException.class, () -> f.recipes.scale(rice, 0, PreferenceProfile.defaults()));
        assertThrows(DomainException.class, () -> f.recipes.scale(rice, 13, PreferenceProfile.defaults()));
    }

    @Test
    public void shoppingListCombines300gAnd250gRiceAs550g() {
        Recipe a = TestRecipes.recipe("A", 2, 20, "rice", "300");
        Recipe b = TestRecipes.recipe("B", 2, 20, "rice", "250", "onion", "1");
        MealPlan plan = MealPlanBuilder.start(new PlanRequest(LocalDate.of(2026, 11, 9), 2, Set.of(MealSlot.DINNER), 2, false))
                .addMeal(1, MealSlot.DINNER, a, 2)
                .addMeal(2, MealSlot.DINNER, b, 2)
                .build();
        ShoppingList list = f.shopping.generate(plan);

        ShoppingList.Line rice = list.lines().stream().filter(l -> l.ingredientId().equals("rice")).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("550").compareTo(rice.total()));
        assertEquals("g", rice.unit());
        assertEquals(2, list.lines().size());
    }

    @Test
    public void shoppingListScalesByMealServings() {
        Recipe a = TestRecipes.recipe("A", 2, 20, "rice", "200");
        MealPlan plan = MealPlanBuilder.start(new PlanRequest(LocalDate.of(2026, 11, 9), 1, Set.of(MealSlot.DINNER), 5, false))
                .addMeal(1, MealSlot.DINNER, a, 5)
                .build();
        assertEquals(0, new BigDecimal("500").compareTo(f.shopping.generate(plan).lines().get(0).total()));
    }

    @Test
    public void recipeValidationSeparatesStructureFromEligibility() {
        Recipe chicken = TestRecipes.recipe("Chicken", 2, 30, "chicken", "300");
        PreferenceProfile vegan = new PreferenceProfile(Diet.VEGAN, Set.of(), 2, 240);
        ValidationResult result = f.validator.recipe(chicken, vegan);
        assertTrue(result.isStructurallyValid());
        assertFalse(result.isEligible());

        Recipe broken = TestRecipes.recipe("Broken", 0, 500, "unicorn", "1", "rice", "-2");
        ValidationResult bad = f.validator.recipe(broken, null);
        assertFalse(bad.isStructurallyValid());
        assertEquals(4, bad.structuralErrors().size());
    }

    @Test
    public void duplicateIngredientsAreRejected() {
        Recipe dup = TestRecipes.recipe("Dup", 2, 20, "rice", "1", "rice", "2");
        assertFalse(f.validator.recipe(dup, null).isStructurallyValid());
    }

    @Test
    public void profileValidationRejectsUnknownIdsAndRanges() {
        ValidationResult r = f.validator.profile(new ProfileInput("keto", List.of("unicorn"), "0", "abc"));
        assertEquals(4, r.structuralErrors().size());
        assertTrue(f.validator.profile(new ProfileInput("vegan", List.of("peanut"), "2", "30")).isValid());
    }

    @Test
    public void pantryValidationRejectsNegativeAndUnknown() {
        assertFalse(f.validator.pantry(PantryInput.set("rice", "-1")).isValid());
        assertFalse(f.validator.pantry(PantryInput.set("unicorn", "1")).isValid());
        assertFalse(f.validator.pantry(PantryInput.set("rice", "lots")).isValid());
        assertTrue(f.validator.pantry(PantryInput.set("rice", "0")).isValid());
    }

    @Test
    public void searchAppliesProfileAndNeverRelaxesTimeLimit() {
        AppState state = f.seedState();
        state.setProfile(new PreferenceProfile(Diet.VEGAN, Set.of("peanut"), 2, 30));
        List<Recipe> found = f.recipes.search(state, new SearchFilter("", List.of(), 120, false));
        assertFalse(found.isEmpty());
        for (Recipe r : found) {
            assertTrue(r.getMinutes() <= 30);
            assertTrue(f.validator.isEligible(r, state.getProfile()));
            assertFalse(r.usesIngredient("peanut"));
        }
        assertTrue(found.stream().noneMatch(r -> r.getTitle().equals("Peanut Noodles")));
    }

    @Test
    public void pantryOnlySearchComparesQuantities() {
        AppState state = f.seedState();
        state.setPantryQuantity("bread", new BigDecimal("2"));
        state.setPantryQuantity("peanut-butter", new BigDecimal("30"));
        state.setPantryQuantity("banana", new BigDecimal("1"));
        List<Recipe> found = f.recipes.search(state, new SearchFilter("", List.of(), null, true));
        assertEquals(1, found.size());
        assertEquals("Peanut Butter Banana Toast", found.get(0).getTitle());

        state.setPantryQuantity("peanut-butter", new BigDecimal("29"));
        assertTrue(f.recipes.search(state, new SearchFilter("", List.of(), null, true)).isEmpty());
    }

    @Test
    public void searchRejectsUnknownIngredientFilter() {
        assertThrows(DomainException.class,
                () -> f.recipes.search(f.seedState(), new SearchFilter("", List.of("unicorn"), null, false)));
    }

    @Test
    public void parseRejectsMalformedAndUnknownFields() {
        assertThrows(DomainException.class, () -> f.recipes.parse("{ not json"));
        assertThrows(DomainException.class, () -> f.recipes.parse("{\"title\":\"x\",\"color\":\"red\"}"));
        DomainException missing = assertThrows(DomainException.class, () -> f.recipes.parse("{\"title\":\"x\"}"));
        assertEquals(4, missing.messages().size());
        Recipe ok = f.recipes.parse("{\"title\":\"Rice\",\"servings\":2,\"minutes\":20,"
                + "\"ingredients\":[{\"ingredientId\":\"rice\",\"quantity\":200}],\"steps\":[\"Cook\"]}");
        assertEquals("Rice", ok.getTitle());
    }

    @Test
    public void planValidationDetectsRepeatsWhenForbidden() {
        Recipe a = TestRecipes.recipe("A", 2, 20, "rice", "300");
        MealPlan plan = MealPlanBuilder.start(new PlanRequest(LocalDate.of(2026, 11, 9), 2, Set.of(MealSlot.DINNER), 2, true))
                .addMeal(1, MealSlot.DINNER, a, 2)
                .addMeal(2, MealSlot.DINNER, a, 2)
                .build();
        ValidationResult r = f.plans.validate(plan, PreferenceProfile.defaults());
        assertFalse(r.isEligible());
    }

    @Test
    public void feasibilityExplainsTooFewRecipesForNoRepeat() {
        AppState state = f.seedState();
        state.setProfile(new PreferenceProfile(Diet.VEGETARIAN, Set.of(), 2, 240));
        PlanRequest week = new PlanRequest(LocalDate.of(2026, 11, 9), 7,
                Set.of(MealSlot.BREAKFAST, MealSlot.LUNCH, MealSlot.DINNER), 2, true);
        DomainException e = assertThrows(DomainException.class, () -> f.plans.checkFeasible(state, week));
        assertTrue(e.getMessage().contains("needs 21 different recipes"));
    }
}
