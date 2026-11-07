package mealplanner.persistence;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import mealplanner.TestRecipes;
import mealplanner.domain.AppState;
import mealplanner.domain.Diet;
import mealplanner.domain.MealPlan;
import mealplanner.domain.MealPlanBuilder;
import mealplanner.domain.MealSlot;
import mealplanner.domain.PlanRequest;
import mealplanner.domain.PreferenceProfile;
import mealplanner.domain.Recipe;

public class JsonStateStoreTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void roundTripPreservesEverything() throws Exception {
        AppState state = new AppState();
        for (int i = 1; i <= 3; i++) {
            state.addRecipe(TestRecipes.recipe("Recipe " + i, 2, 10 * i, "rice", "100", "onion", "0.5"));
        }
        state.setProfile(new PreferenceProfile(Diet.VEGAN, Set.of("peanut"), 3, 45));
        state.setPantryQuantity("rice", new BigDecimal("1.666666666666666666666666666666667"));
        Recipe first = state.getRecipes().values().iterator().next();
        MealPlan plan = MealPlanBuilder.start(new PlanRequest(LocalDate.of(2026, 11, 9), 1, Set.of(MealSlot.DINNER), 3, true))
                .addMeal(1, MealSlot.DINNER, first, 3).build();
        state.addPlan(plan);
        state.incrementRevision();

        JsonStateStore store = new JsonStateStore(tmp.getRoot().toPath().resolve("state.json"));
        store.save(state);
        AppState loaded = store.load().orElseThrow();

        assertEquals(1, loaded.getRevision());
        assertEquals(state.getProfile(), loaded.getProfile());
        assertEquals(0, new BigDecimal("1.666666666666666666666666666666667").compareTo(loaded.pantryQuantity("rice")));
        assertEquals(3, loaded.getRecipes().size());
        assertEquals(0, new BigDecimal("0.5").compareTo(loaded.getRecipes().get(first.getId()).getIngredients().get(1).quantity()));
        assertEquals("R01", loaded.recipeAlias(first.getId()));
        MealPlan loadedPlan = loaded.getPlans().get(plan.getId());
        assertEquals(LocalDate.of(2026, 11, 9), loadedPlan.getStartDate());
        assertTrue(loadedPlan.isNoRepeat());
        assertEquals(first.getTitle(), loadedPlan.getMeals().get(0).getRecipeSnapshot().getTitle());
        assertEquals(Set.of(MealSlot.DINNER), loadedPlan.getSlots());
    }

    @Test
    public void missingFileLoadsEmpty() throws Exception {
        JsonStateStore store = new JsonStateStore(tmp.getRoot().toPath().resolve("none.json"));
        assertTrue(store.load().isEmpty());
    }

    @Test
    public void corruptFileIsAnErrorAndIsNotReplaced() throws Exception {
        Path file = tmp.getRoot().toPath().resolve("state.json");
        Files.writeString(file, "{ this is not json", StandardCharsets.UTF_8);
        JsonStateStore store = new JsonStateStore(file);
        StorageException e = assertThrows(StorageException.class, store::load);
        assertTrue(e.getMessage().contains("not valid MealPlanner JSON"));
        assertEquals("{ this is not json", Files.readString(file));
    }

    @Test
    public void failedSaveLeavesPreviousFileAndNoTempFiles() throws Exception {
        Path dir = tmp.newFolder("data").toPath();
        Path file = dir.resolve("state.json");
        JsonStateStore store = new JsonStateStore(file);
        store.save(new AppState());
        String before = Files.readString(file);

        // Make the target a non-empty directory so the atomic replace must fail.
        Files.delete(file);
        Files.createDirectory(file);
        Files.writeString(file.resolve("blocker"), "x");
        assertThrows(StorageException.class, () -> store.save(new AppState()));
        try (var files = Files.list(dir)) {
            List<Path> leftovers = files.filter(p -> p.getFileName().toString().endsWith(".tmp")).toList();
            assertTrue(leftovers.isEmpty());
        }
        assertTrue(before.contains("\"revision\""));
    }

    @Test
    public void secondLockOnSameDirectoryIsRefused() throws Exception {
        Path dir = tmp.newFolder("locked").toPath();
        try (DataDirectoryLock first = DataDirectoryLock.acquire(dir)) {
            StorageException e = assertThrows(StorageException.class, () -> DataDirectoryLock.acquire(dir));
            assertTrue(e.getMessage().contains("already in use"));
        }
        DataDirectoryLock.acquire(dir).close();
    }
}
