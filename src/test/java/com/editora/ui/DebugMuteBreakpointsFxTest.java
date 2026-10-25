package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.stage.Stage;

import com.editora.command.CommandRegistry;
import com.editora.config.SharedConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Follow-up 3: Mute Breakpoints lasted only for the window and showed nowhere in the editor. Now it is kept per
 * project, every breakpoint glyph is drawn faded with a hover that says so, the Run menu item is checked, and
 * the Debug window's state line says "breakpoints muted".
 */
@Tag("fx")
class DebugMuteBreakpointsFxTest {

    private static final String SOURCE =
            "public class Main {\n    public static void main(String[] args) {\n        int x = 1;\n    }\n}\n";

    private FxWindowFixture fx;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    private static void debugOn(SharedConfig shared) {
        shared.getSettings().setDebugSupport(true);
    }

    /** A window on {@code dir} with Main.java open and a breakpoint on its code line (2). */
    private MainController windowWithBreakpoint(Path dir) throws Exception {
        fx = FxWindowFixture.create(dir, DebugMuteBreakpointsFxTest::debugOn);
        MainController c = fx.controller;
        Path main =
                Files.writeString(Files.createDirectories(dir.resolve("src")).resolve("Main.java"), SOURCE);
        FxTestSupport.runOnFx(() -> c.openAndNavigate(main, 0));
        FxTestSupport.drainFx();
        FxTestSupport.runOnFx(() -> c.activeBuffer().toggleBreakpoint(2));
        FxTestSupport.drainFx();
        return c;
    }

    /** The style classes of every breakpoint glyph drawn in the window's gutters. */
    private List<List<String>> glyphs(MainController c) throws Exception {
        FxTestSupport.drainFx();
        return FxTestSupport.callOnFx(() -> {
            Stage stage = FxTestSupport.field(c, "stage");
            stage.getScene().getRoot().applyCss();
            stage.getScene().getRoot().layout();
            return stage.getScene().getRoot().lookupAll(".breakpoint-marker").stream()
                    .map(Node::getStyleClass)
                    .map(List::copyOf)
                    .toList();
        });
    }

    private String runMenuItem(MainController c, String paletteTitle) throws Exception {
        String title = com.editora.editor.MenuTitles.of(paletteTitle); // a menu row drops "Debug:" (ET3)
        return FxTestSupport.callOnFx(() -> {
            MainMenuBar menuBar = FxTestSupport.field(c, "menuBar");
            menuBar.refreshEnablement(); // what opening a menu does
            MenuBar bar = FxTestSupport.field(menuBar, "bar");
            java.util.ArrayDeque<MenuItem> todo = new java.util.ArrayDeque<>();
            for (Menu menu : bar.getMenus()) {
                todo.addAll(menu.getItems());
            }
            while (!todo.isEmpty()) { // the item may sit in a submenu
                MenuItem item = todo.poll();
                if (item instanceof Menu sub) {
                    todo.addAll(sub.getItems());
                    continue;
                }
                String text = MainMenuBar.displayTextOf(item);
                if (text != null && text.contains(title)) {
                    return text;
                }
            }
            return null;
        });
    }

    @Test
    void mutedBreakpointsAreFadedSaidOnHoverCheckedInTheMenuAndKeptForTheProject() throws Exception {
        Path dir = Files.createTempDirectory("editora-mute");
        MainController c = windowWithBreakpoint(dir);
        String title = tr("command.debug.muteBreakpoints");
        assertFalse(glyphs(c).isEmpty(), "the breakpoint is drawn");
        assertTrue(glyphs(c).stream().noneMatch(g -> g.contains("breakpoint-muted")), "not muted to begin with");
        assertFalse(runMenuItem(c, title).startsWith(MainMenuBar.CHECK));

        FxTestSupport.runOnFx(() -> {
            CommandRegistry reg = FxTestSupport.field(c, "registry");
            reg.run("debug.muteBreakpoints");
        });

        assertTrue(
                glyphs(c).stream().allMatch(g -> g.contains("breakpoint-muted")),
                glyphs(c).toString());
        String hover = FxTestSupport.callOnFx(
                () -> c.activeBuffer().getBreakpointManager().tooltip(2));
        assertTrue(hover.startsWith(tr("debug.breakpoint.muted")), hover);
        assertTrue(runMenuItem(c, title).startsWith(MainMenuBar.CHECK), runMenuItem(c, title));
        DebugCoordinator debug = FxTestSupport.field(c, "debugCoordinator");
        String state = FxTestSupport.callOnFx(() -> ((Label) FxTestSupport.field(debug.panel(), "status")).getText());
        assertTrue(state.contains(tr("debugpanel.muted")), state);
        com.editora.config.ConfigManager config = FxTestSupport.field(c, "config");
        assertTrue(FxTestSupport.callOnFx(() -> config.getWorkspaceState().isDebugBreakpointsMuted()));

        FxTestSupport.runOnFx(() -> {
            CommandRegistry reg = FxTestSupport.field(c, "registry");
            reg.run("debug.muteBreakpoints");
        });
        assertTrue(glyphs(c).stream().noneMatch(g -> g.contains("breakpoint-muted")));
        assertFalse(FxTestSupport.callOnFx(() -> config.getWorkspaceState().isDebugBreakpointsMuted()));
    }

    @Test
    void aWindowStartsMutedWhenTheProjectWasLeftMuted() throws Exception {
        Path dir = Files.createTempDirectory("editora-mute");
        Files.writeString(dir.resolve("workspace-state.json"), "{\"schemaVersion\":20,\"debugBreakpointsMuted\":true}");
        MainController c = windowWithBreakpoint(dir);
        DebugCoordinator debug = FxTestSupport.field(c, "debugCoordinator");

        assertTrue(FxTestSupport.callOnFx(debug::breakpointsMuted));
        assertTrue(
                glyphs(c).stream().allMatch(g -> g.contains("breakpoint-muted")),
                glyphs(c).toString());
        assertTrue(runMenuItem(c, tr("command.debug.muteBreakpoints")).startsWith(MainMenuBar.CHECK));
        assertEquals(
                List.of(),
                FxTestSupport.callOnFx(() -> {
                    @SuppressWarnings("unchecked")
                    var all = (List<com.editora.dap.DapModels.FileBreakpoints>)
                            FxTestSupport.call(debug, "collectBreakpoints", new Class[] {});
                    return all;
                }),
                "a muted project tells the adapter no breakpoints");
    }
}
