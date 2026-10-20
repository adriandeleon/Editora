package com.editora.ui;

import java.util.Set;
import java.util.function.IntFunction;

/**
 * Pom-aware labels for the Structure window's XML outline (Maven review C16): a {@code dependency},
 * {@code plugin}, {@code exclusion} or {@code extension} node is named by its {@code groupId:artifactId},
 * a {@code profile} by its {@code id}, an {@code execution} by its {@code id} — read from the element's own
 * lines, so "dependency line 33" ×5 becomes a list the reader can pick from. Pure.
 */
final class PomOutlineLabels {

    /** Elements named by their {@code <groupId>}/{@code <artifactId}} children. */
    private static final Set<String> COORDINATE_ELEMENTS = Set.of("dependency", "plugin", "exclusion", "extension");

    /** Elements named by their {@code <id>} child. */
    private static final Set<String> ID_ELEMENTS = Set.of("profile", "execution", "repository", "pluginRepository");

    /** How many lines below the element's start are scanned for its naming children. */
    static final int MAX_SCAN_LINES = 40;

    private PomOutlineLabels() {}

    /**
     * The label for an outline node whose element is {@code elementName} starting at {@code startLine} and
     * ending at {@code endLine} (inclusive, 0-based; {@code endLine < startLine} = unknown), reading lines
     * through {@code lineAt}; the plain name when the element is not one with a naming child.
     */
    static String label(String elementName, int startLine, int endLine, int lineCount, IntFunction<String> lineAt) {
        if (elementName == null) {
            return null;
        }
        String name = elementName.strip();
        int last = endLine < startLine ? startLine + MAX_SCAN_LINES : Math.min(endLine, startLine + MAX_SCAN_LINES);
        last = Math.min(last, lineCount - 1);
        if (COORDINATE_ELEMENTS.contains(name)) {
            String group = child(startLine, last, lineAt, "groupId");
            String artifact = child(startLine, last, lineAt, "artifactId");
            if (artifact.isEmpty()) {
                return name;
            }
            return name + " " + (group.isEmpty() ? artifact : group + ":" + artifact);
        }
        if (ID_ELEMENTS.contains(name)) {
            String id = child(startLine, last, lineAt, "id");
            return id.isEmpty() ? name : name + " " + id;
        }
        return name;
    }

    /** The text of the first {@code <tag>…</tag>} on one line within the range, else {@code ""}. */
    private static String child(int from, int to, IntFunction<String> lineAt, String tag) {
        String open = "<" + tag + ">";
        String close = "</" + tag + ">";
        for (int i = Math.max(0, from); i <= to; i++) {
            String line = lineAt.apply(i);
            if (line == null) {
                break;
            }
            int a = line.indexOf(open);
            if (a < 0) {
                continue;
            }
            int b = line.indexOf(close, a + open.length());
            if (b < 0) {
                continue;
            }
            return line.substring(a + open.length(), b).strip();
        }
        return "";
    }
}
