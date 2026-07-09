package com.editora.ui;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure tests for the AI Agent chat's light shell-command tokenizer. */
class AgentCommandHighlighterTest {

    private static List<AgentCommandHighlighter.Span> spans(String s) {
        return AgentCommandHighlighter.spans(s);
    }

    /** The concatenated span texts must always reconstruct the input exactly (no dropped/added chars). */
    private static void assertRoundTrips(String s) {
        StringBuilder sb = new StringBuilder();
        for (AgentCommandHighlighter.Span span : spans(s)) {
            sb.append(span.text());
        }
        assertEquals(s, sb.toString());
    }

    /** The style class of the first span whose text equals {@code token} (may be {@code null} = default
     *  foreground); {@code "<absent>"} when no span has that exact text. */
    private static String classOf(String command, String token) {
        for (AgentCommandHighlighter.Span sp : spans(command)) {
            if (sp.text().equals(token)) {
                return sp.styleClass();
            }
        }
        return "<absent>";
    }

    @Test
    void emptyOrNullYieldsNoSpans() {
        assertTrue(spans("").isEmpty());
        assertTrue(spans(null).isEmpty());
    }

    @Test
    void commandWordIsFunction() {
        assertEquals("function", classOf("grep -n plugins ~/.bashrc", "grep"));
    }

    @Test
    void flagsAreConstant() {
        assertEquals("constant", classOf("grep -n plugins ~/.bashrc", "-n"));
        assertEquals("constant", classOf("ls --all", "--all"));
    }

    @Test
    void argumentsAndPathsStayPlain() {
        assertEquals(null, classOf("grep -n plugins ~/.bashrc", "plugins"));
        assertEquals(null, classOf("grep -n plugins ~/.bashrc", "~/.bashrc"));
    }

    @Test
    void quotedStringsAreStrings() {
        assertEquals("string", classOf("git commit -m \"a message\"", "\"a message\""));
        assertEquals("string", classOf("echo 'hi there'", "'hi there'"));
    }

    @Test
    void operatorsAndPipelineRestartCommandColor() {
        // After a pipe, the next word is a new command → function again.
        List<AgentCommandHighlighter.Span> s = spans("cat f | grep x");
        assertEquals("function", classOf("cat f | grep x", "cat"));
        assertEquals("operator", classOf("cat f | grep x", "|"));
        assertEquals("function", classOf("cat f | grep x", "grep"));
        assertRoundTrips("cat f | grep x");
    }

    @Test
    void trailingCommentIsComment() {
        assertEquals(
                "# note",
                spans("make all # note").stream()
                        .reduce((a, b) -> b)
                        .map(AgentCommandHighlighter.Span::text)
                        .orElse(""));
        assertEquals("comment", classOf("make all # note", "# note"));
    }

    @Test
    void hashInsideAWordIsNotAComment() {
        // A '#' not at a token boundary stays part of the word (e.g. a fragment/anchor).
        assertRoundTrips("curl http://x/a#frag");
        assertEquals("<absent>", classOf("curl http://x/a#frag", "#frag"));
    }

    @Test
    void variablesAreConstant() {
        assertEquals("constant", classOf("echo $HOME", "$HOME"));
    }

    @Test
    void roundTripsPreserveWhitespaceAndOperators() {
        assertRoundTrips("bash -n /home/adl/.dotfiles/shell/functions.sh && echo \"Syntax OK\"");
        assertRoundTrips("  leading   spaces  ");
    }
}
