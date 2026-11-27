package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.TextArea;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import com.editora.diff.DiffEngine;
import com.editora.github.GhInvocation;
import com.editora.process.ProcessRunner;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PrInlineReview} with a real diff pane and overlay and a scripted {@code gh}: the threads read from GitHub and
 * marked on their side, a draft comment and the review summary, the card's reply and resolve, and the drafts cleared
 * once the review is sent.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PrInlineReviewFxTest {

    private static final String URL = "https://github.com/octo/app/pull/7";
    private static final String BASE = "a\nb\nc\nd\n";
    private static final String HEAD = "a\nB\nc\nd\ne\n";
    private static final String THREADS = """
            {"data":{"repository":{"pullRequest":{"reviewThreads":{"nodes":[
              {"id":"T_1","isResolved":false,"isOutdated":false,"path":"src/A.java","line":2,"startLine":null,
               "diffSide":"RIGHT","comments":{"nodes":[{"author":{"login":"ada"},"body":"Why B?","createdAt":"",
               "url":"https://github.com/octo/app/pull/7#r1"}]}}]}}}}}
            """;

    private Stage stage;
    private StackPane root;
    private OverlayHost overlay;
    private PrInlineReview inline;
    private final List<GhInvocation> calls = new ArrayList<>();
    private final Deque<String> answers = new ArrayDeque<>();
    private final List<String> statuses = new ArrayList<>();

    @BeforeAll
    void boot() throws Exception {
        FxTestSupport.bootToolkit();
        FxTestSupport.runOnFx(() -> {
            root = new StackPane();
            stage = new Stage();
            stage.setScene(new Scene(root, 1000, 700));
            stage.show();
            overlay = new OverlayHost();
            overlay.install(root);
        });
    }

    @AfterAll
    void close() throws Exception {
        FxTestSupport.runOnFx(stage::close);
    }

    @BeforeEach
    void setUp() throws Exception {
        calls.clear();
        answers.clear();
        statuses.clear();
        inline = FxTestSupport.callOnFx(() -> new PrInlineReview(new PrInlineReview.Host() {
            @Override
            public OverlayHost overlayHost() {
                return overlay;
            }

            @Override
            public void setStatus(String message) {
                statuses.add(message);
            }

            @Override
            public void gh(Path dir, GhInvocation call, Consumer<ProcessRunner.Result> done) {
                calls.add(call);
                String out = answers.isEmpty() ? "{\"data\":{}}" : answers.poll();
                done.accept(new ProcessRunner.Result(0, out, "", false, false));
            }

            @Override
            public void installKeys(javafx.scene.control.TextInputControl control) {}

            @Override
            public void openUrl(String url) {
                statuses.add("open " + url);
            }

            @Override
            public boolean confirm(Confirm.Question question) {
                return true;
            }
        }));
    }

    @AfterEach
    void tidy() throws Exception {
        FxTestSupport.runOnFx(() -> {
            if (overlay.isShowing()) {
                overlay.hide();
            }
            root.getChildren()
                    .removeIf(
                            n -> n != root.getChildren().get(root.getChildren().size() - 1));
        });
    }

    private DiffViewerPane pane() throws Exception {
        var model = DiffEngine.compute(BASE, HEAD, DiffEngine.DiffOptions.DEFAULT);
        DiffViewerPane pane = FxTestSupport.callOnFx(() -> new DiffViewerPane(
                "A.java", "Base", "Head", "A.java", "A.java", BASE, HEAD, model, "Monospaced", 13, true, "A.java"));
        FxTestSupport.runOnFx(() -> {
            root.getChildren().add(0, pane.node());
            root.applyCss();
            root.layout();
        });
        return pane;
    }

    private PrInlineReview.State state() throws Exception {
        return FxTestSupport.callOnFx(() -> {
            PrInlineReview.State s = inline.state(new GitHubCoordinator.ReviewKey(Path.of("/repo"), 7));
            inline.pinned(s, "head1", URL);
            return s;
        });
    }

    private Node card() {
        StackPane overlayRoot = FxTestSupport.field(overlay, "overlayRoot");
        return overlayRoot.getChildren().get(1);
    }

    private Button button(String label) {
        return (Button) card().lookupAll(".button").stream()
                .filter(n -> n instanceof Button b && label.equals(b.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no '" + label + "' button"));
    }

    @Test
    void threadsFromGitHubAreMarkedOnTheirSideAndDraftsJoinThem() throws Exception {
        DiffViewerPane pane = pane();
        PrInlineReview.State s = state();
        answers.add(THREADS);
        FxTestSupport.runOnFx(() -> {
            inline.attachDiff(s, pane, "src/A.java");
            inline.loadThreads(s);
        });
        assertTrue(calls.get(0).args().contains("graphql"));
        DiffViewerPane.LineHook hook = pane.lineHook();
        assertNotNull(hook);
        assertEquals(java.util.Set.of(2), FxTestSupport.callOnFx(() -> hook.marked(false)), "head side, line 2");
        assertEquals(java.util.Set.of(), FxTestSupport.callOnFx(() -> hook.marked(true)));

        FxTestSupport.runOnFx(() -> hook.comment(true, 3, 4));
        FxTestSupport.runOnFx(() -> {
            ((TextArea) card().lookup(".text-area")).setText("Why keep c?");
            button(tr("github.review.saveDraft")).fire();
        });
        assertEquals(1, s.drafts.size());
        assertEquals("LEFT", s.drafts.get(0).side());
        assertEquals(3, s.drafts.get(0).startLine());
        assertEquals(java.util.Set.of(4), FxTestSupport.callOnFx(() -> hook.marked(true)), "the draft's last line");
        assertEquals(tr("github.review.summary", 1, 1, 0), PrInlineReview.summary(s));
    }

    @Test
    void theCardRepliesAndResolvesOnGitHubAndReadsTheThreadsAgain() throws Exception {
        DiffViewerPane pane = pane();
        PrInlineReview.State s = state();
        answers.add(THREADS);
        FxTestSupport.runOnFx(() -> {
            inline.attachDiff(s, pane, "src/A.java");
            inline.loadThreads(s);
        });
        FxTestSupport.runOnFx(() -> pane.lineHook().open(false, 2));
        assertTrue(FxTestSupport.callOnFx(overlay::isShowing), "the card opened at line 2");
        assertTrue(FxTestSupport.callOnFx(() -> card().lookupAll(".label").stream()
                .anyMatch(n -> n instanceof javafx.scene.control.Label l && "Why B?".equals(l.getText()))));
        answers.add("{\"data\":{}}");
        answers.add(THREADS);
        FxTestSupport.runOnFx(() -> {
            ((TextArea) card().lookup(".text-area")).setText("Because the spec says so.");
            button(tr("review.card.reply")).fire();
        });
        GhInvocation reply = calls.get(1);
        assertEquals("Because the spec says so.", reply.stdin());
        assertTrue(reply.args().contains("id=T_1"));
        assertTrue(statuses.contains(tr("status.github.replied")));
        assertTrue(calls.get(2).args().contains("graphql"), "the threads are read again");

        FxTestSupport.runOnFx(() -> pane.lineHook().open(false, 2));
        answers.add("{\"errors\":[{\"message\":\"no\"}]}");
        FxTestSupport.runOnFx(() -> button(tr("review.card.resolve")).fire());
        assertTrue(String.join(" ", calls.get(3).args()).contains("resolveReviewThread"));
        assertTrue(statuses.contains(tr("status.github.resolveFailed")), "an error in the answer is a failure");
    }

    @Test
    void sendingTheReviewClearsTheDraftsAndReadsTheirThreads() throws Exception {
        DiffViewerPane pane = pane();
        PrInlineReview.State s = state();
        FxTestSupport.runOnFx(() -> {
            inline.attachDiff(s, pane, "src/A.java");
            s.drafts.add(com.editora.github.PrLineDraft.create("src/A.java", false, 2, 2, "Nit."));
        });
        assertEquals(1, FxTestSupport.callOnFx(() -> inline.drafts(s)).size());
        FxTestSupport.runOnFx(() -> inline.submitted(s));
        assertTrue(s.drafts.isEmpty());
        assertTrue(calls.get(calls.size() - 1).args().contains("graphql"), "read again: they are threads now");
        assertFalse(PrInlineReview.summary(s).contains("1"), PrInlineReview.summary(s));
    }

    @Test
    void aReviewTabWithDraftsAsksBeforeItCloses() throws Exception {
        PrReviewPane tab =
                FxTestSupport.callOnFx(() -> new PrReviewPane(7, fp -> {}, () -> {}, url -> {}, () -> {}, () -> {}));
        PrInlineReview.State s = state();
        FxTestSupport.runOnFx(() -> inline.attachTab(s, tab));
        assertFalse(tab.hasUnsavedChanges(), "no drafts: closes at once");
        FxTestSupport.runOnFx(() -> s.drafts.add(com.editora.github.PrLineDraft.create("a", false, 1, 1, "x")));
        assertTrue(tab.hasUnsavedChanges());
        assertFalse(tab.saveBeforeClose(), "Keep Drafts keeps the tab open");
        assertEquals("Keep Drafts", tr(tab.closeSaveActionKey()));
    }
}
