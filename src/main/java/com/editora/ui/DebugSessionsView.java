package com.editora.ui;

import java.util.ArrayList;
import java.util.List;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

import com.editora.editor.UiZoom;
import org.jspecify.annotations.Nullable;

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
        // debug.switchSession: which of the strip's sessions to show, asked as a list, as clicking its tab shows it.
        FocusCommands.menu(this, this::sessionRows);
    }

    /** One row per session tab of the strip, each marked for {@code debug.switchSession}; null without a strip. */
    private @Nullable List<MenuItem> sessionRows() {
        if (!strip.isVisible()) {
            return null;
        }
        List<MenuItem> rows = new ArrayList<>();
        for (Node child : strip.getChildren()) {
            if (child instanceof ToggleButton tab) {
                MenuItem row = FocusCommands.also(new MenuItem(tab.getText()), "debug.switchSession");
                row.setOnAction(e -> tab.fire());
                rows.add(row);
            }
        }
        return rows;
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

    /**
     * Pure: whether {@code now} differs from {@code before} only in the tabs' state words — the same sessions in the
     * same order, with the same titles, each as live and as shown — so the strip's buttons need only their text set.
     */
    static boolean onlyStatesDiffer(List<Tab> before, List<Tab> now) {
        if (before.size() != now.size()) {
            return false;
        }
        for (int i = 0; i < now.size(); i++) {
            Tab a = before.get(i);
            Tab b = now.get(i);
            if (a.session() != b.session()
                    || !a.title().equals(b.title())
                    || a.live() != b.live()
                    || a.shown() != b.shown()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Draws the sessions' tabs again; with fewer than two the strip is hidden. A step (Running, then Suspended)
     * only changes a tab's words: the buttons are kept and their text set (perf review 2026-11-23, D14).
     */
    void setTabs(List<Tab> now) {
        if (now.equals(tabs)) {
            return;
        }
        if (now.size() >= 2 && onlyStatesDiffer(tabs, now)) {
            int i = 0;
            for (Node child : strip.getChildren()) {
                if (child instanceof ToggleButton button) {
                    Tab tab = now.get(i++);
                    button.setText(tr("debug.sessions.tab", tab.title(), tab.state()));
                }
            }
            tabs.clear();
            tabs.addAll(now);
            return;
        }
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
