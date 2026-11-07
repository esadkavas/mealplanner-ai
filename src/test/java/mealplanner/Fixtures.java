package mealplanner;

import java.util.Optional;

import mealplanner.app.SeedData;
import mealplanner.domain.AppState;
import mealplanner.domain.Catalogue;
import mealplanner.persistence.StateStore;
import mealplanner.persistence.StorageException;
import mealplanner.service.ConstraintValidator;
import mealplanner.service.PlanService;
import mealplanner.service.RecipeService;
import mealplanner.service.ShoppingService;

/** Shared test wiring: the real catalogue, real services and seed recipes. */
public final class Fixtures {

    public static final Catalogue CATALOGUE;

    static {
        try {
            CATALOGUE = SeedData.loadCatalogue();
        } catch (StorageException e) {
            throw new IllegalStateException(e);
        }
    }

    public final ConstraintValidator validator = new ConstraintValidator(CATALOGUE);
    public final RecipeService recipes = new RecipeService(validator);
    public final PlanService plans = new PlanService(validator);
    public final ShoppingService shopping = new ShoppingService(CATALOGUE);

    public AppState seedState() {
        try {
            return SeedData.initialState(validator, recipes);
        } catch (StorageException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Keeps the last saved state in memory; can be told to fail. */
    public static final class MemoryStore implements StateStore {
        public AppState saved;
        public boolean failNextSave;
        public int saves;

        @Override
        public Optional<AppState> load() {
            return Optional.ofNullable(saved);
        }

        @Override
        public void save(AppState state) throws StorageException {
            if (failNextSave) {
                failNextSave = false;
                throw new StorageException("Simulated disk failure. Your previous data is unchanged.");
            }
            saves++;
            saved = state.deepCopy();
        }
    }
}
