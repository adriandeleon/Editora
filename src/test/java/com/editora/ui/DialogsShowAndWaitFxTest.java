package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Dialogs#showAndWait}, the one place a dialog with buttons is shown, and its hook
 * ({@link Dialogs#answerWith}, {@link DialogAnswers}): with the hook set no window opens, the hook is given what the
 * dialog asks as plain data, and the caller gets the dialog's own button back (CONF-12, Pauta 6.0.1).
 */
@Tag("fx")
class DialogsShowAndWaitFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @AfterEach
    void showDialogsAgain() {
        Dialogs.answerWith(null);
        Confirm.answerWith(null);
    }

    /** The unsaved-changes shape: Save (the default), a red Discard, and the catalog's Cancel. */
    private record Unsaved(Alert alert, ButtonType save, ButtonType discard, ButtonType cancel) {
        static Unsaved build() {
            ButtonType save = new ButtonType("Save");
            ButtonType discard = new ButtonType("Discard");
            ButtonType cancel = Dialogs.cancel();
            Alert alert = Dialogs.styled(new Alert(Alert.AlertType.CONFIRMATION));
            alert.setTitle("Unsaved Changes");
            alert.setHeaderText("Save notes.md?");
            alert.setContentText("It has edits.");
            alert.getButtonTypes().setAll(save, discard, cancel);
            Dialogs.destructive(alert, discard);
            Dialogs.defaultTo(alert, save);
            return new Unsaved(alert, save, discard, cancel);
        }
    }

    @Test
    void withTheHookSetNothingIsShownAndTheDialogsOwnButtonComesBack() throws Exception {
        FxTestSupport.runOnFx(() -> {
            int windows = Window.getWindows().size();
            Unsaved q = Unsaved.build();
            boolean[] showing = {true};
            int[] windowsWhileAsked = {-1};
            Dialogs.answerWith(asked -> {
                showing[0] = q.alert().isShowing();
                windowsWhileAsked[0] = Window.getWindows().size();
                return asked.answer("Discard");
            });
            Optional<ButtonType> answer = Dialogs.showAndWait(q.alert());
            assertSame(q.discard(), answer.orElseThrow(), "the dialog's own ButtonType, so == still works");
            assertFalse(showing[0], "the dialog is not showing while the hook answers");
            assertEquals(windows, windowsWhileAsked[0], "no window was opened");
            assertFalse(q.alert().isShowing());

            Dialogs.answerWith(asked -> asked.answer(ButtonBar.ButtonData.CANCEL_CLOSE));
            assertSame(q.cancel(), Dialogs.showAndWait(q.alert()).orElseThrow());
        });
    }

    @Test
    void theHookIsGivenWhatTheDialogAsksAsPlainData() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Unsaved q = Unsaved.build();
            try (DialogAnswers answers = DialogAnswers.pressing("Save")) {
                assertSame(q.save(), Dialogs.showAndWait(q.alert()).orElseThrow());
                Dialogs.Asked asked = answers.only();
                assertEquals("Unsaved Changes", asked.title());
                assertEquals("Save notes.md?", asked.header());
                assertEquals("It has edits.", asked.content());
                assertSame(q.alert(), asked.dialog());
                assertEquals(
                        List.of(
                                new Dialogs.Answer("Save", ButtonBar.ButtonData.OTHER, true, false),
                                new Dialogs.Answer("Discard", ButtonBar.ButtonData.OTHER, false, true),
                                new Dialogs.Answer(
                                        tr("dialog.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE, false, false)),
                        asked.answers(),
                        "label, role, the default button and the red one, in the dialog's order");
                assertNull(asked.answer(ButtonBar.ButtonData.YES), "it has no such answer");
                assertNull(asked.answer("Reload"));
            }
        });
    }

    @Test
    void aStockButtonIsReportedInTheCatalogsWordAndAnErrorHasOneAnswer() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Alert error = Dialogs.styled(new Alert(Alert.AlertType.ERROR, "It failed.", ButtonType.OK));
            error.setHeaderText(null);
            try (DialogAnswers answers = DialogAnswers.pressing(ButtonBar.ButtonData.OK_DONE)) {
                assertSame(ButtonType.OK, Dialogs.showAndWait(error).orElseThrow());
                Dialogs.Asked asked = answers.only();
                assertNull(asked.header());
                assertEquals("It failed.", asked.content());
                assertEquals(
                        List.of(new Dialogs.Answer(tr("dialog.ok"), ButtonBar.ButtonData.OK_DONE, true, false)),
                        asked.answers());
            }
        });
    }

    @Test
    void noAnswerClosesTheDialogAsEscapeDoes() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Unsaved q = Unsaved.build();
            try (DialogAnswers answers = DialogAnswers.closing()) {
                assertSame(
                        q.cancel(),
                        Dialogs.showAndWait(q.alert()).orElseThrow(),
                        "a closed dialog answers with its Cancel, as JavaFX has it");
                assertEquals(1, answers.asked().size());

                // A dialog with no Cancel has no answer when it is closed: the caller's orElse decides.
                ButtonType one = new ButtonType("Rebase", ButtonBar.ButtonData.OTHER);
                ButtonType two = new ButtonType("Merge", ButtonBar.ButtonData.OTHER);
                Alert noCancel = Dialogs.styled(new Alert(Alert.AlertType.CONFIRMATION, "?", one, two));
                assertEquals(Optional.empty(), Dialogs.showAndWait(noCancel));

                // A No button stands in for Cancel, as in JavaFX, when there is no Cancel.
                Alert yesNo =
                        Dialogs.styled(new Alert(Alert.AlertType.CONFIRMATION, "?", ButtonType.YES, ButtonType.NO));
                assertSame(ButtonType.NO, Dialogs.showAndWait(yesNo).orElseThrow());
            }
        });
    }

    @Test
    void theHooksNoAnswerIsWhatClosingTheRealDialogGives() throws Exception {
        Unsaved shown = FxTestSupport.callOnFx(Unsaved::build);
        List<Optional<ButtonType>> real = new ArrayList<>();
        assertNotNull(
                FxDialogs.duringHeader(() -> real.add(Dialogs.showAndWait(shown.alert())), "Save notes.md?", null));
        assertSame(shown.cancel(), real.get(0).orElseThrow(), "with no hook the dialog is shown and answered");

        FxTestSupport.runOnFx(() -> {
            Unsaved hooked = Unsaved.build();
            try (DialogAnswers answers = DialogAnswers.closing()) {
                assertSame(hooked.cancel(), Dialogs.showAndWait(hooked.alert()).orElseThrow());
            }
        });
    }

    @Test
    void theHookGetsTheDialogSoATestCanTickACheckBoxTheCallerReads() throws Exception {
        FxTestSupport.runOnFx(() -> {
            ButtonType delete = Dialogs.action("Delete");
            Alert alert =
                    Dialogs.styled(new Alert(Alert.AlertType.CONFIRMATION, "Delete it?", delete, Dialogs.cancel()));
            CheckBox forget = new CheckBox("Also forget the host");
            TextArea detail = new TextArea("line one\nline two");
            alert.getDialogPane().setContent(new VBox(new Label("Delete “box”?"), forget, detail));
            Dialogs.destructive(alert, delete);
            try (DialogAnswers answers = DialogAnswers.with(asked -> {
                all(asked.dialog(), CheckBox.class).get(0).setSelected(true);
                return asked.answer(ButtonBar.ButtonData.OK_DONE);
            })) {
                assertFalse(forget.isSelected());
                assertSame(delete, Dialogs.showAndWait(alert).orElseThrow());
                assertTrue(forget.isSelected(), "the caller reads what the hook set");
                assertEquals(
                        "Delete “box”?\nAlso forget the host\nline one\nline two",
                        answers.only().content(),
                        "a layout of its own is read as the text of its labels, check boxes and text boxes");
                assertTrue(answers.only().answer("Delete").destructive());
                assertTrue(answers.only().answer(tr("dialog.cancel")).isDefault());
            }
        });
    }

    @Test
    void aDialogWithAResultOfItsOwnAnswersThroughItsConverter() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Dialog<String> typed = Dialogs.styled(new Dialog<>());
            ButtonType use = new ButtonType("Use", ButtonBar.ButtonData.OK_DONE);
            typed.getDialogPane().getButtonTypes().setAll(use, Dialogs.cancel());
            typed.setResultConverter(button -> button == use ? "chosen" : null);
            try (DialogAnswers answers = DialogAnswers.pressing("Use")) {
                assertEquals(Optional.of("chosen"), Dialogs.showAndWait(typed));
                answers.nowClosing();
                assertEquals(Optional.empty(), Dialogs.showAndWait(typed));
            }
        });
    }

    @Test
    void anAnswerTheDialogDoesNotHaveIsRefused() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Unsaved q = Unsaved.build();
            Dialogs.answerWith(asked -> new Dialogs.Answer("Reload", ButtonBar.ButtonData.OTHER, false, false));
            assertThrows(IllegalArgumentException.class, () -> Dialogs.showAndWait(q.alert()));

            try (DialogAnswers answers = DialogAnswers.pressing(ButtonBar.ButtonData.YES)) {
                assertThrows(
                        AssertionError.class,
                        () -> Dialogs.showAndWait(q.alert()),
                        "the helper fails a test that meets a dialog without the answer it names");
                answers.nowPressing("Reload");
                assertThrows(AssertionError.class, () -> Dialogs.showAndWait(q.alert()));
            }
        });
    }

    @Test
    void closingTheHelperShowsDialogsAgainAndItKeepsWhatWasAsked() throws Exception {
        Unsaved q = FxTestSupport.callOnFx(Unsaved::build);
        try (DialogAnswers answers = DialogAnswers.pressing("Discard")) {
            FxTestSupport.runOnFx(() -> {
                assertSame(q.discard(), Dialogs.showAndWait(q.alert()).orElseThrow());
                answers.nowPressing(ButtonBar.ButtonData.CANCEL_CLOSE);
                assertSame(q.cancel(), Dialogs.showAndWait(q.alert()).orElseThrow());
            });
            assertEquals(2, answers.asked().size());
            assertThrows(AssertionError.class, answers::only, "two were asked");
            answers.clear();
            assertEquals(List.of(), answers.asked());
        }
        // Reset: the dialog is shown for real, and a button pressed in it is its answer.
        List<Optional<ButtonType>> real = new ArrayList<>();
        assertNotNull(
                FxDialogs.duringHeader(
                        () -> real.add(Dialogs.showAndWait(q.alert())),
                        "Save notes.md?",
                        ButtonBar.ButtonData.CANCEL_CLOSE),
                "with the hook reset the dialog opens");
        assertSame(q.cancel(), real.get(0).orElseThrow());
    }

    @Test
    void aConfirmQuestionIsAnsweredByEitherHookAndConfirmsOwnComesFirst() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Confirm.Question question = new Confirm.Question("Delete file", null, "Delete notes.md?", "Delete");
            try (DialogAnswers answers = DialogAnswers.pressing(ButtonBar.ButtonData.OK_DONE)) {
                assertTrue(Confirm.ask(null, question), "the action confirms");
                Dialogs.Asked asked = answers.only();
                assertEquals("Delete file", asked.title());
                assertEquals("Delete notes.md?", asked.content());
                assertEquals(
                        List.of(
                                new Dialogs.Answer("Delete", ButtonBar.ButtonData.OK_DONE, false, true),
                                new Dialogs.Answer(
                                        tr("dialog.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE, true, false)),
                        asked.answers(),
                        "a red action that is not the default, and Cancel as the default");

                answers.nowPressing(ButtonBar.ButtonData.CANCEL_CLOSE);
                assertFalse(Confirm.ask(null, question));
                answers.nowClosing();
                assertFalse(Confirm.ask(null, question, 460), "closing a wide question is a Cancel too");
                assertEquals(3, answers.asked().size());

                try (ConfirmAnswers confirm = ConfirmAnswers.confirming()) {
                    assertTrue(Confirm.ask(null, question));
                    assertTrue(Confirm.ask(null, question, 460));
                    assertEquals(List.of(question, question), confirm.asked());
                }
                assertEquals(3, answers.asked().size(), "Confirm's own hook answered: no dialog was built");
            }
        });
    }

    // --- the twin that does not wait ------------------------------------------------------------------------------

    @Test
    void aDialogShownWithoutWaitingIsAskedAtOnceAndAnsweredOnALaterPulse() throws Exception {
        List<String> order = new ArrayList<>();
        List<ButtonType> results = new ArrayList<>();
        Unsaved q = FxTestSupport.callOnFx(Unsaved::build);
        try (DialogAnswers answers = DialogAnswers.with(asked -> {
            order.add("asked");
            return asked.answer("Discard");
        })) {
            FxTestSupport.runOnFx(() -> {
                int windows = Window.getWindows().size();
                Dialogs.show(q.alert(), result -> {
                    order.add("answered");
                    results.add(result);
                });
                order.add("returned");
                assertEquals(List.of("asked", "returned"), order, "the hook is asked at once; the answer waits");
                assertFalse(q.alert().isShowing());
                assertEquals(windows, Window.getWindows().size(), "no window was opened");
                assertEquals("Save notes.md?", answers.only().header());
            });
            FxTestSupport.drainFx();
            assertEquals(
                    List.of("asked", "returned", "answered"),
                    order,
                    "the caller went on before the answer came, as it does when a dialog is shown");
            assertSame(q.discard(), results.get(0), "the dialog's own ButtonType");

            // Closed with no answer: the dialog's Cancel, as JavaFX has it; with no Cancel, nothing.
            answers.nowClosing();
            FxTestSupport.runOnFx(() -> Dialogs.show(q.alert(), results::add));
            FxTestSupport.drainFx();
            assertSame(q.cancel(), results.get(1));
            FxTestSupport.runOnFx(() -> {
                ButtonType one = new ButtonType("Rebase", ButtonBar.ButtonData.OTHER);
                ButtonType two = new ButtonType("Merge", ButtonBar.ButtonData.OTHER);
                Dialogs.show(Dialogs.styled(new Alert(Alert.AlertType.CONFIRMATION, "?", one, two)), results::add);
            });
            FxTestSupport.drainFx();
            assertNull(results.get(2));

            // An answer nobody reads, and a prompt whose result is its text.
            answers.clear();
            List<String> typed = new ArrayList<>();
            FxTestSupport.runOnFx(() -> {
                Dialogs.show(Dialogs.styled(new Alert(Alert.AlertType.ERROR, "It failed.", ButtonType.OK)));
                javafx.scene.control.TextInputDialog prompt =
                        Dialogs.styled(new javafx.scene.control.TextInputDialog("main"));
                answers.nowPressing(ButtonBar.ButtonData.OK_DONE);
                Dialogs.show(prompt, typed::add);
            });
            FxTestSupport.drainFx();
            assertEquals(2, answers.asked().size());
            assertEquals("It failed.", answers.asked().get(0).content());
            assertEquals(List.of("main"), typed);
        }
    }

    @Test
    void withNoHookADialogShownWithoutWaitingOpensAndGivesItsResultWhenItCloses() throws Exception {
        Unsaved q = FxTestSupport.callOnFx(Unsaved::build);
        List<ButtonType> results = new ArrayList<>();
        FxTestSupport.runOnFx(() -> {
            Dialogs.show(q.alert(), results::add);
            assertTrue(q.alert().isShowing(), "shown, and the caller goes on");
            assertEquals(List.of(), results);
        });
        FxTestSupport.runOnFx(
                () -> ((javafx.scene.control.Button) q.alert().getDialogPane().lookupButton(q.discard())).fire());
        FxTestSupport.drainFx();
        assertFalse(FxTestSupport.callOnFx(() -> q.alert().isShowing()));
        assertEquals(1, results.size());
        assertSame(q.discard(), results.get(0));
    }

    private static <T> List<T> all(Dialog<?> dialog, Class<T> type) {
        List<T> found = new ArrayList<>();
        collect(dialog.getDialogPane().getContent(), type, found);
        return found;
    }

    private static <T> void collect(javafx.scene.Node node, Class<T> type, List<T> into) {
        if (type.isInstance(node)) {
            into.add(type.cast(node));
        }
        if (node instanceof javafx.scene.Parent parent) {
            parent.getChildrenUnmodifiable().forEach(child -> collect(child, type, into));
        }
    }
}
