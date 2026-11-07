package mealplanner.agent;

import java.util.List;

/** The agent could not produce a usable draft. Nothing is saved. Carries the steps taken so far. */
public class AgentException extends Exception {

    private final List<String> trace;

    public AgentException(String message, List<String> trace) {
        super(message);
        this.trace = List.copyOf(trace);
    }

    public AgentException(String message, List<String> trace, Throwable cause) {
        super(message, cause);
        this.trace = List.copyOf(trace);
    }

    public List<String> trace() {
        return trace;
    }
}
