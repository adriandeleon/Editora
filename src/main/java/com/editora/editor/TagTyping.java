package com.editora.editor;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.editora.editops.HtmlCaretRegion;
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
 *
 * <p>In an HTML start tag, an {@code =} typed right after an attribute name brings its quotes: {@code <a class=}
 * gives {@code <a class="|">} (VS Code's {@code html.autoCreateQuotes}; HTML review D10). A {@code "} typed straight
 * after is taken as that opening quote, so a habitual {@code class="x"} still gives {@code class="x"}.
 *
 * <p>The join's edge keystrokes (XML/pom review 3, C22): Backspace while a join is pending takes back the last
 * character the user typed — {@code </} and the name so far, less one, go back as the user's own text before the
 * closer — instead of deleting the closer's own characters (which auto-rename mirrored onto the start tag:
 * {@code <em>text</em>}). A space before the {@code >} keeps the {@code >} stepping over ({@code </item >} no
 * longer leaves {@code >>}); HTML names step over case-insensitively; and the pending join is dropped when the caret
 * moves, so typing after the caret comes back edits the closer as text.
 */
final class TagTyping {

    private static final Pattern CLOSER = Pattern.compile("</[A-Za-z_:][-A-Za-z0-9_.:]*>");

    /** Where the next character to step over is, and the tag's text from there that may still be stepped over. */
    private int at = -1;

    private @Nullable String rest;

    /** Where the joined closer starts (its {@code <}) and its whole text: what an unjoin puts the typing back by. */
    private int joinStart = -1;

    private String joined = "";

    /** The name characters the user typed after the join, as typed (an HTML name may differ in case). */
    private final StringBuilder typedName = new StringBuilder();

    /** Spaces typed before the closer's {@code >} ({@code </item >}): the {@code >} still steps over. */
    private int spaces;

    /** The area the pending join is in (a split view shares this buffer's state), and those watched for moves. */
    private @Nullable CodeArea joinArea;

    private final Set<CodeArea> watched = Collections.newSetFromMap(new WeakHashMap<>());

    /** Where an {@code =""} just put the caret (inside the quotes), and in which area: a {@code "} typed next is
     *  taken as the opening quote the user meant, not a second one. */
    private @Nullable CodeArea quoteArea;

    private int quoteAt = -1;

    /** Handles {@code c} typed at the caret of {@code a} in a {@code language} buffer; true when it did. */
    boolean typed(CodeArea a, char c, String language) {
        boolean html = "html".equals(language);
        if ((!html && !"xml".equals(language)) || a.getSelection().getLength() > 0) {
            clear();
            return false;
        }
        int caret = a.getCaretPosition();
        boolean quoted = a == quoteArea && caret == quoteAt;
        quoteArea = null;
        if (quoted && c == '"') {
            return true; // the habitual `class="`: that quote is the one already there, and the caret is inside it
        }
        String pending = rest;
        if (pending != null && caret == at && a == joinArea) {
            if (same(pending.charAt(0), c, html)) {
                if (pending.length() > 1) {
                    typedName.append(c);
                }
                at = caret + 1;
                rest = pending.length() == 1 ? null : pending.substring(1);
                a.moveTo(at);
                if (rest == null) {
                    clear();
                }
                return true;
            }
            // Only the '>' is left: a space there is still this closer ("</item >"), and the '>' still steps over.
            if (pending.length() == 1 && Character.isWhitespace(c)) {
                spaces++;
                at = caret + 1; // where the space typed as usual leaves the caret
                return false;
            }
            return unjoin(a, caret, c);
        }
        clear();
        if (c == '>') {
            return autoClose(a, caret, html);
        }
        if (c == '/') {
            return joinCloser(a, caret, html);
        }
        if (c == '=') {
            return html && autoQuote(a, caret);
        }
        return isNameChar(c) && rejoin(a, caret, c, html);
    }

    private static boolean isNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.' || c == ':';
    }

    /**
     * Backspace at the caret of {@code a} while a join is pending: a space typed before the {@code >} is deleted and
     * the {@code >} still steps over; otherwise the last character the user typed is taken back — {@code </} and the
     * name so far, less one, go back as a tag of their own before the closer, which is left as it was. True when it
     * did; false (a plain Backspace) when no join is pending here.
     */
    boolean backspace(CodeArea a, String language) {
        String pending = rest;
        int caret = a.getCaretPosition();
        if (pending == null
                || a != joinArea
                || caret != at
                || (!"html".equals(language) && !"xml".equals(language))
                || a.getSelection().getLength() > 0) {
            return false;
        }
        if (spaces > 0) {
            spaces--;
            at = caret - 1; // first, so the caret listener keeps the join
            a.deleteText(caret - 1, caret);
            return true;
        }
        if (!closerIntact(a, caret)) {
            clear();
            return false;
        }
        String own = "</" + typedName;
        own = own.substring(0, own.length() - 1);
        int start = joinStart;
        clear();
        a.replaceText(start, start, own); // holds '<' (and '/'), so auto-rename leaves it alone
        a.moveTo(start + own.length());
        return true;
    }

    private static boolean same(char tag, char typed, boolean html) {
        return tag == typed || (html && Character.toLowerCase(tag) == Character.toLowerCase(typed));
    }

    /** The joined closer is still where the join left it, and the caret is after what the user typed of it. */
    private boolean closerIntact(CodeArea a, int caret) {
        return joinStart >= 0
                && caret == joinStart + 2 + typedName.length()
                && joinStart + joined.length() <= a.getLength()
                && joined.equals(a.getText(joinStart, joinStart + joined.length()));
    }

    private void clear() {
        rest = null;
        at = -1;
        joinStart = -1;
        joined = "";
        typedName.setLength(0);
        spaces = 0;
        joinArea = null;
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

    /** An {@code =} typed after an attribute name in an HTML start tag: {@code =""}, the caret inside (D10). */
    private boolean autoQuote(CodeArea a, int caret) {
        // The wider window: a page's script may be long, and `if (a <b) x =` there is code, not a tag.
        String before = a.getText(Math.max(0, caret - HtmlCaretRegion.MAX_SCAN), caret);
        String after = a.getText(caret, Math.min(a.getLength(), caret + 1));
        if (!HtmlCaretRegion.quotesAfterEquals(before, after)) {
            return false;
        }
        a.replaceText(caret, caret, "=\"\"");
        a.moveTo(caret + 2);
        quoteArea = a;
        quoteAt = caret + 2;
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
        join(a, caret - 1, tag, "");
        return true;
    }

    /**
     * A name character typed right after the user's own {@code </} and partial name that sit before the innermost
     * element's closer, which that name (and {@code c}) begins — what a Backspace during a join leaves: the partial
     * tag is taken back and the join goes on inside the closer, so {@code </it}, Backspace, {@code tem>} closes
     * {@code <item>} once.
     */
    private boolean rejoin(CodeArea a, int caret, char c, boolean html) {
        int from = Math.max(0, caret - 128);
        String before = a.getText(from, caret);
        int i = before.length();
        while (i > 0 && isNameChar(before.charAt(i - 1))) {
            i--;
        }
        if (i < 2 || before.charAt(i - 1) != '/' || before.charAt(i - 2) != '<' || caret >= a.getLength()) {
            return false;
        }
        Matcher closer = CLOSER.matcher(a.getText(caret, Math.min(a.getLength(), caret + 256)));
        if (!closer.lookingAt()) {
            return false;
        }
        String tag = closer.group();
        String name = tag.substring(2, tag.length() - 1);
        String want = before.substring(i) + c;
        if (want.length() > name.length() || !name.regionMatches(html, 0, want, 0, want.length())) {
            return false;
        }
        int start = from + i - 2; // the user's own "</"
        if (!TagJoin.closesInnermost(a.getText(Math.max(0, start - TagJoin.MAX_SCAN), start), name, html)) {
            return false;
        }
        a.deleteText(start, caret); // the partial tag gives way to the closer it begins
        join(a, start, tag, want);
        return true;
    }

    /** Starts a pending join of the closer {@code tag} at {@code start}, {@code typed} of its name stepped over. */
    private void join(CodeArea a, int start, String tag, String typed) {
        if (watched.add(a)) { // a caret the user moves drops the pending join (C22)
            a.caretPositionProperty().addListener((o, was, now) -> {
                if (a == joinArea && now.intValue() != at) {
                    clear();
                }
            });
        }
        joinStart = start;
        joined = tag;
        joinArea = a;
        typedName.append(typed);
        at = start + 2 + typed.length(); // after "</" and what is stepped over
        rest = tag.substring(2 + typed.length());
        a.moveTo(at);
    }

    /**
     * {@code c} is not the joined closer's next character: puts what the user typed — {@code </}, the name so far
     * and {@code c} — back as a tag of its own before the closer, which stays as it was. Declines (and {@code c} is
     * typed as usual) when the closer is no longer where the join left it.
     */
    private boolean unjoin(CodeArea a, int caret, char c) {
        boolean intact = spaces == 0 && closerIntact(a, caret);
        String own = "</" + typedName + c;
        int start = joinStart;
        clear();
        if (!intact) {
            return false;
        }
        a.replaceText(start, start, own); // holds '<' and '/', so auto-rename leaves it alone
        a.moveTo(start + own.length());
        return true;
    }
}
