package com.editora.ui;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import com.editora.config.ConfigManager;
import com.editora.config.Settings;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Modified markers, per-setting reset, "Show modified only", the restart note and the pages' help links. */
@Tag("fx")
class SettingsModifiedFxTest {

    /** Controls that are not a setting of their own, or that ask before they change (a modal no test answers). */
    private static final Set<String> NOT_SETTINGS =
            Set.of("shortcutFilter", "dictionaryInput", "searchField", "zenCheck", "expertCheck", "mcpCheck");

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static SettingsWindow open(FxWindowFixture fx) throws Exception {
        SettingsWindow window = fx.controller.settingsWindow;
        FxTestSupport.runOnFx(() -> window.show(FxTestSupport.<Stage>field(fx.controller, "stage")));
        return window;
    }

    /** The row (a card row) that holds {@code control}, or the switch bound to it. */
    private static Node rowOf(SettingsWindow window, Node control) {
        List<SettingsSearch.Row> rows = FxTestSupport.field(window, "rows");
        for (SettingsSearch.Row r : rows) {
            if (holds(r.node(), control)) {
                return r.node();
            }
        }
        throw new AssertionError("no row holds " + control);
    }

    private static boolean holds(Node node, Node control) {
        if (node == control || node.getProperties().get(SettingSwitch.CHECK) == control) {
            return true;
        }
        if (node instanceof Parent p) {
            for (Node c : p.getChildrenUnmodifiable()) {
                if (holds(c, control)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Test
    void everyControlHasTheKeyItWritesInTheTable() throws Exception {
        try (var fx = FxWindowFixture.create()) {
            SettingsWindow window = open(fx);
            ConfigManager config = FxTestSupport.field(fx.controller, "config");
            Map<String, String> table = SettingsModified.table();
            ObjectMapper json = new ObjectMapper();
            Map<String, Node> controls = new LinkedHashMap<>();
            for (Field f : SettingsWindow.class.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || NOT_SETTINGS.contains(f.getName())) {
                    continue;
                }
                f.setAccessible(true);
                Object v = f.get(window);
                if (v instanceof CheckBox || v instanceof Spinner || v instanceof ComboBox || v instanceof TextField) {
                    controls.put(f.getName(), (Node) v);
                } else if (v instanceof Map<?, ?> m) {
                    m.forEach((k, c) -> {
                        if (c instanceof CheckBox || c instanceof TextField || c instanceof ComboBox) {
                            controls.put(f.getName() + "[" + k + "]", (Node) c);
                        }
                    });
                }
            }
            List<String> problems = new ArrayList<>();
            for (var e : controls.entrySet()) {
                FxTestSupport.runOnFx(() -> {
                    JsonNode before = json.valueToTree(config.getSettings());
                    change(e.getValue());
                    JsonNode after = json.valueToTree(config.getSettings());
                    List<String> wrote = new ArrayList<>();
                    after.fieldNames().forEachRemaining(k -> {
                        if (!after.get(k).equals(before.get(k)) && !k.equals("editorThemeUserSet")) {
                            wrote.add(k);
                        }
                    });
                    String listed = table.get(e.getKey());
                    if (listed == null) {
                        problems.add(e.getKey() + " is not in settings-controls.properties (it wrote " + wrote + ")");
                    } else if (!wrote.isEmpty() && !wrote.contains(listed)) {
                        problems.add(e.getKey() + "=" + listed + " but a change wrote " + wrote);
                    }
                });
            }
            assertTrue(problems.isEmpty(), String.join("\n", problems));
            FxTestSupport.runOnFx(
                    () -> FxTestSupport.<Stage>field(window, "stage").hide());
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void change(Node c) {
        switch (c) {
            case CheckBox cb -> cb.setSelected(!cb.isSelected());
            case Spinner<?> sp -> sp.increment(1);
            case ComboBox cb -> {
                List items = cb.getItems();
                if (items.size() > 1) {
                    int i = items.indexOf(cb.getValue());
                    cb.setValue(items.get(i + 1 < items.size() ? i + 1 : 0));
                }
            }
            case TextField tf -> {
                tf.setText("probe-x");
                tf.fireEvent(new javafx.event.ActionEvent());
            }
            default -> {}
        }
    }

    @Test
    void aChangedRowIsMarkedCountedInTheSidebarAndPutBackByItsReset() throws Exception {
        try (var fx = FxWindowFixture.create()) {
            SettingsWindow window = open(fx);
            ConfigManager config = FxTestSupport.field(fx.controller, "config");
            CheckBox minimap = FxTestSupport.field(window, "minimapCheck");
            Spinner<Integer> tab = FxTestSupport.field(window, "tabSizeSpinner");
            ComboBox<String> theme = FxTestSupport.field(window, "themeCombo");
            ComboBox<String> editorTheme = FxTestSupport.field(window, "editorThemeCombo");
            boolean defaultMinimap = new Settings().isShowMinimap();
            FxTestSupport.runOnFx(() -> {
                Node minimapRow = rowOf(window, minimap);
                assertFalse(SettingRowState.of(minimapRow).isModified(), "at its default: no marker");
                minimap.setSelected(!defaultMinimap);
                tab.getValueFactory().setValue(8);
                theme.setValue("Editora Dark"); // the editor theme follows: that is not a change of its own
                SettingRowState state = SettingRowState.of(minimapRow);
                assertTrue(state.isModified());
                assertTrue(minimapRow.getPseudoClassStates().contains(SettingRowState.MODIFIED));
                assertTrue(SettingRowState.of(rowOf(window, tab)).isModified());
                assertTrue(SettingRowState.of(rowOf(window, theme)).isModified());
                assertFalse(SettingRowState.of(rowOf(window, editorTheme)).isModified(), "followed, not picked");
                Map<Object, Integer> perPage = FxTestSupport.field(window, "modifiedPerPage");
                ListView<Object> sidebar = FxTestSupport.field(window, "sidebar");
                assertEquals(2, perPage.get(SettingsFrameFxTest.category(sidebar, "EDITOR")), "minimap + tab size");

                assertTrue(state.resetLink().isVisible());
                state.resetLink().fire(); // Reset to default: this setting only
                assertEquals(defaultMinimap, config.getSettings().isShowMinimap());
                assertEquals(defaultMinimap, minimap.isSelected(), "the control shows it");
                assertFalse(SettingRowState.of(minimapRow).isModified());
                assertEquals(8, config.getSettings().getTabSize(), "other changes stay");
                assertEquals(1, perPage(window).get(SettingsFrameFxTest.category(sidebar, "EDITOR")));
                FxTestSupport.<Stage>field(window, "stage").hide();
            });
        }
    }

    private static Map<Object, Integer> perPage(SettingsWindow window) {
        return FxTestSupport.field(window, "modifiedPerPage");
    }

    @Test
    void modifiedOnlyListsJustTheChangedRowsFromTheToggleOrTheSearch() throws Exception {
        try (var fx = FxWindowFixture.create()) {
            SettingsWindow window = open(fx);
            CheckBox minimap = FxTestSupport.field(window, "minimapCheck");
            CheckBox git = FxTestSupport.field(window, "gitCheck");
            ToggleButton only = FxTestSupport.field(window, "modifiedOnly");
            TextField search = FxTestSupport.field(window, "searchField");
            FxTestSupport.runOnFx(() -> {
                only.fire();
                // The test window already differs on its own (it turns remote execution off): those pages stay.
                Set<String> already = shownPages(window);
                assertFalse(already.contains("EDITOR") || already.contains("GIT"));
                Set<String> expected = new java.util.TreeSet<>(already);
                expected.addAll(Set.of("EDITOR", "GIT"));
                minimap.setSelected(!minimap.isSelected());
                git.setSelected(!git.isSelected());
                assertEquals(expected, shownPages(window), "a change joins the list at once");
                assertTrue(rowOf(window, minimap).isVisible());
                only.fire();
                search.setText("@modified");
                assertEquals(expected, shownPages(window), "@modified does the same");
                search.setText("@modified minimap");
                assertEquals(Set.of("EDITOR"), shownPages(window), "with words, both apply");
                git.setSelected(!git.isSelected());
                minimap.setSelected(!minimap.isSelected());
                search.setText("@modified");
                assertEquals(already, shownPages(window), "rows put back leave the list");
                search.setText("@modified zzzz");
                Label empty = FxTestSupport.field(window, "searchEmpty");
                assertTrue(empty.isVisible());
                FxTestSupport.<Stage>field(window, "stage").hide();
            });
        }
    }

    private static Set<String> shownPages(SettingsWindow window) {
        ListView<Object> sidebar = FxTestSupport.field(window, "sidebar");
        Set<Object> hidden = FxTestSupport.field(window, "searchHiddenCats");
        Set<String> out = new java.util.TreeSet<>();
        for (Object item : sidebar.getItems()) {
            if (item.getClass().getSimpleName().equals("Category") && !hidden.contains(item)) {
                out.add(((Enum<?>) item).name());
            }
        }
        return out;
    }

    @Test
    void aRestartOnlyChangeShowsTheRestartNoteAndRestartNowRunsTheRestart() throws Exception {
        try (var fx = FxWindowFixture.create()) {
            ConfigManager config = FxTestSupport.field(fx.controller, "config");
            FxTestSupport.runOnFx(() -> {
                RestartPending.forgetStart();
                RestartPending.captureStart(config.getSettings()); // this "run" starts here
            });
            SettingsWindow window = open(fx);
            int[] restarts = {0};
            CheckBox extended = FxTestSupport.field(window, "extendedWindowCheck");
            FxTestSupport.runOnFx(() -> {
                window.setOnRestart(() -> restarts[0]++);
                HBox box = FxTestSupport.field(window, "restartBox");
                assertFalse(box.isVisible());
                extended.setSelected(!extended.isSelected());
                assertTrue(box.isVisible(), "a restart-only setting changed");
                Label note = FxTestSupport.field(window, "restartNote");
                assertEquals(com.editora.i18n.Messages.tr("settings.restart.pending", 1), note.getText());
                ((Button) box.getChildren().get(1)).fire();
                assertEquals(1, restarts[0], "Restart Now runs the restart");
                extended.setSelected(!extended.isSelected());
                assertFalse(box.isVisible(), "back to what this run started with");
                FxTestSupport.<Stage>field(window, "stage").hide();
                RestartPending.forgetStart();
            });
        }
    }

    @Test
    void eachPageTitleLinksItsDocsPage() throws Exception {
        try (var fx = FxWindowFixture.create()) {
            SettingsWindow window = open(fx);
            List<String> opened = new ArrayList<>();
            FxTestSupport.runOnFx(() -> {
                window.setOpenUrl(opened::add);
                Map<Object, javafx.scene.layout.Region> pages = FxTestSupport.field(window, "pages");
                ListView<Object> sidebar = FxTestSupport.field(window, "sidebar");
                for (Object item : sidebar.getItems()) {
                    if (pages.get(item) instanceof VBox page) {
                        Hyperlink help = (Hyperlink) page.getChildren().get(0).lookup(".settings-help-link");
                        assertNotNull(help, item + " has a help link");
                    }
                }
                VBox keymaps = (VBox) pages.get(SettingsFrameFxTest.category(sidebar, "KEYMAPS"));
                ((Hyperlink) keymaps.getChildren().get(0).lookup(".settings-help-link")).fire();
                assertEquals(List.of(com.editora.AppInfo.docsUrl() + "/keymaps"), opened);
                FxTestSupport.<Stage>field(window, "stage").hide();
            });
        }
    }
}
