package com.editora.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

import com.editora.config.RunConfiguration;
import com.editora.dap.DapManager;
import com.editora.dap.DapServerRegistry;
import com.editora.dap.JsLaunch;
import com.editora.dap.RustLaunch;
import com.editora.editor.EditorBuffer;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * Debug ▸ Start (F5), Debug File and Debug Test at Caret: the check every way to start goes through (a live launch is
 * continued, kept, or replaced after asking), then the file's launch, sent on by its language — Java and the other
 * JVM languages here, Go, Rust, C, C++ and C# to {@link CompiledDebugLaunch}, the rest to their own launches. Part of
 * {@link DebugCoordinator}.
 */
final class DebugFileLaunch {

    private final DebugCoordinator d; // its host and ops are read when used: this is made with the coordinator

    DebugFileLaunch(DebugCoordinator d) {
        this.d = d;
    }

    /**
     * Debug ▸ Start for the active file (saving first, like Run) — or Continue, when the session there is
     * paused or stepping.
     */
    void debugStart() {
        EditorBuffer b = d.host.activeBuffer();
        if (!clearToStart(b, true)) {
            return;
        }
        if (b == null || testDebugger == null || !"java".equals(b.getLanguage())) {
            launchBuffer(b);
            return;
        }
        // The test lines are the Run scan's and the main class a parse: both made off the FX thread (PERF-01).
        b.whenRunScanned(d.bound(() -> {
            String text = b.getContent();
            d.planOffFx("debug-own-main", () -> DebugPlanning.ownMainClass(text), a -> {
                d.s().startCleared = true; // Debug Test's own check (clearToDebugTest) has been answered here
                boolean test = debugsTest(b, a.value());
                d.s().startCleared = false;
                if (!test) {
                    launchBuffer(b);
                }
            });
        }));
    }

    /** Debug File (the editor menu, the Debug File handler): {@code b}, after the same check as F5's. */
    void debugFile(EditorBuffer b) {
        if (clearToStart(b, false)) {
            launchBuffer(b);
        }
    }

    /** Debug Test at the caret or from the editor menu: false when a live launch stays (said, or asked and kept). */
    boolean clearToDebugTest() {
        return d.s().startCleared || clearToStart(d.host.activeBuffer(), false);
    }

    /**
     * The one check every way to start goes through (review 3, C202–C204, A208, A209). A launch is live — or still
     * getting ready: a build before it, a before-launch step, a test run waiting for its JVM, a compile, the
     * main-class picker. For F5 ({@code f5}) a paused (or mid-step) session is continued: never yank a paused
     * session. Otherwise the same file is already being debugged (said so, in words that fit how far it has got),
     * and another file replaces it — after asking, since that ends the program (review A106: a starting launch was
     * replaced unasked; review 3 C202: the editor menu's Debug File ended a paused session unasked). True when
     * {@code b} may be launched now.
     */
    private boolean clearToStart(@Nullable EditorBuffer b, boolean f5) {
        Path path = b == null ? null : b.getPath();
        if (path != null) {
            for (DebugSession other : d.sessions.all()) {
                if (other != d.s() && d.inSession(other, () -> launchLiveFor(path))) {
                    d.focusSession(other); // the file is another session's: that one is shown, and asked about
                    return clearToStart(b, f5);
                }
            }
        }
        boolean preparing = launchPreparing();
        Path liveFile = preparing ? preparingFile() : d.dap().debugFile();
        boolean differentFile = path != null && !d.samePath(path, liveFile);
        boolean debuggable = b != null && path != null && d.debugEffectiveFor(b.getLanguage(), path);
        boolean active = d.dap().isActive();
        DapManager.State state = d.dap().state();
        DebugStartChoice choice = f5
                ? DebugStartChoice.of(active, state, d.dap().isStepping(), preparing, differentFile, debuggable)
                : DebugStartChoice.forFile(active, state, preparing, differentFile);
        DebugStartChoice.Phase phase =
                DebugStartChoice.Phase.of(active, state, d.dap().isStepping(), preparing);
        String label = preparing && d.s().pendingBuild == null && d.s().pendingAttachFile != null
                ? String.valueOf(d.s().pendingAttachFile.getFileName())
                : d.s().sessionLabel;
        switch (choice) {
            case CONTINUE -> {
                d.dap().resume(); // F5-style continue
                return false;
            }
            case ALREADY_RUNNING -> {
                d.host.setStatus(tr(phase.busyKey, label));
                return false;
            }
            case ASK_RETARGET -> {
                if (path == null) {
                    return false;
                }
                // Both at once beside a session that has started; one still getting ready (a build, a test run's
                // JVM) is replaced or kept, as before.
                boolean both = active && !preparing;
                Retarget answer = askRetarget(
                        DebugRetargetPrompt.question(both, phase.retargetKey, label, path.getFileName()), both);
                switch (answer) {
                    case BOTH -> {
                        d.openSession(); // the launch asked for is the new session's
                        launching(path);
                        return true;
                    }
                    case STOP -> {
                        endLiveLaunch(); // retarget to the file asked for
                        launching(path);
                        return true;
                    }
                    default -> {
                        return false;
                    }
                }
            }
            default -> {
                launching(path); // getting ready while it saves and plans
                return true; // nothing to end first
            }
        }
    }

    /**
     * The longest a session's answers still to come (a save, a plan, a scan: off the FX thread since PERF-01) count
     * as a launch getting ready: one that never answers (a save that failed) does not hold F5 for longer.
     */
    static final long STEPS_PENDING_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(15);

    /** {@code path}'s launch is let through: until its last save or plan has answered, it is getting ready. */
    private void launching(@Nullable Path path) {
        d.s().followUp = null; // another launch: what a launch.json configuration asked for is not its
        d.s().launching = true;
        d.s().launchAskedFile = path;
    }

    /**
     * A launch let through is still saving its files or reading its plan: an answer is still to come, not long ago.
     * A second F5 meanwhile launched the program twice.
     */
    private boolean launchAskedRecently() {
        return d.s().launching
                && d.s().stepsPending > 0
                && d.dap().state() == DapManager.State.INACTIVE
                && System.nanoTime() - d.s().stepsPendingSince < STEPS_PENDING_NANOS;
    }

    /**
     * A launch is getting ready with no session yet: a jdtls build, a before-launch step, a test run's JVM, or one
     * still saving its files or reading its plan.
     */
    private boolean launchPreparing() {
        return !d.dap().isActive()
                && (d.s().pendingBuild != null
                        || d.beforeLaunchActive()
                        || d.s().pendingAttachStop != null
                        || launchAskedRecently());
    }

    /** The file the launch getting ready is for, when known. */
    private @Nullable Path preparingFile() {
        return d.s().pendingBuild != null
                ? d.s().pendingBuildFile
                : d.s().pendingAttachStop != null
                        ? d.s().pendingAttachFile
                        : launchAskedRecently() ? d.s().launchAskedFile : null;
    }

    /** A launch of {@code file} is live or getting ready. */
    boolean launchLiveFor(Path file) {
        if (launchPreparing()) {
            return d.samePath(file, preparingFile());
        }
        return d.dap().state() != DapManager.State.INACTIVE
                && d.samePath(file, d.dap().debugFile());
    }

    /** Ends whatever launch is live or getting ready, for a launch of another file. */
    private void endLiveLaunch() {
        if (d.beforeLaunchActive()) {
            d.beforeLaunch().stop();
        }
        d.javaLaunch.dropPendingBuild();
        d.stop(); // the session, or a test run waiting for its JVM
    }

    /** Debugs a JUnit test at the caret ({@code true}: the whole class), as the gutter's Debug Test; set by the
     *  window. Null: Debug ▶ launches a test file like any other. */
    private @Nullable Consumer<Boolean> testDebugger;

    void setTestDebugger(Consumer<Boolean> testDebugger) {
        this.testDebugger = testDebugger;
    }

    /** Debug ▶ in a JUnit test file debugs the test at the caret, or its class (review A102); true when it did. */
    private boolean debugsTest(EditorBuffer b, @Nullable String ownMain) {
        DebugTestRoute.Route route = DebugTestRoute.route(
                true,
                b.isCompactSource(),
                b.testClassTarget() != null,
                ownMain != null,
                b.testTargetAtCaret(false) != null);
        if (route == DebugTestRoute.Route.LAUNCH) {
            return false;
        }
        java.util.Objects.requireNonNull(testDebugger).accept(route == DebugTestRoute.Route.TEST_CLASS);
        return true;
    }

    /**
     * Asks before F5 in another tab ends the running session (review A13); a test replaces it. The answer is
     * the user's: a running server is not stopped by a key pressed in the wrong tab.
     */
    @Nullable
    Predicate<String> confirmRetarget;

    /** What to do with the live launch when another file is asked to be debugged. */
    enum Retarget {
        /** Debug the other file in a session of its own, beside the live one. */
        BOTH,
        /** End the live launch and debug the other file instead. */
        STOP,
        /** Leave things as they are. */
        CANCEL
    }

    /**
     * Answers {@link #askRetarget} in tests: the question, and whether Debug Both was offered. Null: ask the user.
     * When only {@link #confirmRetarget} is set, its yes is {@link Retarget#STOP}.
     */
    java.util.function.@Nullable BiFunction<String, Boolean, Retarget> retargetChoice;

    /**
     * Asks before F5 in another tab ends the live launch: Debug Both (when {@code bothOffered}: the default, a running
     * server is not stopped by a key pressed in the wrong tab), Stop and Debug, or Cancel.
     */
    private Retarget askRetarget(String question, boolean bothOffered) {
        if (retargetChoice != null) {
            return retargetChoice.apply(question, bothOffered);
        }
        if (confirmRetarget != null) {
            return confirmRetarget.test(question) ? Retarget.STOP : Retarget.CANCEL;
        }
        return DebugRetargetPrompt.ask(d.host.window(), question, bothOffered);
    }

    /** Saves {@code b} and launches a debug session for it; also what Restart repeats for such a session. */
    void launchBuffer(@Nullable EditorBuffer b) {
        launchBuffer(b, List.of());
    }

    /**
     * Debugs a Ruby, Bash or Lua script {@code path} in its folder: under rdbg, the bashdb vscode-bash-debug ships, or
     * Local Lua Debugger ({@link com.editora.dap.ScriptLaunch#plan}); a PHP file goes to {@link PhpDebugLaunch}.
     */
    private void debugScriptFile(Path path, String language, List<String> args) {
        Path cwd = path.toAbsolutePath().getParent();
        var plan = com.editora.dap.ScriptLaunch.plan(language, path, args, cwd != null ? cwd : path.toAbsolutePath());
        Runnable start = () ->
                d.withClosedBreakpoints(() -> d.dap().startExternal(path, plan.language(), plan.launch(), plan.tail()));
        d.s().relaunch = start;
        start.run();
    }

    /**
     * Debug Test at Caret in a Rust file: the {@code #[test]} function at the caret, its test binary filtered to it
     * ({@link CompiledDebugLaunch#debugRustFile}). False — nothing done — outside a test, or for any other buffer.
     */
    boolean debugRustTestAtCaret(@Nullable EditorBuffer b, Runnable otherwise) {
        if (b == null || b.getPath() == null || !"rust".equals(b.getLanguage())) {
            return false;
        }
        int caret = d.caretLine(b); // the test is found off the FX thread (PERF-01): the command goes on once it is
        var test = d.scan(b, "rustTest:" + caret, t -> RustLaunch.testAt(t, caret), () -> {
            if (!debugRustTestAtCaret(b, otherwise)) {
                otherwise.run();
            }
        });
        if (test != null && test.value() == null) {
            return false;
        }
        if (test != null) {
            launchBuffer(b);
        }
        return true;
    }

    /**
     * Debug Test at Caret in a C or C++ test file (GoogleTest, Catch2, doctest — {@link com.editora.test.CppTests}): its
     * program built and debugged as Debug File debugs it, run with the filter of the test at the caret, or every test
     * outside one. False — nothing done — for any other buffer.
     */
    boolean debugCppTestAtCaret(@Nullable EditorBuffer b, Runnable otherwise) {
        if (b == null || b.getPath() == null || !("c".equals(b.getLanguage()) || "cpp".equals(b.getLanguage()))) {
            return false;
        }
        int caret = d.caretLine(b); // the test is found off the FX thread (PERF-01): the command goes on once it is
        var found = d.scan(b, "cppTest:" + caret, CppTestAt::of, () -> {
            if (!debugCppTestAtCaret(b, otherwise)) {
                otherwise.run();
            }
        });
        if (found == null) {
            return true; // asked again once the scan answers
        }
        CppTestAt at = found.value();
        if (at == null) {
            return false;
        }
        CppTestAt here = at.at(caret);
        debugCppTests(b, here.kind(), here.filter());
        return true;
    }

    /** A C or C++ test file's framework and its tests, as scanned; {@link #at} the one a line is in. */
    private record CppTestAt(
            com.editora.test.ToolTestKind kind,
            String text,
            @Nullable String filter) {

        static @Nullable CppTestAt of(String text) {
            com.editora.test.ToolTestKind kind = com.editora.test.CppTests.framework(text);
            return kind == null ? null : new CppTestAt(kind, text, null);
        }

        CppTestAt at(int line) {
            com.editora.test.CppTests.Test t = com.editora.test.CppTests.at(kind, text, line);
            return new CppTestAt(kind, text, t == null ? null : t.filter());
        }
    }

    /** A C or C++ test's ▶ (its editor menu's Debug Test): that test, or every test of the file on its first line. */
    void debugCppGlyph(EditorBuffer b, com.editora.test.JavaTestScanner.TestTarget target) {
        String text = b.getContent();
        com.editora.test.ToolTestKind kind = com.editora.test.CppTests.framework(text);
        if (kind == null || b.getPath() == null) {
            return;
        }
        String filter = null;
        if (target.methodName() != null) {
            for (com.editora.test.CppTests.Test t : com.editora.test.CppTests.scan(kind, text)) {
                if (t.line() == target.line()) {
                    filter = t.filter();
                }
            }
        }
        debugCppTests(b, kind, filter);
    }

    private void debugCppTests(EditorBuffer b, com.editora.test.ToolTestKind kind, @Nullable String filter) {
        Path path = b.getPath();
        if (path == null) {
            return;
        }
        d.compiled.debugNative(
                path,
                "cpp".equals(b.getLanguage()),
                com.editora.test.CppTests.filterArgs(kind, filter == null ? List.of() : List.of(filter)),
                DebugCoordinator.LaunchExtras.NONE);
    }

    /**
     * Debug Test at Caret on a Go test file ({@code *_test.go}): the test at the caret under Delve's test mode, the
     * package's tests outside any. False — nothing done — for any other buffer.
     */
    boolean debugDartTestAtCaret(@Nullable EditorBuffer b) {
        return d.dart.debugTestAtCaret(b); // its test or group, on the adapter's test mode
    }

    boolean debugGoTestAtCaret(@Nullable EditorBuffer b) {
        if (b == null
                || b.getPath() == null
                || !"go".equals(b.getLanguage())
                || !com.editora.dap.GoLaunch.isTestFile(b.getPath())) {
            return false;
        }
        launchBuffer(b);
        return true;
    }

    /**
     * Debug Test at Caret on a JavaScript/TypeScript test file ({@link com.editora.dap.JsLaunch#isTestFile}): the
     * file runs under {@code node --test} (in a Deno project, {@code deno test}), narrowed to the test at the caret by
     * name when there is one. False —
     * nothing done — for any other buffer.
     */
    boolean debugJsTestAtCaret(@Nullable EditorBuffer b) {
        if (b == null
                || b.getPath() == null
                || !"javascript".equals(DapServerRegistry.adapterLanguage(b.getLanguage()))
                || !com.editora.dap.JsLaunch.isTestFile(b.getPath())) {
            return false;
        }
        int caret = b.getFocusedArea().getCurrentParagraph()
                + b.getBreakpointManager().regionFirstLine();
        List<String> nodeArgs = new java.util.ArrayList<>(List.of("--test"));
        var named = d.scan(b, "jsTest:" + caret, t -> JsLaunch.testArgsAt(t, caret), () -> debugJsTestAtCaret(b));
        if (named == null) {
            return true; // found off the FX thread (PERF-01): asked again once it is
        }
        nodeArgs.addAll(java.util.Objects.requireNonNull(named.value())); // Node's test pattern, Deno's test name
        launchBuffer(b, nodeArgs);
        return true;
    }

    /** {@link #launchBuffer(EditorBuffer)} with Node.js arguments for a JavaScript program ({@code --test}). */
    private void launchBuffer(@Nullable EditorBuffer b, List<String> nodeArgs) {
        if (b != null && b.isHttpFile()) {
            d.ops.debugHttpRequest(); // its scripts, around the request at the caret
            return;
        }
        // A file no adapter can debug is told so first: an untitled one used to get a Save As dialog, and then
        // a shell script "Debugging is unavailable for this file (check Settings → Debugging)", which no
        // setting can change.
        if (b != null && b.isShell() && !com.editora.dap.ScriptLaunch.isBashScript(b.getPath(), b.getContent())) {
            d.host.setStatus(tr("status.debug.shellNotBash")); // bashdb debugs Bash scripts alone
            return;
        }
        if (b != null && b.getPath() == null && !DapServerRegistry.isDebuggable(b.getLanguage())) {
            d.host.setStatus(tr("status.debug.languageNotDebuggable"));
            return;
        }
        if (b != null && b.isHtml()) {
            d.browser.debugHtmlPage(b); // its scripts run in a browser
            return;
        }
        if (b == null || b.getPath() == null) { // an untitled file is saved first, off the FX thread (PERF-01)
            d.savedOrThen(b == null ? List.of() : List.of(b), () -> launchBuffer(b, nodeArgs));
            if (b == null) {
                d.host.setStatus(tr("status.debug.saveFirst"));
            }
            return;
        }
        String language = DapServerRegistry.adapterLanguage(b.getLanguage()); // a .ts file: JavaScript
        if (!d.debugEffectiveFor(language, b.getPath())) {
            d.host.setStatus(d.unavailableMessage(language, b.getPath()));
            return;
        }
        if (b.isDirty() || b.getPath() == null) { // saved off the FX thread, then launched (PERF-01, Pauta 6.2.0)
            d.ops.saveThen(
                    b,
                    d.asThisSession(
                            saved -> { // the session that asked, whichever is focused when it lands
                                if (saved && b.getPath() != null && !b.isDirty()) {
                                    launchBuffer(b, nodeArgs);
                                } else if (saved) { // typed into while it saved: said, not dropped unseen
                                    Path edited = b.getPath();
                                    d.host.setStatus(tr(
                                            "status.debug.editedWhileSaving",
                                            edited == null ? "" : String.valueOf(edited.getFileName())));
                                }
                            }));
            return;
        }
        Path path = java.util.Objects.requireNonNull(b.getPath()); // checked above
        String text = b.getContent();
        String settingsJdk = d.host.projectScope().jdkHome(d.host.settings());
        // What the launch reads from the disk is looked at off the FX thread first (PERF-01): the trust gate's
        // project files, the build root and its pom, the JDK's java, PATH's java. What follows answers from that.
        d.planOffFx("debug-file-plan", () -> DebugPlanning.warmJavaLaunch(language, path, text, settingsJdk), a -> {
            if (a.value() != null) {
                launchBufferPlanned(b, path, language, nodeArgs, a.value());
            } else {
                d.planFailed(path, a.failure());
            }
        });
    }

    private void launchBufferPlanned(
            EditorBuffer b, Path path, String language, List<String> nodeArgs, DebugPlanning.FileRead read) {
        if (b.runScanPending()) { // compact-source detection is the Run scan, made off FX: decide from its result
            b.whenRunScanned(d.bound(() -> launchBufferPlanned(b, path, language, nodeArgs, read)));
            return;
        }
        // A Clojure file of a Leiningen, deps.edn or bb.edn project, a file of a Scala CLI project: its tool loads
        // the project, not just the file — the workspace-trust gate first, as Run asks it, so "no" starts nothing.
        // The gate's facts and a Java file's project are looked at off the FX thread first when not known (PERF-01).
        if (!d.factsKnown(
                        "debug-file-look",
                        d.facts.fileLaunch(read.gate(), language, path),
                        () -> launchBufferPlanned(b, path, language, nodeArgs, read))
                || !d.trusted(read.gate())) {
            return;
        }
        Integer shebangSource = b.getShebangJavaSource();
        if ("java".equals(language) && b.isCompactSource() && shebangSource != null && shebangSource < 25) {
            d.host.setStatus(tr("status.debug.needSource25", shebangSource));
            return;
        }
        // The debuggee gets the same per-file program arguments the Run feature uses, read the same way.
        List<String> fileArgs = d.debugArgs(d.ops.programArgs(path));
        if (fileArgs == null) {
            return;
        }
        d.ops.openToolWindow();
        d.nameSession(path.getFileName().toString());
        // Said once it runs: "Saved …" or the last run's exit stood in the status bar while it was paused (choice 12).
        d.s().announceStart = true;
        d.dap().setProgramArgs(fileArgs);
        d.dap().setVmArgs(""); // no user VM args on a plain debug
        d.dap().setHostJavaExec("");
        if (b.isPython()) {
            d.python.plan(path, PythonDebugLaunch.firstLine(b.getContent()));
        }
        d.dap().setJsProjectRoot(d.ops.projectRoot()); // a JavaScript file's Node.js, package root, workspace
        d.dap().setJsNodeArgs(nodeArgs);
        d.dap().setJsProgram(null); // the file itself, not a configuration's planned launch
        if ("javascript".equals(language) && com.editora.process.ExecTargets.runsHere(path)) {
            // Planned exactly as Run plans it (review D104, D106): the same Node.js, flags, environment and
            // folder — and for a TypeScript file whatever Run runs for it (tsx, or the compiled file).
            Path launchedJs = path;
            d.s().relaunch = () -> d.javaLaunch.relaunchFor(launchedJs, again -> launchBuffer(again, nodeArgs));
            d.node.debugJsFile(path, fileArgs, nodeArgs);
            return;
        }
        if ("go".equals(language)) {
            d.compiled.debugGoFile(b, path, fileArgs);
            return;
        }
        if ("rust".equals(language)) {
            d.compiled.debugRustFile(b, path, fileArgs);
            return;
        }
        if ("cpp".equals(language)) {
            d.compiled.debugNativeFile(b, path, fileArgs);
            return;
        }
        if ("csharp".equals(language)) {
            d.compiled.debugCsharpFile(path, fileArgs);
            return;
        }
        if ("php".equals(language)) {
            d.php.debug(path, b.getContent(), fileArgs); // a script, a web app's server, a PHPUnit test file
            return;
        }
        if ("ruby".equals(language)) {
            d.ruby.debug(path, b.getContent(), fileArgs); // a script, a spec or test file, a Rails app's server
            return;
        }
        if ("elixir".equals(language)) {
            d.elixir.debug(path, b.getContent(), fileArgs); // a Mix project's tests, a script, the project
            return;
        }
        if ("powershell".equals(language)) {
            d.powershell.debug(path, fileArgs); // a .ps1 on PowerShell Editor Services
            return;
        }
        if (java.util.Set.of("bash", "lua").contains(language)) {
            debugScriptFile(path, language, fileArgs);
            return;
        }
        if ("dart".equals(language)) {
            d.dart.debug(path, fileArgs, java.util.Map.of(), null, false);
            return;
        }
        com.editora.dap.DebugLanguage own = com.editora.dap.DebugLanguages.forLanguage(b.getLanguage());
        if (own != null && com.editora.dap.DebugLanguages.custom().contains(own)) {
            d.launchJson.debugCustomFile(b, path, own, fileArgs);
            return;
        }
        // Re-anchor closed files' breakpoints first (off-thread) so the initial setBreakpoints arms them too.
        boolean java = "java".equals(language);
        Path projectRoot = java ? JavaProjectRoot.find(path) : null;
        boolean compactSource = java && b.isCompactSource();
        // A plain-folder project window: its program runs in the project root, like Run's (review A16).
        Path project = java ? JavaLaunchPrep.projectOf(path, projectRoot, d.ops.projectRoot()) : null;
        if (java
                && !d.savedOrThen(
                        d.javaLaunch.javaBuffers(path, project, false),
                        () -> launchBufferPlanned(b, path, language, nodeArgs, read))) {
            return; // the launched file was saved above; the others of the project first, off the FX thread
        }
        if (projectRoot != null && !compactSource) {
            // A saved configuration for this class is how the project runs it — its before-launch build,
            // arguments, environment, working directory — exactly as Run ▶ uses it (review D1).
            String ownMain = read.ownMain(); // parsed with the plan, off the FX thread (PERF-01)
            RunConfiguration configured = ownMain == null
                    ? null
                    : com.editora.run.JavaLaunchPlan.configurationFor(ownMain, d.ops.runConfigurations());
            if (configured != null) {
                d.debugConfig(configured);
                return;
            }
        }
        // A remote file's program runs on its host: none of this machine's JDK setting, PATH or JAVA_HOME.
        // A Groovy script runs on the Settings JDK too (its JAVA_HOME), as Run runs it.
        boolean jvmScript = com.editora.run.JvmScriptRun.supports(language);
        String jdkHome = d.javaLaunch.presentJdk(
                !(java || jvmScript) || !com.editora.process.ExecTargets.runsHere(path)
                        ? ""
                        : compactSource || jvmScript
                                ? d.host.projectScope().jdkHome(d.host.settings())
                                : d.javaLaunch.configuredJdkHome(projectRoot, null));
        String selectedJava = com.editora.run.JdkToolchain.javaExecutable(jdkHome);
        // A Java file without a build tool runs on Run's JDK: the Settings JDK, else PATH's java (review 2, B102).
        String javaExec = java && projectRoot == null && com.editora.process.ExecTargets.runsHere(path)
                ? JavaDebugLaunch.looseJava(selectedJava)
                : selectedJava;
        d.dap()
                .setEnv(com.editora.run.JdkToolchain.environment(
                        jdkHome, com.editora.process.ProcessRunner.augmentedPath()));
        Path launched = path;
        d.s().relaunch = () -> d.javaLaunch.relaunchFor(launched, again -> launchBuffer(again, nodeArgs));
        if ("kotlin".equals(language)
                && com.editora.run.KotlinRun.shape(launched.getFileName().toString())
                        == com.editora.run.KotlinRun.Shape.PROGRAM) {
            // Compiled first (into the Debug console), then `java -jar` with the agent: Restart compiles an edit.
            d.javaLaunch.debugKotlinProgram(
                    launched,
                    null,
                    javaExec,
                    com.editora.run.JdkToolchain.environment(
                            jdkHome, com.editora.process.ProcessRunner.augmentedPath()));
            return;
        }
        Path looseCwd = projectRoot == null ? project : null;
        Runnable start = () -> d.withClosedBreakpoints(() -> {
            Path file = b.getPath(); // read when the launch starts, after any build
            if (file == null) {
                return;
            }
            if (compactSource && shebangSource != null) {
                d.dap().startCompactShebang(file, shebangSource, javaExec);
            } else if (compactSource && file.getFileName().toString().endsWith(".java")) {
                d.dap().startCompactSource(file, javaExec);
            } else if (java) {
                d.dap().startLaunch(file, d.javaLaunch::pickMainClass, javaExec, projectRoot, looseCwd);
            } else {
                d.dap().startLaunch(file, language, d.javaLaunch::pickMainClass, javaExec, projectRoot);
            }
        });
        if (projectRoot != null && !compactSource) {
            d.javaLaunch.buildThenLaunch(path, start);
        } else if (java && !compactSource) {
            d.javaLaunch.looseBuildThenLaunch(
                    path, start); // a loose file or a plain folder is built too (review 2, A101)
        } else {
            start.run();
        }
    }
}
