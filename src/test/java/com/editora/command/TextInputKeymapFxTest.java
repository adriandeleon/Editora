package com.editora.command;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javafx.application.Platform;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxToolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The installed key filters on a real {@link TextField}, with the platform flag forced to macOS: an
 * Option-composed character (how {@code @ [ ] { } | \ ~} are typed on German/Spanish Mac layouts) must reach
 * the field. The filter used to consume every {@code KEY_TYPED} with Option held on macOS.
 */
@Tag("fx")
class TextInputKeymapFxTest {

    @BeforeAll
    static void bootToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    private static void onFx(Runnable task) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                task.run();
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(10, TimeUnit.SECONDS), "FX task timed out");
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
    }

    private static KeymapManager emacs(boolean mac) {
        KeymapManager km = new KeymapManager();
        km.loadNamed("emacs", mac);
        return km;
    }

    private static KeyEvent typed(String ch, boolean ctrl, boolean alt, boolean meta) {
        return new KeyEvent(KeyEvent.KEY_TYPED, ch, "", KeyCode.UNDEFINED, false, ctrl, alt, meta);
    }

    private static KeyEvent press(KeyCode code, boolean ctrl, boolean alt) {
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, ctrl, alt, false);
    }

    /**
     * A field with the keymap installed for {@code mac}, plus a recorder of the typed characters that got
     * past the keymap's filter to the field's own handlers. (JavaFX's text behaviour applies host-specific
     * modifier rules of its own after that, so "reached the field" is the platform-independent observation.)
     */
    private static TextField field(String text, boolean mac, List<String> reached) {
        TextField field = new TextField(text);
        TextInputKeymap.install(field, emacs(mac), mac);
        field.addEventHandler(KeyEvent.KEY_TYPED, e -> reached.add(e.getCharacter()));
        return field;
    }

    @Test
    void anOptionComposedCharacterIsTypedOnMac() throws Exception {
        onFx(() -> {
            List<String> reached = new ArrayList<>();
            TextField field = field("", true, reached);
            // Option+L on a German Mac layout is "@"; KeyCode L with Option is M-l (downcase-word) in the
            // Emacs keymap, which a text field has no action for — so the press is not handled here.
            field.fireEvent(press(KeyCode.L, false, true));
            field.fireEvent(typed("@", false, true, false));
            field.fireEvent(typed("[", false, true, false));
            assertEquals(List.of("@", "["), reached, "Option-composed characters must reach the field");
        });
    }

    @Test
    void theGlyphOfAHandledOptionChordIsStillSwallowed() throws Exception {
        onFx(() -> {
            List<String> reached = new ArrayList<>();
            TextField field = field("one two", true, reached);
            field.positionCaret(0);
            field.fireEvent(press(KeyCode.F, false, true)); // M-f = nav.wordForward
            assertTrue(field.getCaretPosition() > 0, "M-f moved the caret");
            field.fireEvent(typed("ƒ", false, true, false)); // its Option glyph must not be inserted
            assertEquals(List.of(), reached);
            // …and the swallow is spent: the next Option character is the user's.
            field.fireEvent(typed("@", false, true, false));
            assertEquals(List.of("@"), reached);
        });
    }

    /**
     * A picker registers its own list-navigation filter before the keymap's, and consumes M-v (page up). The
     * keymap then never handled that press itself — and used to let its Option glyph through into the query.
     */
    @Test
    void theGlyphOfAChordAnEarlierFilterHandledIsSwallowedToo() throws Exception {
        onFx(() -> {
            List<String> reached = new ArrayList<>();
            TextField field = new TextField("Foo");
            field.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
                if (e.getCode() == KeyCode.V && e.isAltDown()) {
                    e.consume(); // the picker paged its list
                }
            });
            TextInputKeymap.install(field, emacs(true), true);
            field.addEventHandler(KeyEvent.KEY_TYPED, e -> reached.add(e.getCharacter()));

            field.fireEvent(press(KeyCode.V, false, true));
            field.fireEvent(typed("\u221a", false, true, false));
            assertEquals(List.of(), reached, "the chord's glyph is not text");

            field.fireEvent(typed("@", false, true, false));
            assertEquals(List.of("@"), reached, "and the swallow is spent");
        });
    }

    @Test
    void aHandledChordWithNoTypedEventDoesNotEatTheNextCharacter() throws Exception {
        onFx(() -> {
            List<String> reached = new ArrayList<>();
            TextField field = field("abc", false, reached);
            field.positionCaret(3);
            field.fireEvent(press(KeyCode.A, true, false)); // C-a: handled, and Ctrl+A emits no KEY_TYPED
            assertEquals(0, field.getCaretPosition());
            field.fireEvent(new KeyEvent(KeyEvent.KEY_RELEASED, "", "", KeyCode.A, false, true, false, false));
            field.fireEvent(typed("x", false, false, false));
            assertEquals(List.of("x"), reached, "the first character typed after the chord survives");
        });
    }

    @Test
    void commandTypedCharactersAreDroppedOnMacOnly() throws Exception {
        onFx(() -> {
            List<String> onMac = new ArrayList<>();
            field("", true, onMac).fireEvent(typed("s", false, false, true)); // Cmd+S's by-product
            assertEquals(List.of(), onMac);

            // Off the Mac, Ctrl+Alt is AltGr: its character is text and must not be filtered here.
            List<String> elsewhere = new ArrayList<>();
            field("", false, elsewhere).fireEvent(typed("€", true, true, false));
            assertEquals(List.of("€"), elsewhere, "an AltGr-composed character is left to the field");
        });
    }
}
