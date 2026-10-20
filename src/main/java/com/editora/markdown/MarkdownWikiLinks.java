package com.editora.markdown;

import java.util.Map;
import java.util.Set;

import org.commonmark.Extension;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.CustomNode;
import org.commonmark.node.LinkReferenceDefinition;
import org.commonmark.node.Node;
import org.commonmark.node.Text;
import org.commonmark.parser.InlineParserContext;
import org.commonmark.parser.Parser;
import org.commonmark.parser.beta.InlineContentParser;
import org.commonmark.parser.beta.InlineContentParserFactory;
import org.commonmark.parser.beta.InlineParserState;
import org.commonmark.parser.beta.LinkInfo;
import org.commonmark.parser.beta.LinkProcessor;
import org.commonmark.parser.beta.LinkResult;
import org.commonmark.parser.beta.ParsedInline;
import org.commonmark.parser.beta.Position;
import org.commonmark.parser.beta.Scanner;
import org.commonmark.renderer.NodeRenderer;
import org.commonmark.renderer.html.HtmlRenderer;
import org.commonmark.renderer.text.TextContentRenderer;
import org.jspecify.annotations.Nullable;

/**
 * Wiki links: {@code [[note]]}, {@code [[note|label]]}, {@code [[note#heading]]},
 * {@code [[note#heading|label]]}, {@code [[#heading]]} (this document), and {@code ![[image.png]]} — an embed —
 * each become one {@link WikiLink} node.
 *
 * <p>What a document rendered as before is left alone wherever it already meant something: inside code nothing
 * is parsed; an escaped bracket ({@code \[[note]]}) is text; and where {@code [note]} is a link reference the
 * document defines, {@code [[note]]} stays that link in brackets. Directly after a letter or a digit
 * ({@code x[[1]]}, indexing in R or Lua) the brackets are text too. Brackets directly followed by a destination
 * or a label ({@code [[note]](url)}, {@code [[note]][ref]}) are the ordinary link they always were. The inside
 * is one line of plain text without brackets; anything else (a code span, a line break) is not a wiki link.
 *
 * <p>In a table cell the pipe has to be written {@code \|} ({@code [[note\|label]]}), as everywhere in a GFM
 * table: the row is cut into cells before its text is read.
 *
 * <p>The node says what was written, not where it leads: resolving {@code target} to a file is the caller's
 * (the project's link index). The extension renders it for {@code HtmlRenderer} as
 * {@code <a class="wikilink" href="target#heading">} (an embed: {@code <img class="wikilink">}) and for
 * {@code TextContentRenderer} as its {@link WikiLink#getText text}. Pure.
 */
public final class MarkdownWikiLinks {

    private MarkdownWikiLinks() {}

    /** One wiki link, as written. */
    public static final class WikiLink extends CustomNode {
        private final String target;
        private final @Nullable String heading;
        private final @Nullable String label;
        private final boolean embed;

        public WikiLink(String target, @Nullable String heading, @Nullable String label, boolean embed) {
            this.target = target == null ? "" : target;
            this.heading = heading;
            this.label = label;
            this.embed = embed;
        }

        /** The note (or file) named, without heading or label; empty for {@code [[#heading]]}. */
        public String getTarget() {
            return target;
        }

        /** The heading after {@code #}, or null. */
        public @Nullable String getHeading() {
            return heading;
        }

        /** The text after {@code |}, or null. */
        public @Nullable String getLabel() {
            return label;
        }

        /** Whether it was written {@code ![[…]]}: the target is shown in place (an image), not linked to. */
        public boolean isEmbed() {
            return embed;
        }

        /** What a reader sees: the label, else the target (with {@code #heading}), else the heading. */
        public String getText() {
            if (label != null) {
                return label;
            }
            if (heading == null) {
                return target;
            }
            return target.isEmpty() ? heading : target + "#" + heading;
        }

        /** The link as a relative reference: {@code target#heading} (either part may be missing). */
        public String getReference() {
            return heading == null ? target : target + "#" + heading;
        }
    }

    /** Longest inside ({@code note#heading|label}) taken for a wiki link. */
    static final int MAX_LENGTH = 1000;

    /**
     * Reads the inside of {@code [[…]]} — {@code note}, {@code note|label}, {@code note#heading},
     * {@code note#heading|label}, {@code #heading} — or returns null when it is not one (empty, a bracket or a
     * line break inside, neither a target nor a heading). Parts are trimmed; an empty label or heading is as if
     * it were not written. Pure.
     */
    public static @Nullable WikiLink parse(String inside, boolean embed) {
        if (inside == null || inside.length() > MAX_LENGTH) {
            return null;
        }
        for (int i = 0; i < inside.length(); i++) {
            char c = inside.charAt(i);
            if (c == '[' || c == ']' || c == '\n' || c == '\r') {
                return null;
            }
        }
        int bar = inside.indexOf('|');
        String link = (bar < 0 ? inside : inside.substring(0, bar)).strip();
        String label = bar < 0 ? null : inside.substring(bar + 1).strip();
        int hash = link.indexOf('#');
        String target = (hash < 0 ? link : link.substring(0, hash)).strip();
        String heading = hash < 0 ? null : link.substring(hash + 1).strip();
        if (label != null && label.isEmpty()) {
            label = null;
        }
        if (heading != null && heading.isEmpty()) {
            heading = null;
        }
        if (target.isEmpty() && heading == null) {
            return null;
        }
        return new WikiLink(target, heading, label, embed);
    }

    /** {@code reference} with its spaces percent-encoded, so that it is one URL in an attribute. */
    public static String encode(String reference) {
        return reference.replace(" ", "%20");
    }

    public static Extension extension() {
        return new WikiLinkExtension();
    }

    /** A bracket or bang the author escaped ({@code \[}, {@code \!}): text like any other, but never the
     *  start of a wiki link. */
    private static final class EscapedText extends Text {
        EscapedText(String literal) {
            super(literal);
        }
    }

    /** Reads {@code \[} and {@code \!} before the parser's own escape handling does, to mark them. */
    private static ParsedInline escaped(InlineParserState state) {
        Scanner s = state.scanner();
        s.next(); // the backslash
        char c = s.peek();
        if (c != '[' && c != '!') {
            return ParsedInline.none();
        }
        s.next();
        return ParsedInline.of(new EscapedText(String.valueOf(c)), s.position());
    }

    /**
     * Called by the parser at a closing bracket that has an opening one. A wiki link is taken at its
     * <em>inner</em> pair (the parser never asks about an outer pair that holds brackets): the opening bracket
     * must directly follow another real one, the closing one be directly followed by another, and the text
     * between be plain. The outer opening bracket (and an embed's bang) is still text before the node at that
     * point; {@link #dropOuterBrackets} removes it once the paragraph is read.
     */
    private static final class Processor implements LinkProcessor {
        @Override
        public LinkResult process(LinkInfo info, Scanner scanner, InlineParserContext context) {
            if (info.marker() != null || info.destination() != null || info.label() != null) {
                return LinkResult.none();
            }
            Node outer = info.openingBracket().getPrevious();
            if (!isPlain(outer, "[")) {
                return LinkResult.none(); // one pair of brackets, or \[[note]]
            }
            StringBuilder inside = new StringBuilder();
            for (Node n = info.openingBracket().getNext(); n != null; n = n.getNext()) {
                if (!(n instanceof Text text)) {
                    return LinkResult.none(); // a code span, a line break, an emoji: not plain text
                }
                inside.append(text.getLiteral());
                if (inside.length() > MAX_LENGTH) {
                    return LinkResult.none();
                }
            }
            scanner.setPosition(info.afterTextBracket());
            if (!scanner.next(']')) {
                return LinkResult.none();
            }
            Position end = scanner.position();
            if (scanner.peek() == '(') {
                return LinkResult.none(); // [[note]](url): the link it always was, its text in brackets
            }
            if (scanner.next('[') && scanner.peek() != '[') {
                return LinkResult.none(); // [[note]][ref]: likewise
            }
            String content = inside.toString();
            if (context.getDefinition(LinkReferenceDefinition.class, content) != null) {
                return LinkResult.none(); // [note] is a reference the document defines
            }
            boolean embed = isPlain(outer.getPrevious(), "!");
            if (!embed && outer.getPrevious() instanceof Text before && endsInWord(before.getLiteral())) {
                return LinkResult.none(); // x[[1]]: indexing in R or Lua, not a link
            }
            WikiLink link = parse(content, embed);
            return link == null ? LinkResult.none() : LinkResult.replaceWith(link, end);
        }

        private static boolean endsInWord(String text) {
            return !text.isEmpty() && Character.isLetterOrDigit(text.codePointBefore(text.length()));
        }

        private static boolean isPlain(Node node, String literal) {
            return node != null && node.getClass() == Text.class && literal.equals(((Text) node).getLiteral());
        }
    }

    /**
     * Takes the outer {@code [} (an embed's {@code ![}) off the text before each wiki link. By now adjacent
     * texts are merged, so it is the end of the preceding text; where the parser used that bracket for a link
     * around the wiki link, there is no such text and nothing is removed. Escaped brackets lose their mark here.
     */
    private static Node dropOuterBrackets(Node document) {
        document.accept(new AbstractVisitor() {
            @Override
            public void visit(CustomNode node) {
                if (node instanceof WikiLink link && node.getPrevious() instanceof Text before) {
                    String marker = link.isEmbed() ? "![" : "[";
                    String literal = before.getLiteral();
                    if (literal.endsWith(marker)) {
                        if (literal.length() == marker.length()) {
                            before.unlink();
                        } else {
                            before.setLiteral(literal.substring(0, literal.length() - marker.length()));
                        }
                    }
                }
                visitChildren(node);
            }

            @Override
            public void visit(Text text) {
                if (text instanceof EscapedText) { // its mark has served: renderers know only Text itself
                    Text plain = new Text(text.getLiteral());
                    plain.setSourceSpans(text.getSourceSpans());
                    text.insertAfter(plain);
                    text.unlink();
                }
            }
        });
        return document;
    }

    private static final class WikiLinkExtension
            implements Parser.ParserExtension,
                    HtmlRenderer.HtmlRendererExtension,
                    TextContentRenderer.TextContentRendererExtension {
        @Override
        public void extend(Parser.Builder builder) {
            builder.customInlineContentParserFactory(new InlineContentParserFactory() {
                @Override
                public Set<Character> getTriggerCharacters() {
                    return Set.of('\\');
                }

                @Override
                public InlineContentParser create() {
                    return MarkdownWikiLinks::escaped;
                }
            });
            builder.linkProcessor(new Processor());
            builder.postProcessor(MarkdownWikiLinks::dropOuterBrackets);
        }

        @Override
        public void extend(HtmlRenderer.Builder builder) {
            builder.nodeRendererFactory(context -> new NodeRenderer() {
                @Override
                public Set<Class<? extends Node>> getNodeTypes() {
                    return Set.of(WikiLink.class);
                }

                @Override
                public void render(Node node) {
                    WikiLink link = (WikiLink) node;
                    Map<String, String> attrs = new java.util.LinkedHashMap<>();
                    attrs.put("class", "wikilink");
                    if (link.isEmbed()) {
                        attrs.put("src", encode(link.getTarget()));
                        attrs.put("alt", link.getText());
                        context.getWriter().tag("img", context.extendAttributes(node, "img", attrs), true);
                        return;
                    }
                    attrs.put("href", encode(link.getReference()));
                    context.getWriter().tag("a", context.extendAttributes(node, "a", attrs));
                    context.getWriter().text(link.getText());
                    context.getWriter().tag("/a");
                }
            });
        }

        @Override
        public void extend(TextContentRenderer.Builder builder) {
            builder.nodeRendererFactory(context -> new NodeRenderer() {
                @Override
                public Set<Class<? extends Node>> getNodeTypes() {
                    return Set.of(WikiLink.class);
                }

                @Override
                public void render(Node node) {
                    context.getWriter().write(((WikiLink) node).getText());
                }
            });
        }
    }
}
