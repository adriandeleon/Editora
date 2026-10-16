package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.Callable;

import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;

import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The "No Project" window's TODO tool window follows the active tab: it is hidden (stripe button too) while
 * that tab has no TODOs, comes back on its own on the next tab — or edit — that has some if it was open when
 * hidden, and stays closed when the user closed it.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TodoWindowWithoutProjectFxTest {

    @TempDir
    Path dir;

    private FxWindowFixture fx;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
    }

    @AfterAll
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    private Tab add(String name, String text) throws Exception {
        Path file = Files.writeString(dir.resolve(name), text);
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setPath(file);
            b.setContent(text);
            return (Tab) FxTestSupport.call(
                    fx.controller, "addBuffer", new Class<?>[] {EditorBuffer.class, boolean.class}, b, true);
        });
    }

    private static void await(String what, Callable<Boolean> condition) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        while (!FxTestSupport.callOnFx(condition)) {
            assertTrue(Instant.now().isBefore(deadline), what);
            Thread.sleep(20);
        }
    }

    @Test
    void theWindowHidesOnATabWithoutTodosAndReturnsOnOneWithThem() throws Exception {
        TabPane tabs = FxTestSupport.field(fx.controller, "tabPane");
        ToolWindowManager toolWindows = FxTestSupport.field(fx.controller, "toolWindows");
        ToolWindow todo = FxTestSupport.field(fx.controller, "todoToolWindow");
        Set<ToolWindow> unavailable = FxTestSupport.field(toolWindows, "unavailable");

        Tab plain = add("plain.txt", "nothing to do\n");
        await("a tab without TODOs hides the window", () -> unavailable.contains(todo));
        Tab marked = add("marked.txt", "// TODO one\n");
        await("a tab with TODOs offers it", () -> !unavailable.contains(todo));

        FxTestSupport.runOnFx(() -> toolWindows.open(todo, false));
        FxTestSupport.runOnFx(() -> tabs.getSelectionModel().select(plain));
        await(
                "switching to the plain tab hides the open window",
                () -> !toolWindows.isOpen(todo) && unavailable.contains(todo));
        FxTestSupport.runOnFx(() -> tabs.getSelectionModel().select(marked));
        await("and switching back reopens it", () -> toolWindows.isOpen(todo));

        EditorBuffer buffer = FxTestSupport.callOnFx(fx.controller::activeBuffer);
        FxTestSupport.runOnFx(() -> buffer.setContent("done now\n"));
        await("deleting the last TODO hides it", () -> !toolWindows.isOpen(todo) && unavailable.contains(todo));
        FxTestSupport.runOnFx(() -> buffer.setContent("// FIXME again\n"));
        await("typing one brings it back", () -> toolWindows.isOpen(todo));

        FxTestSupport.runOnFx(() -> toolWindows.close(todo)); // the user's close is kept
        FxTestSupport.runOnFx(() -> tabs.getSelectionModel().select(plain));
        await("hidden again", () -> unavailable.contains(todo));
        FxTestSupport.runOnFx(() -> tabs.getSelectionModel().select(marked));
        await("offered again", () -> !unavailable.contains(todo));
        FxTestSupport.drainFx();
        assertTrue(FxTestSupport.callOnFx(() -> !toolWindows.isOpen(todo)), "a window the user closed stays closed");
    }
}
