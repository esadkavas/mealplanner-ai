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

    private Result(boolean success, T data, String message, List<String> errors) {
        this.success = success;
        this.data = data;
        this.message = message;
        this.errors = List.copyOf(errors);
    }

    public static <T> Result<T> success(T data, String message) {
        return new Result<>(true, data, message == null ? "" : message, List.of());
    }

    public static <T> Result<T> failure(List<String> errors) {
        return new Result<>(false, null, "", errors.isEmpty() ? List.of("Unknown error.") : errors);
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
}
