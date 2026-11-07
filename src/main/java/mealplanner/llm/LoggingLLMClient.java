package mealplanner.llm;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import com.fasterxml.jackson.databind.JsonNode;

import mealplanner.agent.AgentContext;
import mealplanner.agent.AgentDraft;
import mealplanner.agent.AgentStep;
import mealplanner.agent.LLMClient;
import mealplanner.agent.LLMException;
import mealplanner.agent.TokenUsage;

/**
 * Decorator pattern: wraps any {@link LLMClient}, delegates both calls unchanged, and records a
 * sanitized log line per call (task type, call, duration, outcome, token counts).
 *
 * <p>It never logs prompts, request text, tool arguments or credentials. Return values and
 * exceptions pass through exactly as the wrapped client produced them. It also keeps running
 * totals so the UI can show how many tokens this session has used.</p>
 */
public class LoggingLLMClient implements LLMClient {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int RECENT_LINES = 50;

    private final LLMClient wrapped;
    private final Path logFile;
    private final Deque<String> recent = new ArrayDeque<>();
    private final AtomicLong calls = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private final AtomicLong inputTokens = new AtomicLong();
    private final AtomicLong outputTokens = new AtomicLong();

    /** @param logFile file to append to, or null to keep log lines in memory only */
    public LoggingLLMClient(LLMClient wrapped, Path logFile) {
        this.wrapped = wrapped;
        this.logFile = logFile;
    }

    @Override
    public AgentStep nextStep(AgentContext context) throws LLMException {
        long start = System.nanoTime();
        try {
            AgentStep step = wrapped.nextStep(context);
            record(context, "nextStep", start, outcome(step), step.usage());
            return step;
        } catch (LLMException e) {
            failures.incrementAndGet();
            record(context, "nextStep", start, "ERROR(" + e.kind() + ")", TokenUsage.NONE);
            throw e;
        }
    }

    @Override
    public AgentDraft finish(AgentContext context, JsonNode schema) throws LLMException {
        long start = System.nanoTime();
        try {
            AgentDraft draft = wrapped.finish(context, schema);
            record(context, "finish", start, "DRAFT", draft.usage());
            return draft;
        } catch (LLMException e) {
            failures.incrementAndGet();
            record(context, "finish", start, "ERROR(" + e.kind() + ")", TokenUsage.NONE);
            throw e;
        }
    }

    @Override
    public String describe() {
        return wrapped.describe();
    }

    private static String outcome(AgentStep step) {
        if (step instanceof AgentStep.ToolStep tools) {
            List<String> names = new ArrayList<>();
            tools.calls().forEach(c -> names.add(c.name()));
            return "TOOL_CALL(" + String.join(",", names) + ")";
        }
        if (step instanceof AgentStep.FinalReady) {
            return "FINAL_READY";
        }
        return "INVALID";
    }

    private void record(AgentContext context, String call, long startNanos, String outcome, TokenUsage usage) {
        long millis = (System.nanoTime() - startNanos) / 1_000_000;
        calls.incrementAndGet();
        inputTokens.addAndGet(usage.inputTokens());
        outputTokens.addAndGet(usage.outputTokens());
        String line = LocalDateTime.now().format(TIME) + " model=" + wrapped.describe() + " task=" + context.taskType()
                + " call=" + call + " ms=" + millis + " outcome=" + outcome
                + " tokensIn=" + usage.inputTokens() + " tokensOut=" + usage.outputTokens();
        synchronized (recent) {
            recent.addLast(line);
            while (recent.size() > RECENT_LINES) {
                recent.removeFirst();
            }
        }
        if (logFile != null) {
            try {
                Files.createDirectories(logFile.getParent());
                Files.writeString(logFile, line + System.lineSeparator(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException ignored) {
                // Logging must never break the request it describes.
            }
        }
    }

    /** Totals for this session: model calls, failed calls and tokens. */
    public UsageSummary usage() {
        return new UsageSummary(calls.get(), failures.get(), inputTokens.get(), outputTokens.get());
    }

    public List<String> recentLines() {
        synchronized (recent) {
            return List.copyOf(recent);
        }
    }

    public record UsageSummary(long calls, long failures, long inputTokens, long outputTokens) {
    }
}
