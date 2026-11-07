package mealplanner.agent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Test-only LLMClient that replays a script, so agent behaviour can be checked without a network.
 * It is never used by the application itself.
 */
public final class ScriptedLLM implements LLMClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final Deque<AgentStep> steps = new ArrayDeque<>();
    private final Deque<String> finals = new ArrayDeque<>();
    public final List<AgentContext> seenContexts = new ArrayList<>();
    public int nextStepCalls;
    public int finishCalls;
    public long delayMillis;

    public ScriptedLLM tool(String name, String argsJson) {
        try {
            steps.add(new AgentStep.ToolStep(List.of(new ToolCall(name, MAPPER.readTree(argsJson), null)), "{}", new TokenUsage(10, 2)));
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
        return this;
    }

    public ScriptedLLM batch(String name1, String name2) {
        steps.add(new AgentStep.ToolStep(List.of(new ToolCall(name1, MAPPER.createObjectNode(), null),
                new ToolCall(name2, MAPPER.createObjectNode(), null)), "{}", TokenUsage.NONE));
        return this;
    }

    public ScriptedLLM ready() {
        steps.add(new AgentStep.FinalReady("READY", "{}", new TokenUsage(10, 1)));
        return this;
    }

    public ScriptedLLM finalJson(String json) {
        finals.add(json);
        return this;
    }

    @Override
    public AgentStep nextStep(AgentContext context) throws LLMException {
        nextStepCalls++;
        seenContexts.add(context);
        sleep();
        AgentStep step = steps.pollFirst();
        if (step == null) {
            return new AgentStep.FinalReady("READY", "{}", TokenUsage.NONE);
        }
        return step;
    }

    @Override
    public AgentDraft finish(AgentContext context, JsonNode schema) throws LLMException {
        finishCalls++;
        sleep();
        String json = finals.pollFirst();
        if (json == null) {
            throw new LLMException(LLMException.Kind.FORMAT, "No scripted final answer.");
        }
        try {
            return new AgentDraft(MAPPER.readTree(json), new TokenUsage(50, 20));
        } catch (Exception e) {
            throw new LLMException(LLMException.Kind.FORMAT, "bad script json", e);
        }
    }

    private void sleep() {
        if (delayMillis > 0) {
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public String describe() {
        return "Scripted test model";
    }
}
