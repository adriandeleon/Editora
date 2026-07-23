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
 * pure {@link #shift} arithmetic (mirrors {@code BookmarkManager.shift}); programmatic mirror edits
 * are guarded by {@link #applying} to avoid reentrancy.
 */
public final class SnippetSession {

    private final CodeArea area;
    private final List<Field> fields = new ArrayList<>(); // ordered: 1,2,… then $0 last
    private final int[] finalRange; // $0 caret (or end of insert); shifts with edits
    private int active = -1;
    private Subscription sub;
    private boolean applying;
    private boolean ended;
    private Runnable onEnd = () -> {};
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
        this.area = area;
        ParsedSnippet p = reindent(parsed, indent == null ? "" : indent);
        area.replaceText(from, to, p.text());

        int end = from + p.text().length();
        int[] dollarZero = {end, end};
        for (TabStop s : p.stops()) {
            List<int[]> abs = new ArrayList<>();
            for (int[] r : s.ranges()) {
                abs.add(new int[] {from + r[0], from + r[1]});
            }
            if (s.isFinal()) {
                dollarZero = abs.get(0);
            } else {
                fields.add(new Field(s.number(), abs, s.choices(), s.transforms(), s.primaryIndex()));
            }
        }
        this.finalRange = dollarZero;

        if (fields.isEmpty()) {
            area.moveTo(finalRange[0]);
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

    public boolean isActive() {
        return !ended;
    }

    /** Advances to the next stop; past the last one, jumps to {@code $0} and ends. */
    public void next() {
        if (ended) {
            return;
        }
        if (active + 1 < fields.size()) {
            active++;
            selectActive();
        } else {
            finish();
        }
    }

    /** Goes back to the previous stop (no-op at the first). */
    public void previous() {
        if (ended || active <= 0) {
            return;
        }
        active--;
        selectActive();
    }

    /** Ends the session, placing the caret at {@code $0}. */
    public void finish() {
        if (ended) {
            return;
        }
        int caret = Math.min(finalRange[0], area.getLength());
        endSession();
        area.moveTo(caret);
        area.requestFollowCaret();
    }

    /** Ends the session without moving the caret (e.g. user pressed Esc or edited elsewhere). */
    public void cancel() {
        if (!ended) {
            endSession();
        }
    }

    private void endSession() {
        ended = true;
        hideChoiceMenu();
        if (sub != null) {
            sub.unsubscribe();
            sub = null;
        }
        onEnd.run();
    }

    private void selectActive() {
        hideChoiceMenu();
        Field f = fields.get(active);
        int[] r = f.primary();
        area.selectRange(r[0], r[1]);
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

        int[] primary = fields.get(active).primary();
        // An edit outside the active field (the user moved away) ends the snippet.
        if (pos < primary[0] || pos > primary[1] || pos + removed > primary[1]) {
            cancel();
            return;
        }
        // Grow/shrink the active field and shift everything after the edit.
        shift(allRanges(), indexOf(primary), pos, delta);
        mirrorActive();
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
        if (ended || active < 0 || replacement == null) {
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
        List<int[]> all = allRanges();
        for (int i : order) {
            int[] o = f.ranges.get(i);
            int newLen = f.textFor(i, newPrimary).length();
            shift(all, all.indexOf(o), o[0], newLen - (o[1] - o[0]));
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
        if (ended || active < 0) {
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
        applying = true;
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
                area.replaceText(m[0], m[1], text);
                int mdelta = text.length() - oldLen;
                int editEnd = m[0] + oldLen;
                m[1] = m[0] + text.length();
                for (int[] r : allRanges()) {
                    if (r == m) {
                        continue;
                    }
                    if (r[0] >= editEnd) {
                        r[0] += mdelta;
                    }
                    if (r[1] >= editEnd) {
                        r[1] += mdelta;
                    }
                }
            }
        } finally {
            applying = false;
        }
        if (restoreCaret) {
            int[] pr = f.primary(); // offsets may have shifted if a mirror before it changed length
            area.moveTo(Math.min(pr[0] + caretInField, area.getLength()));
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
     * Pure offset arithmetic for an edit replacing text at {@code editPos} with a net {@code delta}
     * length change: every range after the edit shifts by {@code delta}, and the active primary
     * (at {@code activePrimaryIdx}) grows even when the edit is at its end. A range's start moves only
     * when strictly after the edit; its end moves when after the edit, or when it is the active
     * primary's end exactly at the edit position (so typing at the field end extends it).
     */
    public static void shift(List<int[]> ranges, int activePrimaryIdx, int editPos, int delta) {
        for (int i = 0; i < ranges.size(); i++) {
            int[] r = ranges.get(i);
            if (r[0] > editPos) {
                r[0] += delta;
            }
            boolean activePrimary = i == activePrimaryIdx;
            if (r[1] > editPos || (activePrimary && r[1] == editPos)) {
                r[1] += delta;
            }
        }
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
            stops.add(new TabStop(s.number(), rs, s.placeholder(), s.choices(), s.transforms(), s.primaryIndex()));
        }
        return new ParsedSnippet(sb.toString(), stops);
    }
}
