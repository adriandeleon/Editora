package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.layout.Pane;

import com.editora.dap.TypeRenderer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Settings → Debugging's Type renderers list: Add…, Edit… and Remove, each saved at once. */
@Tag("fx")
class TypeRenderersEditorFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static Button button(Node root, String text) {
        if (root instanceof Button b && text.equals(b.getText())) {
            return b;
        }
        if (root instanceof Pane p) {
            for (Node child : p.getChildren()) {
                Button found = button(child, text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    @Test
    void everyChangeIsSaved() throws Exception {
        FxTestSupport.runOnFx(() -> {
            List<TypeRenderer> stored = new ArrayList<>(List.of(new TypeRenderer(true, "java", "Money", "this.a", "")));
            List<List<TypeRenderer>> saves = new ArrayList<>();
            TypeRenderer[] next = {new TypeRenderer(true, "", "Point", "", "x = this.x")};
            TypeRenderersEditor editor = new TypeRenderersEditor(new TypeRenderersEditor.Hooks(
                    () -> stored,
                    list -> {
                        saves.add(list);
                        stored.clear();
                        stored.addAll(list);
                    },
                    (owner, initial, isNew) -> Optional.ofNullable(next[0]),
                    () -> null));
            assertEquals(1, editor.list().getItems().size());

            button(editor.node(), tr("typerenderer.add")).fire();
            assertEquals(
                    List.of("Money", "Point"),
                    stored.stream().map(TypeRenderer::getType).toList());

            editor.list().getSelectionModel().select(0);
            next[0] = new TypeRenderer(true, "java", "Money", "this.b", "");
            button(editor.node(), tr("typerenderer.edit")).fire();
            assertEquals("this.b", stored.getFirst().getValue());

            next[0] = null; // cancelled: nothing saved
            int before = saves.size();
            button(editor.node(), tr("typerenderer.edit")).fire();
            assertEquals(before, saves.size());

            // Remove is saved at once, so it asks first, naming the renderer: Cancel keeps it and saves nothing.
            editor.list().getSelectionModel().select(1);
            before = saves.size();
            try (ConfirmAnswers questions = ConfirmAnswers.cancelling()) {
                button(editor.node(), tr("typerenderer.remove")).fire();
                assertEquals(
                        List.of(new Confirm.Question(
                                "Remove Type Renderer",
                                null,
                                "Remove the renderer for Point? This cannot be undone.",
                                "Remove")),
                        questions.asked());
                assertEquals(2, stored.size(), "Cancel removes nothing");
                assertEquals(before, saves.size());

                // Delete on the list is the same removal, asked about once.
                editor.list()
                        .getOnKeyPressed()
                        .handle(new javafx.scene.input.KeyEvent(
                                javafx.scene.input.KeyEvent.KEY_PRESSED,
                                "",
                                "",
                                javafx.scene.input.KeyCode.DELETE,
                                false,
                                false,
                                false,
                                false));
                assertEquals(2, questions.asked().size());
                assertEquals(2, stored.size());

                questions.answer(true);
                button(editor.node(), tr("typerenderer.remove")).fire();
                assertEquals(3, questions.asked().size(), "asked once for the removal");
            }
            assertEquals(
                    List.of("Money"), stored.stream().map(TypeRenderer::getType).toList());
            assertEquals(
                    "Remove the renderer for Money (java)? This cannot be undone.",
                    TypeRenderersEditor.removeQuestion(TypeRenderers.label(stored.getFirst()))
                            .message());
        });
    }
}
