package com.editora.markdown;

import java.util.List;
import java.util.Set;

import org.commonmark.Extension;
import org.commonmark.ext.gfm.strikethrough.Strikethrough;
import org.commonmark.node.CustomNode;
import org.commonmark.node.Delimited;
import org.commonmark.node.Node;
import org.commonmark.node.Nodes;
import org.commonmark.node.SourceSpans;
import org.commonmark.node.Text;
import org.commonmark.parser.Parser;
import org.commonmark.parser.delimiter.DelimiterProcessor;
import org.commonmark.parser.delimiter.DelimiterRun;
import org.commonmark.renderer.NodeRenderer;
import org.commonmark.renderer.html.HtmlRenderer;
import org.commonmark.renderer.text.TextContentRenderer;

/**
 * Three inline marks of the wider Markdown family: {@code ==highlight==} ({@link Highlight}),
 * {@code ^superscript^} ({@link Superscript}) and {@code ~subscript~} ({@link Subscript}).
 *
 * <p>Each is chosen so that text which is not meant as markup stays as it is:
 *
 * <ul>
 *   <li><b>Highlight</b> needs two equals signs on each side, hugging the text ({@code == a ==} is not one),
 *       and is not recognised inside a word: {@code a==b and c==d} is a comparison.
 *   <li><b>Superscript</b> and <b>subscript</b> hold a single run of text <em>without whitespace</em>
 *       (Pandoc's rule): {@code x^2^}, {@code H~2~O}. {@code 2^10 and 3^5} is not superscript. The run
 *       cannot start with punctuation after a word ({@code e^-1^}): CommonMark does not open a delimiter
 *       there — write that one as math or {@code <sup>}.
 *   <li><b>Subscript shares the tilde with GFM strikethrough</b>, and GitHub reads one tilde as
 *       strikethrough too ({@code ~gone~}). So a single-tilde pair is subscript only when it is attached to
 *       the word before it ({@code H~2~O}, {@code x~i~}) — which as strikethrough was never what anyone meant;
 *       a pair that stands alone ({@code ~gone~}, {@code ~two words~}) and every {@code ~~pair~~} is still
 *       strikethrough, exactly as before.
 * </ul>
 *
 * <p>To get that, {@link #extension()} takes over the <em>single</em> tilde: build the parser with
 * {@code StrikethroughExtension.builder().requireTwoTildes(true)} beside it (a second processor for the same
 * character is only accepted for a different run length). Pure.
 *
 * <p>The extension renders the nodes for {@code HtmlRenderer} ({@code <mark>}, {@code <sup>}, {@code <sub>})
 * and {@code TextContentRenderer} (their text).
 */
public final class MarkdownMarks {

    private MarkdownMarks() {}

    /** {@code ==text==}. */
    public static final class Highlight extends CustomNode implements Delimited {
        @Override
        public String getOpeningDelimiter() {
            return "==";
        }

        @Override
        public String getClosingDelimiter() {
            return "==";
        }
    }

    /** {@code ^text^}. */
    public static final class Superscript extends CustomNode implements Delimited {
        @Override
        public String getOpeningDelimiter() {
            return "^";
        }

        @Override
        public String getClosingDelimiter() {
            return "^";
        }
    }

    /** {@code ~text~} attached to the word before it. */
    public static final class Subscript extends CustomNode implements Delimited {
        @Override
        public String getOpeningDelimiter() {
            return "~";
        }

        @Override
        public String getClosingDelimiter() {
            return "~";
        }
    }

    public static Extension extension() {
        return new MarksExtension();
    }

    /** The character right before an opening run, or 0 at the start of a line or after anything that is not
     *  text (a link, code, a line break). A run is one text node per delimiter character. */
    private static char before(DelimiterRun openingRun) {
        Node outermost = openingRun.getOpener();
        for (Text t : openingRun.getOpeners(openingRun.length())) {
            outermost = t;
            break;
        }
        if (outermost.getPrevious() instanceof Text t && !t.getLiteral().isEmpty()) {
            return t.getLiteral().charAt(t.getLiteral().length() - 1);
        }
        return 0;
    }

    /** The character right after a closing run, or 0. */
    private static char after(DelimiterRun closingRun) {
        Node outermost = closingRun.getCloser();
        for (Text t : closingRun.getClosers(closingRun.length())) {
            outermost = t;
        }
        if (outermost.getNext() instanceof Text t && !t.getLiteral().isEmpty()) {
            return t.getLiteral().charAt(0);
        }
        return 0;
    }

    /** The text between a delimiter pair when it is one plain run without whitespace; else null. */
    private static String solidRun(Text opener, Text closer) {
        Node first = opener.getNext();
        if (!(first instanceof Text t) || first.getNext() != closer) {
            return null;
        }
        String s = t.getLiteral();
        if (s.isEmpty()) {
            return null;
        }
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) {
                return null;
            }
        }
        return s;
    }

    /** Moves what lies between the delimiters into {@code mark}, and puts {@code mark} in their place. */
    private static void wrap(Node mark, DelimiterRun openingRun, DelimiterRun closingRun, int length) {
        Text opener = openingRun.getOpener();
        Text closer = closingRun.getCloser();
        SourceSpans spans = new SourceSpans();
        spans.addAllFrom(openingRun.getOpeners(length));
        for (Node node : Nodes.between(opener, closer)) {
            mark.appendChild(node);
            spans.addAll(node.getSourceSpans());
        }
        spans.addAllFrom(closingRun.getClosers(length));
        mark.setSourceSpans(spans.getSourceSpans());
        opener.insertAfter(mark);
    }

    private static final class HighlightProcessor implements DelimiterProcessor {
        @Override
        public char getOpeningCharacter() {
            return '=';
        }

        @Override
        public char getClosingCharacter() {
            return '=';
        }

        @Override
        public int getMinLength() {
            return 2;
        }

        @Override
        public int process(DelimiterRun openingRun, DelimiterRun closingRun) {
            if (openingRun.length() != 2 || closingRun.length() != 2) {
                return 0; // "===" is a setext underline or an operator, not a mark
            }
            if (Character.isLetterOrDigit(before(openingRun)) || Character.isLetterOrDigit(after(closingRun))) {
                return 0; // a==b and c==d
            }
            wrap(new Highlight(), openingRun, closingRun, 2);
            return 2;
        }
    }

    private static final class SuperscriptProcessor implements DelimiterProcessor {
        @Override
        public char getOpeningCharacter() {
            return '^';
        }

        @Override
        public char getClosingCharacter() {
            return '^';
        }

        @Override
        public int getMinLength() {
            return 1;
        }

        @Override
        public int process(DelimiterRun openingRun, DelimiterRun closingRun) {
            if (openingRun.length() != 1
                    || closingRun.length() != 1
                    || solidRun(openingRun.getOpener(), closingRun.getCloser()) == null) {
                return 0;
            }
            wrap(new Superscript(), openingRun, closingRun, 1);
            return 1;
        }
    }

    /** One tilde on each side: subscript when attached to a word, else GFM's single-tilde strikethrough. */
    private static final class SingleTildeProcessor implements DelimiterProcessor {
        @Override
        public char getOpeningCharacter() {
            return '~';
        }

        @Override
        public char getClosingCharacter() {
            return '~';
        }

        @Override
        public int getMinLength() {
            return 1;
        }

        @Override
        public int process(DelimiterRun openingRun, DelimiterRun closingRun) {
            if (openingRun.length() != 1 || closingRun.length() != 1) {
                return 0; // as the strikethrough extension: runs must match
            }
            char before = before(openingRun);
            boolean attached = Character.isLetterOrDigit(before) || before == ')' || before == ']';
            boolean subscript = attached && solidRun(openingRun.getOpener(), closingRun.getCloser()) != null;
            wrap(subscript ? new Subscript() : new Strikethrough("~"), openingRun, closingRun, 1);
            return 1;
        }
    }

    private static final class MarksExtension
            implements Parser.ParserExtension,
                    HtmlRenderer.HtmlRendererExtension,
                    TextContentRenderer.TextContentRendererExtension {
        @Override
        public void extend(Parser.Builder builder) {
            builder.customDelimiterProcessor(new HighlightProcessor());
            builder.customDelimiterProcessor(new SuperscriptProcessor());
            builder.customDelimiterProcessor(new SingleTildeProcessor());
        }

        @Override
        public void extend(HtmlRenderer.Builder builder) {
            builder.nodeRendererFactory(context -> new NodeRenderer() {
                @Override
                public Set<Class<? extends Node>> getNodeTypes() {
                    return Set.of(Highlight.class, Superscript.class, Subscript.class);
                }

                @Override
                public void render(Node node) {
                    String tag = node instanceof Highlight ? "mark" : node instanceof Superscript ? "sup" : "sub";
                    context.getWriter().tag(tag, context.extendAttributes(node, tag, java.util.Map.of()));
                    for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
                        context.render(child);
                    }
                    context.getWriter().tag("/" + tag);
                }
            });
        }

        @Override
        public void extend(TextContentRenderer.Builder builder) {
            builder.nodeRendererFactory(context -> new NodeRenderer() {
                @Override
                public Set<Class<? extends Node>> getNodeTypes() {
                    return Set.of(Highlight.class, Superscript.class, Subscript.class);
                }

                @Override
                public void render(Node node) {
                    for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
                        context.render(child);
                    }
                }
            });
        }
    }

    /** The node types this extension adds, for a renderer that lists what it knows. */
    public static List<Class<? extends Node>> nodeTypes() {
        return List.of(Highlight.class, Superscript.class, Subscript.class);
    }
}
