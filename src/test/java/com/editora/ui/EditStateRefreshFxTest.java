package com.editora.ui;

import java.util.concurrent.CountDownLatch;

import javafx.scene.control.Button;

import com.editora.editor.EditorBuffer;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The toolbar's edit state (Save / Undo / Redo / Cut / Copy) follows the active buffer per key, but as one
 * coalesced refresh per event-loop turn that never compares the document: a dirty buffer edited at its saved
 * length used to be resolved by an O(document) compare inside every keystroke (K3 in the 2026-10 perf review).
 * The Save button follows the dirty property; the 150 ms settle gives the exact answer.
 */
@Tag("fx")
class EditStateRefreshFxTest {

    private AsyncTestScope async;
    private FxWindowFixture fx;
    private MainController controller;
    private Button saveButton;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @BeforeEach
    void setUp() throws Exception {
        async = new AsyncTestScope();
        fx = async.own(FxWindowFixture.create());
        controller = fx.controller;
        saveButton = FxTestSupport.field(controller, "saveButton");
    }

    @AfterEach
    void tearDown() throws Exception {
        async.close();
    }

    @Test
    void theRefreshIsCoalescedToOnePerTurnAndNeverComparesTheDocument() throws Exception {
        EditorBuffer buffer = FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setDisplayName("draft");
            b.setInitialContent("one\ntwo\nthree\n");
            FxTestSupport.call(controller, "addBuffer", new Class<?>[] {EditorBuffer.class, boolean.class}, b, true);
            return b;
        });
        FxTestSupport.drainFx();
        assertTrue(FxTestSupport.callOnFx(saveButton::isDisable), "a clean buffer has nothing to save");

        boolean pending = FxTestSupport.callOnFx(() -> {
            CodeArea area = buffer.getArea();
            area.replaceText(0, 8, "two\none\n"); // move line down: same length, a real change
            area.replaceText(0, 8, "one\ntwo\n");
            area.replaceText(0, 8, "two\none\n");
            return FxTestSupport.<Boolean>field(controller, "editStateRefreshPending");
        });
        assertTrue(pending, "three edits in one turn queue one refresh");
        FxTestSupport.drainFx();
        assertFalse(FxTestSupport.callOnFx(saveButton::isDisable), "the refresh enables Save");

        long before = FxTestSupport.callOnFx(() -> materializations(buffer));
        for (int i = 0; i < 20; i++) {
            final int n = i;
            FxTestSupport.runOnFx(() -> buffer.getArea().replaceText(0, 8, n % 2 == 0 ? "one\ntwo\n" : "two\none\n"));
            FxTestSupport.drainFx(); // the refresh runs between keys, as it would for a typist
        }
        assertEquals(
                before,
                FxTestSupport.callOnFx(() -> materializations(buffer)),
                "no refresh of a dirty buffer at its saved length builds the document");
        assertFalse(FxTestSupport.callOnFx(saveButton::isDisable), "Save follows the dirty property meanwhile");
    }

    @Test
    void saveDisablesWhenTheSettledEditResolvesTheBufferClean() throws Exception {
        EditorBuffer buffer = FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setDisplayName("draft");
            b.setInitialContent("one\ntwo\nthree\n");
            FxTestSupport.call(controller, "addBuffer", new Class<?>[] {EditorBuffer.class, boolean.class}, b, true);
            return b;
        });
        CountDownLatch clean = new CountDownLatch(1);
        FxTestSupport.runOnFx(() -> {
            CodeArea area = buffer.getArea();
            area.replaceText(0, 8, "two\none\n");
            area.replaceText(0, 8, "one\ntwo\n"); // the saved text again, not compared on the edit itself
            buffer.dirtyProperty().addListener((obs, was, now) -> {
                if (!now) {
                    clean.countDown();
                }
            });
        });
        FxTestSupport.drainFx();
        assertTrue(FxTestSupport.callOnFx(buffer.dirtyProperty()::get), "carried dirty until the edit settles");
        assertFalse(FxTestSupport.callOnFx(saveButton::isDisable), "Save stays enabled with the property");
        async.await(clean, "the settled edit resolves the buffer clean");
        FxTestSupport.drainFx();
        assertTrue(FxTestSupport.callOnFx(saveButton::isDisable), "and the dirty listener disables Save");
    }

    private static long materializations(EditorBuffer buffer) {
        return (Long) FxTestSupport.call(buffer, "documentSnapshotMaterializations", new Class<?>[] {});
    }
}
