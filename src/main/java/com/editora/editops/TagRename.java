package com.editora.editops;

import java.util.Locale;
import java.util.Set;

/**
 * Pure, unit-tested auto-rename-tag core (the VS Code "Auto Rename Tag" behavior): after an edit
 * inside an HTML/XML tag <i>name</i>, {@link #mirror} computes the edit that renames the paired
 * open/close tag to match, or {@code null} when the edit wasn't a tag-name rename. The change is
 * reverted to reconstruct the <b>old name</b> the tag had before the edit (the pair still bears
 * it), then the pair is found by <b>same-name depth counting</b> over one forward lex: only tags
 * bearing the old name participate, so real-world HTML's unclosed optional-close-tag elements
 * ({@code <li>}, {@code <p>}, …) can't misalign the match, and a brand-new half-typed tag (old
 * name empty) never renames an unrelated tag. The lexer skips comments, CDATA, doctype/processing
 * instructions, quoted attribute values, and self-closing tags; in HTML mode it also treats void
 * elements ({@code <br>}, {@code <img>}, …) as self-closing and skips raw-text element content
 * ({@code <script>}/{@code <style>}/…). No toolkit dependency (mirrors {@link LineOps});
 * {@code EditorBuffer} applies the result.
 */
public final class TagRename {

    /** Replace {@code [from, to)} — the paired tag's name — with {@code name}. */
    public record Mirror(int from, int to, String name) {}

    /** Documents above this size are not scanned (bounds the per-keystroke lex). */
    public static final int MAX_DOC = 2_000_000;

    /** Longest tag name considered (bounds the backward region walk). */
    private static final int MAX_NAME = 256;

    /** HTML void elements — no close tag, treated as self-closing. */
    private static final Set<String> VOID_ELEMENTS = Set.of(
            "area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track",
            "wbr");

    /** HTML raw-text elements — their content is skipped to the matching close tag. */
    private static final Set<String> RAW_TEXT_ELEMENTS = Set.of("script", "style", "textarea", "title");

    private TagRename() {}

    /**
     * Computes the paired-tag rename for the change {@code (changePos, removed, inserted)} already
     * applied to {@code text}, or {@code null} when there is nothing to mirror: the change isn't
     * fully inside a tag-name region, the name is empty/unchanged, the old name is empty (a
     * brand-new tag — it has no pre-existing pair), or the tag has no same-name pair (an unclosed
     * opener / a stray closer).
     */
    public static Mirror mirror(String text, int changePos, String removed, String inserted, boolean html) {
        if (text.length() > MAX_DOC) {
            return null;
        }
        int changeEnd = changePos + inserted.length();
        int[] region = nameRegionAt(text, changePos, changeEnd);
        if (region == null) {
            return null;
        }
        int nameStart = region[0];
        int nameEnd = region[1];
        String newName = text.substring(nameStart, nameEnd);
        if (newName.isEmpty()) {
            return null;
        }
        // Revert the change inside the region to get the name the tag had before the edit.
        String oldName = text.substring(nameStart, changePos) + removed + text.substring(changeEnd, nameEnd);
        if (oldName.isEmpty() || oldName.equals(newName) || !isName(oldName)) {
            return null;
        }
        boolean closing = text.charAt(nameStart - 1) == '/';
        return findPair(text, nameStart, closing, oldName, newName, html);
    }

    /**
     * The tag-name region enclosing the change, or {@code null}: walk back over name characters to
     * a preceding {@code <} or {@code </}, forward over name characters to the region end; the
     * change (including what it inserted) must lie entirely within the region.
     */
    private static int[] nameRegionAt(String text, int changeStart, int changeEnd) {
        int n = text.length();
        if (changeStart < 0 || changeEnd > n || changeEnd < changeStart) {
            return null;
        }
        int start = changeStart;
        int walked = 0;
        while (start > 0 && isNameChar(text.charAt(start - 1)) && walked++ < MAX_NAME) {
            start--;
        }
        if (start == 0) {
            return null;
        }
        char prev = text.charAt(start - 1);
        boolean open = prev == '<';
        boolean close = prev == '/' && start >= 2 && text.charAt(start - 2) == '<';
        if (!open && !close) {
            return null;
        }
        // The inserted span itself must be name characters (else the edit broke out of the name).
        for (int i = changeStart; i < changeEnd; i++) {
            if (!isNameChar(text.charAt(i))) {
                return null;
            }
        }
        int end = changeEnd;
        while (end < n && isNameChar(text.charAt(end))) {
            end++;
        }
        return new int[] {start, end};
    }

    /**
     * One forward lex from the document start, then <b>same-name depth counting</b>: only tags
     * bearing {@code oldName} (plus the edited tag itself, which already bears {@code newName})
     * participate in the pairing, so real-world HTML's unclosed optional-close-tag elements
     * ({@code <li>}, {@code <p>}, {@code <td>}, …) with <i>other</i> names can't misalign the
     * match. An opener pairs with the close tag that returns its same-name depth to zero (walking
     * forward); a closer pairs with the open tag that does so walking backward. The pair bears
     * {@code oldName} by construction — the old-name guard is inherent.
     */
    private static Mirror findPair(
            String text, int targetNameStart, boolean targetIsClose, String oldName, String newName, boolean html) {
        record Ev(boolean open, int nameStart, int nameEnd) {}
        java.util.List<Ev> events = new java.util.ArrayList<>();
        int targetIndex = -1;
        int n = text.length();
        int i = 0;
        while (i < n) {
            char c = text.charAt(i);
            if (c != '<') {
                i++;
                continue;
            }
            if (text.startsWith("<!--", i)) {
                i = skipTo(text, i + 4, "-->");
            } else if (text.startsWith("<![CDATA[", i)) {
                i = skipTo(text, i + 9, "]]>");
            } else if (text.startsWith("<?", i)) {
                i = skipTo(text, i + 2, "?>");
            } else if (text.startsWith("<!", i)) {
                i = skipTo(text, i + 2, ">");
            } else if (text.startsWith("</", i)) {
                int nameStart = i + 2;
                int nameEnd = nameEnd(text, nameStart);
                String name = text.substring(nameStart, nameEnd);
                boolean isTarget = nameStart == targetNameStart;
                if (isTarget && !targetIsClose) {
                    return null; // region said opener but the lexer sees a closer — bail
                }
                if (isTarget || namesEqual(name, oldName, html)) {
                    if (isTarget) {
                        targetIndex = events.size();
                    }
                    events.add(new Ev(false, nameStart, nameEnd));
                }
                i = skipTag(text, nameEnd)[0];
            } else if (i + 1 < n && isNameChar(text.charAt(i + 1))) {
                int nameStart = i + 1;
                int nameEnd = nameEnd(text, nameStart);
                String name = text.substring(nameStart, nameEnd);
                int[] end = skipTag(text, nameEnd); // {posAfterTag, selfClosing}
                boolean isTarget = nameStart == targetNameStart;
                // The edited open tag already bears the new name; its void/raw-text classification
                // and its close tag still go by the old one.
                String effName = isTarget ? oldName : name;
                boolean selfClosing = end[1] == 1 || (html && VOID_ELEMENTS.contains(lower(effName)));
                if (isTarget && (targetIsClose || selfClosing)) {
                    return null; // region said close but lexer sees an opener (or a self-closer) — bail
                }
                if (!selfClosing) {
                    if (isTarget || namesEqual(name, oldName, html)) {
                        if (isTarget) {
                            targetIndex = events.size();
                        }
                        events.add(new Ev(true, nameStart, nameEnd));
                    }
                    if (html && RAW_TEXT_ELEMENTS.contains(lower(effName))) {
                        // Raw-text content: no tags inside; jump to this element's own close tag.
                        i = skipToCloseTag(text, end[0], effName);
                        continue;
                    }
                }
                i = end[0];
            } else {
                i++; // stray '<'
            }
        }
        if (targetIndex < 0) {
            return null; // the edited tag sits inside a skipped construct (comment/CDATA/attrs)
        }
        int depth = 1;
        if (!targetIsClose) {
            for (int k = targetIndex + 1; k < events.size(); k++) {
                depth += events.get(k).open() ? 1 : -1;
                if (depth == 0) {
                    return new Mirror(events.get(k).nameStart(), events.get(k).nameEnd(), newName);
                }
            }
        } else {
            for (int k = targetIndex - 1; k >= 0; k--) {
                depth += events.get(k).open() ? -1 : 1;
                if (depth == 0) {
                    return new Mirror(events.get(k).nameStart(), events.get(k).nameEnd(), newName);
                }
            }
        }
        return null; // no same-name pair (unclosed opener / stray closer)
    }

    /** Position after {@code marker} starting the search at {@code from} (end of text if absent). */
    private static int skipTo(String text, int from, String marker) {
        int idx = text.indexOf(marker, from);
        return idx < 0 ? text.length() : idx + marker.length();
    }

    /** End of the tag-name run starting at {@code from}. */
    private static int nameEnd(String text, int from) {
        int i = from;
        int n = text.length();
        while (i < n && isNameChar(text.charAt(i))) {
            i++;
        }
        return i;
    }

    /**
     * Skips a tag's attribute region from {@code from} (just after the name) to past its closing
     * {@code >}, honoring single/double-quoted attribute values. Returns {@code {posAfterTag,
     * selfClosing ? 1 : 0}}; an unterminated tag runs to the end of the text.
     */
    private static int[] skipTag(String text, int from) {
        int n = text.length();
        int i = from;
        char lastMeaningful = 0;
        while (i < n) {
            char c = text.charAt(i);
            if (c == '"' || c == '\'') {
                int close = text.indexOf(c, i + 1);
                i = close < 0 ? n : close + 1;
                lastMeaningful = c;
                continue;
            }
            if (c == '>') {
                return new int[] {i + 1, lastMeaningful == '/' ? 1 : 0};
            }
            if (!Character.isWhitespace(c)) {
                lastMeaningful = c;
            }
            i++;
        }
        return new int[] {n, 0};
    }

    /** Position of {@code </name} (case-insensitive) at/after {@code from} — for raw-text content. */
    private static int skipToCloseTag(String text, int from, String name) {
        String needle = "</" + name;
        int n = text.length();
        for (int i = Math.max(0, from); i <= n - needle.length(); i++) {
            if (text.regionMatches(true, i, needle, 0, needle.length())) {
                return i;
            }
        }
        return n;
    }

    private static boolean namesEqual(String a, String b, boolean html) {
        return html ? a.equalsIgnoreCase(b) : a.equals(b);
    }

    private static boolean isName(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!isNameChar(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.' || c == ':';
    }

    private static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }
}
