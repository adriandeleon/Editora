package com.editora.ui;

/**
 * Decides how much of a large file may be loaded into the heap that is left.
 *
 * <p>A loaded document costs far more than its file: RichTextFX keeps a paragraph object graph and two
 * copies of every line, measured at 13–16 bytes of live heap per file byte for files of 5 MB and up. A
 * second or third very large file therefore exhausts the heap long before the files themselves would, and
 * an {@code OutOfMemoryError} inside the FX-thread insertion takes the whole editor — and every unsaved
 * buffer — with it. So a load that will not fit is opened through the capped, read-only huge-file path
 * instead, showing as much of the file as there is room for.
 */
final class LoadHeapGuard {

    /** Live heap bytes a loaded document costs per file byte (large-file mode; see the class comment). */
    static final long COST_PER_BYTE = 15;

    /** Heap left untouched for the rest of the editor after the load is accounted for. */
    static final long MARGIN_BYTES = 128L << 20;

    /** The least a capped load shows, however little heap is left: a slice this small always fits. */
    static final long MIN_CAP_BYTES = 256L << 10;

    private LoadHeapGuard() {}

    /** Whether a document of {@code bytes} fits, with margin, in what {@code usedHeap} leaves of {@code maxHeap}. */
    static boolean fits(long bytes, long maxHeap, long usedHeap) {
        return bytes <= affordable(maxHeap, usedHeap);
    }

    /**
     * How much of a {@code bytes}-long document to load: all of it when it fits, else as much as the free
     * heap affords (never below {@link #MIN_CAP_BYTES}).
     */
    static long cap(long bytes, long maxHeap, long usedHeap) {
        return Math.min(bytes, Math.max(MIN_CAP_BYTES, affordable(maxHeap, usedHeap)));
    }

    private static long affordable(long maxHeap, long usedHeap) {
        return Math.max(0, maxHeap - usedHeap - MARGIN_BYTES) / COST_PER_BYTE;
    }
}
