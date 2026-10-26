package com.editora.report;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What {@link Redactor} takes out of a problem report — and the debugging detail it must leave in. */
class RedactorTest {

    private final Redactor unix = new Redactor("/home/jdoe", "jdoe");

    private String redact(String text) {
        return unix.redact(text).text();
    }

    @Test
    void homePathsBecomeTildeAndTheUserNameIsReplaced() {
        assertEquals("opened ~/work/app/Main.java as <user>", redact("opened /home/jdoe/work/app/Main.java as jdoe"));
        assertEquals("~", redact("/home/jdoe"));
        assertEquals("/home/jdoe2/x", redact("/home/jdoe2/x"), "another user's home is not this one");
        assertEquals("jdoeling", redact("jdoeling"), "only the whole name");
    }

    @Test
    void aWindowsHomeIsReplacedWithEitherSeparator() {
        Redactor windows = new Redactor("C:\\Users\\Jane", "Jane");
        assertEquals(
                "~\\proj\\a.txt and file:///~/proj/a.txt",
                windows.redact("C:\\Users\\Jane\\proj\\a.txt and file:///C:/Users/Jane/proj/a.txt")
                        .text());
    }

    @Test
    void aShortUserNameIsLeftAloneBecauseItIsAlsoAnOrdinaryWord() {
        Redactor shortName = new Redactor("/home/al", "al");
        assertEquals(
                "~/x — al fresco", shortName.redact("/home/al/x — al fresco").text());
    }

    @Test
    void credentialsInUrlsAreDroppedButTheHostIsKept() {
        assertEquals(
                "clone https://<redacted>@github.com/o/r.git failed",
                redact("clone https://me:s3cret@github.com/o/r.git failed"));
        assertEquals("ssh://git@example.com/r", redact("ssh://git@example.com/r"), "a user with no password stays");
    }

    @Test
    void authenticationHeadersAreRedactedEvenInsideALogLine() {
        assertEquals(
                "12:00:01.000  FINE  Http: Authorization: <redacted>",
                redact("12:00:01.000  FINE  Http: Authorization: Basic dXNlcjpwYXNz"));
        assertEquals("Cookie: <redacted>\nnext line", redact("Cookie: session=abc123; theme=dark\nnext line"));
        assertEquals("sent Bearer <redacted>", redact("sent Bearer abcdef.ghijkl.mnopqr"));
    }

    @Test
    void secretKeyValuePairsAndTokenShapesAreRedacted() {
        assertEquals("GET /api?access_token=<redacted>&page=2", redact("GET /api?access_token=abcd1234&page=2"));
        assertEquals("password: <redacted>", redact("password: hunter22"));
        assertEquals("\"apiKey\": \"<redacted>\"", redact("\"apiKey\": \"zzzz9999\""));
        assertEquals("token <redacted>", redact("token ghp_abcdefghijklmnopqrstuvwxyz0123456789"));
        assertEquals("key <redacted>", redact("key sk-ant-api03-abcdefghijklmnopqrstuvwxyz"));
        assertEquals("aws <redacted>", redact("aws AKIAABCDEFGHIJKLMNOP"));
        assertEquals("jwt <redacted>", redact("jwt eyJhbGciOiJI.eyJzdWIiOiIxMjM0.SflKxwRJSMeKKF2Q"));
        assertEquals(
                "<redacted private key>",
                redact("-----BEGIN OPENSSH PRIVATE KEY-----\nb3BlbnNzaA==\n-----END OPENSSH PRIVATE KEY-----"));
    }

    @Test
    void emailAddressesAreReplacedButAGitRemoteIsNot() {
        assertEquals("author <email> committed", redact("author jane.doe@example.org committed"));
        assertEquals("git@github.com:o/r.git", redact("git@github.com:o/r.git"));
    }

    @Test
    void ordinaryLogTextThatMerelyMentionsSecretsSurvives() {
        for (String line : new String[] {
            "unknown setting key: editor.tabSize",
            "Basic authentication is not supported",
            "token expired, signing in again",
            "LSP semantic tokens: 120 in 4 ms",
            "keymap=emacs",
            "java.lang.IllegalStateException: no password set"
        }) {
            Redactor.Result result = unix.redact(line);
            assertEquals(line, result.text());
            assertEquals(0, result.replaced(), line);
        }
    }

    @Test
    void theCountIsEveryReplacement() {
        Redactor.Result result = unix.redact("/home/jdoe/a /home/jdoe/b jane@example.org");
        assertEquals(3, result.replaced());
        assertTrue(result.text().contains("<email>"));
        assertFalse(result.text().contains("jdoe"));
    }

    @Test
    void nothingToRedact() {
        assertEquals(new Redactor.Result("", 0), unix.redact(""));
        assertEquals(new Redactor.Result("plain", 0), new Redactor(null, null).redact("plain"));
    }
}
