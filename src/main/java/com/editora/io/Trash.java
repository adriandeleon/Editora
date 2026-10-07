package com.editora.io;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.editora.process.ProcessRunner;

/**
 * Moves a file to the operating system's trash instead of deleting it, without AWT.
 *
 * <p>{@code java.awt.Desktop.moveToTrash} is not an option here: Editora runs AWT headless on purpose (see
 * {@code App.main} — a live AWT toolkit deadlocks against JavaFX on macOS), and a headless {@code Desktop}
 * supports nothing; the JDK does not implement the action on Linux at all. So each platform's trash is
 * reached directly:
 *
 * <ul>
 *   <li><b>Linux and other XDG desktops</b> — the FreeDesktop.org Trash specification: the file is renamed
 *       into {@code $XDG_DATA_HOME/Trash/files} with a matching {@code info/<name>.trashinfo}, so the desktop's
 *       file manager lists it and can restore it. That "home trash" only takes files from its own volume; a
 *       file elsewhere goes through {@code gio trash} when GLib's tool is installed.
 *   <li><b>macOS</b> — the file is renamed into {@code ~/.Trash} (the user's trash for the home volume).
 *   <li><b>Windows</b> — not supported: the Recycle Bin is only reachable through the shell API.
 * </ul>
 *
 * <p>A {@link Bin} says up front whether it {@linkplain Bin#accepts takes} a file, so a confirmation can state
 * truthfully whether the file will be recoverable. {@link Bin#trash} never deletes: when the move cannot be
 * done it throws and the file stays where it was.
 */
public final class Trash {

    /** System property: {@code off} disables the trash (the test suite sets it, to keep test files out of it). */
    public static final String PROPERTY = "editora.trash";

    /** One trash location. */
    public interface Bin {
        /** Whether {@link #trash} is expected to work for {@code file}. Cheap; no side effects. */
        boolean accepts(Path file);

        /** Moves {@code file} to the trash, or throws leaving it in place. */
        void trash(Path file) throws IOException;
    }

    /** No trash on this platform: nothing is accepted. */
    public static final Bin NONE = new Bin() {
        @Override
        public boolean accepts(Path file) {
            return false;
        }

        @Override
        public void trash(Path file) throws IOException {
            throw new IOException("No trash is available for " + file);
        }
    };

    private static final Duration GIO_TIMEOUT = Duration.ofSeconds(15);
    private static final DateTimeFormatter DELETION_DATE = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private static volatile Bin system;

    private Trash() {}

    /** The trash of the platform Editora is running on ({@link #NONE} where there is none). */
    public static Bin system() {
        Bin bin = system;
        if (bin == null) {
            bin = forPlatform(
                    System.getProperty("os.name", ""),
                    System.getProperty("user.home"),
                    System.getenv(),
                    System.getProperty(PROPERTY));
            system = bin;
        }
        return bin;
    }

    /** Chooses the trash for a platform. Pure apart from looking for {@code gio} on the given PATH. */
    static Bin forPlatform(String osName, String userHome, Map<String, String> env, String property) {
        if ("off".equalsIgnoreCase(property == null ? "" : property.strip())) {
            return NONE;
        }
        if (userHome == null || userHome.isBlank()) {
            return NONE;
        }
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        Path home;
        try {
            home = Path.of(userHome);
            if (os.contains("win")) {
                return NONE;
            }
            if (os.contains("mac") || os.contains("darwin")) {
                return directory(home.resolve(".Trash"));
            }
            String dataHome = env == null ? null : env.get("XDG_DATA_HOME");
            Path data =
                    dataHome == null || dataHome.isBlank() || !Path.of(dataHome).isAbsolute()
                            ? home.resolve(".local").resolve("share")
                            : Path.of(dataHome);
            return freedesktop(data.resolve("Trash"), findOnPath("gio", env == null ? null : env.get("PATH")));
        } catch (RuntimeException invalidPath) {
            return NONE;
        }
    }

    /**
     * A FreeDesktop.org home trash at {@code trashDir} ({@code files/} + {@code info/}). {@code gio}, when not
     * null, is GLib's command-line tool, used for files on another volume than the trash directory.
     */
    public static Bin freedesktop(Path trashDir, Path gio) {
        return new Bin() {
            @Override
            public boolean accepts(Path file) {
                return trashable(file) && (sameVolume(file, trashDir) || gio != null);
            }

            @Override
            public void trash(Path file) throws IOException {
                Path source = requireTrashable(file);
                if (sameVolume(source, trashDir)) {
                    moveToFreedesktopTrash(source, trashDir);
                } else if (gio != null) {
                    gioTrash(gio, source);
                } else {
                    throw new IOException("The trash cannot take files from this volume: " + source);
                }
            }
        };
    }

    /** A trash that is a plain directory on the same volume as the files it takes (macOS {@code ~/.Trash}). */
    public static Bin directory(Path trashDir) {
        return new Bin() {
            @Override
            public boolean accepts(Path file) {
                return trashable(file) && sameVolume(file, trashDir);
            }

            @Override
            public void trash(Path file) throws IOException {
                Path source = requireTrashable(file);
                if (!sameVolume(source, trashDir)) {
                    throw new IOException("The trash cannot take files from this volume: " + source);
                }
                Files.createDirectories(trashDir);
                String name = source.getFileName().toString();
                for (int attempt = 0; attempt < 1000; attempt++) {
                    Path target = trashDir.resolve(numbered(name, attempt));
                    try {
                        // No REPLACE_EXISTING: an entry of that name already in the trash is kept.
                        Files.move(source, target);
                        return;
                    } catch (FileAlreadyExistsException taken) {
                        // next name
                    }
                }
                throw new IOException("No free name in the trash for " + name);
            }
        };
    }

    private static boolean trashable(Path file) {
        return file != null
                && file.getFileSystem() == FileSystems.getDefault()
                && file.getFileName() != null
                && Files.exists(file, LinkOption.NOFOLLOW_LINKS);
    }

    private static Path requireTrashable(Path file) throws IOException {
        if (!trashable(file)) {
            throw new IOException("Cannot move to the trash: " + file);
        }
        return file.toAbsolutePath().normalize();
    }

    /** Whether {@code file} lives on the volume that holds (or will hold) {@code trashDir}. */
    static boolean sameVolume(Path file, Path trashDir) {
        try {
            Path existing = trashDir.toAbsolutePath();
            while (existing != null && !Files.exists(existing)) {
                existing = existing.getParent();
            }
            Path parent = file.toAbsolutePath().getParent();
            if (existing == null || parent == null) {
                return false;
            }
            return Files.getFileStore(parent).equals(Files.getFileStore(existing));
        } catch (IOException | RuntimeException unknown) {
            return false;
        }
    }

    private static void moveToFreedesktopTrash(Path source, Path trashDir) throws IOException {
        Path files = trashDir.resolve("files");
        Path info = trashDir.resolve("info");
        createPrivateDirectories(trashDir);
        Files.createDirectories(files);
        Files.createDirectories(info);
        String name = source.getFileName().toString();
        String record = trashInfo(source, LocalDateTime.now());
        for (int attempt = 0; attempt < 1000; attempt++) {
            String candidate = numbered(name, attempt);
            Path infoFile = info.resolve(candidate + ".trashinfo");
            Path target = files.resolve(candidate);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            try {
                // The specification's order: the info file reserves the name (exclusive create), then the move.
                Files.writeString(infoFile, record, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            } catch (FileAlreadyExistsException taken) {
                continue;
            }
            try {
                Files.move(source, target); // no REPLACE_EXISTING: never onto an entry already in the trash
            } catch (IOException | RuntimeException failure) {
                Files.deleteIfExists(infoFile);
                throw failure;
            }
            return;
        }
        throw new IOException("No free name in the trash for " + name);
    }

    private static void createPrivateDirectories(Path trashDir) throws IOException {
        if (Files.isDirectory(trashDir)) {
            return;
        }
        Path parent = trashDir.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try {
            Files.createDirectory(
                    trashDir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        } catch (UnsupportedOperationException notPosix) {
            Files.createDirectories(trashDir);
        } catch (FileAlreadyExistsException raced) {
            // another process created it first
        }
    }

    /** The {@code .trashinfo} record for {@code original}, as the specification defines it. Pure. */
    static String trashInfo(Path original, LocalDateTime deleted) {
        return "[Trash Info]\nPath=" + encodePath(original.toAbsolutePath().toString()) + "\nDeletionDate="
                + DELETION_DATE.format(deleted.truncatedTo(ChronoUnit.SECONDS)) + "\n";
    }

    /** Percent-encodes a path as RFC 2396 asks, leaving {@code /} between the segments. Pure. */
    static String encodePath(String path) {
        StringBuilder out = new StringBuilder(path.length() + 16);
        for (byte b : path.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            boolean unreserved = (c >= 'A' && c <= 'Z')
                    || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9')
                    || c == '-'
                    || c == '.'
                    || c == '_'
                    || c == '~'
                    || c == '/';
            if (unreserved) {
                out.append((char) c);
            } else {
                out.append('%')
                        .append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(c & 0xF, 16)));
            }
        }
        return out.toString();
    }

    /** {@code name} for attempt 0, then {@code stem.2.ext}, {@code stem.3.ext}, … Pure. */
    static String numbered(String name, int attempt) {
        if (attempt == 0) {
            return name;
        }
        int dot = name.lastIndexOf('.');
        return dot > 0
                ? name.substring(0, dot) + "." + (attempt + 1) + name.substring(dot)
                : name + "." + (attempt + 1);
    }

    private static void gioTrash(Path gio, Path source) throws IOException {
        ProcessRunner.Result result =
                ProcessRunner.run(source.getParent(), GIO_TIMEOUT, List.of(gio.toString(), "trash", source.toString()));
        if (!result.ok() || Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
            String message = result.message();
            throw new IOException(message == null || message.isBlank() ? "gio trash failed" : message.strip());
        }
    }

    /** The first executable called {@code name} in {@code pathVariable}, or null. */
    static Path findOnPath(String name, String pathVariable) {
        if (pathVariable == null || pathVariable.isBlank()) {
            return null;
        }
        for (String dir : pathVariable.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            if (dir.isBlank()) {
                continue;
            }
            try {
                Path candidate = Path.of(dir).resolve(name);
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                    return candidate;
                }
            } catch (RuntimeException invalidEntry) {
                // an unusable PATH entry is skipped
            }
        }
        return null;
    }
}
