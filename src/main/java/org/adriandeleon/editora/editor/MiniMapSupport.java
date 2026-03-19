package org.adriandeleon.editora.editor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class MiniMapSupport {
    private static final int TAB_WIDTH = 4;
    private static final double IDEAL_ROW_HEIGHT = 2d;
    private static final double MIN_SAMPLE_WIDTH = 0.04d;
    private static final double MIN_VIEWPORT_FRACTION = 0.08d;

    private MiniMapSupport() {
    }

    public static List<MiniMapSample> sampleText(String text, int bucketCount, int maxColumns) {
        List<String> lines = normalizedLines(text);
        int normalizedBucketCount = Math.max(1, Math.min(bucketCount, lines.size()));
        int normalizedMaxColumns = Math.max(8, maxColumns);
        List<MiniMapSample> samples = new ArrayList<>(normalizedBucketCount);
        for (int bucket = 0; bucket < normalizedBucketCount; bucket++) {
            int startIndex = bucket * lines.size() / normalizedBucketCount;
            int endIndex = (bucket + 1) * lines.size() / normalizedBucketCount;
            if (endIndex <= startIndex) {
                endIndex = Math.min(lines.size(), startIndex + 1);
            }

            MiniMapSample strongestSample = MiniMapSample.EMPTY;
            for (int lineIndex = startIndex; lineIndex < endIndex; lineIndex++) {
                MiniMapSample candidate = sampleLine(lines.get(lineIndex), normalizedMaxColumns);
                if (candidate.widthFraction() > strongestSample.widthFraction()) {
                    strongestSample = candidate;
                }
            }
            samples.add(strongestSample);
        }
        return List.copyOf(samples);
    }

    public static MiniMapLayout layout(String text, double availableHeight, int maxSampleRows) {
        int lineCount = normalizedLines(text).size();
        int rowsAllowedByHeight = Math.max(1, (int) Math.ceil(Math.max(1d, availableHeight) / IDEAL_ROW_HEIGHT));
        int sampleCount = Math.max(1, Math.min(lineCount, Math.min(Math.max(1, maxSampleRows), rowsAllowedByHeight)));
        double renderHeight = Math.max(1d, Math.min(Math.max(1d, availableHeight), sampleCount * IDEAL_ROW_HEIGHT));
        return new MiniMapLayout(lineCount, sampleCount, renderHeight, renderHeight / sampleCount);
    }

    public static ViewportIndicator viewportIndicator(double minimum,
                                                      double maximum,
                                                      double value,
                                                      double visibleAmount) {
        double range = Math.max(0d, maximum - minimum);
        if (range <= 0d || visibleAmount >= range) {
            return new ViewportIndicator(0d, 1d);
        }

        double heightFraction = clamp(visibleAmount / (range + visibleAmount), MIN_VIEWPORT_FRACTION, 1d);
        double availableFraction = Math.max(0d, 1d - heightFraction);
        double valueFraction = clamp((value - minimum) / range, 0d, 1d);
        return new ViewportIndicator(availableFraction * valueFraction, heightFraction);
    }

    public static double scrollValueForFraction(double clickFraction,
                                                double minimum,
                                                double maximum,
                                                double visibleAmount) {
        double range = Math.max(0d, maximum - minimum);
        if (range <= 0d || visibleAmount >= range) {
            return minimum;
        }

        ViewportIndicator viewport = viewportIndicator(minimum, maximum, minimum, visibleAmount);
        double availableFraction = Math.max(0d, 1d - viewport.heightFraction());
        if (availableFraction <= 0d) {
            return minimum;
        }

        double centeredStartFraction = clamp(clickFraction, 0d, 1d) - viewport.heightFraction() / 2d;
        double normalizedStart = clamp(centeredStartFraction / availableFraction, 0d, 1d);
        return minimum + normalizedStart * range;
    }

    private static MiniMapSample sampleLine(String line, int maxColumns) {
        String expandedLine = expandTabs(line == null ? "" : line);
        String visibleLine = stripTrailing(expandedLine);
        if (visibleLine.isBlank()) {
            return MiniMapSample.EMPTY;
        }

        int totalWidth = Math.min(visibleLine.length(), maxColumns);
        int indentWidth = Math.min(countLeadingWhitespace(visibleLine), Math.max(0, totalWidth - 1));
        int contentWidth = Math.max(1, totalWidth - indentWidth);
        double indentFraction = clamp((double) indentWidth / maxColumns, 0d, 0.92d);
        double widthFraction = clamp((double) contentWidth / maxColumns, MIN_SAMPLE_WIDTH, 1d - indentFraction);
        return new MiniMapSample(indentFraction, widthFraction);
    }

    private static List<String> normalizedLines(String text) {
        if (text == null || text.isEmpty()) {
            return List.of("");
        }
        return Arrays.asList(text.split("\\R", -1));
    }

    private static String expandTabs(String line) {
        return line.replace("\t", " ".repeat(TAB_WIDTH));
    }

    private static int countLeadingWhitespace(String text) {
        int index = 0;
        while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
            index++;
        }
        return index;
    }

    private static String stripTrailing(String text) {
        int end = text.length();
        while (end > 0 && Character.isWhitespace(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end);
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    public record MiniMapSample(double indentFraction, double widthFraction) {
        private static final MiniMapSample EMPTY = new MiniMapSample(0d, 0d);

        public MiniMapSample {
            indentFraction = clamp(indentFraction, 0d, 1d);
            widthFraction = clamp(widthFraction, 0d, Math.max(0d, 1d - indentFraction));
        }
    }

    public record MiniMapLayout(int lineCount, int sampleCount, double renderHeight, double rowHeight) {
        public MiniMapLayout {
            lineCount = Math.max(1, lineCount);
            sampleCount = Math.max(1, Math.min(sampleCount, lineCount));
            renderHeight = Math.max(1d, renderHeight);
            rowHeight = Math.max(1d / sampleCount, rowHeight);
        }
    }

    public record ViewportIndicator(double startFraction, double heightFraction) {
        public ViewportIndicator {
            startFraction = clamp(startFraction, 0d, 1d);
            heightFraction = clamp(heightFraction, 0d, 1d);
        }
    }
}

