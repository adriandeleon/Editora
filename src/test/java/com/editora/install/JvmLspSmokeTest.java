package com.editora.install;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The post-install request a JVM language server must answer ({@link JvmLspSmoke}): its framing and verdict
 * are pure; the exchange itself is run against a stand-in server ({@code FakeStdioServer.java}, through the
 * JDK's source launcher) that answers, fails, hangs, or dies — the four outcomes the install must tell apart.
 */
class JvmLspSmokeTest {

    // --- pure: framing ------------------------------------------------------------------------------------

    @Test
    void framesCarryTheBodyLengthInBytesNotCharacters() {
        byte[] framed = JvmLspSmoke.frame("{\"é\":1}");
        String text = new String(framed, StandardCharsets.UTF_8);
        assertTrue(text.startsWith("Content-Length: 8\r\n\r\n{"), text);
    }

    @Test
    void readFrameRoundTripsAndSkipsPaddingBetweenMessages() throws IOException {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        stream.writeBytes(JvmLspSmoke.frame("{\"id\":1}"));
        stream.writeBytes("\r\n".getBytes(StandardCharsets.US_ASCII));
        stream.writeBytes(
                ("Content-Type: application/vscode-jsonrpc; charset=utf-8\r\nContent-Length: 10\r\n\r\n{\"id\":22}\n")
                        .getBytes(StandardCharsets.UTF_8));
        var in = new ByteArrayInputStream(stream.toByteArray());

        assertEquals("{\"id\":1}", JvmLspSmoke.readFrame(in));
        assertEquals("{\"id\":22}\n", JvmLspSmoke.readFrame(in));
        assertNull(JvmLspSmoke.readFrame(in), "a clean end of stream is null, not an error");
    }

    @Test
    void aTruncatedFrameIsAnError() {
        var cut = new ByteArrayInputStream("Content-Length: 50\r\n\r\n{\"id\":1}".getBytes(StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> JvmLspSmoke.readFrame(cut));
        var noLength = new ByteArrayInputStream("X-Foo: bar\r\n\r\n{}".getBytes(StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> JvmLspSmoke.readFrame(noLength));
    }

    // --- pure: verdict ------------------------------------------------------------------------------------

    @Test
    void aResultPassesAndAnErrorNamesTheCodeMessageAndFirstDataLine() {
        assertEquals(Optional.empty(), JvmLspSmoke.verdict(json("{\"id\":2,\"result\":null}")));
        assertEquals(Optional.empty(), JvmLspSmoke.verdict(json("{\"id\":2,\"result\":{\"contents\":[]}}")));
        Optional<String> failed = JvmLspSmoke.verdict(
                json(
                        "{\"id\":2,\"error\":{\"code\":-32603,\"message\":\"Internal error.\","
                                + "\"data\":\"java.lang.NoClassDefFoundError: com/google/common/cache/CacheBuilder\\n\\tat x.y(Z.java:1)\"}}"));
        assertEquals(
                "-32603 Internal error.: java.lang.NoClassDefFoundError: com/google/common/cache/CacheBuilder",
                failed.orElseThrow());
        assertEquals(
                "-32601 Method not found",
                JvmLspSmoke.verdict(json("{\"id\":2,\"error\":{\"code\":-32601,\"message\":\"Method not found\"}}"))
                        .orElseThrow());
    }

    @Test
    void responsesAreMatchedByIdAndRequestsFromTheServerAreNot() {
        assertTrue(JvmLspSmoke.isResponseTo(json("{\"id\":2,\"result\":null}"), 2));
        assertTrue(JvmLspSmoke.isResponseTo(json("{\"id\":\"2\",\"result\":null}"), 2));
        assertFalse(JvmLspSmoke.isResponseTo(json("{\"id\":1,\"result\":null}"), 2));
        assertFalse(JvmLspSmoke.isResponseTo(json("{\"id\":2,\"method\":\"workspace/configuration\"}"), 2));
        assertFalse(JvmLspSmoke.isResponseTo(json("{\"method\":\"$/progress\"}"), 2));
    }

    @Test
    void theRequestsAreWhatTheServerExpects() {
        JsonObject init = JvmLspSmoke.initializeParams("file:///tmp/x/");
        assertEquals("file:///tmp/x/", init.get("rootUri").getAsString());
        assertTrue(
                init.getAsJsonObject("initializationOptions")
                        .getAsJsonObject("settings")
                        .getAsJsonObject("xml")
                        .getAsJsonObject("maven")
                        .getAsJsonObject("central")
                        .get("skip")
                        .getAsBoolean(),
                "a smoke must not start a Central index download");
        JsonObject open = JvmLspSmoke.didOpenParams("file:///tmp/x/pom.xml");
        assertEquals(
                "xml", open.getAsJsonObject("textDocument").get("languageId").getAsString());
        assertEquals(
                JvmLspSmoke.POM,
                open.getAsJsonObject("textDocument").get("text").getAsString());
        String hover = JvmLspSmoke.request(2, "textDocument/hover", JvmLspSmoke.positionParams("file:///p", 0, 2));
        assertTrue(
                hover.contains("\"id\":2") && hover.contains("\"line\":0") && hover.contains("\"character\":2"), hover);
    }

    // --- the exchange, against a stand-in server -----------------------------------------------------------

    @Test
    void aServerThatAnswersHoverPasses() {
        assertEquals(Optional.empty(), JvmLspSmoke.hoverCheck(fakeServer("ok"), Duration.ofSeconds(60)));
    }

    @Test
    void aServerThatFailsHoverInsideItselfFailsWithTheMissingClass() {
        String why = JvmLspSmoke.hoverCheck(fakeServer("error"), Duration.ofSeconds(60))
                .orElseThrow();
        assertTrue(
                why.startsWith(
                        "hover failed inside the server: -32603 Internal error.: java.lang.NoClassDefFoundError: com/google/common/cache/CacheBuilder"),
                why);
    }

    @Test
    void aServerThatNeverAnswersFailsAtTheDeadline() {
        String why = JvmLspSmoke.hoverCheck(fakeServer("hang"), Duration.ofSeconds(8))
                .orElseThrow();
        assertEquals("no answer to hover within 8 s", why);
    }

    @Test
    void aServerThatDiesFailsWithItsExitCodeAndStderr() {
        String why = JvmLspSmoke.hoverCheck(fakeServer("exit"), Duration.ofSeconds(60))
                .orElseThrow();
        assertEquals("the server exited with code 3 before answering hover: fake server: giving up", why);
    }

    @Test
    void aCommandThatCannotStartFails() {
        Assumptions.assumeTrue(onPath("java"), "needs java on PATH");
        String why = JvmLspSmoke.hoverCheck(
                        List.of("java", "-cp", "/no/such/dir/*", "no.such.Main"), Duration.ofSeconds(60))
                .orElseThrow();
        assertTrue(why.startsWith("the server exited with code 1 before answering initialize"), why);
        assertTrue(why.contains("no.such.Main"), why);
    }

    /** The native lemminx is asked only whether it runs: its hover on the one-line pom fails even where it works. */
    @Test
    void anInitializeCheckAsksForNoHover() {
        assertEquals(Optional.empty(), JvmLspSmoke.initializeCheck(fakeServer("error"), Duration.ofSeconds(60)));
    }

    /**
     * A program that exits before it reads a byte (a native build on too old a glibc, XML review A4) is reported by
     * its exit and its stderr, not by the "Stream closed" of the first write.
     */
    @Test
    @org.junit.jupiter.api.condition.DisabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void aProgramThatExitsAtOnceSaysWhyNotStreamClosed(@org.junit.jupiter.api.io.TempDir Path dir) throws IOException {
        Path program = dir.resolve("lemminx");
        java.nio.file.Files.writeString(
                program, "#!/bin/sh\necho \"lemminx: version \\`GLIBC_2.34' not found\" >&2\nexit 1\n");
        program.toFile().setExecutable(true);

        String why = JvmLspSmoke.initializeCheck(List.of(program.toString()), Duration.ofSeconds(30))
                .orElseThrow();

        assertEquals(
                "the server exited with code 1 before answering initialize: lemminx: version `GLIBC_2.34' not"
                        + " found",
                why);
    }

    private static List<String> fakeServer(String mode) {
        Assumptions.assumeTrue(onPath("java"), "needs java on PATH (the stand-in runs through the source launcher)");
        URL url = JvmLspSmokeTest.class.getResource("FakeStdioServer.java");
        Assumptions.assumeTrue(url != null && "file".equals(url.getProtocol()), "the stand-in must be a file");
        Path source = Path.of(java.net.URI.create(url.toString()));
        return List.of("java", source.toString(), mode);
    }

    private static boolean onPath(String exe) {
        return !com.editora.process.ProcessRunner.resolveExecutable(List.of(exe))
                .get(0)
                .equals(exe);
    }

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }
}
