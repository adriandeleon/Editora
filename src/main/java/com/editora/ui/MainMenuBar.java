package com.editora.ui;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;

import com.editora.command.Command;
import com.editora.command.CommandRegistry;
import com.editora.command.KeymapManager;

import static com.editora.i18n.Messages.tr;

/**
 * The window's menu bar: a browsable map of what Editora can do, built from {@link MenuBarModel} over the
 * existing {@link CommandRegistry} (#763).
 *
 * <p>Everything an item needs already exists on the command it names — localized title, live keybinding,
 * whether it currently applies — so this class only assembles and refreshes; it holds no behaviour of its own.
 *
 * <h2>Why no keyboard accelerators</h2>
 *
 * <p>JavaFX menu items can carry a {@code KeyCombination} accelerator, which renders natively and fires the
 * item. This deliberately uses none, for two independent reasons:
 *
 * <ul>
 *   <li><b>Most of Editora's chords cannot be expressed as one.</b> The default keymap is Emacs, where the
 *       common bindings are multi-key sequences — {@code C-x C-s}, {@code C-c r}, {@code M-g d}. A
 *       {@code KeyCombination} is a single keystroke; there is no way to represent those at all.
 *   <li><b>On macOS an accelerator would bypass the dispatcher.</b> Under {@code useSystemMenuBar} the native
 *       menu claims accelerators before JavaFX delivers the key, so the keystroke would never reach
 *       {@link com.editora.command.KeyDispatcher} — including its pending-prefix state, which is what makes
 *       multi-key chords work at all. A half-consumed prefix is worse than no accelerator.
 * </ul>
 *
 * <p>So the chord is shown as part of the item's <em>text</em>, which renders identically in the in-window
 * bar and in the macOS system menu bar. It reads as a hint rather than a native accelerator — the honest
 * trade for a keymap this class cannot faithfully mirror.
 */
final class MainMenuBar {

    private final MenuBar bar = new MenuBar();
    private final CommandRegistry registry;
    private final Supplier<Map<String, String>> chordsByCommand;
    private final Supplier<Chrome.PaletteGates> gates;
    private final Supplier<Chrome.PaletteContext> context;

    /** Items by command id, so {@link #refresh()} can restyle them without rebuilding the bar. */
    private final Map<String, MenuItem> items = new LinkedHashMap<>();

    MainMenuBar(
            CommandRegistry registry,
            Supplier<Map<String, String>> chordsByCommand,
            Supplier<Chrome.PaletteGates> gates,
            Supplier<Chrome.PaletteContext> context,
            Consumer<String> run) {
        this.registry = registry;
        this.chordsByCommand = chordsByCommand;
        this.gates = gates;
        this.context = context;

        for (MenuBarModel.MenuSpec spec : MenuBarModel.menus()) {
            Menu menu = new Menu(tr(spec.titleKey()));
            for (String entry : spec.entries()) {
                if (MenuBarModel.SEPARATOR.equals(entry)) {
                    menu.getItems().add(new SeparatorMenuItem());
                    continue;
                }
                MenuItem item = new MenuItem();
                item.setOnAction(e -> run.accept(entry));
                items.put(entry, item);
                menu.getItems().add(item);
            }
            bar.getMenus().add(menu);
        }
        // On macOS the menu belongs at the top of the screen, not inside the window. JavaFX moves it there
        // wholesale; the in-window bar then renders as nothing, which is why the chrome toggle still works.
        bar.setUseSystemMenuBar(KeymapManager.isMac());
        refresh();
    }

    MenuBar node() {
        return bar;
    }

    /**
     * Re-labels every item with its current title and keybinding, and enables or disables it for the current
     * state. Called after a keymap switch (chords move) and after any settings apply (features come and go),
     * mirroring how the palette and toolbar tooltips are kept honest.
     */
    void refresh() {
        Map<String, String> chords = chordsByCommand.get();
        Chrome.PaletteGates g = gates.get();
        Chrome.PaletteContext ctx = context.get();
        for (Map.Entry<String, MenuItem> e : items.entrySet()) {
            String id = e.getKey();
            MenuItem item = e.getValue();
            String title = registry.get(id).map(Command::title).orElse(id);
            String chord = chords.get(id);
            // The chord rides in the text because this class sets no accelerators — see the class javadoc.
            item.setText(chord == null || chord.isBlank() ? title : title + "   " + chord);
            // A command whose feature is switched off, or that has nothing to act on, is shown disabled
            // rather than hidden: the point of a menu is to be a stable map, and an entry that vanishes
            // teaches nothing about why.
            item.setDisable(!Chrome.paletteEnabled(id, g, ctx));
        }
    }
}
