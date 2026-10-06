package com.editora.ui;

import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The electric de-indent for keyword closers ({@code fi}, {@code done}, {@code end}) and Enter's indent,
 * typed through a real {@link EditorBuffer}'s typing assists — the pure {@code Indenter} tests cannot show
 * where the caret ends up after the line is re-aligned under it.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CloserDedentFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    /** Loads {@code content} as {@code language} with the caret at its end, types {@code keys}, returns the text. */
    private static String type(String language, String content, String keys) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setLanguageOverride(language);
            b.setContent(content);
            b.getNode();
            b.getArea().moveTo(content.length());
            b.typeString(keys);
            return b.getArea().getText();
        });
    }

    @Test
    void aWordThatOnlyStartsWithACloserKeywordKeepsItsIndent() throws Exception {
        // `fi` is how `find` begins: it used to de-indent the line at the `i`.
        assertEquals(
                "if x; then\n    find . -name a\n",
                type("shell", "if x; then\n    ", "find . -name a\n").replaceAll("[ ]+$", ""));
        assertEquals("def f\n    endpoint = 1", type("ruby", "def f\n    ", "endpoint = 1"));
        assertEquals("function f()\n    ending = 1", type("lua", "function f()\n    ", "ending = 1"));
    }

    @Test
    void enterAfterACloserKeywordAlignsItWithItsOpener() throws Exception {
        assertEquals("if x; then\n    echo\nfi\n", type("shell", "if x; then\n    echo\n    ", "fi\n"));
        assertEquals("def f\n    x\nend\n", type("ruby", "def f\n    x\n    ", "end\n"));
    }

    @Test
    void aTerminatorAfterACloserKeywordAlignsItAndTypingContinuesAfterIt() throws Exception {
        // The re-align replaces the indent under the caret; the caret must come back after the keyword, or
        // the terminator and everything after it would be typed in front of it.
        assertEquals("if x; then\n    echo\nelif y; then", type("shell", "if x; then\n    echo\n    ", "elif y; then"));
        assertEquals(
                "case x in\n    a)\n        echo\n    ;; # done",
                type("shell", "case x in\n    a)\n        echo\n        ", ";; # done"));
    }

    /** As {@link #type}, but with the caret directly after the first occurrence of {@code after}. */
    private static String typeAfter(String language, String content, String after, String keys) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setLanguageOverride(language);
            b.setContent(content);
            b.getNode();
            b.getArea().moveTo(content.indexOf(after) + after.length());
            b.typeString(keys);
            return b.getArea().getText();
        });
    }

    @Test
    void enterAfterAnAlreadyAlignedNestedCloserLeavesItWhereItIs() throws Exception {
        // The re-align is relative ("nearest shallower line"), so an aligned closer used to be stepped out
        // to the ENCLOSING block on every Enter — the ordinary way to start the next method.
        assertEquals(
                "class Foo\n  def bar\n    x = 1\n  end\n  \nend\n",
                typeAfter("ruby", "class Foo\n  def bar\n    x = 1\n  end\nend\n", "  end", "\n"));
        String sh = "deploy() {\n    if [ -n \"$1\" ]; then\n        echo go\n    else\n        echo stop\n    fi\n}\n";
        assertEquals(sh.replace("    fi\n", "    fi\n    \n"), typeAfter("shell", sh, "    fi", "\n"));
        assertEquals(sh.replace("    else\n", "    else\n        \n"), typeAfter("shell", sh, "    else", "\n"));
        String lua = "function f()\n    if x then\n        y()\n    end\nend\n";
        assertEquals(lua.replace("    end\n", "    end\n    \n"), typeAfter("lua", lua, "    end", "\n"));
        // Tab-indented, and pressed twice: it must not walk out a level per press either.
        String tabs = "f() {\n\tif a; then\n\t\tb\n\tfi\n}\n";
        assertEquals(tabs.replace("\tfi\n", "\tfi\n\t\n\t\n"), typeAfter("shell", tabs, "\tfi", "\n\n"));
    }

    @Test
    void aTerminatorAfterAnAlreadyAlignedNestedCloserLeavesItWhereItIs() throws Exception {
        String sh = "deploy() {\n    if a; then\n        b\n    fi\n}\n";
        assertEquals(sh.replace("    fi", "    fi # end"), typeAfter("shell", sh, "    fi", " # end"));
        assertEquals(sh.replace("    fi", "    fi;"), typeAfter("shell", sh, "    fi", ";"));
        String rb = "class Foo\n  def bar\n    x = 1\n  end\nend\n";
        assertEquals(rb.replace("  end\n", "  end.compact\n"), typeAfter("ruby", rb, "  end", ".compact"));
        // An empty block: the closer sits directly under its opener, at the opener's indent.
        String empty = "f() {\n    if a; then\n    fi\n}\n";
        assertEquals(empty.replace("    fi", "    fi "), typeAfter("shell", empty, "    fi", " "));
    }

    @Test
    void aControlCharacterIsNotATerminator() throws Exception {
        // Backspace/Escape deliver a KEY_TYPED control character; `fix` + Backspace is on its way to `find`.
        assertEquals("if x; then\n    fi\b", typeAfter("shell", "if x; then\n    fi", "    fi", "\b"));
        assertEquals("if x; then\n    fi\u001b", typeAfter("shell", "if x; then\n    fi", "    fi", "\u001b"));
    }

    @Test
    void enterAtColumnZeroOfAnIndentedLineDoesNotIndentItFurther() throws Exception {
        String text = FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setLanguageOverride("java");
            b.setContent("class C {\n    foo();\n}\n");
            b.getNode();
            b.getArea().moveTo("class C {\n".length());
            b.typeString("\n");
            return b.getArea().getText();
        });
        assertEquals("class C {\n\n    foo();\n}\n", text);
    }
}
