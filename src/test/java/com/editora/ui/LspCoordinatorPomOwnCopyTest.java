package com.editora.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.editora.config.Settings;
import com.editora.install.InstallCatalog;
import com.editora.install.MavenServerInstall;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Review 2, A3: a blank pom server command launches Editora's own complete bundle, on the Maven JDK, so "Reset to
 * default" no longer disconnects it.
 */
class LspCoordinatorPomOwnCopyTest {

    @TempDir
    Path config;

    @Test
    void aBlankPomCommandLaunchesTheOwnBundleOnTheMavenJdk() throws IOException {
        assertEquals("", LspCoordinator.withOwnCopy("maven-pom", "", config, "/jdk"), "nothing installed");
        Path dir = Files.createDirectories(MavenServerInstall.folder(config));
        for (String jar : List.of(
                "org.eclipse.lemminx-1.jar",
                "lemminx-maven-1.jar",
                "guava-1.jar",
                "commons-lang3-1.jar",
                "jsoup-1.jar")) {
            Files.createFile(dir.resolve(jar));
        }
        String own = InstallCatalog.jvmClasspathCommand(dir, InstallCatalog.LEMMINX_MAIN_CLASS);
        assertEquals(own, LspCoordinator.withOwnCopy("maven-pom", "", config, ""), "no Maven JDK: java on PATH");
        String onJdk = LspCoordinator.withOwnCopy("maven-pom", " ", config, "/jdk");
        assertEquals(
                com.editora.lsp.JvmServerCommand.withJdk(own, "/jdk"),
                onJdk,
                "the Maven JDK, as a stored command gets");
        assertEquals(
                "my-server", LspCoordinator.withOwnCopy("maven-pom", "my-server", config, "/jdk"), "the user's own");

        Settings s = new Settings();
        s.setMavenPomLspCommand("");
        s.setMavenJdkHome("");
        assertEquals(own, LspCoordinator.launchCommandsForAllServers(s, config).get("maven-pom"));
    }
}
