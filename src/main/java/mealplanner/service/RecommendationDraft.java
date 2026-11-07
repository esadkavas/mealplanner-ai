package mealplanner.service;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The agent's ranked suggestions (F05) plus the constraints it says it took from the request.
 * Java checks every item against those constraints, the profile and the retrieved IDs.
 *
 * @param maxMinutes time limit from the request, or 0 for none
 */
public record RecommendationDraft(String message, int maxMinutes, List<String> mustIncludeIngredientIds,
                                  List<String> avoidIngredientIds, List<Item> items, Set<UUID> retrievedRecipeIds) {

    public RecommendationDraft {
        mustIncludeIngredientIds = List.copyOf(mustIncludeIngredientIds);
        avoidIngredientIds = List.copyOf(avoidIngredientIds);
        items = List.copyOf(items);
        retrievedRecipeIds = Set.copyOf(retrievedRecipeIds);
    }

    public record Item(String recipeRef, String reason) {
    }
}
