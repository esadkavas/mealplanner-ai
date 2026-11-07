package mealplanner.agent;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The result of one bounded agent run: the untrusted JSON draft, the recipe and ingredient IDs
 * that tools actually returned (trusted), a readable trace of the steps, and token usage.
 */
public record AgentRun(JsonNode draft, Set<UUID> retrievedRecipeIds, Set<String> retrievedIngredientIds,
                       List<String> trace, TokenUsage usage, int modelCalls) {

    public AgentRun {
        retrievedRecipeIds = Set.copyOf(retrievedRecipeIds);
        retrievedIngredientIds = Set.copyOf(retrievedIngredientIds);
        trace = List.copyOf(trace);
    }
}
