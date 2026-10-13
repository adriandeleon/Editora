package com.editora.markdown;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * "Format Document" for Markdown: one consistent shape for the things that have several spellings, without
 * touching what the text says. Pure.
 *
 * <p>Two layers. The structural pass is this class's own: setext headings become ATX, a heading and a
 * top-level code fence get a blank line on each side, every table is re-aligned
 * ({@link MarkdownTable#reflow}), ordered lists are numbered in sequence
 * ({@link MarkdownLines#renumber(String, int)}). The rest is the linter's fixes, limited to the rules that
 * are about form ({@link #RULES}): list markers, emphasis and strong style, rule style, spaces inside
 * emphasis, code spans and links, heading marker spacing, blank lines around lists and tables, runs of
 * blank lines, trailing whitespace and the final newline — so the formatter and Fix All never disagree.
 *
 * <p>Code (fenced or indented), front matter, HTML blocks and comments are never changed: both layers work
 * from {@link MarkdownBlocks}. Formatting a formatted document changes nothing.
 */
public final class MarkdownFormat {

    private MarkdownFormat() {}

    /** The lint rules whose fixes the formatter applies. */
    public static final Set<String> RULES = Set.of(
            "MD004", "MD009", "MD012", "MD018", "MD019", "MD023", "MD032", "MD035", "MD037", "MD038", "MD039", "MD047",
            "MD049", "MD050", "MD058");

    private static final Pattern DELIMITER_ROW =
            Pattern.compile("^\\s*\\|?\\s*:?-+:?\\s*(\\|\\s*:?-+:?\\s*)*\\|?\\s*$");

    /** Formats the whole document. */
    public static MarkdownLintFix.Result format(String text, int tabSize, MarkdownLintOptions options) {
        return format(text, tabSize, options, 0, Integer.MAX_VALUE);
    }

    /**
     * Formats lines {@code fromLine..toLine} (0-based, inclusive) and leaves every other line as it is. The
     * result's line map says where each input line went.
     */
    public static MarkdownLintFix.Result format(
            String text, int tabSize, MarkdownLintOptions options, int fromLine, int toLine) {
        if (text == null || text.isEmpty()) {
            return new MarkdownLintFix.Result(text, new int[0]);
        }
        boolean whole = fromLine <= 0 && toLine == Integer.MAX_VALUE;
        MarkdownLintFix.Result result = once(text, tabSize, options, fromLine, toLine);
        // A whole-document format runs until it settles, so that formatting twice is formatting once.
        for (int round = 0; round < 3 && whole && !result.text().equals(text); round++) {
            MarkdownLintFix.Result again = once(result.text(), tabSize, options, fromLine, toLine);
            if (again.text().equals(result.text())) {
                break;
            }
            result = new MarkdownLintFix.Result(again.text(), compose(result.lineMap(), again));
        }
        return result.text().equals(text) ? new MarkdownLintFix.Result(text, result.lineMap()) : result;
    }

    private static int[] compose(int[] first, MarkdownLintFix.Result second) {
        int[] out = new int[first.length];
        for (int i = 0; i < out.length; i++) {
            out[i] = second.mapLine(first[i]);
        }
        return out;
    }

    private static MarkdownLintFix.Result once(
            String text, int tabSize, MarkdownLintOptions options, int fromLine, int toLine) {
        MarkdownLintFix.Result shaped = structure(text, fromLine, toLine);
        int from = fromLine <= 0 ? 0 : shaped.mapLine(fromLine);
        int to = toLine == Integer.MAX_VALUE ? Integer.MAX_VALUE : shaped.mapLine(toLine);
        String numbered = renumber(shaped.text(), from, to);
        Set<String> off = new HashSet<>();
        for (MarkdownLint.Rule rule : MarkdownLint.RULES) {
            if (!RULES.contains(rule.code())) {
                off.add(rule.code());
            }
        }
        MarkdownLintFix.Scope scope = fromLine <= 0 && toLine == Integer.MAX_VALUE
                ? MarkdownLintFix.Scope.ALL
                : (line, code) -> line >= from && line <= to;
        MarkdownLintFix.Result fixed = MarkdownLintFix.fix(
                numbered, off, Math.max(1, tabSize), scope, options == null ? MarkdownLintOptions.DEFAULT : options);
        return new MarkdownLintFix.Result(fixed.text(), compose(shaped.lineMap(), fixed));
    }

    // --- structure -------------------------------------------------------------------------------------

    private static MarkdownLintFix.Result structure(String text, int fromLine, int toLine) {
        String[] lines = text.split("\n", -1);
        MarkdownBlocks blocks = MarkdownBlocks.scan(lines);
        Map<Integer, MarkdownOutline.Heading> headings = new HashMap<>();
        for (MarkdownOutline.Heading h : MarkdownOutline.headings(text)) {
            headings.put(h.line(), h);
        }
        List<String> out = new ArrayList<>(lines.length + 16);
        int[] map = new int[lines.length];
        boolean blankDue = false;
        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            boolean inRange = i >= fromLine && i <= toLine;
            if (blankDue && !line.isBlank() && !(i == lines.length - 1 && line.isEmpty())) {
                out.add("");
            }
            blankDue = false;
            if (!inRange || !isTopLevel(line)) {
                map[i] = out.size();
                out.add(line);
                i++;
                continue;
            }
            MarkdownOutline.Heading heading = blocks.isText(i) ? headings.get(i) : null;
            if (heading != null) {
                blankBefore(out);
                int last = i;
                String atx = line;
                if (!line.startsWith("#")) {
                    last = setextUnderline(lines, blocks, i);
                    if (last < 0) {
                        last = i; // not what it seemed: leave it
                    } else {
                        atx = "#".repeat(heading.level()) + " "
                                + heading.title().strip();
                    }
                }
                for (int k = i; k <= last; k++) {
                    map[k] = out.size();
                }
                out.add(atx);
                blankDue = true;
                i = last + 1;
                continue;
            }
            if (blocks.kind(i) == MarkdownBlocks.Kind.FENCE_OPEN) {
                blankBefore(out);
            } else if (blocks.isText(i) && isTableStart(lines, blocks, i)) {
                int end = i + 2;
                while (end < lines.length && blocks.isText(end) && MarkdownTable.isRow(lines[end])) {
                    end++;
                }
                String block = String.join("\n", java.util.Arrays.copyOfRange(lines, i, end));
                String[] reflowed = MarkdownTable.reflow(block).split("\n", -1);
                boolean same = reflowed.length == end - i;
                for (int k = i; k < end; k++) {
                    map[k] = out.size();
                    out.add(same ? reflowed[k - i] : lines[k]);
                }
                i = end;
                continue;
            }
            map[i] = out.size();
            out.add(line);
            blankDue = blocks.kind(i) == MarkdownBlocks.Kind.FENCE_CLOSE;
            i++;
        }
        return new MarkdownLintFix.Result(String.join("\n", out), map);
    }

    /** Not indented and not quoted: where a blank line can be added without changing what a block belongs to. */
    private static boolean isTopLevel(String line) {
        return !line.isEmpty() && line.charAt(0) != ' ' && line.charAt(0) != '\t' && line.charAt(0) != '>';
    }

    private static void blankBefore(List<String> out) {
        if (!out.isEmpty() && !out.get(out.size() - 1).isBlank()) {
            out.add("");
        }
    }

    /** The line of the {@code ===} / {@code ---} under the setext heading starting at {@code start}, or -1. */
    private static int setextUnderline(String[] lines, MarkdownBlocks blocks, int start) {
        for (int j = start + 1; j < lines.length && blocks.isText(j) && !lines[j].isBlank(); j++) {
            String body = lines[j].strip();
            char c = body.charAt(0);
            if ((c == '=' || c == '-') && body.chars().allMatch(ch -> ch == c)) {
                return j;
            }
        }
        return -1;
    }

    private static boolean isTableStart(String[] lines, MarkdownBlocks blocks, int i) {
        return MarkdownTable.isRow(lines[i])
                && i + 1 < lines.length
                && blocks.isText(i + 1)
                && lines[i + 1].indexOf('-') >= 0
                && DELIMITER_ROW.matcher(lines[i + 1]).matches();
    }

    // --- ordered lists ---------------------------------------------------------------------------------

    private static String renumber(String text, int fromLine, int toLine) {
        String current = text;
        int line = 0;
        int offset = 0;
        boolean previousOrdered = false;
        int previousIndent = -1;
        while (offset <= current.length()) {
            int end = current.indexOf('\n', offset);
            if (end < 0) {
                end = current.length();
            }
            MarkdownLine parsed = MarkdownLine.parse(current.substring(offset, end));
            boolean ordered = parsed.isOrdered();
            int indent = parsed.indent().length();
            // Asked once per run of items, at the first line that is not directly under a sibling.
            if (ordered && line >= fromLine && line <= toLine && !(previousOrdered && previousIndent == indent)) {
                MarkdownEdit edit = MarkdownLines.renumber(current, offset);
                if (edit != null
                        && lineCount(edit.replacement()) == lineCount(current.substring(edit.from(), edit.to()))) {
                    current = current.substring(0, edit.from()) + edit.replacement() + current.substring(edit.to());
                    end = current.indexOf('\n', offset);
                    if (end < 0) {
                        end = current.length();
                    }
                }
            }
            previousOrdered = ordered;
            previousIndent = indent;
            offset = end + 1;
            line++;
        }
        return current;
    }

    private static int lineCount(String s) {
        int n = 1;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') {
                n++;
            }
        }
        return n;
    }
}
