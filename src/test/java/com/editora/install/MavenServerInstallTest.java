package com.editora.install;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link MavenServerInstall}: reading the install folder off the launch command, and what "complete" means. */
class MavenServerInstallTest {

    @TempDir
    Path tmp;

    @Test
    void theClasspathFolderIsReadOffTheStoredCommand() {
        Path dir = tmp.resolve("plugins/lsp/maven");
        String cmd = InstallCatalog.jvmClasspathCommand(dir, InstallCatalog.LEMMINX_MAIN_CLASS);
        List<String> argv = com.editora.run.ProgramArgs.commandLine(cmd);
        assertEquals(Optional.of(dir), MavenServerInstall.classpathDir(argv));
        assertEquals(
                Optional.of(Path.of("C:\\Users\\me\\.editora\\plugins\\lsp\\maven")),
                MavenServerInstall.classpathDir(List.of(
                        "java",
                        "-cp",
                        "C:\\Users\\me\\.editora\\plugins\\lsp\\maven\\*",
                        "org.eclipse.lemminx.XMLServerLauncher")));
        assertEquals(
                Optional.of(Path.of("/opt/lsp")),
                MavenServerInstall.classpathDir(
                        List.of("/usr/lib/jvm/bin/java", "-Xmx1g", "-classpath", "/opt/lsp/*", "Main")));
    }

    @Test
    void aDriveLetterIsNotAClasspathSeparator() {
        assertFalse(MavenServerInstall.severalEntries("C:\\x\\*"));
        assertFalse(MavenServerInstall.severalEntries("/x/*"));
        assertTrue(MavenServerInstall.severalEntries("/x/*" + java.io.File.pathSeparator + "/y/*"));
        assertTrue(MavenServerInstall.severalEntries("C:\\x\\*" + java.io.File.pathSeparator + "D:\\y\\*"));
    }

    @Test
    void aCommandOfAnotherShapeHasNoFolderToCheck() {
        assertEquals(Optional.empty(), MavenServerInstall.classpathDir(List.of()));
        assertEquals(Optional.empty(), MavenServerInstall.classpathDir(null));
        assertEquals(Optional.empty(), MavenServerInstall.classpathDir(List.of("/home/me/bin/lemminx-maven.sh")));
        assertEquals(Optional.empty(), MavenServerInstall.classpathDir(List.of("java", "-jar", "/x/server.jar")));
        assertEquals(
                Optional.empty(),
                MavenServerInstall.classpathDir(List.of("java", "-cp", "/x/a.jar", "Main")),
                "one jar, not a wildcard folder");
        assertEquals(
                Optional.empty(),
                MavenServerInstall.classpathDir(
                        List.of("java", "-cp", "/x/*" + java.io.File.pathSeparator + "/y/*", "Main")),
                "two folders");
        assertEquals(List.of(), MavenServerInstall.missingJars(List.of("/home/me/bin/lemminx-maven.sh")));
    }

    @Test
    void aFolderIsCompleteOnlyWithLemminxTheMavenExtensionAndTheThreeExtraJars() throws IOException {
        Path dir = Files.createDirectories(tmp.resolve("maven"));
        assertEquals(
                List.of(
                        "org.eclipse.lemminx-*.jar",
                        "lemminx-maven-*.jar",
                        "guava-*.jar",
                        "commons-lang3-*.jar",
                        "jsoup-*.jar"),
                MavenServerInstall.missingJars(dir));

        // The upstream bundle as shipped: core + extension + 49 others, none of the `provided` three.
        Files.writeString(dir.resolve("org.eclipse.lemminx-0.30.1.jar"), "");
        Files.writeString(dir.resolve("lemminx-maven-0.12.0.jar"), "");
        Files.writeString(dir.resolve("maven-core-3.9.9.jar"), "");
        assertEquals(List.of("guava-*.jar", "commons-lang3-*.jar", "jsoup-*.jar"), MavenServerInstall.missingJars(dir));
        assertFalse(MavenServerInstall.complete(dir));

        for (InstallCatalog.JvmLspJar jar : InstallCatalog.LEMMINX_MAVEN_EXTRA_JARS) {
            Files.writeString(dir.resolve(jar.fileName()), "");
        }
        assertEquals(List.of(), MavenServerInstall.missingJars(dir));
        assertTrue(MavenServerInstall.complete(dir));

        // A later version of any of them still counts; a stray non-jar with the right prefix does not.
        Files.delete(dir.resolve("guava-33.7.2-jre.jar"));
        Files.writeString(dir.resolve("guava-34.0.0-jre.jar"), "");
        assertTrue(MavenServerInstall.complete(dir));
        Files.delete(dir.resolve("guava-34.0.0-jre.jar"));
        Files.writeString(dir.resolve("guava-34.0.0-jre.jar.part"), "");
        assertEquals(List.of("guava-*.jar"), MavenServerInstall.missingJars(dir));
    }

    @Test
    void aMissingOrWipedFolderLacksEverything() {
        Path gone = tmp.resolve("no-such");
        assertEquals(
                MavenServerInstall.REQUIRED_JAR_PREFIXES.size(),
                MavenServerInstall.missingJars(gone).size());
        assertFalse(MavenServerInstall.complete(gone));
        List<String> argv = com.editora.run.ProgramArgs.commandLine(
                InstallCatalog.jvmClasspathCommand(gone, InstallCatalog.LEMMINX_MAIN_CLASS));
        assertEquals(
                MavenServerInstall.REQUIRED_JAR_PREFIXES.size(),
                MavenServerInstall.missingJars(argv).size());
    }
}
