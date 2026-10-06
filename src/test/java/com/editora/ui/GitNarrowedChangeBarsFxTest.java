package com.editora.ui;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;

import com.editora.editor.EditorBuffer;
import com.editora.git.GitService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Git change bars are keyed by whole-file line numbers; a narrowed buffer shows region-relative lines. Painting
 * one over the other puts the bars on the wrong lines, so a narrowed buffer gets none until it is widened.
 * Driven through a real repository and the real refresh, the way narrowing triggers it.
 */
@Tag("fx")
class GitNarrowedChangeBarsFxTest {

    private static final String COMMITTED = "one\ntwo\nthree\nfour\nfive\nsix\n";
    private static final String WORKING = "one\nTWO\nthree\nfour\nfive\nsix\n"; // file line 2 modified

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void aNarrowedBufferIsNotGivenWholeFileChangeBars(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            GitTestRepo repo = GitTestRepo.init(dir);
            repo.write("doc.txt", COMMITTED);
            repo.commitAll("first");
            Path file = repo.write("doc.txt", WORKING);
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            fx.shared.getSettings().setGitSupport(true);
            EditorArea area = FxTestSupport.field(fx.controller, "editorArea");
            FileWorkflowCoordinator workflows = FxTestSupport.field(fx.controller, "fileWorkflows");
            Object git = FxTestSupport.field(fx.controller, "git");
            GitService service = (GitService) FxTestSupport.call(git, "service", new Class<?>[] {});
            ExecutorService gitLane = FxTestSupport.field(service, "exec");

            CountDownLatch loaded = new CountDownLatch(1);
            EditorBuffer b = FxTestSupport.callOnFx(() -> {
                workflows.openPath(file);
                EditorBuffer opening = (EditorBuffer) area.selectedTab().getUserData();
                workflows.afterBufferLoad(opening, loaded::countDown);
                return opening;
            });
            async.await(loaded, "document load");
            FxTestSupport.runOnFx(() -> FxTestSupport.invoke(git, "refresh"));
            settle(async, gitLane);
            assertEquals(java.util.Set.of(1), bars(b).keySet(), "precondition: the widened buffer marks file line 2");

            FxTestSupport.runOnFx(() -> b.narrowTo(WORKING.indexOf("four"), WORKING.indexOf("six")));
            settle(async, gitLane);
            assertEquals(
                    "four\nfive\n", FxTestSupport.callOnFx(() -> b.getArea().getText()));
            assertNull(bars(b), "file line 2 must not be painted on the region's second line (file line 5)");

            FxTestSupport.runOnFx(b::widen);
            settle(async, gitLane);
            assertEquals(java.util.Set.of(1), bars(b).keySet(), "widening brings the bars back");
        }
    }

    private static Map<Integer, String> bars(EditorBuffer buffer) {
        return FxTestSupport.field(buffer, "changeBars");
    }

    /** Lets the refresh in flight finish and its result reach the FX thread. */
    private static void settle(AsyncTestScope async, ExecutorService gitLane) throws Exception {
        async.awaitFx();
        async.awaitWorker(gitLane);
        async.awaitFx();
    }
}
