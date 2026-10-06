package com.editora.build;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BuildTool#repoWrapper} is what the workspace-trust gate keys on, so what it reports must agree
 * exactly with what {@link BuildTool#executable} would actually launch — a wrapper it misses is an ungated
 * execution of repo-controlled code.
 */
class BuildToolRepoWrapperTest {

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");

    /** Only these two ship a wrapper; every other tool must never be gated. */
    private static final Set<BuildTool> WRAPPER_TOOLS = Set.of(BuildTool.MAVEN, BuildTool.GRADLE);

    private static Path wrapper(Path root, String unix, String windows) throws Exception {
        Path f = Files.writeString(root.resolve(WINDOWS ? windows : unix), "#!/bin/sh\necho pwned\n");
        if (!WINDOWS) {
            f.toFile().setExecutable(true);
        }
        return f;
    }

    @Test
    void reportsNoWrapperInABareRoot(@TempDir Path root) {
        for (BuildTool tool : BuildTool.values()) {
            assertNull(tool.repoWrapper(root, WINDOWS), tool + " must report no wrapper in a bare root");
        }
    }

    @Test
    void detectsTheMavenWrapper(@TempDir Path root) throws Exception {
        wrapper(root, "mvnw", "mvnw.cmd");
        assertNotNull(BuildTool.MAVEN.repoWrapper(root, WINDOWS));
        assertNull(BuildTool.GRADLE.repoWrapper(root, WINDOWS), "Gradle must not claim Maven's wrapper");
    }

    @Test
    void detectsTheGradleWrapper(@TempDir Path root) throws Exception {
        wrapper(root, "gradlew", "gradlew.bat");
        assertNotNull(BuildTool.GRADLE.repoWrapper(root, WINDOWS));
        assertNull(BuildTool.MAVEN.repoWrapper(root, WINDOWS), "Maven must not claim Gradle's wrapper");
    }

    @Test
    void onlyMavenAndGradleAreEverGated(@TempDir Path root) throws Exception {
        wrapper(root, "mvnw", "mvnw.cmd");
        wrapper(root, "gradlew", "gradlew.bat");
        for (BuildTool tool : BuildTool.values()) {
            if (!WRAPPER_TOOLS.contains(tool)) {
                assertNull(tool.repoWrapper(root, WINDOWS), tool + " has no wrapper and must never be gated");
            }
        }
    }

    /**
     * The invariant that actually matters: {@code repoWrapper != null} exactly when {@code executable}
     * launches the wrapper. If these ever disagree the gate is either bypassed or fires spuriously.
     */
    @Test
    void repoWrapperAgreesWithWhatExecutableLaunches(@TempDir Path root) throws Exception {
        for (BuildTool tool : WRAPPER_TOOLS) {
            assertNull(tool.repoWrapper(root, WINDOWS));
            List<String> before = tool.executable(root, WINDOWS, "");
            assertEquals(1, before.size());
            assertTrue(
                    before.get(0).equals("mvn") || before.get(0).equals("gradle"),
                    tool + " with no wrapper must fall back to the PATH tool, got " + before);
        }

        wrapper(root, "mvnw", "mvnw.cmd");
        wrapper(root, "gradlew", "gradlew.bat");

        for (BuildTool tool : WRAPPER_TOOLS) {
            Path reported = tool.repoWrapper(root, WINDOWS);
            assertNotNull(reported, tool + " must report the wrapper it is about to launch");
            String argv0 = tool.executable(root, WINDOWS, "").get(0);
            assertEquals(
                    reported.getFileName().toString(),
                    Path.of(argv0).getFileName().toString(),
                    tool + " reported a different wrapper than executable() launches");
        }
    }

    /**
     * A Settings command override does not displace the wrapper (see {@link BuildExecutable#resolve}) — so
     * the gate must still fire when one is set, or a user override would silently disarm it.
     */
    @Test
    void aSettingsOverrideDoesNotDisarmTheGate(@TempDir Path root) throws Exception {
        wrapper(root, "mvnw", "mvnw.cmd");
        assertNotNull(BuildTool.MAVEN.repoWrapper(root, WINDOWS));
        String argv0 = BuildTool.MAVEN.executable(root, WINDOWS, "/usr/bin/mvn").get(0);
        assertTrue(argv0.contains("mvnw"), "the wrapper still wins over an override, so the gate must stay armed");
    }

    /** A present-but-not-executable wrapper is not launched (it falls back), so it must not be gated either. */
    @Test
    void aNonExecutableWrapperIsNotReported(@TempDir Path root) throws Exception {
        if (WINDOWS) {
            return; // the +x rule is Unix-only
        }
        Path f = Files.writeString(root.resolve("mvnw"), "#!/bin/sh\n");
        f.toFile().setExecutable(false);
        assertNull(BuildTool.MAVEN.repoWrapper(root, false));
        assertEquals("mvn", BuildTool.MAVEN.executable(root, false, "").get(0));
    }

    /** A *directory* named mvnw is not a script — must not be reported (nor launched). */
    @Test
    void aDirectoryNamedLikeAWrapperIsNotReported(@TempDir Path root) throws Exception {
        Files.createDirectory(root.resolve(WINDOWS ? "mvnw.cmd" : "mvnw"));
        assertNull(BuildTool.MAVEN.repoWrapper(root, WINDOWS));
    }

    // --- a wrapper above the nearest marker directory (multi-module builds) ---------------------------

    /**
     * A module of a multi-module build has its own pom.xml but not its own wrapper: {@code mvnw} sits once,
     * at the project root. Looking only in the module fell back to whatever {@code mvn} was on PATH.
     */
    @Test
    void aWrapperInAnAncestorIsFoundUpToTheProjectRoot(@TempDir Path project) throws Exception {
        Path module = Files.createDirectories(project.resolve("services").resolve("billing"));
        Path mvnw = wrapper(project, "mvnw", "mvnw.cmd");
        Path gradlew = wrapper(project, "gradlew", "gradlew.bat");

        assertEquals(mvnw, BuildTool.MAVEN.repoWrapper(module, project, WINDOWS));
        assertEquals(gradlew, BuildTool.GRADLE.repoWrapper(module, project, WINDOWS));
        // Launched by absolute path: "./mvnw" would be resolved against the module, where it does not exist.
        assertEquals(
                List.of(mvnw.toAbsolutePath().normalize().toString()),
                BuildTool.MAVEN.executable(module, project, WINDOWS, ""));
        assertEquals(
                List.of(gradlew.toAbsolutePath().normalize().toString()),
                BuildTool.GRADLE.executable(module, project, WINDOWS, ""));

        assertNull(BuildTool.MAVEN.repoWrapper(module, WINDOWS), "the module-only form still looks nowhere else");
        assertEquals(List.of("mvn"), BuildTool.MAVEN.executable(module, WINDOWS, ""));
    }

    @Test
    void theNearestWrapperWinsAndTheSearchStopsAtTheProjectRoot(@TempDir Path outside) throws Exception {
        Path project = Files.createDirectories(outside.resolve("project"));
        Path module = Files.createDirectories(project.resolve("module"));
        wrapper(outside, "mvnw", "mvnw.cmd"); // above the project: a folder the user never opened

        assertNull(BuildTool.MAVEN.repoWrapper(module, project, WINDOWS), "never a wrapper from above the project");
        assertEquals(List.of("mvn"), BuildTool.MAVEN.executable(module, project, WINDOWS, ""));

        Path own = wrapper(module, "mvnw", "mvnw.cmd");
        wrapper(project, "mvnw", "mvnw.cmd");
        assertEquals(own, BuildTool.MAVEN.repoWrapper(module, project, WINDOWS), "the module's own wrapper is nearer");
        if (!WINDOWS) {
            assertEquals(List.of("./mvnw"), BuildTool.MAVEN.executable(module, project, false, ""));
        }
    }

    /** A marker root that is not inside the open project (a file opened from elsewhere) is not searched upward. */
    @Test
    void aRootOutsideTheProjectIsNotSearchedUpward(@TempDir Path base) throws Exception {
        Path project = Files.createDirectories(base.resolve("project"));
        Path elsewhere = Files.createDirectories(base.resolve("elsewhere").resolve("module"));
        wrapper(base.resolve("elsewhere"), "mvnw", "mvnw.cmd");

        assertNull(BuildTool.MAVEN.repoWrapper(elsewhere, project, WINDOWS));
        assertNull(BuildTool.MAVEN.repoWrapper(elsewhere, null, WINDOWS));
    }

    /** The invariant, restated for the ancestor search: the gate and the launch name the same file. */
    @Test
    void theAncestorSearchKeepsRepoWrapperAndExecutableInAgreement(@TempDir Path project) throws Exception {
        Path module = Files.createDirectories(project.resolve("a").resolve("b"));
        if (!WINDOWS) {
            Path notExecutable = Files.writeString(project.resolve("a").resolve("gradlew"), "#!/bin/sh\n");
            notExecutable.toFile().setExecutable(false); // skipped, so the search goes on to the root's
        }
        wrapper(project, "gradlew", "gradlew.bat");
        for (BuildTool tool : BuildTool.values()) {
            Path reported = tool.repoWrapper(module, project, WINDOWS);
            String argv0 = tool.executable(module, project, WINDOWS, "").get(0);
            if (tool == BuildTool.GRADLE) {
                assertNotNull(reported);
                assertEquals(reported.toAbsolutePath().normalize().toString(), argv0);
                assertEquals(project, reported.getParent(), "the non-executable one in a/ was passed over");
            } else {
                assertNull(reported, tool + " ships no wrapper here");
            }
        }
    }
}
