package com.editora.editor.structured;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;
import java.util.function.Supplier;

import javafx.scene.Node;
import javafx.scene.control.Label;
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
 *
 * <p>The live preview is a virtualized list of those rows ({@link PreviewList}, PERF-08, Pauta 6.2.0); {@link
 * #content} builds them all, for an export or a print.
 */
public final class SystemdPreview {

    private static final int NEXT_RUNS = 3;
    private static final DateTimeFormatter RUN_FMT =
            DateTimeFormatter.ofPattern("EEE MMM d HH:mm", Locale.getDefault());

    private SystemdPreview() {}

    public static Node build(SystemdUnit unit, LocalDateTime now) {
        return PreviewList.of(rows(unit, now), "systemd-preview-list");
    }

    public static VBox content(SystemdUnit unit, LocalDateTime now, double width) {
        return PreviewList.column(rows(unit, now), width, "systemd-preview");
    }

    /** The header, then each section's heading followed by its directives. */
    private static List<Supplier<Node>> rows(SystemdUnit unit, LocalDateTime now) {
        List<Supplier<Node>> rows = new ArrayList<>();
        String kind = SystemdDescribe.kind(unit);
        String desc = unit.first("Unit", "Description");
        String headerText = capitalize(kind) + (desc != null && !desc.isBlank() ? " — " + desc : "");
        rows.add(() -> {
            Label header = new Label(headerText);
            header.getStyleClass().add("systemd-header");
            header.setWrapText(true);
            return header;
        });
        for (SystemdUnit.Section section : unit.sections()) {
            rows.add(() -> {
                Label sh = new Label("[" + section.name() + "]");
                sh.getStyleClass().add("systemd-section");
                return sh;
            });
            for (SystemdUnit.Directive d : section.directives()) {
                rows.add(() -> directiveNode(d, now));
            }
        }
        return rows;
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
            if (p.ok() && p.calendar() instanceof SystemdCalendar calendar) {
                List<LocalDateTime> runs = calendar.nextRuns(now, NEXT_RUNS);
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
