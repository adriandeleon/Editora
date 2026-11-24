package com.editora.sshconfig;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link SshPattern} — OpenSSH {@code Host} pattern matching (rules verified against {@code ssh -G}). */
class SshPatternTest {

    @Test
    void starMatchesAnyRunIncludingDots() {
        assertTrue(SshPattern.matches("*", "web.example.com"));
        assertTrue(SshPattern.matches("*.example.com", "web.example.com"));
        assertTrue(SshPattern.matches("*.example.com", "a.b.example.com"), "* spans dots (ssh globs aren't paths)");
        assertFalse(SshPattern.matches("*.example.com", "example.com"));
    }

    @Test
    void questionMatchesOneChar() {
        assertTrue(SshPattern.matches("host?", "host1"));
        assertFalse(SshPattern.matches("host?", "host"));
        assertFalse(SshPattern.matches("host?", "host12"));
    }

    @Test
    void matchingIsCaseSensitive() {
        // Confirmed against ssh -G: `Host MyServer` does NOT match host `myserver`.
        assertFalse(SshPattern.matches("MyServer", "myserver"));
        assertTrue(SshPattern.matches("MyServer", "MyServer"));
    }

    @Test
    void multiplePatternsPerLineMatchIfAnyPositiveMatches() {
        assertTrue(SshPattern.matches("web prod", "web"));
        assertTrue(SshPattern.matches("web prod", "prod"));
        assertFalse(SshPattern.matches("web prod", "staging"));
    }

    @Test
    void aMatchedNegationDisqualifiesTheWholeLine() {
        // "*.example.com !secret.example.com" matches everything under example.com except secret.
        assertTrue(SshPattern.matches("*.example.com !secret.example.com", "web.example.com"));
        assertFalse(SshPattern.matches("*.example.com !secret.example.com", "secret.example.com"));
    }

    @Test
    void aNegationAloneNeverMatches() {
        assertFalse(SshPattern.matches("!badhost", "goodhost"), "no positive pattern ⇒ no match");
    }

    @Test
    void nullsAndBlanks() {
        assertFalse(SshPattern.matches(null, "h"));
        assertFalse(SshPattern.matches("*", null));
        assertFalse(SshPattern.matches("", "h"));
    }

    @Test
    void everythingButStarAndQuestionIsLiteral() {
        assertTrue(SshPattern.matches("a.b", "a.b"));
        assertFalse(SshPattern.matches("a.b", "axb"), "a dot is a dot, not any character");
        assertTrue(SshPattern.matches("web+1", "web+1"));
        assertFalse(SshPattern.matches("web+1", "webb1"));
        assertTrue(SshPattern.matches("[x]$", "[x]$"));
        assertFalse(SshPattern.matches("[x]", "x"));
        assertTrue(SshPattern.matches("\\Qa\\E", "\\Qa\\E"));
    }

    @Test
    void starsBacktrackAndCollapse() {
        assertTrue(SshPattern.matches("*a*b", "xaxb"));
        assertTrue(SshPattern.matches("*a*b", "aab"));
        assertFalse(SshPattern.matches("*a*b", "xaxbx"));
        assertTrue(SshPattern.matches("a**", "a"));
        assertTrue(SshPattern.matches("*?", "x"));
        assertFalse(SshPattern.matches("*?", ""));
        assertTrue(SshPattern.matches("**.example.com", "a.b.example.com"));
        // The regex backtracked exponentially on this one (it never returned).
        assertFalse(SshPattern.matches("*" + "a*".repeat(30) + "b", "a".repeat(60)), "no exponential walk");
    }

    @Test
    void patternsAreSeparatedByAnyWhitespace() {
        assertTrue(SshPattern.matches(" \tweb\t \fprod\r ", "prod"));
        assertTrue(SshPattern.matches("! web", "web"), "a lone ! is no pattern; web is positive");
    }

    /**
     * The preview sums up each host with what every block above it sets, so a config of N hosts costs N squared
     * matches, five times over (one walk of the file per value it names). With a regex compiled per match the
     * 1,000 hosts of StructuredPreviewListsFxTest held the FX thread past its 20 s limit on macOS CI, and these
     * 3,000 took 68 s here; one walk per host with a plain glob walk takes 0.15 s.
     */
    @Test
    void summingUpThreeThousandHostsIsQuick() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 3_000; i++) {
            text.append("Host h").append(i).append("\n  HostName h").append(i).append(".example.com\n");
        }
        List<SshConfig.Block> blocks = SshConfig.parse(text.toString());
        long t0 = System.nanoTime();
        int summed = 0;
        for (SshConfig.Block b : blocks) {
            summed += SshConfigDescribe.summary(blocks, b).isEmpty() ? 0 : 1;
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertEquals(blocks.size(), summed);
        assertEquals("Connects to h2999.example.com", SshConfigDescribe.summary(blocks, blocks.getLast()));
        assertTrue(ms < 5_000, "3,000 host summaries took " + ms + " ms");
    }
}
