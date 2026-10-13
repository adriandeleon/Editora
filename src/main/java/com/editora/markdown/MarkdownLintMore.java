package com.editora.markdown;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.editora.markdown.MarkdownLint.Edit;
import com.editora.markdown.MarkdownLint.Emitter;
import org.commonmark.ext.footnotes.FootnotesExtension;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.BulletList;
import org.commonmark.node.Code;
import org.commonmark.node.Document;
import org.commonmark.node.Emphasis;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.Heading;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.Image;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Link;
import org.commonmark.node.LinkReferenceDefinition;
import org.commonmark.node.ListBlock;
import org.commonmark.node.ListItem;
import org.commonmark.node.Node;
import org.commonmark.node.OrderedList;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SourceSpan;
import org.commonmark.node.StrongEmphasis;
import org.commonmark.node.Text;
import org.commonmark.node.ThematicBreak;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;

/**
 * The lint rules that need to know how the document <em>parses</em> — list structure, emphasis and link nodes,
 * tables, raw HTML — rather than what a line looks like. They read a CommonMark parse of the document (with the
 * source position of every node) so that, for instance, the asterisks of {@code **bold** and **more**} are never
 * mistaken for emphasis with spaces inside it: only text the parser left as text is examined.
 *
 * <p>Rules here: MD003, MD004, MD005, MD007, MD011, MD013, MD024, MD029, MD030, MD032, MD033, MD035, MD036, MD037,
 * MD038, MD039, MD042, MD045, MD046, MD048, MD049, MD050, MD051, MD053, MD055, MD056, MD058 and the two link-target
 * rules. Every scan is linear in the document: one walk of the tree, and per line a single left-to-right pass.
 *
 * <p>Pure; called by {@link MarkdownLint} only.
 */
final class MarkdownLintMore {

    private MarkdownLintMore() {}

    /** The parse the rules read. No autolink extension: a bare URL must stay text (MD034 is about those). */
    private static final Parser PARSER = Parser.builder()
            .extensions(List.of(
                    TablesExtension.create(),
                    StrikethroughExtension.create(),
                    FootnotesExtension.create(),
                    MarkdownMath.extension()))
            .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES)
            .build();

    private static final Pattern HTML_TAG = Pattern.compile("<([A-Za-z][A-Za-z0-9-]*)(?=[\\s/>])");
    private static final Pattern IMG_TAG = Pattern.compile("<img\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern ALT_ATTR = Pattern.compile("\\balt\\s*=", Pattern.CASE_INSENSITIVE);
    private static final Pattern REVERSED_LINK = Pattern.compile("\\(([^()]+)\\)\\[([^\\]^][^\\]]*)\\](?!\\()");
    private static final Pattern SCHEME = Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*:");
    private static final Pattern LINE_FRAGMENT = Pattern.compile("L\\d+(C\\d+)?(-L\\d+(C\\d+)?)?");
    private static final Pattern HTML_ANCHOR = Pattern.compile("\\b(?:id|name)\\s*=\\s*[\"']([^\"']+)[\"']");
    private static final Pattern LINK_ONLY_LINE = Pattern.compile(
            "^\\s*(?:[-*+>]\\s+|\\d+[.)]\\s+)*(?:!?\\[[^\\]]*\\]\\([^)]*\\)|<[^>]+>|\\[[^\\]]+\\]:.*)\\s*$");
    private static final String MD036_PUNCTUATION = ".,;:!?\u3002\uff0c\uff1b\uff1a\uff01\uff1f";

    /** What one run of the rules knows and remembers. */
    private static final class Run {
        final Emitter emit;
        final MarkdownBlocks blocks;
        final MarkdownLintOptions opt;
        final Set<String> definedLabels = new HashSet<>();
        final List<LinkReferenceDefinition> definitions = new ArrayList<>();
        String headingStyle;
        char bullet;
        String hrStyle;
        String codeBlockStyle;
        String fenceStyle;
        String emphasisStyle;
        String strongStyle;
        Set<String> anchors;
        final Set<String> headingsSeen = new HashSet<>();
        final List<Set<String>> siblingHeadings = new ArrayList<>();

        Run(Emitter emit, MarkdownBlocks blocks) {
            this.emit = emit;
            this.blocks = blocks;
            this.opt = emit.options();
            for (int i = 0; i <= 6; i++) {
                siblingHeadings.add(new HashSet<>());
            }
        }

        String line(int line) {
            return blocks.line(line);
        }

        private int shapeLine = -1;
        private boolean shape;

        /** Whether {@code line} contains ")[" at all — asked once per line, not once per text run of it. */
        boolean hasReversedLinkShape(int line) {
            if (line != shapeLine) {
                shapeLine = line;
                shape = blocks.line(line).contains(")[");
            }
            return shape;
        }

        String raw(SourceSpan s) {
            String line = line(s.getLineIndex());
            int from = Math.min(s.getColumnIndex(), line.length());
            return line.substring(from, Math.min(from + s.getLength(), line.length()));
        }
    }

    static void check(Emitter emit, MarkdownBlocks blocks) {
        Run run = new Run(emit, blocks);
        lineLength(run);
        // The parse sees the document without its front matter (blank lines keep every line number in place).
        StringBuilder source = new StringBuilder();
        for (int i = 0; i < blocks.lineCount(); i++) {
            if (i > 0) {
                source.append('\n');
            }
            if (i > blocks.frontMatterEnd()) {
                source.append(blocks.line(i));
            }
        }
        Node document = PARSER.parse(source.toString());
        collectDefinitions(run, document);
        walk(run, document);
        unusedDefinitions(run);
    }

    // --- the walk ---------------------------------------------------------------------------------------

    /** Depth-first, without recursion (a deeply nested quote must not overflow the stack). */
    private static void walk(Run run, Node root) {
        Node node = root;
        while (node != null) {
            visit(run, node);
            if (node.getFirstChild() != null) {
                node = node.getFirstChild();
                continue;
            }
            while (node != null && node != root && node.getNext() == null) {
                node = node.getParent();
            }
            node = node == null || node == root ? null : node.getNext();
        }
    }

    private static void visit(Run run, Node node) {
        if (first(node) == null && !(node instanceof Document)) {
            return; // a synthesised node (an empty table cell): nothing in the source to point at
        }
        if (node instanceof Heading h) {
            heading(run, h);
        } else if (node instanceof BulletList || node instanceof OrderedList) {
            list(run, (ListBlock) node);
        } else if (node instanceof ThematicBreak) {
            thematicBreak(run, node);
        } else if (node instanceof FencedCodeBlock f) {
            codeBlock(run, node, "fenced");
            fenceStyle(run, f);
        } else if (node instanceof IndentedCodeBlock) {
            codeBlock(run, node, "indented");
        } else if (node instanceof TableBlock) {
            table(run, node);
        } else if (node instanceof Paragraph p) {
            emphasisAsHeading(run, p);
        } else if (node instanceof Emphasis || node instanceof StrongEmphasis) {
            emphasisStyle(run, node);
        } else if (node instanceof Link l) {
            link(run, l);
        } else if (node instanceof Image img) {
            image(run, img);
        } else if (node instanceof Code) {
            codeSpan(run, node);
        } else if (node instanceof HtmlInline || node instanceof HtmlBlock) {
            html(run, node);
        }
        if (node.getFirstChild() instanceof Text || hasTextChild(node)) {
            textRuns(run, node);
        }
    }

    private static boolean hasTextChild(Node node) {
        for (Node c = node.getFirstChild(); c != null; c = c.getNext()) {
            if (c instanceof Text) {
                return true;
            }
        }
        return false;
    }

    private static SourceSpan first(Node node) {
        List<SourceSpan> spans = node.getSourceSpans();
        return spans.isEmpty() ? null : spans.get(0);
    }

    private static SourceSpan last(Node node) {
        List<SourceSpan> spans = node.getSourceSpans();
        return spans.isEmpty() ? null : spans.get(spans.size() - 1);
    }

    private static boolean all(Emitter emit, String... codes) {
        for (String code : codes) {
            if (emit.on(code)) {
                return true;
            }
        }
        return false;
    }

    // --- MD013 ------------------------------------------------------------------------------------------

    private static void lineLength(Run run) {
        if (!run.emit.on("MD013")) {
            return;
        }
        int limit = Math.max(1, run.opt.intOption("MD013.line_length", 80));
        boolean code = run.opt.boolOption("MD013.code_blocks", true);
        boolean tables = run.opt.boolOption("MD013.tables", true);
        boolean headings = run.opt.boolOption("MD013.headings", true);
        boolean strict = run.opt.boolOption("MD013.strict", false);
        MarkdownBlocks blocks = run.blocks;
        for (int i = 0; i < blocks.lineCount(); i++) {
            String line = blocks.line(i);
            if (line.length() <= limit || blocks.kind(i) == MarkdownBlocks.Kind.FRONT_MATTER) {
                continue;
            }
            if (blocks.isCode(i) ? !code : skipLongProse(blocks, i, tables, headings)) {
                continue;
            }
            // A line with nothing to break after the limit (a long URL) cannot be made shorter.
            if (!strict && noSpaceFrom(line, limit)) {
                continue;
            }
            run.emit.add(i + 1, limit + 1, line.length() - limit, "MD013", "MD013");
        }
    }

    private static boolean skipLongProse(MarkdownBlocks blocks, int i, boolean tables, boolean headings) {
        String content = blocks.content(i);
        if (!headings && MarkdownLint.leadingHashes(content) > 0) {
            return true;
        }
        if (!tables && content.stripLeading().startsWith("|")) {
            return true;
        }
        return content.indexOf("](") + content.indexOf("]:") + content.indexOf('<') > -3
                && LINK_ONLY_LINE.matcher(content).matches(); // only a link, an autolink or a definition
    }

    private static boolean noSpaceFrom(String line, int from) {
        for (int p = from; p < line.length(); p++) {
            if (Character.isWhitespace(line.charAt(p))) {
                return false;
            }
        }
        return true;
    }

    // --- headings: MD003, MD024 ---------------------------------------------------------------------------

    private static void heading(Run run, Heading h) {
        SourceSpan s = first(h);
        String raw = run.raw(s).strip();
        if (run.emit.on("MD003")) {
            String style =
                    !raw.startsWith("#") ? "setext" : raw.matches(".*[^\\\\#]\\s*#+\\s*$") ? "atx_closed" : "atx";
            String wanted = run.opt.stringOption("MD003.style", "consistent");
            if (wanted.equals("consistent")) {
                if (run.headingStyle == null) {
                    run.headingStyle = style;
                }
                wanted = run.headingStyle;
            }
            // A setext document has no way to write a level-3 heading: ATX is what it uses there.
            boolean setextFamily = wanted.startsWith("setext");
            String needed = setextFamily && h.getLevel() > 2
                    ? (wanted.equals("setext_with_atx_closed") ? "atx_closed" : "atx")
                    : setextFamily ? "setext" : wanted;
            boolean lenient = wanted.equals("setext") && h.getLevel() > 2; // either ATX form will do
            if (!style.equals(needed) && !(lenient && !style.equals("setext"))) {
                run.emit.add(s.getLineIndex() + 1, s.getColumnIndex() + 1, raw.length(), "MD003", "MD003");
            }
        }
        if (run.emit.on("MD024")) {
            String text = plainText(h).strip();
            int level = Math.max(1, Math.min(6, h.getLevel()));
            // Editora's default is siblings_only: "## Added" under every release of a changelog is not a mistake.
            boolean siblings = run.opt.boolOption(
                    "MD024.siblings_only", run.opt.boolOption("MD024.allow_different_nesting", true));
            for (int deeper = level + 1; deeper <= 6; deeper++) {
                run.siblingHeadings.get(deeper).clear();
            }
            Set<String> seen = siblings ? run.siblingHeadings.get(level) : run.headingsSeen;
            if (!text.isEmpty() && !seen.add(text)) {
                run.emit.add(s.getLineIndex() + 1, s.getColumnIndex() + 1, raw.length(), "MD024", "MD024");
            }
        }
    }

    private static String plainText(Node node) {
        StringBuilder sb = new StringBuilder();
        Node n = node.getFirstChild();
        while (n != null) {
            if (n instanceof Text t) {
                sb.append(t.getLiteral());
            } else if (n instanceof Code c) {
                sb.append(c.getLiteral());
            }
            if (n.getFirstChild() != null) {
                n = n.getFirstChild();
                continue;
            }
            while (n != null && n != node && n.getNext() == null) {
                n = n.getParent();
            }
            n = n == null || n == node ? null : n.getNext();
        }
        return sb.toString();
    }

    // --- lists: MD004, MD005, MD007, MD029, MD030, MD032 ---------------------------------------------------

    private static void list(Run run, ListBlock list) {
        Emitter emit = run.emit;
        boolean bullets = list instanceof BulletList;
        if (!all(emit, "MD004", "MD005", "MD007", "MD029", "MD030", "MD032")) {
            return;
        }
        List<ListItem> items = new ArrayList<>();
        for (Node c = list.getFirstChild(); c != null; c = c.getNext()) {
            if (c instanceof ListItem li && first(li) != null) {
                items.add(li);
            }
        }
        if (items.isEmpty()) {
            return;
        }
        SourceSpan head = first(items.get(0));
        int firstColumn = markerColumn(run, head);
        int firstEnd = markerEnd(run.line(head.getLineIndex()), firstColumn);

        // MD007: a bullet list nested in bullet lists only sits `indent` columns right of its parent's marker.
        int wantedColumn = -1;
        if (bullets
                && emit.on("MD007")
                && list.getParent() instanceof ListItem parentItem
                && bulletAncestry(parentItem)) {
            SourceSpan parent = first(parentItem);
            String parentLine = run.line(parent.getLineIndex());
            int parentColumn = markerColumn(run, parent);
            int parentEnd = markerEnd(parentLine, parentColumn);
            // Only when the parent is written "- text": after "-   text" the child cannot sit two columns in.
            if (parentEnd == parentColumn + 1 && spacesAt(parentLine, parentEnd) == 1) {
                wantedColumn = parentColumn + Math.max(1, run.opt.intOption("MD007.indent", 2));
            }
        }
        boolean rightAligned = !bullets && rightAligned(run, items);
        int previousNumber = -1;
        boolean incrementing = false;
        for (int index = 0; index < items.size(); index++) {
            ListItem item = items.get(index);
            SourceSpan s = first(item);
            int lineNo = s.getLineIndex() + 1;
            String line = run.line(s.getLineIndex());
            int column = markerColumn(run, s);
            int end = markerEnd(line, column);
            if (end <= column) {
                continue;
            }
            if (bullets && emit.on("MD004")) {
                char marker = line.charAt(column);
                char wanted = wantedBullet(run, marker);
                if (marker != wanted) {
                    emit.add(
                            lineNo,
                            column + 1,
                            1,
                            "MD004",
                            "MD004",
                            Edit.on(s.getLineIndex(), column, column + 1, String.valueOf(wanted)));
                }
            }
            if (emit.on("MD005") && index > 0 && s.getLineIndex() != head.getLineIndex()) {
                boolean aligned = column == firstColumn || (rightAligned && end == firstEnd);
                if (!aligned) {
                    emit.add(lineNo, 1, Math.max(1, column), "MD005", "MD005");
                }
            }
            if (wantedColumn >= 0 && column != wantedColumn) {
                emit.add(lineNo, 1, Math.max(1, column), "MD007", "MD007");
            }
            if (!bullets && emit.on("MD029")) {
                int number = number(line, column, end);
                String style = run.opt.stringOption("MD029.style", "one_or_ordered");
                if (index == 1 && style.equals("one_or_ordered")) {
                    incrementing = number != previousNumber;
                }
                boolean ok =
                        switch (style) {
                            case "one" -> number == 1;
                            case "zero" -> number == 0;
                            case "ordered" -> index == 0 || number == previousNumber + 1;
                            default -> index == 0 || number == (incrementing ? previousNumber + 1 : previousNumber);
                        };
                if (!ok && number >= 0) {
                    emit.add(lineNo, column + 1, end - column, "MD029", "MD029");
                }
                previousNumber = ok || index == 0
                        ? number
                        : (incrementing || style.equals("ordered") ? previousNumber + 1 : previousNumber);
            }
            if (emit.on("MD030")) {
                int spaces = spacesAt(line, end);
                boolean empty = end + spaces >= line.length();
                boolean multi =
                        item.getFirstChild() != null && item.getFirstChild().getNext() != null;
                int wanted = run.opt.intOption("MD030." + (bullets ? "ul" : "ol") + (multi ? "_multi" : "_single"), 1);
                // Five or more spaces start an indented code block inside the item: that is content, not spacing.
                if (!empty && spaces != wanted && spaces < 5 && line.indexOf('\t', end) != end) {
                    emit.add(lineNo, end + 1, Math.max(1, spaces), "MD030", "MD030");
                }
            }
        }
        if (emit.on("MD032") && !(list.getParent() instanceof ListItem)) {
            blanksAround(run, list, "MD032");
        }
    }

    private static boolean bulletAncestry(ListItem item) {
        for (Node n = item; n != null; n = n.getParent()) {
            if (n instanceof OrderedList) {
                return false;
            }
        }
        return true;
    }

    private static char wantedBullet(Run run, char marker) {
        String style = run.opt.stringOption("MD004.style", "consistent");
        switch (style) {
            case "asterisk":
                return '*';
            case "dash":
                return '-';
            case "plus":
                return '+';
            default:
                if (run.bullet == 0) {
                    run.bullet = marker;
                }
                return run.bullet;
        }
    }

    /** The column of the list marker of the item whose first span is {@code s} (a span may start in its indent). */
    private static int markerColumn(Run run, SourceSpan s) {
        String line = run.line(s.getLineIndex());
        int p = Math.min(s.getColumnIndex(), line.length());
        while (p < line.length() && (line.charAt(p) == ' ' || line.charAt(p) == '\t')) {
            p++;
        }
        return p;
    }

    /** The offset just past the list marker that starts at {@code column} ({@code -}, {@code 12.}), or column. */
    private static int markerEnd(String line, int column) {
        if (column >= line.length()) {
            return column;
        }
        char c = line.charAt(column);
        if (c == '-' || c == '+' || c == '*') {
            return column + 1;
        }
        int p = column;
        while (p < line.length() && Character.isDigit(line.charAt(p)) && p - column < 9) {
            p++;
        }
        return p > column && p < line.length() && (line.charAt(p) == '.' || line.charAt(p) == ')') ? p + 1 : column;
    }

    private static int spacesAt(String line, int from) {
        int p = from;
        while (p < line.length() && line.charAt(p) == ' ') {
            p++;
        }
        return p - from;
    }

    private static int number(String line, int column, int end) {
        try {
            return Integer.parseInt(line.substring(column, end - 1));
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /** Whether an ordered list's markers line up on their right edge (" 9." above "10."). */
    private static boolean rightAligned(Run run, List<ListItem> items) {
        int end = -1;
        boolean differentStarts = false;
        int start = markerColumn(run, first(items.get(0)));
        for (ListItem item : items) {
            SourceSpan s = first(item);
            int column = markerColumn(run, s);
            int e = markerEnd(run.line(s.getLineIndex()), column);
            if (end >= 0 && e != end) {
                return false;
            }
            end = e;
            differentStarts |= column != start;
        }
        return differentStarts;
    }

    /**
     * MD032 / MD058: a list or a table directly under, or directly above, another block. The blank line the fix
     * inserts is only offered at the top level, where an empty line cannot change what a container holds.
     */
    private static void blanksAround(Run run, Node block, String code) {
        SourceSpan head = first(block);
        SourceSpan tail = last(block);
        boolean topLevel = block.getParent() instanceof Document;
        int above = head.getLineIndex() - 1;
        // Two lists back to back (a change of marker starts a new list) are not "text touching a list".
        boolean listAbove = block instanceof ListBlock && block.getPrevious() instanceof ListBlock;
        boolean listBelow = block instanceof ListBlock && block.getNext() instanceof ListBlock;
        if (block.getPrevious() != null
                && !listAbove
                && above >= 0
                && !run.blocks.isBlank(above)
                && !MarkdownLintDirectives.isDirectiveLine(run.blocks.content(above))) {
            Edit[] fix = topLevel ? new Edit[] {Edit.on(head.getLineIndex(), 0, 0, "\n")} : new Edit[0];
            run.emit.add(
                    head.getLineIndex() + 1, head.getColumnIndex() + 1, head.getLength(), code, code + ".before", fix);
        }
        int below = tail.getLineIndex() + 1;
        if (block.getNext() != null && !listBelow && below < run.blocks.lineCount() && !run.blocks.isBlank(below)) {
            Edit[] fix = topLevel ? new Edit[] {Edit.on(below, 0, 0, "\n")} : new Edit[0];
            run.emit.add(
                    tail.getLineIndex() + 1, tail.getColumnIndex() + 1, tail.getLength(), code, code + ".after", fix);
        }
    }

    // --- MD035, MD046, MD048 -------------------------------------------------------------------------------

    private static void thematicBreak(Run run, Node node) {
        if (!run.emit.on("MD035")) {
            return;
        }
        SourceSpan s = first(node);
        String raw = run.raw(s).strip();
        String wanted = run.opt.stringOption("MD035.style", "consistent");
        if (wanted.equals("consistent")) {
            if (run.hrStyle == null) {
                run.hrStyle = raw;
            }
            wanted = run.hrStyle;
        }
        if (!raw.equals(wanted)) {
            int column = s.getColumnIndex();
            run.emit.add(
                    s.getLineIndex() + 1,
                    column + 1,
                    s.getLength(),
                    "MD035",
                    "MD035",
                    Edit.on(s.getLineIndex(), column, column + s.getLength(), wanted));
        }
    }

    private static void codeBlock(Run run, Node node, String style) {
        if (!run.emit.on("MD046")) {
            return;
        }
        String wanted = run.opt.stringOption("MD046.style", "consistent");
        if (wanted.equals("consistent")) {
            if (run.codeBlockStyle == null) {
                run.codeBlockStyle = style;
            }
            wanted = run.codeBlockStyle;
        }
        if (!style.equals(wanted)) {
            SourceSpan s = first(node);
            run.emit.add(s.getLineIndex() + 1, s.getColumnIndex() + 1, s.getLength(), "MD046", "MD046");
        }
    }

    private static void fenceStyle(Run run, FencedCodeBlock fence) {
        if (!run.emit.on("MD048")) {
            return;
        }
        String style = "~".equals(fence.getFenceCharacter()) ? "tilde" : "backtick";
        String wanted = run.opt.stringOption("MD048.style", "consistent");
        if (wanted.equals("consistent")) {
            if (run.fenceStyle == null) {
                run.fenceStyle = style;
            }
            wanted = run.fenceStyle;
        }
        if (!style.equals(wanted)) {
            SourceSpan s = first(fence);
            Integer length = fence.getOpeningFenceLength();
            run.emit.add(s.getLineIndex() + 1, s.getColumnIndex() + 1, length == null ? 3 : length, "MD048", "MD048");
        }
    }

    // --- tables: MD055, MD056, MD058 -------------------------------------------------------------------------

    private static void table(Run run, Node table) {
        Emitter emit = run.emit;
        if (emit.on("MD058")) {
            blanksAround(run, table, "MD058");
        }
        if (!all(emit, "MD055", "MD056")) {
            return;
        }
        String wantedStyle = run.opt.stringOption("MD055.style", "consistent");
        int headerCells = -1;
        int row = 0;
        for (SourceSpan s : table.getSourceSpans()) {
            String raw = run.raw(s);
            String trimmed = raw.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            boolean leading = trimmed.startsWith("|");
            boolean trailing = trimmed.length() > 1 && trimmed.endsWith("|") && !trimmed.endsWith("\\|");
            int cells = cells(trimmed) + 1 - (leading ? 1 : 0) - (trailing ? 1 : 0);
            String style = leading
                    ? (trailing ? "leading_and_trailing" : "leading_only")
                    : (trailing ? "trailing_only" : "no_leading_or_trailing");
            if (row == 0) {
                headerCells = cells;
                if (wantedStyle.equals("consistent")) {
                    wantedStyle = style;
                }
            }
            if (emit.on("MD055") && !style.equals(wantedStyle)) {
                emit.add(s.getLineIndex() + 1, s.getColumnIndex() + 1, s.getLength(), "MD055", "MD055");
            }
            if (emit.on("MD056") && row > 1 && cells != headerCells) { // row 1 is the delimiter row
                emit.add(
                        s.getLineIndex() + 1,
                        s.getColumnIndex() + 1,
                        s.getLength(),
                        "MD056",
                        cells < headerCells ? "MD056.few" : "MD056.many");
            }
            row++;
        }
    }

    /** The number of cell separators in a table row: {@code |} that is neither escaped nor inside a code span. */
    private static int cells(String row) {
        int[] spans = MarkdownBlocks.codeSpans(row);
        int n = 0;
        for (int p = row.indexOf('|'); p >= 0; p = row.indexOf('|', p + 1)) {
            if ((p == 0 || row.charAt(p - 1) != '\\') && !MarkdownBlocks.inRanges(spans, p)) {
                n++;
            }
        }
        return n;
    }

    // --- emphasis: MD036, MD049, MD050 -----------------------------------------------------------------------

    private static void emphasisAsHeading(Run run, Paragraph p) {
        if (!run.emit.on("MD036") || p.getSourceSpans().size() != 1) {
            return;
        }
        Node only = p.getFirstChild();
        if (only == null || only.getNext() != null || !(only instanceof Emphasis || only instanceof StrongEmphasis)) {
            return;
        }
        if (!(p.getParent() instanceof Document)) {
            return; // "**Note**" opening a list item or a quote is a label, not a section title
        }
        StringBuilder text = new StringBuilder();
        for (Node c = only.getFirstChild(); c != null; c = c.getNext()) {
            if (!(c instanceof Text t)) {
                return; // a link, code, nested emphasis: not a plain title
            }
            text.append(t.getLiteral());
        }
        String title = text.toString().strip();
        if (title.isEmpty()) {
            return;
        }
        String punctuation = run.opt.stringOption("MD036.punctuation", MD036_PUNCTUATION);
        if (punctuation.indexOf(title.charAt(title.length() - 1)) >= 0) {
            return;
        }
        SourceSpan s = first(only);
        run.emit.add(s.getLineIndex() + 1, s.getColumnIndex() + 1, s.getLength(), "MD036", "MD036");
    }

    private static void emphasisStyle(Run run, Node node) {
        boolean strong = node instanceof StrongEmphasis;
        String code = strong ? "MD050" : "MD049";
        if (!run.emit.on(code)) {
            return;
        }
        SourceSpan open = first(node);
        SourceSpan close = last(node);
        String openLine = run.line(open.getLineIndex());
        if (open.getColumnIndex() >= openLine.length()) {
            return;
        }
        char delimiter = openLine.charAt(open.getColumnIndex());
        if (delimiter != '*' && delimiter != '_') {
            return;
        }
        String style = delimiter == '*' ? "asterisk" : "underscore";
        String wanted = run.opt.stringOption(code + ".style", "consistent");
        if (wanted.equals("consistent")) {
            String known = strong ? run.strongStyle : run.emphasisStyle;
            if (known == null) {
                known = style;
                if (strong) {
                    run.strongStyle = style;
                } else {
                    run.emphasisStyle = style;
                }
            }
            wanted = known;
        }
        if (style.equals(wanted)) {
            return;
        }
        int width = strong ? 2 : 1;
        char to = wanted.equals("asterisk") ? '*' : '_';
        String closeLine = run.line(close.getLineIndex());
        int closeEnd = close.getColumnIndex() + close.getLength();
        // An underscore only opens or closes emphasis at a word boundary: "snake*case*d" cannot become "_".
        boolean safe = to == '*'
                || ((open.getColumnIndex() == 0
                                || !Character.isLetterOrDigit(openLine.charAt(open.getColumnIndex() - 1)))
                        && (closeEnd >= closeLine.length() || !Character.isLetterOrDigit(closeLine.charAt(closeEnd))));
        String marker = String.valueOf(to).repeat(width);
        Edit[] fix = safe && closeEnd - width >= 0
                ? new Edit[] {
                    Edit.on(open.getLineIndex(), open.getColumnIndex(), open.getColumnIndex() + width, marker),
                    Edit.on(close.getLineIndex(), closeEnd - width, closeEnd, marker)
                }
                : new Edit[0];
        run.emit.add(open.getLineIndex() + 1, open.getColumnIndex() + 1, open.getLength(), code, code, fix);
    }

    // --- links and images: MD039, MD042, MD045, MD051, LINK001, LINK002 -----------------------------------------

    private static void link(Run run, Link link) {
        Emitter emit = run.emit;
        SourceSpan s = first(link);
        int lineNo = s.getLineIndex() + 1;
        String destination = link.getDestination() == null ? "" : link.getDestination();
        if (emit.on("MD039") && link.getSourceSpans().size() == 1) {
            List<Edit> fix = new ArrayList<>();
            if (link.getFirstChild() instanceof Text t && first(t) != null) {
                int lead =
                        t.getLiteral().length() - t.getLiteral().stripLeading().length();
                if (lead > 0 && lead < t.getLiteral().length()) {
                    fix.add(Edit.on(s.getLineIndex(), first(t).getColumnIndex(), first(t).getColumnIndex() + lead, ""));
                }
            }
            if (link.getLastChild() instanceof Text t && first(t) != null) {
                int trail =
                        t.getLiteral().length() - t.getLiteral().stripTrailing().length();
                int end = first(t).getColumnIndex() + first(t).getLength();
                if (trail > 0 && trail < t.getLiteral().length()) {
                    fix.add(Edit.on(s.getLineIndex(), end - trail, end, ""));
                }
            }
            if (!fix.isEmpty()) {
                emit.add(lineNo, s.getColumnIndex() + 1, s.getLength(), "MD039", "MD039", fix.toArray(Edit[]::new));
            }
        }
        if (emit.on("MD042") && (destination.isEmpty() || destination.equals("#"))) {
            emit.add(lineNo, s.getColumnIndex() + 1, s.getLength(), "MD042", "MD042");
        }
        if (destination.length() > 1 && destination.startsWith("#") && emit.on("MD051")) {
            String fragment = decode(destination.substring(1));
            boolean ignoreCase = run.opt.boolOption("MD051.ignore_case", false);
            Set<String> anchors = anchors(run);
            boolean known = anchors.contains(fragment)
                    || (ignoreCase && anchors.contains(fragment.toLowerCase(Locale.ROOT)))
                    || fragment.equals("top")
                    || LINE_FRAGMENT.matcher(fragment).matches();
            if (!known) {
                emit.add(lineNo, s.getColumnIndex() + 1, s.getLength(), "MD051", "MD051");
            }
        }
        target(run, destination, s);
    }

    private static void image(Run run, Image image) {
        SourceSpan s = first(image);
        if (run.emit.on("MD045") && plainText(image).isBlank()) {
            run.emit.add(s.getLineIndex() + 1, s.getColumnIndex() + 1, s.getLength(), "MD045", "MD045");
        }
        target(run, image.getDestination() == null ? "" : image.getDestination(), s);
    }

    /** LINK001 / LINK002: a relative destination must name a file that is there (and a heading that is in it). */
    private static void target(Run run, String destination, SourceSpan s) {
        Emitter emit = run.emit;
        if (emit.targets() == null || !all(emit, MarkdownLint.LINK_FILE, MarkdownLint.LINK_ANCHOR)) {
            return;
        }
        if (destination.isEmpty()
                || destination.startsWith("#")
                || destination.startsWith("/")
                || destination.startsWith("\\")
                || SCHEME.matcher(destination).find()
                || destination.indexOf('{') >= 0
                || destination.indexOf('<') >= 0
                || destination.indexOf('$') >= 0) {
            return; // not a path relative to this file (or a template that is not one yet)
        }
        int hash = destination.indexOf('#');
        String fragment = hash < 0 ? "" : decode(destination.substring(hash + 1));
        String path = hash < 0 ? destination : destination.substring(0, hash);
        int query = path.indexOf('?');
        path = decode(query < 0 ? path : path.substring(0, query));
        if (path.isBlank()) {
            return;
        }
        if (!emit.targets().exists(path)) {
            emit.add(s.getLineIndex() + 1, s.getColumnIndex() + 1, s.getLength(), MarkdownLint.LINK_FILE, "LINK001");
            return;
        }
        String lower = path.toLowerCase(Locale.ROOT);
        if (!fragment.isEmpty()
                && (lower.endsWith(".md") || lower.endsWith(".markdown"))
                && !fragment.equals("top")
                && !LINE_FRAGMENT.matcher(fragment).matches()
                && emit.on(MarkdownLint.LINK_ANCHOR)) {
            Set<String> there = emit.targets().anchors(path);
            if (there != null && !there.contains(fragment)) {
                emit.add(
                        s.getLineIndex() + 1,
                        s.getColumnIndex() + 1,
                        s.getLength(),
                        MarkdownLint.LINK_ANCHOR,
                        "LINK002");
            }
        }
    }

    private static String decode(String s) {
        if (s.indexOf('%') < 0) {
            return s;
        }
        try {
            return java.net.URLDecoder.decode(s.replace("+", "%2B"), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return s;
        }
    }

    private static Set<String> anchors(Run run) {
        if (run.anchors == null) {
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < run.blocks.lineCount(); i++) {
                text.append(run.blocks.line(i)).append('\n');
            }
            run.anchors = anchorsOf(text.toString());
        }
        return run.anchors;
    }

    /**
     * Every anchor a link can name in {@code markdown}: each heading's GitHub-style slug (of the heading as
     * written and of the text a reader sees, numbered when repeated) and every HTML {@code id}/{@code name}.
     */
    static Set<String> anchorsOf(String markdown) {
        Set<String> out = new HashSet<>();
        List<MarkdownOutline.Heading> headings = MarkdownOutline.headings(markdown);
        for (boolean plain : new boolean[] {false, true}) {
            Map<String, Integer> seen = new HashMap<>();
            for (MarkdownOutline.Heading h : headings) {
                String title = plain ? MarkdownText.plain(h.title()) : h.title();
                if (!title.isBlank()) {
                    out.add(MarkdownToc.uniqueAnchor(MarkdownToc.slug(title), seen));
                }
            }
        }
        int from = 0;
        while (from < markdown.length()) {
            int lt = markdown.indexOf('<', from);
            if (lt < 0) {
                break;
            }
            int end = markdown.indexOf('\n', lt);
            end = end < 0 ? markdown.length() : end;
            Matcher m = HTML_ANCHOR.matcher(markdown).region(lt, end);
            while (m.find()) {
                out.add(m.group(1));
            }
            from = end + 1;
        }
        return out;
    }

    // --- MD038, MD033 (and <img> without alt) ---------------------------------------------------------------------

    private static void codeSpan(Run run, Node code) {
        if (!run.emit.on("MD038") || code.getSourceSpans().size() != 1) {
            return;
        }
        SourceSpan s = first(code);
        String raw = run.raw(s);
        int ticks = 0;
        while (ticks < raw.length() && raw.charAt(ticks) == '`') {
            ticks++;
        }
        if (ticks == 0 || raw.length() < 2 * ticks + 1) {
            return;
        }
        String inner = raw.substring(ticks, raw.length() - ticks);
        String trimmed = inner.strip();
        if (trimmed.isEmpty()) {
            return; // a span of spaces is deliberate
        }
        int lead = inner.length() - inner.stripLeading().length();
        int trail = inner.length() - inner.stripTrailing().length();
        boolean needsPadding = trimmed.startsWith("`") || trimmed.endsWith("`");
        // One space on each side is CommonMark's own padding (stripped when rendered). A space at the end only
        // is how one writes a literal that ends in a space (`- `, `> `), so that is left alone too — narrower
        // than upstream on purpose. What remains is a space after the opening backtick: "` code`".
        boolean fine = lead == 0 || (lead == 1 && trail == 1);
        if (fine) {
            return;
        }
        // In a paragraph with a stray backtick the spans pair up wrongly and "start" where a real span ended;
        // a span that really opens here follows a space or an opening bracket, and ends before one.
        String line = run.line(s.getLineIndex());
        int after = s.getColumnIndex() + raw.length();
        char before = s.getColumnIndex() > 0 ? line.charAt(s.getColumnIndex() - 1) : ' ';
        char next = after < line.length() ? line.charAt(after) : ' ';
        if (!(Character.isWhitespace(before) || "([{\"'".indexOf(before) >= 0)
                || !(Character.isWhitespace(next) || ".,;:!?)]}\"'".indexOf(next) >= 0)) {
            return;
        }
        String fixed = needsPadding ? " " + trimmed + " " : trimmed;
        int column = s.getColumnIndex();
        run.emit.add(
                s.getLineIndex() + 1,
                column + 1,
                s.getLength(),
                "MD038",
                "MD038",
                Edit.on(s.getLineIndex(), column + ticks, column + raw.length() - ticks, fixed));
    }

    private static void html(Run run, Node node) {
        Emitter emit = run.emit;
        if (!all(emit, "MD033", "MD045")) {
            return;
        }
        List<String> allowed = run.opt.listOption("MD033.allowed_elements", List.of());
        for (SourceSpan s : node.getSourceSpans()) {
            String raw = run.raw(s);
            if (raw.indexOf('<') < 0 || raw.stripLeading().startsWith("<!--")) {
                continue;
            }
            if (emit.on("MD033")) {
                Matcher tag = HTML_TAG.matcher(raw);
                while (tag.find()) {
                    String name = tag.group(1).toLowerCase(Locale.ROOT);
                    if (!containsIgnoreCase(allowed, name)) {
                        emit.add(
                                s.getLineIndex() + 1,
                                s.getColumnIndex() + tag.start() + 1,
                                tag.end() - tag.start(),
                                "MD033",
                                "MD033");
                    }
                }
            }
            if (emit.on("MD045") && raw.toLowerCase(Locale.ROOT).contains("<img")) {
                Matcher img = IMG_TAG.matcher(raw);
                while (img.find()) {
                    String tag = img.group();
                    if (!ALT_ATTR.matcher(tag).find() && !tag.contains("aria-hidden=\"true\"")) {
                        emit.add(
                                s.getLineIndex() + 1,
                                s.getColumnIndex() + img.start() + 1,
                                tag.length(),
                                "MD045",
                                "MD045");
                    }
                }
            }
        }
    }

    private static boolean containsIgnoreCase(List<String> list, String name) {
        for (String s : list) {
            if (s.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    // --- text the parser left as text: MD011, MD037 ------------------------------------------------------------------

    /**
     * Runs MD011 and MD037 over each stretch of adjacent text children of {@code parent} on one line. Delimiters
     * the parser consumed (real emphasis, real links) are not text any more, so they cannot be matched here.
     */
    private static void textRuns(Run run, Node parent) {
        if (!all(run.emit, "MD011", "MD037")) {
            return;
        }
        int line = -1;
        int start = 0;
        int end = 0;
        for (Node c = parent.getFirstChild(); ; c = c.getNext()) {
            SourceSpan s = c instanceof Text && c.getSourceSpans().size() == 1 ? first(c) : null;
            if (s != null && s.getLineIndex() == line && s.getColumnIndex() == end) {
                end += s.getLength();
                continue;
            }
            if (line >= 0 && end > start) {
                textRun(run, line, start, end);
            }
            if (c == null) {
                break;
            }
            line = s == null ? -1 : s.getLineIndex();
            start = s == null ? 0 : s.getColumnIndex();
            end = s == null ? 0 : start + s.getLength();
        }
    }

    private static void textRun(Run run, int lineIndex, int start, int end) {
        String line = run.line(lineIndex);
        end = Math.min(end, line.length());
        if (start >= end) {
            return;
        }
        Emitter emit = run.emit;
        if (emit.on("MD011") && run.hasReversedLinkShape(lineIndex)) {
            Matcher m = REVERSED_LINK.matcher(line).region(start, end);
            while (m.find()) {
                // "f(x)[0]" is a call and an index, "\\(" is escaped: a reversed link starts a word.
                char before = m.start() > 0 ? line.charAt(m.start() - 1) : ' ';
                boolean escaped = before == '\\' || Character.isLetterOrDigit(before) || before == ')' || before == ']';
                if (!escaped && !run.definedLabels.contains(MarkdownLint.normalizeLabel(m.group(2)))) {
                    emit.add(
                            lineIndex + 1,
                            m.start() + 1,
                            m.end() - m.start(),
                            "MD011",
                            "MD011",
                            Edit.on(lineIndex, m.start(), m.end(), "[" + m.group(1) + "](" + m.group(2) + ")"));
                }
            }
        }
        if (emit.on("MD037")) {
            spacesInEmphasis(run, line, lineIndex, start, end);
        }
    }

    /**
     * MD037 on {@code line[start, end)}: a pair of equal {@code *} / {@code _} runs with a space just inside one
     * of them ("**bold **") — which is why the parser left them as text. Deliberately narrow: {@code 2 * 3 * 4},
     * {@code *.md and *.txt} and {@code snake_case names_} are arithmetic, globs and identifiers, not emphasis.
     */
    private static void spacesInEmphasis(Run run, String line, int lineIndex, int start, int end) {
        // The delimiter runs of the segment, left to right: {offset, length}.
        List<int[]> runs = new ArrayList<>();
        for (int p = start; p < end; ) {
            char c = line.charAt(p);
            if ((c != '*' && c != '_') || (p > 0 && line.charAt(p - 1) == '\\')) {
                p++;
                continue;
            }
            int q = p;
            while (q < end && line.charAt(q) == c) {
                q++;
            }
            runs.add(new int[] {p, q - p});
            p = q;
        }
        for (int i = 0; i + 1 < runs.size(); i++) {
            int[] open = runs.get(i);
            int[] close = runs.get(i + 1);
            char c = line.charAt(open[0]);
            if (line.charAt(close[0]) != c || close[1] != open[1] || open[1] > 3) {
                continue;
            }
            int innerStart = open[0] + open[1];
            int innerEnd = close[0];
            if (innerEnd - innerStart < 2) {
                continue;
            }
            boolean spaceLeft = line.charAt(innerStart) == ' ';
            boolean spaceRight = line.charAt(innerEnd - 1) == ' ';
            if (!spaceLeft && !spaceRight) {
                continue;
            }
            String inner = line.substring(innerStart, innerEnd);
            if (inner.isBlank()) {
                continue;
            }
            char before = open[0] > 0 ? line.charAt(open[0] - 1) : ' ';
            int afterAt = close[0] + close[1];
            char after = afterAt < line.length() ? line.charAt(afterAt) : ' ';
            char afterNext = afterAt + 1 < line.length() ? line.charAt(afterAt + 1) : ' ';
            boolean outsideLeft = Character.isWhitespace(before) || "([{\"'>".indexOf(before) >= 0;
            boolean outsideRight = Character.isWhitespace(after)
                    || (".,;:!?)]}\"'".indexOf(after) >= 0
                            && (Character.isWhitespace(afterNext) || afterAt + 1 >= line.length()));
            if (!outsideLeft || !outsideRight) {
                continue;
            }
            boolean flagged;
            if (spaceLeft && spaceRight) {
                flagged = open[1] >= 2; // "** bold **"; a lone "* x *" is far more often arithmetic
            } else {
                char attached = spaceLeft ? line.charAt(innerEnd - 1) : line.charAt(innerStart);
                flagged = Character.isLetterOrDigit(attached); // "*.md and *" is two globs
            }
            if (!flagged) {
                continue;
            }
            int lead = inner.length() - inner.stripLeading().length();
            int trail = inner.length() - inner.stripTrailing().length();
            List<Edit> fix = new ArrayList<>();
            if (lead > 0) {
                fix.add(Edit.on(lineIndex, innerStart, innerStart + lead, ""));
            }
            if (trail > 0) {
                fix.add(Edit.on(lineIndex, innerEnd - trail, innerEnd, ""));
            }
            run.emit.add(lineIndex + 1, open[0] + 1, afterAt - open[0], "MD037", "MD037", fix.toArray(Edit[]::new));
            i++; // the closing run is spent
        }
    }

    // --- MD053 ------------------------------------------------------------------------------------------------

    private static void collectDefinitions(Run run, Node document) {
        Node node = document;
        while (node != null) {
            if (node instanceof LinkReferenceDefinition def && first(def) != null) {
                run.definitions.add(def);
                run.definedLabels.add(MarkdownLint.normalizeLabel(def.getLabel()));
            }
            // Definitions live at block level: no need to descend into paragraphs.
            if (node.getFirstChild() != null && !(node instanceof Paragraph) && !(node instanceof Heading)) {
                node = node.getFirstChild();
                continue;
            }
            while (node != null && node != document && node.getNext() == null) {
                node = node.getParent();
            }
            node = node == null || node == document ? null : node.getNext();
        }
    }

    private static void unusedDefinitions(Run run) {
        if (!run.emit.on("MD053") || run.definitions.isEmpty()) {
            return;
        }
        // Every bracketed text of the prose is a possible use: over-counting uses is the safe side here.
        Set<String> used = new HashSet<>();
        MarkdownBlocks blocks = run.blocks;
        for (int i = 0; i < blocks.lineCount(); i++) {
            if (!blocks.isText(i) && blocks.kind(i) != MarkdownBlocks.Kind.HTML_BLOCK) {
                continue;
            }
            String line = blocks.line(i);
            int open = -1;
            for (int p = line.indexOf('['); p >= 0 && p < line.length(); p++) {
                char c = line.charAt(p);
                if (c == '[') {
                    open = p;
                } else if (c == ']' && open >= 0) {
                    boolean definition = p + 1 < line.length()
                            && line.charAt(p + 1) == ':'
                            && line.substring(0, open).isBlank();
                    if (!definition) {
                        used.add(MarkdownLint.normalizeLabel(line.substring(open + 1, p)));
                    }
                    open = -1;
                }
            }
        }
        List<String> ignored = run.opt.listOption("MD053.ignored_definitions", List.of("//"));
        Set<String> seen = new HashSet<>();
        for (LinkReferenceDefinition def : run.definitions) {
            String label = MarkdownLint.normalizeLabel(def.getLabel());
            SourceSpan s = first(def);
            if (containsIgnoreCase(ignored, label)) {
                continue;
            }
            boolean duplicate = !seen.add(label);
            if (!duplicate && used.contains(label)) {
                continue;
            }
            Edit[] fix = def.getSourceSpans().size() == 1 && def.getParent() instanceof Document
                    ? new Edit[] {new Edit(s.getLineIndex(), 0, s.getLineIndex() + 1, 0, "")}
                    : new Edit[0];
            run.emit.add(
                    s.getLineIndex() + 1,
                    s.getColumnIndex() + 1,
                    s.getLength(),
                    "MD053",
                    duplicate ? "MD053.duplicate" : "MD053.unused",
                    fix);
        }
    }
}
