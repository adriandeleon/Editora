package com.editora.ui;

import javafx.scene.control.Tab;

import com.editora.command.CommandRegistry;
import com.editora.config.Settings;
import com.editora.editor.EditorBuffer;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives service-free, observable commands through the real {@link CommandRegistry} against a fully-wired
 * window, exercising the {@code ui/MainController} dispatch paths end-to-end: text zoom (persisted in
 * {@link Settings#getFontZoom()}), the read-only toggle on the active buffer, the view-option toggles
 * (line numbers / minimap / whitespace), and select-all. The palette + keymap dispatch through the same
 * registry, so this covers the command-execution surface without simulating key events.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MainControllerCommandsFxTest {

    private FxWindowFixture fx;
    private CommandRegistry registry;
    private Settings settings;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
        registry = FxTestSupport.field(fx.controller, "registry");
        settings = fx.shared.getSettings();
    }

    @AfterAll
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    private void run(String id) throws Exception {
        FxTestSupport.runOnFx(() -> registry.run(id));
    }

    private EditorBuffer addActiveBuffer(String content) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setContent(content);
            FxTestSupport.call(fx.controller, "addBuffer", new Class[] {EditorBuffer.class, boolean.class}, b, true);
            return b;
        });
    }

    @Test
    void textZoomCommandsAdjustAndResetTheFontZoom() throws Exception {
        run("view.textZoomReset");
        assertEquals(1.0, settings.getFontZoom(), 1e-9, "reset returns to 100%");

        run("view.textZoomIn");
        assertEquals(1.1, settings.getFontZoom(), 1e-9, "zoom in steps +10%");

        run("view.textZoomIn");
        assertEquals(1.2, settings.getFontZoom(), 1e-9);

        run("view.textZoomOut");
        assertEquals(1.1, settings.getFontZoom(), 1e-9, "zoom out steps -10%");

        run("view.textZoomReset");
        assertEquals(1.0, settings.getFontZoom(), 1e-9);
    }

    @Test
    void toggleReadOnlyFlipsTheActiveBufferViewMode() throws Exception {
        EditorBuffer b = addActiveBuffer("editable content\n");
        assertTrue(FxTestSupport.callOnFx(b::isEditable), "starts editable");

        run("view.toggleReadOnly");
        assertTrue(FxTestSupport.callOnFx(b::isViewMode), "command enters view mode");
        assertFalse(FxTestSupport.callOnFx(b::isEditable));

        run("view.toggleReadOnly");
        assertFalse(FxTestSupport.callOnFx(b::isViewMode), "command leaves view mode");
        assertTrue(FxTestSupport.callOnFx(b::isEditable));

        closeActiveTab();
    }

    @Test
    void viewOptionTogglesFlipTheSettingAndRestore() throws Exception {
        assertToggleRoundTrips("view.toggleLineNumbers", settings::isShowLineNumbers);
        assertToggleRoundTrips("view.toggleMinimap", settings::isShowMinimap);
        assertToggleRoundTrips("view.toggleWhitespace", settings::isShowWhitespace);
    }

    @Test
    void selectAllSelectsTheWholeActiveBuffer() throws Exception {
        String content = "line one\nline two\nline three\n";
        EditorBuffer b = addActiveBuffer(content);
        run("edit.selectAll");
        CodeArea area = FxTestSupport.field(b, "area");
        assertEquals(
                content.length(),
                FxTestSupport.callOnFx(() -> area.getSelectedText().length()),
                "the entire document is selected");
        closeActiveTab();
    }

    private void assertToggleRoundTrips(String commandId, java.util.function.BooleanSupplier getter) throws Exception {
        boolean before = getter.getAsBoolean();
        run(commandId);
        assertFalse(before == getter.getAsBoolean(), commandId + " flips the setting");
        run(commandId);
        assertEquals(before, getter.getAsBoolean(), commandId + " restores the setting");
    }

    private void closeActiveTab() throws Exception {
        FxTestSupport.runOnFx(() -> {
            javafx.scene.control.TabPane tabPane = FxTestSupport.field(fx.controller, "tabPane");
            Tab sel = tabPane.getSelectionModel().getSelectedItem();
            if (sel != null) {
                FxTestSupport.call(fx.controller, "closeTab", new Class[] {Tab.class}, sel);
            }
        });
    }
}
