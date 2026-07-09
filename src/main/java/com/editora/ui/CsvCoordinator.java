package com.editora.ui;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import javafx.scene.Node;

import com.editora.command.Command;
import com.editora.command.CommandRegistry;
import com.editora.csv.CsvParser;
import com.editora.editor.EditorBuffer;

/**
 * CSV/TSV grid preview: an IntelliJ-style Editor/Split/Preview view of a {@code .csv}/{@code .tsv} buffer,
 * mirroring the Markdown preview. The grid is embedded <em>in the editor</em> (via
 * {@link EditorBuffer#setCsvPreviewNode}) rather than a bottom tool window — the floating Editor/Split/Preview
 * toggle ({@link MarkdownViewToggle}) attaches automatically once the buffer reports {@link
 * EditorBuffer#hasPreview()}. Each CSV buffer gets its <b>own</b> {@link CsvGridPanel} (a Node can't live in
 * two tabs); the buffer's debounced preview subscription drives {@link #rebuild} on edits while split/preview
 * is showing. Cells are editable when the buffer is writable and its rows map 1:1 to physical lines (see
 * {@link #commitCell}); otherwise the grid is read-only with click-to-jump. Extracted from
 * {@link MainController} via the {@link CoordinatorHost} pattern.
 */
final class CsvCoordinator {

    /** Window hooks beyond {@link CoordinatorHost} (the caret jump + the CSV export pipelines). */
    interface Ops {
        /** Moves the active buffer's caret to {@code line} (0-based paragraph) / {@code col} (char offset). */
        void jumpTo(int line, int col);

        /** Exports the CSV as a PDF (reuses the Markdown-table → PDF pipeline; a FileChooser picks the file). */
        void exportPdf(String csvText, String baseName);

        /** Opens the print preview for the CSV (reuses the Markdown-table → print pipeline). */
        void printCsv(String csvText);

        /** Exports parsed {@code rows} to Excel ({@code .xlsx}); {@code baseName} seeds the FileChooser. */
        void exportExcel(List<List<String>> rows, boolean hasHeader, String baseName);

        /** Exports parsed {@code rows} to an OpenDocument spreadsheet ({@code .ods}). */
        void exportOds(List<List<String>> rows, boolean hasHeader, String baseName);
    }

    /** Above this size the whole-file parse + TableView build is skipped (the huge-file guard idiom). */
    private static final int MAX_PREVIEW_CHARS = 2_000_000;

    private final CoordinatorHost host;
    private final Ops ops;

    /** One grid per open CSV buffer (a Node can't be shared across tabs); cleared on close / feature-off. */
    private final Map<EditorBuffer, CsvGridPanel> panels = new IdentityHashMap<>();
    /** Delimiter last used to build each buffer's grid — maps a clicked field back to a caret offset. */
    private final Map<EditorBuffer, Character> delimiters = new IdentityHashMap<>();

    CsvCoordinator(CoordinatorHost host, Ops ops) {
        this.host = host;
        this.ops = ops;
    }

    boolean isEnabled() {
        return host.settings().isCsvPreview() && !host.simpleModeActive();
    }

    void registerCommands(CommandRegistry registry) {
        registry.register(Command.of("csv.exportPdf", this::exportPdf));
        registry.register(Command.of("csv.print", this::printCsv));
        registry.register(Command.of("csv.exportExcel", this::exportExcel));
        registry.register(Command.of("csv.exportOds", this::exportOds));
    }

    // --- preview lifecycle -----------------------------------------------------------------------------

    /** Init + every settings apply: (de)attach the grid preview on every open buffer per the feature gate. */
    void applySupport() {
        host.forEachBuffer(this::ensureCsvPreview);
    }

    /** Re-target on tab switch: just (re)evaluate the active buffer's preview attachment. */
    void refreshFor(EditorBuffer active) {
        if (active != null) {
            ensureCsvPreview(active);
        }
    }

    /**
     * Attaches or removes the in-editor grid to match the CSV-preview gate, mirroring {@code
     * ensurePreviewControls}. When attaching, injects a per-buffer {@link CsvGridPanel} (wired to this
     * buffer's jump/commit/exports) plus the refresh callback the buffer fires on its debounced pulse.
     */
    void ensureCsvPreview(EditorBuffer buffer) {
        if (buffer == null) {
            return;
        }
        boolean want = buffer.isCsv() && isEnabled();
        boolean has = buffer.hasCsvPreview();
        if (want && !has) {
            CsvGridPanel panel = createPanelFor(buffer);
            panels.put(buffer, panel);
            buffer.setCsvPreviewRefresh(() -> rebuild(buffer));
            buffer.setCsvPreviewNode(panel); // makes hasPreview() true → the toggle attaches (controller)
        } else if (!want && has) {
            buffer.setCsvPreviewNode(null); // resets the buffer to EDITOR
            buffer.setCsvPreviewRefresh(null);
            panels.remove(buffer);
            delimiters.remove(buffer);
        }
    }

    private CsvGridPanel createPanelFor(EditorBuffer buffer) {
        CsvGridPanel panel = new CsvGridPanel((line, col) -> jumpToField(buffer, line, col));
        panel.setExportActions(new CsvGridPanel.ExportActions() {
            @Override
            public void exportPdf() {
                CsvCoordinator.this.exportPdf();
            }

            @Override
            public void printPreview() {
                CsvCoordinator.this.printCsv();
            }

            @Override
            public void exportExcel() {
                CsvCoordinator.this.exportExcel();
            }

            @Override
            public void exportOds() {
                CsvCoordinator.this.exportOds();
            }
        });
        return panel;
    }

    /** Called when a buffer's tab is closed — drop its grid + injected callbacks. */
    void onBufferClosed(EditorBuffer closed) {
        if (closed != null && panels.remove(closed) != null) {
            delimiters.remove(closed);
        }
    }

    // --- populate / edit / jump ------------------------------------------------------------------------

    /** Re-parses {@code buffer}'s text into its grid (fired by the buffer's debounced preview subscription). */
    private void rebuild(EditorBuffer buffer) {
        CsvGridPanel panel = panels.get(buffer);
        if (panel == null) {
            return;
        }
        if (!buffer.isCsv() || !isEnabled()) {
            panel.setEditable(false, null);
            panel.setData(List.of());
            return;
        }
        String text = buffer.getContent();
        if (text.length() > MAX_PREVIEW_CHARS) {
            panel.setEditable(false, null);
            panel.setData(List.of()); // too large — the placeholder shows the empty message
            host.setStatus(com.editora.i18n.Messages.tr("status.csv.gridTooLarge"));
            return;
        }
        char delimiter = CsvParser.detectDelimiter(text);
        delimiters.put(buffer, delimiter);
        List<List<String>> rows = CsvParser.parse(text, delimiter);
        // In-place editing is safe only when each parsed row maps 1:1 to a physical line — i.e. no quoted
        // multi-line field merged two lines — and the buffer is writable. Otherwise the grid stays read-only.
        boolean canEdit = buffer.isEditable() && !CsvParser.hasMultilineField(rows);
        panel.setEditable(canEdit, (dataRow, field, value) -> commitCell(buffer, dataRow, field, value));
        panel.setData(rows);
    }

    /**
     * Writes a grid cell edit back to the buffer: rebuild the edited row's physical line (fields re-quoted
     * per RFC-4180) and replace exactly that paragraph via an undoable {@code replaceText}. The subsequent
     * text-change fires the debounced re-parse, resyncing the grid. {@code dataRow == -1} edits the header
     * row (line 0).
     */
    private void commitCell(EditorBuffer buffer, int dataRow, int field, String value) {
        if (buffer == null || !buffer.isCsv() || !buffer.isEditable()) {
            return;
        }
        CsvGridPanel panel = panels.get(buffer);
        if (panel == null) {
            return;
        }
        char delimiter = delimiters.getOrDefault(buffer, ',');
        var area = buffer.getArea();
        int line = dataRow < 0 ? 0 : (panel.isHeaderRow() ? dataRow + 1 : dataRow);
        if (line < 0 || line >= area.getParagraphs().size()) {
            return;
        }
        String lineText = area.getText(line);
        List<List<String>> parsed = CsvParser.parse(lineText, delimiter);
        List<String> fields = parsed.isEmpty() ? new ArrayList<>() : new ArrayList<>(parsed.get(0));
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

    /** A grid cell was activated: place the caret at that field's start in the buffer. */
    private void jumpToField(EditorBuffer buffer, int lineIndex, int field) {
        if (buffer == null || !buffer.isCsv()) {
            return;
        }
        char delimiter = delimiters.getOrDefault(buffer, ',');
        int paragraphs = buffer.getArea().getParagraphs().size();
        int line = Math.max(0, Math.min(lineIndex, paragraphs - 1));
        String lineText = buffer.getArea().getText(line);
        int col = CsvParser.fieldStartOffset(lineText, delimiter, field);
        ops.jumpTo(line, col);
    }

    // --- exports (right-click menu + palette commands) -------------------------------------------------

    private void exportPdf() {
        withCsv(b -> ops.exportPdf(b.getContent(), host.bufferBaseName(b)));
    }

    private void printCsv() {
        withCsv(b -> ops.printCsv(b.getContent()));
    }

    private void exportExcel() {
        withCsv(b -> ops.exportExcel(currentRows(b), headerRowFor(b), host.bufferBaseName(b)));
    }

    private void exportOds() {
        withCsv(b -> ops.exportOds(currentRows(b), headerRowFor(b), host.bufferBaseName(b)));
    }

    /** Runs {@code action} on the active buffer when it's a CSV/TSV file and the feature is on. */
    private void withCsv(java.util.function.Consumer<EditorBuffer> action) {
        EditorBuffer b = host.activeBuffer();
        if (b != null && b.isCsv() && isEnabled()) {
            action.accept(b);
        }
    }

    /** The active buffer's grid header-row state (defaults to true when no grid is built yet). */
    private boolean headerRowFor(EditorBuffer b) {
        CsvGridPanel panel = panels.get(b);
        return panel == null || panel.isHeaderRow();
    }

    private List<List<String>> currentRows(EditorBuffer b) {
        return CsvParser.parse(b.getContent(), CsvParser.detectDelimiter(b.getContent()));
    }

    /** Exposed for the FX test harness (asserting a per-buffer grid was injected). */
    Node gridNodeFor(EditorBuffer buffer) {
        return panels.get(buffer);
    }
}
