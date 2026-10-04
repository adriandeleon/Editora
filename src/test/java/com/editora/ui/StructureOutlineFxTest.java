package com.editora.ui;

import java.lang.reflect.Field;
import java.util.List;

import com.editora.editor.EditorBuffer;
import com.editora.editor.FoldRegions.Region;
import com.editora.editor.TextMateHighlighter.Symbol;
import com.editora.lsp.SymbolNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The outline behind the Jump to Structure picker: it has to be current while the Structure tool window is
 * closed (its default state), find a declaration whose signature wraps, and exist at all for languages
 * without delimiter folding.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StructureOutlineFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static void setSymbols(EditorBuffer buffer, List<Symbol> symbols) {
        try {
            Field field = EditorBuffer.class.getDeclaredField("symbols");
            field.setAccessible(true);
            field.set(buffer, symbols);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static List<String> outline(StructurePanel panel) {
        return panel.outline().stream().map(o -> o.label() + "@" + o.line()).toList();
    }

    @Test
    void theJumpOutlineIsCurrentWhileTheToolWindowIsClosed() throws Exception {
        List<List<String>> seen = FxTestSupport.callOnFx(() -> {
            StructurePanel panel = new StructurePanel(); // never in a scene: the tool window is closed
            EditorBuffer a = new EditorBuffer();
            a.setContent("class A {\n  void m() {}\n}\n");
            panel.attach(a);
            panel.setLspSymbols(
                    a,
                    List.of(new SymbolNode(
                            "A", "", "class", 0, 2, List.of(new SymbolNode("m", "()", "method", 1, 1, List.of())))));
            List<String> first = outline(panel);

            EditorBuffer b = new EditorBuffer();
            b.setContent("func main() {\n}\n");
            panel.attach(b);
            panel.setLspSymbols(b, List.of(new SymbolNode("main", "()", "function", 0, 1, List.of())));
            return List.of(first, outline(panel));
        });
        assertEquals(List.of("A@0", "m@1"), seen.get(0), "not empty just because the window is closed");
        assertEquals(List.of("main@0"), seen.get(1), "and not the previous file's outline after a switch");
    }

    @Test
    void aWrappedSignatureStillYieldsItsDeclaration() throws Exception {
        List<String> outline = FxTestSupport.callOnFx(() -> {
            StructurePanel panel = new StructurePanel();
            EditorBuffer buffer = new EditorBuffer();
            buffer.setLanguageOverride("java");
            buffer.setContent("class W {\n"
                    + "    private static List<Region> detectRegions(\n"
                    + "            String text,\n"
                    + "            String language) {\n"
                    + "        if (text.isEmpty()\n"
                    + "                || language == null) {\n"
                    + "            return List.of();\n"
                    + "        }\n"
                    + "        return List.of();\n"
                    + "    }\n"
                    + "}\n");
            setSymbols(buffer, List.of(new Symbol(0, "W", "type"), new Symbol(1, "detectRegions", "function")));
            buffer.getFoldManager().setServerRegions(List.of(new Region(0, 10), new Region(3, 9), new Region(5, 7)));
            panel.attach(buffer);
            return outline(panel);
        });
        assertEquals(List.of("W@0", "detectRegions@1"), outline, "the wrapped method, and not the wrapped if");
    }

    @Test
    void signatureStartIsOnlyLookedForBehindAClosingParen() {
        List<String> lines = List.of("void f(", "    int a,", "    int b) {", "  if (x) {", "  }).then(y -> {");
        assertEquals(0, StructurePanel.signatureStartLine(lines::get, 2));
        assertEquals(-1, StructurePanel.signatureStartLine(lines::get, 3), "balanced: an ordinary header");
        assertEquals(-1, StructurePanel.signatureStartLine(lines::get, 4), "the tail of a call chain");
    }

    @Test
    void aLanguageWithoutFoldRegionsGetsAnOutlineFromItsSymbols() throws Exception {
        List<String> outline = FxTestSupport.callOnFx(() -> {
            StructurePanel panel = new StructurePanel();
            EditorBuffer buffer = new EditorBuffer();
            buffer.setLanguageOverride("python");
            buffer.setContent(
                    "class Repo:\n    def load(self):\n        pass\n\n    def save(self):\n        pass\n\ndef main():\n    pass\n");
            setSymbols(
                    buffer,
                    List.of(
                            new Symbol(0, "Repo", "type"),
                            new Symbol(1, "load", "function"),
                            new Symbol(4, "save", "function"),
                            new Symbol(7, "main", "function")));
            panel.attach(buffer);
            return outline(panel);
        });
        assertEquals(List.of("Repo@0", "load@1", "save@4", "main@7"), outline);
    }
}
