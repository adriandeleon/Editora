package com.editora.editor;

import java.util.List;

import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.VBox;

import com.editora.fstab.FstabDescribe;
import com.editora.fstab.FstabEntry;
import com.editora.i18n.Messages;

/**
 * Renders parsed {@code /etc/fstab} entries into the fstab preview: a header (mount count) then one block
 * per line — the mount point (or "swap"), a one-line plain-English summary, the decoded mount options, and
 * the fsck/dump note. A malformed line renders as a red error row. Self-scrolling (wrapped in a
 * {@code ScrollPane}) so it hosts directly as the Split/Preview side, like {@link StructuredTree}. Kept in
 * {@code editor} (no {@code ui} dependency).
 */
public final class FstabPreview {

    private FstabPreview() {}

    /** The live self-scrolling preview node for the tool host. */
    public static Node build(List<FstabEntry> entries) {
        ScrollPane sp = new ScrollPane(content(entries, -1));
        sp.setFitToWidth(true);
        sp.getStyleClass().add("fstab-preview-scroll");
        return sp;
    }

    /** The bare content VBox (used for the light snapshot export); {@code width > 0} pins a layout width. */
    public static VBox content(List<FstabEntry> entries, double width) {
        VBox root = new VBox();
        root.getStyleClass().add("fstab-preview");
        if (width > 0) {
            root.setPrefWidth(width);
        }

        Label header = new Label(Messages.tr("fstab.summary", entries.size()));
        header.getStyleClass().add("fstab-header");
        header.setWrapText(true);
        root.getChildren().add(header);

        for (FstabEntry e : entries) {
            root.getChildren().add(entryNode(e));
        }
        return root;
    }

    private static Node entryNode(FstabEntry e) {
        VBox box = new VBox();
        box.getStyleClass().add(e.ok() ? "fstab-entry" : "fstab-entry-error");

        Label title = new Label(e.isSwap() ? Messages.tr("fstab.swap") : e.mountPoint());
        title.getStyleClass().add("fstab-target");
        title.setWrapText(true);
        box.getChildren().add(title);

        if (!e.ok()) {
            Label err = new Label(Messages.tr("fstab.invalid", e.error()));
            err.getStyleClass().add("fstab-error");
            err.setWrapText(true);
            box.getChildren().add(err);
            return box;
        }

        Label summary = new Label(FstabDescribe.summary(e));
        summary.getStyleClass().add("fstab-summary");
        summary.setWrapText(true);
        box.getChildren().add(summary);

        Label options = new Label(String.join(" · ", FstabDescribe.options(e)));
        options.getStyleClass().add("fstab-options");
        options.setWrapText(true);
        box.getChildren().add(options);

        Label check = new Label(FstabDescribe.checkLine(e));
        check.getStyleClass().add("fstab-check");
        check.setWrapText(true);
        box.getChildren().add(check);
        return box;
    }
}
