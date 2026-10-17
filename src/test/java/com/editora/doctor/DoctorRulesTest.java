package com.editora.doctor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DoctorRulesTest {

    @Test
    void singleTokenPresenceCountsAnyCleanLaunch() {
        assertTrue(DoctorRules.presentFrom(0, false));
        // A tool that launches but rejects --version is still installed.
        assertTrue(DoctorRules.presentFrom(2, false));
        // -1 is the runner's failed-to-start sentinel: not installed.
        assertFalse(DoctorRules.presentFrom(-1, false));
    }

    @Test
    void wrapperPresenceRequiresSuccess() {
        // npx always launches, so only exit 0 proves the wrapped package (the maid rule).
        assertTrue(DoctorRules.presentFrom(0, true));
        assertFalse(DoctorRules.presentFrom(2, true));
        assertFalse(DoctorRules.presentFrom(-1, true));
    }

    @Test
    void ghStatusDistinguishesMissingUnauthenticatedAndReady() {
        assertEquals(DoctorStatus.MISSING, DoctorRules.ghStatus(false, false));
        assertEquals(DoctorStatus.MISSING, DoctorRules.ghStatus(false, true));
        assertEquals(DoctorStatus.WARN, DoctorRules.ghStatus(true, false));
        assertEquals(DoctorStatus.OK, DoctorRules.ghStatus(true, true));
    }

    @Test
    void javaRunStatusTiers() {
        assertEquals(DoctorStatus.OK, DoctorRules.javaRunStatus(25));
        assertEquals(DoctorStatus.OK, DoctorRules.javaRunStatus(26));
        assertEquals(DoctorStatus.WARN, DoctorRules.javaRunStatus(24));
        assertEquals(DoctorStatus.WARN, DoctorRules.javaRunStatus(8));
        assertEquals(DoctorStatus.WARN, DoctorRules.javaRunStatus(1));
        assertEquals(DoctorStatus.MISSING, DoctorRules.javaRunStatus(0));
        assertEquals(DoctorStatus.MISSING, DoctorRules.javaRunStatus(-1));
    }

    @Test
    void debugpyHealthIsTheInterpreterImportingIt() {
        assertEquals("", DoctorRules.debugpyBlocker(true, true, "python3"));
        // A located debugpy bundle is no proof: a broken interpreter is what the debugger trips over.
        assertEquals("python3", DoctorRules.debugpyBlocker(false, false, "python3"));
        assertEquals("/opt/py/bin/python", DoctorRules.debugpyBlocker(false, false, "/opt/py/bin/python"));
        assertEquals("debugpy", DoctorRules.debugpyBlocker(false, true, "python3"));
    }

    @Test
    void firstLinePrefersStdoutSkipsBlanksAndStrips() {
        assertEquals("git version 2.54.0", DoctorRules.firstLine("git version 2.54.0\nmore", "err"));
        assertEquals("from stderr", DoctorRules.firstLine("", "\n  from stderr  \n"));
        assertEquals("second", DoctorRules.firstLine("\n\n  second \n", ""));
        assertEquals("", DoctorRules.firstLine("", ""));
        assertEquals("", DoctorRules.firstLine(null, null));
    }

    /**
     * pyright-langserver answers --version with an error, so Doctor showed only a path (Python review B16):
     * the version comes from the npm package the launcher is linked into.
     */
    @org.junit.jupiter.api.Test
    void anNpmLaunchersVersionComesFromItsPackage(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws Exception {
        java.nio.file.Path pkg = java.nio.file.Files.createDirectories(dir.resolve("lib/node_modules/pyright"));
        java.nio.file.Files.writeString(
                pkg.resolve("package.json"), "{\n  \"name\": \"pyright\",\n  \"version\": \"1.1.411\"\n}\n");
        java.nio.file.Path js = java.nio.file.Files.writeString(pkg.resolve("langserver.index.js"), "");
        java.nio.file.Path bin = java.nio.file.Files.createDirectories(dir.resolve("bin"));
        java.nio.file.Path launcher = bin.resolve("pyright-langserver");
        try {
            java.nio.file.Files.createSymbolicLink(launcher, js);
        } catch (UnsupportedOperationException | java.io.IOException e) {
            launcher = js; // no symlinks here: the package file itself
        }
        org.junit.jupiter.api.Assertions.assertEquals("pyright 1.1.411", DoctorRules.npmPackageVersion(launcher));
        org.junit.jupiter.api.Assertions.assertEquals("", DoctorRules.npmPackageVersion(dir.resolve("missing")));
        org.junit.jupiter.api.Assertions.assertEquals("2.0", DoctorRules.packageJsonVersion("{\"version\": \"2.0\"}"));
    }
}
