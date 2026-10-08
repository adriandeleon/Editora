package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.Labeled;
import javafx.scene.text.Text;

import com.editora.diff.PatchParser;
import com.editora.diff.PatchParser.FilePatch;
import com.editora.github.PrViewParser.PrDetail;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pull-request review tab: what its header, description and file list show, and what its links do. */
@Tag("fx")
class PrReviewPaneFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static final class Clicks {
        final List<FilePatch> opened = new ArrayList<>();
        final List<String> urls = new ArrayList<>();
        final AtomicInteger openAll = new AtomicInteger();
        final AtomicInteger refresh = new AtomicInteger();
        final AtomicInteger submit = new AtomicInteger();
    }

    private static PrReviewPane pane(Clicks clicks) throws Exception {
        return FxTestSupport.callOnFx(() -> new PrReviewPane(
                42,
                clicks.opened::add,
                clicks.openAll::incrementAndGet,
                clicks.urls::add,
                clicks.refresh::incrementAndGet,
                clicks.submit::incrementAndGet));
    }

    private static List<FilePatch> patches(int count) {
        StringBuilder diff = new StringBuilder();
        for (int i = 0; i < count; i++) {
            String name = "src/file" + i + ".txt";
            diff.append("diff --git a/")
                    .append(name)
                    .append(" b/")
                    .append(name)
                    .append("\n--- a/")
                    .append(name)
                    .append("\n+++ b/")
                    .append(name)
                    .append("\n@@ -1,2 +1,3 @@\n keep\n-old\n+new\n+extra\n");
        }
        return PatchParser.parse(diff.toString());
    }

    private static PrDetail detail(String body) {
        return new PrDetail(
                42, "Fix the thing", body, "alice", "main", "fix", "OPEN", "https://example.invalid/pr/42", 7, 3);
    }

    @Test
    void theHeaderShowsWhatGhReportedAndEveryLinkCallsBack() throws Exception {
        Clicks clicks = new Clicks();
        PrReviewPane pane = pane(clicks);
        List<FilePatch> files = patches(2);
        FxTestSupport.runOnFx(() -> pane.update(detail("A short description."), files, "  from the file list  "));

        assertEquals(List.of("Fix the thing"), texts(pane, ".pr-review-title"));
        assertEquals(List.of("#42"), texts(pane, ".pr-review-number"));
        assertEquals(List.of("alice"), texts(pane, ".pr-review-meta"));
        assertEquals(List.of("fix → main"), texts(pane, ".pr-review-branch"));
        assertEquals(List.of("OPEN"), texts(pane, ".pr-review-state"));
        assertEquals(List.of(tr("github.review.filesChanged", 2)), texts(pane, ".pr-review-stats"));
        assertEquals("+7", texts(pane, ".pr-review-adds").get(0), "the totals gh reported, not a recount");
        assertEquals("−3", texts(pane, ".pr-review-dels").get(0));
        assertEquals("from the file list", FxTestSupport.callOnFx(pane::notice));
        assertEquals(List.of("from the file list"), texts(pane, ".pr-review-notice"));
        assertTrue(allText(pane, ".pr-review-description").contains("A short description."));
        assertEquals(
                List.of(
                        tr("github.review.openOnGitHub"),
                        tr("github.review.openAll", 2),
                        tr("github.review.submit"),
                        tr("github.review.refresh")),
                texts(pane, ".pr-review-action"));
        assertEquals(List.of("src/file0.txt", "src/file1.txt"), texts(pane, ".pr-review-file-link"));

        FxTestSupport.runOnFx(() -> {
            for (Node link : nodes(pane, ".pr-review-action")) {
                ((Hyperlink) link).fire();
            }
            ((Hyperlink) nodes(pane, ".pr-review-file-link").stream()
                            .filter(link -> "src/file1.txt".equals(((Hyperlink) link).getText()))
                            .findFirst()
                            .orElseThrow())
                    .fire();
        });
        assertEquals(List.of("https://example.invalid/pr/42"), clicks.urls);
        assertEquals(1, clicks.openAll.get());
        assertEquals(1, clicks.submit.get());
        assertEquals(1, clicks.refresh.get());
        assertEquals(1, clicks.opened.size());
        assertSame(files.get(1), clicks.opened.get(0), "the row's own patch is what opens");
        assertSame(files, FxTestSupport.callOnFx(pane::files));
    }

    /** When {@code gh pr view} failed there is still a usable tab: a number-only title and recounted totals. */
    @Test
    void withoutPullRequestDetailsTheTabFallsBackToTheNumberAndItsOwnCount() throws Exception {
        Clicks clicks = new Clicks();
        PrReviewPane pane = pane(clicks);
        FxTestSupport.runOnFx(() -> pane.update(null, patches(1)));

        assertEquals(List.of(tr("github.review.title", 42)), texts(pane, ".pr-review-title"));
        assertEquals(List.of(), texts(pane, ".pr-review-meta"));
        assertEquals(List.of(), texts(pane, ".pr-review-state"));
        assertEquals("+2", texts(pane, ".pr-review-adds").get(0), "counted from the patch itself");
        assertEquals("−1", texts(pane, ".pr-review-dels").get(0));
        assertEquals(
                List.of(tr("github.review.submit"), tr("github.review.refresh")),
                texts(pane, ".pr-review-action"),
                "no URL to open, and one file is not worth an Open All");
        assertEquals("", FxTestSupport.callOnFx(pane::notice));
        assertTrue(FxTestSupport.callOnFx(
                () -> nodes(pane, ".pr-review-description").isEmpty()));
    }

    @Test
    void blankHeaderFieldsAreLeftOutRatherThanShownEmpty() throws Exception {
        PrReviewPane pane = pane(new Clicks());
        PrDetail bare = new PrDetail(42, " ", "   ", "", "", "", "", " ", 0, 0);
        FxTestSupport.runOnFx(() -> pane.update(bare, patches(1), null));

        assertEquals(List.of(tr("github.review.title", 42)), texts(pane, ".pr-review-title"));
        assertEquals(List.of(), texts(pane, ".pr-review-meta"));
        assertEquals(List.of(), texts(pane, ".pr-review-branch"));
        assertEquals(List.of(), texts(pane, ".pr-review-state"));
        assertFalse(texts(pane, ".pr-review-action").contains(tr("github.review.openOnGitHub")));
        assertTrue(
                FxTestSupport.callOnFx(
                        () -> nodes(pane, ".pr-review-description").isEmpty()),
                "a blank body is no description");

        // A null body, and only one of the two branch names, are handled the same way.
        PrDetail headOnly = new PrDetail(42, "T", null, "", "", "topic", "", "", 0, 0);
        FxTestSupport.runOnFx(() -> pane.update(headOnly, patches(1), ""));
        assertEquals(List.of("topic → "), texts(pane, ".pr-review-branch"));
        assertTrue(FxTestSupport.callOnFx(
                () -> nodes(pane, ".pr-review-description").isEmpty()));
    }

    @Test
    void aPullRequestWithoutTextChangesSaysSoInsteadOfShowingAnEmptyList() throws Exception {
        PrReviewPane pane = pane(new Clicks());
        FxTestSupport.runOnFx(() -> pane.update(detail(""), null, ""));

        assertEquals(List.of(tr("github.review.noFiles")), texts(pane, ".welcome-caption"));
        assertEquals(List.of(tr("github.review.filesChanged", 0)), texts(pane, ".pr-review-stats"));
        assertEquals(List.of(), texts(pane, ".pr-review-file-link"));
        assertEquals(List.of(), FxTestSupport.callOnFx(pane::files));
    }

    @Test
    void aLongDescriptionIsCollapsedUntilAskedForAndStaysExpandedAcrossARefresh() throws Exception {
        PrReviewPane pane = pane(new Clicks());
        StringBuilder body = new StringBuilder();
        for (int i = 1; i <= 12; i++) {
            body.append("Paragraph number ").append(i).append(".\n\n");
        }
        FxTestSupport.runOnFx(() -> pane.update(detail(body.toString()), patches(1), ""));

        Hyperlink toggle = FxTestSupport.callOnFx(() -> (Hyperlink) nodes(pane, ".pr-review-action").stream()
                .filter(link -> tr("github.review.showMore").equals(((Hyperlink) link).getText()))
                .findFirst()
                .orElseThrow());
        String collapsed = allText(pane, ".pr-review-description");
        assertTrue(collapsed.contains("Paragraph number 1."), collapsed);
        assertFalse(collapsed.contains("Paragraph number 12."), "the tail is hidden");

        FxTestSupport.runOnFx(toggle::fire);
        assertEquals(tr("github.review.showLess"), FxTestSupport.callOnFx(toggle::getText));
        assertTrue(allText(pane, ".pr-review-description").contains("Paragraph number 12."));

        // Refresh rebuilds the tab; the reader's choice is kept.
        FxTestSupport.runOnFx(() -> pane.update(detail(body.toString()), patches(1), ""));
        assertTrue(allText(pane, ".pr-review-description").contains("Paragraph number 12."));
        assertTrue(texts(pane, ".pr-review-action").contains(tr("github.review.showLess")));

        Hyperlink again = FxTestSupport.callOnFx(() -> (Hyperlink) nodes(pane, ".pr-review-action").stream()
                .filter(link -> tr("github.review.showLess").equals(((Hyperlink) link).getText()))
                .findFirst()
                .orElseThrow());
        FxTestSupport.runOnFx(again::fire);
        assertFalse(allText(pane, ".pr-review-description").contains("Paragraph number 12."));
    }

    @Test
    void aHugePullRequestListsItsFirstFilesAndCountsTheRest() throws Exception {
        PrReviewPane pane = pane(new Clicks());
        FxTestSupport.runOnFx(() -> pane.update(null, patches(503)));

        assertEquals(
                500,
                FxTestSupport.callOnFx(() -> nodes(pane, ".pr-review-file-row").size()));
        assertEquals(List.of(tr("github.review.truncated", 3)), texts(pane, ".welcome-caption"));
        assertEquals(List.of(tr("github.review.filesChanged", 503)), texts(pane, ".pr-review-stats"));
    }

    // --- plumbing ----------------------------------------------------------------------------------

    /**
     * The nodes under {@code root} carrying style class {@code selector} (given as {@code .name}), in document
     * order. Walks the tree itself: the pane is not in a window, so its scroll pane has no skin to look through.
     */
    private static List<Node> nodes(Node root, String selector) {
        List<Node> out = new ArrayList<>();
        find(root, selector.substring(1), out);
        return out;
    }

    private static void find(Node node, String styleClass, List<Node> out) {
        if (node.getStyleClass().contains(styleClass)) {
            out.add(node);
        }
        if (node instanceof javafx.scene.control.ScrollPane scroll) {
            if (scroll.getContent() != null) {
                find(scroll.getContent(), styleClass, out);
            }
        } else if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                find(child, styleClass, out);
            }
        }
    }

    /** The texts of the labelled nodes matching {@code selector}, in document order. */
    private static List<String> texts(PrReviewPane pane, String selector) throws Exception {
        return FxTestSupport.callOnFx(() -> nodes(pane, selector).stream()
                .filter(Labeled.class::isInstance)
                .map(node -> ((Labeled) node).getText())
                .toList());
    }

    /** Every piece of text rendered under the nodes matching {@code selector}. */
    private static String allText(PrReviewPane pane, String selector) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            StringBuilder out = new StringBuilder();
            for (Node root : nodes(pane, selector)) {
                gather(root, out);
            }
            return out.toString();
        });
    }

    private static void gather(Node node, StringBuilder out) {
        if (node instanceof Text text) {
            out.append(text.getText()).append('\n');
        } else if (node instanceof Label label) {
            out.append(label.getText()).append('\n');
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                gather(child, out);
            }
        }
    }
}
