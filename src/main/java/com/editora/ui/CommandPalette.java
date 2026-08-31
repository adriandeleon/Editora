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
import javafx.scene.control.Tooltip;
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
import com.editora.search.FuzzyMatch;

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
     * version's docs are opened. Uses {@code releaseVersion()}, not the raw version: a snapshot build's
     * {@code -SNAPSHOT} suffix is never a published docs path, so it would always 404 — the release it is
     * working toward at least resolves once that version ships.
     */
    private static final String DOCS_BASE = com.editora.AppInfo.docsUrl() + "/commands/";

    private final CommandRegistry registry;
    private final KeymapManager keymap;
    /** Injected: opens a URL in the system default browser (the highlighted command's docs, on C-h). */
    private java.util.function.Consumer<String> docsOpener = url -> {};

    private Map<String, String> commandToKey;
    /**
     * Builds the enabled-predicate for one pass over the command list. A command matching the predicate is
     * <em>enabled</em> (actionable); one that fails it is still listed but rendered grayed out and skipped
     * by the selection cursor (e.g. a Git command while Git is off, or a Markdown command in a Java file) —
     * so the user sees the command exists and its keybinding rather than it silently missing (#532).
     *
     * <p>A <b>supplier</b>, not a bare predicate, so the caller can snapshot expensive live state (feature
     * gates, active-buffer type, repo root) <em>once per refresh</em> instead of once per command: this runs
     * over the whole registry — several hundred commands — on every keystroke in the query field, so a
     * per-command snapshot re-derives the same answer hundreds of times per keypress. See
     * {@link #enabledSnapshot}.
     */
    private final java.util.function.Supplier<java.util.function.Predicate<Command>> enabledPolicy;

    /** The query of the current filter pass, read by the cells to highlight the matched characters. */
    private String currentQuery = "";

    /** The current pass's predicate, refreshed by {@link #filter} and read by the cells while they render. */
    private java.util.function.Predicate<Command> enabledSnapshot = c -> true;

    /**
     * Explains why a grayed-out command can't run ("Git is turned off — run …"), shown as the row's tooltip.
     * Returns null/blank when there is nothing useful to say, in which case no tooltip is installed.
     * Injected rather than derived here so {@link CommandPalette} stays free of the feature-gate model.
     */
    private java.util.function.Function<Command, String> disabledReason = c -> null;

    private final TextField input = new TextField();
    /** The input row's chord chip (see {@link #paletteChord}). */
    private final Label prefixChip = new Label();

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

    /** Constant policy — the predicate never changes between passes (used by tests and the no-gate ctor). */
    public CommandPalette(
            CommandRegistry registry, KeymapManager keymap, java.util.function.Predicate<Command> enabled) {
        this(registry, keymap, () -> enabled);
    }

    public CommandPalette(
            CommandRegistry registry,
            KeymapManager keymap,
            java.util.function.Supplier<java.util.function.Predicate<Command>> enabledPolicy) {
        this.registry = registry;
        this.keymap = keymap;
        this.commandToKey = invert(keymap.bindings());
        this.enabledPolicy = enabledPolicy;
        build();
    }

    private boolean isEnabled(Command c) {
        return enabledSnapshot.test(c);
    }

    /** Rebuilds the chord hints from the current keymap (after a live keymap switch). */
    public void refreshBindings() {
        this.commandToKey = invert(keymap.bindings());
        prefixChip.setText(paletteChord());
        list.refresh();
    }

    /** The chord that opens the palette (the kit's "M-x" prefix chip); keymap-accurate, not hardcoded. */
    private String paletteChord() {
        return commandToKey.getOrDefault("palette.show", "M-x");
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

        // Kit shape: no title header — the first row IS the input, led by a small chip naming the
        // chord that opened the palette (so the palette introduces itself by its keyboard identity).
        prefixChip.setText(paletteChord());
        prefixChip.getStyleClass().add("palette-prefix");
        input.getStyleClass().add("palette-input");
        javafx.scene.layout.HBox inputRow = new javafx.scene.layout.HBox(9, prefixChip, input);
        inputRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        inputRow.getStyleClass().add("palette-input-row");
        javafx.scene.layout.HBox.setHgrow(input, javafx.scene.layout.Priority.ALWAYS);
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
        content = new VBox(6, inputRow, list, desc, hint);
        content.getStyleClass().add("command-palette");
        content.setPrefWidth(620);
        content.setMaxSize(620, Region.USE_PREF_SIZE); // hug its content; don't stretch to fill the overlay
        // Editor-context chords (C-n/C-p/arrows) are left to the palette's own handler while it's open.
        content.getProperties().put("editora.ownsKeys", Boolean.TRUE);
        // (No MOUSE_CLICKED consume on the card: the backdrop dismisses on MOUSE_PRESSED targeted at
        // itself, so a click inside the card never reaches it — and consuming MOUSE_CLICKED here would
        // swallow the result cells' own click-to-run handler.)
    }

    /**
     * Injects the explainer for grayed-out rows (see {@link #disabledReason}). Only consulted for a command
     * that already failed the enabled predicate, so it costs nothing on the common path.
     */
    public void setDisabledReason(java.util.function.Function<Command, String> disabledReason) {
        this.disabledReason = disabledReason == null ? c -> null : disabledReason;
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
        docsOpener.accept(docsUrl(command.id()));
    }

    /**
     * The online-docs URL for a command. Shared with Search Everywhere so the two pickers can never point
     * at different pages — the version in the path is the subtlety worth having in one place.
     */
    static String docsUrl(String commandId) {
        return DOCS_BASE + commandId;
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
        // One snapshot of the live feature/context state for this whole pass — see enabledPolicy.
        enabledSnapshot = enabledPolicy.get();
        currentQuery = query == null ? "" : query.trim();
        items.setAll(orderedMatches(registry.all(), currentQuery));
        selectFirstEnabled();
    }

    /**
     * The canonical command ordering shared by the Command Palette and Search Everywhere. An empty query
     * preserves registry order; a real query uses fuzzy score, then shorter and alphabetical titles.
     */
    static List<Command> orderedMatches(java.util.Collection<Command> commands, String query) {
        String q = query == null ? "" : query.trim();
        if (q.isEmpty()) {
            return new ArrayList<>(commands);
        }
        // Score every command and order by that score, so the best match leads rather than merely some
        // match. Ties fall back to the shorter then alphabetical title, which keeps the order stable
        // across keystrokes instead of letting equal-scoring rows shuffle under the cursor.
        record Scored(Command command, int score) {}
        List<Scored> scored = new ArrayList<>();
        for (Command command : commands) {
            FuzzyMatch.Match m = FuzzyMatch.of(command.title(), q);
            if (m != null) {
                scored.add(new Scored(command, m.score()));
            }
        }
        scored.sort(Comparator.comparingInt(Scored::score)
                .reversed()
                .thenComparingInt((Scored s) -> s.command().title().length())
                .thenComparing(s -> s.command().title(), String.CASE_INSENSITIVE_ORDER));
        List<Command> matches = new ArrayList<>(scored.size());
        for (Scored s : scored) {
            matches.add(s.command());
        }
        return matches;
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

    /**
     * The title as styled runs: the characters the query matched wear {@code palette-match} (accent +
     * bold — the kit's {@code <mark>}), the rest {@code palette-cell-text}. Reuses the completion popup's
     * {@code MatchHighlighter} — same substring-else-subsequence semantics as {@link #filter}'s matcher,
     * and it indexes the label directly (case folded per char) so highlights can't drift on a
     * length-changing lowercase mapping.
     */
    /**
     * The emboldened runs for a row. These are {@link FuzzyMatch}'s <em>own</em> ranges — the same call
     * that scored the row — so the highlight can never point at different characters than the ranking
     * reasoned about, which it could when the two came from separate matchers.
     */
    private List<javafx.scene.text.Text> buildTitle(String text) {
        return MatchText.runs(text, currentQuery);
    }

    private final class CommandCell extends ListCell<Command> {
        private final javafx.scene.text.TextFlow title = new javafx.scene.text.TextFlow();
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
            title.getChildren().setAll(buildTitle(item.title()));
            key.setText(commandToKey.getOrDefault(item.id(), ""));
            box.getStyleClass().remove("palette-disabled");
            setTooltip(null); // cells are recycled — never leave a previous row's explanation behind
            if (!isEnabled(item)) {
                box.getStyleClass().add("palette-disabled"); // grayed + non-actionable (#532)
                // Say why it's grayed, and which command would fix it — a gray row with no explanation
                // reads as a bug rather than a state.
                String why = disabledReason.apply(item);
                if (why != null && !why.isBlank()) {
                    Tooltip tip = new Tooltip(why);
                    tip.setWrapText(true);
                    tip.setMaxWidth(380);
                    tip.getStyleClass().add("palette-disabled-tooltip");
                    setTooltip(tip);
                }
            }
            setGraphic(box);
        }
    }
}
