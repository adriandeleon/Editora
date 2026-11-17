package com.editora.editor.notes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.TreeSet;
import java.util.UUID;

import com.editora.config.NoteScope;
import com.editora.config.NoteStatus;
import com.editora.config.PersonalNote;
import com.editora.config.TextAnchor;
import com.editora.editor.BookmarkManager;
import com.editora.editor.LineMarks;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.PlainTextChange;
import org.fxmisc.richtext.model.TwoDimensional.Bias;
import org.jspecify.annotations.Nullable;

/**
 * Tracks the {@link PersonalNote}s of a single {@link CodeArea}: their live character-offset ranges follow
 * the document as it's edited ({@link NoteAnchors#shiftRange}), and on (re)open each note is relocated to
 * its {@code selectedText} ({@link NoteAnchors#relocate}) — or marked {@link NoteStatus#ORPHANED} when it
 * can't be found. The pure math lives in {@link NoteAnchors}; this class is the live glue over the editor.
 *
 * <p>Mirrors {@link BookmarkManager}'s {@code snapshot}/{@code restore} for persistence. Edit-driven shifts do
 * <b>not</b> fire {@code onChanged} (no per-keystroke persistence) — the controller persists positions on
 * save/tab-switch via {@link #snapshot()}; only explicit mutations fire {@code onChanged}. Notes have no gutter
 * glyph (their marker is painted inline by {@link NoteHighlightOverlay}, which follows edits on its own), so an
 * edit costs one range shift per note and nothing else; {@code onSpansChanged} tells the overlay when the set
 * itself changes.
 */
public final class NoteManager implements LineMarks.Carrier {

    private final CodeArea area;
    /** id -> live tracking; insertion order preserved for the panel/snapshot. */
    private final Map<UUID, Tracked> tracked = new LinkedHashMap<>();

    private Runnable onChanged = () -> {};
    private Runnable onSpansChanged = () -> {};
    private boolean restoring;
    /** True while a narrow/widen text swap runs: the swap is not an edit, so nothing is shifted through it. */
    private boolean swapping;
    /** While narrowed, the whole-document ranges of the notes outside the region; {@code null} otherwise. */
    private @Nullable Map<UUID, int[]> held;
    /** The narrowed region's whole-document {@code [start, end)} at the moment it was cut out. */
    private int heldStart;

    private int heldEnd;

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

    /**
     * Called when the set of highlighted spans changes other than by an edit (a note added, changed, removed,
     * (un)orphaned, restored, or carried across a narrow/widen) — the highlight overlay repaints then.
     */
    public void setOnSpansChanged(Runnable onSpansChanged) {
        this.onSpansChanged = onSpansChanged == null ? () -> {} : onSpansChanged;
    }

    // ---- queries (for hover and navigation) ----

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
    public @Nullable PersonalNote noteAt(int offset) {
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

    public @Nullable Integer next(int fromLine) {
        NavigableSet<Integer> lines = activeLines();
        if (lines.isEmpty()) {
            return null;
        }
        Integer k = lines.higher(fromLine);
        return k != null ? k : lines.first();
    }

    public @Nullable Integer previous(int fromLine) {
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

    /**
     * Whether {@link #activeSpans()} would be non-empty — the highlight overlay's gate, asked on every edit,
     * scroll and layout of every buffer, so it is one field read for the common buffer without notes.
     */
    public boolean hasActiveSpans() {
        if (tracked.isEmpty()) {
            return false;
        }
        for (Tracked t : tracked.values()) {
            if (t.positioned() && t.note.status() != NoteStatus.ORPHANED) {
                return true;
            }
        }
        return false;
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
        tracked.put(note.id(), atStoredOffset(note));
        fireChanged();
        onSpansChanged.run();
        relocateLater(List.of(note.id()), RELOCATE_TRIES, moved -> {
            if (moved) {
                fireChanged();
            }
        });
    }

    public void update(PersonalNote note) {
        if (note != null && tracked.containsKey(note.id())) {
            tracked.put(note.id(), atStoredOffset(note));
            fireChanged();
            onSpansChanged.run();
            relocateLater(List.of(note.id()), RELOCATE_TRIES, moved -> {
                if (moved) {
                    fireChanged();
                }
            });
        }
    }

    public void remove(UUID id) {
        Tracked t = tracked.remove(id);
        if (t != null) {
            fireChanged();
            onSpansChanged.run();
        }
    }

    public void setStatus(UUID id, NoteStatus status) {
        Tracked t = tracked.get(id);
        if (t != null && status != null) {
            t.note = t.note.withStatus(status);
            fireChanged();
            onSpansChanged.run();
        }
    }

    public void clear() {
        if (!tracked.isEmpty()) {
            tracked.clear();
            fireChanged();
            onSpansChanged.run();
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
     * Replaces the note set from a persisted list (without firing {@code onChanged}). Each note shows at its stored
     * offset at once; finding it in the current text — a search of the document — runs off the FX thread (PERF-01,
     * Pauta 6.0.1) on a snapshot, and its positions are applied when it lands, if the text is unchanged (worked out
     * again otherwise). {@code movedThen} hears then whether anything moved or was (un)orphaned, so the controller
     * can persist the self-healed state.
     */
    public void restore(List<PersonalNote> saved) {
        restore(saved, moved -> {});
    }

    /** As {@link #restore(List)}; {@code movedThen} hears, once the relocation lands, whether anything moved. */
    public void restore(List<PersonalNote> saved, java.util.function.Consumer<Boolean> movedThen) {
        restoring = true;
        List<UUID> ids = new ArrayList<>();
        try {
            tracked.clear();
            if (saved != null) {
                for (PersonalNote s : saved) {
                    if (s != null) {
                        tracked.put(s.id(), atStoredOffset(s));
                        ids.add(s.id());
                    }
                }
            }
        } finally {
            restoring = false;
            onSpansChanged.run();
        }
        relocateLater(ids, RELOCATE_TRIES, movedThen);
    }

    /** How many times a relocation is worked out again when the text changed before it landed. */
    private static final int RELOCATE_TRIES = 3;

    /** {@link com.editora.perf.BlockingSites} name of the notes' relocation, made off the FX thread. */
    public static final String RELOCATE_SITE = "NoteManager.relocate";

    /** Edits made to the text: what a relocation worked out off the FX thread is applied against. */
    private long edits;

    /** One note's relocation asked off the FX thread: from where it is now, with its anchor's text. */
    private record Ask(Tracked tracked, PersonalNote note, int start, int end) {}

    /** A note where its stored anchor says, not yet looked for in the text. FX thread, no search. */
    private Tracked atStoredOffset(PersonalNote note) {
        TextAnchor a = note.anchor();
        return new Tracked(note, absOffset(a.line(), a.column()), absOffset(a.endLine(), a.endColumn()));
    }

    /**
     * Finds the notes {@code ids} in the text off the FX thread (each from where it is now), then applies their
     * positions — or their orphaning — on the FX thread if the text is unchanged; {@code movedThen} hears whether
     * anything moved. Text typed meanwhile has it worked out again, up to {@code tries} times; then the notes stay
     * where the edits carried them.
     */
    private void relocateLater(List<UUID> ids, int tries, java.util.function.Consumer<Boolean> movedThen) {
        List<Ask> asks = new ArrayList<>();
        for (UUID id : ids) {
            Tracked t = tracked.get(id);
            if (t != null) {
                asks.add(new Ask(t, t.note, t.start, t.end));
            }
        }
        if (asks.isEmpty()) {
            movedThen.accept(false);
            return;
        }
        String document = area.getText();
        long version = edits;
        com.editora.perf.OffFx.then(
                RELOCATE_SITE,
                () -> {
                    List<int @Nullable []> found = new ArrayList<>();
                    for (Ask ask : asks) {
                        TextAnchor a = ask.note().anchor();
                        found.add(NoteAnchors.relocate(
                                document,
                                ask.start(),
                                ask.end(),
                                a.selectedText(),
                                a.prefix(),
                                a.suffix(),
                                a.length()));
                    }
                    return found;
                },
                answer -> {
                    List<int @Nullable []> found = answer.value();
                    if (found == null) {
                        movedThen.accept(false);
                        return;
                    }
                    if (edits != version) {
                        if (tries > 1) {
                            relocateLater(ids, tries - 1, movedThen);
                        } else {
                            movedThen.accept(false);
                        }
                        return;
                    }
                    boolean moved = false;
                    for (int k = 0; k < asks.size(); k++) {
                        Ask ask = asks.get(k);
                        Tracked t = ask.tracked();
                        if (tracked.get(t.note.id()) != t || t.note != ask.note()) {
                            continue; // removed or changed meanwhile: its own relocation answers
                        }
                        Tracked placed = placed(ask.note(), found.get(k));
                        t.note = placed.note;
                        t.start = placed.start;
                        t.end = placed.end;
                        if (placed.note.status() != ask.note().status()
                                || (placed.positioned()
                                        && lineOf(placed.start)
                                                != ask.note().anchor().line())) {
                            moved = true;
                        }
                    }
                    onSpansChanged.run();
                    movedThen.accept(moved);
                });
    }

    // ---- internals ----

    /** {@code note} at {@code r} as relocation found it, or orphaned when it found nothing. */
    private static Tracked placed(PersonalNote note, int @Nullable [] r) {
        if (r == null) {
            // ORPHANED is a system observation ("I can't find the text"), RESOLVED is the user's decision.
            // Overwriting one with the other lost the decision permanently: a checkout that hid the text
            // flipped RESOLVED -> ORPHANED (persisted), and finding the text again mapped ORPHANED ->
            // ACTIVE, so the note came back as open work.
            PersonalNote orphan = note.status() == NoteStatus.ACTIVE ? note.withStatus(NoteStatus.ORPHANED) : note;
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

    /**
     * Carries the notes across the narrowing swap: a note inside the region is rebased onto it; the others
     * are unpositioned (no highlight) with their whole-document range held for
     * {@link #widen}.
     */
    @Override
    public void narrow(int start, int end, Runnable swap) {
        runSwap(swap);
        Map<UUID, int[]> outside = new java.util.HashMap<>();
        for (Tracked t : tracked.values()) {
            if (!t.positioned()) {
                continue;
            }
            if (t.start >= start && t.end <= end) {
                t.start -= start;
                t.end -= start;
            } else {
                outside.put(t.note.id(), new int[] {t.start, t.end});
                t.start = -1;
                t.end = -1;
            }
        }
        held = outside;
        heldStart = start;
        heldEnd = end;
        onSpansChanged.run();
    }

    /** Moves the region's notes back to whole-document offsets and re-positions the held ones around them. */
    @Override
    public void widen(Runnable swap) {
        int regionLength = area.getLength();
        runSwap(swap);
        if (held == null) {
            return;
        }
        for (Tracked t : tracked.values()) {
            int[] range = held.get(t.note.id());
            if (range != null) {
                t.start = NoteAnchors.acrossRegion(range[0], heldStart, heldEnd, regionLength);
                t.end = NoteAnchors.acrossRegion(range[1], heldStart, heldEnd, regionLength);
            } else if (t.positioned()) {
                t.start += heldStart;
                t.end += heldStart;
            }
        }
        held = null;
        onSpansChanged.run();
    }

    private void runSwap(Runnable swap) {
        swapping = true;
        try {
            swap.run();
        } finally {
            swapping = false;
        }
    }

    private void onTextChange(PlainTextChange change) {
        edits++; // what a relocation worked out off the FX thread is checked against
        if (swapping || tracked.isEmpty()) {
            return; // hot-path early-out
        }
        int pos = change.getPosition();
        int removed = change.getRemoved().length();
        int inserted = change.getInserted().length();
        if (removed == 0 && inserted == 0) {
            return;
        }
        // Only the offsets move: no line lookups here. They used to find the lines whose gutter to rebuild — two
        // offsetToPosition calls per note per keystroke and a gutter graphic rebuilt for each moved line, for a
        // glyph notes no longer have (an Enter above 200 notes queued 400 rebuilds).
        @Nullable List<Replaced> inReplacement = null;
        for (Tracked t : tracked.values()) {
            if (!t.positioned()) {
                continue;
            }
            int[] shifted = NoteAnchors.shiftRange(t.start, t.end, pos, removed, inserted);
            if (inserted > 0 && t.start >= pos && t.end <= pos + removed && t.end - t.start < removed) {
                // The note sat inside a replaced span (Replace All, a formatter edit, a history restore): its text
                // is followed into the replacement off the FX thread (the replacement can be the whole document).
                if (inReplacement == null) {
                    inReplacement = new ArrayList<>();
                }
                inReplacement.add(new Replaced(t, t.note, t.start - pos));
            }
            t.start = shifted[0];
            t.end = shifted[1];
        }
        if (inReplacement != null) {
            followIntoReplacement(inReplacement, pos, change.getInserted());
        }
    }

    /** {@link com.editora.perf.BlockingSites} name of following notes into a replaced span, off the FX thread. */
    public static final String REPLACEMENT_SITE = "NoteManager.followReplacement";

    /** A note that sat inside a replaced span, {@code relStart} into it. */
    private record Replaced(Tracked tracked, PersonalNote note, int relStart) {}

    /**
     * Follows each note of {@code replaced} into the text that replaced its span, off the FX thread: until it lands
     * the note sits collapsed at the edit point, where the shift put it. Applied only if no edit came meanwhile;
     * otherwise the notes are found in the text as it is then ({@link #relocateLater}).
     */
    private void followIntoReplacement(List<Replaced> replaced, int pos, String insertedText) {
        long version = edits;
        com.editora.perf.OffFx.then(
                REPLACEMENT_SITE,
                () -> {
                    List<int @Nullable []> found = new ArrayList<>();
                    for (Replaced r : replaced) {
                        TextAnchor anchor = r.note().anchor();
                        found.add(NoteAnchors.relocateInReplacement(
                                insertedText, r.relStart(), anchor.selectedText(), anchor.length()));
                    }
                    return found;
                },
                answer -> {
                    List<int @Nullable []> found = answer.value();
                    if (found == null) {
                        return;
                    }
                    if (edits != version) {
                        List<UUID> ids = new ArrayList<>();
                        for (Replaced r : replaced) {
                            if (tracked.get(r.note().id()) == r.tracked()) {
                                ids.add(r.note().id());
                            }
                        }
                        if (!ids.isEmpty()) {
                            relocateLater(ids, RELOCATE_TRIES, moved -> {});
                        }
                        return;
                    }
                    boolean any = false;
                    for (int k = 0; k < replaced.size(); k++) {
                        Replaced r = replaced.get(k);
                        int[] kept = found.get(k);
                        Tracked t = r.tracked();
                        if (kept == null || tracked.get(r.note().id()) != t || t.note != r.note()) {
                            continue;
                        }
                        t.start = pos + kept[0];
                        t.end = pos + kept[1];
                        any = true;
                    }
                    if (any) {
                        onSpansChanged.run();
                    }
                });
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
