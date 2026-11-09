package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.stage.Stage;

import com.editora.command.KeyDispatcher;
import com.editora.i18n.Messages;
import com.editora.terminal.FakeConnector;
import com.editora.terminal.TerminalView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxToolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A terminal moved into the editor (Move to Editor) and back, with stand-in shells and a stand-in editor: the shell
 * survives both moves, and every way a tab can end — closed in the editor, its shell exiting, its editor tab removed,
 * the window closing — still ends its shell and its editor tab.
 */
@Tag("fx")
class TerminalEditorTabFxTest {

    private Stage stage;
    private TerminalPanel panel;
    private final TabPane editor = new TabPane();
    private final List<FakeConnector> shells = new ArrayList<>();
    private final AtomicInteger lastClosed = new AtomicInteger();

    @BeforeAll
    static void bootToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
        Messages.init("en");
    }

    @AfterEach
    void tearDown() throws Exception {
        fx(() -> {
            if (panel != null) panel.shutdown();
            if (stage != null) stage.close();
            return null;
        });
    }

    @Test
    void movingToTheEditorKeepsTheShellAndEmptiesThePanel() throws Exception {
        show((title, programs) -> true);
        fx(() -> {
            panel.newTerminal();
            return null;
        });
        TerminalView view = fx(panel::activeView);
        Tab tab = fx(panel::activeTab);

        fx(() -> {
            panel.moveToEditor(tab);
            return null;
        });

        assertFalse(shells.getFirst().wasClosed(), "the shell runs on");
        assertEquals(0, fx(panel::terminalCount), "the strip is empty");
        assertTrue(fx(panel::hasAnyTerminals));
        assertTrue(fx(() -> panel.isInEditor(tab)));
        assertEquals(1, lastClosed.get(), "the empty tool window hides, as when its last tab closes");
        assertEquals(1, fx(() -> editor.getTabs().size()));
        assertTrue(fx(() -> within(view, editor.getTabs().getFirst().getContent())), "the same view, in the editor");
        assertNotNull(
                fx(() -> tab.getContent().getProperties().get(KeyDispatcher.SHELL_KEYS)),
                "the shell keeps its keys in the editor");
    }

    @Test
    void movingBackToThePanelKeepsTheShellAndClosesTheEditorTab() throws Exception {
        show((title, programs) -> true);
        fx(() -> {
            panel.newTerminal();
            return null;
        });
        TerminalView view = fx(panel::activeView);
        Tab tab = fx(panel::activeTab);
        fx(() -> {
            panel.moveToEditor(tab);
            return null;
        });

        fx(() -> {
            panel.moveToPanel(tab);
            return null;
        });

        assertFalse(shells.getFirst().wasClosed(), "the shell runs on");
        assertEquals(0, fx(() -> editor.getTabs().size()), "its editor tab went");
        assertEquals(1, fx(panel::terminalCount));
        assertSame(tab, fx(panel::activeTab), "selected in the strip");
        assertSame(view, fx(panel::activeView));
        assertFalse(fx(() -> panel.isInEditor(tab)));
        await(() -> call(() -> within(view, panel)), "the view to be the panel's again");
    }

    @Test
    void closingInTheEditorEndsTheShellAndItsEditorTab() throws Exception {
        show((title, programs) -> true);
        Tab tab = movedToEditor();

        fx(() -> {
            panel.requestClose(tab);
            return null;
        });

        assertTrue(shells.getFirst().wasClosed());
        assertEquals(0, fx(() -> editor.getTabs().size()));
        assertFalse(fx(panel::hasAnyTerminals));
    }

    @Test
    void aBusyShellInTheEditorAsksBeforeClosingAndIsListedForTheWindow() throws Exception {
        AtomicBoolean answer = new AtomicBoolean(false);
        List<String> asked = new ArrayList<>();
        show((title, programs) -> {
            asked.add(title + ": " + programs);
            return answer.get();
        });
        FakeConnector program = new FakeConnector();
        fx(() -> panel.runProgram("Main", "java", null, (c, r) -> program, code -> {}));
        Tab tab = fx(panel::activeTab);
        fx(() -> {
            panel.moveToEditor(tab);
            return null;
        });

        assertEquals(List.of("Main (java)"), fx(() -> panel.busyTabs(java.time.Duration.ofSeconds(1))));
        fx(() -> {
            panel.requestClose(tab);
            return null;
        });
        assertEquals(List.of("Main: [java]"), asked);
        assertFalse(program.wasClosed(), "Cancel keeps it");
        assertEquals(1, fx(() -> editor.getTabs().size()));

        answer.set(true);
        fx(() -> {
            panel.requestClose(tab);
            return null;
        });
        assertTrue(program.wasClosed());
        assertEquals(0, fx(() -> editor.getTabs().size()));
    }

    @Test
    void aShellExitingInTheEditorTakesItsEditorTabWith() throws Exception {
        show((title, programs) -> true);
        movedToEditor();

        shells.getFirst().endOfOutput();

        await(() -> call(() -> editor.getTabs().isEmpty()), "the editor tab to close");
        assertFalse(fx(panel::hasAnyTerminals));
    }

    @Test
    void anEditorTabClosedElsewhereEndsItsShell() throws Exception {
        show((title, programs) -> true);
        Tab tab = movedToEditor();

        fx(() -> {
            editor.getTabs().clear(); // Close All, as the editor does it
            panel.editorTabClosed(tab);
            return null;
        });

        assertTrue(shells.getFirst().wasClosed());
        assertFalse(fx(panel::hasAnyTerminals));
    }

    @Test
    void theWindowClosingEndsShellsInTheEditorToo() throws Exception {
        show((title, programs) -> true);
        movedToEditor();
        fx(() -> {
            panel.newTerminal();
            panel.shutdown();
            return null;
        });
        assertTrue(shells.stream().allMatch(FakeConnector::wasClosed));
    }

    @Test
    void theCommandsActOnTheEditorTerminalThatHasTheKeyboard() throws Exception {
        show((title, programs) -> true);
        fx(() -> {
            panel.newTerminal(); // stays in the strip
            return null;
        });
        Tab stays = fx(panel::activeTab);
        Tab moved = movedToEditor();
        TerminalView movedView = fx(panel::viewsForTest).get(1);
        fx(() -> {
            editor.getSelectionModel().select(editor.getTabs().getFirst());
            movedView.requestFocus();
            return null;
        });
        await(() -> call(() -> panel.currentTab() == moved), "the moved terminal to be current");

        fx(() -> {
            panel.rename(panel.currentTab(), "build");
            return null;
        });
        assertEquals("build", fx(moved::getText), "renamed the one in the editor");
        assertFalse("build".equals(fx(stays::getText)));
    }

    // ---------------------------------------------------------------- helpers

    /** A new terminal, moved into the editor. */
    private Tab movedToEditor() throws Exception {
        fx(() -> {
            panel.newTerminal();
            return null;
        });
        Tab tab = fx(panel::activeTab);
        fx(() -> {
            panel.moveToEditor(tab);
            return null;
        });
        return tab;
    }

    private void show(TerminalPanel.CloseGuard guard) throws Exception {
        fx(() -> {
            panel = new TerminalPanel(
                    (dir, shell) -> (columns, rows) -> {
                        FakeConnector connector = new FakeConnector();
                        shells.add(connector);
                        return connector;
                    },
                    guard,
                    () -> (Path) null);
            panel.setOnLastClosed(lastClosed::incrementAndGet);
            java.util.Map<Tab, Tab> shown = new java.util.HashMap<>();
            panel.setEditorHost(new TerminalPanel.EditorHost() {
                @Override
                public void open(Tab tab) {
                    TerminalEditorTab content = new TerminalEditorTab(tab, () -> panel.focus(tab));
                    Tab editorTab = new Tab(content.title(), content.node());
                    shown.put(tab, editorTab);
                    editor.getTabs().add(editorTab);
                    editor.getSelectionModel().select(editorTab);
                }

                @Override
                public void close(Tab tab) {
                    editor.getTabs().remove(shown.remove(tab));
                }

                @Override
                public boolean isShowing(Tab tab) {
                    Tab editorTab = shown.get(tab);
                    return editorTab != null && editorTab.isSelected();
                }
            });
            stage = new Stage();
            stage.setScene(new Scene(new SplitPane(panel, editor), 1000, 400));
            stage.show();
            return null;
        });
    }

    private static boolean within(Node node, Node ancestor) {
        for (Node n = node; n != null; n = n.getParent()) {
            if (n == ancestor) return true;
        }
        return false;
    }

    private static boolean call(Callable<Boolean> c) {
        try {
            return fx(c);
        } catch (Exception e) {
            return false;
        }
    }

    private static <T> T fx(Callable<T> work) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                result.complete(work.call());
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        });
        return result.get(10, TimeUnit.SECONDS);
    }

    private static void await(BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(20);
        }
        throw new AssertionError("timed out waiting for " + what);
    }
}
