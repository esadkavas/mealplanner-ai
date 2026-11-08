package mealplanner.llm;

import java.io.IOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import mealplanner.agent.AgentContext;
import mealplanner.agent.AgentDraft;
import mealplanner.agent.AgentStep;
import mealplanner.agent.LLMClient;
import mealplanner.agent.LLMException;
import mealplanner.agent.TokenUsage;
import mealplanner.agent.ToolCall;
import mealplanner.agent.ToolResult;
import mealplanner.agent.ToolSpec;

/**
 * Adapter pattern: implements the application's {@link LLMClient} on top of the Gemini REST API
 * ({@code models/{model}:generateContent}).
 *
 * <ul>
 *   <li>nextStep() sends the conversation with function declarations and translates the reply
 *       into a ToolStep or FinalReady.</li>
 *   <li>Gemini 3 models require their "thought signatures" to be sent back unchanged with
 *       function calls. The raw model content is kept as opaque providerState and replayed
 *       exactly, so the coordinator never needs to know about signatures.</li>
 *   <li>finish() sends the whole tool transcript as text with no tools, asking for JSON that
 *       follows a response schema (structured output).</li>
 * </ul>
 * The API key travels only in the {@code x-goog-api-key} header, never in URLs or logs.
 */
public class GeminiAdapter implements LLMClient {

    public static final String DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com/v1beta";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String apiKey;
    private final String modelId;
    private final String baseUrl;
    private final Duration callTimeout;
    private final HttpClient http;

    public GeminiAdapter(String apiKey, String modelId, String baseUrl, Duration callTimeout) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.modelId = modelId;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.callTimeout = callTimeout;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .proxy(ProxySelector.getDefault())
                .build();
    }

    @Override
    public String describe() {
        return "Gemini " + modelId;
    }

    // ---- nextStep ------------------------------------------------------------------------------

    @Override
    public AgentStep nextStep(AgentContext context) throws LLMException {
        ObjectNode body = MAPPER.createObjectNode();
        body.set("systemInstruction", textContent(null, context.systemPrompt()));
        body.set("contents", contents(context));
        ArrayNode declarations = MAPPER.createArrayNode();
        for (ToolSpec tool : context.tools()) {
            ObjectNode declaration = declarations.addObject();
            declaration.put("name", tool.name());
            declaration.put("description", tool.description());
            if (tool.parameters() != null && tool.parameters().path("properties").size() > 0) {
                declaration.set("parameters", toGeminiSchema(tool.parameters()));
            }
        }
        body.putArray("tools").addObject().set("functionDeclarations", declarations);
        body.putObject("toolConfig").putObject("functionCallingConfig").put("mode", "AUTO");

        JsonNode response = post(body, context);
        TokenUsage usage = usage(response);
        JsonNode candidate = firstCandidate(response);
        String finishReason = candidate.path("finishReason").asText("");
        JsonNode content = candidate.path("content");
        String providerState = content.isMissingNode() ? "{}" : content.toString();
        if (finishReason.contains("MALFORMED_FUNCTION_CALL") || finishReason.contains("UNEXPECTED_TOOL_CALL")) {
            return new AgentStep.Invalid("The model produced a malformed tool call (" + finishReason + ").",
                    providerState, usage);
        }
        List<ToolCall> calls = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        for (JsonNode part : content.path("parts")) {
            if (part.has("functionCall")) {
                JsonNode call = part.get("functionCall");
                calls.add(new ToolCall(call.path("name").asText(""), call.path("args"),
                        call.hasNonNull("id") ? call.get("id").asText() : null));
            } else if (part.has("text") && !part.path("thought").asBoolean(false)) {
                text.append(part.get("text").asText());
            }
        }
        if (!calls.isEmpty()) {
            return new AgentStep.ToolStep(calls, providerState, usage);
        }
        return new AgentStep.FinalReady(text.toString().trim(), providerState, usage);
    }

    // ---- finish --------------------------------------------------------------------------------

    @Override
    public AgentDraft finish(AgentContext context, JsonNode schema) throws LLMException {
        ObjectNode body = MAPPER.createObjectNode();
        body.set("systemInstruction", textContent(null, context.systemPrompt()));
        ArrayNode contents = body.putArray("contents");
        contents.add(textContent("user", finishPrompt(context)));
        ObjectNode generation = body.putObject("generationConfig");
        generation.put("responseMimeType", "application/json");
        generation.set("responseSchema", toGeminiSchema(schema));

        JsonNode response = post(body, context);
        TokenUsage usage = usage(response);
        JsonNode candidate = firstCandidate(response);
        String finishReason = candidate.path("finishReason").asText("");
        StringBuilder text = new StringBuilder();
        for (JsonNode part : candidate.path("content").path("parts")) {
            if (part.has("text") && !part.path("thought").asBoolean(false)) {
                text.append(part.get("text").asText());
            }
        }
        if ("MAX_TOKENS".equals(finishReason)) {
            throw new LLMException(LLMException.Kind.FORMAT, "The model's final answer was cut off (too long).");
        }
        String json = stripFences(text.toString());
        if (json.isEmpty()) {
            throw new LLMException(LLMException.Kind.FORMAT, "The model returned an empty final answer.");
        }
        try {
            JsonNode parsed = MAPPER.readTree(json);
            if (parsed == null || !parsed.isObject()) {
                throw new LLMException(LLMException.Kind.FORMAT, "The model's final answer was not a JSON object.");
            }
            return new AgentDraft(parsed, usage);
        } catch (JsonProcessingException e) {
            throw new LLMException(LLMException.Kind.FORMAT, "The model's final answer was not valid JSON.", e);
        }
    }

    /** The finish request carries the whole tool transcript as text, so no tools are needed. */
    static String finishPrompt(AgentContext context) {
        StringBuilder prompt = new StringBuilder(context.taskPrompt());
        prompt.append("\n\n=== Tool calls and results gathered so far ===\n");
        int step = 0;
        for (AgentContext.Message message : context.transcript()) {
            if (message instanceof AgentContext.ModelTurn turn && turn.step() instanceof AgentStep.ToolStep tools) {
                for (ToolCall call : tools.calls()) {
                    prompt.append("\n[").append(++step).append("] ").append(call.name())
                            .append(" arguments: ").append(call.arguments() == null ? "{}" : call.arguments().toString());
                }
            } else if (message instanceof AgentContext.ToolResults results) {
                for (ToolResult result : results.results()) {
                    prompt.append("\n    result: ").append(resultJson(result));
                }
            } else if (message instanceof AgentContext.Note note) {
                prompt.append("\n(note: ").append(note.text()).append(")");
            }
        }
        if (step == 0) {
            prompt.append("\n(no tools were called)");
        }
        prompt.append("\n\n=== Final answer ===\nReturn ONLY the JSON object required by the response schema. "
                + "Use only recipe IDs (like R07) and ingredient IDs that appear in the tool results above.");
        return prompt.toString();
    }

    // ---- request building ------------------------------------------------------------------------

    /**
     * Translates the application's neutral JSON-Schema subset into Gemini's Schema object, whose
     * "type" values are the uppercase OpenAPI names (OBJECT, ARRAY, STRING, INTEGER, NUMBER, BOOLEAN)
     * used in Google's REST examples. The input is not modified.
     */
    static JsonNode toGeminiSchema(JsonNode schema) {
        if (schema == null || !schema.isObject()) {
            return schema;
        }
        ObjectNode copy = ((ObjectNode) schema).deepCopy();
        if (copy.has("type") && copy.get("type").isTextual()) {
            copy.put("type", copy.get("type").asText().toUpperCase(Locale.ROOT));
        }
        if (copy.has("items")) {
            copy.set("items", toGeminiSchema(copy.get("items")));
        }
        if (copy.has("properties") && copy.get("properties").isObject()) {
            ObjectNode properties = MAPPER.createObjectNode();
            copy.get("properties").fields().forEachRemaining(e -> properties.set(e.getKey(), toGeminiSchema(e.getValue())));
            copy.set("properties", properties);
        }
        return copy;
    }

    private ArrayNode contents(AgentContext context) throws LLMException {
        ArrayNode contents = MAPPER.createArrayNode();
        contents.add(textContent("user", context.taskPrompt()));
        for (AgentContext.Message message : context.transcript()) {
            if (message instanceof AgentContext.ModelTurn turn) {
                JsonNode content = parseState(turn.step().providerState());
                if (content.path("parts").size() > 0) {
                    ObjectNode copy = ((ObjectNode) content).deepCopy();
                    copy.put("role", "model");
                    contents.add(copy);
                }
            } else if (message instanceof AgentContext.ToolResults results) {
                ArrayNode parts = userParts(contents);
                for (ToolResult result : results.results()) {
                    ObjectNode response = parts.addObject().putObject("functionResponse");
                    if (result.callId() != null) {
                        response.put("id", result.callId());
                    }
                    response.put("name", result.toolName());
                    response.set("response", resultJson(result));
                }
            } else if (message instanceof AgentContext.Note note) {
                userParts(contents).addObject().put("text", note.text());
            }
        }
        return contents;
    }

    /** Appends to the last user content if there is one, so roles keep alternating. */
    private static ArrayNode userParts(ArrayNode contents) {
        JsonNode last = contents.size() == 0 ? null : contents.get(contents.size() - 1);
        if (last != null && "user".equals(last.path("role").asText())) {
            return (ArrayNode) last.get("parts");
        }
        ObjectNode content = contents.addObject();
        content.put("role", "user");
        return content.putArray("parts");
    }

    private static ObjectNode resultJson(ToolResult result) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("ok", result.success());
        if (result.success()) {
            node.set("data", result.data());
        } else {
            ArrayNode errors = node.putArray("errors");
            result.errors().forEach(errors::add);
        }
        return node;
    }

    private static ObjectNode textContent(String role, String text) {
        ObjectNode content = MAPPER.createObjectNode();
        if (role != null) {
            content.put("role", role);
        }
        content.putArray("parts").addObject().put("text", text);
        return content;
    }

    private static JsonNode parseState(String state) throws LLMException {
        try {
            JsonNode node = MAPPER.readTree(state == null ? "{}" : state);
            return node == null || !node.isObject() ? MAPPER.createObjectNode() : node;
        } catch (JsonProcessingException e) {
            throw new LLMException(LLMException.Kind.FORMAT, "Internal error: stored model turn is not JSON.", e);
        }
    }

    // ---- HTTP ------------------------------------------------------------------------------------

    private JsonNode post(ObjectNode body, AgentContext context) throws LLMException {
        if (apiKey.isEmpty()) {
            throw new LLMException(LLMException.Kind.NOT_CONFIGURED,
                    "AI features need a Gemini API key. Copy .env.example to .env and set GEMINI_API_KEY.");
        }
        Duration timeout = callTimeout;
        Duration remaining = context.remaining();
        if (remaining.isNegative() || remaining.isZero()) {
            throw new LLMException(LLMException.Kind.TIMEOUT, "The AI request ran out of time.");
        }
        if (remaining.compareTo(timeout) < 0) {
            timeout = remaining;
        }
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/models/" + modelId + ":generateContent"))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("x-goog-api-key", apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException e) {
            throw new LLMException(LLMException.Kind.TIMEOUT,
                    "The AI provider did not answer within " + timeout.toSeconds() + " seconds.", e);
        } catch (IOException e) {
            throw new LLMException(LLMException.Kind.UNAVAILABLE,
                    "Could not reach the AI provider (" + e.getClass().getSimpleName() + "). Check your internet connection.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LLMException(LLMException.Kind.UNAVAILABLE, "The AI request was cancelled.", e);
        }
        if (response.statusCode() / 100 != 2) {
            throw httpError(response.statusCode(), response.body());
        }
        try {
            return MAPPER.readTree(response.body());
        } catch (JsonProcessingException e) {
            throw new LLMException(LLMException.Kind.FORMAT, "The AI provider returned a response that is not JSON.", e);
        }
    }

    private LLMException httpError(int status, String body) {
        String providerMessage = "";
        String reason = "";
        try {
            JsonNode error = MAPPER.readTree(body).path("error");
            providerMessage = error.path("message").asText("");
            for (JsonNode detail : error.path("details")) {
                if (detail.has("reason")) {
                    reason = detail.get("reason").asText();
                }
            }
        } catch (JsonProcessingException | IllegalArgumentException ignored) {
            // keep the generic message
        }
        providerMessage = sanitize(providerMessage);
        String upper = (providerMessage + " " + reason).toUpperCase(Locale.ROOT);
        if (status == 401 || status == 403 || upper.contains("API_KEY_INVALID") || upper.contains("API KEY NOT VALID")) {
            return new LLMException(LLMException.Kind.AUTH,
                    "The Gemini API key was rejected (HTTP " + status + "). Check GEMINI_API_KEY in your .env file.");
        }
        if (status == 404) {
            return new LLMException(LLMException.Kind.MODEL_NOT_FOUND,
                    "Model '" + modelId + "' was not found (HTTP 404). Check GEMINI_MODEL in your .env file.");
        }
        if (status == 429) {
            return new LLMException(LLMException.Kind.RATE_LIMITED,
                    "The Gemini rate limit or free quota was exceeded (HTTP 429). Wait a minute and try again.");
        }
        if (status >= 500) {
            return new LLMException(LLMException.Kind.UNAVAILABLE,
                    "The Gemini service is temporarily unavailable (HTTP " + status + "). Try again shortly.");
        }
        return new LLMException(LLMException.Kind.BAD_REQUEST,
                "Gemini rejected the request (HTTP " + status + ")" + (providerMessage.isEmpty() ? "." : ": " + providerMessage));
    }

    private String sanitize(String text) {
        String clean = text == null ? "" : text;
        if (!apiKey.isEmpty()) {
            clean = clean.replace(apiKey, "***");
        }
        return clean.length() > 300 ? clean.substring(0, 300) + "..." : clean;
    }

    // ---- response parsing -------------------------------------------------------------------------

    private static JsonNode firstCandidate(JsonNode response) throws LLMException {
        JsonNode candidates = response.path("candidates");
        if (!candidates.isArray() || candidates.isEmpty()) {
            String block = response.path("promptFeedback").path("blockReason").asText("");
            if (!block.isEmpty()) {
                throw new LLMException(LLMException.Kind.BLOCKED, "Gemini blocked the request (" + block + ").");
            }
            throw new LLMException(LLMException.Kind.FORMAT, "Gemini returned no answer.");
        }
        JsonNode candidate = candidates.get(0);
        String finishReason = candidate.path("finishReason").asText("");
        if (finishReason.equals("SAFETY") || finishReason.equals("PROHIBITED_CONTENT")
                || finishReason.equals("BLOCKLIST") || finishReason.equals("RECITATION")) {
            throw new LLMException(LLMException.Kind.BLOCKED, "Gemini stopped the answer (" + finishReason + ").");
        }
        return candidate;
    }

    private static TokenUsage usage(JsonNode response) {
        JsonNode meta = response.path("usageMetadata");
        long input = meta.path("promptTokenCount").asLong(0);
        long output = meta.path("candidatesTokenCount").asLong(0) + meta.path("thoughtsTokenCount").asLong(0);
        return new TokenUsage(input, output);
    }

    private static String stripFences(String text) {
        String trimmed = text.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                return trimmed.substring(firstNewline + 1, lastFence).trim();
            }
        }
        return trimmed;
    }
}
