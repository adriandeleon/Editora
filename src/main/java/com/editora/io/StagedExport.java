package com.editora.io;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Writes a generated file (an export) beside its destination and renames it into place once it is complete.
 *
 * <p>An export target is usually confirmed by the operating system's Save dialog, which asks before an
 * existing file is replaced. Writing straight to that path truncates the previous version first, so a failure
 * partway — a full disk, a quota, a network share that drops, an external tool that exits with an error —
 * leaves the user with neither the old file nor the new one. A staged export keeps the previous version until
 * the new one exists in full.
 *
 * <p>The staging file has the destination's own file name, inside a private directory next to it: external
 * tools pick the output format from the extension, and a sibling directory is on the same volume, so the
 * final rename is atomic where the platform allows. When that directory cannot be created (a read-only folder
 * holding a writable file) the system temp directory is used and the last step becomes a copy.
 */
public final class StagedExport implements AutoCloseable {

    /** Produces the export at the given staging path. */
    @FunctionalInterface
    public interface Producer {
        void writeTo(Path staging) throws IOException;
    }

    private final Path destination;
    private final Path stagingDir;
    private final Path staging;
    private boolean finished;

    private StagedExport(Path destination, Path stagingDir) {
        this.destination = destination;
        this.stagingDir = stagingDir;
        this.staging = stagingDir.resolve(destination.getFileName().toString());
    }

    /** Opens a staging location for {@code destination}; nothing at the destination is touched yet. */
    public static StagedExport begin(Path destination) throws IOException {
        Path target = destination.toAbsolutePath();
        if (target.getFileName() == null) {
            throw new IOException("Not a file path: " + destination);
        }
        Path parent = target.getParent();
        Path dir;
        try {
            if (parent == null) {
                throw new IOException("No parent directory: " + destination);
            }
            Files.createDirectories(parent);
            dir = Files.createTempDirectory(parent, ".editora-export-");
        } catch (IOException | RuntimeException besideUnavailable) {
            dir = Files.createTempDirectory("editora-export-");
        }
        return new StagedExport(target, dir);
    }

    /** Where the export must be written. */
    public Path path() {
        return staging;
    }

    /** The file the export replaces on {@link #commit()}. */
    public Path destination() {
        return destination;
    }

    /**
     * Moves the finished staging file over the destination. Fails — leaving the destination as it was — when
     * nothing was written to the staging path.
     */
    public void commit() throws IOException {
        if (finished) {
            throw new IOException("Export already finished: " + destination);
        }
        try {
            if (!Files.isRegularFile(staging, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Nothing was written for " + destination.getFileName());
            }
            keepPermissions();
            try {
                Files.move(staging, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException otherVolume) {
                Files.move(staging, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            close();
        }
    }

    /** Drops the staging file and its directory; the destination is untouched. Safe to call repeatedly. */
    @Override
    public void close() {
        if (finished) {
            return;
        }
        finished = true;
        deleteTree(stagingDir);
    }

    /** A replaced file keeps the permissions the user gave it (a new file gets the platform default). */
    private void keepPermissions() {
        try {
            if (Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS)) {
                Files.setPosixFilePermissions(staging, Files.getPosixFilePermissions(destination));
            }
        } catch (IOException | RuntimeException notPosix) {
            // not a POSIX file system, or not ours to change: the default permissions stand
        }
    }

    /** Stages {@code bytes} and replaces {@code destination} with them. */
    public static void write(Path destination, byte[] bytes) throws IOException {
        writeVia(destination, staging -> Files.write(staging, bytes));
    }

    /** Stages {@code text} as UTF-8 and replaces {@code destination} with it. */
    public static void writeString(Path destination, String text) throws IOException {
        write(destination, text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Lets {@code producer} write the export to a staging path, then replaces {@code destination} with the
     * result. When the producer throws, the destination keeps its previous content.
     */
    public static void writeVia(Path destination, Producer producer) throws IOException {
        try (StagedExport export = begin(destination)) {
            producer.writeTo(export.path());
            export.commit();
        }
    }

    /** Removes a staging directory created by this class (never follows links). */
    public static void deleteTree(Path dir) {
        if (dir == null || !Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best-effort cleanup of our own staging files
                }
            });
        } catch (IOException ignored) {
            // best-effort cleanup of our own staging files
        }
    }
}
