package com.editora.ui;

import java.nio.file.Path;

import javafx.scene.control.Tab;

import com.editora.dap.DapModels;
import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The debugger's execution-line highlight must land on the stack frame's OWN file, never on whatever tab
 * happens to be active. A frame whose source isn't on disk — one inside a dependency, or a path baked in
 * by a build on another machine — opens nothing, and painting "you are here" onto the unrelated file the
 * user is reading (and yanking its caret there, since setExecutionLine also scrolls) is a silent lie.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DebugExecutionLineFxTest {

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
    void aFrameWhoseSourceIsMissingDoesNotHighlightTheActiveFile() throws Exception {
        Object debug = FxTestSupport.field(fx.controller, "debugCoordinator");

        // The user is reading their own file.
        EditorBuffer mine = FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.getArea().replaceText("one\ntwo\nthree\nfour\nfive\n");
            FxTestSupport.call(fx.controller, "addBuffer", new Class[] {EditorBuffer.class, boolean.class}, b, true);
            return b;
        });

        // The debugger stops in a frame whose file is nowhere on disk (a dependency / a CI build path).
        DapModels.StackFrameInfo frame =
                new DapModels.StackFrameInfo(1, "Foo.bar", Path.of("/nonexistent/build/src/Foo.java"), 3, 0);
        FxTestSupport.runOnFx(
                () -> FxTestSupport.call(debug, "highlightFrame", new Class[] {DapModels.StackFrameInfo.class}, frame));

        for (int line = 0;
                line
                        < FxTestSupport.callOnFx(
                                () -> mine.getArea().getParagraphs().size());
                line++) {
            final int i = line;
            assertTrue(
                    FxTestSupport.callOnFx(() ->
                            mine.getArea().getParagraph(i).getParagraphStyle().isEmpty()),
                    "the active file must carry no execution-line highlight: the frame is a different file");
        }
    }

    @Test
    void aFrameWithSourceOnDiskHighlightsThatFile() throws Exception {
        Object debug = FxTestSupport.field(fx.controller, "debugCoordinator");
        Path real = fx.configDir.resolve("Real.java");
        java.nio.file.Files.writeString(real, "package p;\nclass Real {\n  void go() {}\n}\n");

        FxTestSupport.runOnFx(() -> FxTestSupport.call(fx.controller, "openPath", new Class[] {Path.class}, real));
        DapModels.StackFrameInfo frame = new DapModels.StackFrameInfo(1, "Real.go", real, 2, 0);
        FxTestSupport.runOnFx(
                () -> FxTestSupport.call(debug, "highlightFrame", new Class[] {DapModels.StackFrameInfo.class}, frame));

        Tab tab = FxTestSupport.callOnFx(
                () -> (Tab) FxTestSupport.call(fx.controller, "tabForPath", new Class[] {Path.class}, real));
        EditorBuffer opened = FxTestSupport.callOnFx(
                () -> (EditorBuffer) FxTestSupport.call(fx.controller, "bufferOf", new Class[] {Tab.class}, tab));
        assertEquals(
                java.util.List.of("exec-line"),
                FxTestSupport.callOnFx(() -> opened.getArea().getParagraph(2).getParagraphStyle()),
                "the frame's own file gets the highlight");
    }
}
