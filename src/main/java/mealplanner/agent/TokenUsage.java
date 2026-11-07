package mealplanner.agent;

/** Token counts reported by the provider for one call (zero when the provider does not report them). */
public record TokenUsage(long inputTokens, long outputTokens) {

    public static final TokenUsage NONE = new TokenUsage(0, 0);

    public TokenUsage plus(TokenUsage other) {
        return new TokenUsage(inputTokens + other.inputTokens, outputTokens + other.outputTokens);
    }

    public long total() {
        return inputTokens + outputTokens;
    }
}
