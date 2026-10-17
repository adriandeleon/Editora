package com.editora.terminal;

import com.jediterm.terminal.emulator.mouse.MouseMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Who owns right-click.
 *
 * <p>A terminal wants it for Copy and Paste; a mouse-aware TUI wants it as button 2. Getting this
 * backwards is invisible in a plain shell and breaks exactly one of the two — either right-click is
 * dead inside every TUI, or Copy is unreachable from one.
 */
class ContextMenuContentionTest {

    @Test
    void withNoProgramGrabbingTheMouseTheMenuOpens() {
        assertTrue(TerminalView.shouldShowMenu(MouseMode.MOUSE_REPORTING_NONE, false, false));
    }

    @ParameterizedTest
    @EnumSource(value = MouseMode.class, names = "MOUSE_REPORTING_NONE", mode = EnumSource.Mode.EXCLUDE)
    void aProgramThatGrabbedTheMouseGetsThePlainClick(MouseMode mode) {
        assertFalse(TerminalView.shouldShowMenu(mode, false, false));
    }

    @ParameterizedTest
    @EnumSource(value = MouseMode.class, names = "MOUSE_REPORTING_NONE", mode = EnumSource.Mode.EXCLUDE)
    void shiftAlwaysReachesTheMenu(MouseMode mode) {
        // The escape hatch. Without it there is no way to copy anything out of htop.
        assertTrue(TerminalView.shouldShowMenu(mode, true, false));
    }

    @ParameterizedTest
    @EnumSource(MouseMode.class)
    void theKeyboardMenuKeyIsNeverContested(MouseMode mode) {
        // Nothing was reported to the program, and the user asked for a menu in so many words.
        assertTrue(TerminalView.shouldShowMenu(mode, false, true));
    }
}
