package mealplanner.agent;

/** A provider failure with a user-facing message that never contains credentials. */
public class LLMException extends Exception {

    public enum Kind {
        NOT_CONFIGURED,
        AUTH,
        MODEL_NOT_FOUND,
        RATE_LIMITED,
        TIMEOUT,
        UNAVAILABLE,
        BAD_REQUEST,
        BLOCKED,
        FORMAT
    }

    private final Kind kind;

    public LLMException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public LLMException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
