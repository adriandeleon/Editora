package com.editora.ui;

import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Python typed through a real {@link EditorBuffer}'s typing assists, the way a user types it: the text that
 * comes out must be valid, correctly nested Python (review findings A1, A2, A4).
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PythonTypingFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    /**
     * Loads {@code content} as Python with the caret at its end and types {@code keys}; {@code \b} is a
     * Backspace key press. Returns the text.
     */
    private static String type(String content, String keys) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setLanguageOverride("python");
            b.setContent(content);
            b.getNode();
            b.getArea().moveTo(content.length());
            for (char c : keys.toCharArray()) {
                if (c == '\b') {
                    b.pressKey("BACK_SPACE");
                } else {
                    b.typeChar(c);
                }
            }
            return b.getArea().getText();
        });
    }

    @Test
    void anIfElseTypedLineByLineComesOutNested() throws Exception {
        // A1: `else:` used to stay inside the `if` body and `return 2` a level deeper again.
        assertEquals(
                "def f(x):\n    if x:\n        return 1\n    else:\n        return 2\n    ",
                type("def f(x):\n", "    if x:\nreturn 1\nelse:\nreturn 2\n"));
    }

    @Test
    void tryExceptFinallyTypedLineByLineLineUpWithTheTry() throws Exception {
        assertEquals(
                "try:\n    x = 1\nexcept ValueError:\n    pass\nfinally:\n    pass\n",
                type("", "try:\nx = 1\nexcept ValueError:\npass\nfinally:\npass\n"));
    }

    @Test
    void anElseTypedAfterABodyThatDoesNotEndTheBlockStillAligns() throws Exception {
        // The body's last line is an ordinary statement, so Enter keeps its indent; the `:` of `else` aligns.
        assertEquals("for i in x:\n    total += i\nelse:\n    ", type("for i in x:\n    total += i\n", "    else:\n"));
        // Within nested blocks the clause goes to the statement it can continue.
        assertEquals(
                "try:\n    for i in x:\n        f(i)\nexcept OSError:",
                type("try:\n    for i in x:\n        f(i)\n", "        except OSError:"));
    }

    @Test
    void enterThenBackspaceStepsOutOneLevelAndKeepsTheLine() throws Exception {
        // A2: Backspace on the blank, auto-indented line used to join it to `pass` (`passdef n(self):`).
        assertEquals(
                "class A:\n    def m(self):\n        x = 1\n    \n    def n(self):",
                type("class A:\n    def m(self):\n        x = 1", "\n\b\ndef n(self):"));
    }

    @Test
    void backspaceBeforeIndentedCodeRemovesOneLevel() throws Exception {
        assertEquals("if a:\n    if b:\n    x", type("if a:\n    if b:\n        ", "\bx"));
    }

    @Test
    void enterAfterAWrappedStatementGoesBackToTheStatementsIndent() throws Exception {
        // A4: the next line used to keep the continuation's indent (27 spaces).
        assertEquals(
                "def g():\n    result = some_function(arg_one,\n                           arg_two)\n    y",
                type("def g():\n    result = some_function(arg_one,\n                           arg_two)", "\ny"));
        // PEP 8's own wrapped `if`: the body goes one unit past the `if`, not past the continuation.
        assertEquals(
                "def g():\n    pass\nif (this_is_one_thing and\n        that_is_another_thing):\n    do_something()",
                type(
                        "def g():\n    pass\nif (this_is_one_thing and\n        that_is_another_thing):",
                        "\ndo_something()"));
    }

    @Test
    void enterInsideAnOpenBracketHangsOrLinesUpWithTheFirstArgument() throws Exception {
        assertEquals("x = foo(a,\n        b)", type("x = foo(a,", "\nb)"));
        assertEquals("x = foo(\n    a,\n    b,", type("x = foo(", "\na,\nb,"));
    }
}
