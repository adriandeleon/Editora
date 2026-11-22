package com.editora.ui;

/**
 * Decides how much of a file may be loaded into the heap that is left.
 *
 * <p>A loaded document costs far more than its file: RichTextFX keeps a paragraph object graph per line and the
 * text more than once while it is built, and the highlighter keeps style spans and tokenizer state per line. A
 * second or third very large file therefore exhausts the heap long before the files themselves would, and an
 * {@code OutOfMemoryError} inside the FX-thread insertion takes the whole editor — and every unsaved buffer — with
 * it. So a load that will not fit is opened through the capped, read-only huge-file path instead, showing as much
 * of the file as there is room for.
 *
 * <p>The cost is counted per character <em>and per line</em>, measured on 2026-11-22 (the smallest heap each file
 * still opened in, and the heap a loaded one keeps; JDK 25, G1): Java source, JSON and log text of 5 to 21 MB
 * peaked at about {@value #COST_PER_CHAR} bytes per character plus {@value #COST_PER_LINE} per line while loading,
 * and highlighting kept about {@value #HIGHLIGHT_COST_PER_LINE} more per line. Counting bytes alone (15 per byte
 * before) was right for 70-character lines but undercounted short-lined files: pretty-printed JSON, at 16
 * characters a line, needed 24 bytes per byte.
 */
final class LoadHeapGuard {

    /** Peak heap bytes per character of a document while it loads (see the class comment). */
    static final long COST_PER_CHAR = 8;

    /** Peak heap bytes per line (paragraph) of a document while it loads. */
    static final long COST_PER_LINE = 320;

    /** Heap bytes per line the highlighter keeps for a highlighted document (large-file mode has none). */
    static final long HIGHLIGHT_COST_PER_LINE = 450;

    /** Heap left untouched for the rest of the editor after the load is accounted for. */
    static final long MARGIN_BYTES = 128L << 20;

    /** The least a capped load shows, however little heap is left: a slice this small always fits. */
    static final long MIN_CAP_BYTES = 256L << 10;

    /** A document estimated to cost less than this is not checked: it fits any heap the editor runs in. */
    static final long CHECK_FROM_COST = 32L << 20;

    private LoadHeapGuard() {}

    /** The estimated peak heap cost of a document of {@code chars} characters in {@code lines} lines. */
    static long cost(long chars, long lines, boolean highlighted) {
        return chars * COST_PER_CHAR + lines * (COST_PER_LINE + (highlighted ? HIGHLIGHT_COST_PER_LINE : 0));
    }

    /** Whether a document costing {@code cost} fits, with margin, in what {@code usedHeap} leaves of {@code maxHeap}. */
    static boolean fits(long cost, long maxHeap, long usedHeap) {
        return cost <= room(maxHeap, usedHeap);
    }

    /**
     * How many of a document's {@code chars} characters to load when the whole of it costs {@code cost}: all of
     * them when it fits, else the share the free heap affords (never below {@link #MIN_CAP_BYTES}).
     */
    static long cap(long chars, long cost, long maxHeap, long usedHeap) {
        if (cost <= 0) {
            return chars;
        }
        long allowed = (long) (chars * ((double) room(maxHeap, usedHeap) / cost));
        return Math.min(chars, Math.max(MIN_CAP_BYTES, allowed));
    }

    private static long room(long maxHeap, long usedHeap) {
        return Math.max(0, maxHeap - usedHeap - MARGIN_BYTES);
    }
}
