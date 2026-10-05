package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Labeled;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.stage.Stage;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Settings pages at the window's smallest size: nothing ellipsised, squeezed or left unnamed. */
@Tag("fx")
class SettingsLayoutFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
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

    private static boolean shown(Node n) {
        for (Node x = n; x != null; x = x.getParent()) {
            if (!x.isVisible()) {
                return false;
            }
        }
        return true;
    }

    /** What a single-line label actually paints when it differs from its text (i.e. it was ellipsised). */
    private static String ellipsised(Labeled labeled) {
        if (labeled.isWrapText()
                || labeled.getText() == null
                || labeled.getText().isEmpty()) {
            return null;
        }
        for (Node child : labeled.getChildrenUnmodifiable()) {
            if (child instanceof Text text
                    && child.getStyleClass().contains("text")
                    && !Objects.equals(text.getText(), labeled.getText())) {
                return text.getText();
            }
        }
        return null;
    }

    private static Object category(ListView<Object> sidebar, String name) {
        return sidebar.getItems().stream()
                .filter(i -> i instanceof Enum<?> e
                        && e.name().equals(name)
                        && !e.getDeclaringClass().getSimpleName().equals("Group"))
                .findFirst()
                .orElseThrow();
    }

    private interface Body {
        void run(SettingsWindow window, Stage stage, ListView<Object> sidebar, ScrollPane scroll);
    }

    /** Opens the real Settings window and asks for the old 720x480 minimum, then runs {@code body}. */
    private static void atSmallestSize(Body body) throws Exception {
        try (var fx = FxWindowFixture.create()) {
            SettingsWindow window = FxTestSupport.field(fx.controller, "settingsWindow");
            Stage[] stage = new Stage[1];
            FxTestSupport.runOnFx(() -> {
                window.show(FxTestSupport.<Stage>field(fx.controller, "stage"));
                stage[0] = FxTestSupport.field(window, "stage");
                stage[0].setWidth(720);
                stage[0].setHeight(480);
            });
            FxTestSupport.drainFx();
            try {
                FxTestSupport.runOnFx(() -> body.run(
                        window,
                        stage[0],
                        FxTestSupport.field(window, "sidebar"),
                        FxTestSupport.field(window, "contentScroll")));
            } finally {
                FxTestSupport.runOnFx(() -> stage[0].hide());
            }
        }
    }

    private static Node open(Stage stage, ListView<Object> sidebar, ScrollPane scroll, String page) {
        sidebar.getSelectionModel().select(category(sidebar, page));
        stage.getScene().getRoot().applyCss();
        stage.getScene().getRoot().layout();
        stage.getScene().getRoot().layout(); // wrapped labels settle their height on the second pass
        return scroll.getContent();
    }

    /** S4-5: a long title beside a switch wraps instead of ending in an ellipsis. */
    @Test
    void aLongRowTitleWrapsBesideItsSwitch() throws Exception {
        FxTestSupport.runOnFx(() -> {
            String title = "Projekt-Symbolindex (kein Sprachserver nötig) und noch etwas mehr Text dazu";
            Node row = SettingsWindow.settingRow(title, null, new SettingSwitch());
            Node shortRow = SettingsWindow.settingRow("Minimap", null, new SettingSwitch());
            VBox box = new VBox(row, shortRow);
            Scene scene = new Scene(box, 380, 300);
            scene.getStylesheets()
                    .add(SettingsLayoutFxTest.class
                            .getResource("/com/editora/styles/app.css")
                            .toExternalForm());
            box.applyCss();
            box.layout();
            box.layout();
            Label label = all(row, Label.class, new ArrayList<>()).get(0);
            Label oneLine = all(shortRow, Label.class, new ArrayList<>()).get(0);
            assertTrue(label.isWrapText());
            assertTrue(
                    label.getHeight() > oneLine.getHeight() * 1.5,
                    "the title takes two lines: " + label.getHeight() + " vs " + oneLine.getHeight());
            assertFalse(((SettingRowPane) row).isStacked(), "and the switch stays beside it");
        });
    }

    /** S4-6 / S2-18, S4-8, S4-11: the list-beside-form pages no longer collapse at the smallest size. */
    @Test
    void nothingIsEllipsisedOrSqueezedAtTheSmallestWindowSize() throws Exception {
        atSmallestSize((window, stage, sidebar, scroll) -> {
            assertTrue(stage.getWidth() >= 900 - 0.5, "the window cannot be made narrower than its pages need");
            for (String page : List.of(
                    "SNIPPETS", "TEMPLATES", "MACROS", "REMOTE", "TOOLBAR", "ADVANCED", "APPEARANCE", "KEYMAPS")) {
                Node content = open(stage, sidebar, scroll, page);
                assertTrue(
                        content.getLayoutBounds().getWidth()
                                <= scroll.getViewportBounds().getWidth() + 0.5,
                        page + " fits the viewport without a horizontal scrollbar");
                for (Button b : all(content, Button.class, new ArrayList<>())) {
                    if (shown(b)) {
                        assertEquals(null, ellipsised(b), page + ": button '" + b.getText() + "' is cut off");
                    }
                }
                for (Label l : all(content, Label.class, new ArrayList<>())) {
                    boolean chrome = l.getStyleClass().contains("snippet-bundled-tag")
                            || l.getStyleClass().contains("settings-hint")
                            || l.getStyleClass().contains("settings-section")
                            || l.getStyleClass().contains("settings-row-title");
                    if (shown(l) && chrome && l.getWidth() > 0) {
                        assertEquals(null, ellipsised(l), page + ": '" + l.getText() + "' is cut off");
                    }
                }
                for (ListView<?> list : all(content, ListView.class, new ArrayList<>())) {
                    if (shown(list) && list.getHeight() > 0) { // a combo's unshown popup list has no size
                        assertTrue(list.getWidth() >= 180, page + ": a list is " + list.getWidth() + "px wide");
                    }
                }
                for (ComboBox<?> combo : all(content, ComboBox.class, new ArrayList<>())) {
                    if (shown(combo)) {
                        assertTrue(combo.getWidth() >= 90, page + ": a combo is " + combo.getWidth() + "px wide");
                    }
                }
            }
        });
    }

    /** S4-10: the Appearance combos line up whatever the length of their hints. */
    @Test
    void theAppearanceCombosShareOneRightEdge() throws Exception {
        atSmallestSize((window, stage, sidebar, scroll) -> {
            stage.setWidth(1100);
            open(stage, sidebar, scroll, "APPEARANCE");
            List<Double> edges = new ArrayList<>();
            for (String field : List.of("languageCombo", "fontFamily", "themeCombo", "editorThemeCombo")) {
                Node combo = FxTestSupport.field(window, field);
                Bounds b = combo.localToScene(combo.getBoundsInLocal());
                edges.add(b.getMaxX());
            }
            for (double edge : edges) {
                assertEquals(edges.get(0), edge, 0.5, "combo right edges: " + edges);
            }
        });
    }

    /** S4-7, S4-16: a combo shows its selected value and a path field its placeholder. */
    @Test
    void combosAndPathFieldsAreWideEnoughForTheirOwnContent() throws Exception {
        atSmallestSize((window, stage, sidebar, scroll) -> {
            stage.setWidth(1180);
            for (String[] where :
                    new String[][] {{"COMPLETION", "inlayHintModeCombo"}, {"EDITOR", "indentStyleCombo"}}) {
                open(stage, sidebar, scroll, where[0]);
                ComboBox<String> combo = FxTestSupport.field(window, where[1]);
                for (String item : List.copyOf(combo.getItems())) {
                    combo.setValue(item);
                    stage.getScene().getRoot().applyCss();
                    stage.getScene().getRoot().layout();
                    for (Labeled cell : all(combo, Labeled.class, new ArrayList<>())) {
                        assertEquals(null, ellipsised(cell), where[1] + " cuts off '" + cell.getText() + "'");
                    }
                }
            }
            open(stage, sidebar, scroll, "DEBUG");
            Map<String, TextField> debugFields = FxTestSupport.field(window, "debugCommandFields");
            for (TextField field : debugFields.values()) {
                assertTrue(field.getWidth() >= 300, "a path field is " + field.getWidth() + "px wide");
            }
        });
    }

    /** S4-18, S4-19, S4-9. */
    @Test
    void controlsAreNamedEnabledOnlyWhenUsableAndTranslated() throws Exception {
        atSmallestSize((window, stage, sidebar, scroll) -> {
            for (String page : List.of("TOOLBAR", "MACROS", "TODO", "MARKDOWN", "TOOL_WINDOWS")) {
                Node content = open(stage, sidebar, scroll, page);
                for (ButtonBase b : all(content, ButtonBase.class, new ArrayList<>())) {
                    String text = b.getText() == null ? "" : b.getText().strip();
                    boolean wordless = text.isEmpty() || List.of("▲", "▼", "✕").contains(text);
                    if (wordless && shown(b)) {
                        assertTrue(
                                b.getAccessibleText() != null
                                        && !b.getAccessibleText().isBlank(),
                                page + ": '" + text + "' " + b.getClass().getSimpleName() + " has no accessible name");
                    }
                }
            }

            // A fresh config has no macros, remote sites, external tools or abbreviations to act on.
            Node macros = open(stage, sidebar, scroll, "MACROS");
            for (Button b : all(macros, Button.class, new ArrayList<>())) {
                if (List.of(
                                tr("settings.macro.addCommand"),
                                tr("settings.macro.addText"),
                                tr("settings.macro.removeStep"))
                        .contains(b.getText())) {
                    assertTrue(b.isDisabled(), "'" + b.getText() + "' is live with no macro selected");
                }
            }
            for (String[] where : new String[][] {
                {"REMOTE", "settings.remote.remove"},
                {"EXTERNAL_TOOLS", "settings.externalTool.remove"},
                {"ABBREVIATIONS", "settings.abbrev.remove"}
            }) {
                Node content = open(stage, sidebar, scroll, where[0]);
                ListView<?> list =
                        all(content, ListView.class, new ArrayList<>()).get(0);
                list.getSelectionModel().clearSelection();
                Button remove = all(content, Button.class, new ArrayList<>()).stream()
                        .filter(b -> tr(where[1]).equals(b.getText()))
                        .findFirst()
                        .orElseThrow();
                assertTrue(remove.isDisabled(), where[0] + ": Remove is live with nothing selected");
            }

            // The strings that were English literals come from the catalog.
            Node editor = open(stage, sidebar, scroll, "EDITOR");
            assertTrue(all(editor, Label.class, new ArrayList<>()).stream()
                    .anyMatch(l -> tr("settings.autoSave.delay").equals(l.getText())));
            assertEquals(tr("settings.toolWindows.side.left"), SettingsWindow.sideName(ToolWindow.Side.LEFT));
            assertEquals(tr("settings.toolWindows.side.bottom"), SettingsWindow.sideName(ToolWindow.Side.BOTTOM));
            assertNotEquals("settings.toolWindows.side.right", SettingsWindow.sideName(ToolWindow.Side.RIGHT));
            assertEquals("", SettingsWindow.sideName(null));

            // S4-12: the message names the switch that is off.
            assertEquals(tr("status.ai.masterDisabled"), AiCoordinator.disabledReason(false, false));
            assertEquals(tr("status.ai.masterDisabled"), AiCoordinator.disabledReason(false, true));
            assertEquals(tr("status.ai.disabled"), AiCoordinator.disabledReason(true, false));
            assertEquals(tr("status.ai.simpleMode"), AiCoordinator.disabledReason(true, true));
            assertNotEquals("status.ai.masterDisabled", tr("status.ai.masterDisabled"));
        });
    }
}
