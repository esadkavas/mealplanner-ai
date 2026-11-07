package mealplanner.llm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import mealplanner.agent.AgentContext;
import mealplanner.agent.AgentDraft;
import mealplanner.agent.AgentStep;
import mealplanner.agent.LLMClient;
import mealplanner.agent.LLMException;
import mealplanner.agent.TaskType;
import mealplanner.agent.TokenUsage;

/** The decorator must delegate unchanged, preserve exceptions, and log no request text. */
public class LoggingLLMClientTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final String SECRET_REQUEST = "my private request about lentils";

    private static AgentContext context() {
        return new AgentContext(TaskType.PLAN, "system", SECRET_REQUEST, List.of(), Instant.now().plusSeconds(10));
    }

    @Test
    public void delegatesAndLogsSanitizedLines() throws Exception {
        AgentStep step = new AgentStep.FinalReady("READY", "{}", new TokenUsage(50, 5));
        AgentDraft draft = new AgentDraft(new ObjectMapper().createObjectNode(), new TokenUsage(70, 20));
        LLMClient inner = new LLMClient() {
            public AgentStep nextStep(AgentContext c) {
                return step;
            }

            public AgentDraft finish(AgentContext c, JsonNode s) {
                return draft;
            }

            public String describe() {
                return "Fake model";
            }
        };
        Path log = tmp.getRoot().toPath().resolve("logs/agent.log");
        LoggingLLMClient logging = new LoggingLLMClient(inner, log);

        assertSame(step, logging.nextStep(context()));
        assertSame(draft, logging.finish(context(), null));

        String text = Files.readString(log);
        assertTrue(text.contains("task=PLAN call=nextStep"));
        assertTrue(text.contains("outcome=FINAL_READY"));
        assertTrue(text.contains("call=finish"));
        assertFalse(text.contains(SECRET_REQUEST));
        assertEquals(2, logging.usage().calls());
        assertEquals(120, logging.usage().inputTokens());
        assertEquals(25, logging.usage().outputTokens());
    }

    @Test
    public void exceptionsPassThroughUnchanged() {
        LLMException failure = new LLMException(LLMException.Kind.TIMEOUT, "slow");
        LLMClient inner = new LLMClient() {
            public AgentStep nextStep(AgentContext c) throws LLMException {
                throw failure;
            }

            public AgentDraft finish(AgentContext c, JsonNode s) throws LLMException {
                throw failure;
            }

            public String describe() {
                return "Fake";
            }
        };
        LoggingLLMClient logging = new LoggingLLMClient(inner, null);
        assertSame(failure, assertThrows(LLMException.class, () -> logging.nextStep(context())));
        assertEquals(1, logging.usage().failures());
        assertTrue(logging.recentLines().get(0).contains("ERROR(TIMEOUT)"));
    }
}
