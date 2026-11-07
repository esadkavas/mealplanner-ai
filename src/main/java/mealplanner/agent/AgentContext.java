package mealplanner.agent;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Short-term memory for one agent request: the task, the fixed constraints (in the task
 * prompt), the permitted tools, the transcript of model steps and tool results, and the overall
 * deadline. Long-term memory is the saved AppState, read through tools.
 */
public final class AgentContext {

    /** One entry in the transcript, in order. */
    public sealed interface Message permits ModelTurn, ToolResults, Note {
    }

    /** A model response (tool request, ready signal or invalid output). */
    public record ModelTurn(AgentStep step) implements Message {
    }

    /** Results for the tool calls in the preceding model turn. */
    public record ToolResults(List<ToolResult> results) implements Message {
        public ToolResults {
            results = List.copyOf(results);
        }
    }

    /** Guidance from the coordinator, e.g. "retrieve candidates before finishing". */
    public record Note(String text) implements Message {
    }

    private final TaskType taskType;
    private final String systemPrompt;
    private final String taskPrompt;
    private final List<ToolSpec> tools;
    private final List<Message> transcript = new ArrayList<>();
    private final Instant deadline;

    public AgentContext(TaskType taskType, String systemPrompt, String taskPrompt, List<ToolSpec> tools, Instant deadline) {
        this.taskType = taskType;
        this.systemPrompt = systemPrompt;
        this.taskPrompt = taskPrompt;
        this.tools = List.copyOf(tools);
        this.deadline = deadline;
    }

    public void add(Message message) {
        transcript.add(message);
    }

    public TaskType taskType() {
        return taskType;
    }

    public String systemPrompt() {
        return systemPrompt;
    }

    public String taskPrompt() {
        return taskPrompt;
    }

    public List<ToolSpec> tools() {
        return tools;
    }

    public List<Message> transcript() {
        return Collections.unmodifiableList(transcript);
    }

    public Instant deadline() {
        return deadline;
    }

    public Duration remaining() {
        return Duration.between(Instant.now(), deadline);
    }
}
