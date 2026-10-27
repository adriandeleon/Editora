package com.editora.lsp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javafx.application.Platform;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxToolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A server that exits before it ever answers {@code initialize} leaves its reason on stderr — lemminx's native
 * build on a C library older than it needs says "version `GLIBC_2.34' not found" and nothing else. The manager
 * keeps that first line for the crash notice, Settings and Doctor (XML review A4), instead of only the Debug Log.
 */
@Tag("fx")
@DisabledOnOs(OS.WINDOWS) // a shell script stands in for the binary
class LspCrashReasonFxTest {

    @BeforeAll
    static void bootToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @Test
    void aServerThatDiesAtStartIsExplainedByWhatItSaidFirst(@TempDir Path dir) throws Exception {
        Path root = Files.createDirectories(dir.resolve("app"));
        Path file = root.resolve("note.xml");
        Files.writeString(file, "<note/>\n");
        String said = "/opt/lemminx: /lib/x86_64-linux-gnu/libc.so.6: version `GLIBC_2.34' not found"
                + " (required by /opt/lemminx)";
        Path server = dir.resolve("lemminx");
        Files.writeString(server, "#!/bin/sh\necho >&2\necho \"" + said.replace("`", "\\`") + "\" >&2\nexit 1\n");
        server.toFile().setExecutable(true);
        LspManager manager = new LspManager((f, d) -> {}, (type, message) -> {});
        try {
            manager.configure(true, Map.of("xml", server.toString()));
            CompletableFuture<String> crashed = new CompletableFuture<>();
            manager.setOnSessionCrashed((serverId, sessionRoot) -> crashed.complete(serverId));

            Platform.runLater(() -> manager.openDocument(file, root, "xml", "<note/>\n"));

            assertEquals("xml", crashed.get(30, TimeUnit.SECONDS));
            assertEquals(said, manager.crashReason("xml"));
        } finally {
            manager.close();
        }
    }
}
