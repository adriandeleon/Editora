package com.editora.ui;

import java.util.List;
import java.util.stream.IntStream;

import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pickers in a wired window, under the Emacs and a GUI keymap: navigation and the legend both follow the
 * active keymap, the list pages, and — the reported defect — a GUI keymap's legend no longer advertises
 * {@code C-n}/{@code C-p}, which are {@code file.new}/{@code editor.print} there and never reach the picker.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PickerKeymapFxTest {

    private FxWindowFixture fx;
    private OverlayHost overlay;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
        overlay = FxTestSupport.field(fx.controller, "overlayHost");
    }

    @AfterAll
    void tearDown() throws Exception {
        if (fx != null) {
            FxTestSupport.runOnFx(() -> overlay.hide());
            useKeymap("emacs");
            fx.dispose();
        }
    }

    private void useKeymap(String name) throws Exception {
        FxTestSupport.runOnFx(() -> {
            fx.shared.getSettings().setKeymap(name);
            fx.windowManager.reloadSharedKeymap();
        });
    }

    private QuickOpen<String> picker(int rows) throws Exception {
        List<String> items = IntStream.range(0, rows).mapToObj(i -> "item-" + i).toList();
        return FxTestSupport.callOnFx(() -> {
            QuickOpen<String> p = new QuickOpen<>("Test", "Filter…", () -> items, s -> s, s -> "", s -> {});
            p.setOverlayHost(overlay);
            p.show(null);
            return p;
        });
    }

    private static <T> T field(Object target, String name) {
        return FxTestSupport.field(target, name);
    }

    private static KeyEvent press(TextField field, KeyCode code, boolean ctrl) {
        KeyEvent e = new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, ctrl, false, false);
        field.fireEvent(e);
        return e;
    }

    @Test
    void emacsKeymapNavigatesWithItsOwnChordsAndSaysSo() throws Exception {
        useKeymap("emacs");
        QuickOpen<String> p = picker(30);
        ListView<String> list = field(p, "list");
        TextField input = field(p, "input");
        Label hint = field(p, "hint");
        FxTestSupport.runOnFx(() -> {
            assertEquals("↑↓ / C-n C-p move  ·  ↵ select  ·  esc / C-g cancel", hint.getText());
            assertEquals(0, list.getSelectionModel().getSelectedIndex());
            press(input, KeyCode.N, true); // C-n
            assertEquals(1, list.getSelectionModel().getSelectedIndex());
            press(input, KeyCode.P, true); // C-p
            assertEquals(0, list.getSelectionModel().getSelectedIndex());
            press(input, KeyCode.G, true); // C-g = the keymap's cancel
            assertFalse(p.isShown(), "the keymap's cancel chord dismisses the picker");
        });
    }

    @Test
    void pickersPageAndJumpToTheEnds() throws Exception {
        useKeymap("emacs");
        QuickOpen<String> p = picker(30);
        ListView<String> list = field(p, "list");
        TextField input = field(p, "input");
        FxTestSupport.runOnFx(() -> {
            press(input, KeyCode.PAGE_DOWN, false);
            int afterPage = list.getSelectionModel().getSelectedIndex();
            assertTrue(afterPage > 1, "PageDown moves more than one row, was " + afterPage);
            press(input, KeyCode.PAGE_UP, false);
            assertEquals(0, list.getSelectionModel().getSelectedIndex());
            press(input, KeyCode.END, true); // Ctrl+End
            assertEquals(29, list.getSelectionModel().getSelectedIndex());
            press(input, KeyCode.PAGE_DOWN, false);
            assertEquals(29, list.getSelectionModel().getSelectedIndex(), "paging stops at the end, no wrap");
            press(input, KeyCode.HOME, true); // Ctrl+Home
            assertEquals(0, list.getSelectionModel().getSelectedIndex());
            // Plain Home/End stay with the query field's caret.
            input.setText("item");
            input.positionCaret(2);
            press(input, KeyCode.DOWN, false);
            int selected = list.getSelectionModel().getSelectedIndex();
            press(input, KeyCode.HOME, false);
            assertEquals(selected, list.getSelectionModel().getSelectedIndex(), "plain Home is the query field's");
            overlay.hide();
        });
    }

    @Test
    void aGuiKeymapPickerDoesNotClaimOrAdvertiseControlNAndP() throws Exception {
        useKeymap("cua");
        try {
            QuickOpen<String> p = picker(30);
            ListView<String> list = field(p, "list");
            TextField input = field(p, "input");
            Label hint = field(p, "hint");
            FxTestSupport.runOnFx(() -> {
                assertEquals("↑↓ move  ·  ↵ select  ·  esc cancel", hint.getText(), "no C-n/C-p/C-g in CUA");
                press(input, KeyCode.DOWN, false);
                assertEquals(1, list.getSelectionModel().getSelectedIndex(), "the arrows always work");
                press(input, KeyCode.ESCAPE, false);
                assertFalse(p.isShown(), "Esc always cancels");
            });
        } finally {
            useKeymap("emacs");
        }
    }

    @Test
    void theCommandPaletteLegendAndChordsFollowAKeymapSwitch() throws Exception {
        CommandPalette palette = FxTestSupport.field(fx.controller, "palette");
        Label hint = field(palette, "hint");
        Label chip = field(palette, "prefixChip");
        useKeymap("emacs");
        FxTestSupport.runOnFx(() -> {
            assertEquals("M-x", chip.getText());
            assertEquals("↑↓ / C-n C-p move  ·  ↵ run  ·  C-h docs  ·  esc / C-g cancel", hint.getText());
        });
        useKeymap("cua");
        try {
            FxTestSupport.runOnFx(() -> {
                String opener = chip.getText();
                assertFalse(opener.isEmpty(), "the GUI keymaps bind palette.show");
                assertFalse(opener.matches(".*\\b(C|M|S|Cmd)-.*"), "no raw Emacs tokens in a GUI keymap: " + opener);
                assertFalse(hint.getText().contains("C-n"), hint.getText());
                if (!com.editora.command.KeymapManager.isMac()) {
                    // C-h is Replace in the Win/Linux CUA map, so the docs key moves to one the keymap leaves free.
                    assertFalse(hint.getText().contains("Ctrl+H"), hint.getText());
                    assertTrue(hint.getText().contains("F1"), hint.getText());
                }
            });
        } finally {
            useKeymap("emacs");
        }
    }
}
