package mealplanner.agent;

import java.util.List;

/**
 * What the model decided in one nextStep() call.
 *
 * <p>{@code providerState} is opaque provider data (for Gemini: the raw model content including
 * its thought signatures) that the adapter needs to replay the conversation. The coordinator
 * stores it but never interprets it, which keeps provider details out of the agent logic.</p>
 */
public sealed interface AgentStep permits AgentStep.ToolStep, AgentStep.FinalReady, AgentStep.Invalid {

    TokenUsage usage();

    String providerState();

    /** The model requested tools. More than one call in a step is an unsupported batch. */
    record ToolStep(List<ToolCall> calls, String providerState, TokenUsage usage) implements AgentStep {
        public ToolStep {
            calls = List.copyOf(calls);
        }
    }

    /** The model says it has enough information to produce the final answer. */
    record FinalReady(String text, String providerState, TokenUsage usage) implements AgentStep {
    }

    /** The provider returned something unusable for this step (e.g. a malformed function call). */
    record Invalid(String problem, String providerState, TokenUsage usage) implements AgentStep {
    }
}
