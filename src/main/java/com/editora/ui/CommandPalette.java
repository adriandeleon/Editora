package com.editora.ui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import com.editora.command.Command;
import com.editora.command.CommandRegistry;
import com.editora.command.KeymapManager;

import static com.editora.i18n.Messages.tr;

/**
 * A fuzzy-filtered command palette (bound to {@code M-x}). Shown as an <em>in-scene</em> overlay in the
 * main window's scene-root {@link StackPane} — <strong>not</strong> a {@link javafx.stage.Popup}. A Popup
 * is a separate native window, and on Windows it doesn't reliably take OS keyboard focus: {@code
 * input.requestFocus()} then orphans keyboard focus between the popup's scene and the main window, so the
 * whole app stops receiving keystrokes (mouse still works) until restart. Living in the main scene keeps
 * focus on the one window, which works on every platform (the find bar does the same).
 */
public class CommandPalette {

    private static final boolean IS_MAC =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");

    /**
     * Base URL for per-command documentation on the website; the command id is appended. The docs are
     * versioned per app release ({@code /docs/v-<appVersion>/commands/<command-id>}), so the running
     * version's docs are opened.
     */
    private static final String DOCS_BASE =
            com.editora.AppInfo.HOMEPAGE + "/docs/v-" + com.editora.AppInfo.VERSION + "/commands/";

    private final CommandRegistry registry;
    private final KeymapManager keymap;
    /** Injected: opens a URL in the system default browser (the highlighted command's docs, on C-h). */
    private java.util.function.Consumer<String> docsOpener = url -> {};

    private Map<String, String> commandToKey;
    /** A command matching this predicate is <em>enabled</em> (actionable); one that fails it is still listed
     *  but rendered grayed out and skipped by the selection cursor (e.g. a Git command while Git is off) —
     *  so the user sees the command exists and its keybinding rather than it silently missing (#532). */
    private final java.util.function.Predicate<Command> enabled;

    private final TextField input = new TextField();
    private final ListView<Command> list = new ListView<>();
    private final ObservableList<Command> items = FXCollections.observableArrayList();
    /** One-line description of the highlighted command, shown above the navigation hint. */
    private final Label desc = new Label();

    /** The palette card (header + input + list + hint); shown via the shared {@link OverlayHost}. */
    private VBox content;
    /** Shared in-scene overlay host (injected); shows the card centered with a dim backdrop. */
    private OverlayHost overlayHost;
    /** Shown state for the toolbar button + MainController; flipped by show()/the host's onHidden hook. */
    private final BooleanProperty showing = new SimpleBooleanProperty(false);

    public CommandPalette(CommandRegistry registry, KeymapManager keymap) {
        this(registry, keymap, c -> true);
    }

    public CommandPalette(
            CommandRegistry registry, KeymapManager keymap, java.util.function.Predicate<Command> enabled) {
        this.registry = registry;
        this.keymap = keymap;
        this.commandToKey = invert(keymap.bindings());
        this.enabled = enabled;
        build();
    }

    private boolean isEnabled(Command c) {
        return enabled.test(c);
    }

    /** Rebuilds the chord hints from the current keymap (after a live keymap switch). */
    public void refreshBindings() {
        this.commandToKey = invert(keymap.bindings());
        list.refresh();
    }

    private static Map<String, String> invert(Map<String, String> bindings) {
        Map<String, String> byCommand = new LinkedHashMap<>();
        bindings.forEach((sequence, id) -> byCommand.putIfAbsent(id, sequence));
        return byCommand;
    }

    private void build() {
        input.setPromptText(tr("palette.prompt"));
        list.setItems(items);
        list.setPrefHeight(280);
        list.setCellFactory(v -> new CommandCell());

        input.textProperty().addListener((obs, old, now) -> filter(now));
        input.addEventFilter(KeyEvent.KEY_PRESSED, this::onKey);
        // Emacs caret movement + basic editing in the query field. Registered after onKey so the palette's own
        // list navigation / C-h docs (C-n/C-p/C-g/C-h) consume those chords first and the keymap yields to it.
        com.editora.command.TextInputKeymap.install(input, keymap);
        // The opening chord (e.g. M-x) is Alt/Meta+key; on macOS that combination also emits a
        // KEY_TYPED for a special character (Option+x => "≈") that would land in the just-focused
        // field. Swallow any character typed while a chord modifier is held; plain query typing
        // (no modifier, or only Shift) passes through. macOS only — elsewhere chord modifiers don't
        // emit query characters, and gating this avoids eating AltGr-composed characters on
        // European layouts (AltGr reports as Ctrl+Alt).
        if (IS_MAC) {
            input.addEventFilter(KeyEvent.KEY_TYPED, e -> {
                if (e.isAltDown() || e.isMetaDown() || e.isControlDown() || e.isShortcutDown()) {
                    e.consume();
                }
            });
        }

        Label header = new Label(tr("palette.header"));
        header.getStyleClass().add("palette-title");
        // Description of the highlighted command, between the list and the navigation hint. Single line
        // with a fixed height (so the card doesn't jitter as descriptions vary in length) and ellipsis.
        desc.getStyleClass().add("palette-desc");
        desc.setMaxWidth(Double.MAX_VALUE);
        desc.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);
        list.getSelectionModel().selectedItemProperty().addListener((obs, old, sel) -> {
            String d = sel == null ? "" : sel.description();
            desc.setText(d.isEmpty() ? " " : d); // keep one line tall so the card never collapses/jitters
        });
        Label hint = new Label(tr("palette.hint"));
        hint.getStyleClass().add("palette-hint");
        content = new VBox(6, header, input, list, desc, hint);
        content.getStyleClass().add("command-palette");
        content.setPrefWidth(620);
        content.setMaxSize(620, Region.USE_PREF_SIZE); // hug its content; don't stretch to fill the overlay
        // Editor-context chords (C-n/C-p/arrows) are left to the palette's own handler while it's open.
        content.getProperties().put("editora.ownsKeys", Boolean.TRUE);
        // (No MOUSE_CLICKED consume on the card: the backdrop dismisses on MOUSE_PRESSED targeted at
        // itself, so a click inside the card never reaches it — and consuming MOUSE_CLICKED here would
        // swallow the result cells' own click-to-run handler.)
    }

    /** Injects the shared overlay host used to show the palette card. */
    public void setOverlayHost(OverlayHost overlayHost) {
        this.overlayHost = overlayHost;
    }

    /** Injects the system-browser opener used by C-h to show the highlighted command's online docs. */
    public void setDocsOpener(java.util.function.Consumer<String> docsOpener) {
        this.docsOpener = docsOpener == null ? url -> {} : docsOpener;
    }

    private void onKey(KeyEvent e) {
        switch (e.getCode()) {
            case ESCAPE -> {
                hide();
                e.consume();
            }
            case ENTER -> {
                runSelected();
                e.consume();
            }
            case DOWN -> {
                move(1);
                e.consume();
            }
            case UP -> {
                move(-1);
                e.consume();
            }
            case N -> {
                if (e.isControlDown()) {
                    move(1);
                    e.consume();
                }
            }
            case P -> {
                if (e.isControlDown()) {
                    move(-1);
                    e.consume();
                }
            }
            case G -> {
                if (e.isControlDown()) {
                    hide();
                    e.consume();
                }
            }
            case H -> {
                if (e.isControlDown()) {
                    openDocs();
                    e.consume();
                }
            }
            default -> {}
        }
    }

    /** C-h: open the highlighted command's online documentation in the system default browser. */
    private void openDocs() {
        Command command = list.getSelectionModel().getSelectedItem();
        if (command == null) {
            return;
        }
        hide();
        docsOpener.accept(DOCS_BASE + command.id());
    }

    private void move(int delta) {
        int size = items.size();
        if (size == 0) {
            return;
        }
        int cur = list.getSelectionModel().getSelectedIndex();
        if (cur < 0) {
            cur = 0;
        }
        // Step in `delta`'s direction (wrapping) to the next ENABLED command, skipping grayed-out ones.
        for (int step = 1; step <= size; step++) {
            int idx = Math.floorMod(cur + delta * step, size);
            if (isEnabled(items.get(idx))) {
                list.getSelectionModel().select(idx);
                list.scrollTo(idx);
                return;
            }
        }
        // No enabled command anywhere — leave the selection as-is.
    }

    private void runSelected() {
        Command command = list.getSelectionModel().getSelectedItem();
        if (command != null && isEnabled(command)) { // a grayed-out (disabled) command is not actionable (#532)
            hide();
            registry.run(command.id());
        }
    }

    private void filter(String query) {
        String q = query.toLowerCase(Locale.ROOT).trim();
        List<Command> matches = new ArrayList<>();
        for (Command command : registry.all()) {
            // A command that fails the enabled predicate (a disabled feature) is still listed — grayed and
            // non-actionable — rather than hidden (#532).
            if (q.isEmpty() || isSubsequence(q, command.title().toLowerCase(Locale.ROOT))) {
                matches.add(command);
            }
        }
        if (!q.isEmpty()) {
            // Rank by relevance (exact > whole-word > word-start > substring > scattered subsequence) so the
            // best match leads — e.g. "undo" puts "Edit: Undo" above "Unsplit Editor" (a scattered match).
            matches.sort(Comparator.comparing(Command::title, byRelevance(q)));
        }
        items.setAll(matches);
        selectFirstEnabled();
    }

    /** Selects the first <em>enabled</em> result (the cursor never rests on a grayed-out command). */
    private void selectFirstEnabled() {
        for (int i = 0; i < items.size(); i++) {
            if (isEnabled(items.get(i))) {
                list.getSelectionModel().select(i);
                list.scrollTo(i);
                return;
            }
        }
        list.getSelectionModel().clearSelection(); // all matches are disabled
    }

    /** True if every char of {@code needle} appears in {@code haystack} in order (fuzzy match). */
    /**
     * Comparator that orders titles by how well they match {@code query} (best first), tie-broken by
     * shorter title then case-insensitive alphabetical. Used to rank command-palette results so the most
     * relevant command leads.
     */
    static Comparator<String> byRelevance(String query) {
        String q = query.toLowerCase(Locale.ROOT).trim();
        return Comparator.comparingInt((String t) -> -relevance(q, t))
                .thenComparingInt(String::length)
                .thenComparing(t -> t, String.CASE_INSENSITIVE_ORDER);
    }

    /**
     * Relevance of {@code title} to the (already lowercased) {@code q}: exact (5) &gt; whole-word (4) &gt;
     * word-start (3) &gt; substring anywhere (2) &gt; scattered subsequence / no substring (1).
     */
    static int relevance(String q, String title) {
        String t = title.toLowerCase(Locale.ROOT);
        if (t.equals(q)) {
            return 5;
        }
        int idx = t.indexOf(q);
        if (idx >= 0) {
            boolean wordStart = idx == 0 || !Character.isLetterOrDigit(t.charAt(idx - 1));
            int after = idx + q.length();
            boolean wordEnd = after >= t.length() || !Character.isLetterOrDigit(t.charAt(after));
            if (wordStart && wordEnd) {
                return 4;
            }
            return wordStart ? 3 : 2;
        }
        return 1; // matched only as a scattered subsequence (the filter already guaranteed that)
    }

    static boolean isSubsequence(String needle, String haystack) {
        int i = 0;
        for (int j = 0; i < needle.length() && j < haystack.length(); j++) {
            if (needle.charAt(i) == haystack.charAt(j)) {
                i++;
            }
        }
        return i == needle.length();
    }

    public void show() {
        if (overlayHost == null) {
            return; // setOverlayHost() not called yet
        }
        input.clear();
        filter("");
        showing.set(true);
        overlayHost.show(content, input::requestFocus, () -> showing.set(false));
    }

    public void hide() {
        if (overlayHost != null) {
            overlayHost.hide(); // the host's onHidden hook clears `showing`
        }
    }

    public boolean isShown() {
        return showing.get();
    }

    public javafx.beans.value.ObservableValue<Boolean> showingProperty() {
        return showing;
    }

    private final class CommandCell extends ListCell<Command> {
        private final Label title = new Label();
        private final Label key = new Label();
        private final HBox box = new HBox(10, title, spacer(), key);

        CommandCell() {
            box.setAlignment(Pos.CENTER_LEFT);
            key.getStyleClass().add("keybinding");
            // Click an ENABLED command to run it (the keyboard runs the selected item on Enter). A grayed-out
            // (disabled-feature) command is inert — clicking it does nothing (#532).
            setOnMouseClicked(e -> {
                if (e.getButton() == MouseButton.PRIMARY && !isEmpty() && getItem() != null && isEnabled(getItem())) {
                    getListView().getSelectionModel().select(getItem());
                    runSelected();
                }
            });
        }

        private Region spacer() {
            Region r = new Region();
            HBox.setHgrow(r, Priority.ALWAYS);
            return r;
        }

        @Override
        protected void updateItem(Command item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                return;
            }
            title.setText(item.title());
            key.setText(commandToKey.getOrDefault(item.id(), ""));
            box.getStyleClass().remove("palette-disabled");
            if (!isEnabled(item)) {
                box.getStyleClass().add("palette-disabled"); // grayed + non-actionable (#532)
            }
            setGraphic(box);
        }
    }
}
