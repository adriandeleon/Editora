package com.editora.snippet;

import java.util.ArrayList;
import java.util.List;

import javafx.application.Platform;
import javafx.event.Event;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.PlainTextChange;
import org.reactfx.Subscription;

/**
 * One active snippet expansion bound to a {@link CodeArea}: it inserts the parsed text, tracks each
 * tab stop's document ranges as the user edits, mirrors the active field into its other occurrences
 * live, and steps through the stops with {@link #next()}/{@link #previous()} until {@code $0}.
 *
 * <p>Field offsets are kept in sync with edits via a {@code plainTextChanges} subscription using the
 * pure {@link #shift} arithmetic; programmatic mirror edits are guarded by {@link #applying} to avoid
 * reentrancy.
 */
public final class SnippetSession {

    private final CodeArea area;
    private final List<Field> fields = new ArrayList<>(); // ordered: 1,2,… then $0 last
    private final int[] finalRange; // $0 caret (or end of insert); shifts with edits
    private int active = -1;
    private Subscription sub;
    private boolean applying;
    private boolean ended;
    private boolean completed;
    private boolean suspended;
    private boolean externalEdit;
    private Runnable onEnd = () -> {};
    private java.util.function.Function<CodeArea, Runnable> undoJoin = a -> () -> {};
    private ContextMenu choiceMenu;

    /**
     * One tab stop's live document ranges. {@code ranges.get(primaryIdx)} is the editable field; the rest
     * mirror it. {@code transforms} runs parallel to {@code ranges}: a non-null entry derives that
     * occurrence's text from the primary via a regex transform instead of copying it verbatim (the primary
     * entry is always null).
     */
    private static final class Field {
        final int number;
        final List<int[]> ranges;
        final List<String> choices;
        final List<SnippetTransform> transforms;
        final int primaryIdx;
        /** Set once the field's text was deleted with an enclosing field's: there is nothing left to visit. */
        boolean retired;

        Field(int number, List<int[]> ranges, List<String> choices, List<SnippetTransform> transforms, int primaryIdx) {
            this.number = number;
            this.ranges = ranges;
            this.choices = choices;
            this.transforms = transforms;
            this.primaryIdx = primaryIdx;
        }

        int[] primary() {
            return ranges.get(primaryIdx);
        }

        /** The text occurrence {@code i} should show given the primary's current {@code value}. */
        String textFor(int i, String value) {
            SnippetTransform t = i < transforms.size() ? transforms.get(i) : null;
            return t == null ? value : t.apply(value);
        }

        boolean hasTransforms() {
            for (SnippetTransform t : transforms) {
                if (t != null) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Inserts {@code parsed} (re-indented to {@code indent}) over {@code [from, to)} and starts tracking.
     * Returns immediately with the first field selected; if the snippet has no editable fields it just
     * places the caret and ends.
     */
    public SnippetSession(CodeArea area, ParsedSnippet parsed, int from, int to, String indent) {
        this(area, parsed, from, to, indent, null);
    }

    /**
     * As above, converting the snippet's own indentation to {@code indentUnit} (the buffer's unit — a run of
     * spaces or a tab) first; {@code null} keeps the body's tabs. Line endings are always normalised: the
     * area stores a CRLF as one character, so offsets taken from text that still holds one would be late.
     */
    public SnippetSession(CodeArea area, ParsedSnippet parsed, int from, int to, String indent, String indentUnit) {
        this.area = area;
        ParsedSnippet p = reindent(normalize(parsed, indentUnit), indent == null ? "" : indent);
        area.replaceText(from, to, p.text());

        int end = from + p.text().length();
        // Each tracked range is {start, end, open, close}: the offsets, then the parser's nesting order
        // (TabStop.spans). An implicit $0 at the end of the insert comes after everything.
        int[] dollarZero = {end, end, Integer.MAX_VALUE - 1, Integer.MAX_VALUE};
        boolean structured = true;
        for (TabStop s : p.stops()) {
            List<int[]> spans =
                    s.spans() != null && s.spans().size() == s.ranges().size() ? s.spans() : null;
            structured &= spans != null;
            List<int[]> abs = new ArrayList<>();
            for (int k = 0; k < s.ranges().size(); k++) {
                int[] r = s.ranges().get(k);
                int[] span = spans == null ? new int[2] : spans.get(k);
                abs.add(new int[] {from + r[0], from + r[1], span[0], span[1]});
            }
            if (s.isFinal()) {
                dollarZero = abs.get(0);
            } else {
                fields.add(new Field(s.number(), abs, s.choices(), s.transforms(), s.primaryIndex()));
            }
        }
        this.finalRange = dollarZero;
        if (!structured) { // stops not built by the parser: infer the nesting from where they start out
            List<int[]> all = allRanges();
            int[][] inferred = inferSpans(all);
            for (int k = 0; k < all.size(); k++) {
                all.get(k)[2] = inferred[k][0];
                all.get(k)[3] = inferred[k][1];
            }
        }

        if (fields.isEmpty()) {
            placeFinalCaret();
            ended = true;
            return;
        }
        sub = area.plainTextChanges().subscribe(this::onChange);
        active = 0;
        selectActive();
    }

    public void setOnEnd(Runnable onEnd) {
        this.onEnd = onEnd == null ? () -> {} : onEnd;
    }

    /** See {@link SnippetSessions#SnippetSessions(java.util.function.Function)}. */
    void setUndoJoin(java.util.function.Function<CodeArea, Runnable> undoJoin) {
        this.undoJoin = undoJoin == null ? a -> () -> {} : undoJoin;
    }

    boolean completed() {
        return completed;
    }

    public boolean isActive() {
        return !ended;
    }

    /** A child expansion may replace text only inside this session's current field and view. */
    public boolean suspendForChild(CodeArea target, int from, int to) {
        if (ended || target != area || active < 0) return false;
        int[] field = fields.get(active).primary();
        if (from < field[0] || to > field[1] || from > to) return false;
        suspended = true;
        hideChoiceMenu();
        return true;
    }

    public void resume() {
        if (ended) return;
        suspended = false;
        Field field = fields.get(active);
        if (field.ranges.size() > 1) mirrorInto(field, true);
    }

    public void setExternalEdit(boolean value) {
        externalEdit = value;
    }

    /** Advances to the next stop; past the last one, jumps to {@code $0} and ends. */
    public void next() {
        if (ended) {
            return;
        }
        int target = active + 1;
        while (target < fields.size() && fields.get(target).retired) {
            target++;
        }
        if (target < fields.size()) {
            active = target;
            selectActive();
        } else {
            finish();
        }
    }

    /** Goes back to the previous stop (no-op at the first). */
    public void previous() {
        if (ended) {
            return;
        }
        int target = active - 1;
        while (target >= 0 && fields.get(target).retired) {
            target--;
        }
        if (target >= 0) {
            active = target;
            selectActive();
        }
    }

    /** Ends the session, placing the caret at {@code $0}. */
    public void finish() {
        if (ended) {
            return;
        }
        completed = true;
        endSession();
        placeFinalCaret();
        area.requestFollowCaret();
        onEnd.run();
    }

    /**
     * Puts the caret on {@code $0} — <b>selecting</b> its text when the stop carries a default
     * ({@code ${0:*}}), so the next keystroke replaces it.
     *
     * <p>A bare {@code $0} is a caret position and nothing more, but a defaulted one is a placeholder like
     * any other; it is only "final" in that there is no stop after it. jdtls leans on exactly that for
     * imports — {@code java.util.${0:*};} means "here is the on-demand form, with the {@code *} ready to
     * be typed over" — so leaving the caret merely <em>before</em> the text hands the user a stray
     * {@code *} to delete, which is how the whole proposal came to look useless.
     */
    private void placeFinalCaret() {
        int start = Math.min(finalRange[0], area.getLength());
        int end = Math.min(finalRange[1], area.getLength());
        if (end > start) {
            area.selectRange(start, end);
        } else {
            area.moveTo(start);
        }
    }

    /** Ends the session without moving the caret (e.g. user pressed Esc or edited elsewhere). */
    public void cancel() {
        if (!ended) {
            endSession();
            onEnd.run();
        }
    }

    private void endSession() {
        ended = true;
        hideChoiceMenu();
        if (sub != null) {
            sub.unsubscribe();
            sub = null;
        }
    }

    private void selectActive() {
        hideChoiceMenu();
        Field f = fields.get(active);
        int[] r = f.primary();
        // Clamped like placeFinalCaret: an out-of-range selection is stored before RichTextFX rejects it.
        int len = area.getLength();
        area.selectRange(Math.max(0, Math.min(r[0], len)), Math.max(0, Math.min(r[1], len)));
        area.requestFollowCaret();
        if (!f.choices.isEmpty()) {
            showChoices(f);
        }
    }

    /** Shows a dropdown of a choice field's options; picking one fills (and mirrors) the field. */
    private void showChoices(Field f) {
        ContextMenu menu = new ContextMenu();
        for (String option : f.choices) {
            MenuItem item = new MenuItem(option);
            item.setOnAction(e -> applyChoice(option));
            menu.getItems().add(item);
        }
        choiceMenu = menu;
        int[] r = f.primary();
        // Defer so the selection's layout is settled before we query on-screen bounds.
        Platform.runLater(() -> {
            if (ended || choiceMenu != menu) {
                return;
            }
            java.util.Optional<Bounds> bounds = area.getCharacterBoundsOnScreen(r[0], Math.max(r[0] + 1, r[1]));
            if (bounds.isPresent()) {
                menu.show(area, bounds.get().getMinX(), bounds.get().getMaxY());
            } else {
                menu.show(area, javafx.geometry.Side.BOTTOM, 0, 0);
            }
            installChoiceKeys(menu);
        });
    }

    /**
     * Adds the editor's completion-style chords to the choice popup so it's driven the same way as
     * autocomplete: {@code C-n}/{@code C-p} move (= Down/Up), {@code Tab} accepts (= Enter), {@code C-g}
     * cancels (= leave the default). The popup already handles Down/Up/Enter/Esc natively.
     */
    private void installChoiceKeys(ContextMenu menu) {
        Scene sc = menu.getScene();
        if (sc == null) {
            return;
        }
        sc.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.isControlDown() && e.getCode() == KeyCode.N) {
                redispatch(menu, KeyCode.DOWN);
                e.consume();
            } else if (e.isControlDown() && e.getCode() == KeyCode.P) {
                redispatch(menu, KeyCode.UP);
                e.consume();
            } else if (e.getCode() == KeyCode.TAB) {
                redispatch(menu, KeyCode.ENTER); // accept the highlighted option
                e.consume();
            } else if (e.isControlDown() && e.getCode() == KeyCode.G) {
                hideChoiceMenu(); // cancel — keep the field's current/default text
                area.requestFocus();
                e.consume();
            }
        });
    }

    /** Re-fires {@code code} as a synthetic key press so the popup's native navigation/accept handler runs. */
    private static void redispatch(ContextMenu menu, KeyCode code) {
        Scene sc = menu.getScene();
        if (sc == null) {
            return;
        }
        // The ContextMenu skin's node (ContextMenuContent) owns the Down/Up/Enter behavior; fire at it so the
        // event bubbles through that handler. Fall back to the focus owner / scene root.
        Node target = menu.getSkin() != null ? menu.getSkin().getNode() : null;
        if (target == null) {
            target = sc.getFocusOwner() != null ? sc.getFocusOwner() : sc.getRoot();
        }
        Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false));
    }

    private void applyChoice(String option) {
        hideChoiceMenu();
        if (ended) {
            return;
        }
        int[] p = fields.get(active).primary();
        // A normal (un-guarded) replace so onChange updates the ranges and mirrors the choice.
        area.replaceText(p[0], p[1], option);
        area.selectRange(p[0], p[1]);
        area.requestFocus();
    }

    private void hideChoiceMenu() {
        if (choiceMenu != null) {
            choiceMenu.hide();
            choiceMenu = null;
        }
    }

    private void onChange(PlainTextChange change) {
        if (applying || ended) {
            return;
        }
        // An undo/redo is rewriting the document (e.g. reverting a mirrored-field edit, now a single undo unit
        // via replaceInActiveField): end the session cleanly rather than treat the revert as the user leaving
        // the field and, worse, fire a re-entrant mirror replaceText mid-undo. The document is left consistent
        // (fully reverted), just no longer tracked (#415).
        if (area.getUndoManager().isPerformingAction()) {
            cancel();
            return;
        }
        int pos = change.getPosition();
        int removed = change.getRemoved().length();
        int inserted = change.getInserted().length();
        int delta = inserted - removed;

        if (externalEdit) {
            List<int[]> ranges = allRanges();
            for (int[] range : ranges) {
                if (pos < range[1] && pos + removed > range[0] || removed == 0 && pos > range[0] && pos < range[1]) {
                    cancel();
                    return;
                }
            }
            for (int[] range : ranges) {
                if (range[0] >= pos + removed) {
                    range[0] += delta;
                    range[1] += delta;
                }
            }
            return;
        }

        int[] primary = fields.get(active).primary();
        // An edit outside the active field (the user moved away) ends the snippet.
        if (pos < primary[0] || pos > primary[1] || pos + removed > primary[1]) {
            cancel();
            return;
        }
        retireSwallowed(primary, pos, removed);
        // Grow/shrink the active field and shift everything after the edit.
        shift(allRanges(), indexOf(primary), pos, removed, inserted);
        if (!suspended) mirrorActive();
    }

    /**
     * Retires every field whose whole text an edit of the active field ({@code primary}) just deleted: typing
     * over {@code ${2: ${3:Exception} as ${4:e}}} removes {@code $3} and {@code $4} with it, and there is
     * nothing left for Tab to visit. Only a field nested <em>in</em> the active one can be swallowed: a field
     * that contains it ({@code ${2:${1:foo}}}, the same offsets) just shrinks with the edit. An empty stop
     * sitting exactly at the edit position is left alone too.
     */
    private void retireSwallowed(int[] primary, int pos, int removed) {
        if (removed <= 0) {
            return;
        }
        for (Field f : fields) {
            int[] r = f.primary();
            if (r != primary
                    && r[2] > primary[2]
                    && r[3] < primary[3]
                    && swallowed(r, pos, removed)
                    && (r[1] > r[0] || r[0] > pos)) {
                f.retired = true;
            }
        }
    }

    /**
     * Applies an edit that replaces {@code [from, to)} (which must lie within the active field's primary) with
     * {@code replacement}, updating the primary <b>and every mirror</b> as a <b>single undo unit</b> — so one
     * Ctrl-Z reverts the field and its mirrors together instead of leaving a half-reverted document, and the
     * session stays consistent (#415). Returns {@code false} (edit not handled, caller does it normally) when
     * there's no active session, the edit falls outside the active field, or the field has <b>no mirror</b> — a
     * single-occurrence field's reactive edit is already one undo unit, so intercepting it buys nothing.
     *
     * <p>The reactive {@link #mirrorActive} path (an after-the-fact {@code replaceText}) is what produced the
     * separate undo step; this intercepts the edit <em>before</em> it commits and applies the primary edit +
     * mirror replacements atomically via a {@code MultiChangeBuilder}. Field ranges are then re-derived with the
     * same pure {@link #shift} arithmetic (ascending, cumulative delta), each edit growing its own target range.
     */
    public boolean replaceInActiveField(int from, int to, String replacement) {
        if (ended || suspended || active < 0 || replacement == null) {
            return false;
        }
        Field f = fields.get(active);
        if (f.ranges.size() < 2) {
            return false; // no mirror → the reactive edit is already one undo unit
        }
        int[] p = f.primary();
        if (from < p[0] || to > p[1] || from > to) {
            return false; // the edit isn't confined to the active field
        }
        String oldPrimary = area.getText(p[0], p[1]);
        int relFrom = from - p[0];
        String newPrimary = oldPrimary.substring(0, relFrom) + replacement + oldPrimary.substring(to - p[0]);

        // Occurrence indices (into f.ranges/f.transforms) in document order — the primary may be neither
        // leftmost nor first.
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < f.ranges.size(); i++) {
            order.add(i);
        }
        order.sort((x, y) -> Integer.compare(f.ranges.get(x)[0], f.ranges.get(y)[0]));

        // Reconstruct the whole span from the first occurrence to the last: each occurrence becomes its own
        // derived text (the value verbatim, or a transform of it), and the literal text between occurrences is
        // copied verbatim. Applying it as ONE replaceText makes the field edit + all its mirrors a single undo
        // unit (#415) and avoids mutating the document from inside the outer edit's change event — the reason a
        // reactive mirror cannot safely rewrite text ahead of the caret. (Other fields between occurrences keep
        // their text via the verbatim copy; their ranges are shifted by the arithmetic below.)
        StringBuilder rebuilt = new StringBuilder();
        for (int k = 0; k < order.size(); k++) {
            int i = order.get(k);
            rebuilt.append(f.textFor(i, newPrimary));
            if (k + 1 < order.size()) {
                rebuilt.append(area.getText(f.ranges.get(i)[1], f.ranges.get(order.get(k + 1))[0]));
            }
        }
        int spanStart = f.ranges.get(order.get(0))[0];
        int spanEnd = f.ranges.get(order.get(order.size() - 1))[1];
        applying = true;
        try {
            area.replaceText(spanStart, spanEnd, rebuilt.toString());
        } finally {
            applying = false;
        }

        // Re-derive every tracked range: each occurrence (in document order) changes from its old length to its
        // derived text's length. shift() mutates the arrays in place, so by the time we reach a later occurrence
        // its start is already in current coordinates — read o[0] directly (no cumulative-delta double-count).
        // The primary is described by the edit the user actually made inside it, so ranges nested in the
        // untouched part of the field keep their place; a mirror is rewritten whole.
        retireSwallowed(p, from, to - from);
        List<int[]> all = allRanges();
        for (int i : order) {
            int[] o = f.ranges.get(i);
            if (o == p) {
                shift(all, all.indexOf(o), o[0] + relFrom, to - from, replacement.length());
            } else {
                shift(
                        all,
                        all.indexOf(o),
                        o[0],
                        o[1] - o[0],
                        f.textFor(i, newPrimary).length());
            }
        }
        int caret = p[0] + relFrom + replacement.length(); // p[0] was shifted in place by the loop above
        area.moveTo(Math.min(caret, area.getLength()));
        return true;
    }

    /**
     * Reactively mirrors the active field's value into its other occurrences after a non-typed edit (paste,
     * backspace); typed characters take the atomic {@link #replaceInActiveField} path and never reach here.
     *
     * <p>When every mirror sits <em>after</em> the field, the rewrite is done synchronously inside the
     * triggering change event, exactly as before — no behaviour change for an ordinary snippet. But a mirror
     * <em>before</em> the field (a leading transform occurrence, or a bare {@code $1} ahead of the value
     * placeholder) rewrites text ahead of the caret, and doing that from within the outer edit's change event
     * corrupts that edit's own caret placement. Those defer to {@link #mirrorDeferred} so the outer edit
     * commits first.
     */
    private void mirrorActive() {
        Field f = fields.get(active);
        if (f.ranges.size() < 2) {
            return;
        }
        if (mirrorPrecedesCaret(f)) {
            Platform.runLater(this::mirrorDeferred);
        } else {
            mirrorInto(f, false);
        }
    }

    /** The deferred half of {@link #mirrorActive}, re-validated because it runs a pulse later. */
    private void mirrorDeferred() {
        if (ended || suspended || active < 0) {
            return;
        }
        Field f = fields.get(active);
        if (f.ranges.size() >= 2) {
            mirrorInto(f, true);
        }
    }

    /** True when any non-primary occurrence starts before the editable field (so mirroring it moves the caret). */
    private static boolean mirrorPrecedesCaret(Field f) {
        int primaryStart = f.primary()[0];
        for (int i = 0; i < f.ranges.size(); i++) {
            if (i != f.primaryIdx && f.ranges.get(i)[0] < primaryStart) {
                return true;
            }
        }
        return false;
    }

    /**
     * Rewrites each non-primary occurrence to its derived text (verbatim value, or a transform of it). Each
     * occurrence's length is computed independently, so a length-changing transform ({@code /snakecase})
     * shifts the following ranges correctly. When {@code restoreCaret}, the caret is parked low during the
     * rewrites and returned into the field afterwards — needed only on the deferred path, where a mirror
     * before the field would otherwise leave the caret momentarily out of bounds.
     */
    private void mirrorInto(Field f, boolean restoreCaret) {
        int[] primary = f.primary();
        String value = area.getText(primary[0], primary[1]);
        int caretInField = restoreCaret ? clamp(area.getCaretPosition() - primary[0], 0, primary[1] - primary[0]) : 0;
        int anchorInField = restoreCaret ? clamp(area.getAnchor() - primary[0], 0, primary[1] - primary[0]) : 0;
        applying = true;
        // The mirrors belong to the edit that changed the field (a Backspace, a paste): one undo step, or the
        // first Ctrl-Z reverts a mirror only and leaves the document half-reverted.
        Runnable endUndoJoin = null;
        try {
            if (restoreCaret) {
                area.moveTo(0);
            }
            for (int i = 0; i < f.ranges.size(); i++) {
                if (i == f.primaryIdx) {
                    continue;
                }
                int[] m = f.ranges.get(i);
                String text = f.textFor(i, value);
                int oldLen = m[1] - m[0];
                if (oldLen == text.length() && area.getText(m[0], m[1]).equals(text)) {
                    continue;
                }
                if (endUndoJoin == null) {
                    endUndoJoin = undoJoin.apply(area);
                }
                area.replaceText(m[0], m[1], text);
                shift(allRanges(), indexOf(m), m[0], oldLen, text.length());
            }
        } finally {
            applying = false;
            if (endUndoJoin != null) {
                endUndoJoin.run();
            }
        }
        if (restoreCaret) {
            int[] pr = f.primary(); // offsets may have shifted if a mirror before it changed length
            area.selectRange(
                    Math.min(pr[0] + anchorInField, area.getLength()),
                    Math.min(pr[0] + caretInField, area.getLength()));
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private List<int[]> allRanges() {
        List<int[]> out = new ArrayList<>();
        for (Field f : fields) {
            out.addAll(f.ranges);
        }
        out.add(finalRange); // $0 must shift with edits too, so it lands correctly
        return out;
    }

    private int indexOf(int[] target) {
        List<int[]> all = allRanges();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i) == target) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Pure offset arithmetic for an edit inside the range at {@code activePrimaryIdx} (the active field, or a
     * mirror being rewritten) that replaced {@code removed} characters at {@code editPos} with
     * {@code inserted} characters.
     *
     * <p>What happens to another range depends on where it stands relative to the edited one, which offsets
     * alone cannot say once ranges touch or coincide — {@code ${2:${1}foo}} and {@code ${1}${2:foo}} start
     * out with identical offsets. So each range may carry the parser's nesting order in slots 2 and 3
     * ({@link TabStop#spans()}); for plain {@code {start, end}} ranges it is inferred from the offsets.
     *
     * <ul>
     *   <li>The <b>edited range</b> keeps its start and its end follows the edit — including an insertion
     *       exactly at its end, which extends it.</li>
     *   <li>A range that <b>contains</b> it keeps its start and its end moves by the net change, so a parent
     *       placeholder still covers what was typed in a stop nested at its very start, and a stop whose
     *       default is a mirror ({@code ${4:$1}}) follows that mirror instead of collapsing.</li>
     *   <li>A range <b>before</b> it stays; one <b>after</b> it moves by the net change — which is what keeps
     *       the next field, or {@code $0}, behind text typed at the end of the one before it.</li>
     *   <li>A range <b>nested in</b> it: an offset before the edit stays, one at or after the end of the
     *       removed text moves by the net change, and one inside the removed text collapses to the edit
     *       position; a range the removal swallowed whole becomes empty there. An empty stop at the very end
     *       is pushed by text typed at that end.</li>
     * </ul>
     */
    public static void shift(List<int[]> ranges, int activePrimaryIdx, int editPos, int removed, int inserted) {
        int removedEnd = editPos + removed;
        int delta = inserted - removed;
        boolean hasActive = activePrimaryIdx >= 0 && activePrimaryIdx < ranges.size();
        boolean atActiveEnd = removed == 0 && hasActive && ranges.get(activePrimaryIdx)[1] == editPos;
        int[][] spans = spansOf(ranges);
        int[] active = hasActive ? spans[activePrimaryIdx] : null;
        for (int i = 0; i < ranges.size(); i++) {
            int[] r = ranges.get(i);
            int[] span = spans[i];
            if (i == activePrimaryIdx) {
                r[0] = moved(r[0], editPos, removedEnd, delta);
                r[1] = r[1] == editPos ? editPos + inserted : moved(r[1], editPos, removedEnd, delta);
            } else if (active != null && span[0] < active[0] && span[1] > active[1]) {
                r[1] += delta; // contains the edited range
            } else if (active != null && span[1] < active[0]) {
                continue; // before it
            } else if (active != null && span[0] > active[1]) {
                r[0] += delta; // after it
                r[1] += delta;
            } else if (atActiveEnd && r[0] == editPos) {
                r[0] += inserted;
                r[1] += inserted;
            } else if (swallowed(r, editPos, removed)) {
                r[0] = editPos;
                r[1] = editPos;
            } else {
                r[0] = moved(r[0], editPos, removedEnd, delta);
                r[1] = moved(r[1], editPos, removedEnd, delta);
            }
        }
    }

    /** Each range's {@code {open, close}} nesting order: slots 2 and 3 when every range has them, else inferred. */
    private static int[][] spansOf(List<int[]> ranges) {
        for (int[] r : ranges) {
            if (r.length < 4) {
                return inferSpans(ranges);
            }
        }
        int[][] out = new int[ranges.size()][];
        for (int i = 0; i < out.length; i++) {
            out[i] = new int[] {ranges.get(i)[2], ranges.get(i)[3]};
        }
        return out;
    }

    /**
     * Nesting order read off the offsets, for ranges that did not come from the parser: a non-empty range
     * contains every later-listed range lying within it; everything else is ordered by position, ties by
     * list order.
     */
    static int[][] inferSpans(List<int[]> ranges) {
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < ranges.size(); i++) {
            order.add(i);
        }
        order.sort((x, y) -> {
            int[] a = ranges.get(x);
            int[] b = ranges.get(y);
            if (a[0] != b[0]) {
                return Integer.compare(a[0], b[0]);
            }
            return a[1] != b[1] ? Integer.compare(b[1], a[1]) : Integer.compare(x, y);
        });
        int[][] out = new int[ranges.size()][2];
        java.util.ArrayDeque<Integer> open = new java.util.ArrayDeque<>();
        int seq = 0;
        for (int i : order) {
            int[] r = ranges.get(i);
            while (!open.isEmpty()) {
                int[] top = ranges.get(open.peek());
                if (top[1] > top[0] && r[1] <= top[1]) {
                    break; // still inside the innermost open range
                }
                out[open.pop()][1] = seq++;
            }
            out[i][0] = seq++;
            open.push(i);
        }
        while (!open.isEmpty()) {
            out[open.pop()][1] = seq++;
        }
        return out;
    }

    /** Where {@code offset} lands: unchanged up to the edit, collapsed inside the removal, shifted after it. */
    private static int moved(int offset, int editPos, int removedEnd, int delta) {
        if (offset <= editPos) {
            return offset;
        }
        return offset >= removedEnd ? offset + delta : editPos;
    }

    /** True when {@code range} lies wholly inside the {@code removed} characters at {@code editPos}. */
    private static boolean swallowed(int[] range, int editPos, int removed) {
        return removed > 0 && range[0] >= editPos && range[0] < editPos + removed && range[1] <= editPos + removed;
    }

    /**
     * Makes a parsed snippet fit the buffer it is going into, shifting stop ranges: {@code \r\n} and a lone
     * {@code \r} become {@code \n} (wherever they came from — the body, a {@code $CLIPBOARD} value, a
     * server's text), and, when {@code indentUnit} is given and is not a tab, each tab that indents a line is
     * replaced by that unit — in the snippet format a leading tab means "one indent level", not a tab
     * character. Tabs after the first non-tab character of a line are text and are kept. Pure.
     */
    public static ParsedSnippet normalize(ParsedSnippet parsed, String indentUnit) {
        String t = parsed.text();
        boolean tabs = indentUnit != null && !indentUnit.isEmpty() && !indentUnit.equals("\t") && t.indexOf('\t') >= 0;
        if (!tabs && t.indexOf('\r') < 0) {
            return parsed;
        }
        int[] map = new int[t.length() + 1]; // old offset → new offset
        StringBuilder sb = new StringBuilder(t.length() + 16);
        boolean lineStart = true;
        for (int k = 0; k < t.length(); k++) {
            map[k] = sb.length();
            char c = t.charAt(k);
            if (c == '\r') {
                if (k + 1 < t.length() && t.charAt(k + 1) == '\n') {
                    continue; // the '\n' that follows is the line break
                }
                c = '\n';
            }
            if (c == '\t' && tabs && lineStart) {
                sb.append(indentUnit);
                continue;
            }
            sb.append(c);
            lineStart = c == '\n';
        }
        map[t.length()] = sb.length();
        List<TabStop> stops = new ArrayList<>();
        for (TabStop s : parsed.stops()) {
            List<int[]> rs = new ArrayList<>();
            for (int[] r : s.ranges()) {
                rs.add(new int[] {map[r[0]], map[r[1]]});
            }
            stops.add(new TabStop(
                    s.number(), rs, s.placeholder(), s.choices(), s.transforms(), s.primaryIndex(), s.spans()));
        }
        return new ParsedSnippet(sb.toString(), stops);
    }

    /** Re-indents continuation lines of a parsed snippet to {@code indent}, shifting stop ranges. Pure. */
    public static ParsedSnippet reindent(ParsedSnippet parsed, String indent) {
        if (indent.isEmpty() || parsed.text().indexOf('\n') < 0) {
            return parsed;
        }
        String t = parsed.text();
        int[] add = new int[t.length() + 1];
        StringBuilder sb = new StringBuilder();
        int extra = 0;
        for (int k = 0; k < t.length(); k++) {
            add[k] = extra;
            char c = t.charAt(k);
            sb.append(c);
            if (c == '\n') {
                sb.append(indent);
                extra += indent.length();
            }
        }
        add[t.length()] = extra;
        List<TabStop> stops = new ArrayList<>();
        for (TabStop s : parsed.stops()) {
            List<int[]> rs = new ArrayList<>();
            for (int[] r : s.ranges()) {
                rs.add(new int[] {r[0] + add[r[0]], r[1] + add[r[1]]});
            }
            // Keep choices, transforms AND primaryIndex through re-indent: the 3-/4-arg ctors drop them, which
            // would lose a multi-line choice field's dropdown or a transform occurrence's derivation. Range
            // order is unchanged (offsets only shift), so primaryIndex still points at the same occurrence.
            stops.add(new TabStop(
                    s.number(), rs, s.placeholder(), s.choices(), s.transforms(), s.primaryIndex(), s.spans()));
        }
        return new ParsedSnippet(sb.toString(), stops);
    }
}
