package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.TextInputDialog;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What {@link Dialogs#styled} and {@link Confirm} give every dialog: one button order on every OS, buttons
 * worded from the catalog, and for a question before a destructive action a red action that is never the
 * default, with Cancel as the default.
 */
@Tag("fx")
class DialogsFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @AfterEach
    void showDialogsAgain() {
        Confirm.answerWith(null);
    }

    private static Button button(Alert alert, ButtonType type) {
        return (Button) alert.getDialogPane().lookupButton(type);
    }

    @Test
    void aStyledDialogHasTheOneButtonOrderTheStylesheetAndCatalogWordedStockButtons() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Alert alert = Dialogs.styled(new Alert(Alert.AlertType.CONFIRMATION));
            DialogPane pane = alert.getDialogPane();
            assertEquals(Dialogs.BUTTON_ORDER, ((ButtonBar) pane.lookup(".button-bar")).getButtonOrder());
            assertEquals(tr("dialog.ok"), button(alert, ButtonType.OK).getText());
            assertEquals(tr("dialog.cancel"), button(alert, ButtonType.CANCEL).getText());
            assertEquals(
                    1,
                    pane.getStylesheets().stream()
                            .filter(s -> s.endsWith("app.css"))
                            .count());

            // Buttons set after the dialog was styled are worded too, and a second call adds nothing twice.
            Dialogs.styled(alert);
            alert.getButtonTypes().setAll(ButtonType.YES, ButtonType.NO, ButtonType.APPLY, ButtonType.CLOSE);
            assertEquals(tr("dialog.yes"), button(alert, ButtonType.YES).getText());
            assertEquals(tr("dialog.no"), button(alert, ButtonType.NO).getText());
            assertEquals(tr("dialog.apply"), button(alert, ButtonType.APPLY).getText());
            assertEquals(tr("dialog.close"), button(alert, ButtonType.CLOSE).getText());
            assertEquals(
                    1,
                    pane.getStylesheets().stream()
                            .filter(s -> s.endsWith("app.css"))
                            .count());

            // A button the app named itself keeps its name.
            ButtonType named = Dialogs.action("Replace 3 files");
            alert.getButtonTypes().setAll(named, Dialogs.cancel());
            assertEquals("Replace 3 files", button(alert, named).getText());

            TextInputDialog prompt = Dialogs.styled(new TextInputDialog("x"));
            assertEquals(
                    tr("dialog.cancel"), ((Button) prompt.getDialogPane().lookupButton(ButtonType.CANCEL)).getText());
        });
    }

    @Test
    void cancelIsDrawnLeftOfEveryActionWhateverOrderTheButtonsWereAddedIn() throws Exception {
        FxTestSupport.runOnFx(() -> {
            // The unsaved-changes question's three answers, added Save, Discard, Cancel.
            ButtonType save = new ButtonType("Save");
            ButtonType discard = new ButtonType("Discard");
            ButtonType cancel = Dialogs.cancel();
            Alert alert = Dialogs.styled(new Alert(Alert.AlertType.CONFIRMATION, "?", save, discard, cancel));
            alert.show();
            try {
                alert.getDialogPane().applyCss();
                alert.getDialogPane().layout();
                double c = button(alert, cancel).localToScene(0, 0).getX();
                double s = button(alert, save).localToScene(0, 0).getX();
                double d = button(alert, discard).localToScene(0, 0).getX();
                assertTrue(c < s && s < d, "Cancel, Save, Discard; drawn at " + c + ", " + s + ", " + d);
            } finally {
                alert.close();
            }
        });
    }

    @Test
    void aConfirmQuestionHasARedActionThatIsNeverTheDefaultAndCancelAsTheDefault() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Confirm.Question question = new Confirm.Question("Delete file", null, "Delete notes.md?", "Delete");
            Alert alert = Confirm.dialog(null, question);
            ButtonType action = alert.getButtonTypes().get(0);
            ButtonType cancel = alert.getButtonTypes().get(1);
            assertEquals("Delete", button(alert, action).getText());
            assertTrue(button(alert, action).getStyleClass().contains("danger"));
            assertFalse(button(alert, action).isDefaultButton(), "Enter must not run the red action");
            assertEquals(tr("dialog.cancel"), button(alert, cancel).getText());
            assertTrue(button(alert, cancel).isDefaultButton());
            assertEquals(ButtonBar.ButtonData.CANCEL_CLOSE, cancel.getButtonData());
            assertEquals("Delete file", alert.getTitle());
            assertNull(alert.getHeaderText());
            assertEquals("Delete notes.md?", alert.getContentText());

            // Only the action button confirms: Cancel, and a close by any other route, do not.
            assertTrue(Confirm.confirmed(alert, Optional.of(action)));
            assertFalse(Confirm.confirmed(alert, Optional.of(cancel)));
            assertFalse(Confirm.confirmed(alert, Optional.empty()));
        });
    }

    @Test
    void destructiveMovesTheDefaultToCancelInADialogWithALayoutOfItsOwn() throws Exception {
        FxTestSupport.runOnFx(() -> {
            ButtonType discard = Dialogs.action("Discard All");
            ButtonType keep = new ButtonType("Keep Staged");
            Alert alert = Dialogs.styled(new Alert(Alert.AlertType.WARNING, "?", discard, keep, Dialogs.cancel()));
            assertTrue(button(alert, discard).isDefaultButton(), "JavaFX makes an OK_DONE button the default");
            Dialogs.destructive(alert, discard);
            assertFalse(button(alert, discard).isDefaultButton());
            assertFalse(button(alert, keep).isDefaultButton());
            assertTrue(button(alert, Dialogs.cancelOf(alert)).isDefaultButton());
            assertTrue(button(alert, discard).getStyleClass().contains("danger"));
            Dialogs.defaultTo(alert, keep);
            assertTrue(button(alert, keep).isDefaultButton());
            assertFalse(button(alert, Dialogs.cancelOf(alert)).isDefaultButton());
        });
    }

    @Test
    void aTestAnswersAQuestionWithoutShowingIt() {
        List<Confirm.Question> asked = new ArrayList<>();
        Confirm.answerWith(question -> {
            asked.add(question);
            return question.action().equals("Delete");
        });
        assertTrue(Confirm.ask(null, "Delete file", "Delete notes.md?", "Delete"));
        assertFalse(Confirm.ask(null, new Confirm.Question("Reset", "Reset settings?", "All of them.", "Reset")));
        assertEquals(
                List.of(
                        new Confirm.Question("Delete file", null, "Delete notes.md?", "Delete"),
                        new Confirm.Question("Reset", "Reset settings?", "All of them.", "Reset")),
                asked);
    }
}
