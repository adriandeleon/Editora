package com.editora.ui;

import java.util.ArrayList;
import java.util.List;

import javafx.application.ColorScheme;

import com.editora.ui.SystemTheme.Appearance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The choice of theme from what the OS asks (ICON-16, ACC-09): "System" is the light or dark Caret &amp; Ink theme,
 * Windows' high-contrast mode wins over every choice while it is on and gives it back when it ends, and a change
 * of appearance reaches the hook once, only when something changed. Pure: a fake source stands for
 * {@code Platform.getPreferences()}.
 */
class SystemThemeTest {

    private static final Appearance LIGHT = new Appearance(false, false);
    private static final Appearance DARK = new Appearance(true, false);
    private static final Appearance HC_LIGHT = new Appearance(false, true);
    private static final Appearance HC_DARK = new Appearance(true, true);

    @AfterEach
    void forget() {
        SystemTheme.reset();
    }

    @Test
    void systemIsTheCaretAndInkMemberOfTheOsScheme() {
        assertEquals("Editora Light", SystemTheme.resolve(SystemTheme.SYSTEM, LIGHT));
        assertEquals("Editora Dark", SystemTheme.resolve(SystemTheme.SYSTEM, DARK));
    }

    @Test
    void aNamedThemeIgnoresTheOsScheme() {
        assertEquals("Dracula", SystemTheme.resolve("Dracula", LIGHT));
        assertEquals("Editora Light", SystemTheme.resolve("Editora Light", DARK));
        assertEquals(SystemTheme.HIGH_CONTRAST_LIGHT, SystemTheme.resolve(SystemTheme.HIGH_CONTRAST_LIGHT, DARK));
    }

    @Test
    void theOsHighContrastModeWinsWhileItIsOnAndTheChoiceComesBack() {
        for (String choice : List.of(SystemTheme.SYSTEM, "Dracula", "Editora Light", "My Own Theme")) {
            assertEquals(SystemTheme.HIGH_CONTRAST_DARK, SystemTheme.resolve(choice, HC_DARK), choice);
            assertEquals(SystemTheme.HIGH_CONTRAST_LIGHT, SystemTheme.resolve(choice, HC_LIGHT), choice);
        }
        assertEquals("Dracula", SystemTheme.resolve("Dracula", DARK), "the mode ended");
    }

    @Test
    void aChangeOfAppearanceChangesOnlyTheThemesItDecides() {
        assertTrue(SystemTheme.changes(SystemTheme.SYSTEM, LIGHT, DARK));
        assertFalse(SystemTheme.changes("Dracula", LIGHT, DARK), "a named theme stays");
        assertTrue(SystemTheme.changes("Dracula", DARK, HC_DARK), "high contrast reaches every theme");
        assertTrue(SystemTheme.changes("Dracula", HC_DARK, HC_LIGHT), "and its light or dark");
        assertFalse(SystemTheme.changes(SystemTheme.HIGH_CONTRAST_DARK, DARK, HC_DARK), "already that theme");
    }

    @Test
    void thePreferencesAreReadAsAnAppearance() {
        assertEquals(DARK, SystemTheme.appearance(ColorScheme.DARK, null));
        assertEquals(LIGHT, SystemTheme.appearance(ColorScheme.LIGHT, Boolean.FALSE));
        assertEquals(HC_LIGHT, SystemTheme.appearance(ColorScheme.LIGHT, Boolean.TRUE));
        assertEquals(LIGHT, SystemTheme.appearance(null, "not a boolean"), "no OS answer is light, no high contrast");
    }

    @Test
    void theHookHearsEachRealChangeWithTheAppearanceBeforeAndAfter() {
        FakeSource os = new FakeSource(LIGHT);
        List<String> heard = new ArrayList<>();
        SystemTheme.install(os);
        SystemTheme.onChange((before, after) -> heard.add(before + " -> " + after));
        assertEquals(LIGHT, SystemTheme.appearance());

        os.set(DARK);
        os.set(DARK); // told again, nothing changed
        os.set(HC_DARK);
        assertEquals(List.of(LIGHT + " -> " + DARK, DARK + " -> " + HC_DARK), heard);
        assertEquals(HC_DARK, SystemTheme.appearance());
        assertEquals(SystemTheme.HIGH_CONTRAST_DARK, SystemTheme.resolve("Editora Light"));
    }

    @Test
    void aReplacedSourceIsNoLongerHeard() {
        FakeSource first = new FakeSource(LIGHT);
        SystemTheme.install(first);
        FakeSource second = new FakeSource(LIGHT);
        SystemTheme.install(second);
        List<Appearance> heard = new ArrayList<>();
        SystemTheme.onChange((before, after) -> heard.add(after));
        first.set(DARK);
        assertEquals(List.of(), heard);
        second.set(DARK);
        assertEquals(List.of(DARK), heard);
    }

    /** A stand-in for {@code Platform.getPreferences()}: {@link #set} changes the OS's appearance. */
    static final class FakeSource implements SystemTheme.Source {
        private Appearance appearance;
        private final List<Runnable> listeners = new ArrayList<>();

        FakeSource(Appearance appearance) {
            this.appearance = appearance;
        }

        void set(Appearance next) {
            appearance = next;
            listeners.forEach(Runnable::run);
        }

        @Override
        public Appearance current() {
            return appearance;
        }

        @Override
        public void onChange(Runnable changed) {
            listeners.add(changed);
        }
    }
}
