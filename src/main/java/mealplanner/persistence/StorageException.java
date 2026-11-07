package mealplanner.persistence;

/** A load, save or lock failure. The active in-memory state is never changed when this is thrown. */
public class StorageException extends Exception {

    public StorageException(String message) {
        super(message);
    }

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
