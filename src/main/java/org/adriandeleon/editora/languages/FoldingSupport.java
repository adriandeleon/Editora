package org.adriandeleon.editora.languages;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Utility for computing foldable regions from document text.
 * Three strategies:
 *   computeBraceFolds  – { } delimited blocks (Java, JS, CSS, JSON, XML/HTML)
 *   computeIndentFolds – indentation-sensitive languages (Python, YAML, Shell)
 *   computeMarkdownFolds – fold on heading level changes in Markdown
 *
 * All methods are pure/stateless and safe to call from a background thread.
 */
public final class FoldingSupport {

    private static final int MIN_FOLD_LINES = 2;

    private FoldingSupport() {
    }

    public static List<FoldRange> computeBraceFolds(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        String[] lines = text.split("\r?\n|\r", -1);
        List<FoldRange> folds = new ArrayList<>();
        Deque<Integer> stack = new ArrayDeque<>();

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            for (int j = 0; j < line.length(); j++) {
                char c = line.charAt(j);
                if (c == '{' || c == '[') {
                    stack.push(i);
                } else if (c == '}' || c == ']') {
                    if (!stack.isEmpty()) {
                        int openLine = stack.pop();
                        if (i - openLine >= MIN_FOLD_LINES) {
                            folds.add(new FoldRange(openLine, i));
                        }
                    }
                }
            }
        }
        return List.copyOf(folds);
    }

    public static List<FoldRange> computeIndentFolds(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        String[] lines = text.split("\r?\n|\r", -1);
        int[] indents = resolveIndents(lines);
        List<FoldRange> folds = new ArrayList<>();

        for (int i = 0; i < lines.length - 1; i++) {
            if (lines[i].isBlank()) {
                continue;
            }
            int currentIndent = indents[i];
            if (indents[i + 1] > currentIndent) {
                int endLine = i + 1;
                while (endLine < lines.length - 1) {
                    if (!lines[endLine + 1].isBlank() && indents[endLine + 1] <= currentIndent) {
                        break;
                    }
                    endLine++;
                }
                if (endLine - i >= MIN_FOLD_LINES) {
                    folds.add(new FoldRange(i, endLine));
                }
            }
        }
        return List.copyOf(folds);
    }

    public static List<FoldRange> computeMarkdownFolds(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        String[] lines = text.split("\r?\n|\r", -1);
        List<int[]> headings = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            int level = headingLevel(lines[i]);
            if (level > 0) {
                headings.add(new int[]{i, level});
            }
        }

        List<FoldRange> folds = new ArrayList<>();
        for (int h = 0; h < headings.size(); h++) {
            int startLine = headings.get(h)[0];
            int level = headings.get(h)[1];
            int endLine = lines.length - 1;
            for (int k = h + 1; k < headings.size(); k++) {
                if (headings.get(k)[1] <= level) {
                    endLine = headings.get(k)[0] - 1;
                    break;
                }
            }
            if (endLine - startLine >= MIN_FOLD_LINES) {
                folds.add(new FoldRange(startLine, endLine));
            }
        }
        return List.copyOf(folds);
    }

    private static int[] resolveIndents(String[] lines) {
        int[] indents = new int[lines.length];
        int lastKnown = 0;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].isBlank()) {
                indents[i] = lastKnown;
            } else {
                indents[i] = countLeadingSpaces(lines[i]);
                lastKnown = indents[i];
            }
        }
        return indents;
    }

    private static int countLeadingSpaces(String line) {
        int count = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == ' ') {
                count++;
            } else if (c == '\t') {
                count += 4;
            } else {
                break;
            }
        }
        return count;
    }

    private static int headingLevel(String line) {
        if (line == null || !line.startsWith("#")) {
            return 0;
        }
        int level = 0;
        while (level < line.length() && line.charAt(level) == '#') {
            level++;
        }
        if (level < line.length() && line.charAt(level) == ' ') {
            return level;
        }
        return 0;
    }
}

