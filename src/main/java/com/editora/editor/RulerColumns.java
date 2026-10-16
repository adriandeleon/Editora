package com.editora.editor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Which columns a buffer's rulers stand at: the language's own list when Settings has one for it, else the
 * global list — and an {@code .editorconfig} {@code max_line_length} replaces the first of them, or hides
 * them all when it is {@code off}. Also reads and writes the {@code "80, 120"} form the Settings field and
 * the palette prompt use. Pure.
 */
public final class RulerColumns {

    /** The widest column a ruler may stand at; anything past it is a typo, not a line length. */
    public static final int MAX_COLUMN = 1000;

    /** What {@code .editorconfig}'s {@code max_line_length = off} resolves to (see EditorConfigProperties). */
    private static final int OFF = com.editora.editorconfig.EditorConfigProperties.OFF;

    private RulerColumns() {}

    /**
     * The columns, ascending and each once.
     *
     * @param editorConfig the file's {@code max_line_length}: null when it sets none, {@code OFF} for
     *     {@code off}
     */
    public static List<Integer> resolve(
            List<Integer> global, Map<String, List<Integer>> perLanguage, String language, Integer editorConfig) {
        if (editorConfig != null && editorConfig == OFF) {
            return List.of();
        }
        List<Integer> base = perLanguage != null && language != null && perLanguage.containsKey(language)
                ? perLanguage.get(language)
                : global;
        List<Integer> columns = new ArrayList<>(base == null ? List.of() : base);
        if (editorConfig != null && editorConfig > 0) {
            if (columns.isEmpty()) {
                columns.add(editorConfig);
            } else {
                columns.set(0, editorConfig);
            }
        }
        return clean(columns);
    }

    /** {@code columns} without out-of-range values, ascending and each once. */
    public static List<Integer> clean(List<Integer> columns) {
        TreeSet<Integer> set = new TreeSet<>();
        for (Integer c : columns) {
            if (c != null && c > 0 && c <= MAX_COLUMN) {
                set.add(c);
            }
        }
        return List.copyOf(set);
    }

    /**
     * Reads {@code "80, 120"} (commas, spaces or both between the numbers). Blank means no rulers. Null when
     * any part is not a column number from 1 to {@link #MAX_COLUMN}.
     */
    public static List<Integer> parse(String text) {
        List<Integer> out = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        for (String part : text.trim().split("[,\\s]+")) {
            if (part.isEmpty()) {
                continue;
            }
            try {
                int column = Integer.parseInt(part);
                if (column < 1 || column > MAX_COLUMN) {
                    return null;
                }
                out.add(column);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return clean(out);
    }

    /** {@code [80, 120]} as {@code "80, 120"}. */
    public static String format(List<Integer> columns) {
        StringBuilder out = new StringBuilder();
        for (Integer c : columns) {
            if (out.length() > 0) {
                out.append(", ");
            }
            out.append(c);
        }
        return out.toString();
    }

    /**
     * Reads the per-language form, {@code "java: 100; python: 79, 88; markdown:"} — a language with nothing
     * after its colon has no rulers. Entries are separated by {@code ;} or line breaks. Blank means none. Null
     * when an entry has no language or a column is not a number from 1 to {@link #MAX_COLUMN}.
     */
    public static Map<String, List<Integer>> parseByLanguage(String text) {
        Map<String, List<Integer>> out = new java.util.LinkedHashMap<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        for (String entry : text.split("[;\\n]")) {
            if (entry.isBlank()) {
                continue;
            }
            int colon = entry.indexOf(':');
            String language = colon < 0 ? "" : entry.substring(0, colon).trim();
            List<Integer> columns = colon < 0 ? null : parse(entry.substring(colon + 1));
            if (language.isEmpty() || columns == null) {
                return null;
            }
            out.put(language, columns);
        }
        return out;
    }

    /** {@code {java=[100], python=[79, 88]}} as {@code "java: 100; python: 79, 88"}. */
    public static String formatByLanguage(Map<String, List<Integer>> perLanguage) {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, List<Integer>> e : perLanguage.entrySet()) {
            if (out.length() > 0) {
                out.append("; ");
            }
            out.append(e.getKey()).append(':');
            if (!e.getValue().isEmpty()) {
                out.append(' ').append(format(e.getValue()));
            }
        }
        return out.toString();
    }
}
