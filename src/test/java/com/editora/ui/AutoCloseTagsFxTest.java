package com.editora.ui;

import java.util.List;

import com.editora.editor.EditorBuffer;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * End-to-end (headless-FX) coverage of tag auto-closing: a real {@link EditorBuffer} with a real
 * {@code KEY_TYPED} {@code >} through the auto-close key filter must insert the matching closer and
 * leave the caret between the tags (the pure {@code TagAutoClose} decision is unit-tested separately).
 * Also covers the macro-replay path ({@code typeString}) and the setting gate.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AutoCloseTagsFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private EditorBuffer htmlBuffer(String text) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setLanguageOverride("html");
            b.setContent(text);
            b.setAutoCloseTags(true);
            b.getNode();
            return b;
        });
    }

    private static void typeKey(CodeArea area, String ch) {
        javafx.event.Event.fireEvent(
                area,
                new javafx.scene.input.KeyEvent(
                        javafx.scene.input.KeyEvent.KEY_TYPED,
                        ch,
                        ch,
                        javafx.scene.input.KeyCode.UNDEFINED,
                        false,
                        false,
                        false,
                        false));
    }

    @Test
    void typingTheClosingBracketInsertsTheCloser() throws Exception {
        EditorBuffer b = htmlBuffer("<body");
        int caret = FxTestSupport.callOnFx(() -> {
            CodeArea area = FxTestSupport.field(b, "area");
            area.moveTo(5);
            typeKey(area, ">");
            return area.getCaretPosition();
        });
        assertEquals("<body></body>", FxTestSupport.callOnFx(b::getContent));
        assertEquals(6, caret, "caret between the tags");
    }

    @Test
    void voidElementDoesNotAutoClose() throws Exception {
        EditorBuffer b = htmlBuffer("<br");
        FxTestSupport.runOnFx(() -> {
            CodeArea area = FxTestSupport.field(b, "area");
            area.moveTo(3);
            typeKey(area, ">");
        });
        assertEquals("<br>", FxTestSupport.callOnFx(b::getContent));
    }

    @Test
    void macroReplayTypingAlsoAutoCloses() throws Exception {
        EditorBuffer b = htmlBuffer("");
        FxTestSupport.runOnFx(() -> b.typeString("<section>"));
        assertEquals("<section></section>", FxTestSupport.callOnFx(b::getContent));
    }

    @Test
    void disabledSettingInsertsAPlainBracket() throws Exception {
        EditorBuffer b = htmlBuffer("<body");
        FxTestSupport.runOnFx(() -> {
            b.setAutoCloseTags(false);
            CodeArea area = FxTestSupport.field(b, "area");
            area.moveTo(5);
            typeKey(area, ">");
        });
        assertEquals("<body>", FxTestSupport.callOnFx(b::getContent));
    }

    /** With auto-close on, a habitual hand-typed close tag left {@code <from>x</</from>} (XML review B17). */
    @Test
    void aCloseTagTypedByHandJoinsTheOneAutoCloseInserted() throws Exception {
        EditorBuffer b = htmlBuffer("");
        FxTestSupport.runOnFx(() -> {
            b.setLanguageOverride("xml");
            b.typeString("<from>x</from>");
        });
        assertEquals("<from>x</from>", FxTestSupport.callOnFx(b::getContent));
        assertEquals(14, (int) FxTestSupport.callOnFx(() -> b.getArea().getCaretPosition()), "after the tag");

        // Through the key filter, then on past the tag: typing goes on normally.
        EditorBuffer keys = htmlBuffer("<p>a</p>");
        FxTestSupport.runOnFx(() -> {
            CodeArea area = FxTestSupport.field(keys, "area");
            area.moveTo(4);
            for (String ch : List.of("<", "/", "p", ">", "!")) {
                typeKey(area, ch);
            }
        });
        assertEquals("<p>a</p>!", FxTestSupport.callOnFx(keys::getContent));

        // A different name typed after the join is the user's: it is not stepped over.
        EditorBuffer other = htmlBuffer("<p>a</p>");
        FxTestSupport.runOnFx(() -> {
            CodeArea area = FxTestSupport.field(other, "area");
            area.moveTo(4);
            for (String ch : List.of("<", "/", "q", "p")) {
                typeKey(area, ch);
            }
        });
        assertEquals("<p>a</qp</p>", FxTestSupport.callOnFx(other::getContent), "the q unjoined: a tag of its own");

        // No tag after the caret: "</" is typed as it is.
        EditorBuffer plain = htmlBuffer("<p>a");
        FxTestSupport.runOnFx(() -> {
            CodeArea area = FxTestSupport.field(plain, "area");
            area.moveTo(4);
            typeKey(area, "<");
            typeKey(area, "/");
        });
        assertEquals("<p>a</", FxTestSupport.callOnFx(plain::getContent));
    }

    /** A buffer in {@code language} with auto-close and auto-rename on, as the settings ship. */
    private EditorBuffer renamingBuffer(String language, String text) throws Exception {
        EditorBuffer b = htmlBuffer(text);
        FxTestSupport.runOnFx(() -> {
            b.setLanguageOverride(language);
            b.setAutoRenameTag(true);
        });
        return b;
    }

    /** Types {@code keys} one KEY_TYPED event each at {@code caret}; answers the content and the caret after. */
    private static String typeAt(EditorBuffer b, int caret, String keys) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            CodeArea area = FxTestSupport.field(b, "area");
            area.moveTo(caret);
            for (char ch : keys.toCharArray()) {
                if (ch == '\b') {
                    backspace(area);
                } else {
                    typeKey(area, String.valueOf(ch));
                }
            }
            String text = b.getContent();
            int at = area.getCaretPosition();
            return text.substring(0, at) + "|" + text.substring(at);
        });
    }

    /**
     * XML review 2 C2: closing an inner element by hand before the outer one's end tag joined that end tag, the
     * typed name landed inside it and auto-rename mirrored it: {@code <bp>Hello <b>world</b>p>}.
     */
    @Test
    void closingAnInnerElementByHandLeavesTheOuterEndTagAlone() throws Exception {
        EditorBuffer xml = renamingBuffer("xml", "<p>Hello <b>world</p>");
        assertEquals("<p>Hello <b>world</b>|</p>", typeAt(xml, 17, "</b>"));
        EditorBuffer html = renamingBuffer("html", "<div>Hello <span>world</div>");
        assertEquals("<div>Hello <span>world</span>|</div>", typeAt(html, 22, "</span>"));
    }

    /** XML review 2 C2: a typo in the closing name gave {@code <itmeem>t</itme>em>}; now it is the user's own tag. */
    @Test
    void aTypoInAJoinedCloseTagIsPutBackAsATagOfItsOwn() throws Exception {
        EditorBuffer xml = renamingBuffer("xml", "<item>t</item>");
        assertEquals("<item>t</itme>|</item>", typeAt(xml, 7, "</itme>"));
        EditorBuffer html = renamingBuffer("html", "<li>t</li>");
        assertEquals("<li>t</lu>|</li>", typeAt(html, 5, "</lu>"));
        EditorBuffer early = renamingBuffer("xml", "<item>t</item>");
        assertEquals("<item>t</it>|</item>", typeAt(early, 7, "</it>"), "a '>' before the name is complete");
    }

    /** The join still takes the habitual hand-typed closer, with auto-rename on, in XML and HTML. */
    @Test
    void theHandTypedCloserOfTheInnermostElementStillJoins() throws Exception {
        EditorBuffer xml = renamingBuffer("xml", "<list><item>t</item></list>");
        assertEquals("<list><item>t</item>!|</list>", typeAt(xml, 13, "</item>!"));
        EditorBuffer spaced = renamingBuffer("xml", "<item>t</item>");
        assertEquals("<item>t</item |>", typeAt(spaced, 7, "</item "), "a space before the '>' is the same tag");
        EditorBuffer html = renamingBuffer("html", "<ul><li>one</ul>");
        assertEquals("<ul><li>one</ul>|", typeAt(html, 11, "</ul>"), "an open <li> needs no end tag");
        EditorBuffer typed = renamingBuffer("xml", "");
        FxTestSupport.runOnFx(() -> typed.typeString("<note><to>x</to></note>"));
        assertEquals("<note><to>x</to></note>", FxTestSupport.callOnFx(typed::getContent));
    }

    /** A Backspace key press, as the keyboard sends it ({@code \b} in {@link #typeAt}'s keys). */
    private static void backspace(CodeArea area) {
        javafx.event.Event.fireEvent(
                area,
                new javafx.scene.input.KeyEvent(
                        javafx.scene.input.KeyEvent.KEY_PRESSED,
                        "",
                        "",
                        javafx.scene.input.KeyCode.BACK_SPACE,
                        false,
                        false,
                        false,
                        false));
    }

    /**
     * XML/pom review 3, C22: Backspace during a join deleted the closer's own characters, and auto-rename mirrored
     * that onto the start tag ({@code </it} + Backspace×2 gave {@code <em>text</em>}; {@code </} + Backspace turned
     * the end tag into a start tag). Now it takes back the user's last character only.
     */
    @Test
    void backspaceDuringAJoinTakesBackOnlyWhatWasTyped() throws Exception {
        for (String lang : List.of("xml", "html")) {
            assertEquals(
                    "<item>text</i|</item>", typeAt(renamingBuffer(lang, "<item>text</item>"), 10, "</it\b"), lang);
            assertEquals(
                    "<item>text</|</item>", typeAt(renamingBuffer(lang, "<item>text</item>"), 10, "</it\b\b"), lang);
            assertEquals("<item>t<|</item>", typeAt(renamingBuffer(lang, "<item>t</item>"), 7, "</\b"), lang);
            // typing on after the Backspace joins the closer again: it is closed once
            assertEquals("<item>t</item>|", typeAt(renamingBuffer(lang, "<item>t</item>"), 7, "</it\btem>"), lang);
            assertEquals("<item>t</item>|", typeAt(renamingBuffer(lang, "<item>t</item>"), 7, "</\b/item>"), lang);
            assertEquals(
                    "<item>t</it>|</item>",
                    typeAt(renamingBuffer(lang, "<item>t</item>"), 7, "</it\bt>"),
                    lang + ": a '>' before the name is complete is the user's own tag");
        }
        assertEquals("<span>t<|</span>", typeAt(renamingBuffer("html", "<span>t</span>"), 7, "</\b"));
    }

    /** C22: after a space before the '>', the '>' typed went in as well: {@code <item>text</item >>}. */
    @Test
    void aSpaceBeforeTheGreaterThanStillStepsOverIt() throws Exception {
        for (String lang : List.of("xml", "html")) {
            assertEquals(
                    "<item>text</item >|", typeAt(renamingBuffer(lang, "<item>text</item>"), 10, "</item >"), lang);
            assertEquals(
                    "<item>text</item>|",
                    typeAt(renamingBuffer(lang, "<item>text</item>"), 10, "</item \b>"),
                    lang + ": the space taken back");
        }
    }

    /** C22: HTML names are case-insensitive: {@code <DIV>t</DIV>} + {@code </div>} gave {@code </div></DIV>}. */
    @Test
    void anHtmlCloserStepsOverInAnyCase() throws Exception {
        assertEquals("<DIV>t</DIV>|", typeAt(renamingBuffer("html", "<DIV>t</DIV>"), 6, "</div>"));
        assertEquals(
                "<Item>t</item>|</Item>", typeAt(renamingBuffer("xml", "<Item>t</Item>"), 7, "</item>"), "XML is not");
    }

    /** C22: a join left pending survived a caret move: back at the spot, typing gave {@code </x</item>}. */
    @Test
    void movingTheCaretDropsAPendingJoin() throws Exception {
        EditorBuffer b = renamingBuffer("xml", "<item>t</item>");
        typeAt(b, 7, "</");
        String after = FxTestSupport.callOnFx(() -> {
            CodeArea area = FxTestSupport.field(b, "area");
            area.moveTo(0);
            area.moveTo(9);
            typeKey(area, "x");
            return b.getContent();
        });
        assertEquals("<xitem>t</xitem>", after, "the closer is edited as text (and auto-rename mirrors it)");
    }

    /** D10 (HTML review): an {@code =} after an attribute name brings its quotes, as VS Code's autoCreateQuotes does. */
    @Test
    void anEqualsAfterAnAttributeNameBringsItsQuotes() throws Exception {
        assertEquals("<a class=\"|\">", typeAt(htmlBuffer("<a class>"), 8, "="));
        assertEquals("<a class=\"big|\">", typeAt(htmlBuffer("<a class>"), 8, "=big"));
        assertEquals("<a class=\"big\"|>", typeAt(htmlBuffer("<a class>"), 8, "=\"big\""), "typed quotes as usual");
        assertEquals("<a class=\"\"|>", typeAt(htmlBuffer("<a class>"), 8, "=\"\""), "an empty value typed out");
        assertEquals("<input data-x=\"|\"", typeAt(htmlBuffer("<input data-x"), 13, "="));
        assertEquals("<a title=\"a=|\">", typeAt(htmlBuffer("<a title=\"a\">"), 11, "="), "inside a value");
        assertEquals("<a=|>", typeAt(htmlBuffer("<a>"), 2, "="), "the element's own name");
        assertEquals("<p>x=|</p>", typeAt(htmlBuffer("<p>x</p>"), 4, "="), "text, not a tag");
        assertEquals("<a href=|'x'>", typeAt(htmlBuffer("<a href'x'>"), 7, "="), "the quote is there already");
        assertEquals(
                "<script>\nif (a <b) x =|\n</script>",
                typeAt(htmlBuffer("<script>\nif (a <b) x \n</script>"), 21, "="),
                "code in a script");
        assertEquals("<item key=|", typeAt(renamingBuffer("xml", "<item key"), 9, "="), "HTML only");
        EditorBuffer off = htmlBuffer("<a class>");
        FxTestSupport.runOnFx(() -> off.setAutoCloseTags(false));
        assertEquals("<a class=|>", typeAt(off, 8, "="), "the auto-close setting gates it");
    }
}
