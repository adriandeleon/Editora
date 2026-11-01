package com.editora.install;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.editora.process.ProcessRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTML review 3 A203: Install's {@code npm install -g} with the Settings Node.js's npm puts a server in that Node.js's
 * own bin folder, which need not be on PATH. The server's default program is found there too, after PATH; and an
 * {@code npm -g} whose program Editora still cannot find fails, saying where npm put it, instead of "installed".
 */
@DisabledOnOs(value = OS.WINDOWS, disabledReason = "POSIX launchers; the Windows .cmd lookup is ProcessRunner's")
class NpmServerBinsTest {

    @TempDir
    Path dir;

    private String savedPath;

    @BeforeEach
    void pinPath() throws Exception {
        savedPath = ProcessRunner.augmentedPath();
        setAugmentedPath(Files.createDirectories(dir.resolve("empty-path")).toString()); // nothing on PATH
    }

    @AfterEach
    void restorePath() throws Exception {
        setAugmentedPath(savedPath);
    }

    private static void setAugmentedPath(String path) throws ReflectiveOperationException {
        var f = ProcessRunner.class.getDeclaredField("cachedPath");
        f.setAccessible(true);
        f.set(null, path);
    }

    private Path nodeBin() throws Exception {
        Path bin = Files.createDirectories(dir.resolve("node-v24/bin"));
        executable(bin.resolve("node"));
        executable(bin.resolve("npm"));
        return bin;
    }

    private static Path executable(Path file) throws Exception {
        Files.writeString(file, "#!/bin/sh\n");
        assertTrue(file.toFile().setExecutable(true));
        return file;
    }

    @Test
    void anNpmServersDefaultProgramIsFoundInTheSettingsNodeJs() throws Exception {
        Path bin = nodeBin();
        String node = bin.resolve("node").toString();
        List<String> yaml = List.of("yaml-language-server", "--stdio");
        assertEquals(yaml, NpmServerBins.resolve("yaml", yaml, node), "not installed yet");
        assertFalse(NpmServerBins.defaultProgramFound("yaml", node));

        Path program = executable(bin.resolve("yaml-language-server"));
        assertEquals(List.of(program.toString(), "--stdio"), NpmServerBins.resolve("yaml", yaml, node));
        assertTrue(NpmServerBins.defaultProgramFound("yaml", node));
        assertFalse(NpmServerBins.defaultProgramFound("yaml", ""), "no Node.js chosen: PATH alone");
        assertEquals(yaml, NpmServerBins.resolve("yaml", yaml, null));

        Path onPath = Files.createDirectories(dir.resolve("on-path"));
        Path pathCopy = executable(onPath.resolve("yaml-language-server"));
        setAugmentedPath(onPath.toString());
        assertEquals(yaml, NpmServerBins.resolve("yaml", yaml, node), "PATH first: resolved as before");
        assertTrue(NpmServerBins.defaultProgramFound("yaml", node));
        assertTrue(Files.exists(pathCopy));
    }

    @Test
    void onlyAnNpmServersOwnDefaultProgramIsLookedFor() throws Exception {
        Path bin = nodeBin();
        String node = bin.resolve("node").toString();
        executable(bin.resolve("gopls"));
        executable(bin.resolve("my-yaml"));
        assertEquals(List.of("gopls"), NpmServerBins.resolve("go", List.of("gopls"), node), "not an npm server");
        assertEquals(List.of("my-yaml"), NpmServerBins.resolve("yaml", List.of("my-yaml"), node), "the user's own");
        List<String> path = List.of("/opt/yaml-language-server");
        assertEquals(path, NpmServerBins.resolve("yaml", path, node), "a path is the user's");
        assertFalse(NpmServerBins.isDefaultNpmProgram("yaml", List.of()));
        assertFalse(NpmServerBins.isDefaultNpmProgram("yaml", null));
        assertTrue(NpmServerBins.isDefaultNpmProgram("python", List.of("pyright-langserver", "--stdio")));
        assertTrue(NpmServerBins.isDefaultNpmProgram("typescript", List.of("typescript-language-server")));
        assertEquals("yaml-language-server", NpmServerBins.defaultProgram("yaml"));
        assertEquals("", NpmServerBins.defaultProgram("no-such-server"));
    }

    @Test
    void npmBesideTheSettingsNodeJsIsNamed() throws Exception {
        Path bin = nodeBin();
        assertEquals(bin, NpmServerBins.settingsNodeBin(bin.resolve("node").toString()));
        assertEquals(
                bin.resolve("npm"),
                NpmServerBins.settingsNpm(bin.resolve("node").toString()));
        assertNull(NpmServerBins.settingsNodeBin(" "));
        assertNull(NpmServerBins.settingsNpm(null));
        Files.delete(bin.resolve("npm"));
        assertNull(NpmServerBins.settingsNpm(bin.resolve("node").toString()), "a Node.js without npm");
    }

    /** Install with the Settings npm: its -g puts YAML's server beside that Node.js, which counts as installed. */
    @Test
    void anNpmGlobalInstallIntoTheSettingsNodeJsIsInstalled() throws Exception {
        Path bin = nodeBin();
        String node = bin.resolve("node").toString();
        InstallService service = new InstallService();
        service.setNodeSettingSupplier(() -> node);
        List<List<String>> ran = new CopyOnWriteArrayList<>();
        service.setCommandsWithPathForTest((argv, pathFirst) -> {
            ran.add(argv);
            try {
                executable(bin.resolve("yaml-language-server")); // where npm -g of this Node.js puts it
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            return new ProcessRunner.Result(0, "added 1 package", "");
        });
        service.setNpmProgramFoundForTest(null); // the real check
        try {
            InstallService.Result result = service.installSync(
                    InstallCatalog.serverInstall("yaml").orElseThrow(), dir.resolve("config"), id -> {});
            assertTrue(result.ok(), result.message());
            assertEquals(List.of(List.of(bin.resolve("npm").toString(), "install", "-g", "yaml-language-server")), ran);
        } finally {
            service.shutdown();
        }
    }

    /** An npm whose global folder is neither on PATH nor the Settings Node.js's: not "installed", and where it went. */
    @Test
    void anNpmGlobalInstallEditoraCannotFindSaysWhereItWent() throws Exception {
        Path bin = nodeBin();
        String node = bin.resolve("node").toString();
        InstallService service = new InstallService();
        service.setNodeSettingSupplier(() -> node);
        List<List<String>> ran = new CopyOnWriteArrayList<>();
        service.setCommandsWithPathForTest((argv, pathFirst) -> {
            ran.add(argv);
            return argv.contains("prefix")
                    ? new ProcessRunner.Result(0, "/home/me/.npm-custom\n", "")
                    : new ProcessRunner.Result(0, "added 1 package", "");
        });
        service.setNpmProgramFoundForTest(null);
        try {
            InstallService.Result result = service.installSync(
                    InstallCatalog.serverInstall("yaml").orElseThrow(), dir.resolve("config"), id -> {});
            assertFalse(result.ok());
            assertTrue(result.message().contains("does not find yaml-language-server"), result.message());
            assertTrue(result.message().contains("/home/me/.npm-custom/bin"), result.message());
            assertEquals(List.of(bin.resolve("npm").toString(), "prefix", "-g"), ran.get(1), "npm says where");

            ran.clear();
            service.setCommandsWithPathForTest((argv, pathFirst) -> {
                ran.add(argv);
                return argv.contains("prefix")
                        ? new ProcessRunner.Result(1, "", "npm ERR!")
                        : new ProcessRunner.Result(0, "", "");
            });
            service.setNpmProgramFoundForTest(id -> false);
            InstallService.Result unsaid = service.installSync(
                    InstallCatalog.serverInstall("yaml").orElseThrow(), dir.resolve("config"), id -> {});
            assertFalse(unsaid.ok());
            assertTrue(unsaid.message().contains("npm prefix -g"), unsaid.message());

            service.setNodeSettingSupplier(() -> {
                throw new IllegalStateException("no settings yet");
            });
            service.setNpmProgramFoundForTest(null);
            assertFalse(
                    service.installSync(
                                    InstallCatalog.serverInstall("yaml").orElseThrow(), dir.resolve("config"), id -> {})
                            .ok(),
                    "no Settings Node.js to look in: PATH alone, which has none");
        } finally {
            service.shutdown();
        }
    }
}
