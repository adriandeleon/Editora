package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import javafx.collections.ListChangeListener;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Labeled;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextInputControl;
import javafx.util.Callback;

import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * Shared set-up for native dialogs ({@code Alert}, {@code TextInputDialog}, {@code ChoiceDialog}, a
 * {@code Dialog} the app fills itself). <b>Every dialog the app builds goes through {@link #styled}</b>
 * ({@code DialogsSourceTest} holds that), because a dialog is a scene of its own and takes nothing from the
 * window:
 *
 * <ul>
 *   <li><b>The app stylesheet.</b> Without {@code app.css} a dialog's buttons fall back to the bare theme
 *       (another corner radius, no keyboard-focus ring, none of the state colours). The {@code -color-*}
 *       tokens resolve either way: those come from the application-wide user-agent stylesheet.
 *   <li><b>One button order on every OS</b> ({@link #BUTTON_ORDER}): Cancel left of every action. JavaFX's
 *       own order changes with the operating system, so the same question read differently on each.
 *   <li><b>Buttons worded from the message catalog.</b> JavaFX labels its stock OK, Cancel, Yes, No, Apply and
 *       Close from its own resources, in the JVM's locale, so under menus in the language chosen in Settings a
 *       dialog could still say "Cancel" in another. Each stock button takes the catalog's word, now and
 *       whenever the dialog's buttons change.
 * </ul>
 *
 * A question before something is removed, deleted, discarded or overwritten is asked through {@link Confirm},
 * or, where it needs a layout of its own, marked with {@link #destructive}.
 *
 * <p><b>Every dialog that is answered with a button is shown through {@link #showAndWait}</b>, or through
 * {@link #show(Dialog, java.util.function.Consumer)} when it must not block, never through its own
 * {@code showAndWait()} or {@code show()} ({@code DialogShownThroughHookTest} holds that): a test then answers it
 * with {@link #answerWith} and no window opens (CONF-12, Pauta 6.0.1).
 */
final class Dialogs {

    private static final String APP_CSS = "/com/editora/styles/app.css";

    /**
     * One {@code ButtonBar} order for every OS: help at the left, then Cancel ({@code C}) before every action,
     * so the unsaved-changes question reads Cancel, Save, Discard. JavaFX's default differs per OS.
     */
    static final String BUTTON_ORDER = "L_HE+CUYNBXIAO_R";

    /** JavaFX's stock buttons and the catalog key that words each one. */
    private static final Map<ButtonType, String> STOCK = Map.of(
            ButtonType.OK, "dialog.ok",
            ButtonType.CANCEL, "dialog.cancel",
            ButtonType.YES, "dialog.yes",
            ButtonType.NO, "dialog.no",
            ButtonType.APPLY, "dialog.apply",
            ButtonType.CLOSE, "dialog.close");

    /** Marks a pane this class has set up, so a second {@link #styled} adds no second listener. */
    private static final String STYLED = "editora.dialogs.styled";

    /** Answers dialogs in place of showing them; null (the default) shows them. See {@link #answerWith}. */
    private static volatile @Nullable Function<Asked, @Nullable Answer> answerer;

    private Dialogs() {}

    /**
     * One answer a dialog offers: its button's words as the user reads them, its role, whether it is the default
     * button (the one Enter presses) and whether it is drawn in the red {@code danger} style.
     */
    record Answer(String label, ButtonBar.ButtonData role, boolean isDefault, boolean destructive) {}

    /**
     * What a dialog asks, as plain data: its title, header and text, and its answers in the order the dialog holds
     * them. {@code content} is the dialog's content text or, where the dialog has a layout of its own, the text of
     * the labels, check boxes and text boxes in it, one per line. {@code dialog} is the dialog itself, not shown,
     * for a test that has to set something the caller reads afterwards (tick a check box).
     */
    record Asked(
            @Nullable String title,
            @Nullable String header,
            @Nullable String content,
            List<Answer> answers,
            Dialog<?> dialog) {

        /** The first answer with {@code role}, or null when the dialog has none. */
        @Nullable
        Answer answer(ButtonBar.ButtonData role) {
            for (Answer answer : answers) {
                if (answer.role() == role) {
                    return answer;
                }
            }
            return null;
        }

        /** The answer worded {@code label}, or null when the dialog has none. */
        @Nullable
        Answer answer(String label) {
            for (Answer answer : answers) {
                if (answer.label().equals(label)) {
                    return answer;
                }
            }
            return null;
        }
    }

    /**
     * Shows {@code dialog} and waits for its answer: what {@code dialog.showAndWait()} returns. The one place a
     * dialog with buttons is shown, so that a test can answer it: with {@link #answerWith} set nothing is shown,
     * the hook is given what the dialog asks and names one of its answers, and that button's own
     * {@code ButtonType} is returned (through the dialog's result converter when it has one, as JavaFX does), so
     * a caller that compares the result by identity works the same. A hook that names no answer closes the
     * dialog: the result is then what JavaFX gives for Escape or the window's close button, the dialog's Cancel
     * when it has one and nothing otherwise.
     */
    static <R> Optional<R> showAndWait(Dialog<R> dialog) {
        Function<Asked, @Nullable Answer> answer = answerer;
        if (answer == null) {
            return dialog.showAndWait();
        }
        return Optional.ofNullable(answered(dialog, answer));
    }

    /**
     * Shows {@code dialog} without waiting, and gives {@code onResult} its result when it closes: what
     * {@code dialog.getResult()} holds then (null when it closed with no answer). The twin of {@link #showAndWait}
     * for a dialog that must not block its caller (one asked while the windows are still being restored, or beside
     * an editor that stays usable), and the one place such a dialog is shown, so that a test can answer it. It
     * takes the dialog's {@code onHidden}: a caller sets none of its own.
     *
     * <p>With {@link #answerWith} set nothing is shown. The hook is asked at once, while the dialog is as the
     * caller built it, and {@code onResult} is given its answer on a later pulse of the FX thread
     * ({@code Platform.runLater}), never inside this call: a shown dialog also answers only after {@code show()}
     * has returned, so what the caller does next (returns the dialog or a future, records that it asked) still
     * comes before the answer, and a callback that asks the next question does not nest inside this one.
     */
    static <R> void show(Dialog<R> dialog, java.util.function.Consumer<@Nullable R> onResult) {
        Function<Asked, @Nullable Answer> answer = answerer;
        if (answer == null) {
            dialog.setOnHidden(hidden -> onResult.accept(dialog.getResult()));
            dialog.show();
            return;
        }
        R result = answered(dialog, answer);
        javafx.application.Platform.runLater(() -> onResult.accept(result));
    }

    /** {@link #show(Dialog, java.util.function.Consumer)} for a dialog whose answer nobody reads (an error). */
    static void show(Dialog<?> dialog) {
        show(dialog, result -> {});
    }

    /** The result {@code dialog} has once {@code answer} has answered it, with nothing shown. */
    private static <R> @Nullable R answered(Dialog<R> dialog, Function<Asked, @Nullable Answer> answer) {
        Asked asked = asked(dialog);
        Answer chosen = answer.apply(asked);
        List<ButtonType> types = dialog.getDialogPane().getButtonTypes();
        ButtonType type = chosen == null ? closeAnswer(types) : types.get(indexOf(asked, chosen));
        Callback<ButtonType, R> converter = dialog.getResultConverter();
        @SuppressWarnings("unchecked") // a dialog with no converter has its button as its result, as in JavaFX
        R result = converter == null ? (R) type : converter.call(type);
        return result;
    }

    /**
     * For tests: answers every dialog shown through {@link #showAndWait} or
     * {@link #show(Dialog, java.util.function.Consumer)} with what {@code answer} names (one of
     * the {@link Asked#answers}, or null to close the dialog), and shows none. Pass null to show dialogs again.
     * Nothing in the app sets it.
     */
    static void answerWith(@Nullable Function<Asked, @Nullable Answer> answer) {
        answerer = answer;
    }

    /** What {@code dialog} asks, read from the dialog as it would be shown. */
    static Asked asked(Dialog<?> dialog) {
        DialogPane pane = dialog.getDialogPane();
        List<Answer> answers = new ArrayList<>();
        for (ButtonType type : pane.getButtonTypes()) {
            Button button = pane.lookupButton(type) instanceof Button b ? b : null;
            answers.add(new Answer(
                    button == null ? type.getText() : button.getText(),
                    type.getButtonData(),
                    button != null && button.isDefaultButton(),
                    button != null && button.getStyleClass().contains("danger")));
        }
        Node content = pane.getContent();
        String text = pane.getContentText();
        if (content != null) {
            List<String> lines = new ArrayList<>();
            collectText(content, lines);
            text = String.join("\n", lines);
        }
        return new Asked(dialog.getTitle(), pane.getHeaderText(), text, List.copyOf(answers), dialog);
    }

    /** Adds the text of every label, check box and text box under {@code node}, in order. */
    private static void collectText(@Nullable Node node, List<String> into) {
        if (node instanceof Labeled labeled) {
            if (labeled.getText() != null && !labeled.getText().isEmpty()) {
                into.add(labeled.getText());
            }
        } else if (node instanceof TextInputControl input) {
            if (input.getText() != null && !input.getText().isEmpty()) {
                into.add(input.getText());
            }
        } else if (node instanceof ScrollPane scroll) {
            collectText(scroll.getContent(), into);
        } else if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                collectText(child, into);
            }
        }
    }

    private static int indexOf(Asked asked, Answer chosen) {
        for (int i = 0; i < asked.answers().size(); i++) {
            if (asked.answers().get(i) == chosen) {
                return i;
            }
        }
        int byValue = asked.answers().indexOf(chosen);
        if (byValue < 0) {
            throw new IllegalArgumentException("not an answer of this dialog: " + chosen + " in " + asked.answers());
        }
        return byValue;
    }

    /** The button a closed dialog answers with, as {@code Dialog.close()} picks it; null when it has none. */
    private static @Nullable ButtonType closeAnswer(List<ButtonType> types) {
        ButtonType cancel = null;
        for (ButtonType type : types) {
            ButtonBar.ButtonData role = type.getButtonData();
            if (role == ButtonBar.ButtonData.CANCEL_CLOSE) {
                return type;
            }
            if (role != null && role.isCancelButton()) {
                cancel = type;
            }
        }
        return cancel;
    }

    /**
     * Keeps a form's first column — its field labels — as wide as its widest label: a label shrinks to "Ty…" before a
     * field that grows gives up any width, so a dialog narrower than its form cut them (Customize Rendering…).
     */
    static <G extends javafx.scene.layout.GridPane> G labelColumn(G grid) {
        javafx.scene.layout.ColumnConstraints labels = new javafx.scene.layout.ColumnConstraints();
        labels.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        labels.setHgrow(javafx.scene.layout.Priority.NEVER);
        javafx.scene.layout.ColumnConstraints fields = new javafx.scene.layout.ColumnConstraints();
        fields.setHgrow(javafx.scene.layout.Priority.ALWAYS);
        grid.getColumnConstraints().setAll(labels, fields);
        return grid;
    }

    /** Sets {@code dialog} up as the class comment says (once) and returns it. */
    static <D extends Dialog<?>> D styled(D dialog) {
        DialogPane pane = dialog.getDialogPane();
        var css = Dialogs.class.getResource(APP_CSS);
        if (css != null) {
            String url = com.editora.editor.UiZoom.sheet(css.toExternalForm());
            var sheets = pane.getStylesheets();
            if (!sheets.contains(url)) {
                sheets.add(url);
            }
        }
        if (pane.lookup(".button-bar") instanceof ButtonBar bar) {
            bar.setButtonOrder(BUTTON_ORDER);
        }
        wordStockButtons(pane);
        if (pane.getProperties().putIfAbsent(STYLED, Boolean.TRUE) == null) {
            // A button added after this call is worded too: DialogPane makes a new Button for each change.
            pane.getButtonTypes().addListener((ListChangeListener<ButtonType>) change -> wordStockButtons(pane));
        }
        return dialog;
    }

    /** Sets each stock button's text from the catalog; a button the app named itself is left alone. */
    private static void wordStockButtons(DialogPane pane) {
        for (ButtonType type : pane.getButtonTypes()) {
            String key = STOCK.get(type);
            if (key != null && pane.lookupButton(type) instanceof Button button) {
                button.setText(tr(key));
            }
        }
    }

    /** Cancel in the catalog's word, with the role that Escape and the window's close button answer. */
    static ButtonType cancel() {
        return new ButtonType(tr("dialog.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
    }

    /** A confirming button named {@code label}: the action's own verb, never OK or Yes. */
    static ButtonType action(String label) {
        return new ButtonType(label, ButtonBar.ButtonData.OK_DONE);
    }

    /**
     * Marks {@code dialog} as a question before a destructive action, for a dialog that cannot be a plain
     * {@link Confirm} (it has a checkbox, a third answer, a layout of its own): {@code action}'s button is
     * drawn in red and is no longer the default, and the dialog's Cancel becomes the one default button, so
     * it holds the focus when the question opens and Enter keeps what the user has. The red action then runs
     * only from its own button. Call it once the dialog's buttons are final: {@code DialogPane} makes new
     * buttons whenever they change.
     */
    static <D extends Dialog<?>> D destructive(D dialog, ButtonType action) {
        DialogPane pane = dialog.getDialogPane();
        if (pane.lookupButton(action) instanceof Button act) {
            if (!act.getStyleClass().contains("danger")) {
                act.getStyleClass().add("danger");
            }
            act.setDefaultButton(false);
        }
        defaultTo(dialog, cancelOf(dialog));
        return dialog;
    }

    /**
     * Makes {@code type}'s button the dialog's one default button: it holds the focus when the dialog opens
     * and Enter presses it. Every other button stops being a default, because JavaFX makes an {@code OK_DONE}
     * button one by itself.
     */
    static <D extends Dialog<?>> D defaultTo(D dialog, ButtonType type) {
        DialogPane pane = dialog.getDialogPane();
        for (ButtonType other : pane.getButtonTypes()) {
            if (pane.lookupButton(other) instanceof Button button) {
                button.setDefaultButton(other == type);
            }
        }
        return dialog;
    }

    /** The dialog's Cancel: its button with the {@code CANCEL_CLOSE} role (the stock one when it has none). */
    static ButtonType cancelOf(Dialog<?> dialog) {
        return dialog.getDialogPane().getButtonTypes().stream()
                .filter(type -> type.getButtonData() == ButtonBar.ButtonData.CANCEL_CLOSE)
                .findFirst()
                .orElse(ButtonType.CANCEL);
    }
}
