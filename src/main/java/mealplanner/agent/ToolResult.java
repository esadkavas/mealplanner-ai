package mealplanner.agent;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;

/**
 * The outcome of one tool call, sent back to the model.
 *
 * <p>{@code retrievedRecipeIds} and {@code retrievedIngredientIds} are filled by the tool itself
 * from the data it returned. The coordinator trusts these sets, never IDs the model claims.</p>
 */
public record ToolResult(String toolName, String callId, boolean success, JsonNode data, List<String> errors,
                         Set<UUID> retrievedRecipeIds, Set<String> retrievedIngredientIds, String summary) {

    public ToolResult {
        errors = errors == null ? List.of() : List.copyOf(errors);
        retrievedRecipeIds = retrievedRecipeIds == null ? Set.of() : Set.copyOf(retrievedRecipeIds);
        retrievedIngredientIds = retrievedIngredientIds == null ? Set.of() : Set.copyOf(retrievedIngredientIds);
        data = data == null ? NullNode.getInstance() : data;
    }

    public static ToolResult error(String toolName, String callId, String message) {
        return new ToolResult(toolName, callId, false, null, List.of(message), Set.of(), Set.of(), "error: " + message);
    }
}
