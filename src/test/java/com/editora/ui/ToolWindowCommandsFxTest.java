package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import javafx.scene.control.Button;
import javafx.scene.control.Label;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every tool window has a command that shows it when it is hidden and hides it when it shows, and its stripe
 * button runs that command: a press does what the command does first (tests that the feature is on), and never
 * opens a window the command would refuse.
 */
@Tag("fx")
class ToolWindowCommandsFxTest {

    /**
     * The Terminal's command gives an open terminal the keyboard before it hides it, so it is not a plain
     * toggle and its stripe button does not run it. Opening it here would also start a shell.
     */
    private static final String TERMINAL = "terminal";

    /** Windows whose command is not {@code tool.} + the window's id. No new one may join them. */
    private static final Set<String> NAMED_APART = Set.of("file-information", "externalTool");

    private static FxWindowFixture fx;

    @BeforeAll
    static void window() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
    }

    @AfterAll
    static void close() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    private static String echo() {
        StatusBar statusBar = FxTestSupport.field(fx.controller, "statusBar");
        return FxTestSupport.<Label>field(statusBar, "echo").getText();
    }

    private static Button stripeButton(ToolWindow tw) {
        Map<ToolWindow, Button> buttons = FxTestSupport.field(fx.controller.toolWindows, "stripeButtons");
        return buttons.get(tw);
    }

    @Test
    void everyToolWindowsCommandShowsItWhenHiddenAndHidesItWhenItShows() throws Exception {
        FxTestSupport.runOnFx(() -> {
            MainController c = fx.controller;
            ToolWindowManager windows = c.toolWindows;
            Set<String> shown = new TreeSet<>();
            Set<String> namedApart = new TreeSet<>();
            List<String> stuck = new ArrayList<>();
            for (ToolWindow tw : List.copyOf(windows.getRegisteredToolWindows())) {
                String command = tw.getCommandId();
                assertTrue(c.registry.get(command).isPresent(), tw.getId() + " has the command " + command);
                if (!command.equals("tool." + tw.getId())) {
                    namedApart.add(tw.getId());
                }
                if (TERMINAL.equals(tw.getId())) {
                    continue;
                }
                windows.close(tw);
                c.registry.run(command);
                if (!windows.isOpen(tw)) {
                    continue; // its feature is off in a fresh window, and it said so
                }
                shown.add(tw.getId());
                c.registry.run(command);
                if (windows.isOpen(tw)) {
                    stuck.add(command);
                    windows.close(tw);
                }
            }
            assertEquals(List.of(), stuck, "a command that shows its window and does not hide it");
            assertEquals(NAMED_APART, namedApart, "commands not named tool.<the window's id>");
            // The ones that only ever opened their window, and enough others that the loop proves something.
            assertTrue(
                    shown.containsAll(List.of("gitLog", "maven", "gradle", "npm", "cargo", "go", "sbt", "mill")),
                    shown.toString());
            assertTrue(shown.size() >= 25, shown.toString());
        });
    }

    @Test
    void aStripeButtonRunsItsWindowsCommand() throws Exception {
        FxTestSupport.runOnFx(() -> {
            MainController c = fx.controller;
            ToolWindowManager windows = c.toolWindows;
            // A plain window: the button shows it and hides it, as the command does.
            ToolWindow structure = c.structureToolWindow;
            windows.close(structure);
            stripeButton(structure).fire();
            assertTrue(windows.isOpen(structure));
            stripeButton(structure).fire();
            assertFalse(windows.isOpen(structure));

            // Debugging is off in a fresh window: the command says so, and so does the button. It used to open
            // the panel past the command's test.
            ToolWindow debug = c.debugToolWindow;
            windows.close(debug);
            c.setStatus("");
            stripeButton(debug).fire();
            assertFalse(windows.isOpen(debug), "the button does not open what the command refuses");
            assertEquals(tr("statusbar.tip.debugDisabled"), echo());

            // A window left on screen when its feature went off still closes on its button.
            windows.open(debug);
            assertTrue(windows.isOpen(debug));
            stripeButton(debug).fire();
            assertFalse(windows.isOpen(debug), "a window on screen always hides on its button");
        });
    }
}
