package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.editora.build.BuildAction;

/**
 * The one filter rule for a build tool's task rows, shared by the tasks tool window and the actions popup:
 * a case-insensitive substring of the row's label, or of its section's title (which keeps that whole
 * section, so "lifecycle" lists its phases). The two views used to differ — fuzzy in the popup, substring
 * in the tree — so "sbr" found {@code spring-boot:run} in one and nothing in the other. Substring is the
 * clearer rule for goal names, which are typed as written. Pure.
 */
final class BuildActionsFilter {

    private BuildActionsFilter() {}

    /** {@code query} normalized: stripped and lower-cased; empty means no filtering. */
    static String normalize(String query) {
        return query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
    }

    static boolean matches(String text, String query) {
        return text != null && text.toLowerCase(Locale.ROOT).contains(query);
    }

    static String label(BuildAction.Row row) {
        return row instanceof BuildAction.Task t ? t.label() : ((BuildAction.Toggle) row).label();
    }

    /**
     * {@code sections} narrowed to what matches {@code query} (already normalized). Empty sections are
     * dropped. Purely a view: the active toggles are unaffected, so a profile checked before filtering keeps
     * contributing its argv even while its row is hidden.
     */
    static List<BuildAction.Section> filtered(List<BuildAction.Section> sections, String query) {
        List<BuildAction.Section> out = new ArrayList<>();
        for (BuildAction.Section section : sections) {
            if (matches(section.title(), query)) {
                out.add(section);
                continue;
            }
            List<BuildAction.Row> rows = new ArrayList<>();
            for (BuildAction.Row row : section.rows()) {
                if (matches(label(row), query)) {
                    rows.add(row);
                }
            }
            if (!rows.isEmpty()) {
                // Carry `collapsed` (and the tooltip) across: dropping it here would leave the expand decision
                // to a side effect of which filter branch ran (a title match passes the section through
                // untouched, a row match rebuilt it), so a group found by name stayed folded while one found
                // by goal opened.
                out.add(new BuildAction.Section(
                        section.title(), List.copyOf(rows), section.collapsed(), section.tooltip()));
            }
        }
        return out;
    }
}
