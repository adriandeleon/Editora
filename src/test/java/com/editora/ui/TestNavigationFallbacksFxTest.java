package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javafx.scene.control.Tab;

import com.editora.build.BuildTool;
import com.editora.editor.EditorBuffer;
import com.editora.run.StackTraceLinks;
import com.editora.test.JavaTestScanner;
import com.editora.test.TestNode;
import com.editora.test.TestNodeKind;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where a console link and a Test Results row lead when there is no project and no language server to ask —
 * a plain window with a few files open — and what the status bar says when they lead nowhere, or when there
 * is no build tool to run a test with.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestNavigationFallbacksFxTest {

    private FxWindowFixture fx;
    private Path dir;
    private TestNavigationCoordinator nav;
    private StatusBar statusBar;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
        dir = Files.createTempDirectory(fx.configDir, "nav-fallbacks").toRealPath();
        nav = FxTestSupport.callOnFx(() -> FxTestSupport.field(fx.controller, "testNavigation"));
        statusBar = FxTestSupport.field(fx.controller, "statusBar");
    }

    @AfterAll
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    // --- harness --------------------------------------------------------------------------------------

    private Path write(String name, String content) throws Exception {
        Path f = dir.resolve(name);
        Files.createDirectories(f.getParent());
        return Files.writeString(f, content);
    }

    private String status() throws Exception {
        return FxTestSupport.callOnFx(() -> {
            StringBuilder all = new StringBuilder();
            collect(statusBar, all);
            return all.toString();
        });
    }

    private static void collect(javafx.scene.Node node, StringBuilder out) {
        if (node instanceof javafx.scene.control.Labeled labeled && labeled.getText() != null) {
            out.append(labeled.getText()).append('\n');
        }
        if (node instanceof javafx.scene.Parent parent) {
            parent.getChildrenUnmodifiable().forEach(child -> collect(child, out));
        }
    }

    private EditorBuffer bufferFor(Path file) {
        Tab tab = (Tab) FxTestSupport.call(fx.controller, "tabForPath", new Class[] {Path.class}, file);
        return tab == null
                ? null
                : (EditorBuffer) FxTestSupport.call(fx.controller, "bufferOf", new Class[] {Tab.class}, tab);
    }

    /** Opens {@code file} and returns its buffer once the text has arrived. */
    private EditorBuffer open(Path file) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FileWorkflowCoordinator workflows = FxTestSupport.field(fx.controller, "fileWorkflows");
            FxTestSupport.runOnFx(() -> workflows.openPath(file));
            return WindowMcpBridgeFxTest.awaitLoaded(async, fx.controller, file);
        }
    }

    /** Runs {@code action} and waits for the file it opens to have loaded; returns the 0-based caret line. */
    private int caretLineAfter(Runnable action, Path opened) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxTestSupport.runOnFx(action);
            CountDownLatch loaded = new CountDownLatch(1);
            AtomicReference<EditorBuffer> buffer = new AtomicReference<>();
            FxTestSupport.runOnFx(() -> {
                buffer.set(bufferFor(opened));
                if (buffer.get() == null) {
                    loaded.countDown();
                    return;
                }
                FileWorkflowCoordinator workflows = FxTestSupport.field(fx.controller, "fileWorkflows");
                workflows.afterBufferLoad(buffer.get(), loaded::countDown);
            });
            async.await(loaded, "the load of " + opened.getFileName());
            async.awaitFx();
            return buffer.get() == null
                    ? -1
                    : FxTestSupport.callOnFx(() -> buffer.get().getArea().getCurrentParagraph());
        }
    }

    // --- console links ----------------------------------------------------------------------------------

    @Test
    void anAbsolutePathInALinkOpensThatFileAtItsLine() throws Exception {
        Path script = write("tools/report.py", "import sys\n\ndef main():\n    raise ValueError()\n");

        int line = caretLineAfter(() -> nav.openRunLink(new StackTraceLinks.Link(script.toString(), 4)), script);

        assertEquals(3, line, "console lines are 1-based");
        assertEquals(script, FxTestSupport.callOnFx(() -> nav.resolveRunLinkFile(script.toString())));
    }

    @Test
    void aBareFileNameResolvesToTheOpenTabOfThatName() throws Exception {
        Path opened = write("deep/inside/a/tree/worker.py", "def work():\n    pass\n");
        open(opened);

        assertEquals(opened, FxTestSupport.callOnFx(() -> nav.resolveRunLinkFile("worker.py")));
        assertEquals(
                opened,
                FxTestSupport.callOnFx(() -> nav.resolveRunLinkFile("lib/worker.py")),
                "the name is what a relative path is matched by");
    }

    @Test
    void aLinkToAFileThatCannotBeFoundSaysSoOrHandsOver() throws Exception {
        String missing = dir.resolve("gone/Missing.py").toString();
        assertNull(FxTestSupport.callOnFx(() -> nav.resolveRunLinkFile(missing)), "absolute, and not there");
        assertNull(FxTestSupport.callOnFx(() -> nav.resolveRunLinkFile("nowhere-to-be-found.py")));
        assertNull(FxTestSupport.callOnFx(() -> nav.resolveRunLinkFile("bad\u0000token")), "not a path at all");

        FxTestSupport.runOnFx(() -> nav.openRunLink(new StackTraceLinks.Link("nowhere-to-be-found.py", 3)));
        assertTrue(status().contains(tr("status.run.linkNotFound", "nowhere-to-be-found.py")), status());

        AtomicInteger handedOver = new AtomicInteger();
        FxTestSupport.runOnFx(() -> {
            fx.controller.setStatus("cleared");
            nav.openRunLink(new StackTraceLinks.Link("nowhere-to-be-found.py", 3), handedOver::incrementAndGet);
        });
        assertEquals(1, handedOver.get(), "the caller's own fallback runs instead");
        assertTrue(status().contains("cleared"), "and nothing is reported on its behalf: " + status());
    }

    @Test
    void withNoProjectASourceLookupAnswersAtOnceWithNothing() throws Exception {
        AtomicReference<Object> answer = new AtomicReference<>("unanswered");

        FxTestSupport.runOnFx(() -> nav.locateSource("com/acme/Foo.java", true, answer::set));

        assertNull(answer.get(), "there is no tree to search, so nothing is started");
        assertNull(FxTestSupport.callOnFx(nav::lspStackTraceAnchor), "and no language server to ask instead");
    }

    // --- Test Results rows ------------------------------------------------------------------------------

    @Test
    void aResultWithNoSourceToGoToSaysSo() throws Exception {
        TestNode goTest = new TestNode(TestNodeKind.TEST, "ex/pkg#TestParse", "TestParse");
        goTest.setClassName("ex/pkg");
        goTest.setMethodName("TestParse");
        TestNode nameless = new TestNode(TestNodeKind.TEST, "tap#1", "adds two numbers");

        FxTestSupport.runOnFx(() -> nav.jumpToTestSource(goTest, BuildTool.GO));
        assertTrue(status().contains(tr("status.testrunner.noSource", "TestParse")), status());

        FxTestSupport.runOnFx(() -> nav.jumpToTestSource(nameless, BuildTool.MAVEN));
        assertTrue(status().contains(tr("status.testrunner.noSource", "adds two numbers")), status());
    }

    @Test
    void theJumpInsideAFileGoesToTheMethodAndLeavesTheCaretWhenThereIsNone() throws Exception {
        Path file = write(
                "parse_test.go",
                "package parse\n\nfunc helper() {}\n\nfunc TestParse(t *testing.T) {\n}\n\nfunc TestOther(t *testing.T) {\n}\n");
        EditorBuffer buffer = open(file);

        FxTestSupport.runOnFx(() -> nav.jumpToTestMethod(file, "TestParse/empty_input"));
        assertEquals(4, FxTestSupport.callOnFx(() -> buffer.getArea().getCurrentParagraph()), "a Go subtest's parent");

        FxTestSupport.runOnFx(() -> nav.jumpToTestMethod(file, "TestOther with a display name"));
        assertEquals(7, FxTestSupport.callOnFx(() -> buffer.getArea().getCurrentParagraph()));

        FxTestSupport.runOnFx(() -> {
            nav.jumpToTestMethod(file, "TestNotInThisFile");
            nav.jumpToTestMethod(file, " ");
            nav.jumpToTestMethod(file, null);
            nav.jumpToTestMethod(dir.resolve("not-open.go"), "TestParse");
        });
        assertEquals(7, FxTestSupport.callOnFx(() -> buffer.getArea().getCurrentParagraph()), "the caret stays put");
    }

    // --- running a test with no build tool --------------------------------------------------------------

    @Test
    void withNoBuildToolDetectedEveryWayOfRunningATestSaysSo() throws Exception {
        JavaTestScanner.TestTarget target = new JavaTestScanner.TestTarget(3, "demo.CalcTest", "adds", false);
        String none = tr("status.testrunner.noBuildTool");

        for (Runnable way : new Runnable[] {
            nav::runTestsForContext, () -> nav.runSingleTest(target), () -> nav.debugSingleTest(target)
        }) {
            FxTestSupport.runOnFx(() -> {
                fx.controller.setStatus("cleared");
                way.run();
            });
            assertTrue(status().contains(none), status());
        }
        assertTrue(FxTestSupport.callOnFx(() -> nav.buildCoordinatorFor(BuildTool.MAVEN))
                .isPresent());
        assertEquals(false, FxTestSupport.callOnFx(nav::jvmBuildDetected));
    }

    @Test
    void runTestAtCaretNeedsATestUnderTheCaret() throws Exception {
        open(write("notes.txt", "no tests here\n"));

        FxTestSupport.runOnFx(() -> nav.runTestAtCaret(false, false));
        assertTrue(status().contains(tr("status.testrunner.noTestAtCaret")), status());

        FxTestSupport.runOnFx(() -> {
            fx.controller.setStatus("cleared");
            nav.runTestAtCaret(true, true);
        });
        assertTrue(status().contains(tr("status.testrunner.noTestAtCaret")), status());
    }
}
