package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javafx.application.Platform;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.stage.Window;
import javafx.util.StringConverter;

import com.editora.config.ExceptionBreakpoint;
import com.editora.dap.DebugLanguage;
import com.editora.dap.DebugLanguages;
import com.editora.editor.UiZoom;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * The form of one {@link ExceptionBreakpoint}: the exception type (patterns), the language whose sessions it applies
 * to, the condition evaluated where it was thrown, and whether it is on. Opened from Debug: Exception Breakpoints
 * (its Add row, or a breakpoint's row) and Debug: Add Exception Breakpoint.
 */
final class ExceptionBreakpointDialog {

    /** What the form was closed with: the breakpoint saved, or {@code remove} (an existing one taken away). */
    record Result(@Nullable ExceptionBreakpoint breakpoint, boolean remove) {}

    /** The form's controls, built without a window (tests fill and read it). */
    static final class Form {
        final TextField type = new TextField();
        final ComboBox<String> language = new ComboBox<>();
        final TextField condition = new TextField();
        final CheckBox enabled = new CheckBox(tr("exceptionbp.enabled"));
        final GridPane node = new GridPane();

        Form(ExceptionBreakpoint initial) {
            List<String> ids = new ArrayList<>();
            for (DebugLanguage l : DebugLanguages.all()) {
                ids.add(l.id());
            }
            if (!ids.contains(initial.language())) {
                ids.add(initial.language());
            }
            language.getItems().setAll(ids);
            language.setConverter(new StringConverter<>() {
                @Override
                public String toString(String id) {
                    return id == null
                            ? ""
                            : DebugLanguages.all().stream()
                                    .filter(l -> l.id().equals(id))
                                    .map(DebugLanguage::displayName)
                                    .findFirst()
                                    .orElse(id);
                }

                @Override
                public String fromString(String s) {
                    return s;
                }
            });
            language.setValue(initial.language());
            type.setText(initial.type());
            type.setPromptText(tr("exceptionbp.typePrompt"));
            condition.setText(initial.condition());
            condition.setPromptText(tr("exceptionbp.conditionPrompt"));
            enabled.setSelected(initial.enabled());
            type.getStyleClass().add("exception-breakpoint-type");

            UiZoom.gaps(node, 8, 8);
            int row = 0;
            node.add(label(tr("exceptionbp.type"), type), 0, row);
            node.add(type, 1, row++);
            node.add(label(tr("exceptionbp.language"), language), 0, row);
            node.add(language, 1, row++);
            node.add(label(tr("exceptionbp.condition"), condition), 0, row);
            node.add(condition, 1, row++);
            Label hint = new Label(tr("exceptionbp.hint"));
            hint.setWrapText(true);
            hint.getStyleClass().add("settings-note");
            UiZoom.maxWidth(hint, 460);
            node.add(hint, 1, row++);
            node.add(enabled, 1, row);
            GridPane.setHgrow(type, Priority.ALWAYS);
            GridPane.setHgrow(condition, Priority.ALWAYS);
            UiZoom.prefWidth(type, 360);
        }

        private static Label label(String text, javafx.scene.Node field) {
            Label l = new Label(text);
            l.setLabelFor(field);
            return l;
        }

        /** The breakpoint the form says. */
        ExceptionBreakpoint read() {
            return new ExceptionBreakpoint(
                    language.getValue() == null ? "" : language.getValue(),
                    type.getText(),
                    condition.getText(),
                    enabled.isSelected());
        }
    }

    private ExceptionBreakpointDialog() {}

    /**
     * Shows the form for {@code initial} over {@code owner} ({@code isNew}: Add, else Edit with a Remove button); what
     * it was closed with, or empty when cancelled.
     */
    static Optional<Result> edit(@Nullable Window owner, ExceptionBreakpoint initial, boolean isNew) {
        Form form = new Form(initial);
        Dialog<ButtonType> dialog = Dialogs.styled(new Dialog<ButtonType>());
        if (owner != null) {
            dialog.initOwner(owner);
        }
        dialog.setTitle(tr(isNew ? "exceptionbp.titleNew" : "exceptionbp.title"));
        dialog.setHeaderText(null);
        ButtonType save = new ButtonType(tr("exceptionbp.save"), ButtonBar.ButtonData.OK_DONE);
        ButtonType remove = new ButtonType(tr("exceptionbp.remove"), ButtonBar.ButtonData.LEFT);
        ButtonType cancel = Dialogs.cancel();
        if (isNew) {
            dialog.getDialogPane().getButtonTypes().setAll(cancel, save);
        } else {
            dialog.getDialogPane().getButtonTypes().setAll(remove, cancel, save);
        }
        dialog.getDialogPane().setContent(form.node);
        Platform.runLater(form.type::requestFocus);
        Optional<ButtonType> answer = dialog.showAndWait();
        if (answer.isEmpty() || answer.get() == cancel) {
            return Optional.empty();
        }
        return Optional.of(answer.get() == remove ? new Result(null, true) : new Result(form.read(), false));
    }
}
