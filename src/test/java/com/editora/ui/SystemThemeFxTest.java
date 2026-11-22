package com.editora.ui;

import javafx.application.Application;

import com.editora.ui.SystemTheme.Appearance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A change of the OS's appearance applies the theme again in the running app (ICON-16, ACC-09): with "System"
 * chosen the user-agent sheet follows the OS's light or dark, Windows' high-contrast mode switches to the
 * high-contrast pair and back, and a named theme stays put through a light/dark change. The OS is a fake source;
 * the hook is the one {@link WindowManager} sets, {@link Themes#followSystem}.
 */
@Tag("fx")
class SystemThemeFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @AfterEach
    void restore() throws Exception {
        FxTestSupport.runOnFx(() -> {
            SystemTheme.reset();
            Themes.applyAppTheme(Themes.DEFAULT);
        });
    }

    @Test
    void aChangeOfTheOsAppearanceReappliesTheTheme() throws Exception {
        FxTestSupport.runOnFx(() -> {
            String[] chosen = {SystemTheme.SYSTEM};
            boolean[] editorToo = {false};
            SystemThemeTest.FakeSource os = new SystemThemeTest.FakeSource(new Appearance(false, false));
            SystemTheme.install(os);
            SystemTheme.onChange(
                    (before, after) -> editorToo[0] = Themes.followSystem(chosen[0], chosen[0], before, after));
            Themes.applyAppTheme(chosen[0]);
            assertSheet("editora-light");
            assertFalse(WindowColorScheme.isDark());

            os.set(new Appearance(true, false));
            assertSheet("editora-dark");
            assertTrue(WindowColorScheme.isDark(), "the window frames follow");
            assertTrue(editorToo[0], "the editor theme is told to follow too");

            os.set(new Appearance(true, true));
            assertSheet("high-contrast-dark");
            os.set(new Appearance(false, true));
            assertSheet("high-contrast-light");
            os.set(new Appearance(false, false));
            assertSheet("editora-light");

            chosen[0] = "Dracula";
            Themes.applyAppTheme(chosen[0]);
            String dracula = Application.getUserAgentStylesheet();
            os.set(new Appearance(true, false));
            assertTrue(dracula.equals(Application.getUserAgentStylesheet()), "a named theme stays");
            assertFalse(editorToo[0], "and nothing else re-applies");
            os.set(new Appearance(true, true));
            assertSheet("high-contrast-dark");
        });
    }

    private static void assertSheet(String base) {
        String sheet = Application.getUserAgentStylesheet();
        assertTrue(sheet != null && sheet.contains(base), "user-agent sheet " + sheet + " is not " + base);
    }
}
