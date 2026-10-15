package com.editora.ui;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.editora.http.HttpExchange;
import com.editora.http.HttpFile;
import com.editora.http.HttpResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** What an untitled request cannot send (C12), the resolved "Running …" label (C10), Run All's merge. */
class HttpScratchTest {

    private static final ZonedDateTime NOW = ZonedDateTime.of(2026, 10, 15, 12, 0, 0, 0, ZoneOffset.UTC);

    private static HttpFile.Request block(String text) {
        return HttpFile.parse(text).get(0);
    }

    private static List<String> needs(String text, Map<String, List<String>> defs) {
        HttpFile.Request b = block(text);
        return HttpScratch.needsFolder(b, HttpFile.parseRequest(b), defs);
    }

    @Test
    void bodyFilesMultipartFilesResponseFilesAndDotenvNeedTheFilesFolder() {
        assertEquals(List.of(), needs("POST http://x.test/\nContent-Type: text/plain\n\nhello\n", Map.of()));
        assertEquals(List.of("< ./body.json"), needs("POST http://x.test/\n\n< ./body.json\n", Map.of()));
        assertEquals(List.of("<@ ./t.json"), needs("POST http://x.test/\n\n<@ ./t.json\n", Map.of()));
        assertEquals(List.of(">> out.json"), needs("GET http://x.test/\n\n>> out.json\n", Map.of()));
        assertEquals(
                List.of("< ./logo.png"),
                needs(
                        "POST http://x.test/\nContent-Type: multipart/form-data; boundary=B\n\n--B\n"
                                + "Content-Disposition: form-data; name=\"f\"; filename=\"logo.png\"\n\n< ./logo.png\n--B--\n",
                        Map.of()));
        assertEquals(List.of("$dotenv.KEY"), needs("GET http://x.test/?k={{$dotenv.KEY}}\n", Map.of()));
        assertEquals(
                List.of("$dotenv.KEY"),
                needs("GET http://x.test/?k={{key}}\n", Map.of("key", List.of("{{$dotenv.KEY}}"))),
                "behind a variable too");
        assertEquals(List.of(), needs("GET http://x.test/?k={{$processEnv.HOME}}\n", Map.of()), "not a folder read");
    }

    @Test
    void theRunningLabelFillsInVariablesButNotDynamicOrUnresolvedOnes() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("host", "http://127.0.0.1:8080");
        env.put("api", "{{host}}/api");
        HttpFile.Parsed p = HttpFile.parseRequest(
                block(
                        "@id = 7\n@nonce = {{$uuid}}\nGET {{api}}/users/{{id}}?n={{nonce}}&r={{$random.integer}}&x={{missing}}\n"));
        assertEquals(
                "GET http://127.0.0.1:8080/api/users/7?n={{nonce}}&r={{$random.integer}}&x={{missing}}",
                HttpScratch.label(p, env, null, null, NOW));
        HttpFile.Parsed plain = HttpFile.parseRequest(block("POST http://x.test/a\n"));
        assertEquals("POST http://x.test/a", HttpScratch.label(plain, Map.of(), null, null, NOW));
    }

    @Test
    void aRunAllsResultsComeBackInFileOrder() {
        HttpExchange a = ex("a");
        HttpExchange c = ex("c");
        HttpExchange skippedB = ex("b-not-sent");
        Map<Integer, HttpExchange> skipped = Map.of(1, skippedB);
        assertEquals(List.of(a, skippedB, c), HttpClientCoordinator.merged(3, List.of(0, 2), List.of(a, c), skipped));
        assertEquals(
                List.of(a, skippedB),
                HttpClientCoordinator.merged(3, List.of(0, 2), List.of(a), skipped),
                "a cancelled run stops where it stopped");
    }

    private static HttpExchange ex(String label) {
        return new HttpExchange(label, "GET", "http://x.test/", List.of(), "", HttpResult.failure("x", List.of()));
    }
}
