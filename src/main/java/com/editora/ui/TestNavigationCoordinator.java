package com.editora.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import javafx.scene.control.Tab;

import com.editora.build.BuildTool;
import com.editora.config.ConfigManager;
import com.editora.config.Project;
import com.editora.config.ProjectManager;
import com.editora.editor.EditorBuffer;

import static com.editora.i18n.Messages.tr;

/** Routes test and stack locations and applies project gutter eligibility. */
final class TestNavigationCoordinator {
    interface Host {

        EditorArea editorArea();

        ConfigManager config();

        ProjectManager projects();

        Project windowProject();

        ToolWindowManager toolWindows();

        com.editora.lsp.LspManager lspManager();

        ToolWindow testResultsToolWindow();

        WindowChromeCoordinator chrome();

        FileWorkflowCoordinator fileWorkflows();

        WindowSessionCoordinator sessions();

        CoordinatorHost coordinatorHost();

        List<BuildCoordinator> buildCoordinators();

        RunCoordinator runCoordinator();

        TestRunCoordinator testRunCoordinator();

        LspCoordinator lspCoordinator();

        boolean isLocalBuffer(EditorBuffer b);

        boolean lspEnabled();

        void openAndGoto(Path file, int line0, int col0);

        void setStatus(String message);

        EditorBuffer activeBuffer();

        EditorBuffer bufferOf(Tab tab);

        Tab tabForPath(Path file);
    }

    private final Host host;

    TestNavigationCoordinator(Host host) {
        this.host = host;
    }

    /** A stack-trace location double-clicked in the Run/Debug console: resolve + jump. An absolute
     *  path opens directly; a bare Java file name resolves against the open tabs, then the run file's
     *  directory, then the active project root's top level. */
    void openRunLink(com.editora.run.StackTraceLinks.Link link) {
        // Ask the Java server first when one is running (#744): it resolves the frame against the real
        // classpath, so it can place a frame inside a dependency or the JDK — which the local
        // regex + filesystem walk below can never do, because there is no such file in the project.
        Path anchor = lspStackTraceAnchor();
        if (anchor != null && link.raw() != null) {
            host.lspManager().resolveStackTraceLocation(anchor, link.raw(), uri -> {
                if (uri == null || !openResolvedFrame(anchor, uri, link)) {
                    openRunLinkLocally(link); // no answer, or an answer we can't open — heuristic as before
                }
            });
            return;
        }
        openRunLinkLocally(link);
    }

    /** The file whose session answers stack-trace resolution: the active buffer if the Java server serves
     *  it, else null (which keeps the local heuristic as the only path — e.g. a Python traceback). */
    Path lspStackTraceAnchor() {
        if (!host.lspEnabled()) {
            return null;
        }
        EditorBuffer b = host.activeBuffer();
        Path path = b == null ? null : b.getPath();
        return path != null && host.lspManager().isManaged(path) && "java".equals(b.getLanguage()) ? path : null;
    }

    /** Opens a server-resolved frame URI; false when it names something we can't open, so the caller falls
     *  back. A {@code jdt://} answer is a library frame and opens as read-only class-file source (#665). */
    boolean openResolvedFrame(Path anchor, String uri, com.editora.run.StackTraceLinks.Link link) {
        if (uri.startsWith("jdt:")) {
            host.lspCoordinator().openLibraryFrame(anchor, uri, link.line() - 1);
            return true;
        }
        try {
            Path file = Path.of(java.net.URI.create(uri));
            if (java.nio.file.Files.isRegularFile(file)) {
                host.openAndGoto(file, link.line() - 1, 0); // console lines are 1-based
                return true;
            }
        } catch (RuntimeException notAUsableUri) {
            return false;
        }
        return false;
    }

    void openRunLinkLocally(com.editora.run.StackTraceLinks.Link link) {
        Path resolved = resolveRunLinkFile(link.file());
        if (resolved == null) {
            host.setStatus(tr("status.run.linkNotFound", link.file()));
            return;
        }
        host.openAndGoto(resolved, link.line() - 1, 0); // console lines are 1-based
    }

    Path resolveRunLinkFile(String fileToken) {
        try {
            Path p = Path.of(fileToken);
            if (p.isAbsolute() && java.nio.file.Files.isRegularFile(p)) {
                return p; // a genuinely local absolute path (a local run/build log)
            }
            // A CI log carries the *runner's* paths (absolute /home/runner/work/<r>/<r>/… or repo-relative),
            // which don't exist locally — try progressively shorter repo-relative suffixes under the project
            // root so a GitHub Actions failure frame still jumps to the local file. Exact paths are tried
            // first (candidate 0), so local behaviour is unchanged.
            Project activeProject =
                    host.projects() == null ? null : host.projects().active();
            if (activeProject != null) {
                Path root = Path.of(activeProject.root());
                for (String candidate : com.editora.run.RunnerPaths.candidates(fileToken)) {
                    Path c = root.resolve(candidate);
                    if (java.nio.file.Files.isRegularFile(c)) {
                        return c;
                    }
                }
            }
            if (p.isAbsolute()) {
                return null; // absolute, not local, and no repo-relative suffix matched
            }
            String name = p.getFileName().toString();
            for (Tab t : host.editorArea().tabs()) { // an open tab with that file name wins
                EditorBuffer b = host.bufferOf(t);
                if (b != null
                        && b.getPath() != null
                        && b.getPath().getFileName().toString().equals(name)) {
                    return b.getPath();
                }
            }
            Path lastRunDir = host.runCoordinator().lastRunDir();
            if (lastRunDir != null) {
                Path sibling = lastRunDir.resolve(name);
                if (java.nio.file.Files.isRegularFile(sibling)) {
                    return sibling;
                }
            }
            // (The old "<project root>/<bare name>" fallback is subsumed by the candidate walk above — the
            // last candidate is always the bare file name, resolved against the same root.)
        } catch (RuntimeException ignored) {
            // Malformed path token — treat as unresolvable.
        }
        return null;
    }

    /** The build coordinator that owns {@code tool} (for the Test Results rerun/stop hooks). */
    java.util.Optional<BuildCoordinator> buildCoordinatorFor(BuildTool tool) {
        return host.buildCoordinators().stream().filter(c -> c.tool() == tool).findFirst();
    }

    /** {@code test.run}: run the {@code test} task of the first detected build tool for the active context. */
    void runTestsForContext() {
        for (BuildCoordinator c : host.buildCoordinators()) {
            if (c.isEnabled() && c.isDetected()) {
                c.runTask(com.editora.test.TestRunRecognizer.defaultTestTask(c.tool()), List.of());
                return;
            }
        }
        host.setStatus(tr("status.testrunner.noBuildTool"));
    }

    /** Whether a JVM build tool (Maven/Gradle) is detected + enabled — the JUnit test gutter's build-side gate. */
    boolean jvmBuildDetected() {
        return host.buildCoordinators().stream()
                .anyMatch(c -> (c.tool() == BuildTool.MAVEN || c.tool() == BuildTool.GRADLE)
                        && c.isEnabled()
                        && c.isDetected());
    }

    /** Pushes the project main-method gutter gate to a buffer (not Simple mode + local + a detected Maven/Gradle
     *  project). */
    void applyMainGutter(EditorBuffer buffer) {
        // Show the ▶ for any detected Maven/Gradle project; run/debug availability (jdtls, or the mvn
        // classpath fallback) is checked at click time so the gutter is not hidden when only the fallback works.
        buffer.setMainGutterEnabled(
                !host.chrome().simpleModeActive() && host.isLocalBuffer(buffer) && jvmBuildDetected());
    }

    /** Pushes the JUnit test-gutter gate to a buffer (Test Runner on + not Simple mode + local + JVM project). */
    void applyTestGutter(EditorBuffer buffer) {
        boolean eligible = host.config().getSettings().isTestRunner()
                && !host.chrome().simpleModeActive()
                && host.isLocalBuffer(buffer)
                && jvmBuildDetected();
        buffer.setTestGutterEnabled(eligible);
        if (eligible) {
            refineTestGutterWithServer(buffer); // #745 — asynchronous; only ever turns the gutter OFF
        }
    }

    /**
     * Confirms the test gutter against jdtls's project model ({@code java.project.isTestFile}, #745).
     *
     * <p>{@code JavaTestScanner} decides syntactically — a class carrying {@code @Test} methods — which is
     * right nearly always but can't see source roots, so it decorates a {@code src/main/java} class that
     * happens to carry such an annotation. The server knows which folders are test folders.
     *
     * <p>Deliberately a <b>refinement, not a gate</b>: the scanner's answer is applied immediately and this
     * only ever removes the gutter, and only on a definite {@code false}. A null (no session, command
     * failed, server still starting) leaves the scanner's answer alone, so the gutter never disappears
     * merely because the server isn't ready. Cached per path — this runs on every tab switch.
     */
    void refineTestGutterWithServer(EditorBuffer buffer) {
        Path path = buffer.getPath();
        if (path == null || !host.lspEnabled() || !host.lspManager().isManaged(path)) {
            return;
        }
        Boolean cached = testFileByPath.get(path);
        if (cached != null) {
            if (!cached) {
                buffer.setTestGutterEnabled(false);
            }
            return;
        }
        host.lspManager().isTestFile(path, isTest -> {
            if (isTest == null) {
                return; // "don't know" — keep the scanner's answer
            }
            testFileByPath.put(path, isTest);
            if (!isTest && buffer.getPath() == path) {
                buffer.setTestGutterEnabled(false);
            }
        });
    }

    /** jdtls's verdict on whether a path is a test source (#745); session-scoped, cleared on LSP restart. */
    final Map<Path, Boolean> testFileByPath = new java.util.concurrent.ConcurrentHashMap<>();

    /** Gutter test ▶ / {@code test.runAtCaret}: run one class/method via the detected JVM build tool. */
    void runSingleTest(com.editora.test.JavaTestScanner.TestTarget target) {
        for (BuildCoordinator c : host.buildCoordinators()) {
            if ((c.tool() == BuildTool.MAVEN || c.tool() == BuildTool.GRADLE) && c.isEnabled() && c.isDetected()) {
                c.runTask(
                        com.editora.test.TestRunRecognizer.singleTestTask(
                                c.tool(), target.className(), target.methodName()),
                        List.of());
                return;
            }
        }
        host.setStatus(tr("status.testrunner.noBuildTool"));
    }

    /** Runs/debugs a test method (or its whole class) at the caret. */
    void runTestAtCaret(boolean classLevel, boolean debug) {
        EditorBuffer b = host.activeBuffer();
        com.editora.test.JavaTestScanner.TestTarget target = b == null ? null : b.testTargetAtCaret(classLevel);
        if (target == null) {
            host.setStatus(tr("status.testrunner.noTestAtCaret"));
            return;
        }
        if (debug) {
            debugSingleTest(target);
        } else {
            runSingleTest(target);
        }
    }

    /** Editor context-menu path for a scanned JUnit class/method. */
    void debugSingleTest(com.editora.test.JavaTestScanner.TestTarget target) {
        for (BuildCoordinator c : host.buildCoordinators()) {
            if ((c.tool() == BuildTool.MAVEN || c.tool() == BuildTool.GRADLE) && c.isEnabled() && c.isDetected()) {
                host.testRunCoordinator().debugSingleTest(c.tool(), target, args -> c.runTask(args, List.of()));
                return;
            }
        }
        host.setStatus(tr("status.testrunner.noBuildTool"));
    }

    /** Gates the Test Results tool window: hidden when the feature is off or in Simple UI mode (it becomes
     *  available again on the next test run). Also re-gates every buffer's JUnit test gutter. */
    void applyTestRunner() {
        if (host.testResultsToolWindow() == null) {
            return;
        }
        if (!host.config().getSettings().isTestRunner() || host.chrome().simpleModeActive()) {
            host.toolWindows().setAvailable(host.testResultsToolWindow(), false);
        }
        host.coordinatorHost().forEachBuffer(this::applyTestGutter);
    }

    /** Test Results double-click: open the test's source file and jump to the method (name-based; the failure
     *  path already prefers the exact stack-trace frame via {@link #openRunLink}). */
    void jumpToTestSource(com.editora.test.TestNode node, BuildTool tool) {
        String hint = com.editora.test.TestSourceLocator.fileHint(node.className(), tool);
        Path file = hint == null ? null : resolveTestSourceFile(hint);
        if (file == null) {
            host.setStatus(tr("status.testrunner.noSource", node.displayName()));
            return;
        }
        host.fileWorkflows().openPath(file);
        jumpToTestMethod(file, node.methodName());
    }

    /** Resolves a test source file by name: an open tab / last-run dir / project root first, else a bounded
     *  walk of the project tree (test sources live deep under src/test/…). */
    Path resolveTestSourceFile(String name) {
        Path direct = resolveRunLinkFile(name);
        if (direct != null) {
            return direct;
        }
        Path root = host.windowProject() != null ? Path.of(host.windowProject().root()) : null;
        if (root == null || !java.nio.file.Files.isDirectory(root)) {
            return null;
        }
        try (java.util.stream.Stream<Path> walk = java.nio.file.Files.walk(root, 12)) {
            return walk.filter(java.nio.file.Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equals(name))
                    .filter(p -> {
                        String s = p.toString();
                        return !s.contains("/target/") && !s.contains("/node_modules/") && !s.contains("/build/");
                    })
                    .findFirst()
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    /** Moves the caret to a test method's declaration in an already-open file (text search; strips param/
     *  parameterized/subtest suffixes). Best-effort — leaves the caret at the top if not found. */
    void jumpToTestMethod(Path file, String methodName) {
        EditorBuffer buffer = host.bufferOf(host.tabForPath(file));
        if (buffer == null || methodName == null || methodName.isBlank()) {
            return;
        }
        String name = methodName;
        for (char sep : new char[] {'(', '[', '/', ' '}) {
            int cut = name.indexOf(sep);
            if (cut > 0) {
                name = name.substring(0, cut);
            }
        }
        String text = buffer.getContent();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\b" + java.util.regex.Pattern.quote(name) + "\\b")
                .matcher(text);
        if (!m.find()) {
            return;
        }
        int idx = m.start();
        int line = 1;
        int lineStart = 0;
        for (int i = 0; i < idx; i++) {
            if (text.charAt(i) == '\n') {
                line++;
                lineStart = i + 1;
            }
        }
        host.sessions().gotoInFile(file, line, idx - lineStart + 1, true);
    }
}
