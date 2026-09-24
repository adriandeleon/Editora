package com.editora.git;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Semantic spans within Git's human-readable output, independent of file and URL links. */
public final class GitOutputHighlights {

    public record Span(int start, int end, String styleClass) {}

    private static final Pattern DIFF_STAT = Pattern.compile("^\\s*.+?\\s+\\|\\s+(\\d+)(?:\\s+([+\\-=]+))?\\s*$");
    private static final Pattern SUMMARY = Pattern.compile(
            "^\\s*(\\d+ files? changed)(?:, (\\d+ insertions?\\(\\+\\)))?(?:, (\\d+ deletions?\\(-\\)))?\\s*$");
    private static final Pattern MODE = Pattern.compile("^\\s*(create mode|delete mode)\\s+\\d+\\s+.+$");
    private static final Pattern NEW_REF = Pattern.compile("^\\s*\\* \\[new (?:branch|tag)\\].*$");
    private static final Pattern FORCED_REF = Pattern.compile("^\\s*\\+ .+\\(forced update\\)\\s*$");

    private GitOutputHighlights() {}

    public static List<Span> find(String line) {
        if (line == null || line.isEmpty()) {
            return List.of();
        }
        List<Span> spans = new ArrayList<>();
        Matcher stat = DIFF_STAT.matcher(line);
        if (stat.matches()) {
            add(spans, stat, 1, "git-output-count");
            if (stat.group(2) != null) {
                int start = stat.start(2);
                String graph = stat.group(2);
                for (int i = 0; i < graph.length(); ) {
                    char marker = graph.charAt(i);
                    int end = i + 1;
                    while (end < graph.length() && graph.charAt(end) == marker) {
                        end++;
                    }
                    String style =
                            switch (marker) {
                                case '+' -> "diff-inserted";
                                case '-' -> "diff-deleted";
                                default -> "git-output-neutral";
                            };
                    spans.add(new Span(start + i, start + end, style));
                    i = end;
                }
            }
            return List.copyOf(spans);
        }
        Matcher summary = SUMMARY.matcher(line);
        if (summary.matches()) {
            add(spans, summary, 1, "git-output-summary");
            add(spans, summary, 2, "diff-inserted");
            add(spans, summary, 3, "diff-deleted");
            return List.copyOf(spans);
        }
        Matcher mode = MODE.matcher(line);
        if (mode.matches()) {
            add(spans, mode, 1, "git-output-mode");
            return List.copyOf(spans);
        }
        if (NEW_REF.matcher(line).matches()) {
            spans.add(new Span(0, line.length(), "git-output-added"));
        } else if (FORCED_REF.matcher(line).matches()) {
            spans.add(new Span(0, line.length(), "git-output-forced"));
        }
        return List.copyOf(spans);
    }

    private static void add(List<Span> spans, Matcher matcher, int group, String styleClass) {
        if (matcher.group(group) != null) {
            spans.add(new Span(matcher.start(group), matcher.end(group), styleClass));
        }
    }
}
