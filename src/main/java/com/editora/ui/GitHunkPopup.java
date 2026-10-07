package com.editora.ui;

import java.util.List;

import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import com.editora.editor.GitHunk;

import static com.editora.i18n.Messages.tr;

/**
 * The card a change bar opens: what the change replaced and what is there now, with the things one does to
 * a single change — put the old lines back, stage it, copy the old text, move to the next one.
 *
 * <p>An in-scene overlay ({@link OverlayHost}), like every other card that takes keys: {@code Esc} and a
 * click outside close it. It is closed by those events only; nothing here watches the editor's scroll
 * position, which also changes during layout.
 *
 * <p>Both sides are read-only text areas rather than labels so the text can be selected and copied — the
 * usual reason to look at the old side is to take part of it back.
 */
final class GitHunkPopup {

    /** Most lines a side shows before it scrolls. */
    static final int MAX_ROWS = 12;

    /** What the card's buttons do. Each runs after the card has closed, except {@code copied}. */
    record Actions(
            Runnable revert, Runnable stage, Runnable previous, Runnable next, Runnable openDiff, Runnable copied) {}

    private final OverlayHost overlayHost;
    private final Label title = new Label();
    private final Label oldCaption = new Label(tr("diff.side.head"));
    private final Label newCaption = new Label(tr("diff.side.working"));
    private final TextArea oldText = side("git-hunk-old");
    private final TextArea newText = side("git-hunk-new");
    private final Button revert = button("git.hunk.revert");
    private final Button stage = button("git.hunk.stage");
    private final Button copyOld = button("git.hunk.copyOld");
    private final Button previous = button("git.hunk.previous");
    private final Button next = button("git.hunk.next");
    private final Button openDiff = button("git.hunk.openDiff");
    private final VBox card;
    private Actions actions;
    private boolean showing;

    GitHunkPopup(OverlayHost overlayHost) {
        this.overlayHost = overlayHost;
        title.getStyleClass().add("palette-title");
        oldCaption.getStyleClass().add("git-hunk-caption");
        newCaption.getStyleClass().add("git-hunk-caption");
        WrapRow buttons = new WrapRow(6, 6, previous, next, revert, stage, copyOld, openDiff);
        buttons.getStyleClass().add("git-hunk-actions");
        card = new VBox(4, title, oldCaption, oldText, newCaption, newText, buttons);
        card.getStyleClass().addAll("command-palette", "git-hunk-popup");
        card.setPrefWidth(640);
        card.setMaxSize(640, Region.USE_PREF_SIZE);
        // Claim the keys, as every in-scene card does, or the global dispatcher runs editor chords
        // (and Escape never reaches the host).
        card.getProperties().put("editora.ownsKeys", Boolean.TRUE);
        revert.setOnAction(e -> closeThen(actions.revert()));
        stage.setOnAction(e -> closeThen(actions.stage()));
        previous.setOnAction(e -> closeThen(actions.previous()));
        next.setOnAction(e -> closeThen(actions.next()));
        openDiff.setOnAction(e -> closeThen(actions.openDiff()));
        copyOld.setOnAction(e -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(oldText.getText());
            Clipboard.getSystemClipboard().setContent(content);
            actions.copied().run();
        });
    }

    private static TextArea side(String styleClass) {
        TextArea area = new TextArea();
        area.setEditable(false);
        area.setWrapText(false);
        area.getStyleClass().addAll("git-hunk-side", styleClass);
        return area;
    }

    private static Button button(String key) {
        Button b = new Button(tr(key));
        b.getStyleClass().add("small");
        return b;
    }

    private void closeThen(Runnable action) {
        overlayHost.hide();
        action.run();
    }

    /** The lines of one side as the card shows and copies them. Pure. */
    static String text(List<String> lines) {
        StringBuilder out = new StringBuilder();
        for (String line : lines) {
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append(line.endsWith("\r") ? line.substring(0, line.length() - 1) : line);
        }
        return out.toString();
    }

    /**
     * Shows {@code hunk}.
     *
     * @param anchor {@code {x, belowY, aboveY}} in scene coordinates: the card's left edge, the bottom of
     *     the change's last visible line and the top of its first (see {@link OverlayHost#showAt})
     */
    void show(GitHunk hunk, String heading, String fontFamily, int fontSize, double[] anchor, Actions actions) {
        this.actions = actions;
        title.setText(heading);
        fill(oldCaption, oldText, hunk.oldLines(), fontFamily, fontSize);
        fill(newCaption, newText, hunk.newLines(), fontFamily, fontSize);
        copyOld.setDisable(hunk.oldLines().isEmpty());
        showing = true;
        overlayHost.showAt(
                card,
                anchor[0],
                anchor[1],
                anchor[2],
                () -> (hunk.oldLines().isEmpty() ? next : oldText).requestFocus(),
                () -> showing = false);
    }

    private static void fill(Label caption, TextArea area, List<String> lines, String fontFamily, int fontSize) {
        boolean any = !lines.isEmpty();
        caption.setVisible(any);
        caption.setManaged(any);
        area.setVisible(any);
        area.setManaged(any);
        area.setText(text(lines));
        area.setStyle("-fx-font-family: \"" + fontFamily + "\"; -fx-font-size: " + fontSize + "px;");
        int rows = Math.clamp(lines.size(), 1, MAX_ROWS);
        area.setPrefRowCount(rows);
        // A TextArea's own preferred height ignores the font set by style until it has been laid out once.
        double height = rows * fontSize * 1.45 + 14;
        area.setMinHeight(height);
        area.setPrefHeight(height);
        area.setMaxHeight(height);
    }

    boolean isShowing() {
        return showing;
    }

    /** The card, for tests and snapshots. */
    VBox card() {
        return card;
    }
}
