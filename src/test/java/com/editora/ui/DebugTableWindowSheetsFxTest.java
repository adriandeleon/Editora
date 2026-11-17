package com.editora.ui;

import java.util.List;
import java.util.function.Consumer;

import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import com.editora.dap.DapModels;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A View as Table window follows its owner's stylesheets while it shows, and lets go of them when it closes: the
 * owner's list kept a listener of every closed table, and with it the table.
 */
@Tag("fx")
class DebugTableWindowSheetsFxTest {

    private static final DebugTableWindow.Actions NONE = new DebugTableWindow.Actions() {
        @Override
        public void children(
                int ref, @Nullable String filter, int start, int count, Consumer<List<DapModels.VariableInfo>> cb) {
            cb.accept(List.of());
        }

        @Override
        public void openText(String title, String text, String language) {}

        @Override
        public void evaluate(String expression, Consumer<DapModels.@Nullable EvalResult> cb) {
            cb.accept(null);
        }

        @Override
        public boolean canRefresh() {
            return false;
        }
    };

    @Test
    void aClosedTableNoLongerFollowsItsOwnersStylesheets() throws Exception {
        FxTestSupport.bootToolkit();
        FxTestSupport.runOnFx(() -> {
            Stage owner = new Stage();
            owner.setScene(new Scene(new StackPane(), 200, 100));
            owner.getScene().getStylesheets().setAll("a.css");
            owner.show();
            try {
                DebugTableWindow window = new DebugTableWindow(NONE);
                window.show(owner, "rows", "rows", 1, 0, 0);
                List<String> shown = window.stage().getScene().getStylesheets();
                assertEquals(List.of("a.css"), shown);
                owner.getScene().getStylesheets().setAll("b.css");
                assertEquals(List.of("b.css"), shown, "a theme switch while it shows");
                window.hide();
                owner.getScene().getStylesheets().setAll("c.css");
                assertEquals(List.of("b.css"), shown, "closed: no longer listening");
                window.show(owner, "rows", "rows", 1, 0, 0);
                assertEquals(List.of("c.css"), shown, "shown again: caught up and listening");
                owner.getScene().getStylesheets().setAll("d.css");
                assertEquals(List.of("d.css"), shown);
                window.hide();
            } finally {
                owner.hide();
            }
        });
    }
}
