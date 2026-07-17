package com.editora.sshconfig;

import org.junit.jupiter.api.Test;

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
}
