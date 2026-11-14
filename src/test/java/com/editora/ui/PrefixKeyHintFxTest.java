package com.editora.ui;

import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

import com.editora.command.Command;
import com.editora.command.KeymapManager;
import com.editora.editor.MenuTitles;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * KBD-14 (Pauta 6.0.1): press {@code C-x} in the Emacs keymap and wait, and the window lists the keys that can
 * follow, each with what it runs. Driven through a real window and its own key dispatcher.
 */
@Tag("fx")
class PrefixKeyHintFxTest {

    private static FxWindowFixture fx;
    private static String keymapBefore;

    @BeforeAll
    static void window() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
        FxTestSupport.runOnFx(() -> {
            if (!fx.controller.stage.isShowing()) {
                fx.controller.stage.show(); // the list is laid out for the window's size
            }
            keymapBefore = fx.controller.keymap.activeName();
            fx.controller.keymap.loadNamed("emacs");
        });
    }

    @AfterAll
    static void close() throws Exception {
        if (fx != null) {
            FxTestSupport.runOnFx(() -> fx.controller.keymap.loadNamed(keymapBefore));
            fx.dispose();
        }
    }

    @AfterEach
    void endAnyPrefix() throws Exception {
        FxTestSupport.runOnFx(() -> {
            fx.controller.keymap.loadNamed("emacs");
            hint().prefixChanged("");
        });
    }

    private static PrefixKeyHint hint() {
        PrefixKeyHint hint = fx.controller.prefixHint;
        assertTrue(hint != null, "every window has its prefix hint");
        return hint;
    }

    private static KeymapManager keymap() {
        return fx.controller.keymap;
    }

    /** Presses a key in the window, through the scene, as the keyboard would. */
    private static void press(KeyCode code, boolean ctrl) throws Exception {
        FxTestSupport.runOnFx(() -> {
            Scene scene = fx.controller.stage.getScene();
            Node target = scene.getFocusOwner() != null ? scene.getFocusOwner() : scene.getRoot();
            Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, ctrl, false, false));
            Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_RELEASED, "", "", code, false, ctrl, false, false));
        });
    }

    private static boolean await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (FxTestSupport.callOnFx(condition::getAsBoolean)) {
                return true;
            }
            Thread.sleep(25);
        }
        return false;
    }

    private static String title(String commandId) {
        return MenuTitles.of(
                fx.controller.registry.get(commandId).map(Command::title).orElseThrow());
    }

    private static boolean lists(List<String> rows, String token, String what) {
        return rows.contains(keymap().display(token) + "\t" + what);
    }

    @Test
    void cxAndAMomentListTheKeysThatCanFollowWithoutTakingTheKeyboard() throws Exception {
        Node focusBefore =
                FxTestSupport.callOnFx(() -> fx.controller.stage.getScene().getFocusOwner());
        press(KeyCode.X, true);
        assertFalse(FxTestSupport.callOnFx(() -> hint().isShowing()), "not at once: C-x C-s typed fast never shows it");
        assertTrue(await(() -> hint().isShowing()), "C-x and a moment: the hint shows");
        FxTestSupport.runOnFx(() -> {
            PrefixKeyHint hint = hint();
            assertEquals(keymap().display("C-x"), hint.heading());
            List<String> rows = hint.rows();
            assertTrue(lists(rows, "C-s", title("file.save")), "C-s saves: " + rows);
            assertTrue(lists(rows, "C-f", title("file.find")), rows.toString());
            assertTrue(rows.stream().anyMatch(r -> r.startsWith(keymap().display("v") + "\t")), "C-x v leads further");
            Node card = hint.node();
            assertTrue(card.isMouseTransparent(), "it never catches a click");
            assertFalse(card.isFocusTraversable());
            assertSame(focusBefore, fx.controller.stage.getScene().getFocusOwner(), "it took no focus");
            assertTrue(card.getAccessibleText().contains(keymap().display("C-s")), "a screen reader is told the keys");
            // The Emacs C-x prefix has dozens of keys: every one is either listed or counted in the last cell.
            int total = keymap().continuations(
                            "C-x", id -> fx.controller.registry.get(id).isPresent())
                    .size();
            String last = rows.get(rows.size() - 1);
            assertTrue(
                    rows.size() == total || (!last.contains("\t") && rows.size() - 1 < total),
                    "all " + total + " keys listed or the rest counted: " + rows);
        });

        // The prefix grows: what follows C-x v, at once.
        press(KeyCode.V, false);
        FxTestSupport.runOnFx(() -> {
            assertTrue(hint().isShowing());
            assertEquals(keymap().display("C-x v"), hint().heading());
            assertTrue(
                    lists(hint().rows(), "l", title("git.fileHistory")),
                    hint().rows().toString());
        });

        // C-g cancels the sequence, and the hint goes with it.
        press(KeyCode.G, true);
        FxTestSupport.runOnFx(() -> assertFalse(hint().isShowing(), "a cancelled prefix takes the hint down"));
    }

    @Test
    void aCompletedSequenceNeverShowsTheHint() throws Exception {
        press(KeyCode.X, true);
        press(KeyCode.H, false); // C-x h: Select All, long before the delay
        Thread.sleep((long) PrefixKeyHint.DELAY.toMillis() + 300);
        FxTestSupport.drainFx();
        assertFalse(FxTestSupport.callOnFx(() -> hint().isShowing()));
    }

    @Test
    void theUsersOwnBindingIsListed() throws Exception {
        FxTestSupport.runOnFx(() -> {
            keymap().applyOverrides(Map.of("C-x C-9", "palette.show"));
            hint().prefixChanged("C-x");
            hint().show();
            assertTrue(
                    lists(hint().rows(), "C-9", title("palette.show")),
                    hint().rows().toString());
        });
    }

    /** The list is read from the keymap in force: after a switch, the same prefix lists the new keymap's keys. */
    @Test
    void itFollowsALiveKeymapSwitch() throws Exception {
        FxTestSupport.runOnFx(() -> {
            hint().prefixChanged("C-x");
            hint().show();
            assertTrue(hint().isShowing());
            keymap().loadNamed("vscode"); // C-x is Cut there, not a prefix
            hint().refresh();
            assertFalse(hint().isShowing(), "nothing follows C-x in VS Code");
            hint().prefixChanged("C-k");
            hint().show();
            assertEquals(keymap().display("C-k"), hint().heading());
            assertTrue(
                    hint().rows().stream().allMatch(r -> r.contains("\t") && !r.startsWith("C-")),
                    "VS Code's keys are written its way: " + hint().rows());
        });
    }

    @Test
    void aLongListFlowsIntoColumnsAndCountsWhatDoesNotFit() {
        // 37 keys, 200 px wide each, in a 1000 × 300 space: 4 columns of 10.
        assertEquals(new PrefixKeyHint.Grid(4, 10, 37), PrefixKeyHint.fit(37, 200, 20, 22, 2, 1000, 300));
        // Too small a window: 2 columns of 4, the last place says how many are left out.
        assertEquals(new PrefixKeyHint.Grid(2, 4, 7), PrefixKeyHint.fit(37, 200, 20, 22, 2, 500, 100));
        // A few keys: one row, as low as the hint can be, and never more columns than keys.
        assertEquals(new PrefixKeyHint.Grid(2, 1, 2), PrefixKeyHint.fit(2, 200, 20, 22, 2, 1000, 300));
        // A narrow cell in a window not laid out yet still gets a column.
        assertEquals(new PrefixKeyHint.Grid(1, 3, 2), PrefixKeyHint.fit(4, 200, 20, 22, 2, 0, 0));
    }
}
