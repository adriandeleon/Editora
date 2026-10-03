package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javafx.scene.control.ComboBox;
import javafx.scene.control.MenuButton;

import com.editora.config.RecentFiles;
import com.editora.config.SearchHistory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Every window shows the one shared recent-files list and search history. Each window used to keep its own
 * copy: another window's entries appeared only after a restart — if that window had been the last to save.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SharedHistoryFxTest {

    private FxWindowFixture fx;
    private MainController a;
    private MainController b;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
        a = fx.controller;
        b = FxTestSupport.callOnFx(() -> {
            fx.windowManager.newWindow();
            List<?> holders = FxTestSupport.field(fx.windowManager, "windows");
            return (MainController)
                    FxTestSupport.call(holders.get(holders.size() - 1), "controller", new Class<?>[] {});
        });
        assertNotSame(a, b, "a genuinely second window");
    }

    @AfterAll
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    private static ComboBox<String> queryCombo(MainController window) {
        SearchCoordinator search = FxTestSupport.field(window, "searchCoordinator");
        return FxTestSupport.field(search.panel(), "queryCombo");
    }

    @Test
    void bothWindowsUseTheOneRecentFilesList() throws Exception {
        RecentFiles shared = fx.shared.recentFiles();
        assertSame(shared, FxTestSupport.<RecentFiles>field(a, "recentFiles"));
        assertSame(shared, FxTestSupport.<RecentFiles>field(b, "recentFiles"));

        Path file = Files.createFile(fx.configDir.resolve("opened-in-a.txt"));
        MenuButton recentB = FxTestSupport.field(b, "recentButton");
        int before = FxTestSupport.callOnFx(() -> {
            int items = recentB.getItems().size();
            FxTestSupport.<RecentFiles>field(a, "recentFiles").add(file);
            return items;
        });
        FxTestSupport.drainFx(); // the windows refresh once per pulse
        int after = FxTestSupport.callOnFx(() -> recentB.getItems().size());

        assertEquals(1, before, "window B starts with only the 'no recent files' row");
        assertEquals(3, after, "a file opened in window A is offered by window B: entry, separator, clear");
        assertEquals(List.of(file), FxTestSupport.callOnFx(b::showableRecentFiles));
    }

    @Test
    void aQueryRunInOneWindowIsOfferedByTheOtherWithoutDisturbingItsCombo() throws Exception {
        SearchHistory shared = fx.shared.searchHistory();
        int[] actionsInB = {0};
        FxTestSupport.runOnFx(() -> {
            shared.add("first");
            shared.add("second");
            shared.add("third");
        });
        FxTestSupport.drainFx(); // the windows refresh once per pulse
        FxTestSupport.runOnFx(() -> {
            // Window B has an older query selected in its dropdown: not the top entry.
            ComboBox<String> comboB = queryCombo(b);
            comboB.getSelectionModel().select("first");
            comboB.addEventHandler(javafx.event.ActionEvent.ACTION, e -> actionsInB[0]++);
            // Window A runs more searches: new entries above B's selection, and one moved across it.
            shared.add("fourth");
            shared.add("second");
            shared.add("fifth");
        });
        FxTestSupport.drainFx();
        List<List<String>> seen = FxTestSupport.callOnFx(() -> List.of(
                List.copyOf(shared.getList()),
                List.copyOf(queryCombo(a).getItems()),
                List.copyOf(queryCombo(b).getItems()),
                List.of(String.valueOf(queryCombo(b).getValue()))));

        assertEquals(List.of("fifth", "second", "fourth", "third", "first"), seen.get(0));
        assertEquals(seen.get(0), seen.get(1), "window A offers the whole history");
        assertEquals(seen.get(0), seen.get(2), "and so does window B, including A's queries");
        // The combo's action is "run this search": another window's queries arriving in B's dropdown must
        // neither fire it nor change what B has selected.
        assertEquals(0, actionsInB[0], "window A's searches must not run a search in window B");
        assertEquals(List.of("first"), seen.get(3), "and B's selected query is left alone");
    }
}
