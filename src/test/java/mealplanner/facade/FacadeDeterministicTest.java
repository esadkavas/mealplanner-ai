package mealplanner.facade;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.math.BigDecimal;
import java.util.List;

import org.junit.Test;

import mealplanner.Fixtures;
import mealplanner.domain.AppState;
import mealplanner.domain.Diet;
import mealplanner.domain.Recipe;
import mealplanner.service.PantryInput;
import mealplanner.service.ProfileInput;
import mealplanner.service.RecipeDocument;
import mealplanner.service.SearchFilter;

/** F01-F04, F09 and the shared accept/reject path through the facade. */
public class FacadeDeterministicTest {

    private final Fixtures f = new Fixtures();
    private final Fixtures.MemoryStore store = new Fixtures.MemoryStore();
    private final PlannerFacade facade = new PlannerFacade(f.seedState(), store, f.validator, f.recipes, f.plans, f.shopping);

    @Test
    public void profileUpdateSavesAndIncrementsRevision() {
        Result<?> r = facade.updateProfile(new ProfileInput("vegetarian", List.of("peanut"), "2", "30"));
        assertTrue(r.isSuccess());
        assertEquals(Diet.VEGETARIAN, facade.snapshot().getProfile().diet());
        assertEquals(1, facade.snapshot().getRevision());
        assertEquals(1, store.saves);
    }

    @Test
    public void invalidProfileChangesNothing() {
        Result<?> r = facade.updateProfile(new ProfileInput("keto", List.of(), "2", "30"));
        assertFalse(r.isSuccess());
        assertEquals(0, facade.snapshot().getRevision());
        assertEquals(0, store.saves);
    }

    @Test
    public void failedSaveKeepsPreviousActiveState() {
        store.failNextSave = true;
        Result<?> r = facade.updatePantry(PantryInput.set("rice", "500"));
        assertFalse(r.isSuccess());
        assertTrue(r.errors().get(0).contains("previous data is unchanged"));
        assertTrue(facade.snapshot().getPantry().isEmpty());
        assertEquals(0, facade.snapshot().getRevision());
    }

    @Test
    public void pantrySetZeroAndRemove() {
        assertTrue(facade.updatePantry(PantryInput.set("rice", "0")).isSuccess());
        assertEquals(0, BigDecimal.ZERO.compareTo(facade.snapshot().pantryQuantity("rice")));
        assertTrue(facade.updatePantry(PantryInput.remove("rice")).isSuccess());
        assertFalse(facade.updatePantry(PantryInput.remove("rice")).isSuccess());
        assertFalse(facade.updatePantry(PantryInput.set("rice", "-1")).isSuccess());
    }

    @Test
    public void importPreviewSavesOnlyOnAccept() {
        Result<Proposal> preview = facade.previewRecipe(new RecipeDocument("Garlic Rice", 2, 15,
                List.of(new RecipeDocument.Line("rice", new BigDecimal("200")),
                        new RecipeDocument.Line("garlic", new BigDecimal("2"))),
                List.of("Cook the rice.", "Fry the garlic and mix.")));
        assertTrue(preview.isSuccess());
        assertEquals(21, facade.snapshot().getRecipes().size());

        Result<String> saved = facade.accept(preview.data());
        assertTrue(saved.isSuccess());
        assertTrue(saved.data().contains("R22"));
        assertEquals(22, facade.snapshot().getRecipes().size());
        assertFalse("a consumed proposal cannot be accepted twice", facade.accept(preview.data()).isSuccess());
    }

    @Test
    public void ineligibleImportIsLabelledButStorable() {
        facade.updateProfile(new ProfileInput("vegan", List.of(), "2", "240"));
        Result<Proposal> preview = facade.previewRecipeJson("{\"title\":\"Cheese Toast\",\"servings\":1,\"minutes\":5,"
                + "\"ingredients\":[{\"ingredientId\":\"cheddar\",\"quantity\":40},{\"ingredientId\":\"bread\",\"quantity\":1}],"
                + "\"steps\":[\"Toast with cheese.\"]}");
        assertTrue(preview.isSuccess());
        assertFalse(preview.data().warnings().isEmpty());
        assertTrue(facade.accept(preview.data()).isSuccess());
    }

    @Test
    public void structurallyInvalidImportIsRejectedWithoutPartialImport() {
        Result<Proposal> preview = facade.previewRecipeJson("{\"title\":\"Bad\",\"servings\":2,\"minutes\":5,"
                + "\"ingredients\":[{\"ingredientId\":\"unicorn\",\"quantity\":1}],\"steps\":[\"x\"]}");
        assertFalse(preview.isSuccess());
        assertEquals(21, facade.snapshot().getRecipes().size());
    }

    @Test
    public void staleProposalIsRejected() {
        Result<Proposal> scaled = facade.scale("R01", 8);
        assertTrue(scaled.isSuccess());
        facade.updatePantry(PantryInput.set("rice", "100"));   // any save changes the revision
        Result<String> accepted = facade.accept(scaled.data());
        assertFalse(accepted.isSuccess());
        assertTrue(accepted.errors().get(0).contains("out of date"));
        assertEquals(21, facade.snapshot().getRecipes().size());
    }

    @Test
    public void scaleCreatesVariantAndKeepsSource() {
        Recipe source = facade.snapshot().findRecipe("R01").orElseThrow();
        Result<Proposal> scaled = facade.scale("R01", 8);
        assertTrue(facade.accept(scaled.data()).isSuccess());
        AppState after = facade.snapshot();
        assertEquals(source.getServings(), after.findRecipe("R01").orElseThrow().getServings());
        assertEquals(8, after.findRecipe("R22").orElseThrow().getServings());
        assertFalse(facade.scale("R01", 0).isSuccess());
        assertFalse(facade.scale("R99", 2).isSuccess());
    }

    @Test
    public void rejectSavesNothing() {
        Result<Proposal> scaled = facade.scale("R01", 3);
        facade.reject(scaled.data());
        assertFalse(facade.accept(scaled.data()).isSuccess());
        assertEquals(0, store.saves);
    }

    @Test
    public void searchReportsEmptyResultHonestly() {
        Result<List<Recipe>> r = facade.search(new SearchFilter("zzz-no-such", List.of(), null, false));
        assertTrue(r.isSuccess());
        assertTrue(r.data().isEmpty());
        assertTrue(r.message().contains("No eligible recipe"));
    }

    @Test
    public void snapshotCannotChangeActiveState() {
        AppState copy = facade.snapshot();
        copy.setPantryQuantity("rice", BigDecimal.TEN);
        assertTrue(facade.snapshot().getPantry().isEmpty());
    }
}
