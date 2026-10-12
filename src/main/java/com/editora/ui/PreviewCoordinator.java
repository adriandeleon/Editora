package com.editora.ui;

import java.nio.file.Path;

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

        /** Opens {@code url} through the app's external-link opener (the rule documentation of a lint issue). */
        void openExternalUrl(String url);

        /** The active project's root, or null with no project open (bounds the lint-config lookup). */
        java.nio.file.Path activeProjectRoot();
    }

    private final Host host;

    PreviewCoordinator(Host host) {
        this.host = host;
    }

    /** Max grid the Markdown table-size picker offers (rows × columns). */
    static final int TABLE_PICKER_MAX_ROWS = 8;

    static final int TABLE_PICKER_MAX_COLS = 8;

    final com.editora.editor.MarkdownLintService markdownLintService = new com.editora.editor.MarkdownLintService();

    /** Acting on lint issues (fix, disable, rule help, next/previous) and the project's lint config. */
    private MarkdownLintActions lintActions;

    MarkdownLintActions lint() {
        if (lintActions == null) {
            lintActions = new MarkdownLintActions(host, this);
        }
        return lintActions;
    }

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
        if (lintWindowOpen()) {
            runMarkdownLintScan();
        }
    }

    private boolean lintWindowOpen() {
        return host.markdownLintToolWindow() != null && host.toolWindows().isOpen(host.markdownLintToolWindow());
    }

    /**
     * The active tab changed to {@code buffer} (null for a non-editor tab): the Lint tool window follows it.
     * Its stripe button is offered on a Markdown buffer; an <em>open</em> window stays open on any other tab —
     * it says the file is not Markdown instead of closing, and fills again on the way back.
     */
    void activeBufferChanged(EditorBuffer buffer) {
        if (host.markdownLintToolWindow() == null) {
            return;
        }
        boolean markdown = buffer != null && buffer.isMarkdown();
        host.toolWindows()
                .setAvailable(host.markdownLintToolWindow(), markdownLintEnabled() && (markdown || lintWindowOpen()));
        if (lintWindowOpen()) {
            runMarkdownLintScan();
        }
    }

    /** Lints the active Markdown buffer once and fills the Lint tool window (off-thread). */
    void runMarkdownLintScan() {
        EditorBuffer b = host.activeBuffer();
        if (b == null || !b.isMarkdown() || !markdownLintEnabled()) {
            host.markdownLintPanel().showNotLinted(b == null ? null : b.getPath());
            return;
        }
        markdownLintService.validate(
                this, // the tool window's own request: it must not cancel a buffer's live lint
                b.getVisibleContent(), // what the editor shows: the same lines the squiggles are drawn on
                effectiveMarkdownLintDisabled(b),
                lint().options(b),
                diags -> showInLintPanel(b, diags));
    }

    /** Fills the Lint tool window with {@code buffer}'s diagnostics, when it is still the active buffer. */
    private void showInLintPanel(
            EditorBuffer buffer, java.util.List<com.editora.markdown.MarkdownLint.Diagnostic> diags) {
        if (host.activeBuffer() == buffer && lintWindowOpen()) {
            host.markdownLintPanel().setResults(buffer.getPath(), buffer.getTitle(), diags, lint().configNote(buffer));
        }
    }

    /**
     * Lints {@code buffer}'s {@code text} for its live overlay (only a newer request for the same buffer
     * supersedes it) and mirrors the result into the Lint tool window while the buffer is the active one.
     */
    void lintMarkdown(
            EditorBuffer buffer,
            String text,
            java.util.function.Consumer<java.util.List<com.editora.markdown.MarkdownLint.Diagnostic>> onResult) {
        markdownLintService.validate(
                buffer, text, effectiveMarkdownLintDisabled(buffer), lint().options(buffer), diags -> {
                    onResult.accept(diags);
                    showInLintPanel(buffer, diags);
                });
    }

    /** Toggles the Markdown Lint tool window; opening it auto-scans via {@code focusFirstItem}. */
    void toggleMarkdownLintWindow() {
        host.toolWindows().toggle(host.markdownLintToolWindow());
    }

    /**
     * The rule codes disabled for {@code buffer}: the user's Settings list, minus the rules its project's
     * markdownlint config switches on by name (the project's choice wins inside the project), plus the rules
     * that config switches off. A narrowed buffer shows a fragment, so the two rules about the file's first and
     * last line are off there.
     */
    java.util.Set<String> effectiveMarkdownLintDisabled(EditorBuffer buffer) {
        java.util.Set<String> off = settingsDisabledRules();
        java.nio.file.Path path = buffer == null ? null : buffer.getPath();
        if (path != null && com.editora.vfs.Vfs.isLocal(path)) {
            MarkdownLintActions.ConfigHit hit = lint().configFor(path);
            if (hit != null) {
                off.removeAll(hit.enabled());
                off.addAll(hit.disabled());
            }
        }
        if (buffer != null && buffer.isNarrowed()) {
            off.addAll(java.util.List.of("MD041", "MD047"));
        }
        return off;
    }

    /** The rule codes switched off in Settings, normalized. */
    java.util.Set<String> settingsDisabledRules() {
        java.util.Set<String> off = new java.util.HashSet<>();
        for (String code : host.config().getSettings().getMarkdownLintDisabledRules()) {
            if (code != null && !code.isBlank()) {
                off.add(code.strip().toUpperCase(java.util.Locale.ROOT));
            }
        }
        return off;
    }

    /** The rules the nearest markdownlint config above {@code file} (inside its project) turns off. */
    java.util.Set<String> markdownLintConfigDisabled(java.nio.file.Path file) {
        MarkdownLintActions.ConfigHit hit = lint().configFor(file);
        return hit == null ? java.util.Set.of() : hit.disabled();
    }

    /**
     * What the active buffer's project markdownlint config does to the rule list, as one localized line for
     * display ("2 rules disabled by .markdownlint.json", or which rules it switches on over Settings); "" when no config applies (no file, not Markdown, a
     * remote or unsaved buffer, or a config that changes nothing). The Lint tool window and the Settings card
     * both show it; call on the FX thread.
     */
    String markdownLintConfigNote() {
        EditorBuffer b = host.activeBuffer();
        return b == null || !b.isMarkdown() ? "" : lint().configNote(b);
    }

    /** Applies the safe-to-automate Markdown-lint fixes to the active buffer (undoable). */
    void fixMarkdownLint() {
        lint().fixAll();
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

    /** A line's worth of the preview, as a fraction of its viewport (line up / line down). */
    static final double PREVIEW_LINE = 0.08;

    /** Scrolls the active buffer's preview by {@code pages} viewports when it is the scroll target: with
     *  only the preview showing, line and start/end navigation move what is on screen, not a hidden caret. */
    boolean scrollActivePreview(double pages) {
        EditorBuffer b = host.activeBuffer();
        return b != null && b.scrollPreview(pages);
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
            restorePreviewLayout(buffer);
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

    /** Gives {@code buffer} the preview zoom and Split divider remembered for its file, and has later changes
     *  of either stored (the default pair is not: a file left as it came takes no entry). */
    private void restorePreviewLayout(EditorBuffer buffer) {
        Path file = buffer.getPath();
        var layouts = host.config().getWorkspaceState().getMarkdownPreviewLayouts();
        String saved = file == null ? null : layouts.get(file.toString());
        buffer.restorePreviewLayout(saved == null ? null : com.editora.editor.PreviewLayout.parse(saved), () -> {
            Path now = buffer.getPath();
            if (now == null) {
                return;
            }
            com.editora.editor.PreviewLayout layout = buffer.getPreviewLayout();
            if (layout.isDefault()) {
                layouts.remove(now.toString());
            } else {
                layouts.put(now.toString(), layout.text());
            }
            host.requestSave();
        });
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
        EditorBuffer b = editableMarkdown();
        if (b != null) {
            action.accept(b);
        }
    }

    /**
     * The active buffer when a Markdown editing command can run on it, else null after saying why not: not a
     * Markdown file, or a read-only one (which is not "not a Markdown file"). The commands edit at one caret,
     * so extra carets are dropped first — with a message, rather than silently changing one line of several.
     */
    private EditorBuffer editableMarkdown() {
        EditorBuffer b = host.activeBuffer();
        if (b == null || !b.isMarkdown()) {
            host.setStatus(tr("status.notMarkdown"));
            return null;
        }
        if (!b.canFormatMarkdown()) {
            host.setStatus(tr("status.lsp.readOnly"));
            return null;
        }
        if (!host.editing().activeEditable()) {
            return null; // only the preview is showing: said so in the status line
        }
        if (b.hasMultipleCarets()) {
            b.collapseCarets();
            host.setStatus(tr("status.markdown.multiCaret"));
        }
        return b;
    }

    /**
     * Runs a table command that needs the caret in a table, and says why when it did nothing: the caret is
     * not on a table, or ({@code refusedKey}) the command does not apply to that row or column.
     */
    void markdownTable(java.util.function.Predicate<EditorBuffer> command, String refusedKey) {
        EditorBuffer b = editableMarkdown();
        if (b != null && !command.test(b)) {
            host.setStatus(tr(b.markdown().caretInTable() ? refusedKey : "status.markdown.notTable"));
        }
    }

    /** Opens the table-size grid picker; on pick, inserts a fresh GFM table into the active Markdown buffer. */
    void markdownInsertTable() {
        EditorBuffer b = editableMarkdown();
        if (b == null) {
            return;
        }
        showTableSizePicker((rows, cols) -> b.insertTable(rows, cols));
    }

    /**
     * The command-palette path: a keyboard-only {@code RxC} size prompt (e.g. {@code "4x4"}) instead of the
     * mouse grid picker. The grid picker stays the format-bar button / right-click "Insert Table" UI.
     */
    void markdownInsertTableViaText() {
        EditorBuffer b = editableMarkdown();
        if (b == null) {
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
     * The table-size grid ({@link TableSizePicker}) as an in-scene overlay under the caret of the active
     * editor: hover and click, or arrow keys and Enter, to pick {@code R × C} (rows include the header). Max
     * {@value #TABLE_PICKER_MAX_ROWS} × {@value #TABLE_PICKER_MAX_COLS}.
     */
    void showTableSizePicker(java.util.function.BiConsumer<Integer, Integer> onPick) {
        TableSizePicker picker =
                new TableSizePicker(TABLE_PICKER_MAX_ROWS, TABLE_PICKER_MAX_COLS, host.overlayHost()::hide, onPick);
        Runnable focus = picker.card()::requestFocus; // or the editor keeps the keyboard under the scrim
        EditorBuffer b = host.activeBuffer();
        org.fxmisc.richtext.CodeArea area = b == null ? null : b.getFocusedArea();
        javafx.geometry.Bounds caret =
                area == null ? null : area.getCaretBounds().orElse(null);
        javafx.geometry.Bounds inScene =
                caret == null || area.getScene() == null ? null : area.localToScene(area.screenToLocal(caret));
        if (inScene == null) {
            host.overlayHost().show(picker.card(), true, focus, () -> {}); // no caret on screen: centered
        } else {
            host.overlayHost()
                    .showAt(picker.card(), inScene.getMinX(), inScene.getMaxY(), inScene.getMinY(), focus, () -> {});
        }
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
        if (!host.editing().activeEditable()) {
            return; // only the preview is showing
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
        EditorBuffer b = editableMarkdown();
        if (b == null) {
            return;
        }
        if (!b.reflowTable()) {
            host.setStatus(tr("status.markdown.notTable"));
        }
    }

    /** {@code markdown.toc}: insert a table of contents at the caret, or regenerate the existing TOC block. */
    void markdownToc() {
        EditorBuffer b = editableMarkdown();
        if (b == null) {
            return;
        }
        if (!b.insertOrUpdateToc()) {
            host.setStatus(tr("status.markdown.tocNoHeadings"));
        } else {
            host.setStatus(tr("status.markdown.tocDone"));
        }
    }

    /** {@code markdown.tableFromCsv}: convert the selected CSV (else clipboard CSV) into a GFM table. */
    void markdownTableFromCsv() {
        EditorBuffer b = editableMarkdown();
        if (b == null) {
            return;
        }
        if (!b.tableFromCsv()) {
            host.setStatus(tr("status.markdown.csvEmpty"));
        }
    }

    /** {@code markdown.tableToCsv}: copy the caret's GFM table to the clipboard as CSV. */
    void markdownTableToCsv() {
        EditorBuffer b = editableMarkdown();
        if (b == null) {
            return;
        }
        if (!b.tableToCsv()) {
            host.setStatus(tr("status.markdown.notTable"));
        } else {
            host.setStatus(tr("status.markdown.csvCopied"));
        }
    }

    /** {@code markdown.tableExport*}: file-export the caret's GFM table ({@code format} = csv|xlsx|ods). */
    void markdownTableExport(String format) {
        EditorBuffer b = editableMarkdown();
        if (b == null) {
            return;
        }
        if (!b.exportTableFile(format)) {
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
        if (!NoUndoGuard.allow(b, tr(align ? "command.csv.align" : "command.csv.shrink"))) {
            return;
        }
        b.replaceWholeDocument(out); // computed from getContent(): widens a narrowed buffer first (undoable)
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
        // Three states, so the toggle can let go again: follow the app → the other theme → the app's own,
        // pinned → follow the app.
        String next = com.editora.editor.PreviewButtons.nextTheme(mode, host.appThemeDark());
        host.config().getSettings().setMarkdownPreviewTheme(next);
        host.requestSave();
        applyMarkdownPreviewTheme();
        if (host.settingsWindow() != null) {
            host.settingsWindow().syncAll(); // the Markdown page shows the same three-state choice
        }
        host.setStatus(
                tr("status.markdownPreviewTheme", tr("markdown.previewTheme." + (next.isEmpty() ? "follow" : next))));
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
