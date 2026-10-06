package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;

import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;

import com.editora.command.CommandRegistry;
import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The file load (and a reload from disk) must be the undo baseline, never an undo step: one undo too many
 * used to empty the buffer, and a save then wrote a zero-byte file. Every case opens through the production
 * path ({@code FileWorkflowCoordinator.openPath}) — {@code setContent} in a bare buffer is what hid this.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UndoPastLoadFxTest {

    private static final String TEXT = "class Sample {\n    int a;\n}\n";

    private FxWindowFixture fx;
    private CommandRegistry registry;
    private Path dir;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
        registry = FxTestSupport.field(fx.controller, "registry");
        dir = Files.createTempDirectory("editora-undo-load");
    }

    @AfterAll
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    private EditorBuffer open(String name, String content) throws Exception {
        Path file = dir.resolve(name);
        Files.writeString(file, content);
        EditorBuffer buffer = FxTestSupport.callOnFx(() -> {
            FxTestSupport.call(
                    FxTestSupport.field(fx.controller, "fileWorkflows"), "openPath", new Class<?>[] {Path.class}, file);
            return (EditorBuffer) FxTestSupport.call(fx.controller, "activeBuffer", new Class<?>[] {});
        });
        assertTrue(waitUntil(() -> content.equals(buffer.getContent())), "background load did not complete");
        return buffer;
    }

    private void run(String id) throws Exception {
        FxTestSupport.runOnFx(() -> registry.run(id));
    }

    @Test
    void undoOnAFreshlyOpenedFileIsUnavailableAndLeavesTheText() throws Exception {
        EditorBuffer buffer = open("Fresh.java", TEXT);
        assertFalse(FxTestSupport.callOnFx(() -> buffer.getArea().isUndoAvailable()), "the load must not be undoable");
        run("edit.undo");
        assertEquals(TEXT, FxTestSupport.callOnFx(buffer::getContent));
        assertFalse(FxTestSupport.callOnFx(buffer::isDirty));
    }

    @Test
    void undoPastTheUsersOwnEditStopsAtTheLoadedText() throws Exception {
        EditorBuffer buffer = open("Edited.java", TEXT);
        FxTestSupport.runOnFx(() -> buffer.getArea().insertText(0, "x"));
        run("edit.undo");
        run("edit.undo");
        run("edit.undo");
        assertEquals(TEXT, FxTestSupport.callOnFx(buffer::getContent), "undo must stop at the loaded text");
        assertFalse(FxTestSupport.callOnFx(buffer::isDirty));
    }

    @Test
    void undoHistoryBaselineIsTheLoadedText() throws Exception {
        EditorBuffer buffer = open("Baseline.java", TEXT);
        var entries = FxTestSupport.callOnFx(() -> buffer.getUndoHistory().entriesNewestFirst());
        assertEquals(1, entries.size(), "exactly the as-opened baseline, not the empty loading shell");
        assertEquals(TEXT, entries.get(0).text());
    }

    @Test
    void undoAfterAReloadFromDiskDoesNotResurrectThePreReloadText() throws Exception {
        EditorBuffer buffer = open("Reloaded.java", TEXT);
        String newer = "class Sample {\n    int newer;\n}\n";
        Files.writeString(buffer.getPath(), newer);
        FxTestSupport.runOnFx(() -> {
            TabPane tabs = FxTestSupport.field(fx.controller, "tabPane");
            Tab tab = tabs.getTabs().stream()
                    .filter(candidate -> candidate.getUserData() == buffer)
                    .findFirst()
                    .orElseThrow();
            FxTestSupport.call(
                    FxTestSupport.field(fx.controller, "fileWorkflows"),
                    "reloadFromDisk",
                    new Class<?>[] {Tab.class, EditorBuffer.class},
                    tab,
                    buffer);
        });
        assertTrue(waitUntil(() -> newer.equals(buffer.getContent())), "reload did not complete");
        assertFalse(
                FxTestSupport.callOnFx(() -> buffer.getArea().isUndoAvailable()), "the reload must not be undoable");
        run("edit.undo");
        assertEquals(newer, FxTestSupport.callOnFx(buffer::getContent));
        assertFalse(FxTestSupport.callOnFx(buffer::isDirty));
    }

    private static boolean waitUntil(java.util.function.BooleanSupplier condition) throws Exception {
        for (int i = 0; i < 300; i++) {
            if (FxTestSupport.callOnFx(condition::getAsBoolean)) {
                return true;
            }
            Thread.sleep(20);
        }
        return false;
    }
}
