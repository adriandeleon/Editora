package com.editora.http;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for the "Copy as cURL" export. */
class CurlExportTest {

    /**
     * Splits a command the way a POSIX shell would for the subset the export uses: single-quoted words,
     * unquoted words, and backslash-newline continuations. A {@code $} outside quotes is marked, so a
     * command substitution that escaped the quoting is visible in the words.
     */
    static List<String> shellWords(String cmd) {
        List<String> words = new ArrayList<>();
        StringBuilder cur = null;
        int i = 0;
        while (i < cmd.length()) {
            char c = cmd.charAt(i);
            if (c == '\\' && i + 1 < cmd.length() && cmd.charAt(i + 1) == '\n') {
                i += 2;
                continue;
            }
            if (c == ' ' || c == '\n') {
                if (cur != null) {
                    words.add(cur.toString());
                    cur = null;
                }
                i++;
                continue;
            }
            if (cur == null) {
                cur = new StringBuilder();
            }
            if (c == '\'') {
                int end = cmd.indexOf('\'', i + 1);
                cur.append(cmd, i + 1, end);
                i = end + 1;
            } else if (c == '\\' && i + 1 < cmd.length()) {
                cur.append(cmd.charAt(i + 1));
                i += 2;
            } else {
                cur.append(c == '$' ? "<UNQUOTED$>" : String.valueOf(c));
                i++;
            }
        }
        if (cur != null) {
            words.add(cur.toString());
        }
        return words;
    }

    @Test
    void getOmitsMethodFlag() {
        String curl = CurlExport.toCurl("GET", "https://api.test/x", List.of(), null, null);
        assertTrue(curl.startsWith("curl -L 'https://api.test/x'"), curl); // follows redirects, as a run does
        assertFalse(curl.contains("-X"), curl);
    }

    @Test
    void postIncludesMethodHeadersAndBody() {
        String curl = CurlExport.toCurl(
                "POST",
                "https://api.test/x",
                List.<String[]>of(new String[] {"Content-Type", "application/json"}),
                "{\"k\":\"v\"}",
                null);
        assertFalse(curl.contains("-X"), curl); // curl infers POST from the body, and follows a 302 as the run did
        assertTrue(curl.contains("-H 'Content-Type: application/json'"), curl);
        assertTrue(curl.contains("--data-raw '{\"k\":\"v\"}'"), curl);
    }

    @Test
    void singleQuotesAreEscaped() {
        String curl = CurlExport.toCurl("POST", "https://api.test/x", List.of(), "it's", null);
        assertTrue(curl.contains("it'\\''s"), curl);
    }

    @Test
    void aQuoteInTheUrlCannotEndTheQuotingAndRunACommand() {
        String url = "http://127.0.0.1:1/dump?q=a'$(touch$IFS/tmp/pwned)'b";
        String curl = CurlExport.toCurl("GET", url, List.of(), null, null);
        assertEquals(List.of("curl", "-L", url), shellWords(curl), curl);
    }

    @Test
    void headerNamesAndValuesAreQuotedAsOneWord() {
        String curl = CurlExport.toCurl(
                "GET", "http://h/", List.<String[]>of(new String[] {"X-It's", "v'$(id)'"}), null, null);
        assertEquals(List.of("curl", "-L", "http://h/", "-H", "X-It's: v'$(id)'"), shellWords(curl), curl);
    }

    @Test
    void aBodyStartingWithAtIsSentAsTextNotReadFromAFile() {
        String curl = CurlExport.toCurl("POST", "http://h/", List.of(), "@/etc/passwd", null);
        List<String> words = shellWords(curl);
        assertEquals(List.of("--data-raw", "@/etc/passwd"), words.subList(words.size() - 2, words.size()), curl);
    }

    @Test
    void aFileBodyIsUploadedFromTheFileAndRedirectsAreFollowed() {
        HttpExchange.Details d =
                new HttpExchange.Details("/work/img.bin", List.of(), null, null, true, null, null, false);
        String curl = CurlExport.toCurl(
                "POST",
                "http://h/up",
                List.<String[]>of(new String[] {"Content-Type", "application/octet-stream"}),
                "(binary body from ./img.bin, 5 bytes)",
                d);
        List<String> words = shellWords(curl);
        assertTrue(words.contains("-L"), curl);
        assertEquals(List.of("--data-binary", "@/work/img.bin"), words.subList(words.size() - 2, words.size()), curl);
        assertFalse(curl.contains("binary body from"), curl);
    }

    @Test
    void noRedirectLeavesOutFollow() {
        HttpExchange.Details d = new HttpExchange.Details(null, List.of(), null, null, false, null, null, false);
        assertFalse(shellWords(CurlExport.toCurl("GET", "http://h/", List.of(), null, d))
                .contains("-L"));
    }

    @Test
    void aMultipartFormBecomesFormFieldsWithoutTheFixedBoundaryHeader() {
        HttpExchange.Details d = new HttpExchange.Details(
                null,
                List.of(
                        new HttpExchange.FormPart("title", "@not-a-file;type=x", null, null, null),
                        new HttpExchange.FormPart("upload", null, "/work/a b.png", "a.png", "image/png")),
                null,
                null,
                true,
                null,
                null,
                false);
        String curl = CurlExport.toCurl(
                "POST",
                "http://h/form",
                List.<String[]>of(
                        new String[] {"Content-Type", "multipart/form-data; boundary=XX"},
                        new String[] {"Accept", "*/*"}),
                "(multipart/form-data, 120 bytes)",
                d);
        List<String> words = shellWords(curl);
        assertFalse(curl.contains("boundary=XX"), curl);
        assertTrue(words.contains("Accept: */*"), curl);
        assertEquals("title=@not-a-file;type=x", words.get(words.indexOf("--form-string") + 1), curl);
        assertEquals(
                "upload=@\"/work/a b.png\";filename=\"a.png\";type=image/png",
                words.get(words.indexOf("-F") + 1),
                curl);
        assertFalse(curl.contains("multipart/form-data, 120 bytes"), curl);
    }

    @Test
    void theDigestShorthandBecomesCurlDigestAuth() {
        HttpExchange.Details d =
                new HttpExchange.Details(null, List.of(), "ada", "it's secret", true, null, null, false);
        List<String> words = shellWords(CurlExport.toCurl("GET", "http://h/", List.of(), null, d));
        int at = words.indexOf("--digest");
        assertEquals(List.of("--digest", "-u", "ada:it's secret"), words.subList(at, at + 3));
    }

    @Test
    void headUsesTheHeadFlagAndAVersionIsKept() {
        HttpExchange.Details d = new HttpExchange.Details(null, List.of(), null, null, false, "HTTP/1.1", null, false);
        assertEquals(
                List.of("curl", "-I", "--http1.1", "http://h/"),
                shellWords(CurlExport.toCurl("HEAD", "http://h/", List.of(), null, d)));
    }

    @Test
    void anUnusualMethodIsQuoted() {
        assertEquals(
                List.of("curl", "-X", "PROP;FIND", "-L", "http://h/"),
                shellWords(CurlExport.toCurl("PROP;FIND", "http://h/", List.of(), null, null)));
    }

    @Test
    void aDigestUserWithoutItsPasswordMakesCurlAskForIt() {
        HttpExchange.Details d = new HttpExchange.Details(null, List.of(), "ann", null, true, null, null, false);
        List<String> words = shellWords(CurlExport.toCurl("GET", "http://h/", List.of(), null, d));
        assertEquals(List.of("--digest", "-u", "ann"), words.subList(words.size() - 3, words.size()));
    }

    @Test
    void streamedBodySummariesAreRecognised() {
        assertTrue(HttpExchange.isBodyPlaceholder("(multipart/form-data, 3080 bytes)"));
        assertTrue(HttpExchange.isBodyPlaceholder("(body from ./big.bin, 1.9 MB)"));
        assertTrue(HttpExchange.isBodyPlaceholder("(binary body from ./a.bin, 5 bytes)"));
        assertFalse(HttpExchange.isBodyPlaceholder("(a body in parentheses)"));
        assertFalse(HttpExchange.isBodyPlaceholder("{\"k\":1}"));
    }

    @Test
    void theVersionTheRequestLineAskedForIsKept() {
        assertEquals("--http1.0", CurlExport.versionFlag("HTTP/1.0"));
        assertEquals("--http1.1", CurlExport.versionFlag("http/1.1"));
        assertEquals("--http2", CurlExport.versionFlag("HTTP/2"));
        assertEquals("--http2-prior-knowledge", CurlExport.versionFlag("HTTP/2 (Prior Knowledge)"));
        assertEquals(null, CurlExport.versionFlag(null));
    }
}
