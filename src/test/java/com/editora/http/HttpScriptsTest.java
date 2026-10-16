package com.editora.http;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link HttpScripts}: Node's guard and environment, the shim's input and the result line it writes. */
class HttpScriptsTest {

    @Test
    void theGuardFollowsTheNodeVersion() {
        assertArrayEquals(new int[] {24, 18, 1}, HttpScripts.parseVersion("v24.18.1\n"));
        assertNull(HttpScripts.parseVersion("command not found"));
        assertEquals(HttpScripts.Guard.PERMISSION, HttpScripts.guardFor(new int[] {24, 0, 0}));
        assertEquals(HttpScripts.Guard.PERMISSION, HttpScripts.guardFor(new int[] {22, 13, 0}));
        assertEquals(HttpScripts.Guard.EXPERIMENTAL_PERMISSION, HttpScripts.guardFor(new int[] {22, 12, 0}));
        assertEquals(HttpScripts.Guard.EXPERIMENTAL_PERMISSION, HttpScripts.guardFor(new int[] {20, 0, 0}));
        assertEquals(HttpScripts.Guard.NONE, HttpScripts.guardFor(new int[] {18, 19, 0}));
        assertEquals(HttpScripts.Guard.NONE, HttpScripts.guardFor(null));
        Path shim = Path.of("/cache/http-scripts/shim.js");
        assertEquals(
                List.of("--permission", "--allow-fs-read=" + shim.toAbsolutePath()),
                HttpScripts.flags(HttpScripts.Guard.PERMISSION, shim));
        assertEquals(
                "--experimental-permission",
                HttpScripts.flags(HttpScripts.Guard.EXPERIMENTAL_PERMISSION, shim)
                        .get(0));
        assertTrue(HttpScripts.flags(HttpScripts.Guard.NONE, shim).isEmpty());
    }

    @Test
    void nodeGetsOnlyAMinimalEnvironment() {
        Map<String, String> inherited = new LinkedHashMap<>();
        inherited.put("PATH", "/usr/bin");
        inherited.put("HOME", "/home/u");
        inherited.put("GITHUB_TOKEN", "ghp_secret");
        inherited.put("NODE_OPTIONS", "--require /tmp/evil.js");
        inherited.put("LANG", "en_US.UTF-8");
        inherited.put("TZ", "Europe/Paris");
        Map<String, String> env = HttpScripts.environment(inherited, "/opt/node/bin:/usr/bin", false);
        assertEquals(Map.of("PATH", "/opt/node/bin:/usr/bin", "LANG", "en_US.UTF-8", "TZ", "Europe/Paris"), env);

        Map<String, String> win = new LinkedHashMap<>();
        win.put("Path", "C:\\Windows");
        win.put("SYSTEMROOT", "C:\\Windows");
        win.put("USERPROFILE", "C:\\Users\\u");
        win.put("AWS_SECRET_ACCESS_KEY", "x");
        Map<String, String> w = HttpScripts.environment(win, "C:\\node;C:\\Windows", true);
        assertEquals(Map.of("SYSTEMROOT", "C:\\Windows", "Path", "C:\\node;C:\\Windows"), w);
    }

    @Test
    void theInputCarriesTheExchangeWithSecretsMasked() throws Exception {
        HttpFile.Parsed request = HttpFile.parseRequest(HttpFile.parse(
                        "@id = 7\nPOST http://h/items/{{id}}\nAuthorization: Bearer {{$processEnv TOKEN}}\n\n{\"a\":1}\n")
                .get(0));
        HttpResult r = HttpFixtures.text(
                201,
                List.<String[]>of(new String[] {"Content-Type", "application/json; charset=UTF-8"}),
                "{\"ok\":true}",
                "application/json; charset=UTF-8",
                5,
                11,
                null);
        HttpExchange ex = new HttpExchange(
                        "POST http://h/items/7",
                        "POST",
                        "http://h/items/7?k=s3cret-value",
                        List.<String[]>of(new String[] {"Authorization", "Bearer s3cret-value"}),
                        "{\"a\":1}",
                        r,
                        null)
                .withResult(r);
        String json = HttpScripts.input(
                "N:",
                new HttpScripts.Script("client.log(1)", "/p/a.http", 4, 4),
                request,
                ex,
                Map.of("host", "http://h"),
                Map.of("token", "t"),
                List.of("s3cret-value"));
        JsonNode in = new ObjectMapper().readTree(json);
        assertEquals("N:", in.path("nonce").asText());
        assertEquals("/p/a.http", in.path("script").path("filename").asText());
        assertEquals(4, in.path("script").path("line").asInt());
        assertEquals(201, in.path("response").path("status").asInt());
        assertEquals(
                "application/json",
                in.path("response").path("contentType").path("mimeType").asText());
        assertEquals(
                "utf-8", in.path("response").path("contentType").path("charset").asText());
        assertEquals(
                "http://h/items/7?k=" + SecretMask.MASK,
                in.path("request").path("url").asText());
        assertEquals("http://h/items/{{id}}", in.path("request").path("urlRaw").asText());
        assertEquals(
                "Bearer " + SecretMask.MASK,
                in.path("request").path("headers").path(0).path(1).asText());
        assertEquals(
                "Bearer {{$processEnv TOKEN}}",
                in.path("request").path("headersRaw").path(0).path(1).asText());
        assertEquals("7", in.path("variables").path("id").asText());
        assertEquals("http://h", in.path("environment").path("host").asText());
        assertEquals("t", in.path("globals").path("token").asText());
        assertTrue(!json.contains("s3cret-value"), json);
    }

    @Test
    void theResultLineIsReadAfterWhateverElseWasPrinted() {
        String out = "noise\nN:{\"tests\":[{\"name\":\"a\",\"passed\":true,\"message\":\"\"},"
                + "{\"name\":\"b\",\"passed\":false,\"message\":\"no\"}],\"log\":[\"hi\"],"
                + "\"globals\":[[\"set\",\"t\",\"1\"],[\"clear\",\"u\"],[\"clearAll\"],[\"bogus\"],[\"set\",\"\",\"x\"]],"
                + "\"error\":\"Error: x\\n    at /p/a.http:5:3\"}\n";
        HttpScripts.Output o = HttpScripts.parseOutput(out, "N:");
        assertEquals(2, o.tests().size());
        assertTrue(o.tests().get(0).passed());
        assertEquals("no", o.tests().get(1).message());
        assertEquals(List.of("hi"), o.log());
        assertEquals(3, o.changes().size());
        assertEquals("t", o.changes().get(0).name());
        assertEquals(ResponseScripts.GlobalChange.CLEAR_ALL, o.changes().get(2).kind());
        assertTrue(o.error().contains("/p/a.http:5:3"));
        assertNull(HttpScripts.parseOutput("no result here", "N:"));
        assertNull(HttpScripts.parseOutput("N:not json", "N:"));
    }
}
