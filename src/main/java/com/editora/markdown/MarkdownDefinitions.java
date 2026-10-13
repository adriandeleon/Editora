package com.editora.markdown;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.commonmark.Extension;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.CustomBlock;
import org.commonmark.node.Node;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.SourceSpan;
import org.commonmark.node.Text;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.NodeRenderer;
import org.commonmark.renderer.html.HtmlRenderer;
import org.commonmark.renderer.html.HtmlWriter;
import org.commonmark.renderer.text.TextContentRenderer;

/**
 * Definition lists, as in PHP Markdown Extra and Pandoc: a term on a line of its own, and under it one or more
 * definitions, each starting with a colon and a space.
 *
 * <pre>
 * Apple
 * : A fruit.
 * : A computer company.
 *
 * Orange
 *
 * : The fruit of an evergreen tree.
 * </pre>
 *
 * <p>A paragraph of that shape becomes a {@link DefinitionList} holding {@link Term}s and {@link Description}s
 * (both with inline content: emphasis, links, code work in either). A line under a definition that does not
 * start with a colon continues it. The loose form — a blank line between the term and its definition — is
 * read too, and a definition paragraph right after a list adds to that list. Several terms may share the
 * definitions under them.
 *
 * <p>Done on the parsed tree, so a colon line inside a code block, a quote's lazy line or a table is whatever
 * CommonMark made of it; only a paragraph is ever looked at. Pure. The extension renders the nodes for
 * {@code HtmlRenderer} ({@code <dl>/<dt>/<dd>}) and {@code TextContentRenderer}.
 */
public final class MarkdownDefinitions {

    private MarkdownDefinitions() {}

    /** A run of terms and their definitions. Children: {@link Term} and {@link Description}, in order. */
    public static final class DefinitionList extends CustomBlock {}

    /** What is defined; its children are inline nodes. */
    public static final class Term extends CustomBlock {}

    /** One definition of the term(s) above it; its children are inline nodes. */
    public static final class Description extends CustomBlock {}

    public static Extension extension() {
        return new DefinitionsExtension();
    }

    /** Whether a line of text opens a definition: a colon, then a space or a tab. Pure. */
    static boolean opensDefinition(String line) {
        return line != null
                && line.length() >= 2
                && line.charAt(0) == ':'
                && (line.charAt(1) == ' ' || line.charAt(1) == '\t');
    }

    static Node process(Node document) {
        List<Paragraph> paragraphs = new ArrayList<>();
        document.accept(new AbstractVisitor() {
            @Override
            public void visit(Paragraph paragraph) {
                paragraphs.add(paragraph);
            }
        });
        for (Paragraph p : paragraphs) {
            convert(p);
        }
        return document;
    }

    /** The first node of each line of {@code p} (lines are separated by soft breaks). */
    private static List<Node> lineStarts(Paragraph p) {
        List<Node> starts = new ArrayList<>();
        boolean atStart = true;
        for (Node n = p.getFirstChild(); n != null; n = n.getNext()) {
            if (n instanceof SoftLineBreak) {
                atStart = true;
            } else if (atStart) {
                starts.add(n);
                atStart = false;
            }
        }
        return starts;
    }

    private static boolean isDefinitionStart(Node lineStart) {
        return lineStart instanceof Text t && opensDefinition(t.getLiteral());
    }

    private static void convert(Paragraph p) {
        if (p.getParent() == null) {
            return; // already taken into a list as the terms of a loose definition
        }
        List<Node> starts = lineStarts(p);
        int first = -1;
        for (int i = 0; i < starts.size(); i++) {
            if (isDefinitionStart(starts.get(i))) {
                first = i;
                break;
            }
        }
        if (first < 0) {
            return;
        }
        DefinitionList list;
        Node previous = p.getPrevious();
        if (first > 0) {
            list = new DefinitionList();
            p.insertBefore(list);
        } else if (previous instanceof DefinitionList existing) {
            list = existing; // "Term\n: a\n\n: b" — a further definition, after a blank line
        } else if (previous instanceof Paragraph terms && isPlainTerms(terms)) {
            list = new DefinitionList(); // the loose form: the paragraph above is the term(s)
            terms.insertBefore(list);
            List<SourceSpan> spans = new ArrayList<>(terms.getSourceSpans());
            list.setSourceSpans(spans);
            for (Node start : lineStarts(terms)) {
                list.appendChild(line(new Term(), start, false));
            }
            terms.unlink();
        } else {
            return; // a paragraph that merely starts with a colon
        }
        List<SourceSpan> spans = new ArrayList<>(list.getSourceSpans());
        spans.addAll(p.getSourceSpans());
        list.setSourceSpans(spans);
        Description current = null;
        for (int i = 0; i < starts.size(); i++) {
            Node start = starts.get(i);
            if (i < first) {
                list.appendChild(line(new Term(), start, false));
            } else if (isDefinitionStart(start)) {
                current = (Description) line(new Description(), start, true);
                list.appendChild(current);
            } else {
                current.appendChild(new SoftLineBreak()); // a lazy continuation line of the definition above
                line(current, start, false);
            }
        }
        p.unlink();
    }

    /** A paragraph that can be the terms of a loose definition: short lines of plain inline content. */
    private static boolean isPlainTerms(Paragraph p) {
        List<Node> starts = lineStarts(p);
        if (starts.isEmpty() || starts.size() > 3) {
            return false;
        }
        for (Node start : starts) {
            if (isDefinitionStart(start)) {
                return false;
            }
        }
        return true;
    }

    /** Moves the line starting at {@code start} (up to the next soft break) into {@code into}. */
    private static Node line(Node into, Node start, boolean stripColon) {
        if (stripColon && start instanceof Text t) {
            t.setLiteral(t.getLiteral().substring(1).stripLeading());
        }
        for (Node n = start; n != null && !(n instanceof SoftLineBreak); ) {
            Node next = n.getNext();
            if (!(n instanceof Text t && t.getLiteral().isEmpty())) {
                into.appendChild(n);
            } else {
                n.unlink();
            }
            n = next;
        }
        return into;
    }

    private static final class DefinitionsExtension
            implements Parser.ParserExtension,
                    HtmlRenderer.HtmlRendererExtension,
                    TextContentRenderer.TextContentRendererExtension {
        @Override
        public void extend(Parser.Builder builder) {
            builder.postProcessor(MarkdownDefinitions::process);
        }

        @Override
        public void extend(HtmlRenderer.Builder builder) {
            builder.nodeRendererFactory(context -> new NodeRenderer() {
                @Override
                public Set<Class<? extends Node>> getNodeTypes() {
                    return Set.of(DefinitionList.class, Term.class, Description.class);
                }

                @Override
                public void render(Node node) {
                    String tag = node instanceof DefinitionList ? "dl" : node instanceof Term ? "dt" : "dd";
                    HtmlWriter html = context.getWriter();
                    html.line();
                    html.tag(tag, context.extendAttributes(node, tag, Map.of()));
                    if (node instanceof DefinitionList) {
                        html.line();
                    }
                    for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
                        context.render(child);
                    }
                    html.tag("/" + tag);
                    html.line();
                }
            });
        }

        @Override
        public void extend(TextContentRenderer.Builder builder) {
            builder.nodeRendererFactory(context -> new NodeRenderer() {
                @Override
                public Set<Class<? extends Node>> getNodeTypes() {
                    return Set.of(DefinitionList.class, Term.class, Description.class);
                }

                @Override
                public void render(Node node) {
                    for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
                        context.render(child);
                    }
                    if (!(node instanceof DefinitionList)) {
                        context.getWriter().block();
                    }
                }
            });
        }
    }
}
