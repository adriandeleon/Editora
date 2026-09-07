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
        Path script = Path.of("/work/tools/deploy.sh");

        assertEquals(
                script.getParent(), RunConfigWorkingDirectory.resolve(config("shell", script.toString(), ""), null));
    }
}
