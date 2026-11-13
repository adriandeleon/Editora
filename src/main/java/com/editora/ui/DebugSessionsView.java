package com.editora.ui;

import java.util.ArrayList;
import java.util.List;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

import com.editora.editor.UiZoom;

import static com.editora.i18n.Messages.tr;

/**
 * The Debug tool window's content: the focused session's {@link DebugPanel}, under a strip of the window's sessions
 * when there is more than one — one tab each (its file and its state), a close button on one that has ended, and
 * Stop All while two or more are live. With one session the strip is hidden and the window looks as it always did.
 */
final class DebugSessionsView extends BorderPane {

    /** One session's tab: what it is called, its state's words, whether it is live and is the one shown. */
    record Tab(DebugSession session, String title, String state, boolean live, boolean shown) {}

    /** What the strip asks of the window. */
    interface Actions {
        /** Show {@code session} (its tab was clicked). */
        void show(DebugSession session);

        /** Close {@code session}'s tab: it has ended and its output is no longer wanted. */
        void close(DebugSession session);

        /** Stop every live session. */
        void stopAll();
    }

    private final Actions actions;
    private final HBox strip = UiZoom.spacing(new HBox(), 4);
    private final List<Tab> tabs = new ArrayList<>();

    DebugSessionsView(Actions actions) {
        this.actions = actions;
        getStyleClass().add("debug-sessions-view");
        strip.getStyleClass().add("debug-sessions-strip");
        strip.setAlignment(Pos.CENTER_LEFT);
        showStrip(false);
        setTop(strip);
    }

    /** Shows {@code panel}: the focused session's. */
    void setContent(Node panel) {
        if (getCenter() != panel) {
            setCenter(panel);
        }
    }

    /** The tabs shown now (test read). */
    List<Tab> tabs() {
        return List.copyOf(tabs);
    }

    /** Whether the strip is shown: two or more sessions. */
    boolean stripShown() {
        return strip.isVisible();
    }

    /** Draws the sessions' tabs again; with fewer than two the strip is hidden. */
    void setTabs(List<Tab> now) {
        tabs.clear();
        tabs.addAll(now);
        strip.getChildren().clear();
        if (now.size() < 2) {
            showStrip(false);
            return;
        }
        ToggleGroup group = new ToggleGroup();
        int live = 0;
        for (Tab tab : now) {
            ToggleButton button = new ToggleButton(tr("debug.sessions.tab", tab.title(), tab.state()));
            button.getStyleClass().addAll("debug-threads-toggle", "small", "debug-session-tab");
            if (!tab.live()) {
                button.getStyleClass().add("debug-session-ended");
            }
            button.setToggleGroup(group);
            button.setSelected(tab.shown());
            button.setFocusTraversable(false);
            button.setOnAction(e -> {
                button.setSelected(true); // a tab is always shown: clicking the shown one keeps it
                actions.show(tab.session());
            });
            strip.getChildren().add(button);
            if (tab.live()) {
                live++;
            } else {
                Button close = new Button("×");
                close.getStyleClass().addAll("flat", "small", "debug-session-close");
                if (tab.shown()) { // the command closes the shown session, so only its × carries the chord
                    CommandTips.name(close, "debug.closeEndedSession", () -> tr("debug.sessions.close", tab.title()));
                } else {
                    Icons.name(close, tr("debug.sessions.close", tab.title()));
                }
                close.setFocusTraversable(false);
                close.setOnAction(e -> actions.close(tab.session()));
                strip.getChildren().add(close);
            }
        }
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        strip.getChildren().add(gap);
        if (live >= 2) {
            Button stopAll = new Button(tr("debug.sessions.stopAll"));
            stopAll.getStyleClass().addAll("small", "debug-session-stop-all");
            stopAll.setFocusTraversable(false);
            stopAll.setOnAction(e -> actions.stopAll());
            strip.getChildren().add(stopAll);
        }
        showStrip(true);
    }

    /** {@code debug.closeEndedSession}: the shown session's × when it has ended; false when it has no ×. */
    boolean closeShownEnded() {
        for (Tab tab : tabs()) {
            if (tab.shown() && !tab.live() && stripShown()) {
                actions.close(tab.session());
                return true;
            }
        }
        return false;
    }

    private void showStrip(boolean on) {
        strip.setVisible(on);
        strip.setManaged(on);
    }
}
