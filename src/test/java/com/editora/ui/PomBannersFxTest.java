package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;

import com.editora.install.InstallCatalog;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pom.xml server's banner in a real window (review 2, A1/E2): asked before the "already served" check, so a pom
 * the plain XML server serves still hears about it; a missing command of the user's own leads to Settings, not to an
 * Install that would overwrite it; and for a pom in a folder that is not trusted the offer says the server runs only
 * in trusted folders (review 3, A28).
 */
@Tag("fx")
class PomBannersFxTest {

    @TempDir
    Path work;

    private static final String POM =
            "<project><modelVersion>4.0.0</modelVersion><artifactId>a</artifactId></project>\n";

    private static String bannerTitle(MainController c) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            Node bar = c.stage.getScene().getRoot().lookup(".lsp-install-bar");
            if (bar == null || !bar.isVisible() || bar.getScene() == null) {
                return null;
            }
            for (Node n : bar.lookupAll(".label")) {
                if (n instanceof Label l
                        && l.getText() != null
                        && !l.getText().isBlank()
                        && !(l.getParent() instanceof Button)) {
                    return l.getText();
                }
            }
            return null;
        });
    }

    private static String waitForBanner(MainController c, String expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        String now = bannerTitle(c);
        while (!expected.equals(now) && System.nanoTime() < deadline) {
            FxTestSupport.drainFx();
            now = bannerTitle(c);
        }
        return now;
    }

    private static void awaitPomDetected(MainController c) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!FxTestSupport.callOnFx(() -> c.lspCoordinator.isServerMissing("maven-pom"))
                && System.nanoTime() < deadline) {
            FxTestSupport.drainFx();
        }
        assertTrue(FxTestSupport.callOnFx(() -> c.lspCoordinator.isServerMissing("maven-pom")), "detection answered");
        for (int i = 0; i < 6; i++) {
            FxTestSupport.drainFx();
        }
    }

    private static List<String> buttons(MainController c) throws Exception {
        return FxTestSupport.callOnFx(
                () -> c.stage.getScene().getRoot().lookup(".lsp-install-bar").lookupAll(".button").stream()
                        .filter(n -> n instanceof Button)
                        .map(n -> ((Button) n).getText())
                        .toList());
    }

    private Path pom() throws Exception {
        return Files.writeString(Files.createDirectories(work.resolve("p")).resolve("pom.xml"), POM);
    }

    @Test
    void aPomOffersTheMavenServerWithWhatItBringsAndDownloads() throws Exception {
        FxTestSupport.bootToolkit();
        Path pom = pom();
        try (FxWindowFixture fx = FxWindowFixture.create(Files.createDirectories(work.resolve("cfg")), shared -> {
            shared.getSettings().setLspSupport(true);
            shared.getSettings().setXmlLspEnabled(false); // only the pom server's own offer is in play
            shared.getTrustStore().trust(work.resolve("p"));
        })) {
            MainController c = fx.controller;
            FxTestSupport.runOnFx(() -> c.openAndNavigate(pom, 1));
            String expected = tr(
                    "install.banner.mavenPomServer",
                    InstallCatalog.LEMMINX_MAVEN_VERSION,
                    InstallCatalog.LEMMINX_MAVEN_DOWNLOAD_MB);
            assertEquals(expected, waitForBanner(c, expected));
            assertTrue(buttons(c).contains(tr("install.banner.install")));
        }
    }

    @Test
    void aMissingCustomPomCommandLeadsToSettingsNotToInstall() throws Exception {
        FxTestSupport.bootToolkit();
        Path pom = pom();
        String command =
                work.resolve("opt/jdk/bin/java") + " -cp /opt/lemminx-maven/* org.eclipse.lemminx.XMLServerLauncher";
        try (FxWindowFixture fx = FxWindowFixture.create(Files.createDirectories(work.resolve("cfg")), shared -> {
            shared.getSettings().setLspSupport(true);
            shared.getSettings().setXmlLspEnabled(false);
            shared.getSettings().setMavenPomLspCommand(command);
            shared.getTrustStore().trust(work.resolve("p"));
        })) {
            MainController c = fx.controller;
            FxTestSupport.runOnFx(() -> c.openAndNavigate(pom, 1));
            String expected = tr("install.banner.commandMissing", command);
            assertEquals(expected, waitForBanner(c, expected));
            assertFalse(buttons(c).contains(tr("install.banner.install")), "no Install that would overwrite it");
        }
    }

    /**
     * XML/pom review 3, A28: one offer that says both — the server is not installed, and once installed it runs only
     * in trusted folders — with Install; no trust prompt "for Maven-aware editing" while the server is missing.
     */
    @Test
    void aPomInAFolderThatIsNotTrustedIsOfferedTheServerWithTheTrustRule() throws Exception {
        FxTestSupport.bootToolkit();
        Path pom = pom();
        try (FxWindowFixture fx = FxWindowFixture.create(Files.createDirectories(work.resolve("cfg")), shared -> {
            shared.getSettings().setLspSupport(true);
            shared.getSettings().setXmlLspEnabled(false);
        })) {
            MainController c = fx.controller;
            FxTestSupport.runOnFx(() -> c.openAndNavigate(pom, 1));
            awaitPomDetected(c);
            String expected = tr(
                    "install.banner.mavenPomServerUntrusted",
                    InstallCatalog.LEMMINX_MAVEN_VERSION,
                    InstallCatalog.LEMMINX_MAVEN_DOWNLOAD_MB);
            assertEquals(expected, waitForBanner(c, expected));
            assertTrue(buttons(c).contains(tr("install.banner.install")));
            assertEquals(null, FxTestSupport.callOnFx(() -> c.activeBuffer().fileNoticeText()), "no trust notice");
        }
    }
}
