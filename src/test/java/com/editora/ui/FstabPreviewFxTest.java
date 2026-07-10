package com.editora.ui;

import java.util.ArrayList;
import java.util.List;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

import com.editora.editor.FstabPreview;
import com.editora.fstab.Fstab;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Renders the fstab preview node on the FX thread and checks its decoded content. */
@Tag("fx")
class FstabPreviewFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void rendersDecodedMountsAndError() throws Exception {
        String text = """
                UUID=abc-123  /      ext4  defaults,noatime  0 1
                /dev/sdb1     none   swap  sw                0 0
                /dev/sdc1     /backup
                """;
        List<String> labels = FxTestSupport.callOnFx(() -> {
            VBox node = FstabPreview.content(Fstab.parse(text), 700);
            node.applyCss();
            List<String> out = new ArrayList<>();
            collect(node, out);
            return out;
        });

        String all = String.join("\n", labels);
        assertTrue(all.contains("Mount the filesystem with UUID abc-123 at / as ext4"), all);
        assertTrue(all.contains("access times not updated"), all);
        assertTrue(all.contains("fsck-checked first (root)"), all);
        assertTrue(all.contains("Swap space on device /dev/sdb1"), all);
        assertTrue(labels.stream().anyMatch(s -> s.contains("at least 4")), all); // the broken line's error
    }

    private static void collect(Node node, List<String> out) {
        if (node instanceof Label l && l.getText() != null && !l.getText().isBlank()) {
            out.add(l.getText());
        }
        if (node instanceof Parent p) {
            for (Node child : p.getChildrenUnmodifiable()) {
                collect(child, out);
            }
        }
    }
}
