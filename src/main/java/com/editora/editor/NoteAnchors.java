package com.editora.editor;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure anchoring math for Personal Notes (no toolkit; unit-tested). Two concerns:
 *
 * <ul>
 *   <li><b>Live shifting</b> — adjust a note's character offset range as the document is edited
 *       ({@link #shiftOffset}/{@link #shiftRange}), classic interval arithmetic: offsets before the edit
 *       stay; offsets after move by the length delta; offsets inside a deleted span collapse to the edit
 *       point.</li>
 *   <li><b>Relocation</b> — on (re)open, find where a saved anchor's {@code selectedText} now lives in the
 *       document ({@link #relocate}), following the SDD's levels: exact at the saved offset → a
 *       context-disambiguated occurrence → the nearest occurrence → otherwise <b>orphan</b> ({@code null}).</li>
 * </ul>
 */
public final class NoteAnchors {

    /** Cap on scanned occurrences so relocation stays bounded on pathological inputs. */
    static final int MAX_OCCURRENCES = 5000;

    private NoteAnchors() {}

    /** New position of {@code offset} after an edit at {@code pos} that removed {@code removed} chars and inserted {@code inserted}. */
    public static int shiftOffset(int offset, int pos, int removed, int inserted) {
        if (offset <= pos) {
            return offset;
        }
        if (offset >= pos + removed) {
            return offset + (inserted - removed);
        }
        return pos; // inside the deleted span → collapse to the edit point
    }

    /** Shifts a {@code [start,end]} range (returned normalized so start ≤ end). */
    public static int[] shiftRange(int start, int end, int pos, int removed, int inserted) {
        int s = shiftOffset(start, pos, removed, inserted);
        int e = shiftOffset(end, pos, removed, inserted);
        return new int[] {Math.min(s, e), Math.max(s, e)};
    }

    /**
     * Locates the anchor's text in {@code doc}. Returns {@code {start,end}} (end exclusive) or {@code null}
     * if it can't be relocated (the note should be marked orphaned). The span runs from the match to
     * {@code start + length} (clamped to the document): {@code length} is the note's <em>full</em> original
     * selection length, so a note taken on more than {@link com.editora.config.TextAnchor#MAX_TEXT} characters
     * highlights the whole selection rather than just the capped {@code needle} used to find it (#454). A
     * {@code length} shorter than the needle (an old note with no saved length, or a bad value) falls back to
     * the needle length.
     */
    /** As {@link #relocate(String, int, int, String, String, String, int)} with {@code length} defaulting to
     *  the {@code selectedText} length (the span = the captured text). */
    public static int[] relocate(
            String doc, int savedStart, int savedEnd, String selectedText, String prefix, String suffix) {
        return relocate(
                doc,
                savedStart,
                savedEnd,
                selectedText,
                prefix,
                suffix,
                selectedText == null ? 0 : selectedText.length());
    }

    public static int[] relocate(
            String doc, int savedStart, int savedEnd, String selectedText, String prefix, String suffix, int length) {
        if (doc == null) {
            return null;
        }
        String needle = selectedText == null ? "" : selectedText;
        if (needle.isEmpty()) {
            // No text to search for (e.g. a blank line): keep the clamped position rather than orphan.
            int s = clamp(savedStart, 0, doc.length());
            return new int[] {s, s};
        }
        int span = Math.max(length, needle.length());
        // Level 1: the saved offset still holds the text.
        if (savedStart >= 0
                && savedStart + needle.length() <= doc.length()
                && doc.regionMatches(savedStart, needle, 0, needle.length())) {
            return new int[] {savedStart, Math.min(savedStart + span, doc.length())};
        }
        List<Integer> occ = occurrences(doc, needle);
        if (occ.isEmpty() || occ.size() > MAX_OCCURRENCES) {
            // No occurrence, OR the needle is too common to disambiguate reliably: orphan (honest +
            // recoverable) rather than pick from a truncated top-of-file list. Previously the scan collected
            // only the first MAX_OCCURRENCES from offset 0, so a note living past occurrence #MAX_OCCURRENCES
            // scored to the last of those and jumped far up-file (#455).
            return null;
        }
        // Levels 2-3: prefer an occurrence whose surrounding context matches; tie-break by proximity.
        int best = -1;
        long bestScore = Long.MIN_VALUE;
        int from = Math.max(savedStart, 0);
        for (int o : occ) {
            long score =
                    (contextMatches(doc, o, needle, prefix, suffix) ? 1_000_000_000L : 0) - Math.abs((long) o - from);
            if (score > bestScore) {
                bestScore = score;
                best = o;
            }
        }
        return new int[] {best, Math.min(best + span, doc.length())};
    }

    private static boolean contextMatches(String doc, int at, String needle, String prefix, String suffix) {
        boolean pre = prefix == null
                || prefix.isEmpty()
                || (at - prefix.length() >= 0 && doc.regionMatches(at - prefix.length(), prefix, 0, prefix.length()));
        int after = at + needle.length();
        boolean suf = suffix == null
                || suffix.isEmpty()
                || (after + suffix.length() <= doc.length() && doc.regionMatches(after, suffix, 0, suffix.length()));
        return pre && suf;
    }

    private static List<Integer> occurrences(String doc, String needle) {
        List<Integer> out = new ArrayList<>();
        int i = doc.indexOf(needle);
        // Collect one past the cap so the caller can tell "exactly MAX_OCCURRENCES" from "more than that"
        // (too ambiguous → orphan) instead of silently picking from a truncated prefix.
        while (i >= 0 && out.size() <= MAX_OCCURRENCES) {
            out.add(i);
            i = doc.indexOf(needle, i + 1);
        }
        return out;
    }

    static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
