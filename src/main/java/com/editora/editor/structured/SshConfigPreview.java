package com.editora.editor.structured;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

import com.editora.i18n.Messages;
import com.editora.sshconfig.SshConfig;
import com.editora.sshconfig.SshConfigDescribe;

/**
 * Renders a parsed SSH client config into the preview: a header, then one block per {@code Host}/{@code Match}
 * (and the {@code global} defaults) showing a one-line connection summary above each option's plain-English
 * gloss. Self-scrolling; hosts as the Split/Preview side like {@link StructuredTree}. Kept in {@code editor}
 * (no {@code ui} dependency).
 *
 * <p>The live preview is a virtualized list of those rows ({@link PreviewList}, PERF-08, Pauta 6.0.1); {@link
 * #content} builds them all, for an export or a print.
 */
public final class SshConfigPreview {

    private SshConfigPreview() {}

    public static Node build(List<SshConfig.Block> blocks) {
        return PreviewList.of(rows(blocks), "sshconfig-preview-list");
    }

    public static VBox content(List<SshConfig.Block> blocks, double width) {
        return PreviewList.column(rows(blocks), width, "sshconfig-preview");
    }

    /** The header, then one row per {@code Host} or {@code Match} block. */
    private static List<Supplier<Node>> rows(List<SshConfig.Block> blocks) {
        List<Supplier<Node>> rows = new ArrayList<>();
        rows.add(() -> {
            Label header = new Label(Messages.tr("sshconfig.summary", blocks.size()));
            header.getStyleClass().add("sshconfig-header");
            header.setWrapText(true);
            return header;
        });
        for (SshConfig.Block b : blocks) {
            rows.add(() -> blockNode(blocks, b));
        }
        return rows;
    }

    private static Node blockNode(List<SshConfig.Block> blocks, SshConfig.Block b) {
        VBox box = new VBox();
        box.getStyleClass().add("sshconfig-block");

        String title = b.type().equals("global") ? Messages.tr("sshconfig.global") : b.type() + " " + b.argument();
        Label t = new Label(title);
        t.getStyleClass().add("sshconfig-target");
        t.setWrapText(true);
        box.getChildren().add(t);

        Label summary = new Label(SshConfigDescribe.summary(blocks, b));
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
