package org.adriandeleon.editora.editor;

import java.util.ArrayList;
import java.util.List;

public final class ProgressiveHighlightSupport {
    public static final int VISIBLE_BUFFER_PARAGRAPHS = 80;
    public static final int FALLBACK_VISIBLE_PARAGRAPHS = 120;
    public static final int PREFETCH_MIN_PARAGRAPHS = 160;
    public static final int PREFETCH_MEDIUM_MULTIPLIER = 2;
    public static final int PREFETCH_FAST_MULTIPLIER = 3;

    private ProgressiveHighlightSupport() {
    }

    public static ParagraphWindow windowAroundVisibleParagraphs(int firstVisibleParagraph,
                                                                int visibleParagraphCount,
                                                                int paragraphCount,
                                                                int bufferParagraphs,
                                                                int fallbackVisibleParagraphCount) {
        int normalizedParagraphCount = Math.max(1, paragraphCount);
        int normalizedVisibleCount = visibleParagraphCount > 0
                ? visibleParagraphCount
                : Math.max(1, fallbackVisibleParagraphCount);
        int startParagraph = clamp(firstVisibleParagraph, 0, normalizedParagraphCount - 1);
        int endParagraphExclusive = Math.min(normalizedParagraphCount, startParagraph + normalizedVisibleCount);
        int normalizedBuffer = Math.max(0, bufferParagraphs);
        return new ParagraphWindow(
                Math.max(0, startParagraph - normalizedBuffer),
                Math.min(normalizedParagraphCount, endParagraphExclusive + normalizedBuffer)
        );
    }

    public static List<ParagraphWindow> neighboringParagraphWindows(ParagraphWindow visibleWindow,
                                                                    int paragraphCount,
                                                                    int backwardWindowSize,
                                                                    int forwardWindowSize,
                                                                    ScrollDirection direction) {
        int normalizedParagraphCount = Math.max(1, paragraphCount);
        int normalizedBackwardSize = Math.max(1, Math.max(backwardWindowSize, visibleWindow.size()));
        int normalizedForwardSize = Math.max(1, Math.max(forwardWindowSize, visibleWindow.size()));
        int aboveWindowSize = direction == ScrollDirection.UP ? normalizedForwardSize : normalizedBackwardSize;
        int belowWindowSize = direction == ScrollDirection.UP ? normalizedBackwardSize : normalizedForwardSize;
        ParagraphWindow aboveWindow = visibleWindow.startParagraph() > 0
                ? new ParagraphWindow(
                Math.max(0, visibleWindow.startParagraph() - aboveWindowSize),
                visibleWindow.startParagraph()
        )
                : null;
        ParagraphWindow belowWindow = visibleWindow.endParagraphExclusive() < normalizedParagraphCount
                ? new ParagraphWindow(
                visibleWindow.endParagraphExclusive(),
                Math.min(normalizedParagraphCount, visibleWindow.endParagraphExclusive() + belowWindowSize)
        )
                : null;
        List<ParagraphWindow> windows = new ArrayList<>(2);
        if (direction == ScrollDirection.UP) {
            addIfPresent(windows, aboveWindow);
            addIfPresent(windows, belowWindow);
        } else {
            addIfPresent(windows, belowWindow);
            addIfPresent(windows, aboveWindow);
        }
        return List.copyOf(windows);
    }

    public static ScrollDirection inferScrollDirection(ParagraphWindow previousWindow, ParagraphWindow currentWindow) {
        if (previousWindow == null || currentWindow == null) {
            return ScrollDirection.NONE;
        }
        if (currentWindow.startParagraph() > previousWindow.startParagraph()) {
            return ScrollDirection.DOWN;
        }
        if (currentWindow.startParagraph() < previousWindow.startParagraph()) {
            return ScrollDirection.UP;
        }
        if (currentWindow.endParagraphExclusive() > previousWindow.endParagraphExclusive()) {
            return ScrollDirection.DOWN;
        }
        if (currentWindow.endParagraphExclusive() < previousWindow.endParagraphExclusive()) {
            return ScrollDirection.UP;
        }
        return ScrollDirection.NONE;
    }

    public static ViewportMotion inferViewportMotion(ParagraphWindow previousWindow, ParagraphWindow currentWindow) {
        ScrollDirection direction = inferScrollDirection(previousWindow, currentWindow);
        if (previousWindow == null || currentWindow == null) {
            return new ViewportMotion(direction, 0);
        }
        int paragraphDelta = Math.max(
                Math.abs(currentWindow.startParagraph() - previousWindow.startParagraph()),
                Math.abs(currentWindow.endParagraphExclusive() - previousWindow.endParagraphExclusive())
        );
        return new ViewportMotion(direction, paragraphDelta);
    }

    public static int adaptiveForwardPrefetchParagraphs(int basePrefetchParagraphs,
                                                        ParagraphWindow viewportWindow,
                                                        ViewportMotion motion) {
        int normalizedBase = Math.max(1, basePrefetchParagraphs);
        if (viewportWindow == null || motion == null || motion.direction() == ScrollDirection.NONE) {
            return normalizedBase;
        }
        int visibleSize = Math.max(1, viewportWindow.size());
        if (motion.paragraphDelta() >= visibleSize) {
            return Math.max(normalizedBase, visibleSize * PREFETCH_FAST_MULTIPLIER);
        }
        if (motion.paragraphDelta() >= Math.max(1, visibleSize / 2)) {
            return Math.max(normalizedBase, visibleSize * PREFETCH_MEDIUM_MULTIPLIER);
        }
        return normalizedBase;
    }

    private static void addIfPresent(List<ParagraphWindow> windows, ParagraphWindow window) {
        if (window != null) {
            windows.add(window);
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    public record ParagraphWindow(int startParagraph, int endParagraphExclusive) {
        public ParagraphWindow {
            startParagraph = Math.max(0, startParagraph);
            endParagraphExclusive = Math.max(startParagraph + 1, endParagraphExclusive);
        }

        public int size() {
            return endParagraphExclusive - startParagraph;
        }
    }

    public record HighlightWindow(int startOffset, int endOffset, int startParagraph, int endParagraphExclusive) {
        public HighlightWindow {
            startOffset = Math.max(0, startOffset);
            endOffset = Math.max(startOffset, endOffset);
            startParagraph = Math.max(0, startParagraph);
            endParagraphExclusive = Math.max(startParagraph + 1, endParagraphExclusive);
        }
    }

    public record ViewportMotion(ScrollDirection direction, int paragraphDelta) {
        public ViewportMotion {
            direction = direction == null ? ScrollDirection.NONE : direction;
            paragraphDelta = Math.max(0, paragraphDelta);
        }
    }

    public enum ScrollDirection {
        UP,
        DOWN,
        NONE
    }
}

