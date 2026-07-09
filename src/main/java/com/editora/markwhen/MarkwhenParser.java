package com.editora.markwhen;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure, tolerant parser for the <a href="https://markwhen.com">Markwhen</a> timeline format (a useful
 * subset). Line-based, never throws — an unrecognized line is simply skipped — so a half-typed or
 * malformed document still yields a best-effort {@link Timeline} for the live preview. No JavaFX (unit
 * tested like {@code com.editora.csv.CsvParser} / {@code com.editora.http.HttpFile}).
 *
 * <p><b>Header/frontmatter</b> (top of file, optionally fenced with {@code ---}): {@code title:} → the
 * title, {@code #Tag: color} → a {@link Timeline.TagColor}, any other {@code key: value} → {@code
 * settings}. When unfenced, the header is the leading run of {@code key: value} / {@code #tag: color} /
 * {@code //} lines, ending at the first blank line, {@code #} section header, or event (a line whose text
 * before {@code :} parses as an {@link MwDate} — so {@code 2023: New Year} is an event, not a header key).
 *
 * <p><b>Body</b>: {@code //} comments and blanks are skipped; a {@code #}…{@code ######} header (whitespace
 * after the hashes — which distinguishes {@code # Travel} from a {@code #Travel:} tag decl) opens a group,
 * nested by heading level and auto-closed when a same-or-higher-level header appears or at EOF (exactly
 * like Markdown); a {@code style: section} line directly under a header marks it a full-width section;
 * every other line is parsed as an event {@code DATE_EXPR : LABEL} (the date-expr split into a range on a
 * whitespace-surrounded {@code -}/{@code /}, which cleanly disambiguates the separator from the
 * date-internal {@code -}/{@code /}).
 */
public final class MarkwhenParser {

    private MarkwhenParser() {}

    private static final Pattern TAG_COLOR = Pattern.compile("^#([\\w-]+)\\s*:\\s*(.+)$");
    private static final Pattern EVENT_TAG = Pattern.compile("#[\\w-]+");
    /** The range separator: a {@code -} or {@code /} with surrounding whitespace (so date-internal
     *  {@code 2023-01-15} / {@code 2023/01/15} never splits). */
    private static final Pattern RANGE_SEP = Pattern.compile("\\s+[-/]\\s+");

    /** Parses {@code text} into a {@link Timeline}; never throws (bad lines are dropped). */
    public static Timeline parse(String text) {
        String title = null;
        List<Timeline.TagColor> tagColors = new ArrayList<>();
        Map<String, String> settings = new LinkedHashMap<>();
        List<MwNode> roots = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return new Timeline(null, tagColors, roots, settings);
        }
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);

        int bodyStart = headerEnd(lines);
        for (int i = 0; i < bodyStart; i++) {
            String t = lines[i].strip();
            if (t.isEmpty() || t.equals("---") || isComment(t)) {
                continue;
            }
            Matcher tag = TAG_COLOR.matcher(t);
            if (tag.matches()) {
                tagColors.add(new Timeline.TagColor(tag.group(1), stripTrailingComment(tag.group(2))));
                continue;
            }
            int colon = t.indexOf(':');
            if (colon > 0) {
                String key = t.substring(0, colon).strip();
                String val = stripTrailingComment(t.substring(colon + 1).strip());
                if (key.equalsIgnoreCase("title")) {
                    title = val;
                } else {
                    settings.put(key, val);
                }
            }
        }

        // Body: a heading-level stack of open groups.
        Deque<Frame> stack = new ArrayDeque<>();
        for (int i = bodyStart; i < lines.length; i++) {
            String line = lines[i];
            String t = line.strip();
            if (t.isEmpty() || isComment(t)) {
                continue;
            }
            int level = headingLevel(line);
            if (level > 0) {
                while (!stack.isEmpty() && stack.peek().level >= level) {
                    closeFrame(stack, roots);
                }
                String name = line.stripLeading().substring(level).strip();
                stack.push(new Frame(level, name, i));
                continue;
            }
            if (!stack.isEmpty() && isStyleSection(t)) {
                stack.peek().isSection = true;
                continue;
            }
            MwNode.Event event = parseEvent(t, i);
            if (event != null) {
                addChild(stack, roots, event);
            }
            // else: a stray property / garbage line — dropped (tolerant).
        }
        while (!stack.isEmpty()) {
            closeFrame(stack, roots);
        }
        return new Timeline(title, tagColors, roots, settings);
    }

    /** The body-start index: past a {@code ---} fence if present, else past the leading header run. */
    private static int headerEnd(String[] lines) {
        int first = 0;
        while (first < lines.length && lines[first].isBlank()) {
            first++;
        }
        if (first < lines.length && lines[first].strip().equals("---")) {
            for (int k = first + 1; k < lines.length; k++) {
                if (lines[k].strip().equals("---")) {
                    return k + 1;
                }
            }
            return lines.length; // unterminated fence — treat the rest as header (tolerant)
        }
        int k = 0;
        while (k < lines.length) {
            String t = lines[k].strip();
            if (t.isEmpty()) {
                break; // a blank line ends an unfenced header
            }
            if (isComment(t)) {
                k++;
                continue;
            }
            if (headingLevel(lines[k]) > 0) {
                break; // a section header starts the body
            }
            int colon = t.indexOf(':');
            if (colon <= 0) {
                break; // not key:value / tag decl → body
            }
            String key = t.substring(0, colon).strip();
            if (MwDate.parse(key) != null) {
                break; // the "key" is a date → this is an event, not a header line
            }
            k++;
        }
        return k;
    }

    private static MwNode.Event parseEvent(String t, int line) {
        int colon = t.indexOf(':');
        if (colon < 0) {
            return null;
        }
        String dateExpr = t.substring(0, colon).strip();
        if (dateExpr.isEmpty()) {
            return null;
        }
        String label = t.substring(colon + 1).strip();
        String[] parts = RANGE_SEP.split(dateExpr, 2);
        MwDate start = MwDate.parse(parts[0].strip());
        if (start == null) {
            return null;
        }
        MwDate end = null;
        if (parts.length == 2) {
            end = MwDate.parse(parts[1].strip());
            if (end == null) {
                return null; // a malformed range end → not an event
            }
        }
        return new MwNode.Event(start, end, label, extractTags(label), line);
    }

    private static List<String> extractTags(String label) {
        List<String> tags = new ArrayList<>();
        Matcher m = EVENT_TAG.matcher(label);
        while (m.find()) {
            tags.add(m.group().substring(1)); // drop the leading '#'
        }
        return tags;
    }

    private static void closeFrame(Deque<Frame> stack, List<MwNode> roots) {
        Frame f = stack.pop();
        MwNode.Group group = new MwNode.Group(f.name, f.isSection, f.children, f.line);
        (stack.isEmpty() ? roots : stack.peek().children).add(group);
    }

    private static void addChild(Deque<Frame> stack, List<MwNode> roots, MwNode node) {
        (stack.isEmpty() ? roots : stack.peek().children).add(node);
    }

    /** The heading level (1–6) when {@code line} is a {@code #}…{@code ######} header — i.e. leading
     *  hashes followed by whitespace — else 0. The whitespace requirement makes {@code #Travel:} (a tag
     *  decl) NOT a heading. Mirrors {@code FoldRegions.headingLevel}. */
    static int headingLevel(String line) {
        String ls = line.stripLeading();
        int h = 0;
        while (h < ls.length() && ls.charAt(h) == '#') {
            h++;
        }
        return (h >= 1 && h <= 6 && h < ls.length() && Character.isWhitespace(ls.charAt(h))) ? h : 0;
    }

    private static boolean isComment(String stripped) {
        return stripped.startsWith("//");
    }

    private static boolean isStyleSection(String stripped) {
        String s = stripped.replace(" ", "").toLowerCase(java.util.Locale.ROOT);
        return s.equals("style:section");
    }

    private static String stripTrailingComment(String value) {
        int c = value.indexOf("//");
        return (c >= 0 ? value.substring(0, c) : value).strip();
    }

    /** Mutable parse-time frame for an open {@code #}-header group. */
    private static final class Frame {
        final int level;
        final String name;
        final int line;
        boolean isSection;
        final List<MwNode> children = new ArrayList<>();

        Frame(int level, String name, int line) {
            this.level = level;
            this.name = name;
            this.line = line;
        }
    }
}
