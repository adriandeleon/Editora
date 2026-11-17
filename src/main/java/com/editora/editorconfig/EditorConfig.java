package com.editora.editorconfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.jspecify.annotations.Nullable;

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
        RESOLVED.clear();
        FOLDERS.clear();
    }

    /** The merged EditorConfig properties for {@code file}, or {@link EditorConfigProperties#EMPTY}. */
    public static EditorConfigProperties resolveFor(@Nullable Path file) {
        Map<String, String> merged = rawFor(file);
        return merged.isEmpty() ? EditorConfigProperties.EMPTY : EditorConfigParser.toProperties(merged);
    }

    /**
     * Every property that applies to {@code file}, merged as {@link #resolveFor} merges them but untyped, keys
     * lower-cased — the tool-specific ones too, such as IntelliJ's {@code ij_java_imports_layout}. Empty when none.
     *
     * <p>On the JavaFX thread it never touches the disk (PERF-01, Pauta 6.0.1): it answers what a look off the
     * thread last found for the file ({@link #warm}, a file's load, any earlier ask off the thread), and looks
     * again behind the answer when that is a few seconds old; {@link #addListener listeners} hear, on the FX
     * thread, of a file whose answer changed. A file nothing looked at yet is answered from the parsed configs
     * of its folders when every one of them is known, else as having no rules until its look lands.
     */
    public static Map<String, String> rawFor(@Nullable Path file) {
        if (file == null) {
            return Map.of();
        }
        Path abs;
        try {
            abs = file.toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            return Map.of();
        }
        if (!onFx()) {
            return rawNow(abs);
        }
        Kept kept = RESOLVED.get(abs);
        if (kept == null || System.nanoTime() - kept.at() > FRESH_NANOS) {
            lookBehind(abs);
        }
        if (kept != null) {
            return kept.raw();
        }
        Map<String, String> fromMemory = fromMemory(abs);
        return fromMemory == null ? Map.of() : fromMemory;
    }

    /** Looks at {@code file}'s rules now, on this thread (never the FX thread), and keeps the answer. */
    public static void warm(@Nullable Path file) {
        if (file != null) {
            try {
                rawNow(file.toAbsolutePath().normalize());
            } catch (RuntimeException e) {
                // nothing kept: the next ask looks again
            }
        }
    }

    /**
     * Looks at {@code file}'s rules again behind the kept answer, however fresh it is (a save asks: an
     * {@code .editorconfig} pulled since must reach the file); a changed answer reaches the listeners.
     */
    public static void refresh(@Nullable Path file) {
        if (file == null) {
            return;
        }
        try {
            lookBehind(file.toAbsolutePath().normalize());
        } catch (RuntimeException e) {
            // not a path this file system can express: nothing to look at
        }
    }

    /** Whether a look has answered for {@code file} (the FX thread then answers it from memory). */
    public static boolean knows(Path file) {
        try {
            return RESOLVED.containsKey(file.toAbsolutePath().normalize());
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Adds a listener told, on the FX thread, of a file whose rules a look behind an FX answer found changed.
     * Held weakly: the caller keeps the listener alive as long as it wants to hear.
     */
    public static void addListener(java.util.function.Consumer<Path> listener) {
        LISTENERS.removeIf(ref -> ref.get() == null);
        LISTENERS.add(new java.lang.ref.WeakReference<>(listener));
    }

    /** What a look found for a file, and when (System.nanoTime). */
    private record Kept(Map<String, String> raw, long at) {}

    /** How old an answer the FX thread takes before it looks again behind it. */
    private static final long FRESH_NANOS = 3_000_000_000L;

    private static final int MAX_KEPT = 4_096;

    private static final ConcurrentMap<Path, Kept> RESOLVED = new ConcurrentHashMap<>();
    /** Folders looked at: whether each holds a {@code .editorconfig}. */
    private static final ConcurrentMap<Path, Boolean> FOLDERS = new ConcurrentHashMap<>();

    private static final java.util.Set<Path> LOOKING = ConcurrentHashMap.newKeySet();
    private static final List<java.lang.ref.WeakReference<java.util.function.Consumer<Path>>> LISTENERS =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    /** Where a look behind an FX answer runs. TEST SEAM: a test may run it in place. */
    static volatile java.util.function.Consumer<Runnable> looker =
            r -> Thread.ofVirtual().name("editorconfig-look").start(r);

    private static void lookBehind(Path abs) {
        if (!LOOKING.add(abs)) {
            return;
        }
        Kept before = RESOLVED.get(abs);
        looker.accept(() -> {
            Map<String, String> now;
            try {
                now = rawNow(abs);
            } catch (RuntimeException e) {
                now = Map.of();
            } finally {
                LOOKING.remove(abs);
            }
            Map<String, String> was = before == null ? Map.of() : before.raw();
            if (!now.equals(was)) {
                javafx.application.Platform.runLater(() -> {
                    for (var ref : LISTENERS) {
                        java.util.function.Consumer<Path> l = ref.get();
                        if (l != null) {
                            l.accept(abs);
                        }
                    }
                });
            }
        });
    }

    /** The look itself: stats, reads and parses every config up the tree, and keeps what it found. */
    private static Map<String, String> rawNow(Path abs) {
        com.editora.perf.BlockingSites.enter("EditorConfig.rawFor");
        List<List<Map<String, String>>> nearestFirst = new ArrayList<>();
        for (Path dir = abs.getParent(); dir != null; dir = dir.getParent()) {
            EditorConfigParser.Parsed parsed = readParsed(dir.resolve(FILENAME));
            FOLDERS.put(dir, parsed != null);
            if (parsed == null) {
                continue;
            }
            nearestFirst.add(matchingSections(parsed, relativePath(dir, abs)));
            if (parsed.root()) {
                break;
            }
        }
        Map<String, String> merged = merge(nearestFirst);
        if (RESOLVED.size() >= MAX_KEPT) {
            RESOLVED.clear();
        }
        if (FOLDERS.size() >= MAX_KEPT) {
            FOLDERS.clear();
        }
        RESOLVED.put(abs, new Kept(Map.copyOf(merged), System.nanoTime()));
        return merged;
    }

    /**
     * {@code abs}'s rules worked out from memory alone: the configs of its folders as last read, when every
     * folder up to a root config (or the file system's root) has been looked at; null when one has not.
     */
    private static @Nullable Map<String, String> fromMemory(Path abs) {
        List<List<Map<String, String>>> nearestFirst = new ArrayList<>();
        for (Path dir = abs.getParent(); dir != null; dir = dir.getParent()) {
            Boolean present = FOLDERS.get(dir);
            if (present == null) {
                return null;
            }
            if (!present) {
                continue;
            }
            Cached cached = CACHE.get(dir.resolve(FILENAME));
            if (cached == null) {
                return null;
            }
            nearestFirst.add(matchingSections(cached.parsed(), relativePath(dir, abs)));
            if (cached.parsed().root()) {
                break;
            }
        }
        return merge(nearestFirst);
    }

    private static Map<String, String> merge(List<List<Map<String, String>>> nearestFirst) {
        // Overlay farthest → nearest (and, within a file, top → bottom) on the RAW values, so a nearer
        // `key = unset` removes the inherited value instead of being indistinguishable from "not mentioned".
        Map<String, String> merged = new LinkedHashMap<>();
        for (int i = nearestFirst.size() - 1; i >= 0; i--) {
            for (Map<String, String> section : nearestFirst.get(i)) {
                EditorConfigParser.overlay(merged, section);
            }
        }
        return merged;
    }

    private static boolean onFx() {
        try {
            return javafx.application.Platform.isFxApplicationThread();
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    /**
     * The nearest {@code .editorconfig} file governing {@code file} (the closest one walking up its directory
     * tree, stopping at the first {@code root = true}), or {@code null} when none applies. Used by the status-bar
     * indicator to open the active config. Reads the disk: never on the FX thread.
     */
    public static @Nullable Path nearestFile(Path file) {
        com.editora.perf.BlockingSites.enter("EditorConfig.nearestFile");
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

    private static EditorConfigParser.@Nullable Parsed readParsed(Path ecFile) {

        try {
            if (!Files.isRegularFile(ecFile)) {
                return null;
            }
            long mtime = Files.getLastModifiedTime(ecFile).toMillis();
            Cached hit = CACHE.get(ecFile);
            if (hit != null && hit.mtime() == mtime) {
                return hit.parsed();
            }
            EditorConfigParser.Parsed parsed = EditorConfigParser.parse(Files.readAllBytes(ecFile));
            CACHE.put(ecFile, new Cached(mtime, parsed));
            return parsed;
        } catch (IOException | RuntimeException e) {
            return null; // unreadable .editorconfig is ignored
        }
    }

    /** The raw property maps of every section of {@code parsed} whose glob matches {@code relPath}, in order. */
    private static List<Map<String, String>> matchingSections(EditorConfigParser.Parsed parsed, String relPath) {
        List<Map<String, String>> out = new ArrayList<>();
        // One allowance for the whole file: section globs are untrusted.
        EditorConfigGlob.Budget budget = new EditorConfigGlob.Budget();
        for (EditorConfigParser.Section s : parsed.sections()) {
            if (EditorConfigGlob.matches(s.glob(), relPath, budget)) {
                out.add(s.properties());
            }
        }
        return out;
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
