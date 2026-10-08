package com.editora.snippet;

/** How a snippet reads in a one-line list row (the Insert Snippet picker, the Settings list). Pure. */
public final class SnippetPreview {

    static final int MAX_BODY = 72;

    private SnippetPreview() {}

    /** {@code trigger — name}, or just the trigger when the name says nothing more ({@code fori — fori}). */
    public static String label(Snippet s) {
        String prefix = s.prefix() == null ? "" : s.prefix();
        String name = s.name() == null ? "" : s.name();
        if (name.isBlank() || name.equalsIgnoreCase(prefix)) {
            return prefix;
        }
        return prefix.isBlank() ? name : prefix + " — " + name;
    }

    /** The description when it adds to the label, then what the snippet inserts, on one line. */
    public static String detail(Snippet s) {
        String description = s.description() == null ? "" : s.description().strip();
        if (description.equalsIgnoreCase(s.name() == null ? "" : s.name().strip())) {
            description = "";
        }
        String body = bodyLine(s.body());
        if (description.isEmpty()) {
            return body;
        }
        return body.isEmpty() ? description : description + "  ·  " + body;
    }

    /** The text {@code body} expands to with placeholders at their defaults, collapsed to one short line. */
    public static String bodyLine(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        String text;
        try {
            text = SnippetParser.parse(body, name -> VariableResolver.NAMES.contains(name) ? "…" : null)
                    .text();
        } catch (RuntimeException e) {
            text = body;
        }
        String line = text.strip().replaceAll("\\s+", " ");
        return line.length() <= MAX_BODY ? line : line.substring(0, MAX_BODY - 1) + "…";
    }
}
