package com.editora.editorconfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Resolves the effective {@link EditorConfigProperties} for a file by walking up its directory tree, reading
 * each {@code .editorconfig} (parsed by {@link EditorConfigParser}, cached by modified-time), matching its
 * {@code [glob]} sections ({@link EditorConfigGlob}) against the file's relative path, and merging with
 * nearest-directory-wins precedence — stopping at the first {@code root = true} file. The only class here
 * that touches the filesystem; the parser/glob/merge it drives are pure.
 */
public final class EditorConfig {

    private static final String FILENAME = ".editorconfig";

    private record Cached(long mtime, EditorConfigParser.Parsed parsed) {}

    private static final ConcurrentMap<Path, Cached> CACHE = new ConcurrentHashMap<>();

    private EditorConfig() {}

    /** Clears the per-file parse cache (used when the feature is toggled, and by tests). */
    public static void clearCache() {
        CACHE.clear();
    }

    /** The merged EditorConfig properties for {@code file}, or {@link EditorConfigProperties#EMPTY}. */
    public static EditorConfigProperties resolveFor(Path file) {
        if (file == null) {
            return EditorConfigProperties.EMPTY;
        }
        Path abs;
        try {
            abs = file.toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            return EditorConfigProperties.EMPTY;
        }
        List<EditorConfigProperties> nearestFirst = new ArrayList<>();
        for (Path dir = abs.getParent(); dir != null; dir = dir.getParent()) {
            EditorConfigParser.Parsed parsed = readParsed(dir.resolve(FILENAME));
            if (parsed == null) {
                continue;
            }
            nearestFirst.add(matchProperties(parsed, relativePath(dir, abs)));
            if (parsed.root()) {
                break;
            }
        }
        // Merge farthest → nearest so a nearer directory's values override a farther one's.
        EditorConfigProperties merged = EditorConfigProperties.EMPTY;
        for (int i = nearestFirst.size() - 1; i >= 0; i--) {
            merged = EditorConfigProperties.merge(merged, nearestFirst.get(i));
        }
        return merged;
    }

    /**
     * The nearest {@code .editorconfig} file governing {@code file} (the closest one walking up its directory
     * tree, stopping at the first {@code root = true}), or {@code null} when none applies. Used by the status-bar
     * indicator to open the active config.
     */
    public static Path nearestFile(Path file) {
        if (file == null) {
            return null;
        }
        Path abs;
        try {
            abs = file.toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            return null;
        }
        for (Path dir = abs.getParent(); dir != null; dir = dir.getParent()) {
            Path ecFile = dir.resolve(FILENAME);
            if (Files.isRegularFile(ecFile)) {
                return ecFile;
            }
            EditorConfigParser.Parsed parsed = readParsed(ecFile);
            if (parsed != null && parsed.root()) {
                break;
            }
        }
        return null;
    }

    private static EditorConfigParser.Parsed readParsed(Path ecFile) {
        try {
            if (!Files.isRegularFile(ecFile)) {
                return null;
            }
            long mtime = Files.getLastModifiedTime(ecFile).toMillis();
            Cached hit = CACHE.get(ecFile);
            if (hit != null && hit.mtime() == mtime) {
                return hit.parsed();
            }
            EditorConfigParser.Parsed parsed = EditorConfigParser.parse(Files.readString(ecFile));
            CACHE.put(ecFile, new Cached(mtime, parsed));
            return parsed;
        } catch (IOException | RuntimeException e) {
            return null; // unreadable .editorconfig is ignored
        }
    }

    /** Merges every section of {@code parsed} whose glob matches {@code relPath} (later sections override). */
    private static EditorConfigProperties matchProperties(EditorConfigParser.Parsed parsed, String relPath) {
        EditorConfigProperties props = EditorConfigProperties.EMPTY;
        for (EditorConfigParser.Section s : parsed.sections()) {
            if (EditorConfigGlob.matches(s.glob(), relPath)) {
                props = EditorConfigProperties.merge(props, EditorConfigParser.toProperties(s.properties()));
            }
        }
        return props;
    }

    /** The file path relative to the {@code .editorconfig} directory, {@code /}-separated. */
    static String relativePath(Path dir, Path file) {
        try {
            return dir.relativize(file).toString().replace(java.io.File.separatorChar, '/');
        } catch (RuntimeException e) {
            return file.getFileName().toString();
        }
    }
}
