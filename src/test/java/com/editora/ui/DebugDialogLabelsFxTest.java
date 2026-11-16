package com.editora.ui;

import java.util.ArrayList;
import java.util.List;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;

import com.editora.config.ExceptionBreakpoint;
import com.editora.dap.StepFilter;
import com.editora.dap.TypeRenderer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The debugger's dialogs show their field labels whole: a label beside a field that grows was squeezed to "Ty…",
 * "La…", "V…" in Customize Rendering… (its wrapped hint made the dialog narrower than the form), and the other forms
 * built the same way could be too.
 */
@Tag("fx")
class DebugDialogLabelsFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    /** Shows {@code content} in a dialog as the debugger's dialogs do; the labels cut short in it. */
    private static List<String> cutLabels(Node content) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            Dialog<ButtonType> dialog = Dialogs.styled(new Dialog<ButtonType>());
            dialog.getDialogPane().getButtonTypes().setAll(Dialogs.cancel(), ButtonType.OK);
            dialog.getDialogPane().setContent(content);
            dialog.show();
            try {
                dialog.getDialogPane().applyCss();
                dialog.getDialogPane().layout();
                List<String> cut = new ArrayList<>();
                for (Label l : labels(content, new ArrayList<>())) {
                    if (!l.isWrapText() && l.getWidth() + 0.5 < l.prefWidth(-1)) {
                        cut.add(l.getText() + " (" + Math.round(l.getWidth()) + " < " + Math.round(l.prefWidth(-1))
                                + ")");
                    }
                }
                return cut;
            } finally {
                dialog.close();
            }
        });
    }

    private static List<Label> labels(Node node, List<Label> out) {
        if (node instanceof Label l && l.isVisible()) {
            out.add(l);
        }
        if (node instanceof Parent p) {
            p.getChildrenUnmodifiable().forEach(c -> labels(c, out));
        }
        return out;
    }

    @Test
    void customizeRenderingShowsItsLabelsWhole() throws Exception {
        assertEquals(
                List.of(),
                cutLabels(FxTestSupport.callOnFx(
                        () -> new TypeRendererDialog.Form(new TypeRenderer(true, "java", "Shelf", "", "")).node)));
    }

    @Test
    void anExceptionBreakpointShowsItsLabelsWhole() throws Exception {
        assertEquals(
                List.of(),
                cutLabels(FxTestSupport.callOnFx(() -> new ExceptionBreakpointDialog.Form(
                                new ExceptionBreakpoint("java", "java.io.IOException", "", true))
                        .node)));
    }

    @Test
    void dontStepIntoShowsItsLabelsWhole() throws Exception {
        assertEquals(
                List.of(),
                cutLabels(FxTestSupport.callOnFx(
                        () -> new StepFilterDialog.Form(new StepFilter(true, "java", "com.shop.util.*")).node)));
    }
}
