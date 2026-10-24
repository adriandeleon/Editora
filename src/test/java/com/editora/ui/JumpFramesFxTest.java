package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.editora.editor.EditorBuffer;
import com.editora.editor.FrameWatch;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Go to Line, a jump to a line of the open file (bookmarks, TODOs, the Problems list) and a jump to file:line
 * (definitions, search hits) each repaint the editor once: every frame after the jump shows the target line, the
 * sticky-scroll rows for the line on top and the minimap's box where the viewport is ({@link JumpFrameCheck}).
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JumpFramesFxTest {

    private FxWindowFixture fx;
    private Path file;
    private EditorBuffer buffer;

    @BeforeAll
    void open() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create(
                Files.createTempDirectory("editora-fx-test"),
                shared -> shared.getSettings().setStickyScroll(true));
        Path dir = Files.createTempDirectory("editora-jump");
        file = dir.resolve("Big.java");
        Files.writeString(file, JumpFrameCheck.javaSource(60));
        FxTestSupport.runOnFx(() -> FxTestSupport.call(
                FxTestSupport.field(fx.controller, "fileWorkflows"), "openPath", new Class<?>[] {Path.class}, file));
        buffer = FxTestSupport.callOnFx(
                () -> (EditorBuffer) FxTestSupport.call(fx.controller, "activeBuffer", new Class<?>[] {}));
        assertNotNull(buffer, "the file opened");
        // Wait for the fold regions (the sticky rows come from them) and the minimap's first paint.
        Object minimap = FxTestSupport.field(buffer, "minimap");
        for (int i = 0; i < 200; i++) {
            boolean ready = FxTestSupport.callOnFx(
                    () -> !buffer.getFoldManager().regions().isEmpty()
                            && FxTestSupport.field(minimap, "paintedViewport") != null);
            if (ready) {
                break;
            }
            Thread.sleep(25);
        }
        assertFalse(
                FxTestSupport.callOnFx(() -> buffer.getFoldManager().regions().isEmpty()), "folds computed");
    }

    @AfterAll
    void close() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    @Test
    void goToLine() throws Exception {
        NavigationCoordinator navigation = FxTestSupport.field(fx.controller, "navigation");
        CodeArea area = buffer.getArea();
        jump("Go to Line", 1 + 12 * 40, () -> {
            navigation.handleGoToLine(
                    String.valueOf(2 + 12 * 40), area, area.getParagraphs().size());
        });
        jump("Go to Line, back up", 1 + 12 * 5, () -> {
            navigation.handleGoToLine(
                    String.valueOf(2 + 12 * 5), area, area.getParagraphs().size());
        });
    }

    @Test
    void jumpToALineOfTheOpenFile() throws Exception {
        jump(
                "a line of the file",
                1 + 12 * 33,
                () -> FxTestSupport.call(fx.controller, "navigateToLine", new Class<?>[] {int.class}, 1 + 12 * 33));
        jump(
                "a line of the file, back up",
                1 + 12 * 9,
                () -> FxTestSupport.call(fx.controller, "navigateToLine", new Class<?>[] {int.class}, 1 + 12 * 9));
    }

    @Test
    void jumpToFileLine() throws Exception {
        WindowSessionCoordinator sessions = FxTestSupport.field(fx.controller, "sessions");
        jump("file:line", 1 + 12 * 50, () -> sessions.gotoInFile(file, 2 + 12 * 50, 1));
        jump("file:line, back up", 1 + 12 * 12, () -> sessions.gotoInFile(file, 2 + 12 * 12, 1));
    }

    @Test
    void foldMotion() throws Exception {
        NavigationCoordinator navigation = FxTestSupport.field(fx.controller, "navigation");
        for (int method : new int[] {45, 3}) {
            int line = 1 + 12 * method;
            var region = FxTestSupport.callOnFx(() -> buffer.getFoldManager().regions().stream()
                    .filter(r -> r.startLine() == line)
                    .findFirst()
                    .orElseThrow());
            jump("to the fold of m" + method, line, () -> navigation.gotoFold(region));
        }
    }

    /** The target stays visible: below the pinned rows, not under them. */
    @Test
    void goToLineAboveTheViewportLandsClearOfThePinnedRows() throws Exception {
        NavigationCoordinator navigation = FxTestSupport.field(fx.controller, "navigation");
        CodeArea area = buffer.getArea();
        FxTestSupport.runOnFx(
                () -> FxTestSupport.call(fx.controller, "navigateToLine", new Class<?>[] {int.class}, 1 + 12 * 55));
        jump("Go to Line, up", 3 + 12 * 20, () -> {
            navigation.handleGoToLine(
                    String.valueOf(4 + 12 * 20), area, area.getParagraphs().size());
        });
        int[] view = FxTestSupport.callOnFx(() -> new int[] {
            area.firstVisibleParToAllParIndex(), ((List<?>) FxTestSupport.field(buffer, "stickyLines")).size()
        });
        int target = 3 + 12 * 20;
        assertTrue(
                view[0] + view[1] <= target && target <= view[0] + view[1] + 1,
                "line " + target + " shows right below the " + view[1] + " rows pinned over a viewport from "
                        + view[0]);
    }

    private void jump(String what, int line, Runnable jump) throws Exception {
        List<String> wrong =
                FrameWatch.wrongFrames(buffer, FxTestSupport.field(buffer, "minimap"), JumpFrameCheck.of(buffer), jump);
        assertEquals(List.of(), wrong, what + ": every frame shows the target, pinned and boxed");
        assertEquals(line, (int) FxTestSupport.callOnFx(() -> buffer.getArea().getCurrentParagraph()), what);
    }
}
