package com.editora.snippet;

import java.util.ArrayList;
import java.util.List;

import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;

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

    /** One tab stop's live document ranges; {@code ranges.get(0)} is the editable primary, rest mirror. */
    private static final class Field {
        final int number;
        final List<int[]> ranges;
        final List<String> choices;

        Field(int number, List<int[]> ranges, List<String> choices) {
            this.number = number;
            this.ranges = ranges;
            this.choices = choices;
        }

        int[] primary() {
            return ranges.get(0);
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
                fields.add(new Field(s.number(), abs, s.choices()));
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
        });
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

    /** Replaces every mirror of the active field with the primary's current text. */
    private void mirrorActive() {
        Field f = fields.get(active);
        if (f.ranges.size() < 2) {
            return;
        }
        int[] primary = f.primary();
        String text = area.getText(primary[0], primary[1]);
        applying = true;
        try {
            for (int i = 1; i < f.ranges.size(); i++) {
                int[] m = f.ranges.get(i);
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
            stops.add(new TabStop(s.number(), rs, s.placeholder()));
        }
        return new ParsedSnippet(sb.toString(), stops);
    }
}
