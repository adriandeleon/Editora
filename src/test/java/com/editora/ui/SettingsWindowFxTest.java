package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import javafx.geometry.Rectangle2D;
import javafx.scene.AccessibleAttribute;
import javafx.scene.AccessibleRole;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import atlantafx.base.controls.ToggleSwitch;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Settings window as a keyboard / screen-reader user and a small screen meet it. */
@Tag("fx")
class SettingsWindowFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static KeyEvent press(KeyCode code) {
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false);
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

    @Test
    void aSwitchTogglesFromTheKeyboardAndIsAnnouncedAsANamedToggle() throws Exception {
        FxTestSupport.runOnFx(() -> {
            CheckBox check = new CheckBox("Show minimap");
            SettingSwitch sw = SettingSwitch.boundTo(check);
            Node row = SettingsWindow.settingRow(check.getText(), "Overview of the whole file", sw);
            Stage stage = new Stage();
            stage.setScene(new Scene(new VBox(new TextField(), row), 500, 200));
            stage.show();
            try {
                sw.requestFocus();
                assertTrue(sw.isFocusTraversable());
                javafx.event.Event.fireEvent(sw, press(KeyCode.SPACE));
                assertTrue(check.isSelected(), "Space turns the setting on");
                assertEquals(Boolean.TRUE, sw.queryAccessibleAttribute(AccessibleAttribute.SELECTED));
                javafx.event.Event.fireEvent(sw, press(KeyCode.ENTER));
                assertFalse(check.isSelected(), "Enter turns it off again");
                assertEquals(Boolean.FALSE, sw.queryAccessibleAttribute(AccessibleAttribute.SELECTED));

                assertEquals(AccessibleRole.TOGGLE_BUTTON, sw.getAccessibleRole());
                assertEquals("Show minimap", sw.getAccessibleText());
                assertEquals("Overview of the whole file", sw.getAccessibleHelp());
                Label title = all(row, Label.class, new ArrayList<>()).get(0);
                assertSame(sw, title.getLabelFor(), "the row title is the switch's label");

                check.setDisable(true);
                javafx.event.Event.fireEvent(sw, press(KeyCode.SPACE));
                assertFalse(check.isSelected(), "a disabled switch ignores the keyboard");
            } finally {
                stage.hide();
            }
        });
    }

    @Test
    void theRealWindowOpensOnScreenSearchesShownTextAndResetsTheKeymap() throws Exception {
        try (var fx = FxWindowFixture.create()) {
            FxTestSupport.runOnFx(() -> {
                SettingsWindow window = FxTestSupport.field(fx.controller, "settingsWindow");
                Stage owner = new Stage();
                // A maximized owner shorter than the Settings window's design height.
                Rectangle2D screen = javafx.stage.Screen.getPrimary().getVisualBounds();
                owner.setX(screen.getMinX());
                owner.setY(screen.getMinY());
                owner.setWidth(screen.getWidth());
                owner.setHeight(Math.min(screen.getHeight(), 824));
                window.show(owner);
                Stage stage = FxTestSupport.field(window, "stage");
                try {
                    assertTrue(stage.getY() >= screen.getMinY(), "title bar on screen, y=" + stage.getY());
                    assertTrue(stage.getX() >= screen.getMinX());

                    // A1: every on/off row is the keyboard-operable switch, named after its row title.
                    Region content = (Region) stage.getScene().getRoot();
                    java.util.Map<?, Region> pages = FxTestSupport.field(window, "pages");
                    int switches = 0;
                    for (Region page : pages.values()) {
                        for (ToggleSwitch sw : all(page, ToggleSwitch.class, new ArrayList<>())) {
                            assertInstanceOf(SettingSwitch.class, sw, "a bare ToggleSwitch is mouse-only");
                            assertEquals(AccessibleRole.TOGGLE_BUTTON, sw.getAccessibleRole());
                            assertTrue(
                                    sw.getAccessibleText() != null
                                            && !sw.getAccessibleText().isBlank(),
                                    "switch without an accessible name");
                            switches++;
                        }
                    }
                    assertTrue(switches > 50, "expected the on/off rows, found " + switches);
                    CheckBox minimap = FxTestSupport.field(window, "minimapCheck");
                    SettingSwitch minimapSwitch = null;
                    for (Region page : pages.values()) {
                        for (SettingSwitch sw : all(page, SettingSwitch.class, new ArrayList<>())) {
                            if (minimap.getText().equals(sw.getAccessibleText())) {
                                minimapSwitch = sw;
                            }
                        }
                    }
                    boolean before = fx.shared.getSettings().isShowMinimap();
                    minimapSwitch.requestFocus();
                    javafx.event.Event.fireEvent(minimapSwitch, press(KeyCode.SPACE));
                    assertNotEquals(before, fx.shared.getSettings().isShowMinimap(), "Space flips the bound setting");
                    javafx.event.Event.fireEvent(minimapSwitch, press(KeyCode.SPACE));
                    assertEquals(before, fx.shared.getSettings().isShowMinimap());

                    // A9: words in any order, the shown (localized) title, and a worded empty state.
                    TextField search = FxTestSupport.field(window, "searchField");
                    ScrollPane scroll = FxTestSupport.field(window, "contentScroll");
                    Label empty = FxTestSupport.field(window, "searchEmpty");
                    Node pageBefore = scroll.getContent();
                    search.setText("minimap show");
                    assertTrue(minimapSwitch.getParent().isVisible(), "words match in any order");
                    assertNotEquals(empty, scroll.getContent());
                    search.setText("zzqq-no-such-setting");
                    assertSame(empty, scroll.getContent(), "no hits is said in words");
                    assertTrue(empty.getText().contains("zzqq-no-such-setting"));
                    search.setText("");
                    assertNotEquals(empty, scroll.getContent());
                    assertTrue(scroll.getContent() == pageBefore || pages.containsValue(scroll.getContent()));
                    assertTrue(content.isVisible());

                    // U6: "not in a Maven project" is neutral, not the danger pill.
                    assertEquals("settings-git-neutral", SettingsWindow.buildToolStatusClass(false));
                    assertEquals("settings-git-found", SettingsWindow.buildToolStatusClass(true));

                    // A4: Reset to Defaults must reload the live keymap, not just rewrite the file.
                    AtomicInteger reloads = new AtomicInteger();
                    window.setOnKeymapChanged(reloads::incrementAndGet);
                    fx.shared.getSettings().setKeymap("emacs");
                    com.editora.config.Settings.resetToDefaults(fx.shared.getSettings());
                    FxTestSupport.invoke(window, "commitReset");
                    assertEquals(1, reloads.get(), "reset reloads the keymap");
                } finally {
                    stage.hide();
                }
            });
        }
    }

    /** U6: a wide control goes under the description; a capped, wrapping status pill is never truncated. */
    @Test
    void rowsStackWideControlsAndMeasureWrappingOnesAtTheirCappedWidth() throws Exception {
        FxTestSupport.runOnFx(() -> {
            javafx.scene.control.ComboBox<String> jdk = new javafx.scene.control.ComboBox<>();
            jdk.setPrefWidth(460);
            Node wide = SettingsWindow.settingRow(
                    "Default JDK",
                    "JDK for standalone Java files and Maven projects. Run configurations can override it.",
                    jdk);
            Label status = new Label("x");
            status.getStyleClass().add("settings-git-status");
            status.setWrapText(true);
            status.setMaxWidth(440);
            Node pill = SettingsWindow.settingRow("Erkannt", null, status);
            Node narrow = SettingsWindow.settingRow("Show minimap", "Overview of the whole file", new SettingSwitch());
            VBox box = new VBox(wide, pill, narrow);
            Scene scene = new Scene(box, 574, 500);
            scene.getStylesheets()
                    .add(SettingsWindowFxTest.class
                            .getResource("/com/editora/styles/app.css")
                            .toExternalForm());
            status.setText("Für die aktive Datei/das aktive Projekt wurde kein Maven-Projekt erkannt");
            box.applyCss();
            box.layout();

            assertTrue(((SettingRowPane) wide).isStacked(), "a 460px combo in a 574px card goes under the text");
            Label desc = all(wide, Label.class, new ArrayList<>()).get(1);
            assertTrue(desc.getWidth() > 400, "the description keeps the row's width, was " + desc.getWidth());
            assertTrue(jdk.getLayoutY() + jdk.getBoundsInParent().getMinY() >= 0);
            assertTrue(
                    jdk.localToScene(0, 0).getY() > desc.localToScene(0, 0).getY(), "the control sits below the text");
            assertFalse(((SettingRowPane) narrow).isStacked(), "a switch stays beside its description");

            assertFalse(((SettingRowPane) pill).isStacked());
            assertEquals(440, status.getWidth(), 0.5, "the pill is laid out at its capped width");
            assertTrue(
                    status.getHeight() + 0.5 >= status.prefHeight(status.getWidth()),
                    "and is as tall as its wrapped text needs: " + status.getHeight() + " < "
                            + status.prefHeight(status.getWidth()));
        });
    }

    @Test
    void searchTextCoversTitleDescriptionAndKeywords() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Node row = SettingsWindow.settingRow("Schriftgröße", "Größe der Editorschrift in Punkt", new TextField());
            String text = SettingsWindow.searchText("font size", row, new Label("Darstellung"), null);
            assertTrue(SettingsWindow.matches("schrift", text), "localized title is searchable");
            assertTrue(SettingsWindow.matches("punkt größe", text), "description words, any order");
            assertTrue(SettingsWindow.matches("size", text), "English keywords still work");
            assertTrue(SettingsWindow.matches("darstellung", text), "the card heading finds its rows");
            assertFalse(SettingsWindow.matches("schrift farbe", text));
        });
    }
}
