package com.editora.editor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Consumer;

import com.editora.config.NoteScope;
import com.editora.config.NoteStatus;
import com.editora.config.PersonalNote;
import com.editora.config.TextAnchor;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.PlainTextChange;
import org.fxmisc.richtext.model.TwoDimensional.Bias;

/**
 * Tracks the {@link PersonalNote}s of a single {@link CodeArea}: their live character-offset ranges follow
 * the document as it's edited ({@link NoteAnchors#shiftRange}), and on (re)open each note is relocated to
 * its {@code selectedText} ({@link NoteAnchors#relocate}) — or marked {@link NoteStatus#ORPHANED} when it
 * can't be found. The pure math lives in {@link NoteAnchors}; this class is the live glue over the editor.
 *
 * <p>Mirrors {@link BookmarkManager}: a cheap {@code isNoted} test for the gutter, {@code onLinesRepaint}
 * when an edit moves a marker, and {@code snapshot}/{@code restore} for persistence. Edit-driven shifts do
 * <b>not</b> fire {@code onChanged} (no per-keystroke persistence) — the controller persists positions on
 * save/tab-switch via {@link #snapshot()}; only explicit mutations fire {@code onChanged}.
 */
public final class NoteManager {

    private final CodeArea area;
    /** id -> live tracking; insertion order preserved for the panel/snapshot. */
    private final Map<UUID, Tracked> tracked = new LinkedHashMap<>();

    private Runnable onChanged = () -> {};
    private Consumer<Collection<Integer>> onLinesRepaint = c -> {};
    private boolean restoring;

    /** A note plus its current offset range ({@code start==end==-1} for file-scope / orphaned notes). */
    private static final class Tracked {
        private PersonalNote note;
        private int start;
        private int end;

        Tracked(PersonalNote note, int start, int end) {
            this.note = note;
            this.start = start;
            this.end = end;
        }

        boolean positioned() {
            return start >= 0;
        }
    }

    public NoteManager(CodeArea area) {
        this.area = area;
        area.plainTextChanges().subscribe(this::onTextChange);
    }

    public void setOnChanged(Runnable onChanged) {
        this.onChanged = onChanged == null ? () -> {} : onChanged;
    }

    public void setOnLinesRepaint(Consumer<Collection<Integer>> cb) {
        this.onLinesRepaint = cb == null ? c -> {} : cb;
    }

    // ---- queries (for the gutter, hover, navigation) ----

    /** True if a non-orphaned note's anchor sits on {@code line} (drives the gutter marker). */
    public boolean isNoted(int line) {
        for (Tracked t : tracked.values()) {
            if (t.positioned() && t.note.status() != NoteStatus.ORPHANED && lineOf(t.start) == line) {
                return true;
            }
        }
        return false;
    }

    /** The notes anchored on {@code line} (non-orphaned), in insertion order. */
    public List<PersonalNote> notesOnLine(int line) {
        List<PersonalNote> out = new ArrayList<>();
        for (Tracked t : tracked.values()) {
            if (t.positioned() && t.note.status() != NoteStatus.ORPHANED && lineOf(t.start) == line) {
                out.add(t.note);
            }
        }
        return out;
    }

    /** The note whose span contains {@code offset} (or whose line it is, for LINE scope); null if none. */
    public PersonalNote noteAt(int offset) {
        PersonalNote best = null;
        int bestLen = Integer.MAX_VALUE;
        for (Tracked t : tracked.values()) {
            if (!t.positioned() || t.note.status() == NoteStatus.ORPHANED) {
                continue;
            }
            boolean hit = (offset >= t.start && offset <= t.end)
                    || (t.note.scope() == NoteScope.LINE && lineOf(t.start) == lineOf(offset));
            if (hit) {
                int len = Math.max(0, t.end - t.start);
                if (len < bestLen) {
                    bestLen = len;
                    best = t.note;
                }
            }
        }
        return best;
    }

    /** Lines carrying a non-orphaned note, sorted. */
    public NavigableSet<Integer> activeLines() {
        TreeSet<Integer> lines = new TreeSet<>();
        for (Tracked t : tracked.values()) {
            if (t.positioned() && t.note.status() != NoteStatus.ORPHANED) {
                lines.add(lineOf(t.start));
            }
        }
        return lines;
    }

    public Integer next(int fromLine) {
        NavigableSet<Integer> lines = activeLines();
        if (lines.isEmpty()) {
            return null;
        }
        Integer k = lines.higher(fromLine);
        return k != null ? k : lines.first();
    }

    public Integer previous(int fromLine) {
        NavigableSet<Integer> lines = activeLines();
        if (lines.isEmpty()) {
            return null;
        }
        Integer k = lines.lower(fromLine);
        return k != null ? k : lines.last();
    }

    public boolean isEmpty() {
        return tracked.isEmpty();
    }

    /** {@code [start,end]} offset spans of non-orphaned, positioned notes (for the highlight overlay). */
    public List<int[]> activeSpans() {
        List<int[]> out = new ArrayList<>();
        for (Tracked t : tracked.values()) {
            if (t.positioned() && t.note.status() != NoteStatus.ORPHANED) {
                out.add(new int[] {t.start, t.end});
            }
        }
        return out;
    }

    // ---- mutations ----

    public void add(PersonalNote note) {
        if (note == null) {
            return;
        }
        tracked.put(note.id(), place(note));
        fireChanged();
    }

    public void update(PersonalNote note) {
        if (note != null && tracked.containsKey(note.id())) {
            tracked.put(note.id(), place(note));
            fireChanged();
        }
    }

    public void remove(UUID id) {
        Tracked t = tracked.remove(id);
        if (t != null) {
            fireChanged();
            if (t.positioned()) {
                onLinesRepaint.accept(List.of(lineOf(t.start)));
            }
        }
    }

    public void setStatus(UUID id, NoteStatus status) {
        Tracked t = tracked.get(id);
        if (t != null && status != null) {
            t.note = t.note.withStatus(status);
            fireChanged();
            if (t.positioned()) {
                onLinesRepaint.accept(List.of(lineOf(t.start)));
            }
        }
    }

    public void clear() {
        if (!tracked.isEmpty()) {
            tracked.clear();
            fireChanged();
        }
    }

    // ---- persistence ----

    /** This buffer's notes with anchors refreshed from their live offsets (insertion order). */
    public List<PersonalNote> snapshot() {
        List<PersonalNote> out = new ArrayList<>(tracked.size());
        for (Tracked t : tracked.values()) {
            out.add(t.positioned() ? t.note.withAnchor(anchorFromOffsets(t)) : t.note);
        }
        return out;
    }

    /**
     * Replaces the note set from a persisted list, relocating each to the current text (without firing
     * {@code onChanged}). Returns {@code true} if anything moved or was (un)orphaned, so the controller can
     * persist the self-healed state.
     */
    public boolean restore(List<PersonalNote> saved) {
        restoring = true;
        boolean moved = false;
        try {
            tracked.clear();
            if (saved != null) {
                for (PersonalNote s : saved) {
                    if (s == null) {
                        continue;
                    }
                    Tracked t = place(s);
                    tracked.put(s.id(), t);
                    if (t.note.status() != s.status()) {
                        moved = true;
                    } else if (t.positioned() && lineOf(t.start) != s.anchor().line()) {
                        moved = true;
                    }
                }
            }
            return moved;
        } finally {
            restoring = false;
        }
    }

    // ---- internals ----

    private Tracked place(PersonalNote note) {
        TextAnchor a = note.anchor();
        int savedStart = absOffset(a.line(), a.column());
        int savedEnd = absOffset(a.endLine(), a.endColumn());
        int[] r = NoteAnchors.relocate(area.getText(), savedStart, savedEnd, a.selectedText(), a.prefix(), a.suffix());
        if (r == null) {
            PersonalNote orphan = note.status() == NoteStatus.ORPHANED ? note : note.withStatus(NoteStatus.ORPHANED);
            return new Tracked(orphan, -1, -1);
        }
        PersonalNote active = note.status() == NoteStatus.ORPHANED ? note.withStatus(NoteStatus.ACTIVE) : note;
        return new Tracked(active, r[0], r[1]);
    }

    private TextAnchor anchorFromOffsets(Tracked t) {
        var sp = area.offsetToPosition(clamp(t.start, 0, area.getLength()), Bias.Forward);
        var ep = area.offsetToPosition(clamp(t.end, 0, area.getLength()), Bias.Backward);
        return t.note.anchor().at(sp.getMajor(), sp.getMinor(), ep.getMajor(), ep.getMinor());
    }

    private int absOffset(int line, int column) {
        int paragraphs = area.getParagraphs().size();
        if (paragraphs == 0) {
            return 0;
        }
        int p = clamp(line, 0, paragraphs - 1);
        int len = area.getParagraph(p).getText().length();
        int c = clamp(column, 0, len);
        return area.getAbsolutePosition(p, c);
    }

    private int lineOf(int offset) {
        return area.offsetToPosition(clamp(offset, 0, area.getLength()), Bias.Forward)
                .getMajor();
    }

    private void onTextChange(PlainTextChange change) {
        if (tracked.isEmpty()) {
            return; // hot-path early-out
        }
        int pos = change.getPosition();
        int removed = change.getRemoved().length();
        int inserted = change.getInserted().length();
        if (removed == 0 && inserted == 0) {
            return;
        }
        java.util.Set<Integer> affected = new java.util.HashSet<>();
        for (Tracked t : tracked.values()) {
            if (!t.positioned()) {
                continue;
            }
            int oldLine = lineOf(t.start);
            int[] shifted = NoteAnchors.shiftRange(t.start, t.end, pos, removed, inserted);
            t.start = shifted[0];
            t.end = shifted[1];
            int newLine = lineOf(t.start);
            if (oldLine != newLine) {
                affected.add(oldLine);
                affected.add(newLine);
            }
        }
        if (!affected.isEmpty()) {
            onLinesRepaint.accept(affected);
        }
    }

    private void fireChanged() {
        if (!restoring) {
            onChanged.run();
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
