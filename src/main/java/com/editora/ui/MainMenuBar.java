package com.editora.ui;

import java.util.LinkedHashMap;
import java.util.List;
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
import com.editora.editor.UiZoom;
import org.jspecify.annotations.Nullable;

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

    /** An item plus, for the in-window rendering, the two labels {@link #refresh} writes into. */
    private record Row(
            MenuItem item, @Nullable Label title, @Nullable Label chord) {}

    /** Items by command id, so {@link #refresh()} can restyle them without rebuilding the bar. */
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
            MEASURE.setFont(Font.font(UI_FONT, UiZoom.px(UI_FONT_SIZE)));
            MEASURE.setText(r.title().getText());
            max = Math.max(max, MEASURE.getLayoutBounds().getWidth());
        }
        return Math.ceil(max) + UiZoom.px(2);
    }

    /** The chrome font, matching styles/ui-font.css and the theme's 14px root size. */
    private static final String UI_FONT = "Inter";

    private static final double UI_FONT_SIZE = 14;

    /** The chord label's size, matching `.menu-item-chord` in app.css. */
    private static final double CHORD_FONT_SIZE = 11;

    /** True when the native menu bar owns rendering, so items must carry plain text (see the class javadoc). */
    private final boolean systemMenu = KeymapManager.isMac();

    private final Consumer<String> run;

    /** Which table is currently built — the full menu, or Simple UI mode's reduced one. */
    private boolean simple;

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
        this.run = run;

        build();
        // On macOS the menu belongs at the top of the screen, not inside the window. JavaFX moves it there
        // wholesale; the in-window bar then renders as nothing, which is why the chrome toggle still works.
        bar.setUseSystemMenuBar(systemMenu);
        refresh();
        UiZoom.follow(bar, this::alignChordColumns); // the title column is measured at the zoomed font
    }

    /** (Re)builds every menu from the table for the current mode. */
    private void build() {
        bar.getMenus().clear();
        items.clear();
        menuGroups.clear();
        for (MenuBarModel.MenuSpec spec : MenuBarModel.menus(simple)) {
            Menu menu = menuOf(spec);
            // Recompute enablement as the menu opens, so it can never show a stale answer. The context
            // half of the gate (a repo, an open buffer, a suspended debug session) moves constantly and
            // asynchronously — Git detection alone lands well after the window is built — while refresh()
            // runs only on a settings/keymap apply. On the macOS system menu bar AppKit owns the popup and
            // this may not fire, which is why the state-change sites push refreshEnablement() as well.
            menu.setOnShowing(e -> refreshEnablement());
            bar.getMenus().add(menu);
        }
    }

    /**
     * One menu from its spec: its entries, then — below a separator — its submenus, each built the same
     * way. A submenu is a plain {@link Menu} inside its parent, which the macOS system menu bar renders
     * natively like any other; its rows are their own group for the chord column.
     */
    private Menu menuOf(MenuBarModel.MenuSpec spec) {
        Menu menu = new Menu(tr(spec.titleKey()));
        java.util.List<Row> group = new java.util.ArrayList<>();
        java.util.Set<String> inline = new java.util.HashSet<>();
        for (String entry : spec.entries()) {
            if (MenuBarModel.SEPARATOR.equals(entry)) {
                menu.getItems().add(new SeparatorMenuItem());
                continue;
            }
            String inlineKey = MenuBarModel.inlineSubmenu(entry);
            if (inlineKey != null) {
                for (MenuBarModel.MenuSpec submenu : spec.submenus()) {
                    if (submenu.titleKey().equals(inlineKey)) {
                        menu.getItems().add(nestedMenu(submenu, group));
                        inline.add(inlineKey);
                    }
                }
                continue;
            }
            Row row = newRow(entry);
            row.item().setOnAction(e -> choose(entry));
            items.put(entry, row);
            group.add(row);
            menu.getItems().add(row.item());
        }
        menuGroups.add(group);
        List<MenuBarModel.MenuSpec> below = spec.submenus().stream()
                .filter(submenu -> !inline.contains(submenu.titleKey()))
                .toList();
        if (!below.isEmpty()) {
            menu.getItems().add(new SeparatorMenuItem());
            for (MenuBarModel.MenuSpec submenu : below) {
                menu.getItems().add(nestedMenu(submenu, group));
            }
        }
        return menu;
    }

    /**
     * A submenu inside its parent. In-window it joins the parent's chord-column group with the same
     * icon/title/chord layout as a command row, its title drawn in the graphic and its chord an invisible copy
     * of the group's widest one (see {@link #alignChordColumns}).
     *
     * <p>That is what puts the arrow at the right edge. JavaFX places a submenu's arrow right after the widest
     * <em>label</em> among the plain items, but every command row is a {@link CustomMenuItem} whose content
     * takes no part in that column — so with the title in the label, the arrow sat just past the longest
     * submenu name, halfway across a menu the chords had made wide. Laid out like a command row, the submenu's
     * graphic is exactly as wide as the widest row and the arrow column follows it. The Menu keeps its text
     * (accessibility, and the system menu bar); app.css collapses the label that would draw it twice.
     */
    private Menu nestedMenu(MenuBarModel.MenuSpec submenu, java.util.List<Row> group) {
        Menu nested = menuOf(submenu);
        if (!systemMenu) {
            Label title = new Label(nested.getText());
            title.getStyleClass().add("menu-item-title");
            Label chord = new Label();
            chord.getStyleClass().add("menu-item-chord");
            chord.setVisible(false); // holds the chord column's width; never shows
            nested.setGraphic(rowContent(iconColumn(null), title, chord));
            nested.getStyleClass().add("menu-submenu");
            group.add(new Row(nested, title, chord));
        }
        return nested;
    }

    /**
     * Switches between the full and the Simple UI mode menu. Rebuilds only when the mode actually changed —
     * this is called from every chrome apply, and re-creating ~130 menu items each time would be pure waste.
     */
    void setSimple(boolean simple) {
        if (simple == this.simple) {
            return;
        }
        this.simple = simple;
        build();
        refresh();
    }

    /** Width of the leading icon column. Wide enough for a glyph (~19px drawn) plus a hair of air. */
    private static final double ICON_COLUMN = 22;

    /** A menu row: a laid-out icon/title/chord triple in-window, or plain text on the system menu bar. */
    private Row newRow(String commandId) {
        if (systemMenu) {
            // The system menu bar owns rendering and shows no item graphics — which is also the macOS
            // convention, so there is nothing to add here.
            return new Row(danger(new MenuItem(), commandId), null, null);
        }
        Label title = new Label();
        title.getStyleClass().add("menu-item-title");
        Label chord = new Label();
        chord.getStyleClass().add("menu-item-chord");
        CustomMenuItem item = new CustomMenuItem(rowContent(iconColumn(commandId), title, chord));
        item.setHideOnClick(true);
        return new Row(danger(item, commandId), title, chord);
    }

    /**
     * Marks a destructive command's row ({@link MenuBarModel#isDestructive}) with the {@code danger} style
     * class, which draws its title in the danger colour (app.css). Here, where every row is built, so no menu
     * has to remember it. The system menu bar draws its own rows and ignores the class.
     */
    private static <T extends MenuItem> T danger(T item, String commandId) {
        if (MenuBarModel.isDestructive(commandId)) {
            item.getStyleClass().add("danger");
        }
        return item;
    }

    /** The in-window row layout shared by command rows and submenus: icon, title, spacer, chord. */
    private static HBox rowContent(Node icon, Label title, Label chord) {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS); // pushes the chord to the item's right edge
        // Spacing 0 with explicit margins: one gap belongs between the icon and its title (small) and a
        // different one before the chord column (wide), which a single spacing value cannot express.
        HBox box = new HBox(icon, title, spacer, chord);
        UiZoom.follow(title, () -> HBox.setMargin(title, UiZoom.insets(0, 0, 0, 8)));
        UiZoom.follow(chord, () -> HBox.setMargin(chord, UiZoom.insets(0, 0, 0, 18)));
        box.setAlignment(Pos.CENTER_LEFT);
        // The popup sizes itself to its widest item and stretches the rest; without this the row would
        // hug its content and every chord would sit at a different x.
        box.setMaxWidth(Double.MAX_VALUE);
        // A row drawn from nodes has no text of its own: a screen reader reads a CustomMenuItem from its
        // content's accessible text, so the row says its title and its chord, and follows both. A row whose
        // command cannot run says why there too (showAvailability puts the reason in the accessible help):
        // JavaFX reads a menu row's text from its content, but not the content's help.
        box.accessibleTextProperty()
                .bind(javafx.beans.binding.Bindings.createStringBinding(
                        () -> accessibleRowText(title.getText(), chord.getText(), box.getAccessibleHelp()),
                        title.textProperty(),
                        chord.textProperty(),
                        box.accessibleHelpProperty()));
        return box;
    }

    /** What a screen reader says for a row: its title, then its chord when it has one. Pure. */
    static String accessibleRowText(@Nullable String title, @Nullable String chord) {
        String name = title == null ? "" : title;
        return chord == null || chord.isBlank() ? name : name + ", " + chord;
    }

    /**
     * What a screen reader says for a row that may not run now: its title and chord, then why it cannot, as the
     * Command Palette says it for its grayed rows. Pure.
     */
    static String accessibleRowText(@Nullable String title, @Nullable String chord, @Nullable String reason) {
        String spoken = accessibleRowText(title, chord);
        return reason == null || reason.isBlank() ? spoken : spoken + ". " + reason;
    }

    /**
     * The leading icon column: the command's glyph, or an empty box of the same width.
     *
     * <p>Fixed width either way, which is the point. Around half the entries have a glyph, and JavaFX does
     * not reserve a graphic gutter for the ones that do not — so without this the titles of icon-less items
     * would start further left than their neighbours and each menu would read as two ragged columns.
     */
    private static javafx.scene.layout.StackPane iconColumn(@Nullable String commandId) {
        javafx.scene.layout.StackPane holder = new javafx.scene.layout.StackPane();
        UiZoom.minWidth(holder, ICON_COLUMN);
        UiZoom.prefWidth(holder, ICON_COLUMN);
        UiZoom.maxWidth(holder, ICON_COLUMN);
        holder.setAlignment(Pos.CENTER);
        holder.getStyleClass().add("menu-item-icon");
        Node glyph = commandId == null ? null : MenuBarIcons.forCommand(commandId);
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

    /** Whether the menus are on the system's menu bar (macOS) rather than in the window. */
    boolean onSystemMenuBar() {
        return systemMenu;
    }

    /**
     * Opens the bar's first menu with the keyboard on it, so the arrow keys walk the menus: what F10 does in a
     * JavaFX window, for a keymap that gives F10 to something else. False when there is no bar in the window to
     * open (it is hidden, or it is the system's).
     */
    boolean openFirstMenu() {
        javafx.scene.Scene scene = bar.getScene();
        if (systemMenu || !bar.isVisible() || bar.getMenus().isEmpty()) {
            return false;
        }
        if (scene == null || scene.getWindow() == null || !scene.getWindow().isShowing()) {
            return false; // a menu's popup needs a window on screen to open in
        }
        bar.requestFocus();
        bar.getMenus().get(0).show();
        return true;
    }

    /**
     * Re-labels every item with its current title and keybinding, and enables or disables it for the current
     * state. Called after a keymap switch (chords move) and after any settings apply (features come and go),
     * mirroring how the palette and toolbar tooltips are kept honest.
     */
    void refresh() {
        Map<String, String> chords = chordsByCommand.get();
        menuTitles.clear();
        Map<String, Integer> shortened = new java.util.HashMap<>();
        for (String id : items.keySet()) {
            shortened.merge(
                    com.editora.editor.MenuTitles.of(
                            registry.get(id).map(Command::title).orElse(id)),
                    1,
                    Integer::sum);
        }
        for (String id : items.keySet()) {
            String full = registry.get(id).map(Command::title).orElse(id);
            String bare = com.editora.editor.MenuTitles.of(full);
            // Two rows that would read the same keep their prefixes: "Rerun Last" twice in Run, "Find in Files"
            // in both Find and Window, where the prefix was all that told them apart (fourth review EF1).
            menuTitles.put(id, shortened.getOrDefault(bare, 0) > 1 ? full : bare);
        }
        for (Map.Entry<String, Row> e : items.entrySet()) {
            String id = e.getKey();
            Row row = e.getValue();
            MenuItem item = row.item();
            String title = menuTitles.get(id);
            String chord = DebugPanelText.chordOf(chords::get, id); // Step Into's F11 lives on a wrapper command
            boolean bound = chord != null && !chord.isBlank();
            // No accelerators (see the class javadoc), so the chord is written by hand — into its own
            // right-aligned label in-window, or appended to the text on the system menu bar.
            Label titleLabel = row.title();
            Label chordLabel = row.chord(); // set together with the title
            if (titleLabel != null && chordLabel != null) {
                titleLabel.setText(title);
                chordLabel.setText(bound ? chord : "");
            } else {
                item.setText(bound ? title + "   " + chord : title);
            }
        }
        refreshEnablement();
        alignChordColumns();
    }

    /**
     * Re-evaluates only which items are enabled, for the state as it stands right now.
     *
     * <p>Split out of {@link #refresh()} because the two run on completely different cadences. Titles and
     * chords move only on a keymap switch or a locale change; <em>enablement</em> moves whenever the window
     * does — a tab switch, a repo being detected, a debug session suspending. This pass is a pure map lookup
     * per item with no relabelling and no {@link #alignChordColumns()} re-measure, so it is cheap enough to
     * run on those events and on every menu open.
     *
     * <p>The context half of the gate used to be frozen at window-build time, when Git detection (which is
     * asynchronous and off-thread) had not yet reported a repo: the entire Git menu came up greyed out and
     * stayed that way until some unrelated settings apply happened to call {@link #refresh()}. The status bar
     * beside it showed the branch the whole time.
     *
     * <p>A command whose feature is switched off, or that has nothing to act on, is shown grayed rather
     * than hidden: the point of a menu is to be a stable map, and an entry that vanishes teaches nothing
     * about why. It also says why, with the sentence the Command Palette shows for the same command
     * ({@link #showAvailability}, KBD-04 in Pauta 6.0.1). The sentences are made once per reason per pass:
     * a feature switched off grays a whole menu with one.
     */
    void refreshEnablement() {
        Chrome.PaletteGates g = gates.get();
        Chrome.PaletteContext ctx = context.get();
        Map<Chrome.DisabledReason, String> sentences = new java.util.HashMap<>();
        for (Map.Entry<String, Row> e : items.entrySet()) {
            Chrome.DisabledReason reason = Chrome.disabledReason(e.getKey(), g, ctx);
            showAvailability(
                    e.getValue().item(),
                    reason == null ? null : sentences.computeIfAbsent(reason, MainMenuBar::reasonSentence));
            showCheck(e.getKey(), e.getValue());
        }
    }

    /**
     * The sentence that says why a command cannot run, as the Command Palette, the menu bar and the status bar
     * show it: the reason's message, naming by its localized title the command that would switch a feature back
     * on.
     */
    static String reasonSentence(Chrome.DisabledReason reason) {
        String commandArg = reason.commandArg();
        return commandArg == null ? tr(reason.messageKey()) : tr(reason.messageKey(), tr("command." + commandArg));
    }

    /** Where a row that cannot run says why when it is chosen: the window's status bar. */
    private Consumer<String> sayUnavailable = message -> {};

    /** Sets where a row that cannot run says why when it is chosen (the status bar). */
    void setOnUnavailable(Consumer<String> say) {
        this.sayUnavailable = say;
    }

    /**
     * Runs the command of a chosen row, or, when it cannot run now, says why in the status bar instead. The
     * answer is taken afresh: the state may have moved since the menu opened.
     */
    private void choose(String id) {
        Chrome.DisabledReason reason = Chrome.disabledReason(id, gates.get(), context.get());
        if (reason == null) {
            run.accept(id);
            return;
        }
        String title = registry.get(id).map(Command::title).orElse(id);
        sayUnavailable.accept(tr("status.commandUnavailable", title, reasonSentence(reason)));
    }

    /** The style class of a row whose command cannot run now: grayed, but still a row (app.css). */
    static final String UNAVAILABLE = "unavailable";

    /** The content property that holds the tooltip {@link #showAvailability} installed, so it can be taken off. */
    private static final String REASON_TIP = MainMenuBar.class.getName() + ".reasonTip";

    /**
     * Shows whether a row's command can run now (KBD-04 in Pauta 6.0.1). A row that cannot stays in its menu,
     * grayed, and keeps working as a row: its tooltip says why, a screen reader hears why, and choosing it says
     * why in the status bar ({@link #choose}) instead of running. A disabled row could do none of that: JavaFX
     * gives a disabled node no mouse events, so no tooltip, and the keyboard skips it.
     *
     * <p>On the macOS system menu bar a row is a plain item that AppKit draws, with no node to carry a tooltip,
     * so there it can only be disabled.
     */
    static void showAvailability(MenuItem item, @Nullable String reason) {
        if (!(item instanceof CustomMenuItem custom)) {
            item.setDisable(reason != null);
            return;
        }
        item.setDisable(false);
        Node content = custom.getContent();
        Object shown = content.getProperties().get(REASON_TIP);
        if (shown instanceof javafx.scene.control.Tooltip tip && tip.getText().equals(reason)) {
            return; // the same reason as the last pass: no new tooltip on every menu open
        }
        if (shown instanceof javafx.scene.control.Tooltip tip) {
            javafx.scene.control.Tooltip.uninstall(content, tip);
            content.getProperties().remove(REASON_TIP);
        }
        item.getStyleClass().remove(UNAVAILABLE);
        content.setAccessibleHelp(reason);
        if (reason == null) {
            return;
        }
        item.getStyleClass().add(UNAVAILABLE);
        javafx.scene.control.Tooltip tip = com.editora.editor.ControlNames.tooltip(reason);
        tip.setWrapText(true);
        tip.setMaxWidth(UiZoom.px(380));
        tip.getStyleClass().add("palette-disabled-tooltip"); // the palette's grayed rows explain themselves alike
        javafx.scene.control.Tooltip.install(content, tip);
        content.getProperties().put(REASON_TIP, tip);
    }

    /**
     * Whether a row shows its command as unable to run: grayed with a reason in the window, disabled on the macOS
     * system menu bar. What a test asks of a row, whichever way it is drawn.
     */
    static boolean showsUnavailable(MenuItem item) {
        return item.isDisable() || item.getStyleClass().contains(UNAVAILABLE);
    }

    /** Each row's title as {@link #refresh} chose it: without its category prefix unless another row's would match. */
    private final Map<String, String> menuTitles = new java.util.HashMap<>();

    /** The mark a {@link Command.Toggle} that is on carries in front of its title. */
    static final String CHECK = "✓ ";

    /**
     * Marks an on/off command's item with {@link #CHECK} while it is on (and leaves its place blank while it is
     * off, so the titles stay aligned). Re-read on every menu open, with the enablement.
     */
    private void showCheck(String id, Row row) {
        Command command = registry.get(id).orElse(null);
        if (!(command instanceof Command.Toggle toggle)) {
            return;
        }
        String mark = toggle.isOn() ? CHECK : "\u2002\u2002"; // two en spaces ≈ the mark's width
        if (row.title() != null) {
            row.title().setText(mark + menuTitles.getOrDefault(id, com.editora.editor.MenuTitles.of(command.title())));
        } else {
            String text = row.item().getText();
            String bare = text.startsWith(CHECK) || text.startsWith("\u2002\u2002") ? text.substring(2) : text;
            row.item().setText(mark + bare);
        }
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
            String widestChord = widestChord(group);
            for (Row r : group) {
                if (r.title() != null) {
                    r.title().setMinWidth(w);
                    r.title().setPrefWidth(w);
                }
                if (r.item() instanceof Menu && r.chord() != null) {
                    // A submenu row's invisible chord: as wide as the widest real one, so the row is as
                    // wide as the widest command row and the arrow lands at the menu's right edge.
                    r.chord().setText(widestChord);
                }
            }
        }
    }

    /** The chord in a group that draws widest, measured like {@link #titleColumnWidth} (chords are 11px). */
    private static String widestChord(java.util.List<Row> group) {
        String widest = "";
        double max = 0;
        MEASURE.setFont(Font.font(UI_FONT, CHORD_FONT_SIZE));
        for (Row r : group) {
            if (r.chord() == null || r.item() instanceof Menu) {
                continue;
            }
            MEASURE.setText(r.chord().getText());
            double width = MEASURE.getLayoutBounds().getWidth();
            if (width > max) {
                max = width;
                widest = r.chord().getText();
            }
        }
        return widest;
    }
}
