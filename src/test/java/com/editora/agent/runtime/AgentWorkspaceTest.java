package com.editora.agent.runtime;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class AgentWorkspaceTest {
    @TempDir
    Path dir;

    @Test
    void rejectsTraversalCredentialsAndSymbolicLinks() throws Exception {
        var workspace = new AgentWorkspace(dir);
        assertThrows(java.io.IOException.class, () -> new AgentWorkspace(null));
        assertThrows(java.io.IOException.class, () -> workspace.resolve("../outside.txt"));
        assertThrows(
                java.io.IOException.class,
                () -> workspace.resolve(dir.resolveSibling("outside.txt").toString()));
        assertEquals(
                dir.resolve("inside.txt"),
                workspace.resolve(dir.resolve("inside.txt").toString()));
        assertEquals(dir.resolve("inside.txt"), workspace.resolve("nested/../inside.txt"));
        assertThrows(java.io.IOException.class, () -> workspace.resolve(".env"));
        assertThrows(java.io.IOException.class, () -> workspace.resolve(".ENV.LOCAL"));
        assertThrows(java.io.IOException.class, () -> workspace.resolve(".git/config"));
        assertThrows(java.io.IOException.class, () -> workspace.resolve("keys/private.pem"));
        Path target = Files.createDirectory(dir.resolve("real"));
        try {
            Files.createSymbolicLink(dir.resolve("link"), target);
        } catch (UnsupportedOperationException | java.io.IOException unsupported) {
            return;
        }
        assertThrows(java.io.IOException.class, () -> workspace.resolve("link/new.txt"));
        assertEquals(dir.resolve("new.txt"), workspace.resolve("new.txt"));
    }

    @Test
    void instructionsAreBoundedAndDoNotReadAncestors() throws Exception {
        Path child = Files.createDirectory(dir.resolve("project"));
        Files.writeString(dir.resolve("AGENTS.md"), "parent secret");
        var workspace = new AgentWorkspace(child);
        assertEquals("", workspace.instructions(new AgentCancellation()));
        Files.writeString(child.resolve("AGENTS.md"), "x".repeat(20_000));
        assertEquals(12_000, workspace.instructions(new AgentCancellation()).length());
    }
}
