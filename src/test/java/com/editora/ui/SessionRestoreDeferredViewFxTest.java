package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;

import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A restored <b>background</b> tab is filled with its file but its editor stays out of the scene until the
 * tab is first shown — and everything the session saved for it is still there when it is.
 *
 * <p>The cost being pinned is structural, so the assertions are too: a hidden editor that is in the scene
 * lays out a screenful of paragraph cells on every document update, one that is not cannot.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SessionRestoreDeferredViewFxTest {

    private static final int LINES = 400;
    private static final int CARET_LINE = 300;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void aBackgroundTabIsFilledDetachedAndRestoresItsStateWhenFirstShown() throws Exception {
        Path dir = Files.createTempDirectory("editora-session-deferred-view");
        Path active = Files.writeString(dir.resolve("Active.java"), "class Active {}\n");
        Path background = Files.writeString(dir.resolve("Background.java"), javaSource());
        int caret = offsetOfLine(Files.readString(background), CARET_LINE);
        // Line 1 opens method m0's block; a saved collapsed region must survive the deferral.
        Files.writeString(
                dir.resolve("workspace-state.json"),
                "{\"schemaVersion\":1,\"openFiles\":[{\"path\":\"" + background + "\",\"caret\":" + caret
                        + "},{\"path\":\"" + active + "\"}],\"activeFile\":\"" + active
                        + "\",\"foldedRegions\":{\"" + background + "\":[1]}}");

        FxWindowFixture fx = FxWindowFixture.create(dir, false, false, false, List.of(), c -> {});
        try {
            assertTrue(waitUntil(() -> allFilled(fx.controller)), "both session files should be restored");
            FxTestSupport.drainFx();

            Tab backgroundTab = FxTestSupport.callOnFx(() -> tabOf(fx.controller, background));
            EditorBuffer buffer = (EditorBuffer) backgroundTab.getUserData();
            Tab activeTab = FxTestSupport.callOnFx(() -> tabOf(fx.controller, active));

            // The first tab added to an empty TabPane is auto-selected; it must not have attached for that.
            assertTrue(
                    FxTestSupport.callOnFx(() -> DeferredTabContent.isDeferred(backgroundTab)),
                    "a never-shown restored tab keeps its editor detached");
            assertNull(FxTestSupport.callOnFx(() -> buffer.getNode().getScene()), "detached editor has no scene");
            assertFalse(FxTestSupport.callOnFx(() -> DeferredTabContent.isDeferred(activeTab)));
            assertNotNull(FxTestSupport.callOnFx(
                    () -> ((EditorBuffer) activeTab.getUserData()).getNode().getScene()));

            // The model is complete while the view waits.
            assertEquals(
                    LINES + 3, FxTestSupport.callOnFx(buffer::lineCount).intValue()); // + class braces + final newline
            assertFalse(FxTestSupport.callOnFx(buffer::isLoading));
            assertFalse(FxTestSupport.callOnFx(buffer::isDirty));
            assertEquals(
                    CARET_LINE,
                    FxTestSupport.callOnFx(() -> buffer.getArea().getCurrentParagraph())
                            .intValue());
            assertEquals(
                    List.of(1),
                    FxTestSupport.callOnFx(() -> buffer.getFoldManager().collapsedStartLines()),
                    "the saved collapsed region is re-folded during the background fill");

            FxTestSupport.runOnFx(
                    () -> tabPane(fx.controller).getSelectionModel().select(backgroundTab));
            assertFalse(FxTestSupport.callOnFx(() -> DeferredTabContent.isDeferred(backgroundTab)));
            assertNotNull(FxTestSupport.callOnFx(() -> buffer.getNode().getScene()), "shown tab attaches its editor");
            assertTrue(
                    waitUntil(() -> firstVisibleLine(buffer) == CARET_LINE),
                    "first show parks the viewport on the saved caret; first visible line was "
                            + FxTestSupport.callOnFx(() -> firstVisibleLine(buffer)));
            assertEquals(
                    List.of(1),
                    FxTestSupport.callOnFx(() -> buffer.getFoldManager().collapsedStartLines()));
        } finally {
            fx.dispose();
        }
    }

    /**
     * An empty {@code TabPane} selects the first tab added to it. During a restore that tab is rarely the
     * session's active one, and treating it as the active tab ran the whole tab-switch fan-out for it.
     */
    @Test
    void theFirstRestoredTabIsNotTreatedAsActiveOnTheWayToTheRealOne() throws Exception {
        Path dir = Files.createTempDirectory("editora-session-first-tab");
        Path first = Files.writeString(dir.resolve("first.txt"), "first\n");
        Path active = Files.writeString(dir.resolve("active.txt"), "active\n");
        Files.writeString(
                dir.resolve("workspace-state.json"),
                "{\"schemaVersion\":1,\"openFiles\":[{\"path\":\"" + first + "\"},{\"path\":\"" + active
                        + "\"}],\"activeFile\":\"" + active + "\"}");
        List<List<Tab>> mruAtBuild = new java.util.ArrayList<>();
        FxWindowFixture fx = FxWindowFixture.create(
                dir,
                false,
                false,
                false,
                List.of(),
                c -> mruAtBuild.add(List.copyOf(FxTestSupport.<java.util.List<Tab>>field(c, "mru"))));
        try {
            Tab activeTab = FxTestSupport.callOnFx(() -> tabOf(fx.controller, active));
            // The most-recently-used list is written by the fan-out, once per tab it ran for.
            assertEquals(List.of(activeTab), mruAtBuild.get(0), "only the session's active tab became active");
            assertEquals(
                    activeTab,
                    FxTestSupport.callOnFx(
                            () -> tabPane(fx.controller).getSelectionModel().getSelectedItem()));
            assertTrue(waitUntil(() -> allFilled(fx.controller)));
        } finally {
            fx.dispose();
        }
    }

    private static int firstVisibleLine(EditorBuffer buffer) {
        try {
            return buffer.getArea().firstVisibleParToAllParIndex();
        } catch (RuntimeException e) {
            return -1; // not laid out yet
        }
    }

    private static String javaSource() {
        StringBuilder sb = new StringBuilder("class Background {\n");
        for (int i = 0; i < LINES / 4; i++) {
            sb.append("    void m").append(i).append("() {\n");
            sb.append("        int a = ").append(i).append(";\n");
            sb.append("        System.out.println(a);\n");
            sb.append("    }\n");
        }
        return sb.append("}\n").toString();
    }

    private static int offsetOfLine(String text, int line) {
        int offset = 0;
        for (int i = 0; i < line; i++) {
            offset = text.indexOf('\n', offset) + 1;
        }
        return offset;
    }

    private static TabPane tabPane(MainController controller) {
        return FxTestSupport.field(controller, "tabPane");
    }

    private static Tab tabOf(MainController controller, Path file) {
        for (Tab tab : tabPane(controller).getTabs()) {
            if (tab.getUserData() instanceof EditorBuffer buffer && file.equals(buffer.getPath())) {
                return tab;
            }
        }
        throw new AssertionError("no tab for " + file);
    }

    private static boolean allFilled(MainController controller) {
        for (Tab tab : tabPane(controller).getTabs()) {
            if (!(tab.getUserData() instanceof EditorBuffer buffer)
                    || buffer.isLoading()
                    || buffer.getContent().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /** Polls {@code condition} on the FX thread until it holds, or ~5 s elapse. */
    private static boolean waitUntil(java.util.function.BooleanSupplier condition) throws Exception {
        for (int i = 0; i < 250; i++) {
            if (FxTestSupport.callOnFx(condition::getAsBoolean)) {
                return true;
            }
            Thread.sleep(20);
        }
        return false;
    }
}
