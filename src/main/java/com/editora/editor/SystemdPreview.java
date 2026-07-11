package com.editora.editor;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;

import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.VBox;

import com.editora.i18n.Messages;
import com.editora.systemd.SystemdCalendar;
import com.editora.systemd.SystemdDescribe;
import com.editora.systemd.SystemdUnit;

/**
 * Renders a parsed systemd unit into the preview: a header (unit kind + its {@code Description=}), then each
 * {@code [Section]} with its directives — each shown as a plain-English gloss above the raw {@code Key=value},
 * and an {@code OnCalendar=} line additionally gets its upcoming trigger times. Self-scrolling; hosts as the
 * Split/Preview side like {@link StructuredTree}. Kept in {@code editor} (no {@code ui} dependency).
 */
public final class SystemdPreview {

    private static final int NEXT_RUNS = 3;
    private static final DateTimeFormatter RUN_FMT =
            DateTimeFormatter.ofPattern("EEE MMM d HH:mm", Locale.getDefault());

    private SystemdPreview() {}

    public static Node build(SystemdUnit unit, LocalDateTime now) {
        ScrollPane sp = new ScrollPane(content(unit, now, -1));
        sp.setFitToWidth(true);
        sp.getStyleClass().add("systemd-preview-scroll");
        return sp;
    }

    public static VBox content(SystemdUnit unit, LocalDateTime now, double width) {
        VBox root = new VBox();
        root.getStyleClass().add("systemd-preview");
        if (width > 0) {
            root.setPrefWidth(width);
        }

        String kind = SystemdDescribe.kind(unit);
        String desc = unit.first("Unit", "Description");
        String headerText = capitalize(kind) + (desc != null && !desc.isBlank() ? " — " + desc : "");
        Label header = new Label(headerText);
        header.getStyleClass().add("systemd-header");
        header.setWrapText(true);
        root.getChildren().add(header);

        for (SystemdUnit.Section section : unit.sections()) {
            Label sh = new Label("[" + section.name() + "]");
            sh.getStyleClass().add("systemd-section");
            root.getChildren().add(sh);
            for (SystemdUnit.Directive d : section.directives()) {
                root.getChildren().add(directiveNode(d, now));
            }
        }
        return root;
    }

    private static Node directiveNode(SystemdUnit.Directive d, LocalDateTime now) {
        VBox box = new VBox();
        box.getStyleClass().add("systemd-item");

        String gloss = SystemdDescribe.gloss(d.key(), d.value());
        if (gloss != null && !gloss.isBlank()) {
            Label g = new Label(gloss);
            g.getStyleClass().add("systemd-gloss");
            g.setWrapText(true);
            box.getChildren().add(g);
        }

        Label raw = new Label(d.key() + "=" + d.value());
        raw.getStyleClass().add("systemd-raw");
        raw.setWrapText(true);
        box.getChildren().add(raw);

        if (d.key().equalsIgnoreCase("OnCalendar")) {
            SystemdCalendar.Parsed p = SystemdCalendar.parse(d.value());
            if (p.ok()) {
                List<LocalDateTime> runs = p.calendar().nextRuns(now, NEXT_RUNS);
                if (!runs.isEmpty()) {
                    StringJoiner sj = new StringJoiner(", ");
                    for (LocalDateTime r : runs) {
                        sj.add(r.format(RUN_FMT));
                    }
                    Label next = new Label("→ " + sj);
                    next.getStyleClass().add("systemd-nextruns");
                    next.setWrapText(true);
                    box.getChildren().add(next);
                }
            }
        }
        return box;
    }

    private static String capitalize(String s) {
        if (s.isEmpty()) {
            return Messages.tr("systemd.unit");
        }
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
