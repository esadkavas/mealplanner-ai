package mealplanner.facade;

import java.util.List;

/**
 * Every public PlannerFacade operation returns a Result: either data plus a short message, or a
 * list of user-facing errors. Interfaces never need to catch domain or storage exceptions.
 */
public final class Result<T> {

    private final boolean success;
    private final T data;
    private final String message;
    private final List<String> errors;
    private final List<String> details;

    private Result(boolean success, T data, String message, List<String> errors, List<String> details) {
        this.success = success;
        this.data = data;
        this.message = message;
        this.errors = List.copyOf(errors);
        this.details = details == null ? List.of() : List.copyOf(details);
    }

    public static <T> Result<T> success(T data, String message) {
        return new Result<>(true, data, message == null ? "" : message, List.of(), List.of());
    }

    public static <T> Result<T> failure(List<String> errors) {
        return failure(errors, List.of());
    }

    /** A failure that also carries supporting details, such as the AI agent's steps. */
    public static <T> Result<T> failure(List<String> errors, List<String> details) {
        return new Result<>(false, null, "", errors.isEmpty() ? List.of("Unknown error.") : errors, details);
    }

    public static <T> Result<T> failure(String error) {
        return failure(List.of(error));
    }

    public boolean isSuccess() {
        return success;
    }

    public T data() {
        return data;
    }

    public String message() {
        return message;
    }

    public List<String> errors() {
        return errors;
    }

    /** Supporting information, e.g. the steps an AI request took before it failed. */
    public List<String> details() {
        return details;
    }
}
