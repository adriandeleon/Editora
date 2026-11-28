package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javafx.scene.control.Tab;

import com.editora.config.Settings;
import com.editora.editor.EditorBuffer;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * What every buffer of a window is wired to before it is shown: its handlers, settings and feature coordinators,
 * then its tab's header, listeners and menu, for a new tab and for a restored placeholder that gets its editor
 * late. The buffers and tabs stay with {@link MainController}, which adds them. It lived inside {@code
 * MainController}; it is a file of its own only because of that file's size (see {@link WindowCommandHost}).
 */
final class WindowBufferWiring {

    private final MainController mc;

    WindowBufferWiring(MainController mc) {
        this.mc = mc;
    }

    /** The .http ▶ tooltip: "Run this request (C-M-r) · Run all requests: C-M-S-enter", with the keymap's chords. */
    static String httpRunTooltip(com.editora.command.KeymapManager keymap) {
        String run = keymap == null ? null : keymap.displayChord("http.runRequest");
        String all = keymap == null ? null : keymap.displayChord("http.runFile");
        if (run != null && !run.isEmpty() && all != null && !all.isEmpty()) {
            return tr("editmenu.runHttpRequest.tip.chords", run, all);
        }
        return ChordHint.tr(keymap, "editmenu.runHttpRequest.tip", "http.runRequest");
    }

    /**
     * Gives a restored placeholder tab its editor: the wiring of {@link MainController#addBuffer}, into the {@link Tab} that
     * already holds the file's place (pin, group, MRU slot and selection stay). {@code shown}: the tab is on
     * screen, so the editor attaches now; otherwise it stays detached ({@link DeferredTabContent}) and
     * unhighlighted ({@link EditorBuffer#setHighlightDeferred}) until the tab is first selected.
     */
    Tab attachRestoredBuffer(Tab tab, EditorBuffer buffer, boolean shown) {
        wireBuffer(buffer, com.editora.editorconfig.EditorConfigProperties.EMPTY); // prepared on the worker
        if (!shown) {
            DeferredTabContent.defer(buffer.getNode()); // filled in the background, laid out when shown
        }
        tab.setContent(null); // the placeholder's empty node: a deferred attach only fills an empty tab
        DeferredTabContent.install(tab, buffer.getNode());
        if (!shown) {
            // Registered after install, which is what makes the tab deferred: before it, whenShown runs at once.
            buffer.setHighlightDeferred(true);
            DeferredTabContent.whenShown(tab, () -> buffer.setHighlightDeferred(false));
        }
        tab.setUserData(buffer);
        attachBufferTab(tab, buffer);
        // What the tabs listener does for a tab added in the background: the placeholder was the tab that
        // was added, so the buffer gets it here.
        if (!shown) {
            buffer.setRenderingActive(false);
        }
        mc.recovery.track(buffer);
        DeferredTabContent.arm(tab);
        return tab;
    }

    /** A restored file's placeholder tab: title, icon, pin, no editor until {@link #attachRestoredBuffer}. */
    Tab addPlaceholderTab(RestorePlaceholder placeholder) {
        Tab tab = mc.addContentTab(placeholder, false);
        if (placeholder.entry().isPinned()) {
            mc.pinned.add(tab);
            TabHeaders.toggleClass(tab, "pinned", true);
            mc.tabHeaders.contentHeader(tab, placeholder); // again, now that the pin is known
        }
        // The tab menu is a buffer's; opening it on a placeholder builds the editor first.
        LazyContextMenu.install(tab, menu -> {
            EditorBuffer built = mc.ensureBuffer(tab);
            if (built != null) {
                TabContextMenu.build(mc, tab, built, menu);
            }
        });
        return tab;
    }

    /** Everything a buffer is wired to before it has a tab: handlers, settings, feature coordinators.
     *  {@code editorConfig} null: resolve the file's {@code .editorconfig} here, an ancestor walk on FX. */
    void wireBuffer(EditorBuffer buffer, com.editora.editorconfig.@Nullable EditorConfigProperties editorConfig) {
        // Spell checking: share the user dictionary + persist "Add to Dictionary" (before applyViewSettings,
        // which sets the per-file language and enables checking).
        mc.editorSettings.spell().wire(buffer);
        mc.editorSettings.applyViewSettings(buffer, editorConfig);
        buffer.getFoldManager().setOnFoldStateChanged(() -> mc.fileState.persistFolds(buffer));
        buffer.setOnBookmarksChanged(() -> mc.bookmarkCoordinator.schedulePersistBookmarks(buffer));
        buffer.setBookmarkToggleRequest(mc.bookmarkCoordinator::onBookmarkToggleRequest);
        buffer.setOnNotesChanged(() -> mc.notesCoordinator.schedulePersistNotes(buffer));
        buffer.setOnNarrowChanged(() -> {
            mc.editing.afterNarrowChanged(buffer);
            if (mc.structurePanel != null) {
                mc.structurePanel.narrowChanged(buffer); // its Widen / Defun / Fold segments mirror the buffer
            }
        });
        NoUndoGuard.install(buffer, mc.noUndoGuard);
        buffer.setNoteMarkerClick(mc.notesCoordinator::onNoteMarkerClick);
        mc.reviewCoordinator.wire(buffer);
        buffer.setGutterBlameClick(mc.git::onGutterBlameClick);
        mc.todoCoordinator.applyToBuffer(buffer); // push the compiled TODO/highlight matcher (on by default)
        // No-project window: the TODO window shows only the active tab and hides while it has no TODOs.
        buffer.setOnTodoMarksChanged(() -> {
            if (mc.activeBuffer() == buffer) {
                mc.windowGates.updateTodoToolWindow();
                mc.todoCoordinator.activeBufferTodosChanged(buffer);
            }
        });
        // Refresh the Run button when this buffer's runnable status flips (only acts if it's active).
        buffer.setOnRunnableChanged(() -> {
            if (mc.activeBuffer() == buffer) {
                mc.updateRunButton();
            }
        });
        buffer.addLanguageListener(
                () -> { // a picked language or a typed shebang: the status bar says so at once
                    // .editorconfig rules depend on the language (a shell script keeps LF and no BOM).
                    mc.editorSettings.applyEditorConfig(buffer);
                    if (mc.activeBuffer() == buffer) {
                        mc.statusBar.refresh();
                    }
                    Tab tab = mc.tabFor(buffer);
                    if (tab != null) {
                        mc.updateTabMeta(tab, buffer); // the tab's file-type icon follows the language
                    }
                });
        buffer.setRunHandler(mc.runCoordinator::runActiveFile); // "Run File" editor right-click item (runnable files)
        boolean local = mc.isLocalBuffer(buffer);
        // A remote (SFTP) file runs on its host; whether that host may run commands is asked at launch, and a
        // refusal is reported then.
        buffer.setRunEnabled(local || com.editora.vfs.Vfs.isRemote(buffer.getPath()));
        buffer.setAdminEditAvailable(
                mc.fileWorkflows.elevationAvailable() && local); // "Edit as Administrator" on a locked file
        // .http: the request ▶, the editor menu's Run All Requests (active buffer), the running request's ■
        buffer.setHttpHooks(new com.editora.editor.http.HttpHooks(
                line -> mc.httpClient.runRequest(buffer, line),
                mc.httpClient::runFile,
                () -> mc.httpClient.cancelRun(buffer),
                () -> httpRunTooltip(mc.keymap),
                com.editora.editor.http.HttpHooks.NO_LINT,
                null));
        buffer.setHttpDebugHandler(line -> mc.httpClient.debugRequest(buffer, line)); // editor menu → Debug Request
        mc.httpVariableHints.wire(buffer); // {{variable}} squiggles + {{ completion (on for .http buffers)
        JsonLinesMarks.wire(buffer); // a JSON Lines line that is not one JSON value: squiggled
        buffer.setHttpEnabled(mc.httpClient.isEnabled() && local);
        buffer.setMakeRunHandler(target -> mc.runCoordinator.runMakeTarget(buffer, target)); // Makefile target ▶
        buffer.setTestRunHandler(
                t -> mc.testNavigation.runSingleTest(buffer, t)); // JUnit → build tool; Python → pytest
        buffer.setTestDebugHandler(
                mc.testNavigation::debugSingleTest); // editor menu → suspended test + debugger attach
        mc.testNavigation.applyTestGutter(
                buffer); // gated by the Test Runner feature + a detected JVM (Maven/Gradle) project
        buffer.setMainRunHandler(m -> mc.runCoordinator.runMainClassNamed(m.fqn())); // project main() ▶ → run
        buffer.setMainDebugHandler(m -> mc.debugCoordinator.debugMainClassNamed(m.fqn())); // editor menu → debug
        mc.testNavigation.applyMainGutter(
                buffer); // gated by not-Simple + local + JVM project + Java run/debug available
        mc.debugCoordinator.wireBuffer(buffer);
        buffer.setAddNoteHandler(mc.notesCoordinator::addNoteFromContext);
        buffer.setNotesEnabled(mc.notesCoordinator.isEnabled());
        java.util.function.Function<Path, @Nullable EditorBuffer> open = mc::openBufferFor;
        buffer.setOpenUrlHandler(u -> HeadingLinks.open(
                u,
                buffer,
                mc.windowProjectRoot(),
                mc.hostServices,
                mc.fileWorkflows,
                open,
                mc::openAndGoto,
                mc::setStatus));
        buffer.setAiActionHandlers(
                mc.aiCoordinator::explainSelection, mc.aiCoordinator::rewriteSelection); // AI sel. bar
        buffer.setTableFileExporter(mc.previews::exportMarkdownTableFile); // Markdown table → CSV/Excel/ODS file
        buffer.setInsertTableHandler(mc.previews::markdownInsertTable); // Markdown format-bar "insert table" button
        buffer.setInsertTypstTableHandler(
                () -> mc.previews.showTableSizePicker(buffer::insertTypstTable)); // Typst #table
        buffer.setTypstImageHandler(() -> mc.editing.insertImageFromChooser(buffer)); // Typst "Insert Image" menu
        buffer.previewMenu().setExportPng(mc.typst::exportPng); // Typst preview right-click → PNG
        buffer.previewMenu().setExportSvg(mc.typst::exportSvg); // Typst preview right-click → SVG
        mc.htmlPreview.wireBuffer(
                buffer); // HTML Live Preview: settled edits reload a served site; globe follows language
        buffer.setFormatBarEnabled(mc.config.getSettings().isMarkdownFormatBar());
        buffer.previewMenu().setExportPdf(mc.exports::exportPreviewPdf); // preview right-click menu
        buffer.previewMenu().setPrint(mc.exports::printPreview);
        buffer.setPomViewToggleHandler(mc.previews::togglePomView); // pom preview right-click: summary ⇄ XML tree
        buffer.previewMenu().setExportDocx(mc.exports::exportPreviewDocx); // preview → MS Word
        buffer.previewMenu().setExportOdt(mc.exports::exportPreviewOdt); // preview → OpenDocument
        buffer.previewMenu().setExportJson(mc.exports::exportMarkwhenJson); // Markwhen preview → JSON
        buffer.setTypstRootResolver(mc::resolveTypstRoot); // typst --root: nearest typst.toml / project root
        buffer.setOnMarkwhenViewChanged(
                () -> mc.previews.persistMarkwhenView(buffer)); // persist timeline/calendar choice
        buffer.setOnEnableEditing(() -> mc.enableEditing(buffer)); // "Enable Editing" banner button
        mc.snippetCoordinator.wireBuffer(buffer);
        buffer.setCompletionProvider(mc.completion::complete);
        buffer.setAiCompletionProvider(mc.aiCoordinator::inlineComplete);
        buffer.setAiCompletionEnabled(mc.aiCoordinator.isInlineCompletionEnabled());
        // A Maven submenu only on a pom.xml — mavenMenu itself enforces that, so this surface and the
        // project tree's cannot disagree about when it appears.
        buffer.setBuildMenuContributor(() -> {
            javafx.scene.control.Menu maven = mc.mavenProjectCoordinator.mavenMenu(buffer.getPath());
            if (maven != null) {
                return List.of(maven);
            }
            List<javafx.scene.control.MenuItem> items =
                    new ArrayList<>(mc.previews.lint().editorMenu(buffer));
            items.addAll(mc.runCoordinator.editorMenuItems(buffer)); // Run with Trace on a runnable shell script
            return items;
        });
        buffer.setMenuContributor(
                () -> { // External Tools submenu + plugin-contributed right-click items
                    List<javafx.scene.control.MenuItem> extra = new ArrayList<>(
                            mc.exports.editorMenuItems(buffer, mc.externalToolCoordinator.editorMenuItems()));
                    extra.addAll(mc.pluginCoordinator.editorMenuItems(buffer));
                    return extra;
                });
        Settings acs = mc.config.getSettings();
        buffer.setAutocomplete(
                acs.isAutocomplete(),
                acs.isAutocompleteProse(),
                acs.isAutocompleteSnippets(),
                mc.mermaid.effectiveAutocomplete());
        buffer.setMultiCaretEnabled(
                mc.editorSettings
                        .multiCaretEnabled()); // multiple cursors + Alt+drag column selection (off in Simple UI mode)
        mc.mermaid.wireBuffer(buffer); // live maid validator + initial lint state
        // Markdown linting: the overlay gets the diagnostics; the Lint tool window mirrors them live when
        // this buffer is the active one and the window is open.
        buffer.setMarkdownLintValidator((text, cb) -> mc.previews.lintMarkdown(buffer, text, cb));
        buffer.setMarkdownLintEnabled(mc.previews.markdownLintEnabled());
        buffer.setImageDropHandler(
                files -> mc.editing.insertDroppedImages(buffer, files)); // drag image → assets/ + ![](…)
        buffer.setWebImageDropHandler(
                (image, url) -> mc.editing.insertWebImage(buffer, image, url)); // browser image → assets/
        // LSP: wire didChange/diagnostics/completion/format/nav hooks + open+activate if eligible.
        mc.lspCoordinator.wireBuffer(buffer);
        mc.previews.ensurePreviewControls(buffer);
        mc.htmlPreview.ensureControl(buffer); // the floating "open in browser" globe (HTML buffers, feature on)
        mc.logViewer.ensureControl(buffer); // the floating Follow / level / regex control (log buffers, feature on)
    }

    /** The per-tab part of {@link MainController#addBuffer}: header, dirty/edit/selection listeners and the tab menu. */
    void attachBufferTab(Tab tab, EditorBuffer buffer) {
        mc.updateTabMeta(
                tab, buffer); // replaces the default text/icon with the buffer header (drag handle, pin, dirty)
        buffer.dirtyProperty().addListener((obs, was, now) -> {
            mc.updateTabMeta(tab, buffer);
            if (buffer == mc.activeBuffer()) {
                mc.toolbar.refreshEditState(); // save enablement (e.g. after markClean())
            }
            if (mc.projectPanel != null) {
                mc.projectPanel.refreshModified(); // reflect the dirty marker in the Project file tree
            }
        });
        // Auto save (after-delay mode): each edit restarts the idle timer; cheap (no full-text build).
        buffer.getArea().multiPlainChanges().subscribe(c -> {
            if (FileWorkflowCoordinator.AUTOSAVE_DELAY.equals(mc.fileWorkflows.autoSaveMode())) {
                mc.fileWorkflows.autoSaveIdleTimer.playFromStart();
            }
            if (buffer == mc.activeBuffer()) {
                mc.toolbar.scheduleEditStateRefresh(); // edits change undo/redo (and dirty) availability
            }
        });
        // Selection changes toggle cut/copy enablement (no clipboard read here — that's refreshPasteState).
        buffer.onSelectionChanged(() -> {
            if (buffer == mc.activeBuffer()) {
                mc.toolbar.scheduleEditStateRefresh(); // of the split pane in use
            }
        });
        installTabMenu(tab, buffer);
    }

    /** Builds and attaches the right-click context menu for a tab. */
    private void installTabMenu(Tab tab, EditorBuffer buffer) {
        LazyContextMenu.install(tab, menu -> TabContextMenu.build(mc, tab, buffer, menu)); // built on first use
    }
}
