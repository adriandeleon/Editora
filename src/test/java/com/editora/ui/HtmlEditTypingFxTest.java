package com.editora.ui;

import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Typing in an HTML page's {@code <script>} follows braces (HTML review C10), Toggle Comment there writes a script
 * comment (C3), and a template file gets Editora's tag features (C7) — through a real {@link EditorBuffer}.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HtmlEditTypingFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private EditorBuffer buffer(String language, String text) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setLanguageOverride(language);
            b.setContent(text);
            b.setAutoCloseTags(true);
            b.getNode();
            b.getArea().moveTo(text.length());
            return b;
        });
    }

    @Test
    void aFunctionTypedInAScriptIsIndentedAndItsBraceStepsBack() throws Exception {
        EditorBuffer b = buffer("html", "<body>\n  <script>\n");
        FxTestSupport.runOnFx(() -> b.typeString("    function f() {\nreturn 1;"));
        String text = FxTestSupport.callOnFx(b::getContent);
        assertTrue(text.endsWith("    function f() {\n      return 1;\n    }"), text);

        EditorBuffer typed = buffer("html", "<script>\n  if (x) {\n    y();\n    ");
        FxTestSupport.runOnFx(() -> typed.typeString("}"));
        String closed = FxTestSupport.callOnFx(typed::getContent);
        assertTrue(closed.endsWith("\n    y();\n  }"), closed);
    }

    @Test
    void toggleCommentInAScriptWritesAScriptComment() throws Exception {
        EditorBuffer b = buffer("html", "<script>\n  go();\n</script>\n");
        FxTestSupport.runOnFx(() -> {
            b.getArea().moveTo(12);
            assertTrue(b.toggleComment());
        });
        assertEquals("<script>\n  // go();\n</script>\n", FxTestSupport.callOnFx(b::getContent));
    }

    @Test
    void aTemplateFileClosesAndRenamesTags() throws Exception {
        EditorBuffer b = buffer("html-template", "{% block body %}\n");
        FxTestSupport.runOnFx(() -> b.typeString("<span>"));
        assertEquals("{% block body %}\n<span></span>", FxTestSupport.callOnFx(b::getContent));
        EditorBuffer hbs = buffer("handlebars", "");
        FxTestSupport.runOnFx(() -> hbs.typeString("<ul>"));
        assertEquals("<ul></ul>", FxTestSupport.callOnFx(hbs::getContent));
        assertTrue(FxTestSupport.callOnFx(hbs::supportsComments), "<!-- --> comments");
    }
}
