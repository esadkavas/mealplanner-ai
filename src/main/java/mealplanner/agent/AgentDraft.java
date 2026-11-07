package mealplanner.agent;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The structured final answer from finish(): JSON that matched the requested output schema at
 * the provider. It is still untrusted; Java validates every ID and constraint afterwards.
 */
public record AgentDraft(JsonNode json, TokenUsage usage) {
}
