package com.editora.ui;

import java.lang.reflect.Proxy;

import com.editora.build.OutputStyle;
import com.editora.process.OutputBatch;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A console records no undo history. RichTextFX's default history is unlimited and records programmatic
 * appends and head trims even in a read-only area, so a console capped at 200,000 characters retained every
 * line a long-running program had printed. {@code RichTextAreaUndoPolicyTest} holds every other area to the
 * same decision; this drives the real append paths.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConsoleUndoFxTest {

    /** Enough 60-character lines to pass the consoles' 200,000-character cap, so the head trim runs too. */
    private static final int LINES = 5_000;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static String line(int i) {
        return "line " + i + " " + "x".repeat(50);
    }

    @Test
    void theRunConsoleKeepsNoUndoHistoryHoweverMuchIsAppended() throws Exception {
        FxTestSupport.runOnFx(() -> {
            RunPanel panel = new RunPanel(() -> {});
            CodeArea output = FxTestSupport.field(panel, "output");
            panel.started("java Main.java");
            for (int i = 0; i < LINES; i++) {
                panel.appendOutput(line(i), i % 7 == 0);
            }
            panel.appendPartialOutput("Name: ", false);
            assertTrue(output.getLength() <= 200_000, "the cap was reached and the head trimmed");
            assertTrue(output.getText().endsWith(line(LINES - 1) + "\nName: "));
            assertFalse(output.getUndoManager().isUndoAvailable(), "appends and trims are not recorded");

            // The same through a pump drain, where the lines land as one edit.
            OutputBatch.begin();
            for (int i = 0; i < LINES; i++) {
                panel.appendOutput(line(i), false);
            }
            OutputBatch.end();
            assertFalse(output.getUndoManager().isUndoAvailable());
            panel.clearConsole();
            assertFalse(output.getUndoManager().isUndoAvailable(), "nor is a clear");
        });
    }

    @Test
    void theBuildConsoleKeepsNoUndoHistory() throws Exception {
        FxTestSupport.runOnFx(() -> {
            BuildToolPanel panel = new BuildToolPanel();
            CodeArea output = FxTestSupport.field(panel, "output");
            panel.started("mvn verify", OutputStyle.console(), () -> {});
            for (int i = 0; i < LINES; i++) {
                panel.appendOutput(line(i), false);
            }
            assertTrue(output.getLength() <= 200_000);
            assertFalse(output.getUndoManager().isUndoAvailable());
        });
    }

    @Test
    void theDebugConsoleKeepsNoUndoHistory() throws Exception {
        FxTestSupport.runOnFx(() -> {
            DebugPanel panel = new DebugPanel(noopActions());
            CodeArea console = FxTestSupport.field(panel, "console");
            for (int i = 0; i < LINES; i++) {
                panel.appendOutput(line(i) + "\n", i % 7 == 0 ? "stderr" : "stdout");
            }
            assertTrue(console.getLength() <= 200_000);
            assertFalse(console.getUndoManager().isUndoAvailable());
        });
    }

    /** An area the user types into keeps undo — a bounded one. */
    @Test
    void anEditableFieldKeepsABoundedHistory() throws Exception {
        FxTestSupport.runOnFx(() -> {
            CodeArea body = AreaUndo.bounded(new CodeArea());
            int edits = AreaUndo.FIELD_HISTORY + 50;
            for (int i = 0; i < edits; i++) {
                body.appendText("a");
                body.getUndoManager().preventMerge(); // each its own undo step
            }
            int undone = 0;
            while (body.getUndoManager().isUndoAvailable() && undone <= edits) {
                body.undo();
                undone++;
            }
            assertEquals(AreaUndo.FIELD_HISTORY, undone, "the oldest steps are dropped at the bound");
            assertEquals(50, body.getLength(), "and undo itself still works");
        });
    }

    @Test
    void aReadOnlyAreaRecordsNothing() throws Exception {
        FxTestSupport.runOnFx(() -> {
            CodeArea viewer = AreaUndo.none(new CodeArea());
            viewer.replaceText("one file");
            viewer.replaceText("another");
            assertFalse(viewer.getUndoManager().isUndoAvailable());
        });
    }

    private static DebugPanel.Actions noopActions() {
        return (DebugPanel.Actions) Proxy.newProxyInstance(
                DebugPanel.Actions.class.getClassLoader(), new Class[] {DebugPanel.Actions.class}, (p, m, a) -> {
                    Class<?> rt = m.getReturnType();
                    if (rt == boolean.class) {
                        return false;
                    }
                    if (rt == int.class) {
                        return 0;
                    }
                    if (rt == long.class) {
                        return 0L;
                    }
                    return null;
                });
    }
}
