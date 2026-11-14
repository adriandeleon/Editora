package com.editora.ui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleSupplier;

import javafx.animation.PauseTransition;
import javafx.geometry.Pos;
import javafx.scene.AccessibleAttribute;
import javafx.scene.AccessibleRole;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import com.editora.command.Command;
import com.editora.command.CommandRegistry;
import com.editora.command.KeymapContinuations;
import com.editora.command.KeymapManager;
import com.editora.editor.MenuTitles;
import com.editora.editor.UiZoom;

import static com.editora.i18n.Messages.tr;

/**
 * The which-key style hint (KBD-14 in Pauta 6.0.1): while a multi-key prefix such as {@code C-x} waits for its
 * next key, a card at the bottom of the window lists every key that can follow, each with the title of the
 * command it runs, and for a key that leads further ({@code C-x v}) how many commands lie behind it.
 *
 * <ul>
 *   <li><b>When.</b> {@link #DELAY} after the prefix was typed, so a sequence typed at speed ({@code C-x C-s})
 *       never flashes it; at once when a shown prefix grows ({@code C-x} then {@code v}). It goes the moment the
 *       prefix ends, because the sequence ran, was undefined or was cancelled: the {@code KeyDispatcher} says so
 *       through its prefix listener. A prefix does not time out in the dispatcher, so neither does the hint.
 *   <li><b>Never in the way of the keyboard.</b> A node of the window's scene-root stack, not an
 *       {@link OverlayHost} card and not a separate window: it takes no focus and no mouse, so the next key goes
 *       where it would have gone without it, to the dispatcher's scene filter.
 *   <li><b>What it lists.</b> The live keymap's bindings, the user's own included, read each time it is shown,
 *       so it follows a keymap switch; titles from the command registry; keys written as the keymap writes them
 *       everywhere else ({@link KeymapManager#display}).
 *   <li><b>A long list</b> flows into columns as wide as the window allows, top to bottom then left to right, no
 *       taller than {@link #MAX_HEIGHT_SHARE} of the window. What does not fit ends in a "…and N more" cell.
 *   <li><b>A screen reader</b> is given the whole list as the hint's accessible text.
 * </ul>
 *
 * <p>Per window, and it holds nothing static: it lives and goes with its window.
 */
final class PrefixKeyHint {

    /** How long a prefix waits before the hint shows. */
    static final Duration DELAY = Duration.millis(600);

    /** The share of the window's height the hint may take before it cuts its list short. */
    static final double MAX_HEIGHT_SHARE = 0.45;

    private final CommandRegistry registry;
    private final KeymapManager keymap;
    private final DoubleSupplier bottomInset;

    private final Label header = new Label();
    private final GridPane grid = new GridPane();
    private final VBox card = new VBox(header, grid);
    private final PauseTransition wait = new PauseTransition(DELAY);

    /** The prefix pending now, {@code ""} when none. */
    private String prefix = "";

    /** Every cell of the list being shown, the ones that did not fit included, for a relayout. */
    private List<Node> all = List.of();

    /**
     * @param bottomInset the height at the bottom of the window the hint keeps clear of (the status bar)
     */
    PrefixKeyHint(CommandRegistry registry, KeymapManager keymap, DoubleSupplier bottomInset) {
        this.registry = registry;
        this.keymap = keymap;
        this.bottomInset = bottomInset;
        card.getStyleClass().add("prefix-key-hint");
        header.getStyleClass().add("prefix-key-hint-prefix");
        UiZoom.padding(card, 8, 14, 10, 14);
        UiZoom.spacing(card, 6);
        UiZoom.gaps(grid, 22, 2);
        card.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        card.setMouseTransparent(true); // never catches a click meant for the editor under it
        card.setFocusTraversable(false);
        card.setAccessibleRole(AccessibleRole.TEXT);
        card.setVisible(false);
        card.setManaged(false); // until shown it takes no part in the stack's layout
        StackPane.setAlignment(card, Pos.BOTTOM_CENTER);
        wait.setOnFinished(e -> show());
    }

    /** Adds the hint to the window's scene-root stack, above the window's content. */
    void install(StackPane sceneRoot) {
        sceneRoot.getChildren().add(card);
        // A window resized while the hint shows lays the list out again for the new size.
        sceneRoot.widthProperty().addListener((obs, was, now) -> relayoutIfShowing());
        sceneRoot.heightProperty().addListener((obs, was, now) -> relayoutIfShowing());
    }

    Node node() {
        return card;
    }

    boolean isShowing() {
        return card.isVisible();
    }

    /** What the hint lists, in its order: each cell's key and what it runs, joined by a tab. For tests. */
    List<String> rows() {
        List<String> rows = new ArrayList<>();
        for (Node cell : grid.getChildren()) {
            if (cell instanceof HBox box) {
                List<String> parts = new ArrayList<>();
                for (Node part : box.getChildren()) {
                    parts.add(part instanceof Label label ? label.getText() : "");
                }
                rows.add(String.join("\t", parts));
            } else if (cell instanceof Label label) {
                rows.add(label.getText());
            }
        }
        return rows;
    }

    /** The prefix the hint is headed with, as the keymap writes it. For tests. */
    String heading() {
        return header.getText();
    }

    /** The prefix the dispatcher reports (see {@code KeyDispatcher#setPrefixListener}): a sequence, or "". */
    void prefixChanged(String sequence) {
        prefix = sequence;
        if (prefix.isEmpty()) {
            wait.stop();
            hide();
        } else if (isShowing()) {
            show(); // the prefix grew while shown (C-x, then v): what follows C-x v, at once
        } else {
            wait.playFromStart();
        }
    }

    /** Shows the list for the pending prefix now, without waiting: what the delay ends in. Package-visible for tests. */
    void show() {
        wait.stop();
        if (prefix.isEmpty()) {
            hide();
            return;
        }
        List<KeymapContinuations.Continuation> keys =
                keymap.continuations(prefix, id -> registry.get(id).isPresent());
        if (keys.isEmpty()) {
            hide();
            return;
        }
        String shown = keymap.display(prefix);
        header.setText(shown);
        List<Node> cells = cells(keys);
        card.setManaged(true);
        card.setVisible(true);
        layout(cells);
        card.setAccessibleText(tr("keyhint.accessible", shown, spoken(keys)));
        card.notifyAccessibleAttributeChanged(AccessibleAttribute.TEXT);
    }

    /** Takes the list down again; the next prefix builds it afresh. */
    void hide() {
        card.setVisible(false);
        card.setManaged(false);
        grid.getChildren().clear();
        all = List.of();
    }

    /** Lays the list out again from the live keymap: after a keymap switch or a rebinding. */
    void refresh() {
        if (isShowing()) {
            show();
        }
    }

    private void relayoutIfShowing() {
        if (isShowing() && !all.isEmpty()) {
            layout(all);
        }
    }

    /** One cell per key: the key as the keymap writes it, then what it does. */
    private List<Node> cells(List<KeymapContinuations.Continuation> keys) {
        Map<String, String> titles = titles(keys);
        List<Node> cells = new ArrayList<>(keys.size());
        for (KeymapContinuations.Continuation key : keys) {
            Label chord = new Label(keymap.display(key.token()));
            chord.getStyleClass().add("prefix-key-hint-key");
            Label what = new Label(what(key, titles));
            what.getStyleClass().add(key.isPrefix() ? "prefix-key-hint-group" : "prefix-key-hint-title");
            HBox cell = new HBox(chord, what);
            UiZoom.spacing(cell, 8);
            cell.setAlignment(Pos.BASELINE_LEFT);
            cells.add(cell);
        }
        return cells;
    }

    /**
     * Each command's title without its category, as the menu bar shows it, unless two keys would then read the
     * same: those keep the category that tells them apart.
     */
    private Map<String, String> titles(List<KeymapContinuations.Continuation> keys) {
        Map<String, String> full = new HashMap<>();
        Map<String, Integer> shortCount = new HashMap<>();
        for (KeymapContinuations.Continuation key : keys) {
            String id = key.commandId();
            if (id != null) {
                String title = registry.get(id).map(Command::title).orElse(id);
                full.put(id, title);
                shortCount.merge(MenuTitles.of(title), 1, Integer::sum);
            }
        }
        Map<String, String> titles = new HashMap<>();
        full.forEach((id, title) -> {
            String bare = MenuTitles.of(title);
            titles.put(id, shortCount.getOrDefault(bare, 0) > 1 ? title : bare);
        });
        return titles;
    }

    /** What a screen reader is told: every key and what it does, in the order shown. */
    private String spoken(List<KeymapContinuations.Continuation> keys) {
        Map<String, String> titles = titles(keys);
        List<String> parts = new ArrayList<>(keys.size());
        for (KeymapContinuations.Continuation key : keys) {
            parts.add(MainMenuBar.accessibleRowText(keymap.display(key.token()), what(key, titles)));
        }
        return String.join("; ", parts);
    }

    /** What a key does: its command's title, or for a key that leads further, how many commands lie behind it. */
    private static String what(KeymapContinuations.Continuation key, Map<String, String> titles) {
        String id = key.commandId();
        if (id == null) {
            return tr("keyhint.further", key.further());
        }
        String title = titles.get(id);
        return title != null ? title : id;
    }

    /**
     * Places the cells in columns, top to bottom then left to right: as many columns as the window is wide
     * enough for, as many rows as {@link #MAX_HEIGHT_SHARE} of its height holds. When even that is too few
     * places, the last one says how many keys are left out.
     */
    private void layout(List<Node> cells) {
        all = cells;
        grid.getChildren().setAll(cells);
        if (card.getParent() instanceof StackPane stack) {
            StackPane.setMargin(
                    card,
                    new javafx.geometry.Insets(
                            0, UiZoom.px(16), bottomInset.getAsDouble() + UiZoom.px(6), UiZoom.px(16)));
            card.applyCss();
            double cellWidth = 0;
            double cellHeight = 0;
            for (Node cell : cells) {
                cellWidth = Math.max(cellWidth, cell.prefWidth(-1));
                cellHeight = Math.max(cellHeight, cell.prefHeight(-1));
            }
            double hgap = grid.getHgap();
            double vgap = grid.getVgap();
            double width = stack.getWidth()
                    - UiZoom.px(32)
                    - card.getInsets().getLeft()
                    - card.getInsets().getRight();
            double height = stack.getHeight() * MAX_HEIGHT_SHARE
                    - header.prefHeight(-1)
                    - card.getSpacing()
                    - card.getInsets().getTop()
                    - card.getInsets().getBottom();
            Grid fit = fit(cells.size(), cellWidth, cellHeight, hgap, vgap, width, height);
            place(cells, fit);
        } else {
            place(cells, fit(cells.size(), 1, 1, 0, 0, 4, Double.MAX_VALUE)); // not in a window: four columns
        }
    }

    /** How a list of {@code count} cells is laid out: columns, rows, and how many cells are shown. */
    record Grid(int columns, int rows, int shown) {}

    /**
     * The grid that fits {@code count} cells of at most {@code cellWidth} × {@code cellHeight} into
     * {@code width} × {@code height}: as many columns as fit (at least one), as few rows as that allows, and no
     * more rows than fit (at least three). {@code shown} is less than {@code count} when they still do not all
     * fit, and then one place is kept for the cell that says how many were left out. Pure.
     */
    static Grid fit(
            int count, double cellWidth, double cellHeight, double hgap, double vgap, double width, double height) {
        if (count <= 0) {
            return new Grid(0, 0, 0);
        }
        int columns = (int) Math.max(1, Math.floor((width + hgap) / Math.max(1, cellWidth + hgap)));
        columns = Math.min(columns, count);
        int maxRows = (int) Math.max(3, Math.floor((height + vgap) / Math.max(1, cellHeight + vgap)));
        int rows = (count + columns - 1) / columns;
        if (rows <= maxRows) {
            // Fill the columns evenly: 37 keys in 4 columns are 10 rows, and the last column is the short one.
            columns = (count + rows - 1) / rows;
            return new Grid(columns, rows, count);
        }
        return new Grid(columns, maxRows, columns * maxRows - 1);
    }

    private void place(List<Node> cells, Grid fit) {
        List<Node> placed = new ArrayList<>(fit.shown() + 1);
        for (int i = 0; i < fit.shown(); i++) {
            Node cell = cells.get(i);
            GridPane.setConstraints(cell, i / fit.rows(), i % fit.rows());
            placed.add(cell);
        }
        if (fit.shown() < cells.size()) {
            Label more = new Label(tr("keyhint.more", cells.size() - fit.shown()));
            more.getStyleClass().add("prefix-key-hint-group");
            GridPane.setConstraints(more, fit.shown() / fit.rows(), fit.shown() % fit.rows());
            placed.add(more);
        }
        grid.getChildren().setAll(placed);
    }
}
