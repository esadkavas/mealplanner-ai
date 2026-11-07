package mealplanner.app;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.core.type.TypeReference;

import mealplanner.domain.AppState;
import mealplanner.domain.Catalogue;
import mealplanner.domain.Ingredient;
import mealplanner.domain.Recipe;
import mealplanner.persistence.Json;
import mealplanner.persistence.StorageException;
import mealplanner.service.RecipeDocument;
import mealplanner.service.RecipeService;
import mealplanner.service.ValidationResult;
import mealplanner.service.ConstraintValidator;

/** Loads the bundled ingredient catalogue and the starter recipe collection. */
public final class SeedData {

    private SeedData() {
    }

    public static Catalogue loadCatalogue() throws StorageException {
        try (InputStream in = resource("/catalogue/ingredients.json")) {
            List<Ingredient> ingredients = Json.mapper().readValue(in, new TypeReference<List<Ingredient>>() { });
            return new Catalogue(ingredients);
        } catch (IOException | IllegalArgumentException e) {
            throw new StorageException("The bundled ingredient catalogue is invalid: " + e.getMessage(), e);
        }
    }

    /**
     * A fresh state with default preferences, an empty pantry and the starter recipes. Seed
     * recipe IDs are derived from their titles, so every fresh install has the same IDs.
     */
    public static AppState initialState(ConstraintValidator validator, RecipeService recipes) throws StorageException {
        AppState state = new AppState();
        try (InputStream in = resource("/seed/recipes.json")) {
            List<RecipeDocument> documents = Json.mapper().readValue(in, new TypeReference<List<RecipeDocument>>() { });
            for (RecipeDocument document : documents) {
                Recipe recipe = recipes.fromDocument(document);
                recipe.setId(UUID.nameUUIDFromBytes(("seed:" + recipe.getTitle()).getBytes(StandardCharsets.UTF_8)));
                ValidationResult check = validator.recipe(recipe, null);
                if (!check.isStructurallyValid()) {
                    throw new StorageException("Seed recipe '" + recipe.getTitle() + "' is invalid: "
                            + check.structuralErrors());
                }
                state.addRecipe(recipe);
            }
        } catch (IOException e) {
            throw new StorageException("The bundled seed recipes are invalid: " + e.getMessage(), e);
        }
        return state;
    }

    private static InputStream resource(String path) throws StorageException {
        InputStream in = SeedData.class.getResourceAsStream(path);
        if (in == null) {
            throw new StorageException("Missing bundled resource " + path);
        }
        return in;
    }
}
