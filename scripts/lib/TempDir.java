package lib;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

/** A scratch directory that {@link #close()} deletes with everything in it. */
public final class TempDir implements AutoCloseable {

    private final Path path;

    public TempDir() {
        try {
            path = Files.createTempDirectory("editora-script-test").toRealPath();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Path path() {
        return path;
    }

    public Path resolve(String other) {
        return path.resolve(other);
    }

    @Override
    public void close() {
        delete(path);
    }

    /** Deletes a file or a directory tree; symbolic links are removed, never followed. */
    public static void delete(Path root) {
        if (!Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    // A test may have made a directory read-only; deleting what is inside needs write access.
                    dir.toFile().setWritable(true);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException failure) throws IOException {
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
