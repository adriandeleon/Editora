package com.editora.ui;

import com.editora.command.Command;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * A command of its own for each find bar that is not the editor's: over the Output, Run, External Tools and
 * Debug consoles, over a test's output and over the Application Log. Each shows its panel and opens the bar,
 * and run again while the bar is open it steps to the next match, as {@code find.show} does over the editor.
 * The keymap's find chord, pressed in one of those views, still reaches the same bar.
 */
final class FindBarCommands {

    private FindBarCommands() {}

    static void register(MainController mc) {
        mc.registry.register(Command.of("buildOutput.find", () -> {
            show(mc, mc.buildOutputToolWindow);
            if (!mc.buildOutputPanel.findInShownOutput()) {
                mc.setStatus(tr("status.output.noConsole"));
            }
        }));
        mc.registry.register(Command.of("run.findInOutput", () -> {
            show(mc, mc.runToolWindow);
            mc.runCoordinator.panel().openFind();
        }));
        mc.registry.register(Command.of("externalTool.findInOutput", () -> {
            show(mc, mc.externalToolToolWindow);
            mc.externalToolCoordinator.panel().openFind();
        }));
        mc.registry.register(Command.of(
                "debug.findInConsole",
                () -> mc.debugCoordinator.ifDebug(() -> {
                    show(mc, mc.debugToolWindow);
                    mc.debugCoordinator.panel().openConsoleFind();
                })));
        mc.registry.register(Command.of("test.findInOutput", () -> {
            show(mc, mc.testResultsToolWindow);
            mc.testRunCoordinator.panel().openFind();
        }));
        mc.registry.register(Command.of("debugLog.find", mc::findInDebugLog));
    }

    /** Opens the tool window the bar is in and gives it the keyboard; a window this mode does not have is skipped. */
    private static void show(MainController mc, @Nullable ToolWindow window) {
        if (window != null) {
            mc.toolWindows.open(window, true);
        }
    }
}
