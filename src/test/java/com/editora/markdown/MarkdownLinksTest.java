package com.editora.markdown;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkdownLinksTest {

    private static String apply(String text, MarkdownEdit e) {
        StringBuilder out = new StringBuilder(text.substring(0, e.from()) + e.replacement() + text.substring(e.to()));
        if (e.selStart() == e.selEnd()) {
            out.insert(e.selStart(), '‸');
        } else {
            out.insert(e.selEnd(), '»').insert(e.selStart(), '«');
        }
        return out.toString();
    }

    @Test
    void findsTheInlineLinkUnderTheCaret() {
        String t = "see [the *docs*](http://x.com/a_(b) \"T\") and [b](<my file.md>).";
        MarkdownLinks.Link a = MarkdownLinks.at(t, 6);
        assertEquals("the *docs*", a.text());
        assertEquals("http://x.com/a_(b)", a.destination());
        assertEquals(4, a.start());
        assertEquals(t.indexOf(" and"), a.end());
        assertFalse(a.isReference());
        assertEquals("my file.md", MarkdownLinks.at(t, t.indexOf("[b]") + 1).destination());
        assertNull(MarkdownLinks.at(t, 1));
        assertNull(MarkdownLinks.at("[not a link] here", 3));
        assertTrue(MarkdownLinks.at("![alt](p.png)", 3).image());
        assertEquals(0, MarkdownLinks.at("![alt](p.png)", 3).start());
    }

    @Test
    void findsReferenceLinksAndTheirDefinitions() {
        String t = "a [text][Ref One] b [solo] c [x][]\n\n[ref one]: http://one\n[solo]: <two words.md>\n[x]: #frag\n";
        MarkdownLinks.Link full = MarkdownLinks.at(t, 4);
        assertEquals("Ref One", full.label());
        assertEquals("http://one", full.destination());
        assertEquals(
                "two words.md", MarkdownLinks.at(t, t.indexOf("[solo]") + 2).destination());
        assertEquals("#frag", MarkdownLinks.at(t, t.indexOf("[x][]") + 1).destination());
        assertNull(MarkdownLinks.at(t, t.indexOf("[ref one]:") + 2), "a definition is not a link");
        assertEquals("", MarkdownLinks.at("[a][nope]", 1).destination());
    }

    @Test
    void editALinkOrMakeOne() {
        String t = "see [docs](http://old) now";
        assertEquals(
                "see [the docs](http://new/a b)‸ now".replace("(http://new/a b)", "(<http://new/a b>)"),
                apply(t, MarkdownLinks.set(t, MarkdownLinks.at(t, 6), 0, 0, "the docs", "http://new/a b")));
        assertEquals(
                "see [docs](x.md)‸ now",
                apply("see docs now", MarkdownLinks.set("see docs now", null, 4, 8, "docs", "x.md")));
        assertEquals(
                "![new alt](p.png)‸",
                apply(
                        "![alt](p.png)",
                        MarkdownLinks.set(
                                "![alt](p.png)", MarkdownLinks.at("![alt](p.png)", 2), 0, 0, "new alt", "p.png")));
    }

    @Test
    void editingAReferenceLinkRetargetsItsDefinition() {
        String below = "a [t][r] b\n\n[r]: http://old\n";
        assertEquals(
                "a [new][r]‸ b\n\n[r]: http://new\n",
                apply(below, MarkdownLinks.set(below, MarkdownLinks.at(below, 3), 0, 0, "new", "http://new")));
        String above = "[r]: http://old\n\na [t][r] b";
        assertEquals(
                "[r]: http://new\n\na [new][r]‸ b",
                apply(
                        above,
                        MarkdownLinks.set(
                                above, MarkdownLinks.at(above, above.indexOf("[t]") + 1), 0, 0, "new", "http://new")));
        String undefined = "a [t][r] b\n";
        assertEquals(
                "a [t][r]‸ b\n\n[r]: u",
                apply(undefined, MarkdownLinks.set(undefined, MarkdownLinks.at(undefined, 3), 0, 0, "t", "u")));
    }

    @Test
    void removeALinkKeepsItsText() {
        String t = "see [the *docs*](http://x) now";
        assertEquals("see «the *docs*» now", apply(t, MarkdownLinks.remove(MarkdownLinks.at(t, 6))));
        assertEquals("«alt»", apply("![alt](p.png)", MarkdownLinks.remove(MarkdownLinks.at("![alt](p.png)", 3))));
    }

    @Test
    void inlineToReferenceAndBack() {
        String t = "a [docs](http://x) b\n";
        String ref = apply(t, MarkdownLinks.toReference(t, MarkdownLinks.at(t, 3)));
        assertEquals("a [docs][1]‸ b\n\n[1]: http://x", ref);
        String two = "a [docs][1] and [more](http://y)\n\n[1]: http://x";
        assertEquals(
                "a [docs][1] and [more][2]‸\n\n[1]: http://x\n[2]: http://y",
                apply(two, MarkdownLinks.toReference(two, MarkdownLinks.at(two, two.indexOf("[more]") + 1))),
                "the next number, under the last definition");
        String same = "a [docs][1] and [again](http://x)\n\n[1]: http://x";
        assertEquals(
                "a [docs][1] and [again][1]‸\n\n[1]: http://x",
                apply(same, MarkdownLinks.toReference(same, MarkdownLinks.at(same, same.indexOf("[again]") + 1))),
                "a destination that is defined already reuses its label");
        assertNull(MarkdownLinks.toReference(two, MarkdownLinks.at(two, 3)), "already a reference");

        String back = "a [docs][1] b\n\n[1]: http://x\n";
        assertEquals("a [docs](http://x)‸ b\n", apply(back, MarkdownLinks.toInline(back, MarkdownLinks.at(back, 3))));
        String shared = "a [docs][1] and [again][1]\n\n[1]: http://x";
        assertEquals(
                "a [docs](http://x)‸ and [again][1]\n\n[1]: http://x",
                apply(shared, MarkdownLinks.toInline(shared, MarkdownLinks.at(shared, 3))),
                "a definition another link uses stays");
        String defAbove = "[1]: http://x\ntext [docs][1] end";
        assertEquals(
                "text [docs](http://x)‸ end",
                apply(
                        defAbove,
                        MarkdownLinks.toInline(defAbove, MarkdownLinks.at(defAbove, defAbove.indexOf("[docs]") + 1))));
        assertNull(MarkdownLinks.toInline(t, MarkdownLinks.at(t, 3)));
        assertNotNull(MarkdownLinks.at(back, 3));
    }
}
