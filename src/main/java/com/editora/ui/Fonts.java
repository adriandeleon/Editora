package com.editora.ui;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import javafx.scene.text.Font;

/**
 * Loads the editor's bundled monospace fonts so they're available by family name regardless of what
 * is installed on the system.
 *
 * <p>Font files live under {@code resources/com/editora/fonts/<family>/}. Each family ships Regular,
 * Bold, Italic, and BoldItalic where available (Fira Code has no italic — JavaFX synthesizes it), so
 * the syntax theme's bold/italic spans render with real glyphs.
 *
 * <p>Startup registers only what the first frame can show ({@link #loadForFirstPaint}): the UI-chrome
 * family, the family {@code app.css} names as its monospace default, and the configured editor family.
 * The other families (about 4 MB of TrueType to read and parse) are registered after the first painted
 * frame on a daemon thread ({@link #loadRemainingAsync}); {@link Font#loadFont} is static and safe to call
 * off the FX thread. Anything that lists or applies a family a user may pick — the Settings font picker,
 * a font-setting change — calls {@link #ensureAllLoaded()} first, which finishes the deferred load (or
 * does it inline when nothing started it). {@link #load()} is the eager form for tests and tools.
 */
public final class Fonts {

    /** Bundled font families, in picker order; the first is the default for new configs. */
    public static final List<String> BUNDLED =
            List.of("JetBrains Mono", "Cascadia Code", "Fira Code", "IBM Plex Mono", "Source Code Pro");

    public static final String DEFAULT = "JetBrains Mono";

    /**
     * The UI chrome's font (UI Kit v1): a sans-serif, so intentionally absent from {@link #BUNDLED}. It is
     * also the Markdown preview's and PDF prose font, bundled so it is always available by family name:
     * AtlantaFX ships no Inter, JavaFX won't fall through a CSS font-family list to a fallback, and the
     * macOS system font has no bold face JavaFX can rasterize cleanly (it faux-bolds .AppleSystemUIFont,
     * mangling glyphs). Loading it before any stylesheet is applied is what makes styles/ui-font.css resolve.
     */
    public static final String UI = "Inter";

    /** Family -> its font files, in load order. */
    private static final Map<String, List<String>> FILES = files();

    private static Map<String, List<String>> files() {
        Map<String, List<String>> m = new LinkedHashMap<>();
        m.put(
                "JetBrains Mono",
                List.of(
                        "jetbrains-mono/JetBrainsMono-Regular.ttf",
                        "jetbrains-mono/JetBrainsMono-Bold.ttf",
                        "jetbrains-mono/JetBrainsMono-Italic.ttf",
                        "jetbrains-mono/JetBrainsMono-BoldItalic.ttf"));
        m.put(
                "Cascadia Code",
                List.of(
                        "cascadia-code/CascadiaCode-Regular.ttf",
                        "cascadia-code/CascadiaCode-Bold.ttf",
                        "cascadia-code/CascadiaCode-Italic.ttf",
                        "cascadia-code/CascadiaCode-BoldItalic.ttf"));
        m.put("Fira Code", List.of("fira-code/FiraCode-Regular.ttf", "fira-code/FiraCode-Bold.ttf"));
        m.put(
                "IBM Plex Mono",
                List.of(
                        "ibm-plex-mono/IBMPlexMono-Regular.ttf",
                        "ibm-plex-mono/IBMPlexMono-Bold.ttf",
                        "ibm-plex-mono/IBMPlexMono-Italic.ttf",
                        "ibm-plex-mono/IBMPlexMono-BoldItalic.ttf"));
        m.put(
                "Source Code Pro",
                List.of(
                        "source-code-pro/SourceCodePro-Regular.ttf",
                        "source-code-pro/SourceCodePro-Bold.ttf",
                        "source-code-pro/SourceCodePro-Italic.ttf",
                        "source-code-pro/SourceCodePro-BoldItalic.ttf"));
        m.put(
                UI,
                List.of(
                        "inter/Inter-Regular.ttf",
                        "inter/Inter-Bold.ttf",
                        "inter/Inter-Italic.ttf",
                        "inter/Inter-BoldItalic.ttf"));
        return m;
    }

    /** Families a loader has claimed (guarded by the class lock); {@link #DONE} once their files are in. */
    private static final Set<String> LOADED = new HashSet<>();

    /** Families whose files are registered with the toolkit (guarded by the class lock). */
    private static final Set<String> DONE = new HashSet<>();

    /** The deferred load in flight or done; null until {@link #loadRemainingAsync} starts one. */
    private static CompletableFuture<Void> remaining;

    private Fonts() {}

    /** Registers every bundled font with the JavaFX toolkit. Failures are skipped silently. */
    public static void load() {
        loadFamilies(FILES.keySet());
    }

    /**
     * The families the first frame can show: the UI chrome's, the one {@code app.css} names as its monospace
     * fallback, and the configured editor family when it is a bundled one (a system family needs no load).
     * Pure, so the choice is testable without a toolkit.
     */
    static List<String> firstPaintFamilies(String editorFamily) {
        List<String> out = new ArrayList<>(List.of(UI, DEFAULT));
        if (editorFamily != null && FILES.containsKey(editorFamily) && !out.contains(editorFamily)) {
            out.add(editorFamily);
        }
        return out;
    }

    /** Registers only the families the first painted frame needs; see {@link #firstPaintFamilies}. */
    public static void loadForFirstPaint(String editorFamily) {
        loadFamilies(firstPaintFamilies(editorFamily));
    }

    /**
     * Registers every family not yet loaded on a daemon thread. Idempotent: a second call returns the load
     * already started. Callers that must see every family wait through {@link #ensureAllLoaded()}.
     */
    public static synchronized CompletableFuture<Void> loadRemainingAsync() {
        if (remaining == null) {
            if (DONE.containsAll(FILES.keySet())) {
                remaining = CompletableFuture.completedFuture(null);
            } else {
                CompletableFuture<Void> future = new CompletableFuture<>();
                Thread t = new Thread(
                        () -> {
                            try {
                                load();
                            } finally {
                                future.complete(null);
                            }
                        },
                        "fonts-load");
                t.setDaemon(true);
                t.start();
                remaining = future;
            }
        }
        return remaining;
    }

    /**
     * Starts {@link #loadRemainingAsync} once a frame has painted. A pulse's timer tick runs at the start of a
     * pulse, before it renders, so the second tick is the first proof that a frame in between completed —
     * the same two-tick rule {@code WindowSessionCoordinator.afterNextPaint} uses. FX thread.
     */
    public static void loadRemainingAfterNextPaint() {
        new javafx.animation.AnimationTimer() {
            private int ticks;

            @Override
            public void handle(long now) {
                if (++ticks >= 2) {
                    stop();
                    loadRemainingAsync();
                }
            }
        }.start();
    }

    /**
     * Blocks until every bundled family is registered: joins a deferred load in flight, or loads inline when
     * none started. For the font picker and for applying a font setting, so a family a user picks exists.
     */
    public static void ensureAllLoaded() {
        CompletableFuture<Void> pending;
        synchronized (Fonts.class) {
            if (DONE.containsAll(FILES.keySet())) {
                return;
            }
            pending = remaining;
        }
        if (pending != null) {
            pending.join();
        } else {
            load();
        }
    }

    /**
     * Registers one bundled family now if it is not yet (a system family needs nothing). For applying a font
     * setting: cheap when the family is in, and one family's files otherwise — never the whole deferred set.
     */
    public static void ensureFamily(String family) {
        if (family == null || !FILES.containsKey(family)) {
            return;
        }
        synchronized (Fonts.class) {
            if (DONE.contains(family)) {
                return;
            }
            if (LOADED.contains(family)) {
                // Claimed by the deferred loader, which is parsing it now: wait for that rather than racing it.
                family = null;
            }
        }
        if (family == null) {
            ensureAllLoaded();
        } else {
            loadFamilies(List.of(family));
        }
    }

    /** Whether every bundled family has been registered. */
    public static synchronized boolean allLoaded() {
        return DONE.containsAll(FILES.keySet());
    }

    /** The families registered so far, for tests. */
    static synchronized Set<String> loadedFamilies() {
        return Set.copyOf(DONE);
    }

    private static void loadFamilies(Iterable<String> families) {
        for (String family : families) {
            List<String> files;
            synchronized (Fonts.class) {
                files = LOADED.add(family) ? FILES.get(family) : null; // claim it; a second loader skips it
            }
            if (files == null) {
                continue;
            }
            for (String file : files) {
                try (InputStream in = Fonts.class.getResourceAsStream("/com/editora/fonts/" + file)) {
                    if (in != null) {
                        Font.loadFont(in, 12);
                    }
                } catch (Exception ignored) {
                    // A missing/unreadable font just won't be available; keep loading the rest.
                }
            }
            synchronized (Fonts.class) {
                DONE.add(family);
            }
        }
    }
}
