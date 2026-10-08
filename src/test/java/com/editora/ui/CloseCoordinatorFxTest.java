package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;

import com.editora.editor.EditorBuffer;
import com.editora.editor.TabContent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The questions asked before a tab, or the whole window, closes — a pinned tab, a non-file tab with unsaved
 * work, quitting — answered every way, through the real dialogs.
 */
@Tag("fx")
class CloseCoordinatorFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    /** A non-file tab's content (a form, a diff being edited) whose unsaved state the test controls. */
    private static final class Form implements TabContent {
        boolean unsaved = true;
        boolean saveWorks = true;
        boolean saveClears = true;
        int saves;

        @Override
        public Node node() {
            return new Label("form");
        }

        @Override
        public String title() {
            return "Request 1";
        }

        @Override
        public boolean hasUnsavedChanges() {
            return unsaved;
        }

        @Override
        public boolean saveBeforeClose() {
            saves++;
            if (saveWorks && saveClears) {
                unsaved = false;
            }
            return saveWorks;
        }

        @Override
        public String closeSaveActionKey() {
            return "dialog.close"; // a content type may name its own save action
        }
    }

    private static Tab tabOf(Form form) {
        Tab tab = new Tab();
        tab.setUserData(form);
        tab.setContent(form.node());
        return tab;
    }

    private static final String FORM_HEADER = "dialog.unsaved.header";

    @Test
    void closingAPinnedTabAsksFirst(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("pinned.txt"), "text\n");
        try (WindowRig w = new WindowRig()) {
            w.open(file);
            Tab tab = FxTestSupport.callOnFx(() -> w.tabFor(file));
            FxTestSupport.runOnFx(() -> w.controller.pinned.add(tab));
            String header = tr("dialog.pinnedTab.header", "pinned.txt");

            CountDownLatch kept = w.answer(header, tr("dialog.cancel"));
            assertFalse(FxTestSupport.callOnFx(() -> w.controller.closes.confirmClose(tab)));
            w.async.await(kept, "the pinned-tab question");

            CountDownLatch closed = w.answer(header, tr("dialog.close"));
            assertTrue(FxTestSupport.callOnFx(() -> w.controller.closes.confirmClose(tab)));
            w.async.await(closed, "the pinned-tab question");
        }
    }

    @Test
    void aTabThatIsNotAFileIsAskedAboutOnlyWhileItHasUnsavedWork() throws Exception {
        try (WindowRig w = new WindowRig()) {
            CloseCoordinator closes = w.controller.closes;
            String header = tr(FORM_HEADER, "Request 1");
            FxTestSupport.runOnFx(() -> {
                Form clean = new Form();
                clean.unsaved = false;
                assertTrue(closes.confirmClose(tabOf(clean)), "nothing unsaved: no question");
                assertTrue(closes.confirmClose(null), "no tab at all");
                Tab foreign = new Tab();
                foreign.setUserData("not tab content");
                assertTrue(closes.confirmClose(foreign), "a tab with nothing to lose");
            });

            Form cancelled = new Form();
            CountDownLatch cancel = w.answer(header, tr("dialog.cancel"));
            assertFalse(FxTestSupport.callOnFx(() -> closes.confirmClose(tabOf(cancelled))));
            w.async.await(cancel, "the unsaved question");
            assertEquals(0, cancelled.saves);

            Form discarded = new Form();
            CountDownLatch discard = w.answer(header, tr("dialog.discard"));
            assertTrue(FxTestSupport.callOnFx(() -> closes.confirmClose(tabOf(discarded))));
            w.async.await(discard, "the unsaved question");
            assertEquals(0, discarded.saves, "Discard does not save");

            Form saved = new Form();
            CountDownLatch save = w.answer(header, tr("dialog.close"));
            assertTrue(FxTestSupport.callOnFx(() -> closes.confirmClose(tabOf(saved))));
            w.async.await(save, "the unsaved question, with the content's own save label");
            assertEquals(1, saved.saves);

            Form failing = new Form();
            failing.saveWorks = false;
            w.answer(header, tr("dialog.close"));
            assertFalse(
                    FxTestSupport.callOnFx(() -> closes.confirmClose(tabOf(failing))),
                    "a save that fails keeps the tab open");

            Form stillDirty = new Form();
            stillDirty.saveClears = false;
            w.answer(header, tr("dialog.close"));
            assertFalse(
                    FxTestSupport.callOnFx(() -> closes.confirmClose(tabOf(stillDirty))),
                    "so does a save that reports success but leaves work unsaved");
            assertEquals(1, stillDirty.saves);
        }
    }

    @Test
    void quittingIsConfirmed() throws Exception {
        try (WindowRig w = new WindowRig()) {
            CloseCoordinator closes = w.controller.closes;
            CountDownLatch cancel = w.answer(tr("dialog.quit.header"), tr("dialog.cancel"));
            assertFalse(FxTestSupport.callOnFx(closes::confirmQuit));
            w.async.await(cancel, "the quit question");

            CountDownLatch quit = w.answer(tr("dialog.quit.header"), tr("dialog.quit.button"));
            assertTrue(FxTestSupport.callOnFx(closes::confirmQuit));
            w.async.await(quit, "the quit question");
        }
    }

    @Test
    void closingTheWindowAsksAboutAnUnsavedFormOnceAndShowsItWhileAsking() throws Exception {
        try (WindowRig w = new WindowRig()) {
            CloseCoordinator closes = w.controller.closes;
            EditorArea area = w.field("editorArea");
            String header = tr(FORM_HEADER, "Request 1");
            Form form = new Form();
            Tab formTab = tabOf(form);
            Tab other = tabOf(cleanForm());
            FxTestSupport.runOnFx(() -> {
                area.add(formTab);
                area.add(other);
                area.select(other);
            });

            CountDownLatch cancel = w.answer(header, tr("dialog.cancel"));
            assertFalse(FxTestSupport.callOnFx(closes::confirmCloseAll), "Cancel keeps the window open");
            w.async.await(cancel, "the unsaved question");
            assertSame(formTab, FxTestSupport.callOnFx(area::selectedTab), "the tab in question was brought up");

            // Discard: the next validation pass finds the same unsaved state already approved and asks no more.
            CountDownLatch discard = w.answer(header, tr("dialog.discard"));
            assertTrue(FxTestSupport.callOnFx(closes::confirmCloseAll));
            w.async.await(discard, "the unsaved question");
            assertTrue(form.unsaved);
            assertEquals(0, form.saves);

            CountDownLatch save = w.answer(header, tr("dialog.close"));
            assertTrue(FxTestSupport.callOnFx(closes::confirmCloseAll));
            w.async.await(save, "the unsaved question");
            assertFalse(form.unsaved, "saved, so there is nothing left to ask about");
            assertEquals(1, form.saves);

            assertTrue(FxTestSupport.callOnFx(closes::confirmCloseAll), "nothing unsaved: no dialog at all");
        }
    }

    @Test
    void closingTheWindowWithAnEditedFileCanBeCancelledOrDiscarded(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("draft.txt"), "saved text\n");
        try (WindowRig w = new WindowRig()) {
            EditorBuffer buffer = w.open(file);
            FxTestSupport.runOnFx(() -> buffer.getArea().appendText("unsaved"));
            CloseCoordinator closes = w.controller.closes;
            String header = tr("dialog.unsaved.header", "draft.txt");

            CountDownLatch cancel = w.answer(header, tr("dialog.cancel"));
            assertFalse(FxTestSupport.callOnFx(closes::confirmCloseAll));
            w.async.await(cancel, "the unsaved question");

            CountDownLatch discard = w.answer(header, tr("dialog.discard"));
            assertTrue(FxTestSupport.callOnFx(closes::confirmCloseAll));
            w.async.await(discard, "the unsaved question");
            assertEquals("saved text\n", Files.readString(file), "Discard wrote nothing");
            assertTrue(FxTestSupport.callOnFx(buffer::isDirty));

            // A discard answered once is not a standing permission: the next attempt to close asks again.
            FxTestSupport.runOnFx(() -> buffer.getArea().appendText("!"));
            CountDownLatch again = w.answer(header, tr("dialog.cancel"));
            assertFalse(FxTestSupport.callOnFx(closes::confirmCloseAll));
            w.async.await(again, "the unsaved question, asked again");
        }
    }

    private static Form cleanForm() {
        Form form = new Form();
        form.unsaved = false;
        return form;
    }
}
