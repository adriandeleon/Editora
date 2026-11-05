package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.text.Text;

import com.editora.install.InstallCatalog;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JS review B113: when the TypeScript server is there but Editora does not start it because the only TypeScript beside
 * it is a TypeScript 7 (no tsserver), the JavaScript file's install banner offers Editora's own TypeScript — before,
 * only a status line said so, and at 1300 px it was cut off before its action. "Not now" holds for the window, and
 * the install-prompts setting turns the banner off, as for a missing server.
 */
@Tag("fx")
@DisabledOnOs(OS.WINDOWS) // the fake server is an executable script
class TypeScriptInstallBannerFxTest {

    @TempDir
    Path work;

    /** A global npm layout whose typescript-language-server has a TypeScript 7.0.2 (no tsserver) beside it. */
    private String typeScriptSevenServer() throws Exception {
        Path global = work.resolve("npm/lib/node_modules");
        Path cli = global.resolve("typescript-language-server/lib/cli.mjs");
        Files.createDirectories(cli.getParent());
        Files.writeString(cli, "#!/bin/sh\nexit 3\n");
        assertTrue(cli.toFile().setExecutable(true));
        Files.createDirectories(global.resolve("typescript/lib"));
        Files.writeString(global.resolve("typescript/package.json"), "{\"version\": \"7.0.2\"}");
        return cli + " --stdio";
    }

    private Path app(String name) throws Exception {
        Path root = Files.createDirectories(work.resolve("app"));
        Files.writeString(root.resolve("package.json"), "{}");
        return Files.writeString(root.resolve(name), "console.log(1);\n");
    }

    private static @Nullable String bannerTitle(MainController c) throws Exception {
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

    private static @Nullable String waitForBanner(MainController c, String expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        String now = bannerTitle(c);
        while (!expected.equals(now) && System.nanoTime() < deadline) {
            FxTestSupport.drainFx();
            now = bannerTitle(c);
        }
        return now;
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

    private static void open(MainController c, Path file) throws Exception {
        FxTestSupport.runOnFx(() -> c.openAndNavigate(file, 1));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline
                && !FxTestSupport.callOnFx(() ->
                        c.activeBuffer() != null && file.equals(c.activeBuffer().getPath()))) {
            FxTestSupport.drainFx();
        }
    }

    /** The status echo's text and the text it draws at {@code width} px, which ends in "…" when it is cut off. */
    private static String[] echoAt(MainController c, double width) throws Exception {
        FxTestSupport.drainFx(); // the new text is applied
        return FxTestSupport.callOnFx(() -> {
            // Lay the window's content out at exactly {@code width} within this one call rather than sizing the
            // stage: in a full test run the stage kept 1000 px, so the check depended on the run, not the text.
            javafx.scene.layout.Region root =
                    (javafx.scene.layout.Region) c.stage.getScene().getRoot();
            root.applyCss();
            root.resize(width, root.getHeight());
            root.layout();
            Label echo = (Label) root.lookup(".status-message");
            Text drawn = (Text) echo.lookup(".text");
            assertEquals(width, root.getWidth(), 0.5);
            return new String[] {echo.getText(), drawn == null ? echo.getText() : drawn.getText()};
        });
    }

    @Test
    void typescriptSevenOffersEditorasOwnTypescriptAndTheStatusLineFitsAt1300() throws Exception {
        FxTestSupport.bootToolkit();
        String server = typeScriptSevenServer();
        Path main = app("main.js");
        Path other = app("other.js");
        try (FxWindowFixture fx = FxWindowFixture.create(Files.createDirectories(work.resolve("cfg")), shared -> {
            shared.getSettings().setLspSupport(true);
            shared.getSettings().setTypescriptLspCommand(server);
        })) {
            MainController c = fx.controller;
            open(c, main);
            String expected = tr(
                    "install.banner.typeScriptNoTsserver",
                    "7.0.2",
                    InstallCatalog.TYPESCRIPT_VERSION,
                    InstallCatalog.TYPESCRIPT_LS_VERSION);
            assertEquals(expected, waitForBanner(c, expected));
            assertTrue(
                    buttons(c).contains(tr("install.banner.install")),
                    buttons(c).toString());

            // the refusal's status line, action first, is drawn whole at 1300 px in every language; it was cut off
            // before its action ("… has no tsserver. Install TypeScri...")
            try {
                for (String locale : List.of("en", "de", "es", "fr", "it", "pt")) {
                    com.editora.i18n.Messages.init(locale);
                    for (String key : List.of("status.lsp.tsSdkWithoutTsserver", "status.lsp.tsNoSdk")) {
                        String said = tr(key, "7.0.2");
                        FxTestSupport.runOnFx(() -> c.setError(said));
                        String[] echo = echoAt(c, 1300);
                        assertEquals(said, echo[0]);
                        assertEquals(said, echo[1], locale + " " + key + " is cut off at 1300 px");
                    }
                }
            } finally {
                com.editora.i18n.Messages.init("en");
            }

            // "Not now" holds for every JavaScript file of this window
            FxTestSupport.runOnFx(button(c, tr("install.banner.dismiss"))::fire);
            assertNull(bannerTitle(c));
            open(c, other);
            for (int i = 0; i < 6; i++) {
                FxTestSupport.drainFx();
            }
            FxTestSupport.runOnFx(() -> c.installPrompts.maybeOfferInstall(c.activeBuffer()));
            assertNull(bannerTitle(c), "dismissed for the window");
        }
    }

    @Test
    void withInstallPromptsOffThereIsNoBanner() throws Exception {
        FxTestSupport.bootToolkit();
        String server = typeScriptSevenServer();
        Path main = app("main.js");
        try (FxWindowFixture fx = FxWindowFixture.create(Files.createDirectories(work.resolve("cfg")), shared -> {
            shared.getSettings().setLspSupport(true);
            shared.getSettings().setLspInstallPrompts(false);
            shared.getSettings().setTypescriptLspCommand(server);
        })) {
            MainController c = fx.controller;
            open(c, main);
            // the refusal is what would raise it: wait until the server has been refused
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (System.nanoTime() < deadline
                    && FxTestSupport.callOnFx(() -> c.lspCoordinator.noTypeScriptFor(c.activeBuffer())) == null) {
                FxTestSupport.drainFx();
            }
            assertEquals(
                    "7.0.2",
                    FxTestSupport.callOnFx(() -> c.lspCoordinator.noTypeScriptFor(c.activeBuffer()))
                            .detail());
            FxTestSupport.runOnFx(() -> c.installPrompts.maybeOfferInstall(c.activeBuffer()));
            assertNull(bannerTitle(c));
        }
    }
}
