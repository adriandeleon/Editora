package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import com.editora.command.KeymapManager;
import com.editora.config.ConfigManager;
import com.editora.config.SharedConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Icon-only controls are named for assistive technology (not just tooltipped), native dialogs carry the
 * app stylesheet, and closing the focused tool window hands focus back to the editor.
 */
@Tag("fx")
class NamedControlsFxTest {

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

    @Test
    void iconButtonHelperSetsTooltipAndAccessibleNameTogether() throws Exception {
        FxTestSupport.runOnFx(() -> {
            int[] runs = {0};
            Button plain = Icons.button(Icons.refresh(), "Refresh", () -> runs[0]++, "flat", "git-toolbar-button");
            assertEquals("Refresh", plain.getAccessibleText());
            assertEquals("Refresh", plain.getTooltip().getText());
            assertTrue(plain.getStyleClass().containsAll(List.of("flat", "git-toolbar-button")));
            assertNotNull(plain.getGraphic());
            assertTrue(plain.isFocusTraversable());
            plain.fire();
            assertEquals(1, runs[0]);

            Button toolbar = Icons.toolbarButton(Icons.refresh(), "Reload", null);
            assertFalse(toolbar.isFocusTraversable());
            assertEquals("Reload", toolbar.getAccessibleText());
            Icons.name(toolbar, "Stop");
            assertEquals("Stop", toolbar.getAccessibleText());
            assertEquals("Stop", toolbar.getTooltip().getText());
        });
    }

    private static ToolWindowManager manager(Node editor) throws Exception {
        Path dir = Files.createTempDirectory("editora-named");
        SharedConfig shared = new SharedConfig(dir, false);
        shared.load();
        return new ToolWindowManager(new BorderPane(), editor, new ConfigManager(shared), new KeymapManager());
    }

    @Test
    void toolWindowHeaderAndStripeButtonsAreNamed() throws Exception {
        FxTestSupport.runOnFx(() -> {
            try {
                ToolWindowManager m = manager(new Label("editor"));
                ToolWindow tw = new ToolWindow(
                        "probe", "Probe", ToolWindow.Side.RIGHT, () -> new Label("i"), new Label("c"), "tool.probe");
                m.register(tw);
                m.open(tw);
                Map<ToolWindow, Button> stripe = FxTestSupport.field(m, "stripeButtons");
                assertEquals("Probe", stripe.get(tw).getAccessibleText(), "stripe button is named by its window");
                Map<ToolWindow, Region> panels = FxTestSupport.field(m, "panels");
                List<Button> header = all(panels.get(tw), Button.class, new ArrayList<>());
                assertEquals(3, header.size(), "float, maximize, hide");
                for (Button b : header) {
                    assertTrue(
                            b.getAccessibleText() != null
                                    && !b.getAccessibleText().isBlank(),
                            "an icon-only header button has no accessible name");
                    assertEquals(b.getAccessibleText(), b.getTooltip().getText());
                }
                assertTrue(header.stream().anyMatch(b -> tr("toolwindow.hide").equals(b.getAccessibleText())));
                assertTrue(
                        header.stream().anyMatch(b -> tr("toolwindow.maximize").equals(b.getAccessibleText())));
                m.toggleMaximized(tw);
                assertTrue(
                        header.stream()
                                .anyMatch(b -> tr("toolwindow.restoreSize").equals(b.getAccessibleText())),
                        "the name follows the toggle's direction");
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
    }

    @Test
    void closingTheFocusedToolWindowReturnsFocusToTheEditor() throws Exception {
        FxTestSupport.runOnFx(() -> {
            try {
                TextField editorField = new TextField("editor");
                editorField.getStyleClass().add("editor-area");
                TextField other = new TextField("second editor");
                VBox editor = new VBox(editorField, other);
                BorderPane workspace = new BorderPane();
                Path dir = Files.createTempDirectory("editora-focus");
                SharedConfig shared = new SharedConfig(dir, false);
                shared.load();
                ToolWindowManager m =
                        new ToolWindowManager(workspace, editor, new ConfigManager(shared), new KeymapManager());
                TextField inPanel = new TextField("filter");
                ToolWindow tw = new ToolWindow(
                        "probe", "Probe", ToolWindow.Side.RIGHT, () -> new Label("i"), new StackPane(inPanel), "t.p");
                m.register(tw);
                Scene scene = new Scene(workspace, 800, 500);
                javafx.stage.Stage stage = new javafx.stage.Stage();
                stage.setScene(scene);
                stage.show();
                try {
                    other.requestFocus(); // the editor the user was in
                    assertSame(other, scene.getFocusOwner());
                    m.open(tw, false);
                    inPanel.requestFocus();
                    assertSame(inPanel, scene.getFocusOwner(), "focus is inside the tool window");

                    m.close(tw);
                    assertSame(other, scene.getFocusOwner(), "focus returns to the editor that had it");

                    // A tool window closed while focus is elsewhere leaves focus alone.
                    m.open(tw, false);
                    editorField.requestFocus();
                    m.close(tw);
                    assertSame(editorField, scene.getFocusOwner());
                } finally {
                    stage.hide();
                }
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
    }

    @Test
    void nativeDialogsCarryTheAppStylesheet() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Alert alert = Dialogs.styled(new Alert(Alert.AlertType.CONFIRMATION, "Reset?", ButtonType.OK));
            List<String> sheets = alert.getDialogPane().getStylesheets();
            assertEquals(
                    1,
                    sheets.stream().filter(s -> s.endsWith("/styles/app.css")).count());
            assertSame(alert, Dialogs.styled(alert));
            assertEquals(1, sheets.size(), "styling twice adds the sheet once");
        });
    }

    @Test
    void aFailureKeyedStatusMessageUsesTheErrorChannel() throws Exception {
        try (var fx = FxWindowFixture.create()) {
            FxTestSupport.runOnFx(() -> {
                StatusBar bar = FxTestSupport.field(fx.controller, "statusBar");
                Label echo = FxTestSupport.field(bar, "echo");
                fx.controller.setStatus(tr("status.saved", "notes.md"));
                assertFalse(echo.getStyleClass().contains("status-echo-error"));
                // A coordinator reporting a failure through the plain status channel…
                fx.controller.setStatus(tr("status.pdf.exportFailed", "disk full"));
                assertTrue(echo.getStyleClass().contains("status-echo-error"), "…is shown as an error");
                MessageLog log = FxTestSupport.field(bar, "messageLog");
                assertTrue(log.unreadErrors() >= 1, "and stays flagged until the log is opened");
                fx.controller.setStatus(tr("status.narrow.cannot"));
                assertTrue(echo.getStyleClass().contains("status-echo-warn"));
                fx.controller.setStatus(tr("status.saved", "notes.md"));
                assertFalse(echo.getStyleClass().contains("status-echo-error"));
                assertFalse(echo.getStyleClass().contains("status-echo-warn"));
            });
        }
    }
}
