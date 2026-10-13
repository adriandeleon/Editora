package com.editora.markdown;

import java.util.ArrayList;
import java.util.List;

import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.BulletList;
import org.commonmark.node.Code;
import org.commonmark.node.CustomBlock;
import org.commonmark.node.CustomNode;
import org.commonmark.node.Emphasis;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.Node;
import org.commonmark.node.Paragraph;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.text.TextContentRenderer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkdownMathTest {

    private static final List<org.commonmark.Extension> EXT =
            List.of(MarkdownMath.extension(), TablesExtension.create());
    private static final Parser PARSER = Parser.builder().extensions(EXT).build();

    private static Node parse(String md) {
        return PARSER.parse(md);
    }

    /** Every math node in document order, as {@code i:latex}, {@code d:latex} (display inline) or {@code b:latex}. */
    private static List<String> math(String md) {
        List<String> out = new ArrayList<>();
        parse(md).accept(new AbstractVisitor() {
            @Override
            public void visit(CustomNode node) {
                if (node instanceof MarkdownMath.Inline in) {
                    out.add((in.isDisplay() ? "d:" : "i:") + in.getLatex());
                }
                visitChildren(node);
            }

            @Override
            public void visit(CustomBlock node) {
                if (node instanceof MarkdownMath.Block b) {
                    out.add((b.isClosed() ? "b:" : "open:") + b.getLatex());
                }
                visitChildren(node);
            }
        });
        return out;
    }

    private static <T extends Node> boolean contains(String md, Class<T> type) {
        boolean[] found = {false};
        parse(md).accept(new AbstractVisitor() {
            @Override
            public void visit(Emphasis n) {
                found[0] |= type == Emphasis.class;
                visitChildren(n);
            }

            @Override
            public void visit(BulletList n) {
                found[0] |= type == BulletList.class;
                visitChildren(n);
            }
        });
        return found[0];
    }

    @Test
    void backslashCommandsReachTheRendererVerbatim() {
        // The bundled samples/markdown/math.md: "\," used to arrive as a literal comma.
        assertEquals(
                List.of("b:\\int_0^1 x^2 \\, dx = \\frac{1}{3}"),
                math("$$\n\\int_0^1 x^2 \\, dx = \\frac{1}{3}\n$$\n"));
        assertEquals(List.of("i:\\{1,2\\}"), math("Set $\\{1,2\\}$ here."));
        assertEquals(
                List.of("i:a \\! b \\; c \\% d \\_ e \\& f \\# g"), math("$a \\! b \\; c \\% d \\_ e \\& f \\# g$"));
    }

    @Test
    void rowBreaksSurviveInABlock() {
        String md = "$$\n\\begin{aligned} a &= b \\\\ c &= d \\end{aligned}\n$$\n";
        assertEquals(List.of("b:\\begin{aligned} a &= b \\\\ c &= d \\end{aligned}"), math(md));
        // The common spelling with the environment on the delimiter lines.
        assertEquals(
                List.of("b:\\begin{aligned}\na &= b \\\\\nc &= d\n\\end{aligned}"),
                math("$$\\begin{aligned}\na &= b \\\\\nc &= d\n\\end{aligned}$$\n"));
    }

    @Test
    void emphasisMarkersInsideMathAreNotMarkdown() {
        assertEquals(List.of("i:a*b*c"), math("$a*b*c$"));
        assertFalse(contains("$a*b*c$", Emphasis.class));
        assertEquals(List.of("i:a_b + c_d"), math("so $a_b + c_d$ holds"));
        assertEquals(List.of("i:f(x) = x<y"), math("$f(x) = x<y$"));
    }

    @Test
    void anEscapedDollarIsNeverADelimiter() {
        assertEquals(List.of(), math("Escaped \\$a\\$ stays text"));
        assertEquals(List.of(), math("Price \\$5 and \\$10."));
        assertEquals(List.of("i:\\$5"), math("costs $\\$5$ today"));
    }

    @Test
    void proseDollarsAreNotMath() {
        assertEquals(List.of(), math("Costs $5 and $10 today."));
        assertEquals(List.of(), math("Between $5-$10 each."));
        assertEquals(List.of(), math("Shell: $HOME/bin:$PATH and ${FOO}/$BAR"));
        assertEquals(List.of(), math("a $ b $ c"));
        assertEquals(List.of(), math("an empty $$ pair and a lone $ sign"));
        assertEquals(List.of(), math("$not closed\non this line$"));
    }

    @Test
    void theClosingDollarMayBeFollowedByPunctuationButNotALetterOrDigit() {
        assertEquals(List.of("i:x"), math("($x$), then"));
        assertEquals(List.of("i:x"), math("$x$-axis"));
        assertEquals(List.of(), math("$x$y"));
        assertEquals(List.of(), math("$x$2"));
    }

    @Test
    void codeIsNeverScanned() {
        assertEquals(List.of(), math("`$a$` and ``$$b$$``"));
        assertEquals(List.of(), math("```\n$a$\n$$\nb\n$$\n```\n"));
        assertEquals(List.of(), math("    $$\n    indented code\n    $$\n"));
        assertEquals(List.of(), math("$a `b$ c`$"));
        assertInstanceOf(Code.class, parse("`$a$`").getFirstChild().getFirstChild());
        assertInstanceOf(FencedCodeBlock.class, parse("```\n$$\nx\n$$\n```").getFirstChild());
    }

    @Test
    void aBlockInterruptsAParagraphAndKeepsListLookingLines() {
        String md = "Block math:\n$$\na\n+ b\n- c\n$$\nafter\n";
        assertEquals(List.of("b:a\n+ b\n- c"), math(md));
        assertFalse(contains(md, BulletList.class));
        Node doc = parse(md);
        assertInstanceOf(Paragraph.class, doc.getFirstChild());
        assertInstanceOf(MarkdownMath.Block.class, doc.getFirstChild().getNext());
        assertInstanceOf(Paragraph.class, doc.getLastChild());
    }

    @Test
    void singleLineBlockAndDisplayInsideAParagraph() {
        assertEquals(List.of("b:\\frac{1}{2}"), math("$$\\frac{1}{2}$$\n"));
        assertInstanceOf(MarkdownMath.Block.class, parse("$$\\frac{1}{2}$$\n").getFirstChild());
        assertEquals(List.of("d:x^2"), math("so $$x^2$$ follows"));
        assertEquals(List.of("d:a", "d:b"), math("$$a$$ and $$b$$"));
        assertEquals(List.of("d:a +\nb"), math("text $$ a +\nb $$ text"));
    }

    @Test
    void proseStartingWithTwoDollarsOpensNoBlock() {
        assertEquals(List.of(), math("$$ is the shell's PID\n\n# Heading\n"));
        assertEquals(1, countHeadings("$$ is the shell's PID\n\n# Heading\n"));
    }

    @Test
    void anUnclosedBlockIsMarkedAndBlocksInContainersWork() {
        assertEquals(List.of("open:a = b"), math("$$\na = b\n"));
        assertEquals(List.of("b:q"), math("> $$\n> q\n> $$\n"));
        assertEquals(List.of("b:l"), math("- item\n\n  $$\n  l\n  $$\n"));
    }

    @Test
    void mathInTableCellsAndHeadings() {
        assertEquals(List.of("i:x^2", "i:\\alpha"), math("| a | b |\n|---|---|\n| $x^2$ | $\\alpha$ |\n"));
        assertEquals(List.of("i:E=mc^2"), math("# Energy $E=mc^2$\n"));
    }

    @Test
    void sourceRoundTripsAndPlainTextKeepsTheFormula() {
        assertEquals("$x$", MarkdownMath.source(new MarkdownMath.Inline("x", false)));
        assertEquals("$$x$$", MarkdownMath.source(new MarkdownMath.Inline("x", true)));
        assertNull(MarkdownMath.source(new Paragraph()));
        TextContentRenderer text = TextContentRenderer.builder().extensions(EXT).build();
        String out = text.render(parse("Euler: $e^{i\\pi}$ done\n\n$$\na \\\\ b\n$$\n\nend\n"));
        assertTrue(out.contains("Euler: $e^{i\\pi}$ done"), out);
        assertTrue(out.contains("$$a \\\\ b$$"), out);
        assertTrue(out.strip().endsWith("end"), out);
    }

    @Test
    void delimiterHelpers() {
        assertEquals(2, MarkdownMath.indexOfDelimiter("a $$ b", 0));
        assertEquals(-1, MarkdownMath.indexOfDelimiter("a \\$$ b", 0));
        assertEquals(4, MarkdownMath.closingDelimiter("a b $$  "));
        assertEquals(-1, MarkdownMath.closingDelimiter("$$ a $$"));
        assertEquals(-1, MarkdownMath.closingDelimiter("a $"));
    }

    private static int countHeadings(String md) {
        int[] n = {0};
        parse(md).accept(new AbstractVisitor() {
            @Override
            public void visit(org.commonmark.node.Heading h) {
                n[0]++;
            }
        });
        return n[0];
    }

    @Test
    void aMathFenceIsADisplayFormulaWithNothingToEscape() {
        assertEquals(List.of("b:a &= b \\\\\nc &= d"), math("```math\na &= b \\\\\nc &= d\n```\n"));
        assertEquals(List.of("b:x_1 * y_2 * z"), math("~~~ Math\nx_1 * y_2 * z\n~~~\n"), "any fence, any case");
        assertEquals(List.of(), math("```mathematica\nPlot[x]\n```\n"), "another language is code");
        assertEquals(List.of(), math("```\nmath\n```\n"));
        assertInstanceOf(FencedCodeBlock.class, parse("```java\nint x;\n```\n").getFirstChild());
        assertEquals(List.of("b:q"), math("> ```math\n> q\n> ```\n"));
    }

    @Test
    void latexDelimitersAreMathWhenTheyHoldMath() {
        assertEquals(List.of("i:x^2 + y^2"), math("so \\(x^2 + y^2\\) holds"));
        assertEquals(List.of("i:\\frac{a}{b}"), math("\\(\\frac{a}{b}\\)"));
        assertEquals(List.of("d:E = mc^2"), math("\\[E = mc^2\\]"));
        assertEquals(
                List.of("d:a = b,\nc = d"),
                math("\\[\na = b,\nc = d\n\\]\n"),
                "display may span the paragraph's lines");
        assertEquals(List.of("i:a_i", "i:b_j"), math("\\(a_i\\) and $b_j$"));
    }

    @Test
    void escapedBracketsInProseStayEscapedBrackets() {
        assertEquals(List.of(), math("see \\(this note\\) and \\[not a link\\]"));
        assertEquals(List.of(), math("[f\\(x\\)](https://example.org/f)"), "an escaped paren in link text");
        assertEquals(List.of(), math("1\\) first, 2\\) second, a \\( alone"));
        assertEquals(List.of(), math("\\(x^2\nacross lines\\)"), "inline math stays on its line");
        assertEquals(List.of(), math("`\\(x^2\\)`"));
        assertEquals(List.of(), math("\\(\\)"));
        // What they render as is what they always did: the brackets, without the backslashes.
        org.commonmark.renderer.text.TextContentRenderer text =
                org.commonmark.renderer.text.TextContentRenderer.builder()
                        .extensions(EXT)
                        .build();
        assertEquals(
                "see (this note) and [not a link]",
                text.render(parse("see \\(this note\\) and \\[not a link\\]")).strip());
        assertTrue(MarkdownMath.looksLikeMath("x^2"));
        assertTrue(MarkdownMath.looksLikeMath("\\alpha"));
        assertFalse(MarkdownMath.looksLikeMath("this note"));
        assertFalse(MarkdownMath.looksLikeMath("x"));
        assertFalse(MarkdownMath.looksLikeMath(" "));
        assertFalse(MarkdownMath.looksLikeMath(null));
    }
}
