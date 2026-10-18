package com.editora.doctor;

import java.util.List;

/**
 * The plain-text Doctor report for "Copy report": a header (Editora version, OS, Java) and one line per row,
 * grouped by section — what a user pastes into a bug report or a chat (review C2). It reads as plain text and
 * as Markdown (sections are {@code ##} headings, rows are list items). Pure; the pane resolves every string.
 */
public final class DoctorReport {

    private DoctorReport() {}

    /** One row, already in words: status word, tool, command, version or path, and the tip when it has one. */
    public record Line(String section, String status, String label, String command, String detail, String tip) {}

    public static String render(String title, List<String> headerLines, List<Line> lines) {
        StringBuilder sb = new StringBuilder("# ").append(title).append('\n');
        for (String h : headerLines) {
            if (h != null && !h.isBlank()) {
                sb.append(h).append('\n');
            }
        }
        String section = null;
        for (Line l : lines) {
            if (!l.section().equals(section)) {
                section = l.section();
                sb.append("\n## ").append(section).append('\n');
            }
            sb.append("- [").append(l.status()).append("] ").append(l.label());
            if (!l.command().isBlank()) {
                sb.append(" (").append(l.command()).append(')');
            }
            if (!l.detail().isBlank()) {
                sb.append(": ").append(l.detail());
            }
            sb.append('\n');
            if (!l.tip().isBlank()) {
                sb.append("  ").append(l.tip()).append('\n');
            }
        }
        return sb.toString();
    }
}
