package com.editora.ui;

import java.nio.file.Path;
import java.util.List;

import com.editora.run.PythonInterpreters.PythonInterpreter;
import com.editora.run.PythonInterpreters.Source;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which interpreters an untrusted folder may not have run (HN dry run, 2026-11-27): the environment it ships, found
 * in its folders, is the folder's code; the user's pick, setting, activated environment and PATH are not.
 */
class PythonInterpreterTrustTest {

    private static final Path VENV = Path.of("/w/repo/.venv");

    @Test
    void onlyAnEnvironmentFoundInAnUntrustedFolderIsHeldBack() {
        PythonInterpreter found = new PythonInterpreter(List.of("/w/repo/.venv/bin/python"), Source.PROJECT_VENV, VENV);
        assertTrue(PythonInterpreterCoordinator.untrustedEnvironment(found, p -> false));
        assertFalse(PythonInterpreterCoordinator.untrustedEnvironment(found, p -> p.startsWith("/w/repo")));
        for (Source mine : List.of(Source.PROJECT_CHOICE, Source.SETTING, Source.VIRTUAL_ENV, Source.PATH)) {
            PythonInterpreter interp = new PythonInterpreter(List.of("/w/repo/.venv/bin/python"), mine, VENV);
            assertFalse(PythonInterpreterCoordinator.untrustedEnvironment(interp, p -> false), mine.name());
        }
    }

    @Test
    void anUntrustedEnvironmentsPythonIsNotRunForItsVersion() {
        List<Path> found = List.of(VENV);
        assertFalse(PythonInterpreterCoordinator.mayProbe("/w/repo/.venv/bin/python", found, p -> false));
        assertTrue(PythonInterpreterCoordinator.mayProbe("/w/repo/.venv/bin/python", found, p -> true));
        assertTrue(PythonInterpreterCoordinator.mayProbe("/usr/bin/python3", found, p -> false), "not the folder's");
    }
}
