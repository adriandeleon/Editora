package com.editora.run;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.editora.agent.AcpAgentRegistry;
import com.editora.build.BuildExecutable;
import com.editora.dap.DapServerRegistry;
import com.editora.lsp.LspServerRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A tool path picked with Settings' Browse… is written bare, and a home folder or {@code Program Files} has a
 * space in it. Every configured tool command (language server, ACP agent, Python debug interpreter, build tool
 * override) must take such a path as one executable — it used to split into a program that does not exist.
 */
class ToolCommandLineTest {

    @TempDir
    Path dir;

    @Test
    void anExistingFileWithASpaceIsOneExecutableEverywhere() throws Exception {
        Path exe = Files.createDirectories(dir.resolve("John Smith/tools")).resolve("my server");
        Files.writeString(exe, "#!/bin/sh\n");
        String path = exe.toString();
        List<String> one = List.of(path);

        assertEquals(one, ProgramArgs.commandLine(path));
        assertEquals(one, LspServerRegistry.commandFor("java", Map.of("java", path)));
        assertEquals(
                one, LspServerRegistry.specFor("java", Map.of("java", path)).command());
        assertEquals(one, AcpAgentRegistry.commandFor("claude", Map.of("claude", path)));
        assertEquals(one, DapServerRegistry.interpreterArgv("python", path));
        assertEquals(one, BuildExecutable.resolve(null, path, "mvn"));
    }

    @Test
    void anythingElseIsStillACommandLine() {
        assertEquals(List.of("gemini", "--acp"), ProgramArgs.commandLine("gemini --acp"));
        assertEquals(List.of("/no such/dir/x", "--stdio"), ProgramArgs.commandLine("\"/no such/dir/x\" --stdio"));
        assertEquals(List.of("/no", "such"), ProgramArgs.commandLine("/no such"), "a missing file is not guessed at");
        assertEquals(List.of(), ProgramArgs.commandLine("  "));
    }
}
