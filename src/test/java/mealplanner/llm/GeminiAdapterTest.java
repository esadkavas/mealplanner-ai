package mealplanner.llm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import mealplanner.agent.AgentContext;
import mealplanner.agent.AgentDraft;
import mealplanner.agent.AgentStep;
import mealplanner.agent.LLMException;
import mealplanner.agent.TaskType;
import mealplanner.agent.ToolResult;
import mealplanner.agent.ToolSpec;

public class GeminiAdapterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String KEY = "test-key-123";
    private FakeGeminiServer server;

    @Before
    public void start() throws Exception {
        server = new FakeGeminiServer();
    }

    @After
    public void stop() {
        server.close();
    }

    private GeminiAdapter adapter(Duration callTimeout) {
        return new GeminiAdapter(KEY, "gemini-test", server.baseUrl(), callTimeout);
    }

    private static AgentContext context() {
        ObjectNode params = MAPPER.createObjectNode();
        params.put("type", "object");
        params.putObject("properties").putObject("text").put("type", "string");
        return new AgentContext(TaskType.RECOMMEND, "system rules", "Find a quick vegan dinner.",
                List.of(new ToolSpec("searchRecipes", "Search stored recipes.", params)),
                Instant.now().plusSeconds(30));
    }

    @Test
    public void functionCallBecomesToolStepAndKeyIsOnlyInHeader() throws Exception {
        server.reply(200, FakeGeminiServer.functionCall("searchRecipes", "{\"text\":\"soup\"}", "sigA"));
        AgentStep step = adapter(Duration.ofSeconds(5)).nextStep(context());

        assertTrue(step instanceof AgentStep.ToolStep);
        AgentStep.ToolStep tools = (AgentStep.ToolStep) step;
        assertEquals("searchRecipes", tools.calls().get(0).name());
        assertEquals("soup", tools.calls().get(0).arguments().path("text").asText());
        assertEquals(100, step.usage().inputTokens());
        assertEquals(15, step.usage().outputTokens());

        FakeGeminiServer.Received request = server.received.get(0);
        assertEquals("/v1beta/models/gemini-test:generateContent", request.path());
        assertNull(request.query());
        assertEquals(KEY, request.apiKeyHeader());
        JsonNode body = MAPPER.readTree(request.body());
        assertEquals("searchRecipes", body.at("/tools/0/functionDeclarations/0/name").asText());
        assertEquals("OBJECT", body.at("/tools/0/functionDeclarations/0/parameters/type").asText());
        assertEquals("STRING", body.at("/tools/0/functionDeclarations/0/parameters/properties/text/type").asText());
        assertEquals("AUTO", body.at("/toolConfig/functionCallingConfig/mode").asText());
        assertEquals("system rules", body.at("/systemInstruction/parts/0/text").asText());
        assertEquals("Find a quick vegan dinner.", body.at("/contents/0/parts/0/text").asText());
    }

    @Test
    public void secondRequestReplaysThoughtSignatureAndFunctionResponse() throws Exception {
        server.reply(200, FakeGeminiServer.functionCall("searchRecipes", "{}", "sigA"))
              .reply(200, FakeGeminiServer.text("READY"));
        GeminiAdapter adapter = adapter(Duration.ofSeconds(5));
        AgentContext context = context();
        AgentStep first = adapter.nextStep(context);
        context.add(new AgentContext.ModelTurn(first));
        ObjectNode data = MAPPER.createObjectNode().put("count", 2);
        context.add(new AgentContext.ToolResults(List.of(new ToolResult("searchRecipes", null, true, data,
                List.of(), Set.of(), Set.of(), "2 recipes"))));
        context.add(new AgentContext.Note("Remember the time limit."));
        AgentStep second = adapter.nextStep(context);

        assertTrue(second instanceof AgentStep.FinalReady);
        assertEquals("READY", ((AgentStep.FinalReady) second).text());
        JsonNode contents = MAPPER.readTree(server.received.get(1).body()).path("contents");
        assertEquals(3, contents.size());
        assertEquals("model", contents.get(1).path("role").asText());
        assertEquals("sigA", contents.get(1).at("/parts/0/thoughtSignature").asText());
        assertEquals("user", contents.get(2).path("role").asText());
        assertEquals("searchRecipes", contents.get(2).at("/parts/0/functionResponse/name").asText());
        assertEquals(2, contents.get(2).at("/parts/0/functionResponse/response/data/count").asInt());
        assertEquals("Remember the time limit.", contents.get(2).at("/parts/1/text").asText());
    }

    @Test
    public void finishRequestsStructuredJsonWithoutTools() throws Exception {
        server.reply(200, FakeGeminiServer.text("{\"status\":\"OK\",\"recommendations\":[]}"));
        ObjectNode schema = MAPPER.createObjectNode().put("type", "object");
        AgentDraft draft = adapter(Duration.ofSeconds(5)).finish(context(), schema);

        assertEquals("OK", draft.json().path("status").asText());
        JsonNode body = MAPPER.readTree(server.received.get(0).body());
        assertEquals("application/json", body.at("/generationConfig/responseMimeType").asText());
        assertEquals("OBJECT", body.at("/generationConfig/responseSchema/type").asText());
        assertTrue(body.path("tools").isMissingNode());
        assertTrue(body.at("/contents/0/parts/0/text").asText().contains("Find a quick vegan dinner."));
    }

    @Test
    public void nestedSchemasAreTranslatedWithoutChangingTheOriginal() {
        JsonNode original = mealplanner.agent.AgentPrompts.planSchema();
        JsonNode gemini = GeminiAdapter.toGeminiSchema(original);
        assertEquals("ARRAY", gemini.at("/properties/entries/type").asText());
        assertEquals("OBJECT", gemini.at("/properties/entries/items/type").asText());
        assertEquals("INTEGER", gemini.at("/properties/entries/items/properties/day/type").asText());
        assertEquals("BREAKFAST", gemini.at("/properties/entries/items/properties/slot/enum/0").asText());
        assertEquals("object", original.path("type").asText());
    }

    @Test
    public void fencedJsonIsAccepted() throws Exception {
        server.reply(200, FakeGeminiServer.text("```json\n{\"status\":\"OK\"}\n```"));
        assertEquals("OK", adapter(Duration.ofSeconds(5)).finish(context(), MAPPER.createObjectNode())
                .json().path("status").asText());
    }

    @Test
    public void invalidFinalJsonIsFormatError() {
        server.reply(200, FakeGeminiServer.text("Sure! Here are some recipes."));
        LLMException e = assertThrows(LLMException.class,
                () -> adapter(Duration.ofSeconds(5)).finish(context(), MAPPER.createObjectNode()));
        assertEquals(LLMException.Kind.FORMAT, e.kind());
    }

    @Test
    public void httpErrorsMapToClearKinds() {
        server.reply(400, "{\"error\":{\"code\":400,\"message\":\"API key not valid. Please pass a valid API key.\","
                + "\"status\":\"INVALID_ARGUMENT\",\"details\":[{\"reason\":\"API_KEY_INVALID\"}]}}")
              .reply(429, rateLimited("120s"))
              .reply(404, "{\"error\":{\"code\":404,\"message\":\"models/gemini-test is not found\"}}")
              .reply(503, "{}")
              .reply(400, "{\"error\":{\"code\":400,\"message\":\"Bad schema near " + KEY + "\"}}");
        GeminiAdapter adapter = adapter(Duration.ofSeconds(5));
        assertEquals(LLMException.Kind.AUTH, assertThrows(LLMException.class, () -> adapter.nextStep(context())).kind());
        assertEquals(LLMException.Kind.RATE_LIMITED, assertThrows(LLMException.class, () -> adapter.nextStep(context())).kind());
        assertEquals(LLMException.Kind.MODEL_NOT_FOUND, assertThrows(LLMException.class, () -> adapter.nextStep(context())).kind());
        assertEquals(LLMException.Kind.UNAVAILABLE, assertThrows(LLMException.class, () -> adapter.nextStep(context())).kind());
        LLMException bad = assertThrows(LLMException.class, () -> adapter.nextStep(context()));
        assertEquals(LLMException.Kind.BAD_REQUEST, bad.kind());
        assertFalse("the key must never appear in error messages", bad.getMessage().contains(KEY));
    }

    private static String rateLimited(String delay) {
        return "{\"error\":{\"code\":429,\"message\":\"Resource exhausted\",\"status\":\"RESOURCE_EXHAUSTED\","
                + "\"details\":[{\"@type\":\"type.googleapis.com/google.rpc.RetryInfo\",\"retryDelay\":\"" + delay + "\"}]}}";
    }

    @Test
    public void rateLimitIsRetriedOnceAfterSuggestedDelay() throws Exception {
        server.reply(429, rateLimited("1s")).reply(200, FakeGeminiServer.text("READY"));
        long start = System.nanoTime();
        AgentStep step = adapter(Duration.ofSeconds(5)).nextStep(context());
        long waitedMillis = (System.nanoTime() - start) / 1_000_000;
        assertTrue(step instanceof AgentStep.FinalReady);
        assertEquals(2, server.received.size());
        assertTrue("should wait about the suggested delay", waitedMillis >= 900);
    }

    @Test
    public void rateLimitTwiceOrTooLongDelayIsReported() {
        server.reply(429, rateLimited("1s")).reply(429, rateLimited("1s"));
        LLMException twice = assertThrows(LLMException.class, () -> adapter(Duration.ofSeconds(5)).nextStep(context()));
        assertEquals(LLMException.Kind.RATE_LIMITED, twice.kind());
        assertTrue(twice.getMessage().contains("even after waiting"));

        server.reply(429, rateLimited("120s"));
        int before = server.received.size();
        LLMException tooLong = assertThrows(LLMException.class, () -> adapter(Duration.ofSeconds(5)).nextStep(context()));
        assertEquals(LLMException.Kind.RATE_LIMITED, tooLong.kind());
        assertEquals("no retry when the suggested wait is too long", before + 1, server.received.size());
    }

    @Test
    public void retryDelayIsParsedFromRetryInfo() {
        assertEquals(Duration.ofMillis(23500), GeminiAdapter.retryDelay(rateLimited("23.5s")).orElseThrow());
        assertTrue(GeminiAdapter.retryDelay("{}").isEmpty());
        assertTrue(GeminiAdapter.retryDelay("not json").isEmpty());
    }

    @Test
    public void slowProviderTimesOut() {
        server.reply(200, FakeGeminiServer.text("READY"), 2000);
        LLMException e = assertThrows(LLMException.class, () -> adapter(Duration.ofMillis(300)).nextStep(context()));
        assertEquals(LLMException.Kind.TIMEOUT, e.kind());
    }

    @Test
    public void missingKeyFailsWithoutNetworkCall() {
        GeminiAdapter noKey = new GeminiAdapter("", "gemini-test", server.baseUrl(), Duration.ofSeconds(5));
        LLMException e = assertThrows(LLMException.class, () -> noKey.nextStep(context()));
        assertEquals(LLMException.Kind.NOT_CONFIGURED, e.kind());
        assertTrue(server.received.isEmpty());
    }

    @Test
    public void malformedFunctionCallIsInvalidStep() throws Exception {
        server.reply(200, "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[]},\"finishReason\":\"MALFORMED_FUNCTION_CALL\"}]}");
        assertTrue(adapter(Duration.ofSeconds(5)).nextStep(context()) instanceof AgentStep.Invalid);
    }

    @Test
    public void blockedPromptIsReported() {
        server.reply(200, "{\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}");
        assertEquals(LLMException.Kind.BLOCKED,
                assertThrows(LLMException.class, () -> adapter(Duration.ofSeconds(5)).nextStep(context())).kind());
    }

    @Test
    public void connectionCheckPassesAgainstWellBehavedProvider() {
        server.reply(200, FakeGeminiServer.functionCall("echoNumber", "{\"value\":7}", "sigX"))
              .reply(200, FakeGeminiServer.text("READY"))
              .reply(200, FakeGeminiServer.text("{\"echoedValue\":7}"));
        ConnectionCheck.Report report = ConnectionCheck.run(adapter(Duration.ofSeconds(5)), Duration.ofSeconds(30));
        assertTrue(String.join("\n", report.lines()), report.passed());
        assertEquals(3, server.received.size());
    }
}
