package com.editora.ui;

import java.util.List;
import java.util.function.Consumer;

import javafx.event.Event;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

import com.editora.command.CommandRegistry;
import com.editora.editor.EditorBuffer;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * XML/pom review 4, C31 and C32, through a wired window (the Emacs commands run through the real registry):
 *
 * <ul>
 *   <li>C31: M-DEL / M-d on a tag name put the caret where the text was before auto-rename shortened the start tag
 *       above, so the next word renamed the <em>following</em> element ({@code <nentryext>}), and at the end of the
 *       file the next key threw.
 *   <li>C32: while a close-tag join was pending, every key but plain Backspace edited the joined closer's name, and
 *       auto-rename carried that to the start tag ({@code </it} + Ctrl+Backspace gave {@code <em>t</em>}).
 * </ul>
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TagJoinEditsFxTest {

    private FxWindowFixture fx;
    private CommandRegistry registry;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
        registry = FxTestSupport.field(fx.controller, "registry");
    }

    @AfterAll
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    /**
     * Opens {@code start} ({@code |} the caret) as the active {@code lang} buffer with auto-close and auto-rename on,
     * types {@code typed}, does {@code key}, types {@code after}; answers the text with {@code |} at the caret.
     */
    private String edit(String lang, String start, String typed, Consumer<CodeArea> key, String after)
            throws Exception {
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setLanguageOverride(lang);
            b.setContent(start.replace("|", ""));
            FxTestSupport.call(fx.controller, "addBuffer", new Class[] {EditorBuffer.class, boolean.class}, b, true);
            b.setAutoCloseTags(true);
            b.setAutoRenameTag(true);
            CodeArea area = b.getArea();
            area.moveTo(start.indexOf('|'));
            typed.chars().forEach(ch -> type(area, (char) ch));
            key.accept(area);
            after.chars().forEach(ch -> type(area, (char) ch));
            String text = area.getText();
            int at = area.getCaretPosition();
            assertTrue(at <= text.length(), "the caret is inside the document: " + at + " > " + text.length());
            return text.substring(0, at) + "|" + text.substring(at);
        });
    }

    private static void type(CodeArea area, char ch) {
        String s = String.valueOf(ch);
        Event.fireEvent(area, new KeyEvent(KeyEvent.KEY_TYPED, s, s, KeyCode.UNDEFINED, false, false, false, false));
    }

    private static Consumer<CodeArea> press(KeyCode code, boolean shift, boolean ctrl) {
        return area -> {
            Event.fireEvent(area, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, shift, ctrl, false, false));
            Event.fireEvent(area, new KeyEvent(KeyEvent.KEY_RELEASED, "", "", code, shift, ctrl, false, false));
        };
    }

    private Consumer<CodeArea> command(String id) {
        return area -> registry.run(id);
    }

    /** C31: M-DEL in a closer, then typing, renames that element; the next one is left alone. */
    @Test
    void killingAWordInACloserKeepsTheCaretInThatCloser() throws Exception {
        String next = "\n<next>n</next>";
        assertEquals(
                "<entry>t</entry|>" + next,
                edit("xml", "<item>t</item|>" + next, "", command("edit.backwardKillWord"), "entry"));
        assertEquals("<Z>t</Z|>" + next, edit("xml", "<item>t</|item>" + next, "", command("edit.killWord"), "Z"));
        assertEquals(
                "<Z>t</Z|>", edit("xml", "<item>t</item|>", "", command("edit.backwardKillWord"), "Z"), "end of file");
        assertEquals(
                "<Z>t</Z|>\n<p>more</p>",
                edit("html", "<li>t</li|>\n<p>more</p>", "", command("edit.backwardKillWord"), "Z"));
        assertEquals(
                "<entry|>t</entry>" + next,
                edit("xml", "<item|>t</item>" + next, "", command("edit.backwardKillWord"), "entry"),
                "in the start tag, as before");
    }

    /** C32: each key during a pending join acts on the user's own {@code </it}; the closer and start tag stay. */
    @Test
    void everyKeyDuringAJoinLeavesTheCloserAndTheStartTagAlone() throws Exception {
        for (String lang : List.of("xml", "html")) {
            String start = "<item>t|</item>";
            assertEquals(
                    "<item>t</|</item>", edit(lang, start, "</it", press(KeyCode.BACK_SPACE, false, true), ""), lang);
            assertEquals(
                    "<item>t</i|</item>", edit(lang, start, "</it", press(KeyCode.BACK_SPACE, true, false), ""), lang);
            assertEquals(
                    "<item>t</it|/item>", edit(lang, start, "</it", press(KeyCode.DELETE, false, false), ""), lang);
            assertEquals(
                    "<item>t</itx|</item>", edit(lang, start, "</it", area -> area.replaceSelection("x"), ""), lang);
            assertEquals("<item>t</|</item>", edit(lang, start, "</it", command("edit.backwardKillWord"), ""), lang);
            for (KeyCode key : List.of(KeyCode.ENTER, KeyCode.TAB)) {
                String out = edit(lang, start, "</it", press(key, false, false), "");
                assertTrue(out.startsWith("<item>t</it") && out.endsWith("|</item>"), lang + " " + key + ": " + out);
                assertTrue(out.length() > "<item>t</it|</item>".length(), lang + " " + key + " inserted: " + out);
            }
            String killed = edit(lang, start, "</it", command("edit.killWord"), "");
            assertTrue(killed.startsWith("<item>t</it|"), lang + " M-d: " + killed);
        }
    }

    /** The keys that are part of the join itself still work: plain Backspace, a space before the '>', rejoining. */
    @Test
    void theJoinsOwnKeysStillWork() throws Exception {
        for (String lang : List.of("xml", "html")) {
            assertEquals("<item>t</item >|", edit(lang, "<item>t|</item>", "</item >", area -> {}, ""), lang);
            assertEquals(
                    "<item>t</item>|",
                    edit(lang, "<item>t|</item>", "</item ", press(KeyCode.BACK_SPACE, false, false), ">"),
                    lang);
            assertEquals(
                    "<item>t</item>|",
                    edit(lang, "<item>t|</item>", "</it", press(KeyCode.BACK_SPACE, false, false), "tem>"),
                    lang);
        }
    }
}
