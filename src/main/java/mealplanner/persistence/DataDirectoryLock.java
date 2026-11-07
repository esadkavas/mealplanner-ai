package mealplanner.persistence;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Makes one process the owner of the data directory. A second GUI or CLI opened on the same
 * directory is refused instead of risking two writers overwriting each other's changes.
 */
public final class DataDirectoryLock implements AutoCloseable {

    private final FileChannel channel;
    private final FileLock lock;

    private DataDirectoryLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    public static DataDirectoryLock acquire(Path directory) throws StorageException {
        FileChannel channel = null;
        try {
            Files.createDirectories(directory);
            channel = FileChannel.open(directory.resolve("mealplanner.lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock lock = channel.tryLock();
            if (lock == null) {
                channel.close();
                throw new StorageException(inUseMessage(directory));
            }
            return new DataDirectoryLock(channel, lock);
        } catch (OverlappingFileLockException e) {
            closeQuietly(channel);
            throw new StorageException(inUseMessage(directory), e);
        } catch (IOException e) {
            closeQuietly(channel);
            throw new StorageException("Could not lock data directory " + directory + ": " + e.getMessage(), e);
        }
    }

    private static String inUseMessage(Path directory) {
        return "The data directory " + directory.toAbsolutePath()
                + " is already in use by another MealPlanner window or CLI session. Close it and try again.";
    }

    private static void closeQuietly(FileChannel channel) {
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException ignored) {
                // nothing else to do
            }
        }
    }

    @Override
    public void close() {
        try {
            lock.release();
        } catch (IOException ignored) {
            // released when the channel closes anyway
        }
        closeQuietly(channel);
    }
}
