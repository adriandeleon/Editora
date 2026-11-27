package com.editora.ui;

import java.util.ArrayList;
import java.util.List;

import javafx.scene.control.Tab;

import com.editora.diff.PatchWriter;
import com.editora.review.ReviewFileGroups;

import static com.editora.i18n.Messages.tr;

/**
 * {@code diff.reviewWalkthrough}: asks the AI Agent for a walkthrough of the review tab in front (what it does, the
 * risky spots, an order to read it in), with each file's unified diff in review order. The diffs are written off the
 * FX thread and bounded to {@link #MAX_CHARS}; files past it are counted, not sent.
 */
final class ReviewWalkthrough {

    /** The most diff text one walkthrough sends. */
    static final int MAX_CHARS = 80_000;

    private ReviewWalkthrough() {}

    static void ask(MainController mc) {
        Tab tab = mc.editorArea.selectedTab();
        if (tab == null || !(tab.getUserData() instanceof PatchReviewPane review)) {
            mc.setStatus(tr("status.review.walkthroughNoReview"));
            return;
        }
        if (!mc.agentCoordinator.isEnabled()) {
            mc.setStatus(tr("status.review.agentOff"));
            return;
        }
        List<DiffViewerPane.PatchRequest> requests =
                review.panes().stream().map(DiffViewerPane::patchRequest).toList(); // the texts, taken on the FX thread
        com.editora.perf.OffFx.then("review-walkthrough", () -> prompt(requests), built -> {
            String prompt = built.value();
            if (prompt == null) {
                return;
            }
            mc.toolWindows.open(mc.agentToolWindow, true);
            mc.agentCoordinator.sendPrompt(prompt);
            mc.setStatus(tr("status.review.walkthroughAsked"));
        });
    }

    /** The walkthrough prompt for {@code requests}, their diffs bounded to {@link #MAX_CHARS}. Pure. */
    static String prompt(List<DiffViewerPane.PatchRequest> requests) {
        List<String> patches = new ArrayList<>();
        int used = 0;
        int omitted = 0;
        for (DiffViewerPane.PatchRequest r : requests) {
            String patch = PatchWriter.unifiedDiff(r.leftLabel(), r.rightLabel(), r.leftText(), r.rightText());
            if (used + patch.length() > MAX_CHARS) {
                omitted++;
                continue;
            }
            used += patch.length();
            patches.add(patch);
        }
        return ReviewFileGroups.walkthroughPrompt(patches, omitted);
    }
}
