package com.editora.markdown;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure (no-toolkit, unit-tested) answer to "what is being typed here?" for the places in Markdown source
 * where there is something better to offer than a dictionary word: a link's target path, the anchor after
 * its {@code #}, a reference label, a fence's language, an emoji shortcode.
 *
 * <p>Decided from the caret's line alone (the text before the caret, and the whole line to rule out a code
 * span); whether the line is Markdown at all — not code, front matter or raw HTML — is the caller's to check
 * with {@link MarkdownBlocks}.
 */
public final class MarkdownCompletionContext {

    /** What is being typed. */
    public enum Kind {
        /** A link or image target: {@code [text](docs/gu}. */
        PATH,
        /** The fragment of a link target: {@code [text](#inst} or {@code [text](guide.md#inst}. */
        ANCHOR,
        /** A reference label: {@code [text][lab} or {@code [lab}. */
        REFERENCE,
        /** The info string of an opening fence: {@code ```jav}. */
        FENCE_LANGUAGE,
        /** An emoji shortcode: {@code :smi}. */
        EMOJI,
        /** A wiki link's note: {@code [[not}. */
        WIKI,
        /** A heading of a wiki link's note: {@code [[note#hea} ({@code file} is the note, "" = this document). */
        WIKI_HEADING
    }

    /**
     * @param start the column in the line where the text being typed ({@code partial}) starts — what an
     *     accepted completion replaces, up to the caret
     * @param partial the text typed so far (may be empty)
     * @param file for {@link Kind#ANCHOR}, the target's file part as written ("" = this document)
     * @param angle for a target written {@code (<…>)}: spaces need no escaping
     */
    public record Context(Kind kind, int start, String partial, String file, boolean angle) {}

    private static final Pattern FENCE = Pattern.compile("^ {0,3}(?:`{3,}|~{3,})([\\w+#.-]*)$");
    private static final Pattern EMOJI = Pattern.compile("(?:^|[\\s(\\[>*_])(:([a-z0-9_+-]{2,}))$");
    private static final Pattern SCHEME = Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]+:.*");

    private MarkdownCompletionContext() {}

    /**
     * The context at {@code column} of {@code line}, or null when nothing structural is being typed there
     * (ordinary prose, a web address, inside a code span).
     */
    public static Context at(String line, int column) {
        if (line == null || column < 0 || column > line.length()) {
            return null;
        }
        String before = line.substring(0, column);
        Matcher fence = FENCE.matcher(before);
        if (fence.matches()) {
            return new Context(Kind.FENCE_LANGUAGE, fence.start(1), fence.group(1), "", false);
        }
        if (column > 0 && MarkdownBlocks.inRanges(MarkdownBlocks.codeSpans(line), column - 1)) {
            return null; // in a code span: shown as written
        }
        Context wiki = wikiLink(before);
        if (wiki != null) {
            // "[[" written inside a code span that closed before the caret is not the start of a link.
            int open = before.lastIndexOf("[[");
            return MarkdownBlocks.inRanges(MarkdownBlocks.codeSpans(line), open) ? null : wiki;
        }
        Context target = linkTarget(before);
        if (target != null) {
            return target;
        }
        Context reference = reference(before);
        if (reference != null) {
            return reference;
        }
        Matcher emoji = EMOJI.matcher(before);
        if (emoji.find()) {
            return new Context(Kind.EMOJI, emoji.start(1), emoji.group(2), "", false);
        }
        return null;
    }

    /** {@code …[[note} or {@code …[[note#heading} with the caret before any {@code |} or {@code ]]}. */
    private static Context wikiLink(String before) {
        int open = before.lastIndexOf("[[");
        if (open < 0 || (open > 0 && before.charAt(open - 1) == '\\')) {
            return null;
        }
        String typed = before.substring(open + 2);
        if (typed.indexOf(']') >= 0 || typed.indexOf('[') >= 0 || typed.indexOf('|') >= 0) {
            return null;
        }
        int hash = typed.indexOf('#');
        if (hash < 0) {
            return new Context(Kind.WIKI, open + 2, typed, "", false);
        }
        return new Context(
                Kind.WIKI_HEADING,
                open + 2 + hash + 1,
                typed.substring(hash + 1),
                typed.substring(0, hash).strip(),
                false);
    }

    /** {@code …](target} with the caret still inside the target. */
    private static Context linkTarget(String before) {
        int open = before.lastIndexOf("](");
        if (open < 0) {
            return null;
        }
        int start = open + 2;
        boolean angle = start < before.length() && before.charAt(start) == '<';
        if (angle) {
            start++;
        }
        String target = before.substring(start);
        for (int i = 0; i < target.length(); i++) {
            char c = target.charAt(i);
            if (c == ')' || c == '>' || c == '<' || c == '"' || (!angle && Character.isWhitespace(c))) {
                return null; // the target is finished (or this is its title)
            }
        }
        if (SCHEME.matcher(target).matches() || target.startsWith("//")) {
            return null; // a web address: nothing local to offer
        }
        int hash = target.indexOf('#');
        if (hash >= 0) {
            return new Context(
                    Kind.ANCHOR, start + hash + 1, target.substring(hash + 1), target.substring(0, hash), angle);
        }
        return new Context(Kind.PATH, start, target, "", angle);
    }

    /** {@code [text][lab} always; a bare {@code [lab} once a character is typed (it may be a link's text). */
    private static Context reference(String before) {
        int open = before.lastIndexOf('[');
        if (open < 0) {
            return null;
        }
        String partial = before.substring(open + 1);
        if (partial.indexOf(']') >= 0 || partial.indexOf('(') >= 0 || partial.startsWith("^")) {
            return null;
        }
        boolean second = open > 0 && before.charAt(open - 1) == ']';
        boolean escaped = open > 0 && before.charAt(open - 1) == '\\';
        if (escaped || (!second && (partial.isEmpty() || partial.equals(" ") || partial.equalsIgnoreCase("x")))) {
            return null; // "\[", a bare "[", or a task box being typed
        }
        return new Context(Kind.REFERENCE, open + 1, partial, "", false);
    }
}
