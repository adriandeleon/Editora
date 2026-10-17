package com.editora.ui;

import java.util.ArrayList;
import java.util.List;

import javafx.scene.Scene;
import javafx.scene.control.ListView;
import javafx.stage.Stage;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SettingsKeys#reveal} keeps the asked-for sidebar row in view when another reveal came just before it,
 * with no layout pass between: the list has not moved to the first one yet, so the second must not judge "in
 * view" from where the rows were last drawn.
 */
@Tag("fx")
class SettingsKeysRevealFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void aRevealRightAfterAnotherBeforeALayoutStillShowsItsRow() throws Exception {
        FxTestSupport.runOnFx(() -> {
            ListView<Object> list = new ListView<>();
            List<Object> items = new ArrayList<>();
            for (int i = 0; i < 60; i++) {
                items.add("row " + i);
            }
            list.getItems().setAll(items);
            Stage stage = new Stage();
            stage.setScene(new Scene(list, 200, 300));
            stage.show();
            try {
                list.applyCss();
                list.layout(); // rows 0 … ~12 on screen
                // A command picks a page far down the list, and a search picks one near the top, in one pulse.
                SettingsKeys.reveal(list, 55, item -> false);
                SettingsKeys.reveal(list, 3, item -> false);
                assertTrue(SettingsFrameFxTest.rowInView(list, 3), "row 3 is in view");
            } finally {
                stage.hide();
            }
        });
    }
}
