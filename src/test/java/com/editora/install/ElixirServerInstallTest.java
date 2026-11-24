package com.editora.install;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Where Elixir's language server is started from: as set, Editora's ElixirLS install, then the PATH. */
class ElixirServerInstallTest {

    private static Predicate<String> path(String... names) {
        Set<String> on = Set.of(names);
        return on::contains;
    }

    private static Path install(Path root, String script) throws Exception {
        Path dir = Files.createDirectories(root.resolve(InstallCatalog.ELIXIR_LS_DIR));
        return Files.writeString(dir.resolve(script), "#!/bin/sh\n");
    }

    @Test
    void aCommandOfTheUsersOwnIsKept(@TempDir Path dir) throws Exception {
        Path config = dir.resolve("config");
        install(config, "language_server.sh");
        assertEquals(
                "/opt/elixir-ls/language_server.sh",
                ElixirServerInstall.effectiveCommand(
                        "/opt/elixir-ls/language_server.sh", config, dir, false, path("elixir-ls")));
    }

    @Test
    void editorasInstallIsFoundBeforeThePath(@TempDir Path dir) throws Exception {
        Path config = dir.resolve("config");
        Path script = install(config, "language_server.sh");
        assertEquals(
                script.toString(),
                ElixirServerInstall.effectiveCommand("", config, dir.resolve("home"), false, path("elixir-ls")));
        assertEquals(
                script.toString(),
                ElixirServerInstall.effectiveCommand(" elixir-ls ", config, dir.resolve("home"), false, path()),
                "the bare default counts as blank");
    }

    @Test
    void theHomeInstallIsFoundWhenThisConfigFolderHasNone(@TempDir Path dir) throws Exception {
        Path home = dir.resolve("home");
        Path script = install(home.resolve(".editora"), "language_server.bat");
        assertEquals(
                script.toString(),
                ElixirServerInstall.effectiveCommand("", dir.resolve("config"), home, true, path()),
                "Windows runs the .bat");
        assertNull(ElixirServerInstall.ownLauncher(dir.resolve("config"), home, false), "no .sh installed");
    }

    @Test
    void withoutAnInstallThePathIsAsked(@TempDir Path dir) {
        Path config = dir.resolve("config");
        assertEquals("", ElixirServerInstall.effectiveCommand("", config, dir, false, path("elixir-ls")));
        assertEquals(
                "language_server.sh",
                ElixirServerInstall.effectiveCommand("", config, dir, false, path("language_server.sh")),
                "an unpacked release on the PATH");
        assertEquals(
                "language_server.bat",
                ElixirServerInstall.effectiveCommand("", config, dir, true, path("language_server.bat")));
        assertEquals(
                "",
                ElixirServerInstall.effectiveCommand("", config, dir, false, path()),
                "missing: the default says so");
    }
}
