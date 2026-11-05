package com.editora.editor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.editora.editor.highlight.GrammarRegistry;
import org.eclipse.tm4e.core.grammar.IGrammar;
import org.eclipse.tm4e.core.grammar.IStateStack;
import org.eclipse.tm4e.core.grammar.IToken;
import org.eclipse.tm4e.core.grammar.ITokenizeLineResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * A long run of letters with no spaces colours in linear time in the JavaScript and TypeScript grammars.
 *
 * <p>Upstream, about thirty of their regexes start with an identifier followed by a lookahead, and the
 * regex engine tries each one from every position inside a long word, running to the end of the word
 * each time: 2,000 letters in a string or a statement took 0.2–0.5 s to colour, 10,000 letters 7–8 s.
 * {@code scripts/GuardTsGrammars.java} adds a word-start guard to those regexes (see NOTICE); a fresh
 * copy of either grammar without it fails here. The 10,000-letter lines below now take a few
 * milliseconds each; the budget is generous so a loaded CI machine cannot trip it.
 */
class BundledGrammarsLongWordTest {

    private static final Duration BUDGET = Duration.ofSeconds(2);

    /** Lines before the measured one (state to set up), then the measured line; X is the long word. */
    private static final List<String[]> CASES = List.of(
            new String[] {"", "y = X;"},
            new String[] {"", "const s = \"X\";"},
            new String[] {"", "const s = 'X';"},
            new String[] {"", "f(); // X"},
            new String[] {"", "`${X}`"},
            new String[] {"", "const re = /X/;"},
            new String[] {"", "let a: X = 1;"},
            new String[] {"", "X.y = function () {};"},
            new String[] {"", "a instanceof X"},
            new String[] {"class A {", "  s = \"X\";"},
            new String[] {"class A {", "  #X?: string;"},
            new String[] {"const o = {", "  a: \"X\","},
            new String[] {"interface I {", "  a: \"X\";"},
            new String[] {"type T = {", "  X: string;"});

    private static final List<String[]> JSX_CASES = List.of(
            new String[] {"const e = (\n  <div>", "    <p title=\"X\">a</p>"},
            new String[] {"const e = (\n  <div>", "    <p data-X=\"1\">a</p>"});

    @Test
    void typeScriptColoursALongWordInLinearTime() {
        assertEquals(List.of(), slowLines("a.ts", CASES));
    }

    @Test
    void javaScriptColoursALongWordInLinearTime() {
        List<String[]> cases = new ArrayList<>(CASES);
        cases.addAll(JSX_CASES);
        assertEquals(List.of(), slowLines("a.js", cases)); // the TypeScriptReact grammar, as .jsx and .tsx
    }

    private static List<String> slowLines(String fileName, List<String[]> cases) {
        IGrammar grammar = GrammarRegistry.shared().forFileName(fileName);
        assertNotNull(grammar, fileName);
        String word = "x".repeat(10_000);
        List<String> slow = new ArrayList<>();
        synchronized (grammar) { // shared grammar instances are not thread-safe (see TextMateHighlighter)
            for (String[] c : cases) {
                IStateStack state = null;
                if (!c[0].isEmpty()) {
                    for (String before : c[0].split("\n")) {
                        state = grammar.tokenizeLine(before + "\n", state, BUDGET)
                                .getRuleStack();
                    }
                }
                String line = c[1].replace("X", word) + "\n";
                long started = System.nanoTime();
                ITokenizeLineResult<IToken[]> result = grammar.tokenizeLine(line, state, BUDGET);
                long millis = (System.nanoTime() - started) / 1_000_000;
                if (result.isStoppedEarly()) {
                    slow.add(fileName + ": `" + c[1] + "` not done in " + millis + " ms");
                }
            }
        }
        return slow;
    }
}
