package com.editora.ui;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.function.Consumer;

import javafx.scene.Cursor;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeView;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.stage.Stage;

import com.editora.dap.DapModels;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Review 2, C105 follow-up: the value popup in a dark window over a light editor theme. The popup was owned by the
 * editor area, which made the area its CSS parent: the editor theme's {@code .editor-area .text { -fx-fill: #1b1e2a }}
 * then coloured the String header (and Copy Value) dark on the dark popup.
 */
@Tag("fx")
class DebugValuePopupThemeFxTest {

    /** The dark window theme's colours the popup uses (what the AtlantaFX Editora Dark theme defines). */
    private static final String DARK_WINDOW =
            ".root { -color-fg-default: #e8eaf3; -color-bg-overlay: #232736;" + " -color-border-default: #3a3f52; }";

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void theStringHeaderIsReadableInADarkWindowOverALightEditorTheme() throws Exception {
        DebugValuePopup.Source strings = new DebugValuePopup.Source() {
            @Override
            public void evaluate(String expression, Consumer<DapModels.EvalResult> cb) {
                cb.accept(new DapModels.EvalResult("\"line one\nline two\"", 7, "String"));
            }

            @Override
            public void children(
                    int ref, String filter, int start, int count, Consumer<List<DapModels.VariableInfo>> cb) {
                cb.accept(List.of());
            }

            @Override
            public void addWatch(String expression) {}
        };
        Object[] made = FxTestSupport.callOnFx(() -> {
            CodeArea area = new CodeArea("msg\n");
            area.getStyleClass().add("editor-area");
            Scene scene = new Scene(new StackPane(area), 600, 300);
            scene.getStylesheets()
                    .addAll(
                            getClass()
                                    .getResource("/com/editora/styles/app.css")
                                    .toExternalForm(),
                            getClass()
                                    .getResource("/com/editora/styles/editor-themes/editora-light.css")
                                    .toExternalForm(),
                            "data:text/css;base64,"
                                    + Base64.getEncoder().encodeToString(DARK_WINDOW.getBytes(StandardCharsets.UTF_8)));
            Stage stage = new Stage();
            stage.setScene(scene);
            stage.show();
            return new Object[] {area, stage, new DebugValuePopup(strings)};
        });
        CodeArea area = (CodeArea) made[0];
        Stage stage = (Stage) made[1];
        DebugValuePopup popup = (DebugValuePopup) made[2];
        try {
            FxTestSupport.runOnFx(() -> {
                popup.show(area, "msg", stage.getX() + 20, stage.getY() + 40, true);
                assertTrue(popup.isShowing());
                Label header = FxTestSupport.field(popup, "textHeader");
                header.getScene().getRoot().applyCss();
                // The window's ink, not the editor theme's: StyleContrastTest holds that ink on the overlay ground
                // reads (popupInkReadsOnTheOverlayGround), so no ratio is computed here (ICON-14, Pauta 6.2.0).
                assertEquals(Color.web("#e8eaf3"), header.getTextFill(), "the dark window's -color-fg-default");
                for (javafx.css.Styleable s = header; s != null; s = s.getStyleableParent()) {
                    assertFalse(
                            s.getStyleClass().contains("editor-area"),
                            "the editor's own rules must not reach the popup");
                }
            });
        } finally {
            FxTestSupport.runOnFx(() -> {
                popup.hide();
                stage.close();
            });
        }
    }

    /** The popup's grayed rows say why (KBD-04): nothing selected in its tree, a value tied to no line. */
    @Test
    void itsGrayedRowsSayWhy() throws Exception {
        DebugValuePopup.Source strings = new DebugValuePopup.Source() {
            @Override
            public void evaluate(String expression, Consumer<DapModels.EvalResult> cb) {
                cb.accept(new DapModels.EvalResult("\"line one\"", 0, "String"));
            }

            @Override
            public void children(
                    int ref, String filter, int start, int count, Consumer<List<DapModels.VariableInfo>> cb) {
                cb.accept(List.of());
            }

            @Override
            public void addWatch(String expression) {}
        };
        Object[] made = FxTestSupport.callOnFx(() -> {
            CodeArea area = new CodeArea("msg\n");
            Stage stage = new Stage();
            stage.setScene(new Scene(new StackPane(area), 600, 300));
            stage.show();
            return new Object[] {area, stage, new DebugValuePopup(strings)};
        });
        CodeArea area = (CodeArea) made[0];
        Stage stage = (Stage) made[1];
        DebugValuePopup popup = (DebugValuePopup) made[2];
        try {
            FxTestSupport.runOnFx(() -> {
                popup.show(area, "msg", stage.getX() + 20, stage.getY() + 40, true);
                javafx.scene.control.ContextMenu tree = FxTestSupport.field(popup, "menu");
                tree.getOnShowing().handle(new javafx.stage.WindowEvent(tree, javafx.stage.WindowEvent.WINDOW_SHOWING));
                assertEquals(
                        tr("row.unavailable.noSelection"),
                        com.editora.editor.RowReasons.reasonOf(tree.getItems().get(1)),
                        "Add to Watches with no row selected");
                assertEquals(
                        tr("row.unavailable.noSelection"),
                        com.editora.editor.RowReasons.reasonOf(tree.getItems().get(2)));
                Label line = FxTestSupport.field(popup, "line");
                javafx.scene.control.ContextMenu lineMenu = line.getContextMenu();
                lineMenu.getOnShowing()
                        .handle(new javafx.stage.WindowEvent(lineMenu, javafx.stage.WindowEvent.WINDOW_SHOWING));
                String pin = com.editora.editor.RowReasons.reasonOf(
                        lineMenu.getItems().get(1));
                assertEquals(lineMenu.getItems().get(1).isDisable(), pin != null, "grayed exactly when it says why");
            });
        } finally {
            FxTestSupport.runOnFx(() -> {
                popup.hide();
                stage.close();
            });
        }
    }

    /** The tree's rows click (expand, select): a hand over them, not the editor's I-beam showing through. */
    @Test
    void theTreeRowsShowAHandCursor() throws Exception {
        DebugValuePopup.Source objects = new DebugValuePopup.Source() {
            @Override
            public void evaluate(String expression, Consumer<DapModels.EvalResult> cb) {
                cb.accept(new DapModels.EvalResult("Item@44", 9, "Item"));
            }

            @Override
            public void children(
                    int ref, String filter, int start, int count, Consumer<List<DapModels.VariableInfo>> cb) {
                cb.accept(List.of(
                        new DapModels.VariableInfo("name", "\"Eraser\"", "String", 0),
                        new DapModels.VariableInfo("quantity", "25", "int", 0)));
            }

            @Override
            public void addWatch(String expression) {}
        };
        Object[] made = FxTestSupport.callOnFx(() -> {
            CodeArea area = new CodeArea("item\n");
            Scene scene = new Scene(new StackPane(area), 600, 300);
            scene.getStylesheets()
                    .add(getClass().getResource("/com/editora/styles/app.css").toExternalForm());
            Stage stage = new Stage();
            stage.setScene(scene);
            stage.show();
            return new Object[] {area, stage, new DebugValuePopup(objects)};
        });
        CodeArea area = (CodeArea) made[0];
        Stage stage = (Stage) made[1];
        DebugValuePopup popup = (DebugValuePopup) made[2];
        try {
            FxTestSupport.runOnFx(() -> {
                popup.show(area, "item", stage.getX() + 20, stage.getY() + 40, true);
                assertTrue(popup.isShowing());
                TreeView<DebugPanel.VarRow> tree = popup.tree();
                tree.getScene().getRoot().applyCss();
                tree.layout();
                List<TreeCell<?>> filled = tree.lookupAll(".tree-cell").stream()
                        .<TreeCell<?>>map(n -> (TreeCell<?>) n)
                        .filter(c -> !c.isEmpty())
                        .toList();
                assertFalse(filled.isEmpty(), "the popup shows the object's rows");
                for (TreeCell<?> cell : filled) {
                    assertEquals(Cursor.HAND, cell.getCursor(), "row " + cell.getIndex());
                }
                StackPane popupRoot = FxTestSupport.field(popup, "root");
                assertEquals(Cursor.DEFAULT, popupRoot.getCursor());
            });
        } finally {
            FxTestSupport.runOnFx(() -> {
                popup.hide();
                stage.close();
            });
        }
    }
}
