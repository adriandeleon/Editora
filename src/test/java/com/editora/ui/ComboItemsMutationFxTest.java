package com.editora.ui;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import javafx.collections.FXCollections;
import javafx.scene.Scene;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ListView;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import com.editora.config.RunConfiguration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A {@code ComboBox} whose items <b>shrink while its popup list is being clicked</b> makes JavaFX throw
 * {@code IndexOutOfBoundsException} out of its own event handling, onto the uncaught handler — the app sees
 * a crash with a stack containing no application frames at all.
 *
 * <p>The first test pins that behaviour of the toolkit (JavaFX 26), because it is the reason the second one
 * exists: the toolbar's run-configuration selector must build its row list <em>before</em> assigning it, so
 * its items never shrink under a click. Written after an
 * {@code IndexOutOfBoundsException: [ fromIndex: 0, toIndex: 1, size: 0 ]} was reported from a session with
 * no reproduction: this is the mechanism, reproduced. It is not proof that the selector was the control
 * involved — nothing in that report names one — only that this shape produces that exception.
 */
@Tag("fx")
class ComboItemsMutationFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @SuppressWarnings("unchecked")
    private static ListView<String> popupList(ComboBox<String> combo) {
        return (ListView<String>) FxTestSupport.call(combo.getSkin(), "getListView", new Class<?>[] {});
    }

    /** Clicks {@code row} in a combo's popup list, returning anything the FX thread threw out uncaught. */
    private static Throwable clickRow(int row, Consumer<ComboBox<String>> wire) throws Exception {
        AtomicReference<Throwable> caught = new AtomicReference<>();
        FxTestSupport.runOnFx(() -> {
            Thread.UncaughtExceptionHandler prior = Thread.currentThread().getUncaughtExceptionHandler();
            Thread.currentThread().setUncaughtExceptionHandler((t, e) -> caught.set(e));
            ComboBox<String> combo = new ComboBox<>(FXCollections.observableArrayList("a", "b", "c"));
            Stage stage = new Stage();
            stage.setScene(new Scene(new StackPane(combo), 300, 120));
            stage.show();
            combo.show(); // creates the popup list, and with it the ListViewBehavior that throws
            combo.getSelectionModel().select(0);
            wire.accept(combo);
            try {
                popupList(combo).getSelectionModel().clearAndSelect(row);
            } finally {
                stage.close();
                Thread.currentThread().setUncaughtExceptionHandler(prior);
            }
        });
        return caught.get();
    }

    @Test
    void itemsThatShrinkUnderAClickCrashTheToolkit() throws Exception {
        Throwable crash = clickRow(
                1,
                combo -> combo.valueProperty().addListener((o, was, now) -> {
                    if (now != null && !combo.getItems().isEmpty()) {
                        combo.getItems().clear();
                    }
                }));
        assertNotNull(crash, "precondition for the fix below: this is the shape that crashes");
        assertTrue(crash instanceof IndexOutOfBoundsException, String.valueOf(crash));

        // The control: the same click with the items left alone is quiet, so it is the mutation that does it.
        assertNull(clickRow(1, combo -> {}));
    }

    /**
     * The selector offers a trailing "Edit Configurations…" sentinel, so rebuilding it used to be
     * {@code setAll(configs)} followed by {@code add(sentinel)} — two change events, the first of which
     * leaves the items one row shorter than they started. That is the shrink above.
     */
    @Test
    void theRunConfigSelectorRebuildsItsRowsInOneAssignment() throws Exception {
        FxWindowFixture fx = FxWindowFixture.create();
        try {
            ComboBox<RunConfiguration> combo = FxTestSupport.field(fx.controller, "runConfigCombo");
            java.util.List<Integer> sizes = new java.util.ArrayList<>();
            FxTestSupport.runOnFx(
                    () -> combo.getItems().addListener((javafx.collections.ListChangeListener<RunConfiguration>)
                            c -> sizes.add(c.getList().size())));

            FxTestSupport.runOnFx(() -> {
                com.editora.config.ConfigManager cfg = FxTestSupport.field(fx.controller, "config");
                cfg.getWorkspaceState()
                        .setRunConfigurations(new java.util.ArrayList<>(List.of(
                                new RunConfiguration("A", "com.example.A", "", "", "", ""),
                                new RunConfiguration("B", "com.example.B", "", "", "", ""))));
                FxTestSupport.invoke(fx.controller, "refreshRunConfigs");
            });

            assertTrue(sizes.size() == 1, "one change event, not a shrink then a grow: " + sizes);
            assertTrue(sizes.get(0) == 3, "two configurations plus the sentinel: " + sizes);
        } finally {
            fx.dispose();
        }
    }
}
