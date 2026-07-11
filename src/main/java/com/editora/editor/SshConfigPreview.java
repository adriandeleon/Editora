package com.editora.editor;

import java.util.List;

import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.VBox;

import com.editora.i18n.Messages;
import com.editora.sshconfig.SshConfig;
import com.editora.sshconfig.SshConfigDescribe;

/**
 * Renders a parsed SSH client config into the preview: a header, then one block per {@code Host}/{@code Match}
 * (and the {@code global} defaults) showing a one-line connection summary above each option's plain-English
 * gloss. Self-scrolling; hosts as the Split/Preview side like {@link StructuredTree}. Kept in {@code editor}
 * (no {@code ui} dependency).
 */
public final class SshConfigPreview {

    private SshConfigPreview() {}

    public static Node build(List<SshConfig.Block> blocks) {
        ScrollPane sp = new ScrollPane(content(blocks, -1));
        sp.setFitToWidth(true);
        sp.getStyleClass().add("sshconfig-preview-scroll");
        return sp;
    }

    public static VBox content(List<SshConfig.Block> blocks, double width) {
        VBox root = new VBox();
        root.getStyleClass().add("sshconfig-preview");
        if (width > 0) {
            root.setPrefWidth(width);
        }

        Label header = new Label(Messages.tr("sshconfig.summary", blocks.size()));
        header.getStyleClass().add("sshconfig-header");
        header.setWrapText(true);
        root.getChildren().add(header);

        for (SshConfig.Block b : blocks) {
            root.getChildren().add(blockNode(b));
        }
        return root;
    }

    private static Node blockNode(SshConfig.Block b) {
        VBox box = new VBox();
        box.getStyleClass().add("sshconfig-block");

        String title = b.type().equals("global") ? Messages.tr("sshconfig.global") : b.type() + " " + b.argument();
        Label t = new Label(title);
        t.getStyleClass().add("sshconfig-target");
        t.setWrapText(true);
        box.getChildren().add(t);

        Label summary = new Label(SshConfigDescribe.summary(b));
        summary.getStyleClass().add("sshconfig-summary");
        summary.setWrapText(true);
        box.getChildren().add(summary);

        for (SshConfig.Option o : b.options()) {
            String gloss = SshConfigDescribe.gloss(o.key(), o.value());
            Label opt = new Label(gloss != null ? gloss : o.key() + " " + o.value());
            opt.getStyleClass().add("sshconfig-option");
            opt.setWrapText(true);
            box.getChildren().add(opt);
        }
        return box;
    }
}
