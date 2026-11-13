package com.editora.ui;

import java.util.List;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
            // Java, so the text has regions to detect: plain text has none, and nothing would ever land.
            // Set on the empty document; the load that follows is not scanned on the FX thread.
            buffer.setLanguageOverride("java");
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
                buffer.getFoldManager().regions().size(),
                panel.outline().size(),
                FxTestSupport.<Integer>field(panel, "treeBuilds")
            };
        });
        StructurePanel panel = (StructurePanel) r[0];
        EditorBuffer buffer = (EditorBuffer) r[1];
        assertTrue((Long) r[3] > (Long) r[2], "attach asked for a (background) detection");
        assertEquals(0, r[4], "the loaded text was not scanned yet");
        assertEquals(0, r[5], "and attach did not scan it on the FX thread");
        assertEquals(0, r[6], "so the outline attach built has no rows yet");
        int buildsAtAttach = (Integer) r[7];

        // Wait for what the test means: the regions land, and the outline is rebuilt from them. Both happen
        // in one FX turn (the manager applies the regions and tells the panel), so one read sees both.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        Object[] landed = null;
        while (System.nanoTime() < deadline) {
            landed = FxTestSupport.callOnFx(() -> new Object[] {
                buffer.getFoldManager().regions().size(),
                panel.outline(),
                FxTestSupport.<Integer>field(panel, "treeBuilds"),
                FxTestSupport.<TreeView<?>>field(panel, "tree")
                        .getRoot()
                        .getChildren()
                        .size()
            });
            if ((Integer) landed[0] > 0) {
                break;
            }
            Thread.sleep(5);
        }
        assertNotNull(landed);
        assertTrue((Integer) landed[0] > 0, "the background detection landed its regions");
        @SuppressWarnings("unchecked")
        List<StructurePanel.Outline> outline = (List<StructurePanel.Outline>) landed[1];
        assertFalse(outline.isEmpty(), "the outline is built from the regions when the detection lands");
        assertEquals(0, outline.getFirst().line(), "from the class on the first line down");
        assertTrue((Integer) landed[2] > buildsAtAttach, "by a rebuild of the tree");
        assertTrue((Integer) landed[3] > 0, "which shows it");
        FxTestSupport.runOnFx(buffer::dispose);
    }

    private static long generation(EditorBuffer buffer) {
        return FxTestSupport.<Long>field(buffer.getFoldManager(), "recomputeGeneration");
    }
}
