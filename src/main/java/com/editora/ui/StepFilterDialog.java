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

import com.editora.dap.DebugLanguage;
import com.editora.dap.DebugLanguages;
import com.editora.dap.StepFilter;
import com.editora.editor.UiZoom;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * The form of one Don't step into filter ({@link StepFilter}): the class, module, function or file pattern, the
 * language it applies to, and whether it is on. Opened by the call stack's Don't Step Into This… and by Settings →
 * Debugging's Don't step into list.
 */
final class StepFilterDialog {

    /** The form's controls, built without a window (tests fill and read it). */
    static final class Form {
        final CheckBox enabled = new CheckBox(tr("stepfilter.enabled"));
        final TextField pattern = new TextField();
        final ComboBox<String> language = new ComboBox<>();
        final GridPane node = new GridPane();

        Form(StepFilter initial) {
            List<String> ids = new ArrayList<>();
            ids.add("");
            for (DebugLanguage l : DebugLanguages.all()) {
                ids.add(l.id());
            }
            if (!ids.contains(initial.getLanguage())) {
                ids.add(initial.getLanguage());
            }
            language.getItems().setAll(ids);
            language.setConverter(new StringConverter<>() {
                @Override
                public String toString(String id) {
                    return languageName(id);
                }

                @Override
                public String fromString(String s) {
                    return s;
                }
            });
            enabled.setSelected(initial.isEnabled());
            pattern.setText(initial.getPattern());
            pattern.setPromptText(tr("stepfilter.patternPrompt"));
            language.setValue(initial.getLanguage());

            UiZoom.gaps(node, 8, 8);
            int row = 0;
            node.add(label(tr("stepfilter.pattern"), pattern), 0, row);
            node.add(pattern, 1, row++);
            node.add(label(tr("stepfilter.language"), language), 0, row);
            node.add(language, 1, row++);
            Label hint = new Label(tr("stepfilter.hint"));
            hint.setWrapText(true);
            hint.getStyleClass().add("settings-note");
            UiZoom.maxWidth(hint, 460);
            node.add(hint, 1, row++);
            node.add(enabled, 1, row);
            GridPane.setHgrow(pattern, Priority.ALWAYS);
            UiZoom.prefWidth(pattern, 360);
        }

        private static Label label(String text, javafx.scene.Node field) {
            Label l = new Label(text);
            l.setLabelFor(field);
            return l;
        }

        /** The filter the form says; null while it has no pattern. */
        @Nullable
        StepFilter read() {
            String p = pattern.getText() == null ? "" : pattern.getText().strip();
            if (p.isEmpty()) {
                return null;
            }
            return new StepFilter(enabled.isSelected(), language.getValue() == null ? "" : language.getValue(), p);
        }
    }

    private StepFilterDialog() {}

    /** A language's name as the form and the list show it ({@code ""}: every language). */
    static String languageName(@Nullable String id) {
        if (id == null || id.isEmpty()) {
            return tr("stepfilter.anyLanguage");
        }
        return DebugLanguages.all().stream()
                .filter(l -> l.id().equals(id))
                .map(DebugLanguage::displayName)
                .findFirst()
                .orElse(id);
    }

    /** Shows the form for {@code initial} over {@code owner}; the filter saved, or empty when cancelled. */
    static Optional<StepFilter> edit(@Nullable Window owner, StepFilter initial, boolean isNew) {
        Form form = new Form(initial);
        Dialog<ButtonType> dialog = Dialogs.styled(new Dialog<ButtonType>());
        if (owner != null) {
            dialog.initOwner(owner);
        }
        dialog.setTitle(tr(isNew ? "stepfilter.titleNew" : "stepfilter.title"));
        dialog.setHeaderText(null);
        ButtonType save = new ButtonType(tr("stepfilter.save"), ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = Dialogs.cancel();
        dialog.getDialogPane().getButtonTypes().setAll(cancel, save);
        dialog.getDialogPane().setContent(form.node);
        javafx.scene.Node saveButton = dialog.getDialogPane().lookupButton(save);
        saveButton.disableProperty().bind(form.pattern.textProperty().isEmpty());
        Platform.runLater(form.pattern::requestFocus);
        Optional<ButtonType> answer = dialog.showAndWait();
        if (answer.isEmpty() || answer.get() != save) {
            return Optional.empty();
        }
        return Optional.ofNullable(form.read());
    }
}
