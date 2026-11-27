package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javafx.scene.control.CheckBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;

import com.editora.config.SharedConfig;
import com.editora.diff.DiffEngine;
import com.editora.diff.PatchParser;
import com.editora.github.PrViewParser.PrDetail;
import com.editora.github.PrViewed;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Viewed files: in a review tab (marking moves on to the next file not viewed, a Git review keeps its marks per
 * repository and drops one when the file changes) and in a pull request's tab (GitHub's state, the progress, the
 * boxes and the "changes since your last review" link).
 */
@Tag("fx")
class ReviewViewedFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static PatchReviewPane.Entry entry(String path, String left, String right) {
        var model = DiffEngine.compute(left, right, DiffEngine.DiffOptions.DEFAULT);
        DiffViewerPane pane =
                new DiffViewerPane(path, "L", "R", path, path, left, right, model, "Monospaced", 13, true, path);
        return new PatchReviewPane.Entry(path, "M", 1, 1, pane);
    }

    private static Label progress(PatchReviewPane review) {
        return FxTestSupport.field(review, "progress");
    }

    @Test
    void markingTheShownFileMovesOnToTheNextNotViewed() throws Exception {
        PatchReviewPane review = FxTestSupport.callOnFx(() -> new PatchReviewPane(
                "Review", List.of(entry("a", "1\n", "2\n"), entry("b", "1\n", "3\n"), entry("c", "1\n", "4\n"))));
        assertEquals(
                tr("diff.viewedProgress", 0, 3),
                FxTestSupport.callOnFx(() -> progress(review).getText()));
        FxTestSupport.runOnFx(() -> review.markViewed(1, true)); // not the shown one: stays on a
        assertEquals(
                0,
                FxTestSupport.<javafx.scene.control.ListView<?>>field(review, "files")
                        .getSelectionModel()
                        .getSelectedIndex());
        FxTestSupport.runOnFx(review::toggleViewedOfShown); // a, shown, viewed: b is viewed, so c
        assertEquals(
                2,
                FxTestSupport.<javafx.scene.control.ListView<?>>field(review, "files")
                        .getSelectionModel()
                        .getSelectedIndex());
        assertEquals(
                tr("diff.viewedProgress", 2, 3),
                FxTestSupport.callOnFx(() -> progress(review).getText()));
        FxTestSupport.runOnFx(() -> review.markViewed(0, false));
        assertFalse(review.isViewed(0));
    }

    @Test
    void aGitReviewRemembersItsMarksUntilTheFileChanges(@TempDir Path dir) throws Exception {
        SharedConfig shared = new SharedConfig(dir, false);
        String key = "/repo";
        String before = StoredViewed.digest("1\n", "2\n");
        StoredViewed first = new StoredViewed(shared, key, List.of("a", "b"), List.of(before, "x"));
        first.set(0, true);
        assertTrue(new StoredViewed(shared, key, List.of("a"), List.of(before)).viewed(0), "a reopened review");
        assertFalse(
                new StoredViewed(shared, key, List.of("a"), List.of(StoredViewed.digest("1\n", "2b\n"))).viewed(0),
                "the file changed since: not viewed");
        assertFalse(new StoredViewed(shared, "/other", List.of("a"), List.of(before)).viewed(0), "per repository");
        first.set(0, false);
        assertTrue(shared.reviewViewed(key).isEmpty());
    }

    private static List<PatchParser.FilePatch> patches() {
        return PatchParser.parse("diff --git a/a.txt b/a.txt\n--- a/a.txt\n+++ b/a.txt\n@@ -1 +1 @@\n-x\n+y\n"
                + "diff --git a/b.txt b/b.txt\n--- a/b.txt\n+++ b/b.txt\n@@ -1 +1 @@\n-x\n+z\n");
    }

    @Test
    void aPullRequestTabShowsGitHubsViewedStateAndTheSinceReviewLink() throws Exception {
        List<String> marks = new ArrayList<>();
        int[] since = {0};
        PrReviewPane pane =
                FxTestSupport.callOnFx(() -> new PrReviewPane(7, fp -> {}, () -> {}, u -> {}, () -> {}, () -> {}));
        PrDetail detail = new PrDetail(
                7, "T", "", "ada", "main", "fix", "OPEN", "https://github.com/o/r/pull/7", 2, 2, "def5678");
        FxTestSupport.runOnFx(() -> {
            pane.setViewedActions((path, now) -> marks.add(path + "=" + now), () -> since[0]++);
            pane.update(detail, patches());
        });
        assertTrue(boxes(pane).isEmpty(), "no boxes before GitHub's state is read");
        FxTestSupport.runOnFx(() -> pane.setViewed(
                new PrViewed.State("PR_1", java.util.Map.of("a.txt", "VIEWED", "b.txt", "UNVIEWED"), "abc1234")));
        List<CheckBox> boxes = boxes(pane);
        assertEquals(2, boxes.size());
        assertTrue(boxes.get(0).isSelected());
        assertFalse(boxes.get(1).isSelected());
        assertTrue(labels(pane).contains(tr("github.review.viewedProgress", 1, 2)));
        FxTestSupport.runOnFx(() -> boxes.get(1).fire());
        assertEquals(List.of("b.txt=true"), marks);
        Hyperlink link = FxTestSupport.callOnFx(() -> nodes(pane, "hyperlink").stream()
                .map(Hyperlink.class::cast)
                .filter(h -> tr("github.review.sinceReview").equals(h.getText()))
                .findFirst()
                .orElseThrow());
        FxTestSupport.runOnFx(link::fire);
        assertEquals(1, since[0]);

        FxTestSupport.runOnFx(() -> pane.setViewed(new PrViewed.State("PR_1", java.util.Map.of(), "def5678")));
        assertTrue(
                FxTestSupport.callOnFx(() -> nodes(pane, "hyperlink").stream()
                        .noneMatch(h -> tr("github.review.sinceReview").equals(((Hyperlink) h).getText()))),
                "reviewed at the head: nothing since");
    }

    private static List<CheckBox> boxes(PrReviewPane pane) throws Exception {
        return FxTestSupport.callOnFx(() -> nodes(pane, "pr-review-viewed").stream()
                .map(CheckBox.class::cast)
                .toList());
    }

    private static List<String> labels(PrReviewPane pane) throws Exception {
        return FxTestSupport.callOnFx(() -> nodes(pane, "label").stream()
                .filter(n -> n instanceof Label)
                .map(n -> ((Label) n).getText())
                .toList());
    }

    /** The nodes under {@code root} with {@code styleClass}: walked by hand, the scroll pane has no skin here. */
    private static List<javafx.scene.Node> nodes(javafx.scene.Node root, String styleClass) {
        List<javafx.scene.Node> out = new ArrayList<>();
        walk(root, styleClass, out);
        return out;
    }

    private static void walk(javafx.scene.Node node, String styleClass, List<javafx.scene.Node> out) {
        if (node.getStyleClass().contains(styleClass)) {
            out.add(node);
        }
        if (node instanceof javafx.scene.control.ScrollPane scroll) {
            if (scroll.getContent() != null) {
                walk(scroll.getContent(), styleClass, out);
            }
        } else if (node instanceof javafx.scene.Parent parent) {
            for (javafx.scene.Node child : parent.getChildrenUnmodifiable()) {
                walk(child, styleClass, out);
            }
        }
    }
}
