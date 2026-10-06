package com.editora.ui;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javafx.application.Platform;
import javafx.scene.control.Tab;

import com.editora.build.BuildTool;
import com.editora.config.Project;
import com.editora.editor.EditorBuffer;
import com.editora.run.StackTraceLinks;
import com.editora.test.TestNode;
import com.editora.test.TestNodeKind;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stack-frame links and Test Results activation with <b>no Java language server</b> — the default — against
 * a real window whose project keeps its sources where every Maven project does: under {@code src/}, never in
 * the root.
 *
 * <p>Before this, a frame's bare file name was looked up as {@code <root>/Bar.java}; a failed test stopped at
 * "file not found" instead of falling back to the name-based lookup; a passing test whose file was closed
 * opened at line 1 because the jump ran against the still-empty tab shell; and the name lookup walked the
 * project on the FX thread.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestNavigationCoordinatorFxTest {

    private FxWindowFixture fx;
    private Path root;
    private Path fooSource;
    private Path fooTest;
    private TestNavigationCoordinator nav;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
        root = Files.createTempDirectory("editora-testnav").toRealPath();
        fooSource = write(
                "services/billing/src/main/java/com/acme/Foo.java",
                "package com.acme;\n\nclass Foo {\n    int baz() {\n        throw new IllegalStateException();\n    }\n}\n");
        fooTest = write(
                "services/billing/src/test/java/com/acme/FooTest.java",
                "package com.acme;\n\nclass FooTest {\n\n    void first() {}\n\n    void second() {}\n\n"
                        + "    void works() {\n        new Foo().baz();\n    }\n}\n");
        // A decoy with the same file name in build output: found first by a name-only walk of the tree.
        write("services/billing/target/classes/com/acme/Foo.java", "// copied resource\n");
        FxTestSupport.runOnFx(() -> {
            setWindowProject(new Project("nav-test", "nav-test", root.toString()));
            nav = FxTestSupport.field(fx.controller, "testNavigation");
        });
    }

    @AfterAll
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    private Path write(String rel, String content) throws Exception {
        Path f = root.resolve(rel);
        Files.createDirectories(f.getParent());
        return Files.writeString(f, content);
    }

    private void setWindowProject(Project project) {
        try {
            Field f = MainController.class.getDeclaredField("windowProject");
            f.setAccessible(true);
            f.set(fx.controller, project);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static TestNode test(String className, String method, String stackTrace) {
        TestNode node = new TestNode(TestNodeKind.TEST, className + "#" + method, method);
        node.setClassName(className);
        node.setMethodName(method);
        node.setStackTrace(stackTrace);
        node.setSourceFileHint(className.substring(className.lastIndexOf('.') + 1) + ".java");
        return node;
    }

    private EditorBuffer bufferFor(Path file) {
        Tab tab = (Tab) FxTestSupport.call(fx.controller, "tabForPath", new Class[] {Path.class}, file);
        return tab == null
                ? null
                : (EditorBuffer) FxTestSupport.call(fx.controller, "bufferOf", new Class[] {Tab.class}, tab);
    }

    private void closeAllTabs() throws Exception {
        com.editora.command.CommandRegistry registry =
                FxTestSupport.callOnFx(() -> FxTestSupport.field(fx.controller, "registry"));
        FxTestSupport.runOnFx(() -> registry.run("buffer.closeAll"));
        FxTestSupport.runOnFx(() -> {});
    }

    /**
     * Runs {@code action} on the FX thread and waits until the navigation it starts has settled: the
     * off-thread lookup(s) reported back, and — when a file was opened — its asynchronous load landed along
     * with everything queued behind it. Returns the 0-based caret line in {@code expected}, or -1 if it never
     * opened.
     */
    private int navigate(Runnable action, Path expected) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            CountDownLatch lookups = new CountDownLatch(1);
            java.util.concurrent.atomic.AtomicInteger inFlight = new java.util.concurrent.atomic.AtomicInteger();
            AtomicBoolean lookedUpOnFx = new AtomicBoolean();
            FxTestSupport.runOnFx(() -> {
                var real = nav.lookupRunner;
                async.onClose(() -> FxTestSupport.runOnFx(() -> nav.lookupRunner = real));
                nav.lookupRunner = task -> {
                    inFlight.incrementAndGet();
                    real.accept(() -> {
                        lookedUpOnFx.compareAndSet(false, Platform.isFxApplicationThread());
                        task.run(); // posts its result to the FX thread before returning
                        // The result callback may start a follow-up lookup (the by-name fallback); let it
                        // register before deciding that nothing is in flight.
                        Platform.runLater(() -> {
                            if (inFlight.decrementAndGet() == 0) {
                                lookups.countDown();
                            }
                        });
                    });
                };
                action.run();
                if (inFlight.get() == 0) {
                    lookups.countDown(); // resolved without touching the disk (an open tab)
                }
            });
            async.await(lookups, "source lookups");
            assertFalse(lookedUpOnFx.get(), "the project is never walked on the FX thread");

            CountDownLatch loaded = new CountDownLatch(1);
            AtomicReference<EditorBuffer> opened = new AtomicReference<>();
            FxTestSupport.runOnFx(() -> {
                EditorBuffer buffer = bufferFor(expected);
                opened.set(buffer);
                if (buffer == null) {
                    loaded.countDown();
                    return;
                }
                FileWorkflowCoordinator workflows = FxTestSupport.field(fx.controller, "fileWorkflows");
                workflows.afterBufferLoad(buffer, loaded::countDown); // behind whatever the jump queued
            });
            async.await(loaded, "buffer load");
            async.awaitFx();
            return opened.get() == null
                    ? -1
                    : FxTestSupport.callOnFx(() -> opened.get().getArea().getCurrentParagraph());
        }
    }

    // --- (a) a frame resolves through its qualified class -------------------------------------------

    @Test
    void aStackFrameOpensTheClassUnderTheSourceRootsNotAtTheProjectRoot() throws Exception {
        closeAllTabs();
        StackTraceLinks.Link frame = StackTraceLinks.parse("\tat com.acme.Foo.baz(Foo.java:5)");

        int line = navigate(() -> nav.openRunLink(frame), fooSource);

        assertEquals(4, line, "Foo.java:5 — the real source, not <root>/Foo.java nor the copy in target/");
        assertNull(FxTestSupport.callOnFx(
                () -> bufferFor(root.resolve("services/billing/target/classes/com/acme/Foo.java"))));
    }

    @Test
    void aFailedTestJumpsToItsFrame() throws Exception {
        closeAllTabs();
        TestNode failed = test(
                "com.acme.FooTest",
                "works",
                "java.lang.IllegalStateException\n\tat com.acme.Foo.baz(Foo.java:5)\n"
                        + "\tat com.acme.FooTest.works(FooTest.java:10)\n");

        int line = navigate(() -> nav.jumpToTestSource(failed, BuildTool.MAVEN), fooTest);

        assertEquals(9, line, "the frame in the test's own file wins: FooTest.java:10");
    }

    // --- (b) a failed test falls back to the name-based lookup ----------------------------------------

    @Test
    void aFailedTestWhoseFrameDoesNotResolveFallsBackToTheTestSource() throws Exception {
        closeAllTabs();
        TestNode failed =
                test("com.acme.FooTest", "second", "org.lib.Boom\n\tat org.lib.Other.explode(Other.java:77)\n");

        int line = navigate(() -> nav.jumpToTestSource(failed, BuildTool.MAVEN), fooTest);

        assertEquals(6, line, "no source for org.lib.Other here, so the test's own method is where it lands");
    }

    // --- (c) the jump waits for the asynchronous load --------------------------------------------------

    @Test
    void aPassingTestWhoseFileIsClosedJumpsToItsMethodOnceTheFileHasLoaded() throws Exception {
        closeAllTabs();
        assertNull(FxTestSupport.callOnFx(() -> bufferFor(fooTest)), "the file starts closed");
        TestNode passed = test("com.acme.FooTest", "works", null);

        int line = navigate(() -> nav.jumpToTestSource(passed, BuildTool.MAVEN), fooTest);

        assertEquals(8, line, "the caret is on `void works()`, not left on line 1 of an empty shell");
    }

    @Test
    void aParameterizedInvocationJumpsToItsMethod() throws Exception {
        closeAllTabs();
        TestNode passed = test("com.acme.FooTest", "second(int)[2]", null);

        assertEquals(6, navigate(() -> nav.jumpToTestSource(passed, BuildTool.MAVEN), fooTest));
    }

    // --- (d) and the things that must not regress -------------------------------------------------------

    @Test
    void anAlreadyOpenFileIsUsedWithoutAnyLookup() throws Exception {
        closeAllTabs();
        assertEquals(
                8,
                navigate(
                        () -> nav.jumpToTestSource(test("com.acme.FooTest", "works", null), BuildTool.MAVEN), fooTest));

        AtomicBoolean lookedUp = new AtomicBoolean();
        FxTestSupport.runOnFx(() -> {
            var real = nav.lookupRunner;
            nav.lookupRunner = task -> lookedUp.set(true);
            try {
                nav.jumpToTestSource(test("com.acme.FooTest", "first", null), BuildTool.MAVEN);
            } finally {
                nav.lookupRunner = real;
            }
        });

        assertFalse(lookedUp.get(), "an open tab of that class answers at once");
        assertEquals(
                4, FxTestSupport.callOnFx(() -> bufferFor(fooTest).getArea().getCurrentParagraph()));
    }

    @Test
    void anUnknownClassSaysSoInsteadOfOpeningSomethingElse() throws Exception {
        closeAllTabs();
        TestNode ghost = test("com.acme.GhostTest", "nothing", null);
        Path wouldBe = root.resolve("services/billing/src/test/java/com/acme/GhostTest.java");

        assertEquals(-1, navigate(() -> nav.jumpToTestSource(ghost, BuildTool.MAVEN), wouldBe));
    }

    @Test
    void theDebugAttachAnchorIsTheTestClassSourceElseTheActiveFile() throws Exception {
        closeAllTabs();
        try (AsyncTestScope async = new AsyncTestScope()) {
            CountDownLatch done = new CountDownLatch(2);
            AtomicReference<Path> found = new AtomicReference<>();
            AtomicReference<Path> fallback = new AtomicReference<>(Path.of("unset"));
            FxTestSupport.runOnFx(() -> {
                nav.resolveTestClassAnchor("com.acme.FooTest", p -> {
                    found.set(p);
                    done.countDown();
                });
                nav.resolveTestClassAnchor("com.acme.GhostTest", p -> {
                    fallback.set(p);
                    done.countDown();
                });
            });
            async.await(done, "both anchors — neither supersedes the other");

            assertEquals(fooTest, found.get());
            Path active = FxTestSupport.callOnFx(() -> {
                EditorBuffer b = (EditorBuffer) FxTestSupport.call(fx.controller, "activeBuffer", new Class[] {});
                return b == null ? null : b.getPath();
            });
            assertEquals(active, fallback.get(), "nothing to anchor to: the active buffer's file, as before");
        }
    }

    @Test
    void pathSuffixMatchingIsSeparatorSafe() {
        assertTrue(TestNavigationCoordinator.endsWithPath(
                Path.of("proj", "src", "com", "acme", "Foo.java"), "com/acme/Foo.java"));
        assertFalse(TestNavigationCoordinator.endsWithPath(
                Path.of("proj", "xcom", "acme", "Foo.java"), "com/acme/Foo.java"));
        assertNotNull(nav);
    }
}
