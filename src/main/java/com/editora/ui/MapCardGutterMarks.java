package com.editora.ui;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import com.editora.editor.EditorBuffer;
import com.editora.editor.git.GitGutterLines;
import com.editora.git.GitChangeBars;
import com.editora.git.GitService;
import com.editora.test.JavaTestScanner;
import org.jspecify.annotations.Nullable;

/**
 * Where a Project Map card's gutter reads its marks in an editor window (Map gaps round 3, item 1): the window's
 * breakpoints and bookmarks — a tab's own when the file is open, else the stored ones — its Run and Test Runner gates,
 * and Git's change bars, a tab's live ones when the file is open. Built with the Project panel on its first open.
 */
final class MapCardGutterMarks implements MapCardGutter.Source {

    /** What the marks are read from, and what the clicks do, in a window. */
    interface Window {
        /** Whether a breakpoint may be set in {@code file} (see {@link DebugCoordinator#cardBreakpointsOn}). */
        boolean breakpointsOn(Path file);

        Map<Integer, MapCardGutter.Breakpoint> breakpoints(Path file);

        void toggleBreakpoint(Path file, int line, String text);

        Set<Integer> bookmarks(Path file);

        /** Whether a JVM project's code can run from {@code file}'s gutter: not Simple mode, a build tool, its host. */
        boolean runs(Path file);

        /** Whether the Test Runner is on. */
        boolean testRunner();

        @Nullable
        EditorBuffer openBuffer(Path file);

        boolean gitEnabled();

        /** {@code file}'s diff against HEAD, on the FX thread (see {@link GitService#fileChanges}). */
        void gitChanges(Path file, Consumer<GitService.@Nullable GitDiff> found);

        void runMain(String fqn);

        void runTest(JavaTestScanner.TestTarget target);
    }

    private final Window window;

    MapCardGutterMarks(Window window) {
        this.window = window;
    }

    /** The marks of {@code mc}'s window. */
    static MapCardGutterMarks of(MainController mc) {
        return new MapCardGutterMarks(new Window() {
            @Override
            public boolean breakpointsOn(Path file) {
                DebugCoordinator debug = mc.debugCoordinator;
                return debug != null && debug.cardBreakpointsOn(file);
            }

            @Override
            public Map<Integer, MapCardGutter.Breakpoint> breakpoints(Path file) {
                return mc.debugCoordinator.cardBreakpoints(file);
            }

            @Override
            public void toggleBreakpoint(Path file, int line, String text) {
                mc.debugCoordinator.toggleCardBreakpoint(file, line, text);
            }

            @Override
            public Set<Integer> bookmarks(Path file) {
                return mc.bookmarkCoordinator.bookmarkedLines(file);
            }

            @Override
            public boolean runs(Path file) {
                return !mc.chrome.simpleModeActive()
                        && RemoteExec.runsFor(file)
                        && mc.testNavigation.jvmBuildDetected();
            }

            @Override
            public boolean testRunner() {
                return mc.config.getSettings().isTestRunner();
            }

            @Override
            public @Nullable EditorBuffer openBuffer(Path file) {
                return mc.openBufferFor(file);
            }

            @Override
            public boolean gitEnabled() {
                return mc.git.isEnabled();
            }

            @Override
            public void gitChanges(Path file, Consumer<GitService.@Nullable GitDiff> found) {
                mc.git.service().fileChanges(file, found);
            }

            @Override
            public void runMain(String fqn) {
                mc.runCoordinator.runMainClassNamed(fqn);
            }

            @Override
            public void runTest(JavaTestScanner.TestTarget target) {
                mc.testNavigation.runSingleTest(null, target);
            }
        });
    }

    @Override
    public MapCardGutter.Marks marks(Path file) {
        boolean breakpointsOn = window.breakpointsOn(file);
        boolean runs = window.runs(file);
        return new MapCardGutter.Marks(
                breakpointsOn,
                breakpointsOn ? window.breakpoints(file) : Map.of(),
                window.bookmarks(file),
                runs && window.testRunner(),
                runs);
    }

    @Override
    public void changes(Path file, Consumer<MapCardGutter.@Nullable Changes> found) {
        EditorBuffer open = window.openBuffer(file);
        if (open != null && !open.isNarrowed()) {
            found.accept(liveBars(
                    open.gitGutter(), open.getFocusedArea().getParagraphs().size()));
        } else if (!window.gitEnabled()) {
            found.accept(null);
        } else {
            window.gitChanges(file, diff -> found.accept(bars(diff)));
        }
    }

    /** A diff's bars, or null when it has none (or there is none). */
    static MapCardGutter.@Nullable Changes bars(GitService.@Nullable GitDiff diff) {
        return diff == null || diff.changes().isEmpty()
                ? null
                : new MapCardGutter.Changes(GitChangeBars.cssClassesByLine(diff.changes()), diff.hunks());
    }

    /** An open tab's change bars as its gutter has them now, its edits included; null when it has none. */
    static MapCardGutter.@Nullable Changes liveBars(GitGutterLines lines, int lineCount) {
        if (!lines.barsTracked()) {
            return null;
        }
        Map<Integer, String> bars = new HashMap<>();
        Map<Integer, String> tips = new HashMap<>();
        for (int line = 0; line < lineCount; line++) {
            String bar = lines.barAt(line);
            if (bar != null) {
                bars.put(line, bar);
                String tip = lines.tooltipAt(line);
                if (tip != null) {
                    tips.put(line, tip);
                }
            }
        }
        return bars.isEmpty() ? null : new MapCardGutter.Changes(bars, tips);
    }

    @Override
    public void toggleBreakpoint(Path file, int line, String text) {
        if (window.breakpointsOn(file)) {
            window.toggleBreakpoint(file, line, text);
        }
    }

    @Override
    public void run(Path file, MapCardGutter.Run run) {
        if (run.main() != null) {
            window.runMain(run.main().fqn());
        } else if (run.test() != null) {
            window.runTest(run.test());
        }
    }
}
