package com.editora.run;

import java.nio.file.Path;

import com.editora.config.RunConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RunConfigWorkingDirectoryTest {

    private static RunConfiguration config(String type, String target, String workingDir) {
        return new RunConfiguration("Demo", type, target, "", "", "", "", workingDir, "", "");
    }

    @Test
    void anNpmScriptWithNoExplicitDirectoryRunsAtTheProjectRoot() {
        Path root = Path.of("/work/adl-website");

        assertEquals(root, RunConfigWorkingDirectory.resolve(config("npm", "preview", ""), root));
        assertEquals(root, RunConfigWorkingDirectory.resolve(config("npm", "build", ""), root));
    }

    @Test
    void anExplicitDirectoryOverridesTheProjectRoot() {
        assertEquals(
                Path.of("/work/frontend"),
                RunConfigWorkingDirectory.resolve(
                        config("npm", "build", "/work/frontend"), Path.of("/work/adl-website")));
    }

    @Test
    void buildToolTargetsAreNeverMistakenForPaths() {
        assertNull(RunConfigWorkingDirectory.resolve(config("npm", "preview", ""), null));
        assertNull(RunConfigWorkingDirectory.resolve(config("make", "release", ""), null));
    }

    @Test
    void aStandaloneFileBackedScriptStillDefaultsToItsOwnFolder() {
        Path script = Path.of("/work/tools/deploy.sh").toAbsolutePath(); // a drive too, on Windows

        assertEquals(
                script.getParent(), RunConfigWorkingDirectory.resolve(config("shell", script.toString(), ""), null));
    }

    @Test
    void aRelativeDirectoryIsRelativeToTheProjectNotToWhereEditoraStarted() {
        // It was Path.of("sub"): resolved against the Editora process's own working directory.
        Path root = Path.of("/work/site").toAbsolutePath();
        assertEquals(root.resolve("sub"), RunConfigWorkingDirectory.resolve(config("shell", "x.sh", "sub"), root));
        assertEquals(root.resolve("tools"), RunConfigWorkingDirectory.resolve(config("make", "", "./tools"), root));
    }

    @Test
    void withNoProjectARelativeDirectoryIsRelativeToAnAbsoluteScript() {
        Path script = Path.of("/work/tools/deploy.sh").toAbsolutePath();
        assertEquals(
                script.getParent().resolve("out"),
                RunConfigWorkingDirectory.resolve(config("shell", script.toString(), "out"), null));
    }

    @Test
    void withNoProjectARelativeScriptHasNoDirectoryToRunIn() {
        // It ran in <Editora's cwd>/scripts and then asked bash for scripts/x.sh there: the path twice over.
        assertNull(RunConfigWorkingDirectory.resolve(config("shell", "scripts/x.sh", ""), null));
        assertNull(RunConfigWorkingDirectory.resolve(config("shell", "scripts/x.sh", "sub"), null));
    }

    @Test
    void theScriptIsResolvedWhereTheInterpreterWillLookForIt() {
        Path dir = Path.of("/work/site/sub").toAbsolutePath();
        assertEquals(dir.resolve("run.sh"), RunConfigWorkingDirectory.script(config("shell", "run.sh", ""), dir));
        assertNull(RunConfigWorkingDirectory.script(config("make", "all", ""), dir));
    }

    @Test
    void aPythonScriptRunsInItsOwnFolderLikeRunFile(@org.junit.jupiter.api.io.TempDir Path root) {
        // C8 / decision 2: a configuration ran at the project root while ▶ ran in the script's folder.
        assertEquals(
                root.resolve("scripts"),
                RunConfigWorkingDirectory.resolve(config("python", "scripts/report.py", ""), root));
        assertEquals(root, RunConfigWorkingDirectory.resolve(config("python-module", "app.main", ""), root));
        assertEquals(
                root, RunConfigWorkingDirectory.resolve(config("shell", "scripts/x.sh", ""), root), "shell unchanged");
    }

    @Test
    void aTargetStaysRelativeToTheProjectWhenTheWorkingDirectoryChanges(@org.junit.jupiter.api.io.TempDir Path root)
            throws Exception {
        // C9: target src/app/main.py with working dir src became src/src/app/main.py — "script missing".
        Path main = root.resolve("src/app/main.py");
        java.nio.file.Files.createDirectories(main.getParent());
        java.nio.file.Files.writeString(main, "print(1)\n");
        RunConfiguration cfg = config("python", "src/app/main.py", "src");
        Path cwd = RunConfigWorkingDirectory.resolve(cfg, root);
        assertEquals(root.resolve("src"), cwd);
        assertEquals(main, RunConfigWorkingDirectory.script(cfg, root, cwd));
        // An older configuration whose target was written relative to its working directory still resolves.
        RunConfiguration old = config("python", "app/main.py", "src");
        assertEquals(main, RunConfigWorkingDirectory.script(old, root, RunConfigWorkingDirectory.resolve(old, root)));
    }
}
