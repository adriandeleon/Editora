package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;

import com.editora.install.InstallTestAccess;
import com.editora.install.WebServerInstall;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTML review 2 A108: a user whose HTML server is an earlier {@code npm install -g} of vscode-langservers-extracted,
 * which cannot load TypeScript's libraries, is told so on the first HTML file — with Update, which installs Editora's
 * own copy — instead of only in Doctor. Once per window; the user's own command is left alone.
 */
@Tag("fx")
class HtmlOwnCopyBannerFxTest {

    @TempDir
    Path work;

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

    private static List<String> buttons(MainController c) throws Exception {
        return FxTestSupport.callOnFx(
                () -> c.stage.getScene().getRoot().lookup(".lsp-install-bar").lookupAll(".button").stream()
                        .filter(n -> n instanceof Button)
                        .map(n -> ((Button) n).getText())
                        .toList());
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

    private static void open(MainController c, Path file) throws Exception {
        FxTestSupport.runOnFx(() -> c.openAndNavigate(file, 1));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline
                && !FxTestSupport.callOnFx(() ->
                        c.activeBuffer() != null && file.equals(c.activeBuffer().getPath()))) {
            FxTestSupport.drainFx();
        }
    }

    @Test
    void aGlobalCopyWithoutTheLibrariesIsOfferedUpdateOncePerWindow() throws Exception {
        FxTestSupport.bootToolkit();
        Path pkg = InstallTestAccess.webServersLayout(work.resolve("npm-global"), true);
        String global = pkg.resolve("bin/vscode-html-language-server").toString();
        Path site = Files.createDirectories(work.resolve("site"));
        Path index = Files.writeString(site.resolve("index.html"), "<!doctype html>\n<script>document.</script>\n");
        Path about = Files.writeString(site.resolve("about.html"), "<!doctype html>\n<p>About</p>\n");
        List<List<String>> asked = new CopyOnWriteArrayList<>();
        try (FxWindowFixture fx = FxWindowFixture.create(Files.createDirectories(work.resolve("cfg")), shared -> {})) {
            MainController c = fx.controller;
            open(c, index);
            FxTestSupport.runOnFx(() -> {
                // as if `npm install -g` had put that copy on PATH: the default command resolves to it
                c.installPrompts.foreignWebCopy = (argv, config) -> {
                    asked.add(argv);
                    return WebServerInstall.foreignCopyWithoutLibs(List.of(global, "--stdio"), config);
                };
                fx.shared.getSettings().setLspSupport(true); // language servers on, no server started here
                c.lspCoordinator.setServerAvailableForTest("html", true);
                c.installPrompts.maybeOfferInstall(c.activeBuffer());
            });
            assertEquals(tr("install.banner.htmlNoTsLibs"), bannerTitle(c));
            assertEquals(1, asked.size());
            assertEquals("vscode-html-language-server", asked.get(0).get(0), "the default command, not a custom one");
            assertTrue(
                    buttons(c).contains(tr("install.banner.update")), buttons(c).toString());

            FxTestSupport.runOnFx(() -> c.installPrompts.maybeOfferInstall(c.activeBuffer()));
            assertEquals(tr("install.banner.htmlNoTsLibs"), bannerTitle(c), "it stays on its tab until answered");

            FxTestSupport.runOnFx(button(c, tr("install.banner.dismiss"))::fire);
            assertNull(bannerTitle(c));
            open(c, about);
            FxTestSupport.runOnFx(() -> c.installPrompts.maybeOfferInstall(c.activeBuffer()));
            assertNull(bannerTitle(c), "said once in this window");
            assertEquals(1, asked.size(), "the files are read once");
        }
    }

    @Test
    void theUsersOwnCommandIsNotOfferedUpdate() throws Exception {
        FxTestSupport.bootToolkit();
        Path pkg = InstallTestAccess.webServersLayout(work.resolve("npm-global"), true);
        String global = pkg.resolve("bin/vscode-html-language-server").toString();
        Path index = Files.writeString(
                Files.createDirectories(work.resolve("site")).resolve("index.html"), "<!doctype html>\n<p>x</p>\n");
        try (FxWindowFixture fx = FxWindowFixture.create(Files.createDirectories(work.resolve("cfg")), shared -> {
            shared.getSettings().setHtmlLspCommand(global + " --stdio");
        })) {
            MainController c = fx.controller;
            open(c, index);
            FxTestSupport.runOnFx(() -> {
                fx.shared.getSettings().setLspSupport(true);
                c.lspCoordinator.setServerAvailableForTest("html", true);
                c.installPrompts.maybeOfferInstall(c.activeBuffer());
            });
            String title = bannerTitle(c);
            assertTrue(title == null || !title.equals(tr("install.banner.htmlNoTsLibs")), String.valueOf(title));
        }
    }
}
