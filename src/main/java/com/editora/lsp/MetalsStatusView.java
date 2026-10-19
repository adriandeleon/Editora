package com.editora.lsp;

import java.util.regex.Pattern;

/**
 * How a Metals {@code metals/status} item reads in Editora's status bar. Metals writes VS Code's icon syntax
 * into the text ({@code $(sync~spin) Importing build}); a spinning icon marks work in progress, which runs the
 * language-server loading bar until Metals hides the item. The icons themselves are dropped.
 *
 * <p>Pure.
 *
 * @param type the server-status type: {@code Progress}, {@code ProgressEnd} or {@code Message}
 * @param text what the status bar says (null for {@code ProgressEnd})
 */
record MetalsStatusView(String type, String text) {

    /** {@code $(name)} or {@code $(name~modifier)}: a VS Code product icon. */
    private static final Pattern ICON = Pattern.compile("\\$\\([A-Za-z0-9-]+(?:~[A-Za-z]+)?\\)");

    /** What {@code status} says, or null when it says nothing to show. */
    static MetalsStatusView of(LanguageServerSession.MetalsStatus status) {
        if (status == null) {
            return null;
        }
        if (Boolean.TRUE.equals(status.hide)) {
            return new MetalsStatusView("ProgressEnd", null);
        }
        String raw = status.text == null ? "" : status.text;
        String text =
                ICON.matcher(raw).replaceAll("").replaceAll("\\s{2,}", " ").strip();
        if (text.isEmpty()) {
            return null;
        }
        boolean spinning = raw.contains("~spin)");
        return new MetalsStatusView(spinning ? "Progress" : "Message", text);
    }
}
