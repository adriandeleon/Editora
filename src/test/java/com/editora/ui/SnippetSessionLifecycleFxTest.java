package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

import com.editora.editor.EditorBuffer;
import com.editora.snippet.Snippet;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The snippet session as the keyboard sees it, through the buffer's real key filters: when it ends (N1),
 * what still works inside a field (N2, N22), what the window is told (N3), undo (N4), where Tab expands at
 * all (N5), and triggers of several words (N15).
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SnippetSessionLifecycleFxTest {

    private static final String FORI = "for (${1:int} ${2:i} = 0; $2 < ${3:max}; $2++) {\n\t$0\n}";

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static void press(EditorBuffer b, KeyCode code, boolean shift) {
        b.getArea().fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, shift, false, false, false));
    }

    private static void tab(EditorBuffer b) {
        press(b, KeyCode.TAB, false);
    }

    private static void type(EditorBuffer b, String text) {
        for (char c : text.toCharArray()) {
            String s = String.valueOf(c);
            b.getArea()
                    .fireEvent(new KeyEvent(KeyEvent.KEY_TYPED, s, s, KeyCode.UNDEFINED, false, false, false, false));
        }
    }

    /** A Java buffer holding {@code content} with the caret at its end, knowing {@code triggers} (trigger → body). */
    private static EditorBuffer buffer(String content, Map<String, String> triggers) {
        EditorBuffer b = new EditorBuffer();
        b.setLanguageOverride("java");
        b.setSnippetProvider(
                (lang, p) -> triggers.containsKey(p) ? new Snippet(p, p, triggers.get(p), "", "java") : null);
        b.setContent(content);
        b.getArea().moveTo(content.length());
        return b;
    }

    // --- N1 ---

    @Test
    void tabIndentsAgainOnceTheCaretHasLeftTheFields() throws Exception {
        FxTestSupport.runOnFx(() -> {
            EditorBuffer b = buffer("int a;\nfori", Map.of("fori", FORI));
            CodeArea area = b.getArea();
            tab(b);
            assertTrue(b.hasActiveSnippet());
            assertEquals("int", area.getSelectedText());
            String expanded = area.getText();

            area.moveTo(0); // a click on line 1, no edit
            tab(b);
            assertFalse(b.hasActiveSnippet(), "the session ended when the caret left its fields");
            assertEquals(0, area.getCurrentParagraph(), "the caret was not pulled back into the snippet");
            assertTrue(area.getSelectedText().isEmpty());
            assertTrue(area.getText().endsWith(expanded.substring("int a;".length())), "the snippet text is untouched");
        });
    }

    @Test
    void theSessionEndsByItselfWhenTheCaretLeaves() throws Exception {
        EditorBuffer b = FxTestSupport.callOnFx(() -> {
            EditorBuffer made = buffer("int a;\nfori", Map.of("fori", FORI));
            tab(made);
            made.getArea().moveTo(2);
            assertTrue(made.hasActiveSnippet(), "decided at the end of the event turn, not mid-move");
            return made;
        });
        FxTestSupport.drainFx();
        assertFalse(FxTestSupport.callOnFx(b::hasActiveSnippet));
    }

    @Test
    void movingIntoAnotherFieldMakesItTheActiveOne() throws Exception {
        FxTestSupport.runOnFx(() -> {
            EditorBuffer b = buffer("fori", Map.of("fori", FORI));
            CodeArea area = b.getArea();
            tab(b);
            int max = area.getText().indexOf("max");
            area.moveTo(max + 1); // a click inside the third field
            press(b, KeyCode.TAB, true);
            assertTrue(b.hasActiveSnippet());
            assertEquals("i", area.getSelectedText(), "Shift+Tab went back from the field the caret was put in");
        });
    }

    @Test
    void aBackgroundTabEndsItsSession() throws Exception {
        FxTestSupport.runOnFx(() -> {
            EditorBuffer b = buffer("fori", Map.of("fori", FORI));
            tab(b);
            assertTrue(b.hasActiveSnippet());
            b.setRenderingActive(false);
            assertFalse(b.hasActiveSnippet());
        });
    }

    // --- N2 / N22 ---

    @Test
    void autoPairsAndEnterIndentWorkInsideAField() throws Exception {
        FxTestSupport.runOnFx(() -> {
            EditorBuffer b = buffer("\tfn", Map.of("fn", "void ${1:name}() {\n\t${2:body}\n}"));
            CodeArea area = b.getArea();
            tab(b);
            tab(b);
            assertEquals("body", area.getSelectedText());
            type(b, "foo(");
            assertTrue(area.getText().contains("foo()"), "the bracket was paired: " + area.getText());
            assertTrue(b.hasActiveSnippet());
            type(b, ")"); // types over the closer it just got
            assertTrue(area.getText().contains("foo()") && !area.getText().contains("foo())"));
            type(b, " {");
            assertTrue(area.getText().contains("foo() {}"), area.getText());
            String fieldLine = area.getParagraph(area.getCurrentParagraph()).getText();
            press(b, KeyCode.ENTER, false);
            String newLine = area.getParagraph(area.getCurrentParagraph()).getText();
            assertTrue(newLine.isBlank() && newLine.length() > indentOf(fieldLine), "Enter indented the new line");
            assertTrue(b.hasActiveSnippet(), "and the session absorbed all of it");
            tab(b);
            assertFalse(b.hasActiveSnippet(), "Tab still went to the end of the snippet");
            assertEquals(area.getLength(), area.getCaretPosition());
        });
    }

    private static int indentOf(String line) {
        int i = 0;
        while (i < line.length() && Character.isWhitespace(line.charAt(i))) {
            i++;
        }
        return i;
    }

    @Test
    void aBracketTypedInAMirroredFieldIsPairedAndMirrored() throws Exception {
        FxTestSupport.runOnFx(() -> {
            EditorBuffer b = buffer("m", Map.of("m", "${1:x} = $1;"));
            tab(b);
            type(b, "f(");
            assertEquals("f() = f();", b.getArea().getText());
            assertTrue(b.hasActiveSnippet());
        });
    }

    @Test
    void backspaceInFrontOfAnEmptiedFieldKeepsTheSession() throws Exception {
        FxTestSupport.runOnFx(() -> {
            EditorBuffer b = buffer("fori", Map.of("fori", FORI));
            CodeArea area = b.getArea();
            tab(b);
            press(b, KeyCode.BACK_SPACE, false); // clears "int"
            assertTrue(area.getText().startsWith("for ( i"), area.getText());
            press(b, KeyCode.BACK_SPACE, false); // removes the "(" in front of the now empty field
            assertTrue(area.getText().startsWith("for  i"), area.getText());
            assertTrue(b.hasActiveSnippet(), "N22: the session used to end here without a sign");
            tab(b);
            assertEquals("i", area.getSelectedText(), "and Tab still finds the next field");
        });
    }

    // --- N4 ---

    @Test
    void undoingATypoInAFieldLeavesTabGoingToTheNextStop() throws Exception {
        FxTestSupport.runOnFx(() -> {
            EditorBuffer b = buffer("fori", Map.of("fori", FORI));
            CodeArea area = b.getArea();
            tab(b);
            tab(b);
            assertEquals("i", area.getSelectedText());
            type(b, "idxx");
            assertTrue(area.getText().contains("idxx = 0; idxx < max; idxx++"), area.getText());
            area.undo();
            assertTrue(b.hasActiveSnippet(), "the undo is followed, not treated as the end of the session");
            tab(b);
            assertEquals("max", area.getSelectedText(), "Tab moved on instead of indenting mid-expression");
            assertFalse(area.getText().contains("\t++") || area.getText().contains("  ++"), area.getText());
        });
    }

    // --- N3 ---

    @Test
    void theWindowIsToldWhereTheSessionIs() throws Exception {
        FxTestSupport.runOnFx(() -> {
            EditorBuffer b = buffer("fori", Map.of("fori", FORI));
            List<int[]> told = new ArrayList<>();
            b.setSnippetHooks(null, told::add);
            tab(b);
            assertArrayEquals(new int[] {1, 3}, told.get(told.size() - 1));
            assertNotNull(
                    FxTestSupport.field(b, "snippetOverlay"), "the field overlay is attached with the first session");
            tab(b);
            assertArrayEquals(new int[] {2, 3}, told.get(told.size() - 1));
            press(b, KeyCode.ESCAPE, false);
            assertNull(told.get(told.size() - 1), "null says the session is over");
            assertFalse(b.hasActiveSnippet());
        });
    }

    // --- N5 ---

    @Test
    void tabExpansionHasItsOwnSwitch() throws Exception {
        FxTestSupport.runOnFx(() -> {
            EditorBuffer b = buffer("fori", Map.of("fori", FORI));
            b.setSnippetTabExpansion(false);
            tab(b);
            assertFalse(b.getArea().getText().contains("for ("), "off: Tab is just Tab");
            assertFalse(b.hasActiveSnippet());

            EditorBuffer on = buffer("fori", Map.of("fori", FORI));
            tab(on);
            assertTrue(on.getArea().getText().startsWith("for (int i"));
        });
    }

    @Test
    void tabDoesNotExpandInsideACommentOrAString() throws Exception {
        FxTestSupport.runOnFx(() -> {
            for (String scope : List.of("comment", "string")) {
                String text = scope.equals("comment") ? "// see the main" : "String s = \"the main";
                EditorBuffer b = buffer(text, Map.of("main", "public static void main(String[] args) {\n\t$0\n}"));
                CodeArea area = b.getArea();
                area.setStyle(text.indexOf(scope.equals("comment") ? "//" : "\""), text.length(), List.of(scope));
                tab(b);
                assertFalse(area.getText().contains("public static void"), scope + ": " + area.getText());
            }
            EditorBuffer code = buffer("main", Map.of("main", "public static void main(String[] args) {\n\t$0\n}"));
            tab(code);
            assertTrue(code.getArea().getText().contains("public static void"), "in code it expands as before");
        });
    }

    @Test
    void tabDoesNotExpandInADelimitedFile() throws Exception {
        FxTestSupport.runOnFx(() -> {
            EditorBuffer b = buffer("name\tsig", Map.of("sig", "-- me"));
            b.setLanguageOverride("csv");
            tab(b);
            assertFalse(b.getArea().getText().contains("-- me"), "Tab is the field separator in a .tsv");
        });
    }

    // --- N15 / N8 ---

    @Test
    void aTriggerOfTwoWordsExpands() throws Exception {
        FxTestSupport.runOnFx(() -> {
            EditorBuffer b = buffer("} else if", Map.of("else if", "else if (${1:condition}) {\n\t$0\n}", "if", "IF"));
            tab(b);
            assertTrue(
                    b.getArea().getText().startsWith("} else if (condition) {"),
                    b.getArea().getText());
            assertEquals("condition", b.getArea().getSelectedText());
        });
    }

    @Test
    void aSpaceIndentedBodyFollowsATabIndentedBuffer() throws Exception {
        FxTestSupport.runOnFx(() -> {
            EditorBuffer b = buffer("\t\tif", Map.of("if", "if (${1:true})\n{\n    $0\n}"));
            b.setIndentOverride(false, 4);
            tab(b);
            assertEquals("\t\tif (true)\n\t\t{\n\t\t\t\n\t\t}", b.getArea().getText(), "N8: no spaces after the tabs");
        });
    }
}
