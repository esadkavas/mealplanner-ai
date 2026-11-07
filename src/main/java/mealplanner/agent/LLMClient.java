package mealplanner.agent;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Application-owned model interface (the Adapter pattern's target). The coordinator only knows
 * these two operations; provider request and response formats stay inside the adapter.
 */
public interface LLMClient {

    /** Asks the model for its next step: one tool call, or a signal that it is ready to answer. */
    AgentStep nextStep(AgentContext context) throws LLMException;

    /**
     * Asks for the final structured answer with no tools available. {@code schema} is a JSON
     * Schema subset that the provider must follow.
     */
    AgentDraft finish(AgentContext context, JsonNode schema) throws LLMException;

    /** A short provider/model description for logs and the UI, e.g. "Gemini gemini-3.5-flash-lite". */
    String describe();
}
