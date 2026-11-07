package mealplanner.llm;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import mealplanner.agent.AgentContext;
import mealplanner.agent.AgentDraft;
import mealplanner.agent.AgentStep;
import mealplanner.agent.LLMClient;
import mealplanner.agent.LLMException;
import mealplanner.agent.TaskType;
import mealplanner.agent.TokenUsage;
import mealplanner.agent.ToolCall;
import mealplanner.agent.ToolResult;
import mealplanner.agent.ToolSpec;

/**
 * Live provider check ("llm-check"): three real calls that exercise everything the agent needs:
 * the API key, function calling, replaying the conversation (thought signatures) and structured
 * JSON output. It changes no data.
 */
public final class ConnectionCheck {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ConnectionCheck() {
    }

    public record Report(boolean passed, List<String> lines, TokenUsage usage) {
    }

    public static Report run(LLMClient client, Duration totalTimeout) {
        List<String> lines = new ArrayList<>();
        TokenUsage usage = TokenUsage.NONE;
        lines.add("Provider: " + client.describe());
        ObjectNode params = MAPPER.createObjectNode();
        params.put("type", "object");
        params.putObject("properties").putObject("value").put("type", "integer");
        params.putArray("required").add("value");
        ToolSpec echo = new ToolSpec("echoNumber", "Returns the number it is given.", params);
        AgentContext context = new AgentContext(TaskType.CONNECTION_CHECK,
                "You are running a connectivity test for an application. Follow the instructions exactly.",
                "Call the echoNumber tool exactly once with value 7. After you receive its result, reply with the single word READY.",
                List.of(echo), Instant.now().plus(totalTimeout));
        try {
            AgentStep first = client.nextStep(context);
            usage = usage.plus(first.usage());
            if (!(first instanceof AgentStep.ToolStep tools) || tools.calls().isEmpty()
                    || !"echoNumber".equals(tools.calls().get(0).name())) {
                lines.add("[FAIL] 1/3 function calling: the model did not call the test tool.");
                return new Report(false, lines, usage);
            }
            lines.add("[ OK ] 1/3 API key accepted and the model called a tool (function calling works).");
            context.add(new AgentContext.ModelTurn(first));
            List<ToolResult> results = new ArrayList<>();
            for (ToolCall call : tools.calls()) {
                ObjectNode data = MAPPER.createObjectNode();
                data.set("value", call.arguments().path("value"));
                results.add(new ToolResult(call.name(), call.callId(), true, data, List.of(), Set.of(), Set.of(), "echo"));
            }
            context.add(new AgentContext.ToolResults(results));

            AgentStep second = client.nextStep(context);
            usage = usage.plus(second.usage());
            context.add(new AgentContext.ModelTurn(second));
            lines.add("[ OK ] 2/3 follow-up request with the tool result was accepted (conversation replay works).");

            ObjectNode schema = MAPPER.createObjectNode();
            schema.put("type", "object");
            schema.putObject("properties").putObject("echoedValue").put("type", "integer");
            schema.putArray("required").add("echoedValue");
            AgentDraft draft = client.finish(context, schema);
            usage = usage.plus(draft.usage());
            JsonNode value = draft.json().path("echoedValue");
            if (!value.canConvertToInt()) {
                lines.add("[FAIL] 3/3 structured output: the JSON answer had no integer 'echoedValue'.");
                return new Report(false, lines, usage);
            }
            lines.add("[ OK ] 3/3 structured JSON output works (echoedValue = " + value.asInt() + ").");
            lines.add("Tokens used by this check: " + usage.inputTokens() + " input, " + usage.outputTokens() + " output.");
            return new Report(true, lines, usage);
        } catch (LLMException e) {
            lines.add("[FAIL] " + e.kind() + ": " + e.getMessage());
            return new Report(false, lines, usage);
        }
    }
}
