package com.editora.ui;

import java.util.function.BiConsumer;

import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;

import static com.editora.i18n.Messages.tr;

/**
 * The table-size grid shown when a table is inserted from the format bar or the right-click menu: a
 * Typora/Word-style block of cells where a size is picked by hovering and clicking — or with the arrow keys
 * and Enter. The card takes the keyboard focus while it is up, so nothing typed reaches the document under
 * it and Esc (the overlay's own cancel) closes it.
 */
final class TableSizePicker {

    /** The size highlighted when the picker opens: Enter alone inserts a usable table. */
    static final int START_ROWS = 3;

    static final int START_COLS = 3;

    private final VBox card = new VBox(8);
    private final Label heading = new Label();
    private final Rectangle[][] cells;
    private final int maxRows;
    private final int maxCols;
    private int rows;
    private int cols;

    /**
     * @param onPick called with (rows including the header, columns) when a size is chosen
     * @param close hides the picker; called before {@code onPick}
     */
    TableSizePicker(int maxRows, int maxCols, Runnable close, BiConsumer<Integer, Integer> onPick) {
        this.maxRows = maxRows;
        this.maxCols = maxCols;
        this.rows = Math.min(START_ROWS, maxRows);
        this.cols = Math.min(START_COLS, maxCols);
        cells = new Rectangle[maxRows][maxCols];
        card.getStyleClass().add("table-size-picker");
        card.setPadding(new Insets(12));
        // Hug the grid + padding — without a max-size cap the StackPane overlay stretches the card to fill
        // the whole editor area (like QuickOpen's card, which caps to its preferred size).
        card.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        card.setFocusTraversable(true);
        card.setAccessibleText(tr("table.picker.prompt"));
        heading.getStyleClass().add("table-size-label");
        GridPane grid = new GridPane();
        grid.setHgap(3);
        grid.setVgap(3);
        for (int r = 0; r < maxRows; r++) {
            for (int c = 0; c < maxCols; c++) {
                Rectangle cell = new Rectangle(16, 16);
                final int rr = r + 1;
                final int cc = c + 1;
                cell.setOnMouseEntered(e -> select(rr, cc));
                cell.setOnMouseClicked(e -> {
                    close.run();
                    onPick.accept(rr, cc);
                });
                cells[r][c] = cell;
                grid.add(cell, c, r);
            }
        }
        Label hint = new Label(tr("table.picker.hint"));
        hint.getStyleClass().addAll("table-size-label", "text-muted");
        card.getChildren().addAll(heading, grid, hint);
        card.getProperties().put("editora.ownsKeys", true);
        card.addEventHandler(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ENTER) {
                close.run();
                onPick.accept(rows, cols);
                e.consume();
            } else {
                int[] next = step(rows, cols, e.getCode(), maxRows, maxCols);
                if (next != null) {
                    select(next[0], next[1]);
                    e.consume();
                }
            }
        });
        // Whatever else is typed stays here: the document behind the picker is not being edited.
        card.addEventHandler(KeyEvent.KEY_TYPED, KeyEvent::consume);
        select(rows, cols);
    }

    VBox card() {
        return card;
    }

    int rows() {
        return rows;
    }

    int cols() {
        return cols;
    }

    /**
     * The size after an arrow key (one step, clamped to the grid), Home (smallest) or End (largest); null
     * for any other key. Pure.
     */
    static int[] step(int rows, int cols, KeyCode key, int maxRows, int maxCols) {
        return switch (key) {
            case RIGHT -> new int[] {rows, Math.min(maxCols, cols + 1)};
            case LEFT -> new int[] {rows, Math.max(1, cols - 1)};
            case DOWN -> new int[] {Math.min(maxRows, rows + 1), cols};
            case UP -> new int[] {Math.max(1, rows - 1), cols};
            case HOME -> new int[] {1, 1};
            case END -> new int[] {maxRows, maxCols};
            default -> null;
        };
    }

    private void select(int r, int c) {
        rows = r;
        cols = c;
        for (int i = 0; i < maxRows; i++) {
            for (int j = 0; j < maxCols; j++) {
                boolean on = i < r && j < c;
                cells[i][j]
                        .getStyleClass()
                        .setAll("table-size-cell", on ? "table-size-cell-on" : "table-size-cell-off");
            }
        }
        heading.setText(tr("table.picker.size", r, c));
    }
}
