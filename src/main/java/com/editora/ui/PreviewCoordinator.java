package com.editora.ui;

import java.nio.file.Path;

import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;

import com.editora.config.ConfigManager;
import com.editora.config.Settings;
import com.editora.editor.EditorBuffer;
import com.editora.markdown.MarkdownTable;

import static com.editora.i18n.Messages.tr;

/** Coordinates preview modes, preview editing and lint integration. */
final class PreviewCoordinator {
    interface Host {
        EditorArea editorArea();

        ConfigManager config();

        SettingsWindow settingsWindow();

        OverlayHost overlayHost();

        ToolWindowManager toolWindows();

        MarkdownLintPanel markdownLintPanel();

        ToolWindow markdownLintToolWindow();

        EditingCoordinator editing();

        EditorSettingsCoordinator editorSettings();

        MermaidCoordinator mermaid();

        ExportCoordinator exports();

        CsvCoordinator csvCoordinator();

        HttpClientCoordinator httpClient();

        void setStatus(String message);

        EditorBuffer activeBuffer();

        EditorBuffer bufferOf(Tab tab);

        void requestSave();

        boolean appThemeDark();

        void promptText(String title, String label, String initial, java.util.function.Consumer<String> onAccept);
    }

    private final Host host;

    PreviewCoordinator(Host host) {
        this.host = host;
    }

    /** Max grid the Markdown table-size picker offers (rows × columns). */
    static final int TABLE_PICKER_MAX_ROWS = 8;

    static final int TABLE_PICKER_MAX_COLS = 8;

    final com.editora.editor.MarkdownLintService markdownLintService = new com.editora.editor.MarkdownLintService();

    /** Whether Markdown linting is effective (the setting; the per-buffer gate adds Markdown + non-huge). */
    boolean markdownLintEnabled() {
        return host.config().getSettings().isMarkdownLint();
    }

    /** Pushes the Markdown-lint enabled state to every buffer (init + each settings apply). */
    void applyMarkdownLint() {
        boolean on = markdownLintEnabled();
        for (Tab tab : host.editorArea().tabs()) {
            EditorBuffer b = host.bufferOf(tab);
            if (b != null) {
                b.setMarkdownLintEnabled(on);
            }
        }
        if (host.markdownLintToolWindow() != null
                && host.markdownLintPanel() != null
                && host.toolWindows().isOpen(host.markdownLintToolWindow())) {
            runMarkdownLintScan();
        }
    }

    /** Lints the active Markdown buffer once and fills the Lint tool window (off-thread). */
    void runMarkdownLintScan() {
        EditorBuffer b = host.activeBuffer();
        if (b == null || !b.isMarkdown() || !markdownLintEnabled()) {
            host.markdownLintPanel().setResults(null, java.util.List.of());
            return;
        }
        markdownLintService.validate(
                b.getContent(),
                effectiveMarkdownLintDisabled(b),
                diags -> host.markdownLintPanel().setResults(b.getPath(), diags));
    }

    /** Toggles the Markdown Lint tool window; opening it auto-scans via {@code focusFirstItem}. */
    void toggleMarkdownLintWindow() {
        host.toolWindows().toggle(host.markdownLintToolWindow());
    }

    private record MarkdownLintConfigEntry(long mtime, java.util.Set<String> disabled) {}

    final java.util.Map<java.nio.file.Path, MarkdownLintConfigEntry> markdownLintConfigCache =
            new java.util.HashMap<>();

    /** The rule codes disabled for {@code buffer}: the Settings list ∪ the nearest {@code .markdownlint.json}. */
    java.util.Set<String> effectiveMarkdownLintDisabled(EditorBuffer buffer) {
        java.util.Set<String> off = new java.util.HashSet<>();
        for (String code : host.config().getSettings().getMarkdownLintDisabledRules()) {
            if (code != null && !code.isBlank()) {
                off.add(code.strip().toUpperCase(java.util.Locale.ROOT));
            }
        }
        java.nio.file.Path path = buffer == null ? null : buffer.getPath();
        if (path != null && com.editora.vfs.Vfs.isLocal(path)) {
            off.addAll(markdownLintConfigDisabled(path));
        }
        return off;
    }

    /** Walks up from {@code file} for the nearest {@code .markdownlint.json} and returns the rules it disables. */
    java.util.Set<String> markdownLintConfigDisabled(java.nio.file.Path file) {
        java.nio.file.Path dir = file.getParent();
        while (dir != null) {
            java.nio.file.Path cfg = dir.resolve(".markdownlint.json");
            if (java.nio.file.Files.isRegularFile(cfg)) {
                try {
                    long mtime = java.nio.file.Files.getLastModifiedTime(cfg).toMillis();
                    MarkdownLintConfigEntry cached = markdownLintConfigCache.get(cfg);
                    if (cached == null || cached.mtime() != mtime) {
                        java.util.Set<String> rules = com.editora.markdown.MarkdownLintConfig.disabledRules(
                                java.nio.file.Files.readString(cfg));
                        cached = new MarkdownLintConfigEntry(mtime, rules);
                        markdownLintConfigCache.put(cfg, cached);
                    }
                    return cached.disabled();
                } catch (java.io.IOException e) {
                    return java.util.Set.of();
                }
            }
            dir = dir.getParent();
        }
        return java.util.Set.of();
    }

    /** Applies the safe-to-automate Markdown-lint fixes to the active buffer (undoable). */
    void fixMarkdownLint() {
        EditorBuffer b = host.activeBuffer();
        if (b == null || !b.isMarkdown()) {
            host.setStatus(tr("status.markdownLint.notMarkdown"));
            return;
        }
        if (!markdownLintEnabled()) {
            host.setStatus(tr("status.markdownLint.off"));
            return;
        }
        String text = b.getContent();
        String fixed = com.editora.markdown.MarkdownLintFix.fix(
                text,
                effectiveMarkdownLintDisabled(b),
                host.config().getSettings().getTabSize());
        if (fixed.equals(text)) {
            host.setStatus(tr("status.markdownLint.fixNone"));
            return;
        }
        b.getArea().replaceText(fixed); // whole-document replace (undoable)
        host.setStatus(tr("status.markdownLint.fixed"));
    }

    /** Picker to enable/disable an individual Markdown-lint rule (writes Settings + re-lints live). */
    void chooseMarkdownLintRule() {
        host.editorSettings()
                .chooseSetting(
                        "markdownLint.toggleRule",
                        () -> com.editora.markdown.MarkdownLint.RULES.stream()
                                .map(com.editora.markdown.MarkdownLint.Rule::code)
                                .toList(),
                        code -> {
                            boolean on = !markdownLintRuleDisabled(code);
                            String name = tr("mdlint.rule." + code);
                            return (on ? "✓ " : "✗ ") + code + " — " + name;
                        },
                        this::toggleMarkdownLintRule);
    }

    boolean markdownLintRuleDisabled(String code) {
        for (String c : host.config().getSettings().getMarkdownLintDisabledRules()) {
            if (code.equalsIgnoreCase(c)) {
                return true;
            }
        }
        return false;
    }

    void toggleMarkdownLintRule(String code) {
        java.util.List<String> list =
                new java.util.ArrayList<>(host.config().getSettings().getMarkdownLintDisabledRules());
        boolean wasDisabled = list.removeIf(c -> code.equalsIgnoreCase(c));
        if (!wasDisabled) {
            list.add(code);
        }
        host.config().getSettings().setMarkdownLintDisabledRules(list);
        host.requestSave();
        applyMarkdownLint(); // re-kicks every buffer's validator (new disabled set) + refreshes the panel
        if (host.settingsWindow() != null) {
            host.settingsWindow().syncAll();
        }
        host.setStatus(tr(wasDisabled ? "status.markdownLint.ruleEnabled" : "status.markdownLint.ruleDisabled", code));
    }

    /** Pages the active buffer's Markdown preview if it's the active scroll target (C-v / M-v). */
    boolean pageActivePreview(boolean down) {
        EditorBuffer b = host.activeBuffer();
        return b != null && b.pagePreview(down);
    }

    /** Persists the buffer's Markdown view mode, keyed by file path (EDITOR is the unset default). */
    void persistMarkdownMode(EditorBuffer buffer) {
        Path file = buffer.getPath();
        if (file == null) {
            return;
        }
        var map = host.config().getWorkspaceState().getMarkdownViewModes();
        EditorBuffer.MarkdownViewMode mode = buffer.getMarkdownViewMode();
        if (mode == EditorBuffer.MarkdownViewMode.EDITOR) {
            map.remove(file.toString());
        } else {
            map.put(file.toString(), mode.name());
        }
        host.requestSave();
    }

    /**
     * Attaches or removes the floating Editor/Split/Preview control to match {@link EditorBuffer#hasPreview()}.
     * Re-evaluated whenever a buffer's language can change its previewability — at open, after Save As
     * (a new untitled buffer becomes a `.md`/`.mmd`), and when the Mermaid feature toggles. The light/dark
     * sun/moon control is Markdown-only (it themes the Markdown CSS; diagrams follow the app theme).
     */
    void ensurePreviewControls(EditorBuffer buffer) {
        // A CSV / .http buffer becomes previewable (hasPreview()) only once its grid / response panel is
        // injected, so do that first — the same Editor/Split/Preview toggle then attaches below, exactly
        // like Markdown/Mermaid.
        host.csvCoordinator().ensureCsvPreview(buffer);
        host.httpClient().ensureHttpPreview(buffer);
        boolean want = buffer.hasPreview();
        boolean has = buffer.hasViewModeControl();
        if (want && !has) {
            MarkdownViewToggle toggle = new MarkdownViewToggle(buffer);
            buffer.setOnViewModeChanged(() -> {
                persistMarkdownMode(buffer);
                toggle.sync();
            });
            buffer.setViewModeControl(toggle);
            if (buffer.isMarkdown()) {
                buffer.setPreviewThemeToggle(this::toggleMarkdownPreviewTheme);
                buffer.applyPreviewTheme(host.config().getSettings().getMarkdownPreviewTheme(), host.appThemeDark());
            }
        } else if (!want && has) {
            buffer.setMarkdownViewMode(EditorBuffer.MarkdownViewMode.EDITOR);
            buffer.setViewModeControl(null);
        }
        // Keep live linting in step when a Save As / rename flips the buffer to/from .mmd.
        host.mermaid().refreshLint(buffer);
    }

    /** Restores a Markdown/CSV/.http file's saved view mode after it is opened (and its toggle is wired). */
    void restoreMarkdownMode(EditorBuffer buffer) {
        // Inject the node-gated previews first so those buffers report hasPreview().
        host.csvCoordinator().ensureCsvPreview(buffer);
        host.httpClient().ensureHttpPreview(buffer);
        Path file = buffer.getPath();
        if (file == null || !buffer.hasPreview()) {
            return;
        }
        if (buffer.isMarkwhen()) {
            // Restore the timeline/calendar renderer BEFORE the view mode below (so the first render uses it).
            String view = host.config().getWorkspaceState().getMarkwhenViews().get(file.toString());
            if (view != null) {
                try {
                    buffer.setMarkwhenView(EditorBuffer.MarkwhenView.valueOf(view));
                } catch (IllegalArgumentException ignored) {
                    // unknown persisted value — keep the timeline default
                }
            }
        }
        String saved = host.config().getWorkspaceState().getMarkdownViewModes().get(file.toString());
        if (saved == null) {
            return;
        }
        try {
            buffer.setMarkdownViewMode(EditorBuffer.MarkdownViewMode.valueOf(saved));
        } catch (IllegalArgumentException ignored) {
            // unknown persisted value — leave in EDITOR mode
        }
    }

    /** Sets the active previewable buffer's view mode (Markdown or Mermaid; no-op otherwise). */
    void setActiveMarkdownMode(EditorBuffer.MarkdownViewMode mode) {
        EditorBuffer b = host.activeBuffer();
        if (b != null && b.hasPreview()) {
            b.setMarkdownViewMode(mode);
        } else {
            host.setStatus(tr("status.notMarkdown"));
        }
    }

    /**
     * Toggles the active buffer between Editor and the given preview mode — backs the file-type-agnostic
     * "Toggle Preview" ({@link EditorBuffer.MarkdownViewMode#PREVIEW}) and "Toggle Split Preview"
     * ({@link EditorBuffer.MarkdownViewMode#SPLIT}) commands. Works for any previewable file
     * ({@link EditorBuffer#hasPreview()}): Markdown, CSV, Mermaid, diagrams, Typst, SVG, structured data,
     * crontab/fstab/systemd/etc. Pressing it while already in {@code target} collapses back to Editor;
     * otherwise it switches to {@code target} (so the two commands also flip Split ⇄ Preview between them).
     */
    void togglePreviewMode(EditorBuffer.MarkdownViewMode target) {
        EditorBuffer b = host.activeBuffer();
        if (b == null || !b.hasPreview()) {
            host.setStatus(tr("status.noPreview"));
            return;
        }
        EditorBuffer.MarkdownViewMode next =
                b.getMarkdownViewMode() == target ? EditorBuffer.MarkdownViewMode.EDITOR : target;
        b.setMarkdownViewMode(next);
    }

    /** Runs a Markdown format action on the active buffer; reports when it isn't an editable Markdown file. */
    void withMarkdown(java.util.function.Consumer<EditorBuffer> action) {
        EditorBuffer b = host.activeBuffer();
        if (b == null || !b.canFormatMarkdown()) {
            host.setStatus(tr("status.notMarkdown"));
            return;
        }
        action.accept(b);
    }

    /** Opens the table-size grid picker; on pick, inserts a fresh GFM table into the active Markdown buffer. */
    void markdownInsertTable() {
        EditorBuffer b = host.activeBuffer();
        if (b == null || !b.canFormatMarkdown()) {
            host.setStatus(tr("status.notMarkdown"));
            return;
        }
        showTableSizePicker((rows, cols) -> b.insertTable(rows, cols));
    }

    /**
     * The command-palette path: a keyboard-only {@code RxC} size prompt (e.g. {@code "4x4"}) instead of the
     * mouse grid picker. The grid picker stays the format-bar button / right-click "Insert Table" UI.
     */
    void markdownInsertTableViaText() {
        EditorBuffer b = host.activeBuffer();
        if (b == null || !b.canFormatMarkdown()) {
            host.setStatus(tr("status.notMarkdown"));
            return;
        }
        host.promptText(tr("table.size.title"), tr("table.size.label"), "3x3", input -> {
            int[] rc = MarkdownTable.parseSize(input);
            if (rc == null) {
                host.setStatus(tr("table.size.invalid"));
                return;
            }
            b.insertTable(rc[0], rc[1]);
        });
    }

    /**
     * A Typora/Word-style table-size grid picker shown as an in-scene overlay: hover to highlight an
     * {@code R × C} block (rows include the header), click to commit. Max {@value #TABLE_PICKER_MAX_ROWS} ×
     * {@value #TABLE_PICKER_MAX_COLS}.
     */
    void showTableSizePicker(java.util.function.BiConsumer<Integer, Integer> onPick) {
        final int maxR = TABLE_PICKER_MAX_ROWS;
        final int maxC = TABLE_PICKER_MAX_COLS;
        javafx.scene.layout.VBox card = new javafx.scene.layout.VBox(8);
        card.getStyleClass().add("table-size-picker");
        card.setPadding(new javafx.geometry.Insets(12));
        // Hug the grid + padding — without a max-size cap the StackPane overlay stretches the card to fill
        // the whole editor area (like QuickOpen's card, which caps to its preferred size).
        card.setMaxSize(javafx.scene.layout.Region.USE_PREF_SIZE, javafx.scene.layout.Region.USE_PREF_SIZE);
        Label heading = new Label(tr("table.picker.prompt"));
        heading.getStyleClass().add("table-size-label");
        javafx.scene.layout.GridPane grid = new javafx.scene.layout.GridPane();
        grid.setHgap(3);
        grid.setVgap(3);
        javafx.scene.shape.Rectangle[][] cells = new javafx.scene.shape.Rectangle[maxR][maxC];
        int[] sel = {0, 0}; // selected rows, cols (1-based; 0 = nothing yet)
        Runnable[] repaint = new Runnable[1];
        repaint[0] = () -> {
            for (int r = 0; r < maxR; r++) {
                for (int c = 0; c < maxC; c++) {
                    boolean on = r < sel[0] && c < sel[1];
                    cells[r][c]
                            .getStyleClass()
                            .setAll("table-size-cell", on ? "table-size-cell-on" : "table-size-cell-off");
                }
            }
            heading.setText(sel[0] == 0 ? tr("table.picker.prompt") : tr("table.picker.size", sel[0], sel[1]));
        };
        for (int r = 0; r < maxR; r++) {
            for (int c = 0; c < maxC; c++) {
                javafx.scene.shape.Rectangle cell = new javafx.scene.shape.Rectangle(16, 16);
                cell.getStyleClass().setAll("table-size-cell", "table-size-cell-off");
                final int rr = r + 1;
                final int cc = c + 1;
                cell.setOnMouseEntered(e -> {
                    sel[0] = rr;
                    sel[1] = cc;
                    repaint[0].run();
                });
                cell.setOnMouseClicked(e -> {
                    host.overlayHost().hide();
                    onPick.accept(rr, cc);
                });
                cells[r][c] = cell;
                grid.add(cell, c, r);
            }
        }
        card.getChildren().addAll(heading, grid);
        card.getProperties().put("editora.ownsKeys", true);
        host.overlayHost().show(card, true, () -> {}, () -> {}); // centered — it's a small grid, not a top palette
    }

    void markdownInline(String marker) {
        withMarkdown(b -> b.formatInline(marker));
    }

    /** Runs a Typst markup-format action on the active buffer, or reports it isn't a Typst buffer. */
    void withTypst(java.util.function.Consumer<EditorBuffer> action) {
        EditorBuffer b = host.activeBuffer();
        if (b == null || !b.isTypst() || !b.canFormatMarkup()) {
            host.setStatus(tr("status.typst.notTypst"));
            return;
        }
        action.accept(b);
    }

    /** {@code markdown.openLink}: open the link under the caret externally. */
    void markdownOpenLink() {
        EditorBuffer b = host.activeBuffer();
        if (b == null || !b.isMarkdown()) {
            host.setStatus(tr("status.notMarkdown"));
        } else if (!b.openLinkUnderCaret()) {
            host.setStatus(tr("status.markdown.noLink"));
        }
    }

    /** {@code markdown.reflowTable}: normalize/align the GFM table around the caret. */
    void markdownReflowTable() {
        EditorBuffer b = host.activeBuffer();
        if (b == null || !b.canFormatMarkdown()) {
            host.setStatus(tr("status.notMarkdown"));
        } else if (!b.reflowTable()) {
            host.setStatus(tr("status.markdown.notTable"));
        }
    }

    /** {@code markdown.toc}: insert a table of contents at the caret, or regenerate the existing TOC block. */
    void markdownToc() {
        EditorBuffer b = host.activeBuffer();
        if (b == null || !b.canFormatMarkdown()) {
            host.setStatus(tr("status.notMarkdown"));
        } else if (!b.insertOrUpdateToc()) {
            host.setStatus(tr("status.markdown.tocNoHeadings"));
        } else {
            host.setStatus(tr("status.markdown.tocDone"));
        }
    }

    /** {@code markdown.tableFromCsv}: convert the selected CSV (else clipboard CSV) into a GFM table. */
    void markdownTableFromCsv() {
        EditorBuffer b = host.activeBuffer();
        if (b == null || !b.canFormatMarkdown()) {
            host.setStatus(tr("status.notMarkdown"));
        } else if (!b.tableFromCsv()) {
            host.setStatus(tr("status.markdown.csvEmpty"));
        }
    }

    /** {@code markdown.tableToCsv}: copy the caret's GFM table to the clipboard as CSV. */
    void markdownTableToCsv() {
        EditorBuffer b = host.activeBuffer();
        if (b == null || !b.canFormatMarkdown()) {
            host.setStatus(tr("status.notMarkdown"));
        } else if (!b.tableToCsv()) {
            host.setStatus(tr("status.markdown.notTable"));
        } else {
            host.setStatus(tr("status.markdown.csvCopied"));
        }
    }

    /** {@code markdown.tableExport*}: file-export the caret's GFM table ({@code format} = csv|xlsx|ods). */
    void markdownTableExport(String format) {
        EditorBuffer b = host.activeBuffer();
        if (b == null || !b.canFormatMarkdown()) {
            host.setStatus(tr("status.notMarkdown"));
        } else if (!b.exportTableFile(format)) {
            host.setStatus(tr("status.markdown.notTable"));
        }
    }

    /**
     * File-exports a Markdown table the buffer already rendered to {@code csv}. {@code format} = {@code csv}
     * writes the CSV text; {@code xlsx}/{@code ods} parse it and reuse the spreadsheet writers. Wired into each
     * buffer via {@code setTableFileExporter}.
     */
    void exportMarkdownTableFile(String csv, String format) {
        if (csv == null || csv.isBlank()) {
            host.setStatus(tr("status.markdown.notTable"));
            return;
        }
        EditorBuffer b = host.activeBuffer();
        String base = b == null ? "table" : ExportCoordinator.bufferBaseName(b);
        switch (format) {
            case "csv" -> host.exports().exportCsvTextToFile(csv, base);
            // A Markdown table always leads with a header row (toCsv drops the ---|--- divider).
            case "xlsx" ->
                host.exports().csvExportSpreadsheet(com.editora.csv.CsvParser.parse(csv, ','), true, base, true);
            case "ods" ->
                host.exports().csvExportSpreadsheet(com.editora.csv.CsvParser.parse(csv, ','), true, base, false);
            default -> {}
        }
    }

    /** {@code csv.copyAsMarkdownTable}: copy the active CSV/TSV buffer to the clipboard as a GFM table. */
    void csvCopyAsMarkdownTable() {
        EditorBuffer b = host.activeBuffer();
        if (b == null || !b.isCsv()) {
            host.setStatus(tr("status.csv.notCsv"));
            return;
        }
        String md = MarkdownTable.fromCsv(b.getContent());
        if (md == null) {
            host.setStatus(tr("status.csv.empty"));
            return;
        }
        ClipboardContent cc = new ClipboardContent();
        cc.putString(md);
        Clipboard.getSystemClipboard().setContent(cc);
        host.setStatus(tr("status.csv.copied"));
    }

    /** {@code csv.align}: pad the active CSV/TSV so its column delimiters line up (Rainbow-CSV Align). */
    void csvAlign() {
        csvReformat(true);
    }

    /** {@code csv.shrink}: strip column-alignment padding from the active CSV/TSV (reverses {@link #csvAlign}). */
    void csvShrink() {
        csvReformat(false);
    }

    /** Aligns ({@code align=true}) or shrinks the active CSV buffer's text via {@code CsvAlign}, undoable. */
    void csvReformat(boolean align) {
        EditorBuffer b = host.activeBuffer();
        if (b == null || !b.isCsv()) {
            host.setStatus(tr("status.csv.notCsv"));
            return;
        }
        if (!host.editing().activeEditable()) {
            return;
        }
        String text = b.getContent();
        if (text.isEmpty()) {
            host.setStatus(tr("status.csv.empty"));
            return;
        }
        char delim = com.editora.csv.CsvParser.detectDelimiter(text);
        // A quoted multi-line field means a record no longer maps to one physical line — line-based
        // align/shrink would corrupt it, so refuse (mirrors the grid's edit guard).
        if (com.editora.csv.CsvParser.hasMultilineField(com.editora.csv.CsvParser.parse(text, delim))) {
            host.setStatus(tr("status.csv.multiline"));
            return;
        }
        String out = align ? com.editora.csv.CsvAlign.align(text, delim) : com.editora.csv.CsvAlign.shrink(text, delim);
        if (out.equals(text)) {
            host.setStatus(tr(align ? "status.csv.alignNoChange" : "status.csv.shrinkNoChange"));
            return;
        }
        b.getArea().replaceText(out); // whole-document replace (undoable)
        host.setStatus(tr(align ? "status.csv.aligned" : "status.csv.shrunk"));
    }

    /** {@code markdown.toggleFormatBar}: flip the selection format-bar setting + re-sync every buffer. */
    void toggleMarkdownFormatBar() {
        Settings s = host.config().getSettings();
        boolean now = !s.isMarkdownFormatBar();
        s.setMarkdownFormatBar(now);
        host.requestSave();
        for (Tab tab : host.editorArea().tabs()) {
            EditorBuffer b = host.bufferOf(tab);
            if (b != null) {
                b.setFormatBarEnabled(now);
            }
        }
        host.settingsWindow().syncMarkdownFormatBarCheck();
        host.setStatus(tr(now ? "status.markdown.formatBar.on" : "status.markdown.formatBar.off"));
    }

    /** Pushes the (global) Markdown preview theme to every open buffer's preview + its toggle glyph. */
    void applyMarkdownPreviewTheme() {
        String mode = host.config().getSettings().getMarkdownPreviewTheme();
        boolean appDark = host.appThemeDark();
        for (Tab tab : host.editorArea().tabs()) {
            EditorBuffer b = host.bufferOf(tab);
            if (b != null && b.isMarkdown()) {
                b.applyPreviewTheme(mode, appDark);
            }
        }
    }

    /**
     * Toggles the Markdown preview between light and dark, independent of the app theme. Flips the current
     * effective theme (seeded from the app theme the first time), persists it, and re-applies to all
     * previews. Run by the floating sun/moon control and the {@code view.toggleMarkdownPreviewTheme} command.
     */
    void toggleMarkdownPreviewTheme() {
        String mode = host.config().getSettings().getMarkdownPreviewTheme();
        boolean currentlyDark = "dark".equals(mode) || (mode.isEmpty() && host.appThemeDark());
        String next = currentlyDark ? "light" : "dark";
        host.config().getSettings().setMarkdownPreviewTheme(next);
        host.requestSave();
        applyMarkdownPreviewTheme();
        host.setStatus(tr("status.markdownPreviewTheme", tr("markdown.previewTheme." + next)));
    }

    /** Zooms the active Markdown buffer's preview text: {@code >0} in, {@code <0} out, {@code 0} reset. */
    void markdownZoom(int direction) {
        EditorBuffer b = host.activeBuffer();
        if (b == null || !b.isMarkdown()) {
            host.setStatus(tr("status.notMarkdown"));
            return;
        }
        if (direction > 0) {
            b.zoomPreviewIn();
        } else if (direction < 0) {
            b.zoomPreviewOut();
        } else {
            b.resetPreviewZoom();
        }
    }

    /** Toggles the active Markwhen buffer's preview between the timeline and calendar renderers. */
    void toggleMarkwhenView() {
        EditorBuffer b = host.activeBuffer();
        if (b == null || !b.isMarkwhen()) {
            host.setStatus(tr("status.markwhen.notMarkwhen"));
            return;
        }
        b.toggleMarkwhenView();
        host.setStatus(tr(
                b.getMarkwhenView() == EditorBuffer.MarkwhenView.CALENDAR
                        ? "status.markwhen.viewCalendar"
                        : "status.markwhen.viewTimeline"));
    }

    /** Flips a structured (JSON/YAML/TOML) preview between the tree and the OpenAPI-docs view. */
    void toggleStructuredView() {
        EditorBuffer b = host.activeBuffer();
        if (b == null || !b.isStructured()) {
            host.setStatus(tr("status.structured.notStructured"));
            return;
        }
        b.toggleStructuredView();
        host.setStatus(tr(b.isStructuredOpenApi() ? "status.structured.viewToggled" : "status.structured.notOpenApi"));
    }

    /** Flips a pom.xml preview between its Maven summary (the default) and the standard XML tree. */
    void togglePomView() {
        EditorBuffer b = host.activeBuffer();
        if (b == null || !b.isPom()) {
            host.setStatus(tr("status.pom.notPom"));
            return;
        }
        if (!b.togglePomView()) {
            // The XML tree belongs to the structured-data preview; say which switch is missing rather than
            // flipping into a view that isn't there.
            host.setStatus(tr("status.pom.xmlPreviewOff"));
            return;
        }
        host.setStatus(tr(b.isPomShowingXml() ? "status.pom.viewXml" : "status.pom.viewSummary"));
    }

    /** Persists the Markwhen buffer's preview renderer choice (TIMELINE default → removed). */
    void persistMarkwhenView(EditorBuffer buffer) {
        Path file = buffer.getPath();
        if (file == null) {
            return;
        }
        java.util.Map<String, String> map = host.config().getWorkspaceState().getMarkwhenViews();
        if (buffer.getMarkwhenView() == EditorBuffer.MarkwhenView.TIMELINE) {
            map.remove(file.toString());
        } else {
            map.put(file.toString(), buffer.getMarkwhenView().name());
        }
        host.requestSave();
    }
}
