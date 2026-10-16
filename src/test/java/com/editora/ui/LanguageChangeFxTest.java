package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;

import javafx.scene.control.Label;

import com.editora.command.CommandRegistry;
import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A buffer's language can change after it is opened: picked in the status bar, or by a shebang typed into an
 * untitled or extension-less file. Everything that hangs off the language has to follow — the status bar's
 * language segment and the Run glyph used to keep showing the old answer until the tab was reopened.
 */
@Tag("fx")
class LanguageChangeFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @TempDir
    Path dir;

    private FxWindowFixture fx;

    @AfterEach
    void close() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    private String statusLanguage() throws Exception {
        Label label = FxTestSupport.field(FxTestSupport.field(fx.controller, "statusBar"), "language");
        return FxTestSupport.callOnFx(label::getText);
    }

    private EditorBuffer active() throws Exception {
        return FxTestSupport.callOnFx(() -> fx.controller.activeBuffer());
    }

    /** Waits (bounded) until the active buffer's language is {@code language}. */
    private void awaitLanguage(String language) throws Exception {
        for (int i = 0;
                i < 200
                        && !language.equals(FxTestSupport.callOnFx(
                                () -> fx.controller.activeBuffer().getLanguage()));
                i++) {
            Thread.sleep(25);
            FxTestSupport.drainFx();
        }
        assertEquals(
                language,
                FxTestSupport.callOnFx(() -> fx.controller.activeBuffer().getLanguage()));
    }

    @Test
    void aShebangTypedIntoANewBufferUpdatesTheStatusBar() throws Exception {
        fx = FxWindowFixture.create(dir.resolve("cfg"), shared -> {});
        FxTestSupport.runOnFx(() -> {
            CommandRegistry registry = FxTestSupport.field(fx.controller, "registry");
            registry.run("file.new");
        });
        FxTestSupport.drainFx();
        assertEquals(FxTestSupport.callOnFx(() -> fx.controller.activeBuffer().getLanguage()), "plaintext");
        String plain = statusLanguage();

        FxTestSupport.runOnFx(() -> fx.controller.activeBuffer().getArea().replaceText("#!/bin/bash\necho hi\n"));
        awaitLanguage("shell");
        FxTestSupport.drainFx();

        assertEquals("Shell", statusLanguage(), "was still \"" + plain + "\" after the shebang made it shell");
    }

    /** Picking Shell for an extension-less script gives it the Run glyph now, not on its next edit. */
    @Test
    void pickingShellMakesAScriptRunnableAtOnce() throws Exception {
        fx = FxWindowFixture.create(dir.resolve("cfg"), shared -> {});
        Path script = dir.resolve("deploy");
        Files.writeString(script, "echo hi\n");
        FxTestSupport.runOnFx(() -> fx.controller.openAndNavigate(script, 1));
        for (int i = 0;
                i < 200
                        && FxTestSupport.callOnFx(() -> fx.controller.activeBuffer() == null
                                || fx.controller.activeBuffer().isLoading());
                i++) {
            Thread.sleep(25);
        }
        EditorBuffer b = active();
        FxTestSupport.runOnFx(() -> b.setRunEnabled(true));
        assertFalse(FxTestSupport.callOnFx(b::isRunnable), "plain text is not runnable");

        FxTestSupport.runOnFx(() -> b.setLanguageOverride("shell"));

        assertTrue(FxTestSupport.callOnFx(b::isRunnable), "picked as Shell: the Run glyph shows straight away");
    }
    /** An extension-less file that names its language in a vim or emacs modeline opens as that language. */
    @Test
    void aModelineInAnExtensionlessFileSetsTheLanguage() throws Exception {
        fx = FxWindowFixture.create(dir.resolve("cfg"), shared -> {});
        Path script = dir.resolve("setup-env");
        Files.writeString(script, "# vim: ft=sh\nexport PATH=\"$HOME/bin:$PATH\"\n");
        FxTestSupport.runOnFx(() -> fx.controller.openAndNavigate(script, 1));
        awaitLanguage("shell");
        FxTestSupport.drainFx();
        assertEquals("Shell", statusLanguage());
    }
    /** The SVG path data of the first glyph under {@code node}, or "" — what tells two file-type icons apart. */
    private static String glyph(javafx.scene.Node node) {
        if (node instanceof javafx.scene.shape.SVGPath svg) {
            return svg.getContent();
        }
        if (node instanceof javafx.scene.Parent parent) {
            for (javafx.scene.Node child : parent.getChildrenUnmodifiable()) {
                String g = glyph(child);
                if (!g.isEmpty()) {
                    return g;
                }
            }
        }
        return "";
    }

    /** The glyph of the active tab's file-type icon. */
    private String activeTabIcon() throws Exception {
        return FxTestSupport.callOnFx(() -> {
            javafx.scene.control.Tab tab = (javafx.scene.control.Tab) FxTestSupport.call(
                    fx.controller, "tabFor", new Class<?>[] {EditorBuffer.class}, fx.controller.activeBuffer());
            javafx.scene.Node icon = tab.getGraphic().lookup(".tab-file-icon");
            return glyph(icon);
        });
    }

    /**
     * An extension-less script's tab shows the shell icon once its shebang makes it shell — the icon followed the
     * file name only, so every such script had the generic page.
     */
    @Test
    void theTabIconFollowsTheResolvedLanguage() throws Exception {
        fx = FxWindowFixture.create(dir.resolve("cfg"), shared -> {});
        String shellGlyph = FxTestSupport.callOnFx(() -> glyph(FileIcons.forFileName("x.sh")));
        String genericGlyph = FxTestSupport.callOnFx(() -> glyph(FileIcons.forFileName("notes")));
        Path script = dir.resolve("deploy");
        Files.writeString(script, "#!/bin/bash\necho hi\n");
        FxTestSupport.runOnFx(() -> fx.controller.openAndNavigate(script, 1));
        awaitLanguage("shell");
        FxTestSupport.drainFx();
        assertEquals(shellGlyph, activeTabIcon(), "a shebang script's tab has the shell icon");

        // …and a language picked later updates it.
        FxTestSupport.runOnFx(() -> fx.controller.activeBuffer().setLanguageOverride("plaintext"));
        FxTestSupport.drainFx();
        assertEquals(genericGlyph, activeTabIcon(), "back to plain text: the generic page");
    }
}
