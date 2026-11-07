package mealplanner.persistence;

import java.util.Optional;

import mealplanner.domain.AppState;

/** Persistence abstraction used by the facade; JsonStateStore is the implementation. */
public interface StateStore {

    /** Returns the saved state, or empty if nothing has been saved yet. Invalid data is an error. */
    Optional<AppState> load() throws StorageException;

    /** Saves the whole state; on failure the previously saved file is left unchanged. */
    void save(AppState state) throws StorageException;
}
