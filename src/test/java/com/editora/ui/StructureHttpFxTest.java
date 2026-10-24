package com.editora.ui;

import java.util.ArrayList;
import java.util.List;

import javafx.scene.Scene;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.text.Text;

import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** A {@code .http} file's Structure: one row per request, not a method — no {@code ()}, no function icon (U11). */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StructureHttpFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void aRequestRowReadsAsTheRequestWithoutACallSuffix() throws Exception {
        String rendered = FxTestSupport.callOnFx(() -> {
            StructurePanel p = new StructurePanel();
            new Scene(new StackPane(p), 400, 400);
            EditorBuffer b = new EditorBuffer();
            b.setDisplayName("api.http");
            b.setContent("### Slow\nGET {{host}}/slow\n\n###\nPOST {{host}}/echo\n");
            p.attach(b);
            @SuppressWarnings("unchecked")
            TreeView<Object> tree = (TreeView<Object>) FxTestSupport.<TreeView<?>>field(p, "tree");
            List<String> rows = new ArrayList<>();
            for (var item : tree.getRoot().getChildren()) {
                @SuppressWarnings("unchecked")
                TreeCell<Object> cell = (TreeCell<Object>) tree.getCellFactory().call(tree);
                Object value = item.getValue();
                FxTestSupport.call(cell, "updateItem", new Class<?>[] {value.getClass(), boolean.class}, value, false);
                StringBuilder sb = new StringBuilder();
                for (var n : ((HBox) cell.getGraphic()).getChildren()) {
                    if (n instanceof Text t && !t.getStyleClass().contains("structure-line-number")) {
                        sb.append(t.getText());
                    }
                }
                rows.add(sb.toString());
            }
            return String.join("|", rows);
        });
        assertEquals("Slow — GET {{host}}/slow|POST {{host}}/echo", rendered);
        assertFalse(rendered.contains("()"), rendered);
        assertNotEquals(StructureIcons.pathFor("method"), StructureIcons.pathFor(StructureOutline.HTTP_REQUEST_KIND));
    }
}
