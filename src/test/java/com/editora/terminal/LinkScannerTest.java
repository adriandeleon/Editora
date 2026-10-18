package com.editora.terminal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** What counts as a link under the pointer, and what does not. */
class LinkScannerTest {

    private static LinkMatch at(String text, String needle) {
        return LinkScanner.at(text, text.indexOf(needle));
    }

    @Test
    void findsAUrlUnderThePointer() {
        LinkMatch match = at("see https://example.com/a for more", "https");
        assertNotNull(match);
        assertEquals(LinkMatch.Kind.URL, match.kind());
        assertEquals("https://example.com/a", match.target());
    }

    @Test
    void theWholeTokenIsTheLinkWhereverInItYouPoint() {
        String line = "https://example.com/deep/path";
        assertEquals(line, LinkScanner.at(line, 0).target());
        assertEquals(line, LinkScanner.at(line, line.length() - 1).target());
        assertEquals(line, LinkScanner.at(line, 12).target());
    }

    @Test
    void aSentenceFullStopIsNotPartOfTheUrl() {
        assertEquals(
                "https://example.com/a",
                at("Go to https://example.com/a.", "https").target());
        assertEquals(
                "https://example.com",
                at("Try https://example.com, then stop", "https").target());
    }

    @Test
    void aBracketTheUrlOpenedIsKept() {
        // The Wikipedia case. Cutting at the first ')' produces a link that does not resolve, which
        // is worse than not linking it at all.
        assertEquals(
                "https://en.wikipedia.org/wiki/Terminal_(disambiguation)",
                at("see https://en.wikipedia.org/wiki/Terminal_(disambiguation)", "https")
                        .target());
    }

    @Test
    void aBracketTheUrlDidNotOpenIsDropped() {
        assertEquals(
                "https://example.com/a",
                at("(see https://example.com/a)", "https").target());
    }

    @Test
    void pointingAtTheTrimmedPunctuationIsNotPointingAtTheLink() {
        String line = "see https://example.com/a.";
        assertNull(LinkScanner.at(line, line.length() - 1));
    }

    @Test
    void onlyAllowedSchemesAreUrls() {
        // A scheme we do not allow is never a URL, which is what matters: nothing with one is ever
        // handed to the desktop, where it would reach a registered handler — a program on this
        // machine, launched by whatever a command happened to print.
        //
        // It does fall through to being a path candidate, and that is harmless by construction: a
        // candidate only becomes a link if it resolves to a file that exists, and what is opened is
        // then that resolved file, never the token. There is no file called "javascript:alert(1)".
        assertNotUrl(at("run javascript:alert(1) now", "javascript"));
        assertNotUrl(at("see data:text/html;base64,xx now", "data:"));
        assertNotUrl(at("open vscode://file/tmp/x now", "vscode"));

        assertEquals(
                LinkMatch.Kind.URL,
                at("mail me@example.com via mailto:me@example.com", "mailto").kind());
    }

    private static void assertNotUrl(LinkMatch match) {
        if (match != null) assertEquals(LinkMatch.Kind.PATH, match.kind());
    }

    @Test
    void aSchemelessTokenIsOfferedAsAPathCandidate() {
        // No shape test on purpose: whether it names a file is the filesystem's answer, not a
        // pattern's. That is what makes a bare name in `ls` output clickable.
        LinkMatch match = at("README.md  src  build", "README.md");
        assertNotNull(match);
        assertEquals(LinkMatch.Kind.PATH, match.kind());
        assertEquals("README.md", match.target());
        assertEquals(0, match.line());
    }

    @Test
    void aStackTraceFrameCarriesItsLineAndColumn() {
        LinkMatch match = at("at src/main/java/Foo.java:42:7 in", "src/");
        assertNotNull(match);
        assertEquals("src/main/java/Foo.java", match.target());
        assertEquals(42, match.line());
        assertEquals(7, match.column());
        // The whole thing is underlined: underlining only the file part of what reads as one word
        // looks like a rendering fault.
        assertEquals("src/main/java/Foo.java:42:7", match.text());
    }

    @Test
    void aSingleLineNumberIsALineAndNotAColumn() {
        LinkMatch match = at("Foo.java:42", "Foo");
        assertEquals("Foo.java", match.target());
        assertEquals(42, match.line());
        assertEquals(0, match.column());
    }

    @Test
    void aWindowsPathKeepsItsDriveLetter() {
        // Parsed from the end, so the drive's colon is never mistaken for a position separator —
        // and "c" is not an allowed scheme, so it is not read as a URL either.
        LinkMatch match = at("C:\\src\\Foo.cs:42 failed", "C:");
        assertNotNull(match);
        assertEquals(LinkMatch.Kind.PATH, match.kind());
        assertEquals("C:\\src\\Foo.cs", match.target());
        assertEquals(42, match.line());
    }

    @Test
    void aLongRunOfDigitsIsNotALineNumber() {
        // A digest or an id ending a token is likelier than line ten million.
        LinkMatch match = at("blob:1234567890123 here", "blob");
        assertEquals("blob:1234567890123", match.target());
        assertEquals(0, match.line());
    }

    @Test
    void aBarePositionIsNotSplitIntoAnEmptyFile() {
        // ":42" names no file, so the position is not split off — the whole token stays the
        // candidate and the filesystem declines it, rather than a link to "" at line 42.
        LinkMatch match = at(":42 alone", ":42");
        assertEquals(":42", match.target());
        assertEquals(0, match.line());
    }

    @Test
    void whitespaceAndOutOfRangeGiveNothing() {
        assertNull(LinkScanner.at("a b", 1));
        assertNull(LinkScanner.at("abc", -1));
        assertNull(LinkScanner.at("abc", 3));
        assertNull(LinkScanner.at(null, 0));
        assertNull(LinkScanner.at("", 0));
    }

    @Test
    void quotesAroundALinkAreNotPartOfIt() {
        assertEquals(
                "https://example.com/a",
                at("\"https://example.com/a\"", "https").target());
        assertEquals("Foo.java", at("'Foo.java'", "Foo").target());
    }

    @Test
    void anAngleBracketedUrlLosesTheBrackets() {
        assertEquals(
                "https://example.com/a", at("<https://example.com/a>", "https").target());
    }
}
