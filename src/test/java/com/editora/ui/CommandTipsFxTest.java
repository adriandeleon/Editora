package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBase;

import com.editora.command.KeymapManager;
import com.editora.command.TextInputKeymap;
import com.editora.toolbar.ToolbarCatalog;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A button that runs a command is named after it: one string, the command's title and the keymap's chord, is its
 * tooltip and its accessible text, and follows a keymap switch. The main toolbar's buttons and the tool window
 * stripes' are named that way and stay out of the Tab order, and no toolbar button is the only way to its action.
 */
@Tag("fx")
class CommandTipsFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static KeymapManager keymap(String name) {
        KeymapManager keymap = new KeymapManager();
        keymap.loadNamed(name);
        return keymap;
    }

    /** Runs {@code body} with {@code keymap} as the app-wide one, and puts the earlier one back. */
    private static void withKeymap(KeymapManager keymap, Runnable body) {
        KeymapManager before = TextInputKeymap.sharedKeymap();
        TextInputKeymap.setShared(keymap);
        try {
            body.run();
        } finally {
            if (before != null) {
                TextInputKeymap.setShared(before);
            }
        }
    }

    private static <T extends Node> List<T> all(Node root, Class<T> type, List<T> out) {
        if (type.isInstance(root)) {
            out.add(type.cast(root));
        }
        if (root instanceof Parent p) {
            p.getChildrenUnmodifiable().forEach(c -> all(c, type, out));
        }
        return out;
    }

    @Test
    void theNameIsTheCommandsTitleAndChordInTheTooltipAndForTheScreenReaderAlike() throws Exception {
        FxTestSupport.runOnFx(() -> {
            KeymapManager cua = keymap("cua");
            withKeymap(cua, () -> {
                Button save = CommandTips.name(new Button(), "file.save");
                String name = CommandTips.title("file.save") + " (" + cua.displayChord("file.save") + ")";
                assertEquals("Save", CommandTips.title("file.save"), "the title without its palette category");
                assertEquals(name, save.getTooltip().getText());
                assertEquals(name, save.getAccessibleText(), "a screen reader says what the tooltip shows");
                assertEquals(
                        com.editora.editor.ControlNames.TOOLTIP_DELAY,
                        save.getTooltip().getShowDelay(),
                        "the one delay every named control's tooltip has");

                // A keymap switch: the same button, named again from the new keymap.
                KeymapManager emacs = keymap("emacs");
                TextInputKeymap.setShared(emacs);
                CommandTips.refresh();
                String switched = "Save (" + emacs.displayChord("file.save") + ")";
                assertEquals(switched, save.getTooltip().getText());
                assertEquals(switched, save.getAccessibleText());

                // An unbound command shows its title alone, never an empty pair of parentheses.
                Button unbound = CommandTips.name(new Button(), "view.floatToolWindow");
                assertEquals(CommandTips.title("view.floatToolWindow"), unbound.getAccessibleText());
            });
        });
    }

    @Test
    void aTogglesOwnWordsKeepTheCommandsChordAndFollowItsState() throws Exception {
        FxTestSupport.runOnFx(() -> {
            KeymapManager emacs = keymap("emacs");
            withKeymap(emacs, () -> {
                boolean[] maximized = {false};
                Button button = CommandTips.name(
                        new Button(), "view.maximizeToolWindow", () -> maximized[0] ? "Restore" : "Maximize");
                String chord = emacs.displayChord("view.maximizeToolWindow");
                assertNotNull(chord, "the Emacs keymap binds Maximize Tool Window");
                assertEquals("Maximize (" + chord + ")", button.getAccessibleText());
                maximized[0] = true;
                CommandTips.refresh(button);
                assertEquals("Restore (" + chord + ")", button.getAccessibleText());
                assertEquals(button.getAccessibleText(), button.getTooltip().getText());
            });
        });
    }

    @Test
    void theMainToolbarsButtonsAreNamedAfterTheirCommandsAndStayOutOfTheTabOrder(@TempDir Path dir) throws Exception {
        FxWindowFixture fx = FxWindowFixture.create(dir, c -> {});
        try {
            FxTestSupport.runOnFx(() -> {
                MainController c = fx.controller;
                List<ButtonBase> buttons = all(c.toolbarRow, ButtonBase.class, new ArrayList<>()).stream()
                        .filter(b -> b.getStyleClass().contains("toolbar-button"))
                        .toList();
                assertTrue(buttons.size() >= 15, "found the toolbar's buttons (" + buttons.size() + ")");
                for (ButtonBase b : buttons) {
                    String name = b.getAccessibleText();
                    assertTrue(name != null && !name.isBlank(), "a toolbar button has no accessible name: " + b);
                    assertNotNull(b.getTooltip(), "a toolbar button has no tooltip: " + name);
                    assertEquals(name, b.getTooltip().getText(), "one string for the tooltip and the name");
                    assertFalse(b.isFocusTraversable(), name + " is in the Tab order");
                }
                KeymapManager shared = TextInputKeymap.sharedKeymap();
                assertNotNull(shared);
                assertEquals(
                        CommandTips.withChord(CommandTips.title("file.save"), shared.displayChord("file.save")),
                        c.saveButton.getAccessibleText(),
                        "Save is named after File: Save");
                assertEquals(
                        CommandTips.withChord(CommandTips.title("run.stop"), shared.displayChord("run.stop")),
                        c.runConfigStopButton.getAccessibleText(),
                        "Stop is the Run: Stop Program command");
            });
        } finally {
            fx.close();
        }
    }

    @Test
    void noToolbarButtonIsTheOnlyWayToItsAction() {
        // The full menu bar's rows: a row only Simple mode's short menus have would not do.
        List<String> inMenus = MenuBarModel.menus(false).stream()
                .flatMap(m -> m.allEntries().stream())
                .toList();
        List<String> missing = new ArrayList<>();
        for (ToolbarCatalog.Item item : ToolbarCatalog.items()) {
            if (item.commandId() != null && !inMenus.contains(item.commandId())) {
                missing.add(item.commandId());
            }
        }
        // The fixed tail: the run configuration's three buttons, Simple mode and Settings.
        for (String id : List.of(
                "run.selectedConfig", "debug.selectedConfig", "run.stop", "view.toggleSimpleMode", "view.settings")) {
            if (!inMenus.contains(id)) {
                missing.add(id);
            }
        }
        assertEquals(List.of(), missing, "each toolbar button's command is also a menu row");
    }
}
