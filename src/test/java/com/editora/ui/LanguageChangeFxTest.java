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
        FxTestSupport.runOnFx(() -> {
            b.setRunEnabled(true);
            b.setShellRunEnabled(true); // the shell Run gate itself is not what this test is about
        });
        assertFalse(FxTestSupport.callOnFx(b::isRunnable), "plain text is not runnable");

        FxTestSupport.runOnFx(() -> b.setLanguageOverride("shell"));

        assertTrue(FxTestSupport.callOnFx(b::isRunnable), "picked as Shell: the Run glyph shows straight away");
    }
}
