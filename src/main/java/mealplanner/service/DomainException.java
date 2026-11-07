package mealplanner.service;

import java.util.List;

/** A business-rule failure with one or more user-facing messages. The facade turns it into a Result. */
public class DomainException extends RuntimeException {

    private final List<String> messages;

    public DomainException(String message) {
        this(List.of(message));
    }

    public DomainException(List<String> messages) {
        super(String.join(" ", messages));
        this.messages = List.copyOf(messages);
    }

    public List<String> messages() {
        return messages;
    }
}
