package com.editora.ui;

import com.editora.command.CommandRegistry;
import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The edit commands whose pure cores were fixed in the third review round (findings E2-*), driven through
 * the real command registry: what matters here is that the command hands the core the buffer's language,
 * selection and tab size, and applies what comes back.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EditOpsCommandsFxTest {

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

    private void run(String id) throws Exception {
        FxTestSupport.runOnFx(() -> registry.run(id));
        FxTestSupport.drainFx();
    }

    /** A buffer of {@code language} holding {@code text}, with {@code [selStart, selEnd)} selected. */
    private EditorBuffer buffer(String language, String text, int selStart, int selEnd) throws Exception {
        EditorBuffer b = FxTestSupport.callOnFx(() -> {
            EditorBuffer buffer = new EditorBuffer();
            buffer.setLanguageOverride(language);
            buffer.setContent(text);
            FxTestSupport.call(
                    fx.controller, "addBuffer", new Class[] {EditorBuffer.class, boolean.class}, buffer, true);
            buffer.getArea().selectRange(selStart, selEnd);
            return buffer;
        });
        FxTestSupport.drainFx();
        return b;
    }

    private static String text(EditorBuffer b) throws Exception {
        return FxTestSupport.callOnFx(() -> b.getArea().getText());
    }

    private static String selected(EditorBuffer b) throws Exception {
        return FxTestSupport.callOnFx(() -> b.getArea().getSelectedText());
    }

    @Test
    void killSexpLeavesTheBufferAloneWhenTheBracketNeverCloses() throws Exception {
        String src = "x (a b\nmore\n";
        EditorBuffer b = buffer("java", src, 1, 1);
        run("edit.killSexp");
        assertEquals(src, text(b));
        run("edit.markSexp");
        assertEquals("", selected(b));
    }

    @Test
    void killSexpStopsAtTheMatchingBraceDespiteAnApostropheInAComment() throws Exception {
        EditorBuffer b = buffer("java", "f() { // don't\n  a();\n}\nint g() {}\n", 3, 3);
        run("edit.killSexp");
        assertEquals("f()\nint g() {}\n", text(b));
    }

    @Test
    void fillParagraphLeavesCodeAloneAndRespectsMarkdownLists() throws Exception {
        String java = "int a = 1;\nint b = 2;\nreturn a + b;\n";
        EditorBuffer code = buffer("java", java, 3, 3);
        run("edit.fillParagraph");
        assertEquals(java, text(code));
        String list = "- one\n- two\n- three\n";
        EditorBuffer md = buffer("markdown", list, 3, 3);
        run("edit.fillParagraph");
        assertEquals(list, text(md));
        run("edit.selectAll");
        run("edit.fillRegion");
        assertEquals(list, text(md));
    }

    @Test
    void moveAndDuplicateTakeEveryLineOfTheSelection() throws Exception {
        String src = "a\nb1\nb2\nc\n";
        EditorBuffer b = buffer("plaintext", src, 3, 6); // from inside b1 to inside b2
        run("edit.moveLineDown");
        assertEquals("a\nc\nb1\nb2\n", text(b));
        assertEquals("1\nb", selected(b), "the block stays selected");
        run("edit.moveLineUp");
        run("edit.moveLineUp");
        assertEquals("b1\nb2\na\nc\n", text(b));
        run("edit.duplicateLine");
        assertEquals("b1\nb2\nb1\nb2\na\nc\n", text(b));
        assertEquals("1\nb", selected(b));
    }

    @Test
    void toggleCommentUncommentsSelectedLineComments() throws Exception {
        String src = "    // a\n    // b\n";
        EditorBuffer b = buffer("java", src, 0, src.length());
        run("edit.toggleComment");
        assertEquals("    a\n    b\n", text(b));
    }

    @Test
    void caseCycleAtTheCaretDoesNotEatAMinus() throws Exception {
        EditorBuffer java = buffer("java", "x = count-1;", 6, 6);
        run("edit.case.cycle");
        assertEquals("x = COUNT-1;", text(java));
        EditorBuffer css = buffer("css", ".my-class {}", 3, 3);
        run("edit.case.cycle");
        assertEquals(".MyClass {}", text(css));
    }
}
