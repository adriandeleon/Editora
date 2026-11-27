package com.editora.ui;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Predicate;

import javafx.application.Platform;
import javafx.scene.control.Tab;

import com.editora.config.Settings;
import com.editora.diff.DiffModels.DiffModel;
import com.editora.editor.EditorBuffer;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * Builds a {@link ProposalPane} for a window and ties its tab to the decision: the tab closes once the user (or the
 * time limit) decided, and a tab closed undecided rejects the edit. FX thread.
 */
final class ProposalTabs {

    private ProposalTabs() {}

    /** The proposal tab for {@code buffer}: its text {@code before} and {@code after} the edit, read-only. */
    static ProposalPane build(
            EditorBuffer buffer,
            String before,
            String after,
            DiffModel model,
            @Nullable String why,
            String agent,
            CompletableFuture<ProposalPane.Decision> decided,
            Settings settings) {
        String name = buffer.getTitle();
        String path = buffer.getPath() == null ? null : buffer.getPath().toString();
        DiffViewerPane diff = new DiffViewerPane(
                tr("mcp.proposal.title", name),
                tr("mcp.proposal.before"),
                tr("mcp.proposal.after", agent),
                name,
                name,
                before,
                after,
                model,
                settings.getFontFamily(),
                settings.getFontSize(),
                settings.isShowLineNumbers(),
                path);
        diff.setMutationAllowed(false); // the proposal is a picture of the edit: Accept applies it, not the diff
        return new ProposalPane(name, agent, why, diff, decided);
    }

    /**
     * Closes {@code tab} once {@code pane} is decided, and decides it (rejected, no reason) when the tab leaves the
     * window first. {@code inWindow} says whether the window still has the tab; a tab dragged to another split has.
     */
    static void closeWhenDecided(Tab tab, ProposalPane pane, Predicate<Tab> inWindow, Consumer<Tab> close) {
        pane.decided()
                .whenComplete((d, err) -> Platform.runLater(() -> {
                    if (inWindow.test(tab)) {
                        close.accept(tab);
                    }
                    pane.diff().dispose();
                }));
        tab.tabPaneProperty().addListener((obs, was, now) -> {
            if (now == null) {
                Platform.runLater(
                        () -> { // a move between splits takes it out and puts it back
                            if (!inWindow.test(tab)) {
                                pane.closed();
                            }
                        });
            }
        });
    }
}
