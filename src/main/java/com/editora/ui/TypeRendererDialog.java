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
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.stage.Window;
import javafx.util.StringConverter;

import com.editora.dap.DebugLanguage;
import com.editora.dap.DebugLanguages;
import com.editora.dap.TypeRenderer;
import com.editora.editor.UiZoom;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * The form of one {@link TypeRenderer} (IntelliJ's Customize Data Views → Java Type Renderers): the type, the language
 * it applies to, the value shown as an expression of {@code this}, the children as {@code name = expression} lines, and
 * whether it is on. Opened by the Variables menu's Customize Rendering… and by Settings → Debugging's renderers list.
 */
final class TypeRendererDialog {

    /** The form's controls, built without a window (tests fill and read it). */
    static final class Form {
        final CheckBox enabled = new CheckBox(tr("typerenderer.enabled"));
        final TextField type = new TextField();
        final ComboBox<String> language = new ComboBox<>();
        final TextField value = new TextField();
        final TextArea children = new TextArea();
        final GridPane node = new GridPane();

        Form(TypeRenderer initial) {
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
                    if (id == null || id.isEmpty()) {
                        return tr("typerenderer.anyLanguage");
                    }
                    return DebugLanguages.all().stream()
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
            enabled.setSelected(initial.isEnabled());
            type.setText(initial.getType());
            type.setPromptText(tr("typerenderer.typePrompt"));
            language.setValue(initial.getLanguage());
            value.setText(initial.getValue());
            value.setPromptText(tr("typerenderer.valuePrompt"));
            children.setText(initial.getChildren());
            children.setPromptText(tr("typerenderer.childrenPrompt"));
            children.setPrefRowCount(5);
            children.getStyleClass().add("type-renderer-children");
            value.getStyleClass().add("type-renderer-value");

            UiZoom.gaps(node, 8, 8);
            int row = 0;
            node.add(label(tr("typerenderer.type"), type), 0, row);
            node.add(type, 1, row++);
            node.add(label(tr("typerenderer.language"), language), 0, row);
            node.add(language, 1, row++);
            node.add(label(tr("typerenderer.value"), value), 0, row);
            node.add(value, 1, row++);
            node.add(label(tr("typerenderer.children"), children), 0, row);
            node.add(children, 1, row++);
            Label hint = new Label(tr("typerenderer.hint"));
            hint.setWrapText(true);
            hint.getStyleClass().add("settings-note");
            UiZoom.maxWidth(hint, 460);
            node.add(hint, 1, row++);
            node.add(enabled, 1, row);
            GridPane.setHgrow(type, Priority.ALWAYS);
            GridPane.setHgrow(value, Priority.ALWAYS);
            GridPane.setHgrow(children, Priority.ALWAYS);
            UiZoom.prefWidth(type, 360);
        }

        private static Label label(String text, javafx.scene.Node field) {
            Label l = new Label(text);
            l.setLabelFor(field);
            return l;
        }

        /** The renderer the form says; null while it names no type. */
        @Nullable
        TypeRenderer read() {
            String t = type.getText() == null ? "" : type.getText().strip();
            if (t.isEmpty()) {
                return null;
            }
            return new TypeRenderer(
                    enabled.isSelected(),
                    language.getValue() == null ? "" : language.getValue(),
                    t,
                    value.getText() == null ? "" : value.getText().strip(),
                    children.getText() == null ? "" : children.getText().strip());
        }
    }

    private TypeRendererDialog() {}

    /** Shows the form for {@code initial} over {@code owner}; the renderer saved, or empty when cancelled. */
    static Optional<TypeRenderer> edit(@Nullable Window owner, TypeRenderer initial, boolean isNew) {
        Form form = new Form(initial);
        Dialog<ButtonType> dialog = Dialogs.styled(new Dialog<ButtonType>());
        if (owner != null) {
            dialog.initOwner(owner);
        }
        dialog.setTitle(tr(isNew ? "typerenderer.titleNew" : "typerenderer.title"));
        dialog.setHeaderText(null);
        ButtonType save = new ButtonType(tr("typerenderer.save"), ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = Dialogs.cancel();
        dialog.getDialogPane().getButtonTypes().setAll(cancel, save);
        dialog.getDialogPane().setContent(form.node);
        javafx.scene.Node saveButton = dialog.getDialogPane().lookupButton(save);
        saveButton.disableProperty().bind(form.type.textProperty().isEmpty());
        Platform.runLater(() -> (form.type.getText().isBlank() ? form.type : form.value).requestFocus());
        Optional<ButtonType> answer = dialog.showAndWait();
        if (answer.isEmpty() || answer.get() != save) {
            return Optional.empty();
        }
        return Optional.ofNullable(form.read());
    }
}
