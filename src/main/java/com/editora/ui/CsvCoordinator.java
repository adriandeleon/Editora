package com.editora.ui;

import java.util.List;

import javafx.beans.InvalidationListener;
import javafx.util.Duration;

import com.editora.command.Command;
import com.editora.command.CommandRegistry;
import com.editora.csv.CsvParser;
import com.editora.editor.EditorBuffer;

/**
 * CSV/TSV grid preview: a spreadsheet view of the active {@code .csv}/{@code .tsv} buffer in a tool window,
 * refreshed on tab switch and (debounced) as the file is edited. Cells are editable when the buffer is
 * writable and its rows map 1:1 to physical lines (see {@link #commitCell}); otherwise the grid is
 * read-only with click-to-jump. Extracted from {@link MainController} via the {@link CoordinatorHost}
 * pattern: owns the {@link CsvGridPanel} and the parse/refresh/edit/jump logic; {@code MainController} keeps
 * the {@code ToolWindow} (built with {@link #panel()}), gates its stripe on a CSV buffer, and registers the
 * {@code view.toggleCsvGrid} setting toggle.
 */
final class CsvCoordinator {

    /** Window hooks beyond {@link CoordinatorHost} (the CSV grid tool window + the caret jump). */
    interface Ops {
        boolean isToolWindowOpen();

        void toggleToolWindow();

        /** Moves the active buffer's caret to {@code line} (0-based paragraph) / {@code col} (char offset). */
        void jumpTo(int line, int col);
    }

    /** Above this size the whole-file parse + TableView build is skipped (the huge-file guard idiom). */
    private static final int MAX_PREVIEW_CHARS = 2_000_000;

    private final CoordinatorHost host;
    private final Ops ops;
    private final CsvGridPanel panel;

    /** Debounced rebuild while the active CSV buffer is edited (only when the grid window is open). */
    private final javafx.animation.PauseTransition debounce =
            new javafx.animation.PauseTransition(Duration.millis(300));

    private final InvalidationListener textListener = o -> debounce.playFromStart();
    /** The buffer whose text listener is currently attached (so we can detach on tab switch). */
    private EditorBuffer attached;
    /** Delimiter of the grid currently shown — used to map a clicked field back to a caret offset. */
    private char delimiter = ',';

    CsvCoordinator(CoordinatorHost host, Ops ops) {
        this.host = host;
        this.ops = ops;
        this.panel = new CsvGridPanel(this::jumpToField);
        this.panel.setOnShown(() -> rebuild(host.activeBuffer())); // populate when the window is shown
        debounce.setOnFinished(e -> {
            if (ops.isToolWindowOpen()) {
                rebuild(host.activeBuffer());
            }
        });
    }

    CsvGridPanel panel() {
        return panel;
    }

    boolean isEnabled() {
        return host.settings().isCsvPreview() && !host.simpleModeActive();
    }

    void registerCommands(CommandRegistry registry) {
        registry.register(Command.of("tool.csvGrid", ops::toggleToolWindow));
    }

    /**
     * Re-targets the grid at {@code active}: (de)attaches the debounced edit listener and rebuilds the grid
     * from the buffer's text when the window is open. Called on tab switch, settings apply, and window open.
     */
    void refreshFor(EditorBuffer active) {
        EditorBuffer csv = (active != null && active.isCsv() && isEnabled()) ? active : null;
        if (attached != csv) {
            if (attached != null) {
                attached.getArea().textProperty().removeListener(textListener);
            }
            attached = csv;
            if (attached != null) {
                attached.getArea().textProperty().addListener(textListener);
            }
        }
        if (ops.isToolWindowOpen()) {
            rebuild(active);
        }
    }

    private void rebuild(EditorBuffer active) {
        if (active == null || !active.isCsv() || !isEnabled()) {
            panel.setEditable(false, null);
            panel.setData(List.of());
            return;
        }
        String text = active.getContent();
        if (text.length() > MAX_PREVIEW_CHARS) {
            panel.setEditable(false, null);
            panel.setData(List.of()); // too large — the placeholder shows the empty message
            host.setStatus(com.editora.i18n.Messages.tr("status.csv.gridTooLarge"));
            return;
        }
        delimiter = CsvParser.detectDelimiter(text);
        List<List<String>> rows = CsvParser.parse(text, delimiter);
        // In-place editing is safe only when each parsed row maps 1:1 to a physical line — i.e. no quoted
        // multi-line field merged two lines — and the buffer is writable. Otherwise the grid stays read-only.
        boolean canEdit = active.isEditable() && !CsvParser.hasMultilineField(rows);
        panel.setEditable(canEdit, this::commitCell);
        panel.setData(rows);
    }

    /**
     * Writes a grid cell edit back to the buffer: rebuild the edited row's physical line (fields re-quoted
     * per RFC-4180) and replace exactly that paragraph via an undoable {@code replaceText}. The subsequent
     * text-change fires the debounced re-parse, resyncing the grid.
     */
    private void commitCell(int dataRow, int field, String value) {
        EditorBuffer active = host.activeBuffer();
        if (active == null || !active.isCsv() || !active.isEditable()) {
            return;
        }
        var area = active.getArea();
        int line = panel.isHeaderRow() ? dataRow + 1 : dataRow;
        if (line < 0 || line >= area.getParagraphs().size()) {
            return;
        }
        String lineText = area.getText(line);
        List<List<String>> parsed = CsvParser.parse(lineText, delimiter);
        List<String> fields = parsed.isEmpty() ? new java.util.ArrayList<>() : new java.util.ArrayList<>(parsed.get(0));
        while (fields.size() <= field) {
            fields.add("");
        }
        fields.set(field, value);
        String newLine = CsvParser.formatRow(fields, delimiter);
        if (newLine.equals(lineText)) {
            return; // no-op (e.g. re-committing the same value)
        }
        int start = area.getAbsolutePosition(line, 0);
        int end = start + area.getParagraphLength(line);
        area.replaceText(start, end, newLine); // undoable; marks the buffer dirty
    }

    /** A grid cell was activated: place the caret at that field's start in the active buffer. */
    private void jumpToField(int lineIndex, int field) {
        EditorBuffer active = host.activeBuffer();
        if (active == null || !active.isCsv()) {
            return;
        }
        int paragraphs = active.getArea().getParagraphs().size();
        int line = Math.max(0, Math.min(lineIndex, paragraphs - 1));
        String lineText = active.getArea().getText(line);
        int col = CsvParser.fieldStartOffset(lineText, delimiter, field);
        ops.jumpTo(line, col);
    }

    /** Called when the buffer's tab is closed — drop the text listener if it was this one. */
    void onBufferClosed(EditorBuffer closed) {
        if (attached == closed && attached != null) {
            attached.getArea().textProperty().removeListener(textListener);
            attached = null;
        }
    }
}
