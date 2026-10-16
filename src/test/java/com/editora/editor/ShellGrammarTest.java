package com.editora.editor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.tm4e.core.grammar.IGrammar;
import org.eclipse.tm4e.core.grammar.IStateStack;
import org.eclipse.tm4e.core.grammar.IToken;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bundled shell grammar must give the rest of the file back after each common construct. A construct the
 * grammar opens and never closes colours everything below it as one string — the whole file, for one line of
 * ordinary bash. Each case here is a snippet followed by {@code echo SENTINEL}: that last line must tokenize
 * exactly as it does on its own (the command styled as a function, the grammar back at the top level).
 */
class ShellGrammarTest {

    private static final IGrammar SHELL = GrammarRegistry.shared().forFileName("x.sh");

    private record Line(IStateStack end, List<String> styles) {}

    /** Tokenizes {@code text} line by line and returns the last line's end state and per-token styles. */
    private static Line lastLine(String text) {
        IStateStack state = null;
        List<String> styles = new ArrayList<>();
        for (String line : text.split("\n")) {
            var result = SHELL.tokenizeLine(line + "\n", state, Duration.ofSeconds(5));
            state = result.getRuleStack();
            styles.clear();
            for (IToken t : result.getTokens()) {
                int start = Math.min(t.getStartIndex(), line.length());
                int end = Math.min(t.getEndIndex(), line.length());
                if (end > start && !line.substring(start, end).isBlank()) {
                    styles.add(line.substring(start, end) + "|" + TextMateHighlighter.styleForScopes(t.getScopes()));
                }
            }
        }
        return new Line(state, List.copyOf(styles));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                // A backslash-quoted here-doc delimiter: the delimiter is EOF, not \EOF.
                "cat <<\\EOF\nliteral $HOME\nEOF",
                "\tcat <<-\\EOF\n\tliteral\n\tEOF",
                // A left shift inside $(( )) is arithmetic, not a here-doc whose delimiter is `2))`.
                "echo $((a<<2))",
                "echo $((1 << 4))",
                "x=$(( y << 1 ))",
                "echo \"$(( 1 << 3 ))\"",
                // Constructs that already worked — kept so a grammar update cannot quietly break them.
                "cat <<EOF\nhello $USER\nEOF",
                "cat <<'EOF'\nno $x \"q\nEOF",
                "cat <<-\"EOF\"\n\tx\n\tEOF",
                "x=$(cat <<EOF\nit's\nEOF\n)",
                "case $x in a) echo one;; b) echo two;; esac",
                "echo $(( $(cmd) + 1 )) \"$(cmd \"$(other)\")\"",
                "echo $(( (a + b) / 2 ))",
                "for ((i = 0; i < 10; i++)); do echo $i; done",
                "echo \"${foo:-$(echo \"${bar:-baz}\")}\"",
                "[[ $x =~ ^[[:alpha:]]+$ ]] && echo ok",
            })
    void theLineAfterTheConstructIsBackAtTheTopLevel(String snippet) {
        Line alone = lastLine("echo SENTINEL");
        Line after = lastLine(snippet + "\necho SENTINEL");

        assertEquals(alone.styles(), after.styles(), "the line after `" + snippet + "` is styled as on its own");
        assertEquals(alone.end().getDepth(), after.end().getDepth(), "the grammar is back at the top level");
    }

    /** `$(( … ))` is arithmetic: its names are variables and its operators operators, not commands and words. */
    @Test
    void dollarDoubleParenIsArithmetic() {
        List<String> styles = lastLine("total=$(( count * 2 + RANDOM % 10 ))").styles();

        assertTrue(styles.contains("count|variable"), styles.toString());
        assertTrue(styles.contains("RANDOM|variable"), styles.toString());
        assertTrue(styles.contains("*|operator"), styles.toString());
        assertTrue(styles.contains("+|operator"), styles.toString());
        assertTrue(styles.contains("%|operator"), styles.toString());
        assertTrue(styles.contains("2|number"), styles.toString());
    }

    /** The body of a {@code <<\EOF} here-doc is literal text, and the line after its delimiter is code again. */
    @Test
    void backslashHereDocBodyIsAStringAndEndsAtItsDelimiter() {
        List<String> body = lastLine("cat <<\\END\nliteral text").styles();
        List<String> after = lastLine("cat <<\\END\nliteral text\nEND\nif true; then echo hi; fi")
                .styles();

        assertEquals(List.of("literal text|string"), body);
        assertTrue(after.contains("if|keyword") && after.contains("then|keyword"), after.toString());
    }
    /**
     * A command's bare words and options are arguments, not string literals: plain text (which also keeps spell
     * check off `pipefail` and package names). Quoted strings and here-doc bodies are still strings.
     */
    @Test
    void bareArgumentsAndOptionsArePlainButQuotedStringsAreStrings() {
        List<String> set = lastLine("set -euo pipefail").styles();
        List<String> apt = lastLine("apt-get install -y libssl-dev \"quoted text\" 'single'")
                .styles();
        List<String> forList = lastLine("for f in alpha beta; do :; done").styles();
        List<String> heredoc = lastLine("cat <<EOF\nbody words").styles();

        assertEquals(List.of("set|function", "-|null", "euo|null", "pipefail|null"), set);
        assertTrue(apt.containsAll(List.of("install|null", "y|null", "libssl-dev|null")), apt.toString());
        assertTrue(apt.contains("quoted text|string") && apt.contains("single|string"), apt.toString());
        assertTrue(forList.stream().noneMatch(t -> t.endsWith("|string")), forList.toString());
        assertEquals(List.of("body words|string"), heredoc);
    }
}
