package com.editora.ui;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.text.Font;
import javafx.scene.text.Text;

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
 * <p>So the chord is drawn by this class rather than by an accelerator, in one of two ways:
 *
 * <ul>
 *   <li><b>In-window bar</b> (Linux/Windows): each item is a {@link CustomMenuItem} holding a title label, a
 *       growing spacer and a chord label — so every chord in a menu right-aligns into one column, the way a
 *       native accelerator would. This is only possible because the item is ours to lay out.
 *   <li><b>macOS system menu bar</b>: the native menu renders <em>text only</em> — a {@code CustomMenuItem}'s
 *       content node is simply not drawn there, which would blank every item. So on macOS the chord stays
 *       appended to the item's text, as before. Alignment is the platform's to give, and without real
 *       accelerators (see above) it cannot.
 * </ul>
 */
final class MainMenuBar {

    private final MenuBar bar = new MenuBar();
    private final CommandRegistry registry;
    private final Supplier<Map<String, String>> chordsByCommand;
    private final Supplier<Chrome.PaletteGates> gates;
    private final Supplier<Chrome.PaletteContext> context;

    /** Items by command id, so {@link #refresh()} can restyle them without rebuilding the bar. */
    /** An item plus, for the in-window rendering, the two labels {@link #refresh} writes into. */
    private record Row(MenuItem item, Label title, Label chord) {}

    private final Map<String, Row> items = new LinkedHashMap<>();

    /** Rows grouped by the menu they belong to — a chord column is aligned per menu, not app-wide. */
    private final java.util.List<java.util.List<Row>> menuGroups = new java.util.ArrayList<>();

    /**
     * Measures a title so every row in a menu can share one title-column width. A {@link Text} node needs
     * the toolkit but no scene, so this works while the menu is still being built — unlike asking the
     * labels themselves, which report nothing until they are in the popup's scene.
     *
     * <p>Absolute accuracy is not required for alignment: every title in a menu gets the SAME width, so
     * the chords line up whatever that width is. It only has to be no SMALLER than the widest title, or
     * that one title would overflow and push its own chord out of the column — hence the ceiling and the
     * small margin below.
     */
    private static final Text MEASURE = new Text();

    private static double titleColumnWidth(java.util.List<Row> group) {
        double max = 0;
        for (Row r : group) {
            if (r.title() == null) {
                continue;
            }
            MEASURE.setFont(Font.font(UI_FONT, UI_FONT_SIZE));
            MEASURE.setText(r.title().getText());
            max = Math.max(max, MEASURE.getLayoutBounds().getWidth());
        }
        return Math.ceil(max) + 2;
    }

    /** The chrome font, matching styles/ui-font.css and the theme's 14px root size. */
    private static final String UI_FONT = "Inter";

    private static final double UI_FONT_SIZE = 14;

    /** True when the native menu bar owns rendering, so items must carry plain text (see the class javadoc). */
    private final boolean systemMenu = KeymapManager.isMac();

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
            java.util.List<Row> group = new java.util.ArrayList<>();
            for (String entry : spec.entries()) {
                if (MenuBarModel.SEPARATOR.equals(entry)) {
                    menu.getItems().add(new SeparatorMenuItem());
                    continue;
                }
                Row row = newRow(entry);
                row.item().setOnAction(e -> run.accept(entry));
                items.put(entry, row);
                group.add(row);
                menu.getItems().add(row.item());
            }
            bar.getMenus().add(menu);
            menuGroups.add(group);
        }
        // On macOS the menu belongs at the top of the screen, not inside the window. JavaFX moves it there
        // wholesale; the in-window bar then renders as nothing, which is why the chrome toggle still works.
        bar.setUseSystemMenuBar(systemMenu);
        refresh();
    }

    /** Width of the leading icon column. Wide enough for a glyph (~19px drawn) plus a hair of air. */
    private static final double ICON_COLUMN = 22;

    /** A menu row: a laid-out icon/title/chord triple in-window, or plain text on the system menu bar. */
    private Row newRow(String commandId) {
        if (systemMenu) {
            // The system menu bar owns rendering and shows no item graphics — which is also the macOS
            // convention, so there is nothing to add here.
            return new Row(new MenuItem(), null, null);
        }
        Label title = new Label();
        title.getStyleClass().add("menu-item-title");
        Label chord = new Label();
        chord.getStyleClass().add("menu-item-chord");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS); // pushes the chord to the item's right edge
        // Spacing 0 with explicit margins: one gap belongs between the icon and its title (small) and a
        // different one before the chord column (wide), which a single spacing value cannot express.
        HBox box = new HBox(iconColumn(commandId), title, spacer, chord);
        HBox.setMargin(title, new javafx.geometry.Insets(0, 0, 0, 8));
        HBox.setMargin(chord, new javafx.geometry.Insets(0, 0, 0, 18));
        box.setAlignment(Pos.CENTER_LEFT);
        // The popup sizes itself to its widest item and stretches the rest; without this the row would
        // hug its content and every chord would sit at a different x.
        box.setMaxWidth(Double.MAX_VALUE);
        CustomMenuItem item = new CustomMenuItem(box);
        item.setHideOnClick(true);
        return new Row(item, title, chord);
    }

    /**
     * The leading icon column: the command's glyph, or an empty box of the same width.
     *
     * <p>Fixed width either way, which is the point. Around half the entries have a glyph, and JavaFX does
     * not reserve a graphic gutter for the ones that do not — so without this the titles of icon-less items
     * would start further left than their neighbours and each menu would read as two ragged columns.
     */
    private static javafx.scene.layout.StackPane iconColumn(String commandId) {
        javafx.scene.layout.StackPane holder = new javafx.scene.layout.StackPane();
        holder.setMinWidth(ICON_COLUMN);
        holder.setPrefWidth(ICON_COLUMN);
        holder.setMaxWidth(ICON_COLUMN);
        holder.setAlignment(Pos.CENTER);
        holder.getStyleClass().add("menu-item-icon");
        Node glyph = MenuBarIcons.forCommand(commandId);
        if (glyph != null) {
            holder.getChildren().add(glyph);
        }
        return holder;
    }

    /**
     * The visible text of an item under either rendering — the system-menu item's own text, or the
     * title/chord pair the in-window row lays out. Package-visible so tests can assert on one shape.
     */
    static String displayTextOf(MenuItem item) {
        if (!(item instanceof CustomMenuItem custom) || !(custom.getContent() instanceof HBox box)) {
            return item.getText() == null ? "" : item.getText();
        }
        StringBuilder sb = new StringBuilder();
        for (var node : box.getChildren()) {
            if (node instanceof Label l && l.getText() != null && !l.getText().isBlank()) {
                sb.append(sb.isEmpty() ? "" : "   ").append(l.getText());
            }
        }
        return sb.toString();
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
        for (Map.Entry<String, Row> e : items.entrySet()) {
            String id = e.getKey();
            Row row = e.getValue();
            MenuItem item = row.item();
            String title = registry.get(id).map(Command::title).orElse(id);
            String chord = chords.get(id);
            boolean bound = chord != null && !chord.isBlank();
            // No accelerators (see the class javadoc), so the chord is written by hand — into its own
            // right-aligned label in-window, or appended to the text on the system menu bar.
            if (row.title() != null) {
                row.title().setText(title);
                row.chord().setText(bound ? chord : "");
            } else {
                item.setText(bound ? title + "   " + chord : title);
            }
            // A command whose feature is switched off, or that has nothing to act on, is shown disabled
            // rather than hidden: the point of a menu is to be a stable map, and an entry that vanishes
            // teaches nothing about why.
            item.setDisable(!Chrome.paletteEnabled(id, g, ctx));
        }
        alignChordColumns();
    }

    /**
     * Gives every title in a menu one width, so that menu's chords start at the same column — the
     * alignment a native accelerator would provide. Re-run from {@link #refresh} because a keymap switch
     * or a locale change moves the widest title.
     *
     * <p>Sizing the title (rather than stretching the row) is deliberate: a {@link CustomMenuItem}'s
     * content is laid out at its preferred width and is NOT stretched to the popup's width, so a
     * growing spacer alone leaves every row hugging its own content — which is exactly what it did.
     */
    private void alignChordColumns() {
        for (java.util.List<Row> group : menuGroups) {
            double w = titleColumnWidth(group);
            for (Row r : group) {
                if (r.title() != null) {
                    r.title().setMinWidth(w);
                    r.title().setPrefWidth(w);
                }
            }
        }
    }
}
