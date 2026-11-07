package mealplanner.persistence;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;

import mealplanner.domain.AppState;

/**
 * Saves the whole {@link AppState} as one JSON file.
 *
 * <p>Each save writes a temporary file in the same directory, forces it to disk and then
 * atomically replaces the saved file. If any step fails, or the file system cannot replace
 * atomically, the old file is untouched and a {@link StorageException} is thrown.</p>
 */
public class JsonStateStore implements StateStore {

    private final Path filePath;

    public JsonStateStore(Path filePath) {
        this.filePath = filePath.toAbsolutePath();
    }

    public Path filePath() {
        return filePath;
    }

    @Override
    public Optional<AppState> load() throws StorageException {
        if (!Files.exists(filePath)) {
            return Optional.empty();
        }
        try {
            byte[] bytes = Files.readAllBytes(filePath);
            AppState state = Json.mapper().readValue(bytes, AppState.class);
            if (state == null) {
                throw new StorageException("Saved data in " + filePath + " is empty.");
            }
            if (state.getSchemaVersion() != AppState.SCHEMA_VERSION) {
                throw new StorageException("Saved data in " + filePath + " uses unsupported schema version "
                        + state.getSchemaVersion() + ".");
            }
            return Optional.of(state);
        } catch (JsonProcessingException e) {
            throw new StorageException("Saved data in " + filePath + " is not valid MealPlanner JSON ("
                    + e.getOriginalMessage() + "). It was not replaced; fix or move the file and restart.", e);
        } catch (IOException e) {
            throw new StorageException("Could not read " + filePath + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void save(AppState state) throws StorageException {
        Path directory = filePath.getParent();
        Path temp = null;
        try {
            Files.createDirectories(directory);
            byte[] bytes = Json.mapper().writeValueAsBytes(state);
            temp = Files.createTempFile(directory, "state-", ".json.tmp");
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            Files.move(temp, filePath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            temp = null;
        } catch (AtomicMoveNotSupportedException e) {
            throw new StorageException("This file system cannot replace " + filePath
                    + " atomically, so nothing was saved.", e);
        } catch (IOException e) {
            throw new StorageException("Could not save " + filePath + ": " + e.getMessage()
                    + ". Your previous data is unchanged.", e);
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // best effort cleanup of the temporary file
                }
            }
        }
    }
}
