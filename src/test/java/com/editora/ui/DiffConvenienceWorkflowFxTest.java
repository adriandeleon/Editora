package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;

import com.editora.command.CommandRegistry;
import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DiffConvenienceWorkflowFxTest {

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
    void commandsCompareClipboardAndEmptyTextAndSwappedRefreshKeepsOrientation() throws Exception {
        Path file = Files.createTempFile("editora-diff-convenience", ".txt");
        Files.writeString(file, "working\n");
        EditorBuffer buffer = FxTestSupport.callOnFx(() -> {
            EditorBuffer created = new EditorBuffer();
            created.setPath(file);
            created.setContent("working\n");
            FxTestSupport.call(
                    fx.controller, "addBuffer", new Class<?>[] {EditorBuffer.class, boolean.class}, created, true);
            ClipboardContent content = new ClipboardContent();
            content.putString("clipboard\n");
            Clipboard.getSystemClipboard().setContent(content);
            return created;
        });

        CommandRegistry registry = FxTestSupport.field(fx.controller, "registry");
        Object diff = FxTestSupport.field(fx.controller, "diffCoordinator");
        Object ops = FxTestSupport.field(diff, "ops");
        assertTrue(registry.get("diff.compareClipboard").isPresent());
        assertTrue(registry.get("diff.compareBlank").isPresent());
        assertTrue(registry.get("diff.swapSides").isPresent());
        FxTestSupport.runOnFx(() -> {
            registry.run("diff.compareClipboard");
            registry.run("diff.compareBlank");
        });

        List<DiffViewerPane> panes = awaitPanes(ops, 2);
        DiffViewerPane clipboard = paneWithLeftHeader(panes, "Clipboard");
        DiffViewerPane blank = paneWithLeftHeader(panes, "Empty text");
        assertNotNull(clipboard);
        assertNotNull(blank);
        assertEquals("clipboard\n", FxTestSupport.field(clipboard, "leftText"));
        assertEquals("working\n", FxTestSupport.field(clipboard, "rightText"));
        assertEquals("", FxTestSupport.field(blank, "leftText"));
        assertEquals(DiffViewerPane.EditableSide.RIGHT, clipboard.editableSide());

        FxTestSupport.runOnFx(clipboard::swapComparisonSides);
        awaitCondition(() -> clipboard.editableSide() == DiffViewerPane.EditableSide.LEFT);
        assertEquals("working\n", FxTestSupport.field(clipboard, "leftText"));
        assertEquals("clipboard\n", FxTestSupport.field(clipboard, "rightText"));

        FxTestSupport.runOnFx(() -> {
            buffer.getArea().replaceText("updated\n");
            clipboard.refresh();
        });
        awaitCondition(() -> "updated\n".equals(FxTestSupport.field(clipboard, "leftText")));
        assertEquals("clipboard\n", FxTestSupport.field(clipboard, "rightText"));
        assertEquals(DiffViewerPane.EditableSide.LEFT, clipboard.editableSide());
    }

    @SuppressWarnings("unchecked")
    private static List<DiffViewerPane> awaitPanes(Object ops, int count) throws Exception {
        for (int i = 0; i < 100; i++) {
            List<DiffViewerPane> panes = FxTestSupport.callOnFx(
                    () -> (List<DiffViewerPane>) FxTestSupport.call(ops, "openDiffPanes", new Class<?>[] {}));
            if (panes.size() >= count) {
                return panes;
            }
            Thread.sleep(50);
        }
        return List.of();
    }

    private static DiffViewerPane paneWithLeftHeader(List<DiffViewerPane> panes, String header) {
        return panes.stream()
                .filter(pane -> header.equals(FxTestSupport.field(pane, "headerLeft")))
                .findFirst()
                .orElse(null);
    }

    private static void awaitCondition(CheckedBoolean condition) throws Exception {
        for (int i = 0; i < 100; i++) {
            if (condition.get()) {
                return;
            }
            Thread.sleep(50);
        }
        assertTrue(condition.get(), "condition did not become true");
    }

    @FunctionalInterface
    private interface CheckedBoolean {
        boolean get() throws Exception;
    }
}
