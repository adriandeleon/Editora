package com.editora.editor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.tm4e.core.grammar.IGrammar;
import org.eclipse.tm4e.core.grammar.IStateStack;
import org.eclipse.tm4e.core.grammar.IToken;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bundled Python grammar (patched: see NOTICE) as the editor styles it: modern syntax keeps its colours,
 * nothing bleeds past its line, and declarations reach the Structure outline (review findings A5, A9).
 */
class PythonGrammarTest {

    private static final IGrammar PYTHON = GrammarRegistry.shared().forLanguageName("python");

    /** Each line of {@code source} as {@code text|style} tokens (blank tokens left out). */
    private static List<List<String>> tokens(String source) {
        List<List<String>> out = new ArrayList<>();
        IStateStack state = null;
        for (String line : source.split("\n", -1)) {
            var result = PYTHON.tokenizeLine(line + "\n", state, Duration.ofSeconds(5));
            state = result.getRuleStack();
            List<String> row = new ArrayList<>();
            for (IToken t : result.getTokens()) {
                int s = Math.min(t.getStartIndex(), line.length());
                int e = Math.min(t.getEndIndex(), line.length());
                String text = line.substring(s, e);
                if (!text.isBlank()) {
                    row.add(text.strip() + "|" + TextMateHighlighter.styleForScopes(t.getScopes()));
                }
            }
            out.add(row);
        }
        return out;
    }

    private static void assertHas(List<String> line, String... expected) {
        for (String e : expected) {
            assertTrue(line.contains(e), "expected " + e + " in " + line);
        }
    }

    @Test
    void genericFunctionsAndClassesKeepTheirNames() {
        var t = tokens("def first[T](items: list[T]) -> T: ...\nclass Box[T, *Ts, **P](Base): ...");
        assertHas(t.get(0), "first|function", "T|type", "items|variable");
        assertHas(t.get(1), "Box|type", "Ts|type", "P|type", "Base|type");
        var symbols = TextMateHighlighter.analyze("def first[T](x: T) -> T: ...\nclass Box[T]:\n    pass\n", PYTHON)
                .symbols();
        assertEquals(
                List.of(
                        new TextMateHighlighter.Symbol(0, "first", "function"),
                        new TextMateHighlighter.Symbol(1, "Box", "type")),
                symbols,
                "a type parameter is not a declaration of its own");
    }

    @Test
    void theTypeStatementDeclaresAnAlias() {
        var t = tokens("type Vector[T] = list[T]\ntype = 5\ntype(x)");
        assertHas(t.get(0), "type|keyword", "Vector|type");
        assertHas(t.get(1), "type|type"); // the builtin, assigned
        assertHas(t.get(2), "type|type");
        assertEquals(
                List.of(new TextMateHighlighter.Symbol(0, "Vector", "type")),
                TextMateHighlighter.analyze("type Vector[T] = list[T]\n", PYTHON)
                        .symbols());
    }

    @Test
    void templateStringsInterpolateLikeFStrings() {
        var t = tokens("a = t\"Hi {name}\"\nb = rt'x {n}\\d'\nSENTINEL = 1");
        assertHas(t.get(0), "t|keyword", "Hi|string", "name|null");
        assertHas(t.get(1), "rt|keyword", "n|null");
        assertHas(t.get(2), "SENTINEL|null", "1|number");
    }

    @Test
    void matchIsAKeywordOnlyOnAMatchStatement() {
        var t = tokens("match(x)\nmatch command.split():\n    case [a]:\n        pass\nmatch = 3");
        assertHas(t.get(0), "match|function");
        assertHas(t.get(1), "match|keyword");
        assertHas(t.get(2), "case|keyword");
        assertHas(t.get(4), "match|null");
    }

    @Test
    void numbersAreNumbersPrefixAndSuffixIncluded() {
        assertHas(tokens("n = 0xFF + 0o17 + 0b1 + 3j").get(0), "0x|number", "0o|number", "0b|number", "j|number");
    }

    @Test
    void dunderNamesDecoratorsAndBuiltinNamedDefs() {
        var t = tokens("@property\n@staticmethod\ndef __init__(self): print(__name__)\ndef open(self): ...");
        assertHas(t.get(0), "property|annotation");
        assertHas(t.get(1), "staticmethod|annotation");
        assertHas(t.get(2), "__init__|function", "__name__|variable");
        var symbols = TextMateHighlighter.analyze("def __init__(self): ...\ndef open(self): ...\n", PYTHON)
                .symbols();
        assertEquals(
                List.of(
                        new TextMateHighlighter.Symbol(0, "__init__", "function"),
                        new TextMateHighlighter.Symbol(1, "open", "function")),
                symbols,
                "a method named like a builtin or a dunder is still in the outline");
    }

    @Test
    void anStrftimeFormatSpecIsASpecAndSlicesAreNot() {
        var t = tokens("f\"{when:%Y-%m-%d} {a[1:2]} {x!r:>10}\"\nSENTINEL = 1");
        assertHas(t.get(0), ":%Y-%m-%d|keyword", "1|number", "2|number", ":>10|keyword");
        assertHas(t.get(1), "SENTINEL|null");
    }

    @Test
    void modernStringsDoNotBleed() {
        var t = tokens(String.join(
                "\n",
                "x = f\"{d[\"k\"]}\"", // PEP 701 nested quotes
                "y = f\"{\"\\n\".join(a)}\"",
                "bad = \"unterminated",
                "bad2 = f\"unterminated {x",
                "s = \"\"\"a",
                "b\"\"\"",
                "SENTINEL = 1"));
        for (int i : new int[] {2, 3}) {
            assertTrue(t.get(i + 1).get(0).endsWith("|null"), "line " + (i + 1) + " is code again: " + t.get(i + 1));
        }
        assertHas(t.get(6), "SENTINEL|null", "1|number");
    }
}
