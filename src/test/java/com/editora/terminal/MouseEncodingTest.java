package com.editora.terminal;

import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;

import com.jediterm.terminal.emulator.mouse.MouseButtonCodes;
import com.jediterm.terminal.emulator.mouse.MouseButtonModifierFlags;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The JavaFX-to-JediTerm mouse mapping. */
class MouseEncodingTest {

    private static MouseEvent event(boolean control, boolean shift, boolean meta) {
        return new MouseEvent(
                MouseEvent.MOUSE_PRESSED,
                0,
                0,
                0,
                0,
                MouseButton.PRIMARY,
                1,
                shift,
                control,
                false,
                meta,
                true,
                false,
                false,
                false,
                false,
                false,
                null);
    }

    @Test
    void buttonsMapToTheProtocolCodes() {
        assertEquals(MouseButtonCodes.LEFT, MouseEncoding.buttonCode(MouseButton.PRIMARY));
        assertEquals(MouseButtonCodes.MIDDLE, MouseEncoding.buttonCode(MouseButton.MIDDLE));
        assertEquals(MouseButtonCodes.RIGHT, MouseEncoding.buttonCode(MouseButton.SECONDARY));
        assertEquals(MouseButtonCodes.NONE, MouseEncoding.buttonCode(MouseButton.NONE));
    }

    @Test
    void wheelCodesAreNamedForTheContentNotTheWheel() {
        // Turning the wheel up moves the content down, and the code is named after the content.
        // This reads backwards and is deliberate — it matches JediTerm's encoder.
        assertEquals(MouseButtonCodes.SCROLLDOWN, MouseEncoding.wheelButtonCode(40));
        assertEquals(MouseButtonCodes.SCROLLUP, MouseEncoding.wheelButtonCode(-40));
        assertEquals(MouseButtonCodes.NONE, MouseEncoding.wheelButtonCode(0));
    }

    @Test
    void modifierBitsMatchTheProtocolFlags() {
        assertEquals(0, MouseEncoding.modifierFlags(event(false, false, false)));
        assertEquals(
                MouseButtonModifierFlags.MOUSE_BUTTON_CTRL_FLAG,
                MouseEncoding.modifierFlags(event(true, false, false)));
        assertEquals(
                MouseButtonModifierFlags.MOUSE_BUTTON_SHIFT_FLAG,
                MouseEncoding.modifierFlags(event(false, true, false)));
        assertEquals(
                MouseButtonModifierFlags.MOUSE_BUTTON_META_FLAG,
                MouseEncoding.modifierFlags(event(false, false, true)));
        assertEquals(
                MouseButtonModifierFlags.MOUSE_BUTTON_CTRL_FLAG
                        | MouseButtonModifierFlags.MOUSE_BUTTON_SHIFT_FLAG
                        | MouseButtonModifierFlags.MOUSE_BUTTON_META_FLAG,
                MouseEncoding.modifierFlags(event(true, true, true)));
    }

    @Test
    void motionFlagIsNeverSetHereBecauseTheEncoderDerivesIt() {
        // Setting it too would flip the bit back and report a drag as an unrelated button.
        int all = MouseEncoding.modifierFlags(event(true, true, true));
        assertEquals(0, all & MouseButtonModifierFlags.MOUSE_BUTTON_MOTION_FLAG);
        assertEquals(0, all & MouseButtonModifierFlags.MOUSE_BUTTON_SCROLL_FLAG);
    }

    @ParameterizedTest(name = "deltaY {0} over line height {1} scrolls {2}")
    @CsvSource({
        "-16, 16, 1", // one line down
        "-160, 16, 10",
        "16, 16, -1", // one line up
        "0, 16, 0",
    })
    void pixelDeltasBecomeWholeLinesWithAwtSign(double deltaY, double lineHeight, int expected) {
        assertEquals(expected, MouseEncoding.unitsToScroll(deltaY, lineHeight));
    }

    @Test
    void aScrollSmallerThanOneLineStillScrollsOneLine() {
        // A slow trackpad drag reports a few pixels at a time; rounding those to zero would make
        // the wheel appear dead.
        assertEquals(1, MouseEncoding.unitsToScroll(-2, 16));
        assertEquals(-1, MouseEncoding.unitsToScroll(2, 16));
    }

    @Test
    void aFlungTrackpadIsCapped() {
        // Without the cap, one inertial fling sends hundreds of arrow keys to the shell.
        assertEquals(MouseEncoding.MAX_SCROLL_UNITS, MouseEncoding.unitsToScroll(-100_000, 16));
        assertEquals(-MouseEncoding.MAX_SCROLL_UNITS, MouseEncoding.unitsToScroll(100_000, 16));
    }

    @Test
    void aZeroLineHeightDoesNotDivideByZero() {
        // Reachable before the first layout pass has measured the font.
        assertTrue(Math.abs(MouseEncoding.unitsToScroll(-40, 0)) == 1);
    }

    @Test
    void altIsXtermsMetaBit() {
        // On Linux and Windows the Meta of a mouse report is Alt; it used to be dropped.
        MouseEvent alt = new MouseEvent(
                MouseEvent.MOUSE_PRESSED,
                0,
                0,
                0,
                0,
                MouseButton.PRIMARY,
                1,
                false,
                false,
                true,
                false,
                true,
                false,
                false,
                false,
                false,
                false,
                null);
        assertEquals(MouseButtonModifierFlags.MOUSE_BUTTON_META_FLAG, MouseEncoding.modifierFlags(alt));
    }
}
