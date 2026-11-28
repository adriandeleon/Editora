package com.editora.mcp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Claude Desktop Extension in {@code integrations/claude-desktop}: its manifest has what the MCPB spec requires
 * and runs the bundled wrapper with the configured Editora command, and the wrapper relays standard input and output
 * to {@code <command> mcp} and explains a command it cannot find.
 */
class ClaudeDesktopExtensionTest {

    private static final Path EXT = Path.of("integrations/claude-desktop");

    private final ObjectMapper m = new ObjectMapper();

    @Test
    void theManifestHasWhatTheSpecRequiresAndStartsTheWrapper() throws Exception {
        JsonNode man = m.readTree(EXT.resolve("manifest.json").toFile());
        for (String field : List.of("manifest_version", "name", "version", "description", "author", "server")) {
            assertTrue(man.has(field), "required: " + field);
        }
        assertEquals("0.3", man.get("manifest_version").asText());
        assertTrue(man.get("version").asText().matches("\\d+\\.\\d+\\.\\d+"), "semver");
        assertTrue(man.at("/author/name").isTextual());
        JsonNode server = man.get("server");
        assertEquals("node", server.get("type").asText());
        assertTrue(Files.isRegularFile(EXT.resolve(server.get("entry_point").asText())), "the entry point is there");
        assertEquals("node", server.at("/mcp_config/command").asText());
        assertEquals(
                "${__dirname}/server/index.js", server.at("/mcp_config/args/0").asText());
        assertEquals(
                "${user_config.editora_command}",
                server.at("/mcp_config/env/EDITORA_COMMAND").asText());
        assertEquals("editora", man.at("/user_config/editora_command/default").asText());
        assertTrue(Files.isRegularFile(EXT.resolve(man.get("icon").asText())), "the icon is there");
    }

    private static boolean nodeAvailable() {
        try {
            return new ProcessBuilder("node", "--version").start().waitFor(10, TimeUnit.SECONDS);
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    private static Process wrapper(String command) throws IOException {
        ProcessBuilder pb =
                new ProcessBuilder("node", EXT.resolve("server/index.js").toString());
        pb.environment().put("EDITORA_COMMAND", command);
        return pb.start();
    }

    @Test
    void theWrapperRelaysToEditoraMcpAndSaysWhenItCannotFindIt(@TempDir Path dir) throws Exception {
        Assumptions.assumeTrue(nodeAvailable(), "node is not installed");
        Assumptions.assumeTrue(!System.getProperty("os.name").startsWith("Windows"), "a shell script stands in");
        // A stand-in for `editora`: answers `mcp` by echoing standard input, with its arguments on the first line.
        Path fake = dir.resolve("editora");
        Files.writeString(fake, "#!/bin/sh\necho \"args: $*\"\ncat\n");
        Files.setPosixFilePermissions(fake, PosixFilePermissions.fromString("rwx------"));

        Process p = wrapper(fake.toString());
        p.getOutputStream()
                .write("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}\n".getBytes(StandardCharsets.UTF_8));
        p.getOutputStream().close();
        assertTrue(p.waitFor(20, TimeUnit.SECONDS));
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals("args: mcp\n{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}\n", out);
        assertEquals(0, p.exitValue());

        Process missing = wrapper(dir.resolve("nope").toString());
        missing.getOutputStream().close();
        assertTrue(missing.waitFor(20, TimeUnit.SECONDS));
        assertEquals(1, missing.exitValue());
        assertEquals(
                "", new String(missing.getInputStream().readAllBytes(), StandardCharsets.UTF_8), "stdout stays JSON");
        String err = new String(missing.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(err.contains("was not found") && err.contains("Copy Connect Command"), err);
    }
}
