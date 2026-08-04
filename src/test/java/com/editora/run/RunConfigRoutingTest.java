package com.editora.run;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RunConfigRoutingTest {

    private static final Path APP = Path.of("/w/app/src/main/java/App.java");
    private static final Path UTIL = Path.of("/w/app/src/main/java/Util.java");
    private static final Path OTHER = Path.of("/w/other/src/main/java/Other.java");

    /** The previous behaviour, preserved: with a Java file active, that is what gets used. */
    @Test
    void theActiveJavaFileIsPreferred() {
        assertEquals(APP, RunConfigRouting.pick(List.of(UTIL, APP), APP, ""));
    }

    /**
     * The point of the change. A configuration is named and independent of the current tab, so looking at a
     * Markdown file must not stop it launching when a Java file is open elsewhere.
     */
    @Test
    void anyOpenJavaFileWorksWhenTheActiveOneIsNot() {
        assertEquals(UTIL, RunConfigRouting.pick(List.of(UTIL, APP), null, ""));
    }

    /** With several projects open, the configuration's working directory says which one it belongs to. */
    @Test
    void theWorkingDirectorySelectsTheProject() {
        // Active file is in a different project than the configuration's working dir: the config wins.
        assertEquals(OTHER, RunConfigRouting.pick(List.of(APP, OTHER), APP, "/w/other"));
    }

    /** Among candidates inside the right project, the active one is still preferred. */
    @Test
    void theActiveFileWinsAmongCandidatesInTheSameProject() {
        assertEquals(APP, RunConfigRouting.pick(List.of(UTIL, APP), APP, "/w/app"));
    }

    /**
     * A working directory with no open file under it says nothing about where the sources are — it may be an
     * output folder or a sandbox — so it must not veto an otherwise usable candidate.
     */
    @Test
    void anUnmatchedWorkingDirectoryFallsBackRatherThanRefusing() {
        assertEquals(APP, RunConfigRouting.pick(List.of(APP), APP, "/tmp/sandbox"));
        assertEquals(APP, RunConfigRouting.pick(List.of(APP), null, "/tmp/sandbox"));
    }

    /** The one genuinely unresolvable case, so the caller can say precisely that. */
    @Test
    void nothingOpenYieldsNull() {
        assertNull(RunConfigRouting.pick(List.of(), null, ""));
        assertNull(RunConfigRouting.pick(null, null, "/w/app"));
    }

    @Test
    void prefersAnAlreadyManagedFileAmongOtherwiseArbitraryCandidates() {
        // "any other open Java file" is an arbitrary choice; picking one jdtls already has open avoids
        // starting a second server for an unrelated root just to answer one resolveClasspath.
        Path cold = Path.of("/w/a/Cold.java");
        Path warm = Path.of("/w/b/Warm.java");
        assertEquals(warm, RunConfigRouting.pick(List.of(cold, warm), null, "", warm::equals));
    }

    @Test
    void managedPreferenceNeverOverridesTheActiveFile() {
        // The active file encodes intent; "managed" does not. Correctness comes from the caller opening
        // whatever is returned (LspCoordinator.ensureManaged), so this stays a tie-break only.
        Path active = Path.of("/w/a/Active.java");
        Path warm = Path.of("/w/b/Warm.java");
        assertEquals(active, RunConfigRouting.pick(List.of(active, warm), active, "", warm::equals));
    }

    @Test
    void managedPreferenceStaysInsideTheConfiguredWorkingDirectory() {
        // A managed file in the WRONG project must not win over an unmanaged one in the right project.
        Path outsideWarm = Path.of("/other/Warm.java");
        Path insideCold = Path.of("/w/proj/Cold.java");
        assertEquals(
                insideCold,
                RunConfigRouting.pick(List.of(outsideWarm, insideCold), null, "/w/proj", outsideWarm::equals));
    }
}
