package com.editora.editops;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Pure: whether a close tag the user starts typing by hand may join the {@code </name>} already after the caret
 * (XML review 2 C2). The join is for the habit of typing {@code </item>} although auto-close already put it
 * there; it must only take the closer of the element the user is actually closing — the innermost one still
 * open. Joining any closer that happened to follow the caret turned {@code <p>Hello <b>world|</p>} plus a typed
 * {@code </b>} into {@code <bp>Hello <b>world</b>p>}: the {@code b} landed inside {@code </p>}'s name, and
 * auto-rename mirrored it onto {@code <p>}.
 *
 * <p>One forward lex over a bounded window before the caret keeps a stack of open elements, skipping comments,
 * CDATA, processing instructions, declarations, quoted attribute values and self-closing tags, and in HTML void
 * elements and raw-text content. No toolkit dependency; {@code TagTyping} applies it.
 */
public final class TagJoin {

    /** How much text before the caret is read (bounds the scan; it runs only on a {@code /} typed after {@code <}). */
    public static final int MAX_SCAN = 100_000;

    /** HTML elements whose end tag may be left out: an open one does not stop a closer of its parent joining. */
    static final Set<String> OPTIONAL_END_TAG = Set.of(
            "body",
            "caption",
            "colgroup",
            "dd",
            "dt",
            "head",
            "html",
            "li",
            "optgroup",
            "option",
            "p",
            "rp",
            "rt",
            "tbody",
            "td",
            "tfoot",
            "th",
            "thead",
            "tr");

    private TagJoin() {}

    /**
     * Whether {@code closerName} closes the innermost element still open at the end of {@code beforeCaret} (the
     * text before the typed {@code <}; at most the last {@link #MAX_SCAN} characters). True as well when no
     * element is open in the window — it may have started before it, and a join the user does not complete is put
     * back anyway. In HTML an open element whose end tag is optional ({@code <li>}, {@code <p>}, …) is passed
     * over.
     */
    public static boolean closesInnermost(String beforeCaret, String closerName, boolean html) {
        List<String> open = openElements(beforeCaret, html);
        if (open.isEmpty()) {
            return true;
        }
        for (int k = open.size() - 1; k >= 0; k--) {
            String name = open.get(k);
            if (html ? name.equalsIgnoreCase(closerName) : name.equals(closerName)) {
                return true;
            }
            if (!html || !OPTIONAL_END_TAG.contains(name.toLowerCase(Locale.ROOT))) {
                return false;
            }
        }
        return false;
    }

    /** The elements open at the end of {@code text}, outermost first. */
    static List<String> openElements(String text, boolean html) {
        List<String> stack = new ArrayList<>();
        int n = text.length();
        int i = 0;
        while (i < n) {
            if (text.charAt(i) != '<') {
                i++;
            } else if (text.startsWith("<!--", i)) {
                i = TagRename.skipTo(text, i + 4, "-->");
            } else if (text.startsWith("<![CDATA[", i)) {
                i = TagRename.skipTo(text, i + 9, "]]>");
            } else if (text.startsWith("<?", i)) {
                i = TagRename.skipTo(text, i + 2, "?>");
            } else if (text.startsWith("<!", i)) {
                i = TagRename.skipTo(text, i + 2, ">");
            } else if (text.startsWith("</", i)) {
                int nameEnd = TagRename.nameEnd(text, i + 2);
                pop(stack, text.substring(i + 2, nameEnd), html);
                i = TagRename.skipTag(text, nameEnd)[0];
            } else if (i + 1 < n && TagRename.isNameChar(text.charAt(i + 1))) {
                int nameEnd = TagRename.nameEnd(text, i + 1);
                String name = text.substring(i + 1, nameEnd);
                int[] end = TagRename.skipTag(text, nameEnd);
                boolean closed = end[0] > nameEnd && text.charAt(end[0] - 1) == '>';
                String lower = name.toLowerCase(Locale.ROOT);
                if (closed && end[1] == 0 && !(html && TagRename.VOID_ELEMENTS.contains(lower))) {
                    stack.add(name);
                    if (html && TagRename.RAW_TEXT_ELEMENTS.contains(lower)) {
                        i = TagRename.skipToCloseTag(text, end[0], name); // script/style content is not markup
                        continue;
                    }
                }
                i = end[0];
            } else {
                i++; // a '<' in text
            }
        }
        return stack;
    }

    /** Closes the innermost open element named {@code name} and any left open inside it; a stray closer is ignored. */
    private static void pop(List<String> stack, String name, boolean html) {
        for (int k = stack.size() - 1; k >= 0; k--) {
            String open = stack.get(k);
            if (html ? open.equalsIgnoreCase(name) : open.equals(name)) {
                stack.subList(k, stack.size()).clear();
                return;
            }
        }
    }
}
