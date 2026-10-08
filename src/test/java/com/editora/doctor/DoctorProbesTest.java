package com.editora.doctor;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Doctor's probes against stand-in programs: shell scripts written into the test's folder that answer
 * {@code --version} / {@code --help} the ways real tools do.
 */
class DoctorProbesTest {

    private static Path script(Path dir, String name, String body) throws Exception {
        Path file = Files.writeString(dir.resolve(name), "#!/bin/sh\n" + body + "\n");
        assertTrue(file.toFile().setExecutable(true));
        return file;
    }

    @Test
    void nothingToProbeIsAbsent(@TempDir Path dir) {
        assertFalse(DoctorProbes.version(null).present());
        assertFalse(DoctorProbes.version(List.of()).present());
        assertEquals("", DoctorProbes.resolvedPath(null));
        assertEquals("", DoctorProbes.resolvedPath(List.of()));
        assertFalse(DoctorProbes.onPath(List.of()));
        String missing = dir.resolve("no-such-tool").toString();
        assertFalse(DoctorProbes.version(List.of(missing)).present());
        assertFalse(DoctorProbes.raw(List.of(missing)).present());
        assertEquals("", DoctorProbes.resolvedPath(List.of(missing)));
        assertFalse(DoctorProbes.onPath(List.of(missing)));
        assertFalse(DoctorProbes.succeeds(List.of(missing)));
        assertEquals("", DoctorProbes.resolvedPath(List.of("editora-no-such-tool-on-any-path")));
    }

    @Test
    void aPathThatCannotBeAPathIsAbsentNotAnError() {
        assertEquals("", DoctorProbes.resolvedPath(List.of("bad\u0000/name")));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aToolIsPresentWithTheFirstLineItPrints(@TempDir Path dir) throws Exception {
        Path tool = script(dir, "tool", "echo 'tool 1.2.3'\necho 'built yesterday'");
        DoctorProbes.Presence p = DoctorProbes.version(List.of(tool.toString()));
        assertTrue(p.present());
        assertEquals("tool 1.2.3", p.version());
        assertEquals(tool.toString(), DoctorProbes.resolvedPath(List.of(tool.toString(), "--flag")));
        assertTrue(DoctorProbes.onPath(List.of(tool.toString())));
        assertTrue(DoctorProbes.succeeds(List.of(tool.toString())));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aBareToolThatRejectsTheFlagIsStillPresentButHasNoVersion(@TempDir Path dir) throws Exception {
        Path tool = script(dir, "grumpy", "echo 'unknown option' >&2\nexit 2");
        DoctorProbes.Presence p = DoctorProbes.version(List.of(tool.toString()));
        assertTrue(p.present(), "it launched, so it is installed");
        assertEquals("", p.version());
        assertFalse(DoctorProbes.succeeds(List.of(tool.toString())));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aWrapperCommandGetsASecondChanceWithHelp(@TempDir Path dir) throws Exception {
        // "npx tool"-style: more than one token, so a failing run proves nothing about the tool behind it.
        Path helpOnly = script(dir, "wrap", "if [ \"$2\" = \"--help\" ]; then echo usage; exit 0; fi\nexit 1");
        DoctorProbes.Presence viaHelp = DoctorProbes.version(List.of(helpOnly.toString(), "sub"));
        assertTrue(viaHelp.present());
        assertEquals("", viaHelp.version());

        Path neither = script(dir, "broken", "exit 1");
        assertFalse(DoctorProbes.version(List.of(neither.toString(), "sub")).present());

        Path both = script(dir, "fine", "echo 'sub 9.9'");
        assertEquals(
                "sub 9.9", DoctorProbes.version(List.of(both.toString(), "sub")).version());
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void rawAndOutputReadWhatAToolWritesToEitherStream(@TempDir Path dir) throws Exception {
        Path java = script(dir, "fakejava", "echo 'openjdk version \"25\"' >&2\necho 'on stdout'");
        DoctorProbes.Presence p = DoctorProbes.raw(List.of(java.toString(), "-version"));
        assertTrue(p.present());
        assertFalse(p.version().isBlank());
        String all = DoctorProbes.output(List.of(java.toString(), "-version"));
        assertTrue(all.contains("openjdk version \"25\""), all);
        assertTrue(all.contains("on stdout"), all);
        assertTrue(
                all.indexOf("openjdk") < all.indexOf("on stdout"), "stderr first: that is where java -version writes");
        assertFalse(DoctorProbes.output(List.of(dir.resolve("gone").toString())).contains("openjdk"));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void succeedsPassesTheExtraEnvironment(@TempDir Path dir) throws Exception {
        Path needsEnv = script(dir, "needs", "[ \"$EDITORA_PROBE_FLAG\" = yes ]");
        assertFalse(DoctorProbes.succeeds(List.of(needsEnv.toString())));
        assertTrue(DoctorProbes.succeeds(List.of(needsEnv.toString()), Map.of("EDITORA_PROBE_FLAG", "yes")));
    }

    @Test
    void pythonPathPutsTheBundleAheadOfWhatIsAlreadySet(@TempDir Path dir) {
        assertEquals(Map.of(), DoctorProbes.pythonPathWith(null));
        String existing = System.getenv().getOrDefault("PYTHONPATH", "");
        String expected = existing.isEmpty() ? dir.toString() : dir + File.pathSeparator + existing;
        assertEquals(Map.of("PYTHONPATH", expected), DoctorProbes.pythonPathWith(dir));
    }
}
