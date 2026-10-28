package com.editora.editops;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which closer a hand-typed close tag may join: the innermost open element's only (XML review 2 C2). */
class TagJoinTest {

    @Test
    void theInnermostOpenElementsCloserJoins() {
        assertTrue(TagJoin.closesInnermost("<item>t", "item", false));
        assertTrue(TagJoin.closesInnermost("<list><item>t</item><item>u", "item", false));
        assertTrue(TagJoin.closesInnermost("<a:b x=\"1\">t", "a:b", false));
    }

    @Test
    void anOuterElementsCloserDoesNot() {
        // <p>Hello <b>world|</p>: the </p> after the caret is not the one being typed.
        assertFalse(TagJoin.closesInnermost("<p>Hello <b>world", "p", false));
        assertFalse(TagJoin.closesInnermost("<p>Hello <b>world", "p", true), "b's end tag is not optional in HTML");
    }

    @Test
    void xmlNamesAreCaseSensitiveAndHtmlNamesAreNot() {
        assertFalse(TagJoin.closesInnermost("<Item>t", "item", false));
        assertTrue(TagJoin.closesInnermost("<DIV>t", "div", true));
    }

    @Test
    void htmlPassesOverElementsWhoseEndTagIsOptional() {
        assertTrue(TagJoin.closesInnermost("<ul><li>one<li>two", "ul", true));
        assertFalse(TagJoin.closesInnermost("<ul><li>one<li>two", "ul", false), "XML has no optional end tags");
        assertTrue(TagJoin.closesInnermost("<div><br><img src=\"a.png\">x", "div", true), "void elements");
    }

    @Test
    void markupThatIsNotATagIsSkipped() {
        String text = "<root><!-- <b> --><![CDATA[<c>]]><?pi <d>?><e a=\"<f>\"/><g/>x";
        assertEquals(List.of("root"), TagJoin.openElements(text, false));
        assertEquals(List.of("div", "script"), TagJoin.openElements("<div><script>if (a<b) {", true));
        assertEquals(List.of("div"), TagJoin.openElements("<div><script>if (a<b) {}</script>", true));
    }

    @Test
    void nothingOpenInTheWindowLetsTheJoinHappen() {
        assertTrue(TagJoin.closesInnermost("t", "item", false), "the element started before the window");
        assertTrue(TagJoin.closesInnermost("x</stray>", "item", false));
    }

    @Test
    void anUnclosedInnerElementIsClosedByItsParentsEndTag() {
        assertEquals(List.of("a"), TagJoin.openElements("<a><b><c>x</b>", false));
        assertTrue(TagJoin.closesInnermost("<a><b><c>x</b>", "a", false));
    }
}
