package com.editora.editor;

import javafx.application.Platform;

import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * The gutter Run glyphs of a buffer and what they run: a runnable script's entry line, a Java project's
 * {@code main} methods, JUnit test classes and methods, a Makefile's targets and a {@code .http} file's
 * requests. Decides which lines carry a glyph from a {@link RunScan} of the text (off the FX thread after a load
 * or a settled edit, guarded by a generation counter), and holds the handlers the window injects.
 */
final class BufferRunGutter {

    private final EditorBuffer b;

    BufferRunGutter(EditorBuffer b) {
        this.b = b;
    }

    /** Files above this size are never scanned for compact-source detection (keeps it off the hot path). */
    private static final int COMPACT_SCAN_LIMIT = 256 * 1024;
    /** Whether this buffer can run as a local file (remote buffers cannot). */
    private boolean runFeatureEnabled = true;
    /** 0-based definition line → target name for a Makefile buffer (each line gets a Run glyph → {@code
     *  make <target>}); empty for non-Makefile buffers. */
    private java.util.Map<Integer, String> makeTargets = java.util.Map.of();
    /** Fired with the clicked target's name when a Makefile Run glyph is clicked (runs {@code make <name>}). */
    private java.util.function.Consumer<String> makeRunHandler = t -> {};
    /** JUnit test-gutter gate (Test Runner feature + a detected JVM build tool), pushed by MainController. */
    private boolean testGutterEnabled;
    /** 0-based line → JUnit test target (class-decl line + each test method) for the gutter ▶; empty otherwise. */
    java.util.Map<Integer, com.editora.test.JavaTestScanner.TestTarget> testLines = java.util.Map.of();

    /** What the test at the caret is read from, made by the Run scan with {@link #testLines}; null without tests. */
    com.editora.test.JavaTestScanner.@Nullable CaretIndex testIndex;
    /** Fired with a test target when its gutter ▶ is clicked (runs one class/method via the build tool). */
    java.util.function.Consumer<com.editora.test.JavaTestScanner.TestTarget> testRunHandler = t -> {};
    /** Fired with a test target from the editor context menu (debugs one class/method via the build tool). */
    java.util.function.Consumer<com.editora.test.JavaTestScanner.TestTarget> testDebugHandler = t -> {};
    /** Main-method gutter gate (a Java file in a Maven/Gradle project with run/debug available), pushed by
     *  MainController. When on, {@code public static void main} lines get a green ▶ to run the project class. */
    private boolean mainGutterEnabled;
    /** 0-based line → project {@code main} entry point for the gutter ▶; empty otherwise. */
    java.util.Map<Integer, com.editora.run.MainMethodScanner.MainMethod> mainLines = java.util.Map.of();
    /** Fired with a main method when its gutter ▶ is clicked (runs that project main class). */
    java.util.function.Consumer<com.editora.run.MainMethodScanner.MainMethod> mainRunHandler = m -> {};
    /** Fired with a main method for the editor right-click "Debug Main Class" item. */
    java.util.function.Consumer<com.editora.run.MainMethodScanner.MainMethod> mainDebugHandler = m -> {};
    /** Whether this file is runnable (a Java 25 compact source file, a Python script, or — when the Bash
     *  LSP is enabled — a shell script) — drives the gutter Run glyph + the Run tool window. */
    boolean runnable;
    /** 0-based line the gutter Run glyph sits on (a compact file's {@code main}, a Python {@code __main__}
     *  guard, else the first line), or -1 when not runnable. */
    private int runLine = -1;
    /** Fired (FX thread) when the runnable status flips, so the controller refreshes the Run button. */
    private Runnable onRunnableChanged = () -> {};

    /** "Run File" context-menu handler (compact source files); null hides the item. */
    @Nullable
    Runnable runHandler;
    /** "Debug File" beside "Run File" (null: none); shown while {@link #debugFileShown} says it can debug. */
    @Nullable
    Runnable debugFileHandler;

    java.util.function.BooleanSupplier debugFileShown = () -> false;
    /** True if this file can be run (a Java 25 compact source file, a Python script, or a shell script
     *  when the Bash LSP is enabled). */
    boolean isRunnable() {
        return runnable;
    }

    /** True when the single-file Run entry is a compact Java source method (rather than a test glyph). */
    boolean isCompactSource() {
        return "java".equals(b.language) && runLine >= 0;
    }

    /** Sets the callback fired when {@link #isRunnable()} flips (so the controller refreshes the Run button). */
    void setOnRunnableChanged(Runnable callback) {
        this.onRunnableChanged = callback == null ? () -> {} : callback;
    }

    /** Enables/disables the local-file Run affordance. When off, the gutter Run glyph,
     *  the right-click Run item, and the Run tool window all disappear. */
    void setRunEnabled(boolean enabled) {
        if (enabled != runFeatureEnabled) {
            runFeatureEnabled = enabled;
            recomputeRun();
        }
    }

    /** Debug Request for the request at a 0-based line (the editor menu); null when there is none to offer. */
    java.util.function.@Nullable IntConsumer httpDebugHandler;

    /** Sets what the editor menu's Debug Request does with the request at a line (null removes the item). */
    void setHttpDebugHandler(java.util.function.IntConsumer handler) {
        this.httpDebugHandler = handler;
    }

    /** Injects the handler run with a target's name when its Makefile gutter ▶ is clicked. */
    void setMakeRunHandler(java.util.function.Consumer<String> handler) {
        this.makeRunHandler = handler == null ? t -> {} : handler;
    }

    /** Enables/disables the JUnit test gutter ▶ (gated by the Test Runner feature + a detected JVM build
     *  tool). When on, a Java buffer's test class + each test method get a Run glyph. */
    void setTestGutterEnabled(boolean enabled) {
        if (enabled != testGutterEnabled) {
            testGutterEnabled = enabled;
            recomputeRun();
        }
    }

    /** Injects the handler run with the clicked test target (class ▶ has {@code methodName == null}). */
    void setTestRunHandler(java.util.function.Consumer<com.editora.test.JavaTestScanner.TestTarget> handler) {
        this.testRunHandler = handler == null ? t -> {} : handler;
    }

    /** Injects the handler that debugs the test class/method selected by the editor context menu. */
    void setTestDebugHandler(java.util.function.Consumer<com.editora.test.JavaTestScanner.TestTarget> handler) {
        this.testDebugHandler = handler == null ? t -> {} : handler;
    }

    /** Gate for the project {@code main}-method gutter ▶ (a Java file in a Maven/Gradle project with run/debug
     *  available); pushed by MainController. */
    void setMainGutterEnabled(boolean enabled) {
        if (enabled != mainGutterEnabled) {
            mainGutterEnabled = enabled;
            recomputeRun();
        }
    }

    /** Injects the handler run with a clicked {@code main}-method gutter ▶ (runs that project main class). */
    void setMainRunHandler(java.util.function.Consumer<com.editora.run.MainMethodScanner.MainMethod> handler) {
        this.mainRunHandler = handler == null ? m -> {} : handler;
    }

    /** Injects the handler for the editor "Debug Main Class" item on a {@code main} method. */
    void setMainDebugHandler(java.util.function.Consumer<com.editora.run.MainMethodScanner.MainMethod> handler) {
        this.mainDebugHandler = handler == null ? m -> {} : handler;
    }

    /** The project {@code main} entry point at (or nearest above) the caret, else the file's first main, else
     *  {@code null} — backs the editor right-click Run/Debug Main Class items. */
    com.editora.run.MainMethodScanner.@Nullable MainMethod mainTargetAtCaret() {
        return mainTargetAt(b.focusedArea.getCurrentParagraph());
    }

    com.editora.run.MainMethodScanner.@Nullable MainMethod mainTargetAt(int caret) {
        if (mainLines.isEmpty()) {
            return null;
        }
        com.editora.run.MainMethodScanner.MainMethod best = null;
        for (var e : mainLines.entrySet()) {
            if (e.getKey() <= caret && (best == null || e.getKey() > best.line())) {
                best = e.getValue();
            }
        }
        return best != null ? best : mainLines.values().iterator().next();
    }

    /**
     * The whole-class JUnit target for this buffer (the "Run Tests" context-menu item), or {@code null} when
     * this isn't a test file / the test gutter is off.
     */
    com.editora.test.JavaTestScanner.@Nullable TestTarget testClassTarget() {
        for (var t : testLines.values()) {
            if (t.methodName() == null) {
                return t; // the class-declaration target the scanner emits first
            }
        }
        return null;
    }

    /**
     * The JUnit test target at or nearest above the caret, for {@code test.runAtCaret}/{@code runClassAtCaret}.
     * {@code classLevel} forces the whole-class target (methodName null). Returns null when the caret isn't in
     * a test class.
     */
    com.editora.test.JavaTestScanner.@Nullable TestTarget testTargetAtCaret(boolean classLevel) {
        return testTargetAt(b.focusedArea.getCurrentParagraph(), classLevel);
    }

    com.editora.test.JavaTestScanner.@Nullable TestTarget testTargetAt(int caret, boolean classLevel) {
        // Read from the index the Run scan made off the FX thread with these targets: no parse here (PERF-01).
        var index = testIndex;
        var best = index == null ? null : com.editora.test.JavaTestScanner.targetAt(index, testLines, caret); // A205
        if (best == null) {
            return classLevel ? testClassTarget() : null; // above the class line: the class's (sixth review BS3)
        }
        return classLevel
                ? new com.editora.test.JavaTestScanner.TestTarget(best.line(), best.className(), null, false)
                : best;
    }

    /** Whether {@code line} draws a Run glyph: every request line for an enabled {@code .http} buffer,
     *  else the single script entry line. */
    boolean isRunGlyphLine(int line) {
        if (mainLines.containsKey(line)) {
            return true; // a project main ▶ draws independently of `runnable` (see recomputeRun)
        }
        if (!runnable) {
            return false;
        }
        if (testLines.containsKey(line)) {
            return true;
        }
        if (b.httpFeatureEnabled && b.isHttpFile()) {
            return b.httpGutter.isGlyphLine(line, b::isFoldedLine); // a folded request's ▶ shows on its ### line
        }
        if (b.isMakefile()) {
            return makeTargets.containsKey(line);
        }
        return line == runLine;
    }

    /** Hover text for a gutter Run glyph: explains what the blue JUnit ▶ runs (class vs method). */
    @Nullable
    String runGlyphTooltip(int line) {
        com.editora.run.MainMethodScanner.MainMethod m = mainLines.get(line);
        if (m != null) {
            return tr("editmenu.runMainTooltip", com.editora.test.TestSourceLocator.simpleName(m.fqn()));
        }
        com.editora.test.JavaTestScanner.TestTarget t = testLines.get(line);
        if (t == null) {
            if (b.httpFeatureEnabled && b.isHttpFile()) {
                return b.httpGutter.tooltip(line, b::isFoldedLine); // ■ cancels, ▶ runs its request (names the chord)
            }
            return null; // the plain script/Makefile ▶ keeps its untooltipped look
        }
        return t.methodName() == null
                ? t.className().isEmpty() // a Python test file's whole-file ▶
                        ? tr("testrunner.gutter.runFile")
                        : tr(
                                "testrunner.gutter.runClass",
                                com.editora.test.TestSourceLocator.displayName(t.className()))
                : tr("testrunner.gutter.runMethod", t.methodName());
    }

    /** Dispatches a Run-glyph click: a JUnit or main ▶, a {@code .http} request (the clicked line), a Makefile
     *  target (the clicked target's name), else the script run handler. */
    void onRunGlyph(int line) {
        com.editora.test.JavaTestScanner.TestTarget testTarget = testLines.get(line);
        com.editora.run.MainMethodScanner.MainMethod mainTarget = mainLines.get(line);
        if (testTarget != null) {
            testRunHandler.accept(testTarget); // a JUnit class/method ▶ (never coincides with a compact-source main)
        } else if (mainTarget != null) {
            mainRunHandler.accept(mainTarget); // a project main class ▶ (disjoint from test/compact-source lines)
        } else if (b.httpFeatureEnabled && b.isHttpFile()) {
            b.httpGutter.click(line, b::isFoldedLine); // the ■ in flight cancels it; a ▶ runs its request
        } else if (b.isMakefile()) {
            String target = makeTargets.get(line);
            if (target != null) {
                makeRunHandler.accept(target);
            }
        } else if (runHandler != null) {
            runHandler.run();
        }
    }

    /** Discards an in-flight background run scan whose result would be stale (mirrors {@link BufferTodos#todoGen}). */
    long runScanGen;
    /** True while the load's (or a settle's) text scan is on the pool and its result has not landed. */
    private boolean runScanInFlight;

    /** What waits for the scan in flight to land: a Run or Debug command issued right after an open or an edit. */
    private final java.util.List<Runnable> afterRunScan = new java.util.ArrayList<>();

    /**
     * Runs {@code then} once the Run scan has landed: at once when none is in flight, else when it lands. The scan
     * runs off the FX thread (PERF-01, Pauta 6.2.0), so a Run or Debug command issued right after a file opens
     * waits for the answer here before deciding how to launch, rather than scanning the text itself.
     */
    void whenRunScanned(Runnable then) {
        if (!runScanInFlight) {
            then.run();
            return;
        }
        afterRunScan.add(then);
    }

    /** Set only by {@link #settleRunScanForTest}: the scan runs where it is asked. */
    private boolean scanInPlaceForTest;

    /**
     * TEST SEAM — lands a scan still in flight now, scanning in place on the calling thread: what a test that reads
     * the gutter right after an open does instead of waiting. The app waits with {@link #whenRunScanned}.
     */
    void settleRunScanForTest() {
        if (runScanInFlight) {
            scanInPlaceForTest = true;
            try {
                recomputeRun(true);
            } finally {
                scanInPlaceForTest = false;
            }
        }
    }

    /** Whether a scan is in flight: {@link #whenRunScanned} then waits for it. */
    boolean runScanPending() {
        return runScanInFlight;
    }

    /** {@link com.editora.perf.BlockingSites} name of the Run scan, heard on the thread it runs on. */
    static final String RUN_SCAN_SITE = "BufferRunGutter.runScan";

    /** Recomputes runnable status + the gutter entry line(s); refreshes the gutter and fires the callback. */
    void recomputeRun() {
        recomputeRun(false);
    }

    /**
     * As {@link #recomputeRun()}. The scan of the text ({@link RunScan}, up to 256 KiB) runs off the FX thread
     * whatever asked for it (PERF-01, Pauta 6.2.0) — a settled edit, a load, Save As, a feature toggle — and a
     * generation counter drops a superseded result, as for {@link BufferTodos#refreshTodoMarks}. A pass that needs
     * no text (the features are off or the file is too large) answers at once. {@code settled} is kept for the
     * callers' sake: it no longer changes where the scan runs.
     */
    void recomputeRun(boolean settled) {
        b.maybeApplyShebang(); // an interpreter shebang can set the language before we gate the Run glyph
        long gen = ++runScanGen; // any newer pass (or dispose) supersedes an in-flight scan
        boolean small = !b.largeFile && b.area.getLength() <= COMPACT_SCAN_LIMIT;
        // Test gutter: independent of the LSP-gated run feature, so it has its own eligibility (a Java buffer
        // in a JVM project with the Test Runner on). Additive — a test file also keeps any compact-source ▶.
        // Project main-method gutter: a Java file in a Maven/Gradle project (gated by MainController).
        RunScan.Inputs in = new RunScan.Inputs(
                runFeatureEnabled && small,
                b.httpFeatureEnabled && small && b.isHttpFile(),
                testGutterEnabled && small && "java".equals(b.language),
                mainGutterEnabled && small && "java".equals(b.language),
                b.language,
                b.path != null ? b.path.getFileName().toString() : b.displayName,
                b.shebangJavaSource != null);
        // Only materialize the whole document when we actually scan it (compact-source / .http / test/main
        // detection). Otherwise editing a moderately large file (256 KB–5 MB) would allocate the full text on
        // every 150 ms edit pulse just to discard it as run-ineligible.
        if (!in.needsText()) {
            runScanInFlight = false;
            applyRunScan(RunScan.scan(in, ""), null);
            landed();
            return;
        }
        String text = b.documentTextSnapshot();
        if (scanInPlaceForTest) {
            runScanInFlight = false;
            RunScan.Result found = RunScan.scan(in, text);
            applyRunScan(found, testIndexOf(text, found));
            landed();
            return;
        }
        runScanInFlight = true;
        long version = b.docVersion;
        EditorBuffer.HIGHLIGHT_POOL.execute(() -> {
            com.editora.perf.BlockingSites.enter(RUN_SCAN_SITE);
            RunScan.Result found = RunScan.scan(in, text);
            com.editora.test.JavaTestScanner.CaretIndex index = testIndexOf(text, found);
            Platform.runLater(() -> {
                if (gen != runScanGen || b.disposed) {
                    return; // a newer pass answers
                }
                if (version != b.docVersion) {
                    // An edit since the snapshot moved the lines this scan reports: applying them would put the ■ on
                    // the old lines and drop every .http result the edit had moved (reanchor reads them as edited).
                    // That edit's own settled pass scans the new text; a command waiting for it scans it now.
                    if (!afterRunScan.isEmpty()) {
                        recomputeRun(true);
                    }
                    return;
                }
                runScanInFlight = false;
                applyRunScan(found, index);
                landed();
            });
        });
    }

    /** Runs what waited for the scan, now that it has landed. */
    private void landed() {
        if (afterRunScan.isEmpty()) {
            return;
        }
        java.util.List<Runnable> waiting = java.util.List.copyOf(afterRunScan);
        afterRunScan.clear();
        waiting.forEach(Runnable::run);
    }

    /** The test-at-caret index of a scan's text, for its test targets; null when it found none. Scan thread. */
    private static com.editora.test.JavaTestScanner.@Nullable CaretIndex testIndexOf(
            String text, RunScan.Result found) {
        return found.testLines().isEmpty()
                ? null
                : com.editora.test.JavaTestScanner.caretIndex(text, found.testLines());
    }

    private void applyRunScan(RunScan.Result now, com.editora.test.JavaTestScanner.@Nullable CaretIndex index) {
        testIndex = index;
        boolean changed = now.runnable() != runnable;
        boolean linesChanged = !now.httpLines().equals(b.httpGutter.requestLines())
                || !now.makeTargets().equals(makeTargets)
                || !now.testLines().equals(testLines)
                || !now.mainLines().equals(mainLines);
        int oldLine = runLine;
        runnable = now.runnable();
        runLine = now.line();
        b.httpGutter.setRequestLines(now.httpLines(), b::paragraphTextOrNull); // the ■ follows its request
        b.repaintHttpResults(b.httpResults.reanchor(now.httpLines(), b::paragraphTextOrNull)); // edited: it goes
        makeTargets = now.makeTargets();
        testLines = now.testLines();
        mainLines = now.mainLines();
        if (changed) {
            onRunnableChanged.run();
            b.refreshGutter(); // the Run slot appeared/disappeared on every row — rebuild the factory
        } else if (linesChanged) {
            b.refreshGutter(); // requests/targets/test/main methods added/removed — relight the glyphs
        } else if (runLine != oldLine) {
            // The entry line moved (edits above it) — repaint just the old and new gutter rows.
            if (oldLine >= 0) {
                b.refreshGutterLine(oldLine);
            }
            if (runLine >= 0) {
                b.refreshGutterLine(runLine);
            }
        }
    }

    /** Sets the "Run File" context-menu handler (controller runs the compact source file); null disables it. */
    void setRunHandler(Runnable handler) {
        this.runHandler = handler;
    }

    /** Sets the "Debug File" context-menu entry: shown beside "Run File" while {@code shown} holds. */
    void setDebugFileHandler(Runnable handler, java.util.function.BooleanSupplier shown) {
        this.debugFileHandler = handler;
        this.debugFileShown = shown == null ? () -> false : shown;
    }
}
