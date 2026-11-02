package com.editora.config;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.jspecify.annotations.Nullable;

/**
 * The Project Map's trails (guided tours): ordered stops through a project's code, each a file, optionally one of
 * its declarations, a range of its lines and a note, kept in the project as {@code .editora/map-trails/<name>.json}
 * so a team can commit them beside {@code .editora/settings.json} and {@code .editora/run-configurations.json}.
 *
 * <p>As with {@link SharedRunConfigs}, the files are the team's: a read never fails the caller — a file that is not
 * a trail is listed with the reason, and left as it is — and every write is atomic ({@link ConfigWriter}). A file from
 * a newer Editora ({@code version} above {@link #VERSION}) is read but never written, so this build cannot drop what
 * that one keeps in it.
 */
public final class MapTrails {

    /** The folder under the project's {@code .editora/} that holds the trails. */
    public static final String FOLDER = "map-trails";

    public static final String SUFFIX = ".json";
    /** The file format's version. */
    public static final int VERSION = 1;
    /** Trails listed for one project, at most (a hand-filled folder must not build a menu of thousands). */
    public static final int MAX_TRAILS = 200;
    /** Stops a trail keeps, at most. */
    public static final int MAX_STOPS = 200;

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private MapTrails() {}

    /**
     * One stop. {@code file} is relative to the project with {@code /} separators; {@code declaration} is the Map's
     * path of a declaration below that file ({@code ""} for the file itself); the lines are 1-based and inclusive (0
     * for none); {@code title} is what the trail bar shows ({@code ""}: the declaration's or the file's name).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Stop(String file, String declaration, int startLine, int endLine, String title, String note) {
        public Stop {
            file = file == null ? "" : file.replace('\\', '/');
            declaration = declaration == null ? "" : declaration;
            startLine = Math.max(0, startLine);
            endLine = Math.max(startLine, endLine);
            title = title == null ? "" : title;
            note = note == null ? "" : note;
        }

        public Stop withTitle(String value) {
            return new Stop(file, declaration, startLine, endLine, value, note);
        }

        public Stop withNote(String value) {
            return new Stop(file, declaration, startLine, endLine, title, value);
        }
    }

    /** A trail as stored: its title (the name it was created under) and its stops, in order. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Trail(int version, String title, List<Stop> stops) {
        public Trail {
            title = title == null ? "" : title;
            stops = stops == null
                    ? List.of()
                    : stops.stream().filter(Objects::nonNull).limit(MAX_STOPS).toList();
        }

        public Trail(String title, List<Stop> stops) {
            this(VERSION, title, stops);
        }

        public Trail withStops(List<Stop> value) {
            return new Trail(version, title, value);
        }

        public Trail withTitle(String value) {
            return new Trail(version, value, stops);
        }
    }

    /**
     * A file of the trails folder: its {@code name} (the file name without {@code .json}), and either its trail or
     * why it is not one ({@code problem}). {@code readOnly} for a file from a newer Editora.
     */
    public record Listed(
            String name,
            Path file,
            @Nullable Trail trail,
            @Nullable String problem,
            boolean readOnly) {
        /** What the menus show: the trail's title, else the file's name. */
        public String label() {
            return trail != null && !trail.title().isBlank() ? trail.title() : name;
        }
    }

    /** The trails folder of a project. */
    public static Path folderFor(Path projectRoot) {
        return projectRoot.resolve(SharedRunConfigs.DIR).resolve(FOLDER);
    }

    /** The file a trail named {@code name} is kept in. */
    public static Path fileFor(Path projectRoot, String name) {
        return folderFor(projectRoot).resolve(fileName(name) + SUFFIX);
    }

    /**
     * A file-name-safe form of a trail's name, the same on every OS: blanks become {@code -}, characters Windows or a
     * shell would trip on are dropped, and the result is capped. Empty for a name with nothing usable. Pure.
     */
    public static String fileName(@Nullable String name) {
        String value = name == null ? "" : name.strip();
        StringBuilder out = new StringBuilder(Math.min(value.length(), 80));
        boolean dash = false;
        for (int i = 0; i < value.length() && out.length() < 80; i++) {
            char c = value.charAt(i);
            if (Character.isWhitespace(c) || c == '-' || c == '/' || c == '\\') {
                if (!out.isEmpty() && !dash) {
                    out.append('-');
                    dash = true;
                }
            } else if (Character.isLetterOrDigit(c) || c == '_' || c == '.') {
                out.append(c);
                dash = false;
            }
        }
        while (!out.isEmpty() && (out.charAt(out.length() - 1) == '-' || out.charAt(out.length() - 1) == '.')) {
            out.setLength(out.length() - 1);
        }
        while (!out.isEmpty() && out.charAt(0) == '.') {
            out.deleteCharAt(0); // never a hidden file
        }
        return out.toString();
    }

    /**
     * The project's trails, by label (ignoring case): every {@code .json} file of the folder, a trail or the reason it
     * is not one. Empty when the folder is absent or cannot be listed. Blocking file I/O.
     */
    public static List<Listed> list(Path projectRoot) {
        Path folder = folderFor(projectRoot);
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        List<Listed> out = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(folder, "*" + SUFFIX)) {
            for (Path file : files) {
                if (out.size() >= MAX_TRAILS) {
                    break;
                }
                if (Files.isRegularFile(file)) {
                    out.add(read(file));
                }
            }
        } catch (IOException | RuntimeException unreadable) {
            return List.of();
        }
        out.sort(Comparator.comparing((Listed listed) -> listed.label().toLowerCase(Locale.ROOT))
                .thenComparing(Listed::name));
        return out;
    }

    /** One trail file; never throws: a file that is not a trail comes back with its {@code problem}. */
    public static Listed read(Path file) {
        String fileName = file.getFileName() == null ? "" : file.getFileName().toString();
        String name = fileName.endsWith(SUFFIX) ? fileName.substring(0, fileName.length() - SUFFIX.length()) : fileName;
        try {
            JsonNode node = JSON.readTree(file.toFile());
            if (node == null || !node.isObject()) {
                return new Listed(name, file, null, "not a JSON object", false);
            }
            JsonNode stops = node.get("stops");
            if (stops != null && !stops.isNull() && !stops.isArray()) {
                return new Listed(name, file, null, "\"stops\" is not a list", false);
            }
            Trail trail = JSON.treeToValue(node, Trail.class);
            if (trail == null) {
                return new Listed(name, file, null, "empty", false);
            }
            if (trail.title().isBlank()) {
                trail = trail.withTitle(name);
            }
            return new Listed(name, file, trail, null, trail.version() > VERSION);
        } catch (IOException | RuntimeException malformed) {
            String reason =
                    malformed.getMessage() == null ? malformed.getClass().getSimpleName() : malformed.getMessage();
            int line = reason.indexOf('\n');
            return new Listed(name, file, null, line > 0 ? reason.substring(0, line) : reason, false);
        }
    }

    /** A file from a newer Editora, or one that is not a trail: writing it here would lose what it holds. */
    public static final class NotWritableException extends IOException {
        public NotWritableException(String message) {
            super(message);
        }
    }

    /**
     * Writes {@code trail} as {@code name}'s file, creating {@code .editora/map-trails/} if needed, atomically. Refuses
     * ({@link NotWritableException}) to replace a file that is not a trail or comes from a newer Editora. Returns the
     * file.
     */
    public static Path save(Path projectRoot, String name, Trail trail) throws IOException {
        String stem = fileName(name);
        if (stem.isEmpty()) {
            throw new IOException("no usable file name in \"" + name + "\"");
        }
        Path file = folderFor(projectRoot).resolve(stem + SUFFIX);
        refuseUnwritable(file);
        Files.createDirectories(file.getParent());
        Trail stored = new Trail(VERSION, trail.title(), trail.stops());
        ConfigWriter.writeAtomic(file, JSON, stored);
        return file;
    }

    /**
     * Moves trail {@code from} to the file of {@code to} (its title becomes {@code to}). Refuses when another trail
     * already has that file. Returns the new file.
     */
    public static Path rename(Path projectRoot, String from, String to, Trail trail) throws IOException {
        Path source = folderFor(projectRoot).resolve(from + SUFFIX);
        String stem = fileName(to);
        if (stem.isEmpty()) {
            throw new IOException("no usable file name in \"" + to + "\"");
        }
        Path target = folderFor(projectRoot).resolve(stem + SUFFIX);
        refuseUnwritable(source);
        if (!target.equals(source) && Files.exists(target)) {
            throw new java.nio.file.FileAlreadyExistsException(target.toString());
        }
        Path written = save(projectRoot, stem, trail.withTitle(to));
        if (!written.equals(source)) {
            Files.deleteIfExists(source);
        }
        return written;
    }

    private static void refuseUnwritable(Path file) throws IOException {
        if (!Files.exists(file)) {
            return;
        }
        Listed existing = read(file);
        if (existing.trail() == null) {
            throw new NotWritableException("not a trail: " + existing.problem());
        }
        if (existing.readOnly()) {
            throw new NotWritableException("version " + existing.trail().version());
        }
    }
}
