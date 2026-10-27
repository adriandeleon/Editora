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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A jump to a place near the start of its line shows the line from column 1 (XML review C19): after the view had
 * scrolled right to show the end of a long line, Go to Definition landed with every line's first character cut
 * off. Shared by every jump (definition, search hit, Structure), so it is tested on the shared path.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JumpRevealColumnFxTest {

    private FxWindowFixture fx;
    private Path file;
    private EditorBuffer buffer;

    @BeforeAll
    void open() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create(
                Files.createTempDirectory("editora-fx-test"),
                shared -> shared.getSettings().setWordWrap(false));
        Path dir = Files.createTempDirectory("editora-jump-col");
        file = dir.resolve("note.xml");
        StringBuilder text = new StringBuilder("<?xml version=\"1.0\"?>\n<note");
        for (int i = 0; i < 40; i++) {
            text.append(" attribute").append(i).append("=\"value ").append(i).append('"');
        }
        text.append(">\n");
        for (int i = 0; i < 80; i++) {
            text.append("  <to>Tove ").append(i).append("</to>\n");
        }
        text.append("</note>\n");
        Files.writeString(file, text.toString());
        FxTestSupport.runOnFx(() -> FxTestSupport.call(
                FxTestSupport.field(fx.controller, "fileWorkflows"), "openPath", new Class<?>[] {Path.class}, file));
        buffer = FxTestSupport.callOnFx(
                () -> (EditorBuffer) FxTestSupport.call(fx.controller, "activeBuffer", new Class<?>[] {}));
        assertNotNull(buffer, "the file opened");
        settle();
    }

    @AfterAll
    void close() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    @Test
    void aJumpNearTheLineStartScrollsBackToColumnOne() throws Exception {
        CodeArea area = buffer.getArea();
        WindowSessionCoordinator sessions = FxTestSupport.field(fx.controller, "sessions");
        for (int[] target : new int[][] {{3, 4}, {60, 9}}) {
            FxTestSupport.runOnFx(() -> {
                area.moveTo(1, area.getParagraphLength(1)); // the far end of the long line
                area.requestFollowCaret();
            });
            settle();
            double scrolled = FxTestSupport.callOnFx(() -> area.getEstimatedScrollX());
            assertTrue(scrolled > 0, "the view scrolled right to the end of the long line: " + scrolled);
            FxTestSupport.runOnFx(() -> sessions.gotoInFile(file, target[0], target[1]));
            settle();
            assertEquals(
                    0.0,
                    FxTestSupport.callOnFx(() -> area.getEstimatedScrollX()),
                    0.5,
                    "line " + target[0] + ", column " + target[1] + " shows from column 1");
            assertEquals(target[0] - 1, FxTestSupport.callOnFx(area::getCurrentParagraph));
        }
    }

    @Test
    void aJumpFarAlongALongLineStillShowsTheCaret() throws Exception {
        CodeArea area = buffer.getArea();
        WindowSessionCoordinator sessions = FxTestSupport.field(fx.controller, "sessions");
        FxTestSupport.runOnFx(() -> {
            area.moveTo(4, 0);
            area.requestFollowCaret();
        });
        settle();
        int far = FxTestSupport.callOnFx(() -> area.getParagraphLength(1)) - 5;
        FxTestSupport.runOnFx(() -> sessions.gotoInFile(file, 2, far));
        settle();
        assertTrue(
                FxTestSupport.callOnFx(() -> area.getEstimatedScrollX()) > 0,
                "a column past the viewport's width is scrolled to");
    }

    private static void settle() throws Exception {
        for (int i = 0; i < 6; i++) {
            FxTestSupport.drainFx();
            Thread.sleep(30);
        }
    }
}
