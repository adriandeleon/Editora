package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A folder of the Project tree that is closed is no longer watched, so when it is opened again it is listed
 * again. The handlers that did both were registered where a tree row's events never arrive, so a closed
 * folder stayed watched and a reopened one showed what it held when it was first opened.
 */
@Tag("fx")
class ProjectPanelReopenedFolderFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static Set<Path> watched(ProjectPanelRig r) {
        Map<?, Path> keys = FxTestSupport.field(r.panel, "watchKeys");
        return Set.copyOf(keys.values());
    }

    @Test
    void closingAFolderStopsWatchingItAndOpeningItWatchesItAgain(@TempDir Path dir) throws Exception {
        try (ProjectPanelRig r = new ProjectPanelRig(dir)) {
            FxTestSupport.runOnFx(() -> r.item(r.docs).setExpanded(true));
            r.awaitChildren(r.docs, 1);
            r.await("the open folder to be watched", () -> watched(r).contains(r.docs));

            FxTestSupport.runOnFx(() -> {
                r.item(r.docs).setExpanded(false);
                assertEquals(Set.of(r.root), watched(r), "only what is on show is watched");

                r.item(r.docs).setExpanded(true);
                assertTrue(watched(r).contains(r.docs));
                assertFalse(watched(r).contains(r.src), "a folder never opened is not watched");
            });
        }
    }

    @Test
    void aFolderChangedWhileClosedShowsTheChangeWhenReopened(@TempDir Path dir) throws Exception {
        try (ProjectPanelRig r = new ProjectPanelRig(dir)) {
            FxTestSupport.runOnFx(() -> r.item(r.docs).setExpanded(true));
            r.awaitChildren(r.docs, 1);
            FxTestSupport.runOnFx(() -> r.item(r.docs).setExpanded(false));
            r.await("the closed folder to be unwatched", () -> !watched(r).contains(r.docs));

            Files.writeString(r.docs.resolve("added.md"), "written while the folder was closed\n");
            Files.delete(r.guide);

            FxTestSupport.runOnFx(() -> r.item(r.docs).setExpanded(true));
            r.await("the reopened folder to be listed again", () -> r.item(r.docs.resolve("added.md")) != null);
            assertEquals(
                    java.util.List.of("added.md"),
                    FxTestSupport.callOnFx(() -> r.item(r.docs).getChildren().stream()
                            .map(i -> i.getValue().getFileName().toString())
                            .toList()),
                    "the new file is there and the deleted one is gone");
        }
    }
}
