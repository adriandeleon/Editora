package com.editora.editops;

import com.editora.editops.TagRename.Mirror;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TagRenameTest {

    /**
     * Simulates typing: {@code before} contains a {@code |}-marked caret; {@code typed} is inserted
     * there (replacing {@code removedLen} chars after the caret first). Returns the text after the
     * mirror is applied, or {@code null} when no mirror was produced.
     */
    private static String type(String before, String typed, int removedLen, boolean html) {
        int pos = before.indexOf('|');
        String base = before.replace("|", "");
        String removed = base.substring(pos, pos + removedLen);
        String after = base.substring(0, pos) + typed + base.substring(pos + removedLen);
        Mirror m = TagRename.mirror(after, pos, removed, typed, html);
        if (m == null) {
            return null;
        }
        return after.substring(0, m.from()) + m.name() + after.substring(m.to());
    }

    private static String typeHtml(String before, String typed) {
        return type(before, typed, 0, true);
    }

    // --- basic open → close and close → open ---

    @Test
    void renamingOpenTagUpdatesCloser() {
        assertEquals("<divx>text</divx>", typeHtml("<div|>text</div>", "x"));
    }

    @Test
    void renamingCloseTagUpdatesOpener() {
        assertEquals("<divx>text</divx>", typeHtml("<div>text</div|>", "x"));
    }

    @Test
    void selectionReplaceRenames() {
        // Select "div" (caret at its start) and type "span" over it.
        assertEquals("<span>text</span>", type("<|div>text</div>", "span", 3, true));
    }

    @Test
    void deletionRenames() {
        assertEquals("<dv>text</dv>", type("<d|iv>text</div>", "", 1, true));
    }

    // --- nesting / siblings ---

    @Test
    void nestedSameNameTagsPairCorrectly() {
        assertEquals("<divx><div>a</div></divx>", typeHtml("<div|><div>a</div></div>", "x"));
    }

    @Test
    void innerTagPairsWithInnerCloser() {
        assertEquals("<div><emx>a</emx></div>", typeHtml("<div><em|>a</em></div>", "x"));
    }

    @Test
    void siblingsDoNotInterfere() {
        assertEquals("<divx>a</divx><div>b</div>", typeHtml("<div|>a</div><div>b</div>", "x"));
        assertEquals("<div>a</div><divx>b</divx>", typeHtml("<div>a</div><div|>b</div>", "x"));
    }

    // --- guards: no mirror ---

    @Test
    void editOutsideATagNameIsIgnored() {
        assertNull(typeHtml("<div>te|xt</div>", "x"));
    }

    @Test
    void editInAnAttributeIsIgnored() {
        assertNull(typeHtml("<div class=\"a|\">text</div>", "x"));
    }

    @Test
    void freshTagTypingDoesNotRenameAnUnrelatedCloser() {
        // Typing a new "<e" before <em>: positionally <e would pair with </em>, but the old-name
        // guard (old name empty) blocks the rename.
        assertNull(typeHtml("<div><e|<em>a</em></div>", "e"));
    }

    @Test
    void pairWithADifferentNameIsNotRenamed() {
        // Malformed: <div> closed by </span> — old name "div" doesn't match, so no rename.
        assertNull(typeHtml("<div|>text</span>", "x"));
    }

    @Test
    void unclosedTagHasNoMirror() {
        assertNull(typeHtml("<div|>text", "x"));
    }

    @Test
    void closerWithoutOpenerHasNoMirror() {
        assertNull(typeHtml("text</div|>", "x"));
    }

    @Test
    void selfClosingTagHasNoMirror() {
        assertNull(typeHtml("<div|/>text", "x"));
    }

    @Test
    void hugeDocumentIsNotScanned() {
        String text = "<div>" + "a".repeat(TagRename.MAX_DOC) + "</div>";
        assertNull(TagRename.mirror(text, 4, "", "x", true));
    }

    // --- real-world HTML: optional close tags (the bug positional pairing had) ---

    @Test
    void unclosedOptionalCloseTagsDoNotBreakThePairing() {
        // <li>/<p> without closers are valid HTML5 — the edited tag must still find its own pair.
        assertEquals("<ulx><li>a<li>b</ulx>", typeHtml("<ul|><li>a<li>b</ul>", "x"));
        assertEquals("<divx><p>one<p>two</divx>", typeHtml("<div|><p>one<p>two</div>", "x"));
        assertEquals("<divx><p>a</divx>", typeHtml("<div|><p>a</div>", "x"));
    }

    @Test
    void closerRenameAlsoSurvivesUnclosedTags() {
        assertEquals("<ulx><li>a<li>b</ulx>", typeHtml("<ul><li>a<li>b</ul|>", "x"));
    }

    @Test
    void editingAnUnclosedOptionalCloseTagHasNoMirror() {
        assertNull(typeHtml("<ul><li|>a<li>b</ul>", "x"));
    }

    // --- skipped constructs ---

    @Test
    void tagInsideACommentIsIgnored() {
        assertNull(typeHtml("<!-- <div|>text</div> -->", "x"));
    }

    @Test
    void commentBetweenPairIsSkipped() {
        assertEquals("<divx><!-- </div> -->a</divx>", typeHtml("<div|><!-- </div> -->a</div>", "x"));
    }

    @Test
    void quotedAttributeWithAngleBracketIsSkipped() {
        assertEquals("<divx title=\"a > b\">c</divx>", typeHtml("<div| title=\"a > b\">c</div>", "x"));
    }

    @Test
    void cdataIsSkipped() {
        assertEquals("<ax><![CDATA[</a>]]></ax>", type("<a|><![CDATA[</a>]]></a>", "x", 0, false));
    }

    @Test
    void doctypeAndProcessingInstructionAreSkipped() {
        assertEquals(
                "<?xml version=\"1.0\"?><rootx></rootx>", type("<?xml version=\"1.0\"?><root|></root>", "x", 0, false));
    }

    // --- html specifics ---

    @Test
    void voidElementsAreSkippedInHtml() {
        assertEquals("<divx>a<br>b</divx>", typeHtml("<div|>a<br>b</div>", "x"));
    }

    @Test
    void voidElementsPairNormallyInXml() {
        assertEquals("<brx>a</brx>", type("<br|>a</br>", "x", 0, false));
    }

    @Test
    void scriptContentIsSkippedInHtml() {
        assertEquals(
                "<divx><script>if (a < b) { s = \"</div>\"; }</script>a</divx>",
                typeHtml("<div|><script>if (a < b) { s = \"</div>\"; }</script>a</div>", "x"));
    }

    @Test
    void htmlTagNamesMatchCaseInsensitively() {
        assertEquals("<DIVx>a</DIVx>", typeHtml("<DIV|>a</div>", "x"));
    }

    @Test
    void xmlTagNamesMatchCaseSensitively() {
        assertNull(type("<DIV|>a</div>", "x", 0, false));
    }

    @Test
    void namespacedXmlTagRenames() {
        assertEquals("<ns:itemx>a</ns:itemx>", type("<ns:item|>a</ns:item>", "x", 0, false));
    }

    // --- changeInTagName: the cheap local gate EditorBuffer runs before materializing the whole document ---

    @Test
    void changeInTagNameTrueInsideOpenTagName() {
        // 'x' at index 4 sits inside the name of <divx>.
        assertTrue(TagRename.changeInTagName("<divx>a</div>", 4, 5));
    }

    @Test
    void changeInTagNameTrueInsideCloseTagName() {
        assertTrue(TagRename.changeInTagName("</divx>", 5, 6));
    }

    @Test
    void changeInTagNameFalseInTextContent() {
        assertFalse(TagRename.changeInTagName("<div>hix</div>", 7, 8));
    }

    @Test
    void changeInTagNameFalseInAttributeValue() {
        assertFalse(TagRename.changeInTagName("<div id=x>", 8, 9));
    }
}
