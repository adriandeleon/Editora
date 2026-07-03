package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;

import com.editora.editor.EditorBuffer;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Full-app-path repro for Auto Rename Tag: a real window ({@link FxWindowFixture}), a real {@code .html}
 * file opened through {@link MainController} (language detection from the file name, view settings pushed
 * by {@code applyViewSettings}), and a real {@code KEY_TYPED} event — the paired close tag must rename.
 * This is the closest headless equivalent of typing in the running app.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AutoRenameTagWindowFxTest {

    private FxWindowFixture fx;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
    }

    @AfterAll
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    @Test
    void typingInAnOpenedHtmlFileRenamesThePairedTag() throws Exception {
        Path file = Files.createTempFile("editora-tag-test", ".html");
        Files.writeString(file, "<div>text</div>");
        try {
            FxTestSupport.runOnFx(() -> FxTestSupport.call(fx.controller, "openPath", new Class[] {Path.class}, file));
            EditorBuffer buffer = FxTestSupport.callOnFx(
                    () -> (EditorBuffer) FxTestSupport.call(fx.controller, "activeBuffer", new Class[] {}));
            assertNotNull(buffer, "the html file opened into a buffer");
            assertEquals("html", FxTestSupport.callOnFx(buffer::getLanguage), "language detected from .html");

            FxTestSupport.runOnFx(() -> {
                CodeArea area = buffer.getFocusedArea();
                area.moveTo(4);
                javafx.event.Event.fireEvent(
                        area,
                        new javafx.scene.input.KeyEvent(
                                javafx.scene.input.KeyEvent.KEY_TYPED,
                                "x",
                                "x",
                                javafx.scene.input.KeyCode.UNDEFINED,
                                false,
                                false,
                                false,
                                false));
            });
            assertEquals("<divx>text</divx>", FxTestSupport.callOnFx(buffer::getContent));
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
