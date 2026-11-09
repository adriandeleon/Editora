package com.editora.ui;

import javafx.collections.ListChangeListener;
import javafx.scene.Node;
import javafx.scene.control.Tab;
import javafx.scene.layout.StackPane;

import com.editora.editor.TabContent;
import org.jspecify.annotations.Nullable;

/**
 * A terminal shown as an editor tab (Move to Editor, as in IntelliJ): the content of a {@link TerminalPanel} tab,
 * shell and all, in a frame of its own. The panel tab stays the terminal's identity — its title, folder, program
 * and close question are the panel's — so moving it back and forth restarts nothing.
 *
 * <p>The frame asks focus for the terminal, not for itself: every path that shows a page tab ends in the page's
 * {@code requestFocus} ({@link TabFocus#focusPage}), and a frame that took it would leave the shell without keys.
 */
final class TerminalEditorTab implements TabContent {

    private final Tab terminal;
    private final StackPane frame;
    private @Nullable Tab editorTab;

    TerminalEditorTab(Tab terminal, Runnable focusTerminal) {
        this.terminal = terminal;
        this.frame = new StackPane() {
            @Override
            public void requestFocus() {
                focusTerminal.run();
            }
        };
        frame.getStyleClass().add("terminal-editor-frame");
        frame.getChildren().setAll(terminal.getContent()); // takes it out of the panel's strip
    }

    /** The terminal panel's tab this one shows. */
    Tab terminal() {
        return terminal;
    }

    /** The editor tab showing it, once added. */
    @Nullable
    Tab editorTab() {
        return editorTab;
    }

    /**
     * Ties this to its editor tab: the header follows the terminal's title, and a bell or notification out of sight
     * marks the editor tab as it marks a strip tab, until the tab is looked at.
     */
    void attach(Tab tab, Runnable refreshHeader) {
        this.editorTab = tab;
        tab.getStyleClass().add(STYLE);
        terminal.textProperty().addListener((o, was, now) -> refreshHeader.run());
        mirrorMarks(tab);
        terminal.getStyleClass().addListener((ListChangeListener<String>) c -> mirrorMarks(tab));
        tab.selectedProperty().addListener((o, was, now) -> {
            if (now) {
                terminal.getStyleClass().removeAll(TerminalPanel.BELL, TerminalPanel.NOTIFIED);
            }
        });
    }

    /** Copies the terminal tab's bell and notification marks to the editor tab. */
    private void mirrorMarks(Tab tab) {
        for (String mark : new String[] {TerminalPanel.BELL, TerminalPanel.NOTIFIED}) {
            boolean on = terminal.getStyleClass().contains(mark);
            if (on && !tab.getStyleClass().contains(mark)) {
                tab.getStyleClass().add(mark);
            } else if (!on) {
                tab.getStyleClass().remove(mark);
            }
        }
    }

    /** Style class of an editor tab showing a terminal. */
    static final String STYLE = "terminal-editor-tab";

    @Override
    public Node node() {
        return frame;
    }

    @Override
    public String title() {
        String text = terminal.getText();
        return text == null || text.isBlank() ? com.editora.i18n.Messages.tr("toolwindow.terminal") : text;
    }

    @Override
    public Node icon() {
        return Icons.terminal();
    }
}
