package com.editora.ui;

import java.util.List;

import com.editora.editor.highlight.TextMateHighlighter.Symbol;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The Python Structure's constants, fields and def markers without a language server (review 2 C113). */
class PythonOutlineSymbolsTest {

    @Test
    void moduleConstantsAndClassFieldsButNoLocals() {
        String src = String.join(
                "\n",
                "MAX = 1",
                "name: str",
                "x == 1",
                "if DEBUG:",
                "    level = 2",
                "for i in range(3):",
                "    total = i",
                "s = \"\"\"",
                "inside = 1",
                "\"\"\"",
                "class A:",
                "    \"\"\"Doc.\"\"\"",
                "    field: int = 0",
                "    def m(self):",
                "        local = 1",
                "    other = 2",
                "self.attr = 3",
                "");
        assertEquals(
                List.of(
                        new Symbol(0, "MAX", "constant"),
                        new Symbol(1, "name", "variable"),
                        new Symbol(7, "s", "variable"),
                        new Symbol(12, "field", "field"),
                        new Symbol(15, "other", "field")),
                PythonOutlineSymbols.variables(src));
    }

    @Test
    void markersComeFromTheDefAndItsDecorators() {
        List<String> lines = List.of(
                "class A:",
                "    @functools.cached_property",
                "    def a(self): ...",
                "    @staticmethod",
                "    @overload",
                "    def b(): ...",
                "    async def c(self): ...",
                "    @app.route(\"/\")",
                "    def d(self): ...",
                "    @value.setter",
                "    def value(self, v): ...");
        assertEquals("property", PythonOutlineSymbols.marker(lines::get, 2));
        assertEquals("staticmethod overload", PythonOutlineSymbols.marker(lines::get, 5));
        assertEquals("async", PythonOutlineSymbols.marker(lines::get, 6));
        assertEquals("", PythonOutlineSymbols.marker(lines::get, 8));
        assertEquals("setter", PythonOutlineSymbols.marker(lines::get, 10));
        assertEquals("", PythonOutlineSymbols.marker(lines::get, 0));
    }

    @Test
    void aSymbolsNameIsFoundAsAWholeWord() {
        // Review 2 A105: `def f(` matched the f inside `def`, and "de" was shown as the return type.
        assertEquals(4, StructurePanel.nameColumn("def f(match, case):", "f"));
        assertEquals(10, StructurePanel.nameColumn("async def a(self):", "a"));
        assertEquals(8, StructurePanel.nameColumn("    def e(self):", "e"));
        assertEquals(4, StructurePanel.nameColumn("fun f() = 1", "f"));
        assertEquals(0, StructurePanel.nameColumn("operator+ x", "operator+"));
        assertEquals(-1, StructurePanel.nameColumn("x = 1", "f"));
    }
}
