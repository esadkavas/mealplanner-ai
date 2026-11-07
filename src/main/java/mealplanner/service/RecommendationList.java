package mealplanner.service;

import java.util.List;

/**
 * Validated F05 output: up to five eligible stored recipes with the model's reasons and the
 * missing quantities (computed in Java at base servings). Nothing is saved.
 */
public record RecommendationList(List<Recommendation> items, String message, String appliedConstraints,
                                 List<String> agentTrace) {

    public RecommendationList {
        items = List.copyOf(items);
        agentTrace = agentTrace == null ? List.of() : List.copyOf(agentTrace);
    }

    public RecommendationList withTrace(List<String> trace) {
        return new RecommendationList(items, message, appliedConstraints, trace);
    }

    public record Recommendation(String alias, mealplanner.domain.Recipe recipe, String reason,
                                 List<RecipeService.Shortage> missing) {
        public Recommendation {
            missing = List.copyOf(missing);
        }
    }
}
