package com.editora.install;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import com.editora.process.ProcessRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTML review 2 A105: the Node.js chosen in Settings → Build Tools → Node.js counts for Install. npm is looked for
 * beside it, the npm install runs with its bin folder first on PATH (npm's own {@code #!/usr/bin/env node}), and the
 * Node.js version a server's floor is checked against is that one's.
 */
class InstallServiceNodeSettingTest {

    private static Path nodeBin(Path dir, boolean withNpm) throws Exception {
        Path bin = Files.createDirectories(dir.resolve("node-v24/bin"));
        Files.writeString(bin.resolve("node"), "");
        if (withNpm) {
            Files.writeString(bin.resolve("npm"), "");
            Files.writeString(bin.resolve("npm.cmd"), "");
        }
        return bin;
    }

    @Test
    void npmIsFoundBesideTheChosenNodeJs(@TempDir Path dir) throws Exception {
        Path bin = nodeBin(dir, true);
        assertEquals(bin.resolve("npm"), InstallService.npmBeside(bin, false, Files::isRegularFile));
        assertEquals(bin.resolve("npm.cmd"), InstallService.npmBeside(bin, true, Files::isRegularFile), "Windows");
        assertNull(InstallService.npmBeside(nodeBin(dir.resolve("bare"), false), false, Files::isRegularFile));
        assertNull(InstallService.npmBeside(null, false, Files::isRegularFile));
    }

    @Test
    void anNpmCommandRunsWithTheChosenNodeJs(@TempDir Path dir) throws Exception {
        Path bin = nodeBin(dir, true);
        List<String> argv = List.of("npm", "install", "--prefix", "/x", "pkg@1");
        Map.Entry<List<String>, String> chosen = InstallService.npmCommand(argv, bin, false, Files::isRegularFile);
        assertEquals(bin.resolve("npm").toString(), chosen.getKey().get(0), "npm beside the chosen Node.js");
        assertEquals(argv.subList(1, argv.size()), chosen.getKey().subList(1, argv.size()));
        assertEquals(bin.toString(), chosen.getValue(), "its folder goes first on PATH");

        Path bare = nodeBin(dir.resolve("bare"), false);
        Map.Entry<List<String>, String> pathNpm = InstallService.npmCommand(argv, bare, false, Files::isRegularFile);
        assertEquals(argv, pathNpm.getKey(), "npm from PATH, run on the chosen Node.js");
        assertEquals(bare.toString(), pathNpm.getValue());

        assertEquals(Map.entry(argv, ""), InstallService.npmCommand(argv, null, false, Files::isRegularFile));
        List<String> pip = List.of("python3", "-m", "pip");
        assertEquals(Map.entry(pip, ""), InstallService.npmCommand(pip, bin, false, Files::isRegularFile));
    }

    @Test
    void theHtmlServerInstallRunsNpmBesideTheChosenNodeJs(@TempDir Path dir) throws Exception {
        Path bin = nodeBin(dir, true);
        Path config = Files.createDirectories(dir.resolve("config"));
        InstallService service = new InstallService();
        service.setNodeSettingSupplier(() -> bin.resolve("node").toString());
        List<List<String>> ran = new CopyOnWriteArrayList<>();
        List<String> paths = new CopyOnWriteArrayList<>();
        service.setCommandsWithPathForTest((argv, pathFirst) -> {
            ran.add(argv);
            paths.add(pathFirst);
            try {
                InstallTestAccess.webServersLayout(Path.of(argv.get(argv.indexOf("--prefix") + 1)), true);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            return new ProcessRunner.Result(0, "added 31 packages", "");
        });
        try {
            InstallService.Result result =
                    service.installSync(InstallCatalog.serverInstall("html").orElseThrow(), config, id -> {});
            assertTrue(result.ok(), result.message());
            assertEquals(1, ran.size(), ran.toString());
            // npm.cmd on Windows, as on PATH (nodeBin writes both)
            boolean windows = System.getProperty("os.name", "")
                    .toLowerCase(java.util.Locale.ROOT)
                    .contains("win");
            assertEquals(
                    bin.resolve(windows ? "npm.cmd" : "npm").toString(),
                    ran.get(0).get(0));
            assertEquals(List.of(bin.toString()), paths);
            assertTrue(Files.exists(WebServerInstall.folder(config).resolve("node_modules/node_modules/typescript")));
        } finally {
            service.shutdown();
        }
    }

    @Test
    void npmBesideTheChosenNodeJsSatisfiesTheNpmPrerequisite(@TempDir Path dir) throws Exception {
        Path bin = nodeBin(dir, true);
        InstallService service = new InstallService();
        try {
            service.setNodeSettingSupplier(() -> bin.resolve("node").toString());
            assertTrue(service.npmFound(), "found without npm on PATH (A105)");
            assertEquals(bin, service.settingsNodeBin());
            service.setNodeSettingSupplier(() -> "");
            assertNull(service.settingsNodeBin(), "nothing chosen: PATH alone");
            service.setNodeSettingSupplier(() -> {
                throw new IllegalStateException("no settings yet");
            });
            assertNull(service.settingsNodeBin());
            service.setNodeSettingSupplier(null);
            assertNull(service.settingsNodeBin());
        } finally {
            service.shutdown();
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS) // a shell-script "node"
    void theNodeFloorIsCheckedAgainstTheChosenNodeJs(@TempDir Path dir) throws Exception {
        Path bin = Files.createDirectories(dir.resolve("node-v99/bin"));
        Path node = Files.writeString(bin.resolve("node"), "#!/bin/sh\necho v99.1.0\n");
        assertTrue(node.toFile().setExecutable(true));
        InstallService service = new InstallService();
        try {
            service.setNodeSettingSupplier(node::toString);
            assertEquals("v99.1.0", service.probeNodeVersion());
        } finally {
            service.shutdown();
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS) // a shell-script "npm"
    void anNpmInstallRunsTheNpmBesideTheChosenNodeJsWithItFirstOnPath(@TempDir Path dir) throws Exception {
        // the real command runner: the step's npm is the one beside the Settings Node.js, which sees that folder first
        Path bin = Files.createDirectories(dir.resolve("node-v24/bin"));
        Files.writeString(bin.resolve("node"), "");
        Path seen = dir.resolve("seen.txt");
        // like npm -g with this Node.js: the server's program lands in its bin folder (Install checks it is found)
        Path program = bin.resolve("yaml-language-server");
        Path npm = Files.writeString(
                bin.resolve("npm"),
                "#!/bin/sh\necho \"$0 $1|$PATH\" > '" + seen + "'\nprintf '#!/bin/sh\\n' > '" + program
                        + "'\nchmod +x '" + program + "'\nexit 0\n");
        assertTrue(npm.toFile().setExecutable(true));
        InstallService service = new InstallService();
        try {
            service.setNodeSettingSupplier(() -> bin.resolve("node").toString());
            InstallService.Result result = service.installSync(
                    InstallCatalog.serverInstall("yaml").orElseThrow(),
                    Files.createDirectories(dir.resolve("config")),
                    id -> {});
            assertTrue(result.ok(), result.message());
            String line = Files.readString(seen).strip();
            assertTrue(line.startsWith(npm + " install|" + bin + java.io.File.pathSeparator), line);
            assertTrue(Files.isExecutable(program), "npm put the server beside the chosen Node.js");
        } finally {
            service.shutdown();
        }
    }
}
