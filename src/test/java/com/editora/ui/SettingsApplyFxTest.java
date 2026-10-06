package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javafx.event.ActionEvent;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.layout.Region;
import javafx.stage.Stage;

import com.editora.command.CommandRegistry;
import com.editora.config.Settings;
import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How the Settings window writes a change, and how it is kept in step with changes made elsewhere. */
@Tag("fx")
class SettingsApplyFxTest {

    private FxWindowFixture fx;
    private Settings settings;
    private SettingsWindow window;
    private Stage owner;
    private CommandRegistry registry;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @BeforeEach
    void setUp() throws Exception {
        fx = FxWindowFixture.create();
        settings = fx.shared.getSettings();
        window = FxTestSupport.field(fx.controller, "settingsWindow");
        owner = FxTestSupport.field(fx.controller, "stage");
        registry = FxTestSupport.field(fx.controller, "registry");
    }

    @AfterEach
    void tearDown() throws Exception {
        FxTestSupport.runOnFx(() -> hide(window));
        fx.dispose();
    }

    private static void hide(SettingsWindow w) {
        Stage stage = FxTestSupport.field(w, "stage");
        stage.hide();
    }

    private <T> T control(String name) {
        return FxTestSupport.field(window, name);
    }

    private static void flip(CheckBox check) {
        check.setSelected(!check.isSelected());
    }

    private EditorBuffer addBuffer(MainController controller) {
        EditorBuffer buffer = new EditorBuffer();
        buffer.setContent("one\ntwo\n");
        FxTestSupport.call(controller, "addBuffer", new Class<?>[] {EditorBuffer.class, boolean.class}, buffer, true);
        return buffer;
    }

    private static boolean lineNumbers(EditorBuffer buffer) {
        return FxTestSupport.<Boolean>field(buffer, "lineNumbersVisible");
    }

    private static <T extends Node> List<T> all(Node root, Class<T> type, List<T> out) {
        if (type.isInstance(root)) {
            out.add(type.cast(root));
        }
        if (root instanceof Parent p) {
            p.getChildrenUnmodifiable().forEach(c -> all(c, type, out));
        }
        return out;
    }

    /** S2-1: an emptied Font size field used to become a null value that made every later change a no-op. */
    @Test
    void emptyingTheFontSizeFieldDoesNotStopLaterChangesFromApplying() throws Exception {
        FxTestSupport.runOnFx(() -> {
            EditorBuffer buffer = addBuffer(fx.controller);
            window.show(owner);
            Spinner<Integer> fontSize = control("fontSize");
            int before = settings.getFontSize();

            fontSize.getEditor().setText("");
            fontSize.commitValue(); // the spinner's own commit when focus leaves it

            assertEquals(before, fontSize.getValue(), "the value is never null");
            boolean shown = lineNumbers(buffer);
            flip(control("lineNumbersCheck"));
            assertEquals(!shown, lineNumbers(buffer), "a later change is applied to the open editors");
            assertEquals(before, settings.getFontSize());
        });
    }

    /** S2-2: the spinner covers the range the setting allows, and unrelated changes do not rewrite the font. */
    @Test
    void anUnrelatedChangeDoesNotRewriteTheFont() throws Exception {
        FxTestSupport.runOnFx(() -> {
            settings.setFontSize(60); // the palette command allows up to 72
            window.show(owner);
            Spinner<Integer> fontSize = control("fontSize");
            assertEquals(60, fontSize.getValue(), "the control shows the size in force");

            flip(control("minimapCheck"));
            assertEquals(60, settings.getFontSize());

            fontSize.getValueFactory().setValue(18); // while the font controls themselves still write it
            assertEquals(18, settings.getFontSize());
            ComboBox<String> family = control("fontFamily");
            String other = family.getItems().stream()
                    .filter(f -> !f.equals(settings.getFontFamily()))
                    .findFirst()
                    .orElseThrow();
            family.setValue(other);
            assertEquals(other, settings.getFontFamily());
        });
    }

    /** S2-3: re-syncing one control of a hidden window used to write its stale font controls back. */
    @Test
    void reSyncingAHiddenWindowsControlsWritesNothingBack() throws Exception {
        FxTestSupport.runOnFx(() -> {
            window.show(owner);
            hide(window);
            settings.setFontSize(20); // changed from the palette while Settings is closed
            settings.setTodoTagColor("#123456");

            assertTrue(registry.run("view.togglePersonalDictionary"));
            assertTrue(registry.run("view.toggleTechnicalDictionary"));
            window.syncTodoPartColors();

            assertEquals(20, settings.getFontSize());
            CheckBox personal = control("dictEnableCheck");
            assertEquals(settings.isPersonalDictionary(), personal.isSelected());
        });
    }

    /** S2-15: a value the palette command accepts must be the value Settings shows. */
    @Test
    void spinnersShowValuesAcrossTheRangeThePaletteCommandsAccept() throws Exception {
        FxTestSupport.runOnFx(() -> {
            settings.setAutoSaveDelayMillis(Settings.MAX_AUTO_SAVE_DELAY_SECONDS * 1000);
            settings.setHistoryMaxTotalMb(Settings.MAX_HISTORY_TOTAL_MB);
            settings.setFillColumn(250);
            settings.setFontSize(Settings.MIN_FONT_SIZE);
            window.show(owner);

            assertEquals(
                    3600, this.<Spinner<Integer>>control("autoSaveDelaySpinner").getValue());
            assertEquals(
                    10_000,
                    this.<Spinner<Integer>>control("historyMaxTotalSpinner").getValue());
            assertEquals(
                    250, this.<Spinner<Integer>>control("fillColumnSpinner").getValue());
            assertEquals(
                    Settings.MIN_FONT_SIZE,
                    this.<Spinner<Integer>>control("fontSize").getValue());

            Spinner<Integer> tab = control("tabSizeSpinner");
            tab.getEditor().setText("abc");
            tab.getEditor().fireEvent(new ActionEvent());
            assertEquals(settings.getTabSize(), tab.getValue());
        });
    }

    /** S2-10: the bespoke view toggles did not move an open Settings window's switches. */
    @Test
    void paletteTogglesMoveTheSwitchesOfAnOpenSettingsWindow() throws Exception {
        FxTestSupport.runOnFx(() -> {
            window.show(owner);
            Map<String, String> toggles = Map.of(
                    "view.toggleColumnRuler", "columnRulerCheck",
                    "view.toggleLineHighlight", "lineHighlightCheck",
                    "view.toggleLineNumbers", "lineNumbersCheck",
                    "view.toggleMinimap", "minimapCheck",
                    "view.toggleWhitespace", "whitespaceCheck",
                    "view.toggleSpellCheck", "spellCheckBox");
            toggles.forEach((command, field) -> {
                CheckBox check = control(field);
                boolean before = check.isSelected();
                assertTrue(registry.run(command), command);
                assertEquals(!before, check.isSelected(), command + " moves its switch");
            });
            ComboBox<String> language = control("spellLanguageCombo");
            assertEquals(!settings.isSpellCheck(), language.isDisable());
        });
    }

    /** S2-11: the two focus modes exclude each other, and both have commands. */
    @Test
    void zenAndExpertSwitchesFollowTheRealMode() throws Exception {
        FxTestSupport.runOnFx(() -> {
            window.show(owner);
            CheckBox zen = control("zenCheck");
            CheckBox expert = control("expertCheck");

            expert.setSelected(true);
            zen.setSelected(true);
            assertTrue(zen.isSelected());
            assertFalse(expert.isSelected(), "entering Zen left Expert");

            assertTrue(registry.run("view.toggleZen"));
            assertFalse(zen.isSelected(), "the palette command moves the switch");
            assertTrue(registry.run("view.toggleExpert"));
            assertTrue(expert.isSelected());
            assertTrue(registry.run("view.toggleExpert"));
            assertFalse(expert.isSelected());
        });
    }

    /** S3-3: a command field takes effect on Enter or focus loss, not for every half-typed prefix. */
    @Test
    void aCommandFieldIsCommittedOnEnterNotPerKeystroke() throws Exception {
        FxTestSupport.runOnFx(() -> {
            window.show(owner);
            Map<String, TextField> lsp = control("lspCommandFields");
            TextField java = lsp.get("java");
            String before = settings.getJavaLspCommand();

            for (String typed : List.of("/", "/o", "/op", "/opt/jdtls")) {
                java.setText(typed);
                assertEquals(before, settings.getJavaLspCommand(), "nothing is applied while typing");
            }
            java.fireEvent(new ActionEvent());
            assertEquals("/opt/jdtls", settings.getJavaLspCommand());

            Map<String, TextField> debug = control("debugCommandFields");
            Map.Entry<String, TextField> adapter = debug.entrySet().iterator().next();
            String shown = adapter.getValue().getText();
            adapter.getValue().setText(shown + "x");
            adapter.getValue().setText(shown); // typed and deleted again
            Path file = fx.configDir.resolve("settings.json");
            try {
                Files.deleteIfExists(file);
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            adapter.getValue().fireEvent(new ActionEvent());
            assertFalse(Files.exists(file), "an unchanged command is not saved and re-applied");
            flip(control("minimapCheck"));
            assertTrue(Files.exists(file), "(a real change is written at once)");
        });
    }

    /** S3-11: with Settings open in two windows, one window's stale controls used to revert the other's change. */
    @Test
    void aSecondOpenSettingsWindowFollowsChangesMadeInTheFirst() throws Exception {
        FxTestSupport.runOnFx(() -> {
            fx.windowManager.newWindow();
            List<?> holders = FxTestSupport.field(fx.windowManager, "windows");
            MainController other = (MainController)
                    FxTestSupport.call(holders.get(holders.size() - 1), "controller", new Class<?>[] {});
            assertNotSame(fx.controller, other);
            SettingsWindow otherSettings = FxTestSupport.field(other, "settingsWindow");
            window.show(owner);
            otherSettings.show(FxTestSupport.field(other, "stage"));
            try {
                Spinner<Integer> tabB = FxTestSupport.field(otherSettings, "tabSizeSpinner");
                Spinner<Integer> fontB = FxTestSupport.field(otherSettings, "fontSize");
                tabB.getValueFactory().setValue(2);
                fontB.getValueFactory().setValue(20);

                assertEquals(2, this.<Spinner<Integer>>control("tabSizeSpinner").getValue(), "A re-read tab size");
                assertEquals(20, this.<Spinner<Integer>>control("fontSize").getValue(), "and font size");

                flip(control("minimapCheck")); // an unrelated change in A
                assertEquals(20, settings.getFontSize());
                assertEquals(2, settings.getTabSize());
                CheckBox minimapB = FxTestSupport.field(otherSettings, "minimapCheck");
                assertEquals(settings.isShowMinimap(), minimapB.isSelected(), "B follows A as well");
            } finally {
                hide(otherSettings);
            }
        });
    }

    /** S3-13: a Tool Windows row showed the side and visibility it was built with, for good. */
    @Test
    void toolWindowRowsAreReReadWhenSettingsIsShownAgain() throws Exception {
        FxTestSupport.runOnFx(() -> {
            ToolWindowManager toolWindows = FxTestSupport.field(fx.controller, "toolWindows");
            ToolWindow structure = toolWindows.getStripeToolWindows().stream()
                    .filter(tw -> "structure".equals(tw.getId()))
                    .findFirst()
                    .orElseThrow();
            toolWindows.setVisible(structure, true);
            window.show(owner);
            hide(window);

            ToolWindow.Side moved = toolWindows.currentSide(structure) == ToolWindow.Side.LEFT
                    ? ToolWindow.Side.RIGHT
                    : ToolWindow.Side.LEFT;
            toolWindows.setSide(structure, moved);
            toolWindows.setVisible(structure, false);
            window.show(owner);

            Map<?, Region> pages = FxTestSupport.field(window, "pages");
            SettingSwitch show = null;
            ComboBox<?> side = null;
            for (Region page : pages.values()) {
                for (SettingSwitch sw : all(page, SettingSwitch.class, new ArrayList<>())) {
                    if (structure.getTitle().equals(sw.getAccessibleText())) {
                        show = sw;
                    }
                }
                for (ComboBox<?> combo : all(page, ComboBox.class, new ArrayList<ComboBox>())) {
                    if (structure.getTitle().equals(combo.getAccessibleText())
                            && combo.getValue() instanceof ToolWindow.Side) {
                        side = combo;
                    }
                }
            }
            assertNotNull(show, "the Structure row's switch");
            assertNotNull(side, "the Structure row's side combo");
            assertFalse(show.isSelected(), "hidden outside Settings");
            assertEquals(moved, side.getValue(), "moved outside Settings");
            assertTrue(side.isDisable());
            assertFalse(toolWindows.isVisible(structure), "re-reading the row changes nothing");
            assertEquals(moved, toolWindows.currentSide(structure));
        });
    }
}
