package com.editora.ui;

import java.util.List;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import javafx.application.Platform;

import com.editora.Os;
import com.editora.i18n.Messages;
import org.jspecify.annotations.Nullable;

/**
 * About and Settings in macOS's application menu, the one named after the app, where every Mac app keeps them
 * (ABOUT-01, WIN-15). JavaFX builds that menu itself (Hide, Hide Others, Show All, Quit) and offers no way to add
 * to it: a {@code MenuBar} on the system menu bar only gets the menus after it. Glass's
 * {@code MacApplication.getAppleMenu} hands out the menu, and Glass's own menu items go into it. After the app
 * template's {@code ui/MacAppMenu} and Mirada's {@code os/MacAppMenu}. Quit, the item JavaFX puts in the same
 * menu, is caught in {@code com.editora.MacOpenFiles} (WIN-13).
 *
 * <p>{@code MacApplication} is in {@code com.sun.glass.ui.mac}, which exists only in macOS's JavaFX, so it is
 * reached by reflection, and only where that package is exported to Editora: the macOS launchers pass
 * {@code --add-exports javafx.graphics/com.sun.glass.ui.mac=com.editora} (the pom's {@code glass.mac.option}).
 * Without it the menu stays JavaFX's, and both commands are still in the Help and View menus and the palette.
 *
 * <p>Each item runs the focused window's own command ({@link WindowManager#runFromApplicationMenu}):
 * {@code help.about} and {@code view.settings}, the commands the Help and View menus' rows run. Those rows stay
 * where they are on every OS. The Settings item carries no key equivalent of its own: its title shows the chord
 * the keymap binds to {@code view.settings}, as the system menu bar's rows do (MENU-02), and {@link #refresh}
 * writes it again after a keymap change.
 */
public final class MacAppMenu {

    private static final Logger LOG = Logger.getLogger(MacAppMenu.class.getName());

    static final String ABOUT = "help.about";
    static final String SETTINGS = "view.settings";

    /** One item of the application menu: its title, written when it is installed or refreshed, and its command. */
    record Item(Supplier<String> title, String commandId) {}

    private MacAppMenu() {}

    /** The Settings item, once installed (a Glass {@code MenuItem}), and what writes its title. */
    private static @Nullable Object settingsItem;

    private static @Nullable Supplier<String> settingsTitle;

    /**
     * The two items, in order: About, then Settings with the keymap's chord. Each runs its command in the focused
     * window through {@code windows}. Package-visible so a test can run them without macOS's JavaFX.
     */
    static List<Item> items(WindowManager windows) {
        return List.of(
                new Item(() -> Messages.tr("command." + ABOUT), ABOUT),
                new Item(() -> settingsTitle(Messages.tr("command." + SETTINGS), windows.chordOf(SETTINGS)), SETTINGS));
    }

    /**
     * The Settings item's title: the command's title without its category ({@code View: Settings} reads
     * {@code Settings…}, the ellipsis macOS puts on an item that opens a window), then the chord the keymap binds
     * to it, spaced as the system menu bar's rows space theirs. Pure.
     */
    static String settingsTitle(String commandTitle, @Nullable String chord) {
        int colon = commandTitle.indexOf(": ");
        String title = (colon >= 0 ? commandTitle.substring(colon + 2) : commandTitle).strip() + "…";
        return chord == null || chord.isBlank() ? title : title + "   " + chord;
    }

    /**
     * Puts About and Settings at the top of the application menu, on the FX thread; each runs its command on the
     * FX thread. Returns what it did, or why it did nothing.
     */
    public static String install(WindowManager windows) {
        if (!Os.isMac()) {
            return "none (not macOS)";
        }
        Module ours = MacAppMenu.class.getModule();
        Module javafx = Platform.class.getModule();
        if (!javafx.isExported("com.sun.glass.ui", ours) || !javafx.isExported("com.sun.glass.ui.mac", ours)) {
            LOG.warning("No application menu items: com.sun.glass.ui.mac is not exported to Editora");
            return "none (com.sun.glass.ui.mac not exported)";
        }
        List<Item> items = items(windows);
        Item about = items.get(0);
        Item settings = items.get(1);
        try {
            String done = Glass.install(
                    about.title().get(),
                    () -> windows.runFromApplicationMenu(about.commandId()),
                    settings.title().get(),
                    () -> windows.runFromApplicationMenu(settings.commandId()));
            settingsTitle = settings.title();
            return done;
        } catch (Throwable t) { // an internal API: whatever it throws, the menu stays JavaFX's
            LOG.log(Level.WARNING, "No application menu items", t);
            return "none (" + t + ")";
        }
    }

    /** Writes the Settings item's title again: its chord follows the keymap (WIN-15). Nothing before install. */
    static void refresh() {
        Object item = settingsItem;
        Supplier<String> title = settingsTitle;
        if (item != null && title != null) {
            try {
                Glass.retitle(item, title.get());
            } catch (Throwable t) {
                LOG.log(Level.FINE, "Settings item not retitled", t);
            }
        }
    }

    /** Everything that names a Glass class, apart, so that loading {@link MacAppMenu} loads none. */
    static final class Glass {

        private Glass() {}

        static String install(String aboutTitle, Runnable about, String settingsTitle, Runnable settings)
                throws ReflectiveOperationException {
            com.sun.glass.ui.Application glass = com.sun.glass.ui.Application.GetApplication();
            if (glass == null) {
                return "none (no glass application)";
            }
            Object found = glass.getClass().getMethod("getAppleMenu").invoke(glass);
            if (!(found instanceof com.sun.glass.ui.Menu menu)) {
                return "none (no application menu)";
            }
            menu.insert(glass.createMenuItem(aboutTitle, callback(about)), 0);
            menu.insert(com.sun.glass.ui.MenuItem.Separator, 1);
            com.sun.glass.ui.MenuItem item =
                    glass.createMenuItem(settingsTitle, callback(settings)); // no key equivalent
            menu.insert(item, 2);
            menu.insert(com.sun.glass.ui.MenuItem.Separator, 3);
            settingsItem = item;
            return "about, settings";
        }

        static void retitle(Object item, String title) {
            if (item instanceof com.sun.glass.ui.MenuItem menuItem) {
                menuItem.setTitle(title);
            }
        }

        private static com.sun.glass.ui.MenuItem.Callback callback(Runnable action) {
            return new com.sun.glass.ui.MenuItem.Callback() {
                @Override
                public void action() {
                    action.run();
                }

                @Override
                public void validate() {}
            };
        }
    }
}
