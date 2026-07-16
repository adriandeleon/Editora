package com.editora.build;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How a project's build wrapper ({@code mvnw}/{@code gradlew}) is turned into an argv prefix. The wrapper is
 * *preferred over everything* — including the Settings command override — so getting its usability wrong
 * can't be recovered from by configuration.
 */
class BuildWrapperTest {

    private static Path wrapper(Path root, String name, boolean executable) throws IOException {
        Path w = root.resolve(name);
        Files.writeString(w, "#!/bin/sh\necho hi\n");
        Set<java.nio.file.attribute.PosixFilePermission> perms = Files.getPosixFilePermissions(w);
        if (executable) {
            perms.add(java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE);
        } else {
            perms.remove(java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE);
            perms.remove(java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE);
            perms.remove(java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE);
        }
        Files.setPosixFilePermissions(w, perms);
        return w;
    }

    @Test
    @DisabledOnOs(OS.WINDOWS) // POSIX permissions
    void anExecutableWrapperIsPreferred(@TempDir Path root) throws IOException {
        wrapper(root, "mvnw", true);
        assertEquals(List.of("./mvnw"), BuildTool.MAVEN.executable(root, false, null));
        wrapper(root, "gradlew", true);
        assertEquals(List.of("./gradlew"), BuildTool.GRADLE.executable(root, false, null));
    }

    /**
     * A wrapper that lost its exec bit — a Windows clone without {@code core.filemode}, an unzip that dropped
     * modes; Maven's own docs tell people to {@code chmod +x mvnw} — used to win anyway, and then every build
     * died with "error=13, Permission denied" instead of falling back to the tool on PATH.
     */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aNonExecutableWrapperFallsBackInsteadOfFailingTheRun(@TempDir Path root) throws IOException {
        wrapper(root, "mvnw", false);
        assertEquals(List.of("mvn"), BuildTool.MAVEN.executable(root, false, null));
        assertEquals(
                List.of("my-mvn", "-B"),
                BuildTool.MAVEN.executable(root, false, "my-mvn -B"),
                "the Settings override is reachable again too");
        wrapper(root, "gradlew", false);
        assertEquals(List.of("gradle"), BuildTool.GRADLE.executable(root, false, null));
    }

    @Test
    void noWrapperUsesTheOverrideThenThePathDefault(@TempDir Path root) {
        assertEquals(List.of("mvn"), BuildTool.MAVEN.executable(root, false, null));
        assertEquals(List.of("mvn"), BuildTool.MAVEN.executable(root, false, "  "));
        assertEquals(List.of("/opt/maven/bin/mvn"), BuildTool.MAVEN.executable(root, false, "/opt/maven/bin/mvn"));
    }

    /**
     * On Windows the wrapper must be absolute: a bare {@code mvnw.cmd} is not on PATH, and is not resolved
     * against the child's working directory either — so it was simply not found.
     */
    @Test
    void theWindowsWrapperIsAbsolute(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("mvnw.cmd"), "@echo off\n");
        List<String> argv = BuildTool.MAVEN.executable(root, true, null);
        assertEquals(1, argv.size());
        assertTrue(Path.of(argv.get(0)).isAbsolute(), "must not be a bare name: " + argv.get(0));
        assertTrue(argv.get(0).endsWith("mvnw.cmd"), argv.get(0));
    }

    @Test
    void aUnixWrapperNameIsNotPickedUpOnWindowsAndViceVersa(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("mvnw"), "#!/bin/sh\n");
        assertEquals(List.of("mvn"), BuildTool.MAVEN.executable(root, true, null), "mvnw is not a Windows wrapper");
        Files.writeString(root.resolve("gradlew.bat"), "@echo off\n");
        assertEquals(
                List.of("gradle"), BuildTool.GRADLE.executable(root, false, null), "gradlew.bat is not a Unix wrapper");
    }

    /**
     * Bun >= 1.2 writes a *text* lockfile named {@code bun.lock}; {@code bun.lockb} is the legacy binary one.
     * Probing only the legacy name meant every modern Bun project fell through to running {@code npm}.
     */
    @Test
    void aModernBunLockfileSelectsBun(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("package.json"), "{\"name\":\"x\",\"scripts\":{\"dev\":\"bun run x\"}}");
        Files.writeString(root.resolve("bun.lock"), "{}\n"); // Bun >= 1.2
        assertEquals(List.of("bun"), BuildTool.NPM.executable(root, false, null));
    }

    @Test
    void theLegacyBinaryBunLockfileStillSelectsBun(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("package.json"), "{\"name\":\"x\"}");
        Files.writeString(root.resolve("bun.lockb"), "\0binary");
        assertEquals(List.of("bun"), BuildTool.NPM.executable(root, false, null));
    }

    @Test
    void noLockfileStillMeansNpm(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("package.json"), "{\"name\":\"x\"}");
        assertEquals(List.of("npm"), BuildTool.NPM.executable(root, false, null));
    }
}
