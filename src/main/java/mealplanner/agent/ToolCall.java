package mealplanner.agent;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * One tool request from the model.
 *
 * @param callId optional provider call ID that must be echoed in the matching result
 */
public record ToolCall(String name, JsonNode arguments, String callId) {
}
