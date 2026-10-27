package com.editora.editor;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.editora.editops.TagAutoClose;
import com.editora.editops.TagJoin;
import org.fxmisc.richtext.CodeArea;
import org.jspecify.annotations.Nullable;

/**
 * Tag auto-close as typed in an HTML or XML buffer (the buffer calls it only while the setting is on).
 *
 * <p>Typing the {@code >} that completes an open tag inserts {@code </name>} after the caret (the VS Code
 * auto-closing-tags behavior), leaving the caret between the tags; the decision is the pure {@link
 * TagAutoClose} over a bounded window before the caret, so the per-keystroke cost is one short backward scan.
 *
 * <p>Typing that close tag by hand anyway (XML review B17): a habitual {@code x</from>} left {@code
 * <from>x</</from>}. Now the {@code /} typed right after a {@code <} that sits before an existing {@code
 * </name>} joins that tag instead — the stray {@code <} is taken back and the caret lands after the tag's
 * {@code </} — and the name and {@code >} typed next step over the tag's own characters, as a typed closing
 * bracket steps over the auto-inserted one.
 *
 * <p>Only the closer of the innermost open element is joined, and a join the user does not complete is put back
 * (XML review 2 C2): the first character that differs from the closer's own — a typo, or the name of another
 * element — re-inserts the {@code </} and the name typed so far as a tag of their own before the closer, which is
 * left as it was. Before, that character landed inside the closer's name and auto-rename mirrored it onto the
 * start tag: {@code <item>t|</item>} plus {@code </itme>} gave {@code <itmeem>t</itme>em>}.
 */
final class TagTyping {

    private static final Pattern CLOSER = Pattern.compile("</[A-Za-z_:][-A-Za-z0-9_.:]*>");

    /** Where the next character to step over is, and the tag's text from there that may still be stepped over. */
    private int at = -1;

    private @Nullable String rest;

    /** Where the joined closer starts (its {@code <}) and its whole text: what an unjoin puts the typing back by. */
    private int joinStart = -1;

    private String joined = "";

    /** Handles {@code c} typed at the caret of {@code a} in a {@code language} buffer; true when it did. */
    boolean typed(CodeArea a, char c, String language) {
        boolean html = "html".equals(language);
        if ((!html && !"xml".equals(language)) || a.getSelection().getLength() > 0) {
            rest = null;
            return false;
        }
        int caret = a.getCaretPosition();
        String pending = rest;
        rest = null;
        if (pending != null && caret == at) {
            if (pending.charAt(0) == c) {
                at = caret + 1;
                rest = pending.length() == 1 ? null : pending.substring(1);
                a.moveTo(at);
                return true;
            }
            // Only the '>' is left: a space there is still this closer ("</item >"); anything else is not it.
            if (pending.length() > 1 || !Character.isWhitespace(c)) {
                return unjoin(a, caret, c);
            }
        }
        if (c == '>') {
            return autoClose(a, caret, html);
        }
        return c == '/' && joinCloser(a, caret, html);
    }

    private static boolean autoClose(CodeArea a, int caret, boolean html) {
        int from = Math.max(0, caret - TagAutoClose.MAX_TAG_SCAN);
        String closer = TagAutoClose.closer(a.getText(from, caret), html);
        if (closer == null) {
            return false;
        }
        a.replaceText(caret, caret, ">" + closer);
        a.moveTo(caret + 1);
        return true;
    }

    /** A {@code /} typed after {@code <} right before an existing {@code </name>}: step into that tag. */
    private boolean joinCloser(CodeArea a, int caret, boolean html) {
        if (caret == 0 || a.getText(caret - 1, caret).charAt(0) != '<') {
            return false;
        }
        Matcher closer = CLOSER.matcher(a.getText(caret, Math.min(a.getLength(), caret + 256)));
        if (!closer.lookingAt()) {
            return false;
        }
        String tag = closer.group();
        int from = Math.max(0, caret - 1 - TagJoin.MAX_SCAN);
        if (!TagJoin.closesInnermost(a.getText(from, caret - 1), tag.substring(2, tag.length() - 1), html)) {
            return false; // the closer of an outer element: "</" is the start of the user's own tag
        }
        a.deleteText(caret - 1, caret); // the typed '<' joins the existing "</"
        joinStart = caret - 1;
        joined = tag;
        at = caret + 1; // just after that "</"
        rest = tag.substring(2);
        a.moveTo(at);
        return true;
    }

    /**
     * {@code c} is not the joined closer's next character: puts what the user typed — {@code </}, the name so far
     * and {@code c} — back as a tag of its own before the closer, which stays as it was. Declines (and {@code c} is
     * typed as usual) when the closer is no longer where the join left it.
     */
    private boolean unjoin(CodeArea a, int caret, char c) {
        int typed = caret - joinStart;
        if (joinStart < 0
                || typed < 2
                || typed > joined.length()
                || joinStart + joined.length() > a.getLength()
                || !joined.equals(a.getText(joinStart, joinStart + joined.length()))) {
            return false;
        }
        String own = joined.substring(0, typed) + c;
        a.replaceText(joinStart, joinStart, own); // holds '<' and '/', so auto-rename leaves it alone
        a.moveTo(joinStart + own.length());
        joinStart = -1;
        return true;
    }
}
