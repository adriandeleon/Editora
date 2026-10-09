package com.editora.ui;

import java.util.concurrent.TimeUnit;

import javafx.scene.Scene;
import javafx.scene.control.TreeView;
import javafx.scene.layout.StackPane;

import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Attaching the Structure outline to a buffer (every tab switch) must not scan the document on the FX
 * thread: a hidden panel scans nothing, and a shown one asks the fold manager for a background detection
 * and rebuilds when it lands (T1 in the 2026-10 perf review).
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StructureAttachFxTest {

    private static final String SOURCE = "class A {\n  void m() {\n    int x;\n  }\n}\n";

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void aHiddenPanelScansNothingOnAttach() throws Exception {
        Object[] r = FxTestSupport.callOnFx(() -> {
            StructurePanel panel = new StructurePanel(); // not in a scene: the tool window is closed
            EditorBuffer buffer = new EditorBuffer();
            buffer.setInitialContent(SOURCE);
            long generationBefore = generation(buffer);
            int regionsBefore = buffer.getFoldManager().regions().size();
            panel.attach(buffer);
            return new Object[] {
                generationBefore,
                generation(buffer),
                regionsBefore,
                buffer.getFoldManager().regions().size(),
                FxTestSupport.<Boolean>field(panel, "pendingRebuild")
            };
        });
        assertEquals(r[0], r[1], "no detection, synchronous or background, was started by the attach");
        assertEquals(r[2], r[3], "the regions are whatever the fold manager held");
        assertTrue((Boolean) r[4], "the rebuild waits for the window to open");
    }

    @Test
    void aShownPanelDetectsInTheBackgroundAndRebuildsWhenTheRegionsLand() throws Exception {
        Object[] r = FxTestSupport.callOnFx(() -> {
            StructurePanel panel = new StructurePanel();
            new Scene(new StackPane(panel), 300, 400); // open tool window
            EditorBuffer buffer = new EditorBuffer();
            buffer.setInitialContent(SOURCE);
            long generationBefore = generation(buffer);
            int regionsBefore = buffer.getFoldManager().regions().size();
            panel.attach(buffer);
            return new Object[] {
                panel,
                buffer,
                generationBefore,
                generation(buffer),
                regionsBefore,
                buffer.getFoldManager().regions().size()
            };
        });
        StructurePanel panel = (StructurePanel) r[0];
        EditorBuffer buffer = (EditorBuffer) r[1];
        assertTrue((Long) r[3] > (Long) r[2], "attach asked for a (background) detection");
        assertEquals(r[4], r[5], "which did not run on the FX thread inside attach");

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        int children = 0;
        while (System.nanoTime() < deadline) {
            children = FxTestSupport.callOnFx(() -> {
                TreeView<?> tree = FxTestSupport.field(panel, "tree");
                return tree.getRoot() == null ? 0 : tree.getRoot().getChildren().size();
            });
            if (children > 0
                    && FxTestSupport.callOnFx(
                            () -> !buffer.getFoldManager().regions().isEmpty())) {
                break;
            }
            Thread.sleep(20);
        }
        assertTrue(children > 0, "the outline is built from the regions when the detection lands");
        FxTestSupport.runOnFx(buffer::dispose);
    }

    private static long generation(EditorBuffer buffer) {
        return FxTestSupport.<Long>field(buffer.getFoldManager(), "recomputeGeneration");
    }
}
