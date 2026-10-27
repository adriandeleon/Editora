package com.editora.editor;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.editora.editops.TagAutoClose;
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
 * bracket steps over the auto-inserted one. Any other character typed there ends the step-over.
 */
final class TagTyping {

    private static final Pattern CLOSER = Pattern.compile("</[A-Za-z_:][-A-Za-z0-9_.:]*>");

    /** Where the next character to step over is, and the tag's text from there that may still be stepped over. */
    private int at = -1;

    private @Nullable String rest;

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
        if (pending != null && caret == at && pending.charAt(0) == c) {
            at = caret + 1;
            rest = pending.length() == 1 ? null : pending.substring(1);
            a.moveTo(at);
            return true;
        }
        if (c == '>') {
            return autoClose(a, caret, html);
        }
        return c == '/' && joinCloser(a, caret);
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
    private boolean joinCloser(CodeArea a, int caret) {
        if (caret == 0 || a.getText(caret - 1, caret).charAt(0) != '<') {
            return false;
        }
        Matcher closer = CLOSER.matcher(a.getText(caret, Math.min(a.getLength(), caret + 256)));
        if (!closer.lookingAt()) {
            return false;
        }
        a.deleteText(caret - 1, caret); // the typed '<' joins the existing "</"
        at = caret + 1; // just after that "</"
        rest = closer.group().substring(2);
        a.moveTo(at);
        return true;
    }
}
