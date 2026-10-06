package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.scene.Scene;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextField;
import javafx.stage.Stage;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The file finder and the breadcrumb dropdown listed a directory — a {@code readdir} and a {@code stat} per
 * entry — on the FX thread, at a keystroke or a click. Both now read on a worker and drop a stale answer.
 */
@Tag("fx")
class PickerListingOffThreadFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static final String SEP = java.io.File.separator;

    @Test
    void theFinderReadsOffTheFxThreadOncePerDirectoryAndDropsAStaleRead(@TempDir Path root) throws Exception {
        Path slow = Files.createDirectory(root.resolve("slow"));
        Files.writeString(slow.resolve("slow-file.txt"), "");
        Path fast = Files.createDirectory(root.resolve("fast"));
        Path alpha = Files.writeString(fast.resolve("alpha.txt"), "");
        Path beta = Files.writeString(fast.resolve("beta.txt"), "");

        try (AsyncTestScope async = new AsyncTestScope()) {
            FileFinder finder = FxTestSupport.callOnFx(() -> new FileFinder(() -> root, p -> {}));
            TextField input = FxTestSupport.field(finder, "input");
            ObservableList<Path> items = FxTestSupport.field(finder, "items");
            List<Path> reads = new CopyOnWriteArrayList<>();
            AtomicBoolean readOnFx = new AtomicBoolean();
            CountDownLatch slowStarted = new CountDownLatch(1);
            CountDownLatch releaseSlow = new CountDownLatch(1);
            async.onClose(releaseSlow::countDown);
            finder.directoryReader = (dir, directoriesOnly) -> {
                reads.add(dir);
                readOnFx.compareAndSet(false, Platform.isFxApplicationThread());
                if (dir.equals(slow)) {
                    slowStarted.countDown();
                    try {
                        releaseSlow.await(20, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                return DirectoryListing.read(dir, directoriesOnly);
            };

            // The keystroke that completes a slow folder's name returns at once; the list is simply empty.
            FxTestSupport.runOnFx(() -> input.setText(slow + SEP));
            async.await(slowStarted, "the slow read to start");
            assertTrue(FxTestSupport.callOnFx(items::isEmpty));

            // The user goes elsewhere before it answers.
            FxTestSupport.runOnFx(() -> input.setText(fast + SEP));
            SaveGuardsFxTest.awaitOnFx(async, "the fast folder's listing", () -> items.size() == 2);
            assertEquals(List.of(alpha, beta), FxTestSupport.callOnFx(() -> List.copyOf(items)));

            // Typing a name filters the listing already held: no further read.
            FxTestSupport.runOnFx(() -> input.setText(fast + SEP + "be"));
            assertEquals(List.of(beta), FxTestSupport.callOnFx(() -> List.copyOf(items)));
            assertEquals(List.of(slow, fast), reads);

            // The slow read finally lands — for a directory the field no longer names. It changes nothing.
            releaseSlow.countDown();
            async.awaitFx();
            async.awaitFx();
            assertEquals(List.of(beta), FxTestSupport.callOnFx(() -> List.copyOf(items)));
            assertFalse(readOnFx.get(), "no directory was read on the FX thread");
        }
    }

    @Test
    void theBreadcrumbReadsOffTheFxThreadAndBuildsAPageOnlyWhenItIsOpened(@TempDir Path root) throws Exception {
        for (int i = 0; i < 100; i++) {
            Files.writeString(root.resolve(String.format("file%03d.txt", i)), "");
        }
        Path active = root.resolve("file000.txt");
        try (AsyncTestScope async = new AsyncTestScope()) {
            AtomicBoolean readOnFx = new AtomicBoolean(true);
            FileBreadcrumb bar = FxTestSupport.callOnFx(() -> {
                FileBreadcrumb b = new FileBreadcrumb(p -> {}, () -> root);
                b.setEnabled(true);
                b.setActiveFile(active);
                Stage stage = new Stage();
                stage.setScene(new Scene(b, 600, 60));
                stage.show();
                return b;
            });
            async.onClose(() -> FxTestSupport.runOnFx(() -> {
                if (bar.lastMenuForTest != null) {
                    bar.lastMenuForTest.hide();
                }
                ((Stage) bar.getScene().getWindow()).close();
            }));
            bar.directoryReader = (dir, directoriesOnly) -> {
                readOnFx.set(Platform.isFxApplicationThread());
                return DirectoryListing.read(dir, directoriesOnly);
            };

            FxTestSupport.runOnFx(() -> FxTestSupport.call(
                    bar, "showCrumbMenu", new Class<?>[] {Path.class, javafx.scene.Node.class}, active, bar));
            SaveGuardsFxTest.awaitOnFx(async, "the dropdown", () -> bar.lastMenuForTest != null);

            assertFalse(readOnFx.get(), "the listing was read on a worker");
            ContextMenu menu = bar.lastMenuForTest;
            List<Menu> pages = FxTestSupport.callOnFx(() -> menu.getItems().stream()
                    .filter(Menu.class::isInstance)
                    .map(Menu.class::cast)
                    .toList());
            assertTrue(pages.size() >= 3, "100 entries are paged: " + pages.size());
            for (Menu page : pages) {
                assertEquals(
                        1, FxTestSupport.callOnFx(() -> page.getItems().size()), "a closed page has built no rows");
            }
            List<String> first = FxTestSupport.callOnFx(() -> {
                pages.get(0).getOnShowing().handle(new javafx.event.Event(Menu.ON_SHOWING));
                pages.get(0).getOnShowing().handle(new javafx.event.Event(Menu.ON_SHOWING)); // opening it twice
                return pages.get(0).getItems().stream().map(MenuItem::getText).toList();
            });
            assertEquals(BreadcrumbPages.PAGE_SIZE, first.size());
            assertEquals("file000.txt", first.get(0));
            assertEquals(1, FxTestSupport.callOnFx(() -> pages.get(1).getItems().size()), "the others still have none");
        }
    }
}
