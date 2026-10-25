package com.editora.markdown;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.commonmark.Extension;
import org.commonmark.node.CustomBlock;
import org.commonmark.node.CustomNode;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.parser.SourceLine;
import org.commonmark.parser.beta.InlineContentParser;
import org.commonmark.parser.beta.InlineContentParserFactory;
import org.commonmark.parser.beta.InlineParserState;
import org.commonmark.parser.beta.ParsedInline;
import org.commonmark.parser.beta.Position;
import org.commonmark.parser.beta.Scanner;
import org.commonmark.parser.block.AbstractBlockParser;
import org.commonmark.parser.block.BlockContinue;
import org.commonmark.parser.block.BlockParserFactory;
import org.commonmark.parser.block.BlockStart;
import org.commonmark.parser.block.MatchedBlockParser;
import org.commonmark.parser.block.ParserState;
import org.commonmark.renderer.NodeRenderer;
import org.commonmark.renderer.text.TextContentRenderer;
import org.jspecify.annotations.Nullable;

/**
 * LaTeX math as part of the Markdown <em>parse</em>: {@code $…$} and {@code $$…$$} become {@link Inline} and
 * {@link Block} nodes that carry the LaTeX exactly as written.
 *
 * <p>Math used to be looked for afterwards, in the text nodes CommonMark had already produced. By then the
 * source was gone: backslash escapes were consumed ({@code \,} was a comma, {@code \\} a single backslash,
 * {@code \{} a bare brace), {@code *} and {@code _} had become emphasis, and an escaped {@code \$} was
 * indistinguishable from a delimiter. Here the delimiters are recognised while the inline parser still has the
 * raw line, so nothing between them is interpreted as Markdown.
 *
 * <p>Rules, chosen so prose keeps its dollar signs:
 *
 * <ul>
 *   <li>Inline {@code $…$}: the opening {@code $} is followed by a non-space, the closing one preceded by a
 *       non-space and <b>not followed by a letter or a digit</b> ({@code $5 and $10}, {@code $HOME/bin:$PATH}
 *       stay text), on one line, with no backtick inside. {@code \$} is never a delimiter.
 *   <li>Display {@code $$…$$} inside a paragraph may span its lines.
 *   <li>A display <em>block</em> opens on a line that starts with {@code $$} and is either closed on that same
 *       line, has nothing after the {@code $$}, or continues with a LaTeX command ({@code $$\begin{aligned}}).
 *       It runs to the first line ending in {@code $$}, like a code fence — so a line inside it that starts
 *       with {@code +} or {@code -} is math, not a list.
 *   <li>Code spans and code blocks are never scanned: CommonMark claims them first.
 * </ul>
 *
 * <p>Pure (no toolkit). Add {@link #extension()} to a {@link Parser.Builder}; a renderer that does not know the
 * two node types can fall back to {@link #source(Node)}, the text as it was written.
 */
public final class MarkdownMath {

    private MarkdownMath() {}

    /** {@code $…$}, or {@code $$…$$} written inside a paragraph ({@link #isDisplay()}). */
    public static final class Inline extends CustomNode {
        private final String latex;
        private final boolean display;

        public Inline(String latex, boolean display) {
            this.latex = latex;
            this.display = display;
        }

        /** The LaTeX between the delimiters, verbatim (surrounding whitespace stripped). */
        public String getLatex() {
            return latex;
        }

        public boolean isDisplay() {
            return display;
        }
    }

    /** A {@code $$} block of its own. {@link #isClosed()} is false when the document ended before a closing
     *  {@code $$}; such a block is shown as the text it is, not as a formula. */
    public static final class Block extends CustomBlock {
        private String latex = "";
        private boolean closed;

        Block() {}

        /** A complete block holding {@code latex} (a {@code ```math} fence, or a renderer's own). */
        public Block(String latex) {
            this.latex = latex == null ? "" : latex;
            this.closed = true;
        }

        public String getLatex() {
            return latex;
        }

        public boolean isClosed() {
            return closed;
        }
    }

    /** The parser extension, also teaching {@code TextContentRenderer} to print math as its source. */
    public static Extension extension() {
        return new MathExtension();
    }

    /** The Markdown source of a math node ({@code $x$}, {@code $$x$$}), or null for any other node. */
    public static @Nullable String source(Node node) {
        if (node instanceof Inline in) {
            String d = in.isDisplay() ? "$$" : "$";
            return d + in.getLatex() + d;
        }
        if (node instanceof Block b) {
            return b.isClosed() ? "$$" + b.getLatex() + "$$" : "$$" + b.getLatex();
        }
        return null;
    }

    private static final class MathExtension
            implements Parser.ParserExtension, TextContentRenderer.TextContentRendererExtension {
        @Override
        public void extend(Parser.Builder builder) {
            builder.customInlineContentParserFactory(new InlineFactory());
            builder.customInlineContentParserFactory(new BackslashFactory());
            builder.customBlockParserFactory(new BlockFactory());
            builder.postProcessor(MarkdownMath::mathFences);
        }

        @Override
        public void extend(TextContentRenderer.Builder builder) {
            builder.nodeRendererFactory(context -> new NodeRenderer() {
                @Override
                public Set<Class<? extends Node>> getNodeTypes() {
                    return Set.of(Inline.class, Block.class);
                }

                @Override
                public void render(Node node) {
                    context.getWriter().write(source(node));
                    if (node instanceof Block) {
                        context.getWriter().block();
                    }
                }
            });
        }
    }

    // --- inline --------------------------------------------------------------------------------

    private static final class InlineFactory implements InlineContentParserFactory {
        @Override
        public Set<Character> getTriggerCharacters() {
            return Set.of('$');
        }

        @Override
        public InlineContentParser create() {
            return MarkdownMath::tryParseInline;
        }
    }

    /** {@code \(…\)} and {@code \[…\]}: tried before CommonMark's own backslash escape. */
    private static final class BackslashFactory implements InlineContentParserFactory {
        @Override
        public Set<Character> getTriggerCharacters() {
            return Set.of('\\');
        }

        @Override
        public InlineContentParser create() {
            return MarkdownMath::tryParseBackslash;
        }
    }

    /**
     * LaTeX's own delimiters: {@code \(x^2\)} inline (one line) and {@code \[ … \]} display (may span the
     * paragraph's lines). In Markdown {@code \(} and {@code \[} are also how a literal bracket is written
     * ({@code \[not a link\]}, {@code [f\(x\)](url)}), so the pair is math only when what it holds looks
     * like math ({@link #looksLikeMath}); anything else stays the escaped brackets it was.
     */
    private static ParsedInline tryParseBackslash(InlineParserState state) {
        Scanner s = state.scanner();
        s.next(); // the backslash
        char open = s.peek();
        if (open != '(' && open != '[') {
            return ParsedInline.none();
        }
        char close = open == '(' ? ')' : ']';
        boolean display = open == '[';
        s.next();
        Position start = s.position();
        while (s.hasNext()) {
            char c = s.peek();
            if (c == '`' || (c == '\n' && !display)) {
                return ParsedInline.none();
            }
            if (c == '\\') {
                Position end = s.position();
                s.next();
                if (s.peek() == close) {
                    String latex = s.getSource(start, end).getContent().strip();
                    if (!looksLikeMath(latex)) {
                        return ParsedInline.none();
                    }
                    s.next();
                    return ParsedInline.of(new Inline(latex, display), s.position());
                }
                if (s.hasNext()) {
                    s.next(); // an escaped character or a command's first letter
                }
                continue;
            }
            s.next();
        }
        return ParsedInline.none();
    }

    /**
     * Whether the text between {@code \(…\)} or {@code \[…\]} is a formula rather than prose in escaped
     * brackets: it uses something only math does — a command, a sub- or superscript, a brace, an equals,
     * plus or comparison sign. A single variable ({@code \(x\)}) is not told apart from a word in brackets:
     * write that one as {@code $x$}. Pure.
     */
    static boolean looksLikeMath(String content) {
        if (content == null || content.isBlank()) {
            return false;
        }
        for (int i = 0; i < content.length(); i++) {
            if ("\\^_={}+<>".indexOf(content.charAt(i)) >= 0) {
                return true;
            }
        }
        return false;
    }

    /** Replaces every {@code ```math} fence under {@code document} by a {@link Block} (GitHub's spelling of
     *  a display formula: nothing inside a fence is ever Markdown, so nothing needs escaping). */
    static Node mathFences(Node document) {
        java.util.List<org.commonmark.node.FencedCodeBlock> fences = new ArrayList<>();
        document.accept(new org.commonmark.node.AbstractVisitor() {
            @Override
            public void visit(org.commonmark.node.FencedCodeBlock fence) {
                String info = fence.getInfo() == null ? "" : fence.getInfo().strip();
                if (info.split("\\s+", 2)[0].equalsIgnoreCase("math")) {
                    fences.add(fence);
                }
            }
        });
        for (org.commonmark.node.FencedCodeBlock fence : fences) {
            Block block = new Block(
                    fence.getLiteral() == null ? "" : fence.getLiteral().strip());
            block.setSourceSpans(fence.getSourceSpans());
            fence.insertAfter(block);
            fence.unlink();
        }
        return document;
    }

    private static ParsedInline tryParseInline(InlineParserState state) {
        Scanner s = state.scanner();
        int dollars = s.matchMultiple('$');
        if (dollars == 1) {
            return inline(s);
        }
        return dollars == 2 ? display(s) : ParsedInline.none();
    }

    /** After the opening {@code $}: scans to a closing one on the same line that satisfies the rules. */
    private static ParsedInline inline(Scanner s) {
        char first = s.peek();
        if (first == Scanner.END || Character.isWhitespace(first) || first == '$') {
            return ParsedInline.none();
        }
        Position start = s.position();
        while (s.hasNext()) {
            char c = s.peek();
            if (c == '\n' || c == '`') {
                return ParsedInline.none();
            }
            if (c == '\\') {
                s.next();
                if (s.hasNext() && s.peek() != '\n') {
                    s.next();
                }
                continue;
            }
            if (c == '$') {
                if (Character.isWhitespace(s.peekPreviousCodePoint())) {
                    return ParsedInline.none(); // "$5 and $10": the second dollar opens nothing either
                }
                Position end = s.position();
                s.next();
                if (Character.isLetterOrDigit(s.peekCodePoint())) {
                    return ParsedInline.none(); // "$HOME/bin:$PATH", "$5-$10"
                }
                String latex = s.getSource(start, end).getContent().strip();
                return latex.isEmpty() ? ParsedInline.none() : ParsedInline.of(new Inline(latex, false), s.position());
            }
            s.next();
        }
        return ParsedInline.none();
    }

    /** After an opening {@code $$}: scans (across the paragraph's lines) to the next unescaped {@code $$}. */
    private static ParsedInline display(Scanner s) {
        Position start = s.position();
        while (s.hasNext()) {
            char c = s.peek();
            if (c == '`') {
                return ParsedInline.none();
            }
            if (c == '\\') {
                s.next();
                if (s.hasNext()) {
                    s.next();
                }
                continue;
            }
            if (c == '$') {
                Position end = s.position();
                if (s.matchMultiple('$') >= 2) {
                    String latex = s.getSource(start, end).getContent().strip();
                    if (latex.isEmpty()) {
                        return ParsedInline.none();
                    }
                    s.setPosition(end);
                    s.next();
                    s.next();
                    return ParsedInline.of(new Inline(latex, true), s.position());
                }
                continue;
            }
            s.next();
        }
        return ParsedInline.none();
    }

    // --- block ---------------------------------------------------------------------------------

    /** Index of the first unescaped {@code $$} in {@code text} at or after {@code from}, or -1. Pure. */
    static int indexOfDelimiter(CharSequence text, int from) {
        for (int i = from; i + 1 < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == '$' && text.charAt(i + 1) == '$') {
                return i;
            }
        }
        return -1;
    }

    /** Where a closing {@code $$} ends {@code line} (trailing whitespace allowed after it), or -1. Pure. */
    static int closingDelimiter(CharSequence line) {
        int end = line.length();
        while (end > 0 && Character.isWhitespace(line.charAt(end - 1))) {
            end--;
        }
        if (end < 2 || line.charAt(end - 1) != '$' || line.charAt(end - 2) != '$') {
            return -1;
        }
        int at = indexOfDelimiter(line, 0);
        return at == end - 2 ? at : -1; // an earlier "$$" on the line means this is not a plain closing line
    }

    private static final class BlockFactory implements BlockParserFactory {
        @Override
        public BlockStart tryStart(ParserState state, MatchedBlockParser matched) {
            if (state.getIndent() >= 4) {
                return BlockStart.none();
            }
            CharSequence line = state.getLine().getContent();
            int at = state.getNextNonSpaceIndex();
            if (at + 1 >= line.length() || line.charAt(at) != '$' || line.charAt(at + 1) != '$') {
                return BlockStart.none();
            }
            CharSequence rest = line.subSequence(at + 2, line.length());
            if (rest.length() > 0 && rest.charAt(0) == '$') {
                return BlockStart.none();
            }
            String body = rest.toString().strip();
            int close = closingDelimiter(rest);
            if (close >= 0) {
                String latex = rest.subSequence(0, close).toString().strip();
                if (latex.isEmpty()) {
                    return BlockStart.none();
                }
                return BlockStart.of(new BlockParser(latex, true)).atIndex(line.length());
            }
            boolean opens = body.isEmpty() || (body.charAt(0) == '\\' && indexOfDelimiter(rest, 0) < 0);
            if (!opens) {
                return BlockStart.none(); // "$$ is the shell's PID": prose, left to the inline parser
            }
            return BlockStart.of(new BlockParser(body, false)).atIndex(line.length());
        }
    }

    private static final class BlockParser extends AbstractBlockParser {
        private final Block block = new Block();
        private final List<String> lines = new ArrayList<>();
        /** The parser hands the (empty) remainder of the opening line to {@link #addLine} first. */
        private boolean openingLineSeen;

        BlockParser(String firstLine, boolean closed) {
            if (!firstLine.isEmpty()) {
                lines.add(firstLine);
            }
            block.closed = closed;
        }

        @Override
        public org.commonmark.node.Block getBlock() {
            return block;
        }

        @Override
        public BlockContinue tryContinue(ParserState state) {
            if (block.closed) {
                return BlockContinue.none();
            }
            CharSequence line = state.getLine().getContent();
            CharSequence rest = line.subSequence(Math.min(state.getIndex(), line.length()), line.length());
            int close = closingDelimiter(rest);
            if (close >= 0) {
                String last = rest.subSequence(0, close).toString();
                if (!last.isBlank()) {
                    lines.add(last.stripTrailing());
                }
                block.closed = true;
                return BlockContinue.finished();
            }
            return BlockContinue.atIndex(state.getIndex());
        }

        @Override
        public void addLine(SourceLine line) {
            String content = line.getContent().toString();
            if (!openingLineSeen) {
                openingLineSeen = true;
                if (content.isBlank()) {
                    return;
                }
            }
            if (!block.closed) {
                lines.add(content);
            }
        }

        @Override
        public void closeBlock() {
            block.latex = String.join("\n", lines).strip();
        }
    }
}
