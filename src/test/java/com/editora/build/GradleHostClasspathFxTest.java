package com.editora.build;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.editora.process.ExecTargets;
import com.editora.vfs.EmbeddedSftpFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxToolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A Gradle module's classpath worked out on the host it is deployed to: the init script goes to the host's
 * cache, the build's own wrapper runs there with the module's task, and the classpath it writes is read back.
 * A stand-in {@code gradlew} plays Gradle (no Gradle download on a CI runner).
 */
@Tag("fx")
@DisabledOnOs(OS.WINDOWS)
class GradleHostClasspathFxTest {

    @BeforeAll
    static void bootToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @TempDir
    Path base;

    private EmbeddedSftpFixture sftp;

    @BeforeEach
    void setUp() throws Exception {
        ExecTargets.setRemoteEnabled(() -> true);
        sftp = EmbeddedSftpFixture.startWithShell(base);
        Files.createDirectories(sftp.serverPath("build-root/app"));
    }

    @AfterEach
    void tearDown() throws Exception {
        sftp.close();
        ExecTargets.setRemoteEnabled(null);
    }

    private void wrapper(String body) throws Exception {
        Files.writeString(sftp.serverPath("build-root/gradlew"), "#!/bin/sh\n" + body);
    }

    @Test
    void theWrapperRunsTheModulesTaskOnTheHostAndItsClasspathComesBack() throws Exception {
        wrapper("""
                out=; init=; prev=
                for a in "$@"; do
                  case "$a" in -PeditoraClasspathOut=*) out=${a#-PeditoraClasspathOut=} ;; esac
                  [ "$prev" = -I ] && init=$a
                  prev=$a
                done
                echo "$@" > args.txt
                cat "$init" > init-copy.gradle
                printf '/h/lib/a.jar:/h/app/build/classes/java/main' > "$out"
                """);
        Path root = sftp.remotePath("build-root");
        String[] error = new String[1];
        List<String> cp = GradleHostClasspath.resolve(root, root.resolve("app"), "/opt/jdk", error);

        assertEquals(List.of("/h/lib/a.jar", "/h/app/build/classes/java/main"), cp, String.valueOf(error[0]));
        String args = Files.readString(sftp.serverPath("build-root/args.txt"));
        assertTrue(args.strip().endsWith(":app:editoraClasspath"), args);
        assertTrue(args.contains("-Dorg.gradle.configuration-cache=false"), args);
        assertEquals(
                GradleHostClasspath.INIT_SCRIPT,
                Files.readString(sftp.serverPath("build-root/init-copy.gradle")),
                "the init script reached the host's cache");
        try (var left = Files.list(sftp.serverPath("build-root/app/build"))) {
            assertEquals(0, left.count(), "the classpath file is removed after reading");
        }
    }

    @Test
    void aFailedBuildSaysWhatWentWrong() throws Exception {
        wrapper("""
                echo 'FAILURE: Build failed with an exception.' >&2
                echo '* What went wrong:' >&2
                echo "Plugin [id: 'nope'] was not found" >&2
                exit 1
                """);
        Path root = sftp.remotePath("build-root");
        String[] error = new String[1];
        assertNull(GradleHostClasspath.resolve(root, root, "", error));
        assertEquals("Plugin [id: 'nope'] was not found", error[0]);
    }
}
