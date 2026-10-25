package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import com.editora.lsp.JavaCleanups;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * The Java clean-ups chooser behind Settings' "Choose clean-ups…": one checkbox per jdtls clean-up
 * ({@link JavaCleanups#ALL}), the chosen ones ticked. OK hands back the ticked ids in the catalog's order.
 */
final class JavaCleanupsDialog {

    private JavaCleanupsDialog() {}

    /** The checkboxes for {@code chosen}, one per clean-up, in the catalog's order (each box's user data its id). */
    static List<CheckBox> boxes(List<String> chosen) {
        List<CheckBox> out = new ArrayList<>();
        for (String id : JavaCleanups.ALL) {
            CheckBox box = new CheckBox(tr("settings.javaCleanup." + id));
            box.setUserData(id);
            box.setSelected(chosen.contains(id));
            out.add(box);
        }
        return out;
    }

    /** The ids of the ticked {@code boxes}. */
    static List<String> ticked(List<CheckBox> boxes) {
        return boxes.stream()
                .filter(CheckBox::isSelected)
                .map(b -> (String) b.getUserData())
                .toList();
    }

    static void show(@Nullable Window owner, List<String> chosen, Consumer<List<String>> onChosen) {
        List<CheckBox> boxes = boxes(chosen);
        VBox list = new VBox(6);
        list.getChildren().addAll(boxes);
        ScrollPane scroll = new ScrollPane(list);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(360);
        Dialog<ButtonType> dialog = Dialogs.styled(new Dialog<>());
        if (owner != null) {
            dialog.initOwner(owner);
        }
        dialog.setTitle(tr("settings.javaCleanups.title"));
        dialog.setHeaderText(tr("settings.javaCleanups.header"));
        dialog.getDialogPane().setContent(scroll);
        dialog.getDialogPane().getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.showAndWait().filter(b -> b == ButtonType.OK).ifPresent(b -> onChosen.accept(ticked(boxes)));
    }
}
