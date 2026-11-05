package com.editora.editor.highlight;

import com.editora.editops.Commenter;
import com.editora.editops.Indenter;
import org.eclipse.tm4e.core.grammar.IGrammar;
import org.eclipse.tm4e.core.grammar.IStateStack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsxContextTest {

    private static final IGrammar JS = GrammarRegistry.shared().forFileName("a.js");

    private static final String COMPONENT = String.join(
            "\n",
            "function A() {",
            "  const x = 1;",
            "  return (",
            "    <ul className=\"list\">",
            "      {/* <li>old</li> */}",
            "      <li>one</li>",
            "      {items.map((i) => (",
            "        <li key={i}>{i}</li>",
            "      ))}",
            "    </ul>",
            "  );",
            "}",
            "");

    private static HighlightPass.Lines lines(String text) {
        var a = TextMateHighlighter.analyzeFrom(text, JS, 0, null);
        return new HighlightPass.Lines(a.endStates().toArray(new IStateStack[0]), null);
    }

    private static boolean children(int line) {
        return JsxContext.lineInChildren(JS, lines(COMPONENT), line, COMPONENT.split("\n")[line]);
    }

    @Test
    void markupBetweenTagsIsChildrenAndCodeIsNot() {
        assertFalse(children(1), "const x = 1;");
        assertFalse(children(3), "<ul>: the root element sits in the return's parentheses, code");
        assertTrue(children(4), "an existing {/* */} comment line is markup");
        assertTrue(children(5), "<li>one</li> under <ul>");
        assertFalse(children(7), "<li> inside a map callback is an expression again");
        assertFalse(children(10), ");");
    }

    @Test
    void plainTypeScriptHasNoJsx() {
        IGrammar ts = GrammarRegistry.shared().forFileName("a.ts");
        assertFalse(JsxContext.childrenAfter(ts, null, "const el = <div>"));
        assertTrue(JsxContext.childrenAfter(JS, null, "const el = <div>"));
        assertFalse(JsxContext.childrenAfter(null, null, "<div>"));
    }

    @Test
    void theJsxCommentToggles() {
        String text = "    <li>one</li>\n";
        Commenter.Edit on = Commenter.toggle(text, 6, 6, JsxContext.COMMENT);
        assertNotNull(on);
        String commented = text.substring(0, on.from()) + on.replacement() + text.substring(on.to());
        assertEquals("    {/* <li>one</li> */}\n", commented);
        Commenter.Edit off = Commenter.toggle(commented, 8, 8, JsxContext.COMMENT);
        assertNotNull(off);
        assertEquals(text, commented.substring(0, off.from()) + off.replacement() + commented.substring(off.to()));
    }

    @Test
    void openingTagsAreFoundAtTheEndOfTheTextBeforeTheCaret() {
        assertEquals("div", JsxContext.openingTagAtEnd("const a = <div>"));
        assertEquals("div", JsxContext.openingTagAtEnd("  <div className=\"a>b\" onClick={() => go(1 > 0)}>"));
        assertEquals("Foo.Bar", JsxContext.openingTagAtEnd("<Foo.Bar>"));
        assertEquals("", JsxContext.openingTagAtEnd("  <>"));
        assertNull(JsxContext.openingTagAtEnd("  </div>"), "a closing tag");
        assertNull(JsxContext.openingTagAtEnd("  <br />"), "a self-closing tag");
        assertNull(JsxContext.openingTagAtEnd("const f = (x) =>"), "an arrow");
        assertNull(JsxContext.openingTagAtEnd("  <li>a</li>"), "the line ends with a closer");
        assertNull(JsxContext.openingTagAtEnd("if (a > b)"));
    }

    @Test
    void enterAfterAnOpeningTagGoesInAndSplitsFromItsCloser() {
        Indenter.EnterEdit split = JsxContext.enter(JS, null, 0, "  <ul></ul>", 6, "  ");
        assertNotNull(split);
        assertEquals("\n    \n  ", split.insert());
        assertEquals(5, split.caretOffset());
        Indenter.EnterEdit in = JsxContext.enter(JS, null, 0, "  <div className=\"x\">", 21, "  ");
        assertNotNull(in);
        assertEquals("\n    ", in.insert());
        assertNull(JsxContext.enter(JS, null, 0, "  <li>a</li>", 12, "  "), "after a closer: the usual rules");
        IGrammar tsx = GrammarRegistry.shared().forFileName("a.tsx");
        assertNull(JsxContext.enter(tsx, null, 0, "const s = useState<string>", 26, "  "), "a type argument");
    }
}
