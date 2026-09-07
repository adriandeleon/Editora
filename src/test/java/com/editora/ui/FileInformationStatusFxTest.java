package com.editora.ui;

import javafx.scene.control.Label;

import com.editora.command.CommandRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** File Information has one visual toggle: the file-size segment in the status bar. */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FileInformationStatusFxTest {

    private FxWindowFixture fx;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
    }

    @AfterAll
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    @Test
    void sizeSegmentTogglesWindowAndReplacesStripeIcon() throws Exception {
        ToolWindowManager manager = FxTestSupport.field(fx.controller, "toolWindows");
        ToolWindow fileInfo = FxTestSupport.field(fx.controller, "fileInfoToolWindow");
        StatusBar statusBar = FxTestSupport.field(fx.controller, "statusBar");
        Label size = FxTestSupport.field(statusBar, "size");
        CommandRegistry registry = FxTestSupport.field(fx.controller, "registry");

        FxTestSupport.runOnFx(() -> {
            registry.run("file.new"); // makes the buffer-scoped File Information window available
            manager.close(fileInfo);
            size.getOnMouseClicked().handle(null);
        });
        assertTrue(FxTestSupport.callOnFx(() -> manager.isOpen(fileInfo)));

        FxTestSupport.runOnFx(() -> size.getOnMouseClicked().handle(null));
        assertFalse(FxTestSupport.callOnFx(() -> manager.isOpen(fileInfo)));
        assertTrue(manager.getRegisteredToolWindows().contains(fileInfo));
        assertFalse(
                manager.getStripeToolWindows().contains(fileInfo),
                "File Information must not be offered on the stripe");
        assertTrue(FxTestSupport.callOnFx(() -> size.getStyleClass().contains("status-segment-clickable")));
    }
}
