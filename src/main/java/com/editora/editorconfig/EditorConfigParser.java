package com.editora.editorconfig;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pure parser for a single {@code .editorconfig} file's text. Splits it into the preamble {@code root} flag
 * and an ordered list of {@code [glob]} sections (each a key→value map, keys/values lowercased per the spec;
 * the glob keeps its original case). {@link #toProperties} converts a section's raw map into the typed
 * {@link EditorConfigProperties}. No I/O — {@link EditorConfig} reads the file and calls this.
 */
public final class EditorConfigParser {

    /** Max key/value length honored (per the EditorConfig spec, longer ones are ignored). */
    private static final int MAX_LEN = 1024;

    private EditorConfigParser() {}

    public record Section(String glob, Map<String, String> properties) {}

    public record Parsed(boolean root, List<Section> sections) {}

    public static Parsed parse(String text) {
        boolean root = false;
        List<Section> sections = new ArrayList<>();
        Map<String, String> current = null;
        if (text == null) {
            return new Parsed(false, sections);
        }
        for (String raw : text.split("\n", -1)) {
            String line = stripInlineComment(raw).strip();
            if (line.isEmpty()) {
                continue;
            }
            if (line.charAt(0) == '[' && line.endsWith("]")) {
                String glob = line.substring(1, line.length() - 1);
                current = new LinkedHashMap<>();
                sections.add(new Section(glob, current));
                continue;
            }
            int eq = line.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String key = line.substring(0, eq).strip().toLowerCase(Locale.ROOT);
            String value = line.substring(eq + 1).strip().toLowerCase(Locale.ROOT);
            if (key.isEmpty() || key.length() > MAX_LEN || value.length() > MAX_LEN) {
                continue;
            }
            if (current == null) {
                if (key.equals("root")) {
                    root = "true".equals(value);
                }
            } else {
                current.put(key, value); // last value wins within a section
            }
        }
        return new Parsed(root, sections);
    }

    /** Strips a {@code #} or {@code ;} comment that starts the line (whole-line comments only, per spec). */
    private static String stripInlineComment(String line) {
        String t = line.stripLeading();
        return (t.startsWith("#") || t.startsWith(";")) ? "" : line;
    }

    /** Converts a section's raw key→value map to typed properties; unrecognized/invalid values are dropped. */
    public static EditorConfigProperties toProperties(Map<String, String> raw) {
        if (raw == null || raw.isEmpty()) {
            return EditorConfigProperties.EMPTY;
        }
        return new EditorConfigProperties(
                insertSpaces(raw.get("indent_style")),
                indentSize(raw.get("indent_size")),
                positiveInt(raw.get("tab_width")),
                endOfLine(raw.get("end_of_line")),
                charset(raw.get("charset")),
                bool(raw.get("trim_trailing_whitespace")),
                bool(raw.get("insert_final_newline")),
                maxLineLength(raw.get("max_line_length")));
    }

    private static Boolean insertSpaces(String v) {
        if ("space".equals(v)) {
            return Boolean.TRUE;
        }
        if ("tab".equals(v)) {
            return Boolean.FALSE;
        }
        return null;
    }

    /** {@code indent_size}: a positive int, or {@code tab} (→ null, deferring to {@code tab_width}). */
    private static Integer indentSize(String v) {
        if (v == null || v.equals("tab")) {
            return null;
        }
        return positiveInt(v);
    }

    private static String endOfLine(String v) {
        return "lf".equals(v) || "crlf".equals(v) || "cr".equals(v) ? v : null;
    }

    private static String charset(String v) {
        return switch (v == null ? "" : v) {
            case "utf-8", "utf-8-bom", "latin1", "utf-16le", "utf-16be" -> v;
            default -> null;
        };
    }

    private static Boolean bool(String v) {
        if ("true".equals(v)) {
            return Boolean.TRUE;
        }
        if ("false".equals(v)) {
            return Boolean.FALSE;
        }
        return null;
    }

    private static Integer maxLineLength(String v) {
        if ("off".equals(v)) {
            return EditorConfigProperties.OFF;
        }
        return positiveInt(v);
    }

    private static Integer positiveInt(String v) {
        if (v == null) {
            return null;
        }
        try {
            int n = Integer.parseInt(v.strip());
            return n > 0 ? n : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
