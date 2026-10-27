package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;

import com.editora.install.InstallCatalog;
import com.editora.process.ProcessRunner;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The banners on an XML file in a real window (XML review A5, A6, A17/C12, C13): with language servers off the
 * first XML file offers to turn them on, once; with them on and lemminx missing the banner says what the server
 * adds and what Install downloads, and "Not now" holds for every XML file of the window; it is not offered while
 * the XML server is switched off; and a configured XML command that is missing leads to Settings, not to an
 * Install that would overwrite it.
 */
@Tag("fx")
class XmlBannersFxTest {

    @TempDir
    Path work;

    /** The install banner's title, or null while it is not shown. */
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

    /** Waits until the XML server's detection has answered (missing), then lets the banner decide. */
    private static void awaitXmlDetected(MainController c) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!FxTestSupport.callOnFx(() -> c.lspCoordinator.isServerMissing("xml")) && System.nanoTime() < deadline) {
            FxTestSupport.drainFx();
        }
        assertTrue(FxTestSupport.callOnFx(() -> c.lspCoordinator.isServerMissing("xml")), "detection answered");
        for (int i = 0; i < 6; i++) {
            FxTestSupport.drainFx();
        }
    }

    private static Button button(MainController c, String text) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            for (Node n :
                    c.stage.getScene().getRoot().lookup(".lsp-install-bar").lookupAll(".button")) {
                if (n instanceof Button b && text.equals(b.getText())) {
                    return b;
                }
            }
            throw new AssertionError("no button " + text);
        });
    }

    private static List<String> buttons(MainController c) throws Exception {
        return FxTestSupport.callOnFx(
                () -> c.stage.getScene().getRoot().lookup(".lsp-install-bar").lookupAll(".button").stream()
                        .filter(n -> n instanceof Button)
                        .map(n -> ((Button) n).getText())
                        .toList());
    }

    private Path xml(String name) throws Exception {
        return Files.writeString(Files.createDirectories(work.resolve("p")).resolve(name), "<note/>\n");
    }

    private static void assumeNoLemminxOnPath() {
        assumeTrue(
                ProcessRunner.resolveExecutable(List.of("lemminx")).get(0).equals("lemminx"),
                "lemminx is on this machine's PATH");
    }

    @Test
    void theFirstXmlFileOffersToTurnOnLanguageServersOnce() throws Exception {
        FxTestSupport.bootToolkit();
        Path first = xml("a.xml");
        Path second = xml("b.xml");
        try (FxWindowFixture fx = FxWindowFixture.create(Files.createDirectories(work.resolve("cfg")), shared -> {})) {
            MainController c = fx.controller;
            FxTestSupport.runOnFx(() -> c.openAndNavigate(first, 1));
            assertEquals(tr("install.banner.enableXml"), waitForBanner(c, tr("install.banner.enableXml")));
            FxTestSupport.runOnFx(button(c, tr("install.banner.dismiss"))::fire);

            FxTestSupport.runOnFx(() -> c.openAndNavigate(second, 1));
            for (int i = 0; i < 6; i++) {
                FxTestSupport.drainFx();
            }
            assertNull(bannerTitle(c), "asked once per window, not on every XML file");
        }
    }

    @Test
    void theInstallBannerSaysWhatItBringsAndNotNowHoldsForEveryXmlFile() throws Exception {
        assumeNoLemminxOnPath();
        FxTestSupport.bootToolkit();
        Path note = xml("note.xml");
        Path icon = Files.writeString(work.resolve("p/icon.svg"), "<svg xmlns=\"http://www.w3.org/2000/svg\"/>\n");
        try (FxWindowFixture fx = FxWindowFixture.create(Files.createDirectories(work.resolve("cfg")), shared -> {
            shared.getSettings().setLspSupport(true);
        })) {
            MainController c = fx.controller;
            FxTestSupport.runOnFx(() -> c.openAndNavigate(note, 1));
            String expected = tr(
                    "install.banner.xmlServer",
                    InstallCatalog.LEMMINX_SERVER_VERSION,
                    InstallCatalog.LEMMINX_DOWNLOAD_MB);
            assertEquals(expected, waitForBanner(c, expected));
            FxTestSupport.runOnFx(button(c, tr("install.banner.dismiss"))::fire);

            FxTestSupport.runOnFx(() -> c.openAndNavigate(icon, 1));
            awaitXmlDetected(c);
            assertNull(bannerTitle(c), "\"Not now\" holds for the window's other XML files (A17/C12)");
        }
    }

    @Test
    void anXmlServerThatIsSwitchedOffIsNotOffered() throws Exception {
        assumeNoLemminxOnPath();
        FxTestSupport.bootToolkit();
        Path note = xml("note.xml");
        try (FxWindowFixture fx = FxWindowFixture.create(Files.createDirectories(work.resolve("cfg")), shared -> {
            shared.getSettings().setLspSupport(true);
            shared.getSettings().setXmlLspEnabled(false);
        })) {
            MainController c = fx.controller;
            FxTestSupport.runOnFx(() -> c.openAndNavigate(note, 1));
            awaitXmlDetected(c);
            assertNull(bannerTitle(c), "XML switched off: no Install offer (A5)");
        }
    }

    @Test
    void aMissingCustomXmlCommandLeadsToSettingsNotToInstall() throws Exception {
        FxTestSupport.bootToolkit();
        Path note = xml("note.xml");
        String command = work.resolve("opt/my-lemminx/bin/lemminx").toString();
        try (FxWindowFixture fx = FxWindowFixture.create(Files.createDirectories(work.resolve("cfg")), shared -> {
            shared.getSettings().setLspSupport(true);
            shared.getSettings().setXmlLspCommand(command);
        })) {
            MainController c = fx.controller;
            FxTestSupport.runOnFx(() -> c.openAndNavigate(note, 1));
            String expected = tr("install.banner.commandMissing", command);
            assertEquals(expected, waitForBanner(c, expected));
            button(c, tr("install.banner.openSettings")); // Settings…, which leaves the user's command as it is
            assertTrue(!buttons(c).contains(tr("install.banner.install")), "no Install that would overwrite it (A6)");
        }
    }
}
