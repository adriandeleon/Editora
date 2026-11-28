package com.editora.editor;

import java.time.Duration;

import javafx.application.Platform;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.AnchorPane;

import com.editora.editops.HtmlLinks;
import com.editora.editor.EditorBuffer.MarkdownViewMode;
import com.editora.editor.fold.FoldManager;
import com.editora.editor.highlight.HighlightDirty;
import com.editora.editor.http.HttpVariableFixes;
import com.editora.editor.lsp.LspEditPlacement;
import com.editora.editor.lsp.LspEditShift;
import com.editora.editor.markdown.MarkdownLintHover;
import com.editora.editor.markdown.MermaidLintHover;
import com.editora.editor.markdown.PreviewSurfaces;
import com.editora.editor.minimap.Minimap;
import com.editora.editor.spell.SpellCheckOverlay;
import com.editora.editor.undo.UndoMerge;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.TwoDimensional.Bias;

/**
 * The listener wiring every {@link EditorBuffer} gets at construction: the gutter's hooks, the document-change
 * subscriptions (undo grouping, the change stamp, the mark ring, dirty tracking), the settled-edit milestones, the
 * per-view selection, scroll and Ctrl/Cmd-click listeners, and the eager overlays over the text; and {@link
 * #dispose()}, which takes it down again when the tab closes.
 *
 * <p>Moved out of {@code EditorBuffer} for size; the buffer's constructor calls {@link #install()} at the point
 * its body used to run, so the order of construction is unchanged.
 */
final class BufferWiring {

    private final EditorBuffer b;

    BufferWiring(EditorBuffer b) {
        this.b = b;
    }

    /**
     * The constructor's work up to the preview scroll sync, which the buffer assigns itself (NullAway checks
     * that its constructor sets it), before calling {@link #installOverlays()}.
     */
    void install() {
        b.refreshGutter();
        TabStops.apply(b.viewHost, b.tabSize); // JavaFX's own tab stop is 8 columns; ours starts at the default 4
        // Gutter click: route to the injectable handler (the controller adds, or confirms a removal);
        // defaults to a plain toggle so the editor works standalone (and in tests).
        b.folds.setBookmarkHooks(b.bookmarks::isBookmarked);
        b.folds.setBulbHooks(
                b.bulb::kindAt,
                line -> b.bulb.click(line, b.lspCodeActionsAction), // Alt+Enter's list on the bulb's line
                b.bulb::tooltip);
        b.folds.setSplitViews(() -> b.focusedArea, () -> b.area2);
        // Personal-Notes markers are drawn inline at each note's start by noteOverlay (no gutter slot): a change
        // to the note set repaints the overlay, never the gutter (the overlay follows edits on its own).
        b.notes.setOnSpansChanged(b.noteOverlay::refresh);
        b.gitLines.attach(b.folds); // change bars: slot reserved only while tracking is on; hunk text on hover
        // Gutter Run glyph: reserved for a runnable file — one entry line for a script, or one per
        // request for a .http file.
        b.folds.setRunHooks(
                () -> b.runGutter.runnable || !b.runGutter.mainLines.isEmpty(),
                b.runGutter::isRunGlyphLine,
                b.runGutter::onRunGlyph,
                line -> b.runGutter.testLines.containsKey(line)
                        ? "test-run-marker"
                        : line == b.httpGutter.runningGlyphLine(b::isFoldedLine)
                                ? FoldManager.HTTP_RUNNING_CLASS
                                : b.httpResults.glyphClass(line), // the ▶ of a request that ran: its outcome
                b.runGutter::runGlyphTooltip);
        // Gutter breakpoint strip: reserved only while debugging is enabled; click toggles a breakpoint.
        b.folds.setBreakpointHooks(
                () -> b.debugEnabled,
                b.breakpoints::hasGlyph, // a line breakpoint, or a method breakpoint's / field watchpoint's mark
                b.breakpoints::glyphClasses,
                line -> b.gutterBreakpointClick.accept(b, line));
        b.folds.setBreakpointShiftClick(line -> b.gutterLogpointClick.accept(b, line)); // IntelliJ's logpoint gesture
        b.folds.setBreakpointTooltip(b.breakpoints::glyphTooltip);
        b.folds.setDebugGutterHooks(
                i -> b.executionLine.isMarked(b.area, i), (l, e) -> b.gutterBreakpointMenu.show(b, l, e));
        b.folds.setRunToHereHooks(
                i -> b.gutterRunToHere != null
                        && !b.executionLine.isMarked(b.area, i)
                        && b.gutterRunToHere.offeredAt(b, i),
                (i, force) -> {
                    if (b.gutterRunToHere != null) {
                        b.gutterRunToHere.run(b, i, force);
                    }
                },
                i -> b.gutterRunToHere == null ? "" : b.gutterRunToHere.tooltip(b, i));
        // Gutter blame "Annotate" column (leftmost): reserved only while blame is on; the per-line
        // author/date/heatmap come from the controller-supplied list, click shows that line's commit.
        b.folds.setBlameHooks(
                b::isBlameOn,
                b.gitLines::blameAt,
                () -> b.blameColumnWidth,
                line -> b.gutterBlameClick.accept(b, line));
        b.breakpoints.setOnLinesRepaint(lines -> Platform.runLater(() -> lines.forEach(b::refreshGutterLine)));
        b.typingAids.addViewModePaging(b.area); // Space/Backspace = page down/up while in read-only View mode
        b.completionActions.addCompletionKeys(b.area); // popup owns Enter/Tab before snippet and indentation filters
        b.completionActions.installCommitCharacters(b.area);
        b.snippetKeys.addSnippetKeys(b.area); // Tab expands/cycles snippets (else falls through to indent)
        b.snippetSession.setOnChanged(b.snippetKeys::snippetSessionChanged);
        b.focusedView.addListener((o, was, now) -> b.snippetSession.focusMovedTo(now)); // the other split view
        // Auto-close ()[]{} and quotes: before auto-indent, so it sees the keystroke first.
        b.typingAids.addAutoClose(b.area);
        b.typingAids.addAutoIndent(b.area); // Enter auto-indents; closers de-indent (per-language smart indent)
        b.completionActions.installCompletionTrigger(b.area);
        b.lspAssists.installOccurrenceTrigger(b.area); // LSP document highlight (#675)
        b.inlayDecor.installCodeLensClick(b.area);
        // When an edit shifts bookmarks, repaint the affected lines' gutter markers after the edit's own
        // graphic rebuild settles (deferred to the next pulse), so the moved marker follows its line.
        b.bookmarks.setOnLinesRepaint(lines -> Platform.runLater(() -> lines.forEach(b::refreshGutterLine)));
        b.area.getStyleClass().add("editor-area");
        b.area.setWrapText(false);
        b.area.setUndoManager(b.boundedUndoManager());
        // Word/line-level undo: end the current undo group after an edit that finishes a word or line, so
        // one C-z undoes a word/line rather than the whole typing burst (the idle break is built into the
        // manager via UndoMerge.PAUSE). Subscribe AFTER setUndoManager so the manager records the change
        // first; both views share the document, so this one subscription covers edits made in either.
        b.area.plainTextChanges().subscribe(c -> b.breakUndoGroupIfBoundary(c.getInserted(), c.getRemoved()));
        // Document-change stamp for the deferred/async completion paths. On plainTextChanges (emitted
        // synchronously by the edit) rather than the debounced stream, so a change is visible the instant it
        // happens; both views share the document, so this one subscription covers either. One long++ per
        // edit — off the per-keystroke cost budget.
        b.area.plainTextChanges().subscribe(c -> {
            b.docVersion++;
            // Drop our reference to the previous version immediately. In-flight background consumers keep
            // their own immutable String; the next settled consumer materializes the new version once.
            b.documentSnapshots.invalidate();
            if (b.isMarkdown() || b.markdownEditing.blockMap().holdsMap()) {
                // The line map the Markdown keys look up: notes the edited line; scanned off the FX thread when
                // typing pauses or a key asks (PERF-01; perf review 2026-11-23 E1).
                b.markdownEditing
                        .blockMap()
                        .changed(b.area.offsetToPosition(c.getPosition(), Bias.Forward)
                                .getMajor());
            }
            b.completionActions.documentChanged(c);
            if (!b.completionEditTrackers.isEmpty()) {
                int[] start = LspEditPlacement.position(b.area, c.getPosition());
                int[] before = LspEditPlacement.endAfter(start, c.getRemoved());
                int[] after = LspEditPlacement.endAfter(start, c.getInserted());
                var change = new LspEditShift.Change(start[0], start[1], before[0], before[1], after[0], after[1]);
                for (var tracker : b.completionEditTrackers)
                    tracker.changed(
                            c.getPosition(),
                            c.getRemoved().length(),
                            c.getInserted().length(),
                            change);
            }
        });
        // Mark ring: shift stored offsets across every edit so a mark still points at its text after
        // typing (one cheap pass over <=16 ints per edit; skipped when the ring is empty, the common case).
        b.area.plainTextChanges()
                .subscribe(c -> b.markRing.shift(
                        c.getPosition(),
                        c.getRemoved().length(),
                        c.getInserted().length()));
        b.area.setLineHighlighterFill(b.lineHighlightColor);
        // Track the changed range immediately (the settled dispatcher coalesces intermediate edits, so
        // the dirty range must be accumulated here), then re-highlight after a pause. Multi-change undo
        // reports each replacement in the coordinate space where that replacement ran, which is the
        // order HighlightDirty maps its range through them.
        configureSettledEditDispatcher();
        b.settledEditSub = b.area.multiPlainChanges().subscribe(changes -> {
            b.inlayDecor.shiftCodeLenses(changes);
            if (b.httpResults.shift(changes, b.area)) {
                b.inlayDecor.refreshInlayAreas(); // a request's result follows it when lines are added above
            }
            b.gitLines.edited(changes, b::refreshGutterLine, b.area2); // bars + blame follow inserted/removed lines
            for (var change : changes) {
                int removed = change.getRemoved().length();
                int inserted = change.getInserted().length();
                b.highlighter.highlightDirty.edited(change.getPosition(), removed, inserted);
                if (b.highlighter.semanticActive) {
                    b.highlighter.semanticDirty.edited(change.getPosition(), removed, inserted);
                }
                // The matched pair moves with the text, so the next re-match clears it where it now is: a
                // pass no longer restyles everything below an edit, which used to hide a stale position.
                int[] pair = b.completionActions.braceMatch();
                if (pair != null) {
                    pair[0] = HighlightDirty.moved(pair[0], change.getPosition(), removed, inserted);
                    pair[1] = HighlightDirty.moved(pair[1], change.getPosition(), removed, inserted);
                }
            }
            // The cached semantic tokens now point at shifted offsets; suppress the overlay until the
            // next response re-anchors them (one boolean write — off the per-char path's cost budget). The
            // generation bump invalidates any in-flight request so its (now-stale) response is dropped.
            if (b.highlighter.semanticActive) {
                b.highlighter.semanticGen++;
                b.highlighter.semanticStale = true;
            }
            // LSP diagnostics are anchored to absolute line/col and only replaced when the server re-pushes
            // (a debounced didChange + round-trip, ~300 ms+). Until then every keystroke would repaint the OLD
            // squiggles/stripe/minimap ticks at their OLD lines — underlining whatever text now sits there.
            // Clear them on the edit so nothing paints on shifted lines; setLspDiagnostics re-anchors on the
            // next push (which always follows an edit — jdtls republishes, pull servers re-pull) (#417).
            if (b.lspActive) {
                b.suppressStaleDiagnostics();
            }
            b.settledEdits.changed();
        });
        // After scrolling settles, re-request semantic tokens for the now-visible region (debounced so a
        // drag-scroll doesn't fire a request per frame). Inert unless semantic highlighting is active.
        b.semanticScrollDebounce.setOnFinished(e -> {
            if ((b.highlighter.semanticActive || b.inlayHintsActive) && b.semanticTokensRequester != null) {
                b.semanticTokensRequester.run(); // drives semantic tokens AND inlay hints (#681)
            }
        });
        // Dirty only when the content differs from the last saved/loaded text, so reverting an edit
        // (undo or manual) clears the marker. Driven off plainTextChanges (not textProperty): subscribing
        // to textProperty would force RichTextFX to materialize the whole document String on every
        // keystroke — O(n) allocation per char on a very large single buffer (e.g. minified JS on one
        // line, past the line-count heavy-file tier). See dirtyAfterEdit for when the text is compared.
        // A log filter or followed append rewrites the area without being a user edit: dirty is carried over.
        b.area.plainTextChanges()
                .filter(c -> !b.logView.adjusting())
                .subscribe(c -> b.dirtyAfterEdit(
                        c.getRemoved().length() == c.getInserted().length()));
        b.area.plainTextChanges().subscribe(c -> b.wrap.inserted(c.getInserted())); // a giant token holds wrap off
        // Auto-fill: break the line at a word boundary when it grows past the fill column (off by default,
        // so the very first check short-circuits for every buffer that hasn't turned it on).
        b.area.plainTextChanges().subscribe(b.typingAids::maybeAutoFill);
        // Abbrev auto-expand: when abbrev-mode is on, typing a word terminator expands the word before it.
        b.area.plainTextChanges().subscribe(b.snippetKeys::maybeExpandAbbrev);
        b.area.caretPositionProperty().addListener((obs, old, now) -> {
            b.resetGoalColumn();
            b.scheduleBraceMatch();
            b.bulb.schedule();
        });
        b.area.focusedProperty().addListener((obs, was, now) -> {
            if (now) {
                b.focusedArea = b.area;
                b.focusedView.set(b.area);
            }
        });
        b.editorMenu.installContextMenu(b.area);
        installFormatBarListeners(b.area);
    }
    /**
     * Registers every document-level idle milestone on one timer sequence. Feature predicates are checked
     * before a milestone is armed and again before its actions run, so inactive services add no timer work.
     * The order within a shared delay matches the former independent ReactFX subscriptions.
     */
    private void configureSettledEditDispatcher() {
        // The fold detection, the minimap's render and the spell checker's code-line scan had a ReactFX timer
        // each (two more with a split), restarted on every keystroke; they run from this one instead (E10).
        b.folds.settleExternally();
        b.settledEdits.at(FoldManager.SETTLE, () -> true, b.folds::editsSettled);
        b.minimap.settleExternally();
        b.settledEdits.at(Minimap.SETTLE, () -> true, b.minimap::editsSettled);
        b.spellOverlay.settleExternally();
        b.settledEdits.at(SpellCheckOverlay.SETTLE, () -> true, b.spellOverlay::editsSettled);
        b.settledEdits.at(Duration.ofMillis(150), () -> true, () -> {
            b.resolveDirty();
            b.sealBaselineIfDirty();
            b.highlighter.applyHighlighting();
            b.runGutter.recomputeRun(true); // re-evaluate the Run glyph when a top-level main / __main__ appears/leaves
            b.markdownEditing.blockMap().settled(); // the Markdown line map, scanned per pause, not per key (E1)
        });
        b.settledEdits.at(
                Duration.ofMillis(250),
                () -> b.htmlPreviewDirtyListener != null
                        || (b.previewLive() && b.markdownViewMode != MarkdownViewMode.EDITOR),
                () -> {
                    if (b.htmlPreviewDirtyListener != null) {
                        b.htmlPreviewDirtyListener.run();
                    }
                    if (b.previewLive() && b.markdownViewMode != MarkdownViewMode.EDITOR) {
                        b.previewPanes.scheduleRenderPreview();
                    }
                });
        b.settledEdits.at(
                Duration.ofMillis(90),
                () -> b.lspActive
                        && b.completionActions.autocompleteEnabled()
                        && b.focusedArea != null
                        && b.focusedArea.isFocused(),
                () -> b.completionActions.updateCompletion(b.focusedArea, false));
        b.settledEdits.at(
                Duration.ofMillis(280),
                () -> !b.lspActive
                        && b.completionActions.autocompleteEnabled()
                        && b.focusedArea != null
                        && b.focusedArea.isFocused(),
                () -> b.completionActions.updateCompletion(b.focusedArea, false));
        b.settledEdits.at(
                Duration.ofMillis(300),
                () -> b.todoMarker.todoEnabled || b.lspActive || b.highlighter.semanticActive || b.inlayHintsActive,
                () -> {
                    if (b.todoMarker.todoEnabled) {
                        b.todoMarker.refreshTodoMarks();
                    }
                    if (b.lspActive) {
                        b.sendLspChange();
                    }
                    if (b.lspActive && b.lspDiagnosticsRequester != null) {
                        b.lspDiagnosticsRequester.run();
                    }
                    if ((b.highlighter.semanticActive || b.inlayHintsActive) && b.semanticTokensRequester != null) {
                        b.semanticTokensRequester.run(); // drives semantic tokens AND inlay hints (#681)
                    }
                });
        b.settledEdits.at(
                UndoMerge.PAUSE,
                () -> !b.disposed && !b.largeFile && b.area.getLength() <= EditorBuffer.UNDO_HISTORY_MAX_BYTES,
                b::captureUndoCheckpoint);
        b.settledEdits.at(
                Duration.ofMillis(450),
                () -> b.mermaidLintEnabled || b.markdownLintEnabled || b.httpVariableMarks.enabled(),
                () -> {
                    b.scheduleMermaidLint();
                    b.scheduleMarkdownLint();
                    b.httpVariableMarks.schedule();
                });
        b.settledEdits.at(
                Duration.ofMillis(600),
                () -> b.completionActions.aiCompletionEnabled() && b.focusedArea != null && b.focusedArea.isFocused(),
                () -> b.completionActions.maybeRequestAiCompletion(b.focusedArea));
    }
    /** Show/reposition the Markdown format bar + the AI selection-actions bar as the selection or scroll
     *  changes (each coalesced per pulse; {@link SelectionBars#updateFormatBar()} always runs first so the AI bar can
     *  stack above it when both apply to the same selection). */
    void installFormatBarListeners(CodeArea a) {
        b.markdownEditing.attach(a, b.selectionBars::scheduleFormatBar);
        // Ctrl/Cmd-hover underlines what the Ctrl/Cmd-click below follows; the underline's overlay is built on
        // the first link shown.
        DefinitionLinks links = new DefinitionLinks(a, b::probeDefinitionLink, b::definitionLinkOverlay);
        if (a == b.area) {
            b.definitionLinks = links;
        }
        a.selectionProperty().addListener((obs, old, now) -> {
            b.selectionBars.scheduleFormatBar();
            b.selectionBars.scheduleAiActionsBar();
            b.selectionChanged.run();
        });
        // One vertical-scroll listener for the four per-scroll features (each gated on its own flag and
        // coalesced per pulse), in the order the former separate listeners ran: RichTextFX notifies every
        // listener on every scroll step, so three of them cost three dispatches for one boolean each.
        a.estimatedScrollYProperty().addListener((obs, old, now) -> {
            b.selectionBars.scheduleFormatBar();
            b.selectionBars.scheduleAiActionsBar();
            b.scheduleStickyScroll();
            if (b.highlighter.semanticActive) {
                b.semanticScrollDebounce.playFromStart(); // re-request tokens for the scrolled-to region
            }
        });
        a.estimatedScrollXProperty().addListener((obs, old, now) -> {
            b.selectionBars.scheduleFormatBar();
            b.selectionBars.scheduleAiActionsBar();
        });
        a.focusedProperty().addListener((obs, was, now) -> {
            if (!now) {
                b.selectionBars.hideFormatBar();
                b.selectionBars.hideAiActionsBar();
            }
        });
        a.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (b.caretOps.multiCaretActiveOn(a)) { // suspend single-caret assists while multiple carets exist
                return;
            }
            if (e.getCode() == KeyCode.ESCAPE) {
                b.selectionBars.hideFormatBar(); // don't consume — let other Escape behavior run
                b.selectionBars.hideAiActionsBar();
            }
        });
        // Ctrl/Cmd-click: open a Markdown link in the browser, or (in a code buffer with a live language
        // server) jump to the definition of the clicked symbol — the IDE "go to definition" gesture.
        a.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
            if (!e.isShortcutDown() || e.getButton() != MouseButton.PRIMARY) {
                return;
            }
            try {
                int off = a.hit(e.getX(), e.getY()).getInsertionIndex();
                if (b.isMarkdown()) {
                    // Worked out off the FX thread (PERF-01): the link opens when its destination lands.
                    b.markdownEditing.linkAt(a, off, url -> {
                        if (url != null) {
                            b.openUrlHandler.accept(url);
                        }
                    });
                    return;
                }
                if (b.isLspActive()) {
                    // Move the caret to the clicked symbol + focus this area, then go-to-definition (the
                    // controller reads getFocusedArea()'s caret). Deferred so focus/caret settle first.
                    a.moveTo(off);
                    a.requestFocus();
                    Runnable go = b.lspGotoDefinitionAction;
                    javafx.application.Platform.runLater(go::run);
                    e.consume();
                } else if (HtmlLinks.isHtml(b.language)) {
                    // An HTML link without a server (B6): the page is parsed whole, so the link is looked for off
                    // the FX thread (perf review 2026-11-23 E11) and followed when it lands, if nothing was typed.
                    long version = b.docVersion;
                    DocumentText page = DocumentText.of(a);
                    com.editora.perf.OffFx.then(
                            EditorBuffer.HTML_LINK_CLICK_SITE, () -> HtmlLinks.at(page.text(), off), found -> {
                                if (b.docVersion != version || b.isDisposed() || !found.ok() || found.value() == null) {
                                    return;
                                }
                                a.moveTo(off);
                                a.requestFocus();
                                Runnable go = b.lspGotoDefinitionAction;
                                javafx.application.Platform.runLater(go::run);
                            });
                }
            } catch (RuntimeException ignored) {
                // hit-test off the text — ignore
            }
        });
    }

    void installOverlays() {
        b.columnRuler.heightProperty().bind(b.root.heightProperty());
        // A bottom tool window opening (Problems, Run) shrinks the editor from below and the caret line could
        // drop out of view; keep it in view when it was in view before (Java review A20).
        b.area.heightProperty().addListener((obs, old, now) -> {
            if (now.doubleValue() < old.doubleValue() && JumpReveal.caretParagraphVisible(b.area)) {
                javafx.application.Platform.runLater(b.area::requestFollowCaret);
            }
        });
        // Re-measure the ruler whenever the rendered text moves: horizontal scroll, and any layout
        // change (first paint, resize, vertical scroll, gutter widening). Always deferred via
        // runLater so we never query character bounds synchronously inside the layout pass — doing
        // that re-enters layout and blanks the editor.
        b.area.estimatedScrollXProperty().addListener((obs, old, now) -> b.rulerPlacement.scheduleRulerMeasure());
        // viewportDirtyEvents fires for a vertical scroll and for every edit, neither of which can move the
        // ruler: its x is (glyph advance, gutter width, horizontal scroll), and the visibility test needs the
        // viewport width. Each measure costs TWO forced VirtualFlow layouts plus character-bounds queries, so
        // measuring on every dirty event was ~6-7 full measures per chrome toggle and a needless one per
        // keystroke. Horizontal scroll has its own listener above; the rest arrive as a width change or via
        // markRulerInputsDirty().
        b.area.viewportDirtyEvents().subscribe(ignore -> {
            double w = b.area.getWidth();
            if (b.rulerPlacement.rulerInputsDirty || w != b.rulerPlacement.lastRulerViewportWidth) {
                b.rulerPlacement.lastRulerViewportWidth = w;
                b.rulerPlacement.scheduleRulerMeasure();
            }
        });

        // Editor scroll pane fills the area, leaving room on the right for the minimap; the minimap
        // is docked to the right edge; the column ruler floats on top of everything. Only what every
        // buffer uses is built here: the note and spell overlays are on by default, and the ruler is one
        // Line. Every other overlay and stripe is attached on first activation (see their accessors and
        // attachLazyOverlay / attachLazyStripe) at the Layer that keeps this stacking order, so an
        // off-feature buffer never builds their Canvas, subscriptions or listeners.
        b.root.getChildren()
                .addAll(
                        OverlayLayer.layered(b.scrollPane, OverlayLayer.TEXT),
                        OverlayLayer.layered(b.noteOverlay, OverlayLayer.NOTE),
                        OverlayLayer.layered(b.spellOverlay, OverlayLayer.SPELL),
                        OverlayLayer.layered(b.minimap, OverlayLayer.MINIMAP),
                        OverlayLayer.layered(b.columnRuler, OverlayLayer.RULER));
        AnchorPane.setTopAnchor(b.scrollPane, 0d);
        AnchorPane.setBottomAnchor(b.scrollPane, 0d);
        AnchorPane.setLeftAnchor(b.scrollPane, 0d);
        AnchorPane.setRightAnchor(b.scrollPane, Minimap.WIDTH);
        // The overlays share the text rectangle with the scroll pane (and track it when the minimap is
        // toggled — see anchorOverText); they are mouse-transparent so clicks reach the editor.
        b.anchorOverText(b.spellOverlay); // red squiggles
        b.imageDrop.install(b.area);
        b.anchorOverText(b.noteOverlay);
        b.noteOverlay.setSpans(b.notes::activeSpans, b.notes::hasActiveSpans); // inert until a note is positioned
        b.noteOverlay.setActive(true);
        installPointerHovers(b.area);
        AnchorPane.setTopAnchor(b.minimap, 0d);
        AnchorPane.setBottomAnchor(b.minimap, 0d);
        AnchorPane.setRightAnchor(b.minimap, 0d);
    }
    /** The popups that follow the pointer over {@code area} (either pane), each shown at the pointer. */
    void installPointerHovers(CodeArea a) {
        MermaidLintHover.install(a, () -> b.mermaidLintEnabled ? b.lintOverlay : null); // lazily attached
        MarkdownLintHover.install(
                a,
                () -> b.markdownLintEnabled || b.httpVariableMarks.enabled() ? b.mdLintOverlay : null,
                hits -> HttpVariableFixes.hint(b.httpVariableMarks, b.httpGutter.hooks(), hits));
        b.lspAssists.installLspHover(a); // reads lspOverlay only while LSP is active (lazily attached)
        b.debugHover.install(a);
        b.noteHover.install(a);
    }

    /**
     * Releases this buffer's resources. Must be called by the controller when the tab is
     * <em>actually closed</em> (not on a plain tab switch). The preview/highlight executors are now
     * shared, app-lifetime pools ({@link BufferPreview#PREVIEW_POOL}/{@link EditorBuffer#HIGHLIGHT_POOL}), so disposal does not
     * shut them down — it instead bumps {@link BufferPreview#previewGen}/{@link BufferHighlighter#highlightGen} so any in-flight task
     * submitted by this buffer discards its result (the gen guard) instead of touching the now-dead
     * buffer. The reactfx subscriptions are on the buffer's own {@code area}/{@code area2} and die with
     * it on GC; the shared settled-edit subscription is dropped eagerly too. Idempotent. Once disposed the
     * buffer must not be reused.
     */
    void dispose() {
        b.encryption.wipe(); // a passphrase-only file's passphrase leaves with the tab
        b.snippetSession.cancel();
        b.views.cancelWrapRemeasure();
        b.completionEditTrackers.clear();
        b.completionActions.cancelCompletion();
        b.disposed = true; // reject any LATER dispatch (see below) — the gen bumps only cover in-flight work
        // Drop the undo checkpoints eagerly. They go with the buffer on GC (BufferReleasedOnCloseFxTest
        // pins that the whole buffer becomes collectable when its tab closes), but they are the single
        // largest thing hanging off it -- up to MAX whole-document snapshots -- and releasing them at the
        // moment the tab closes returns that memory now rather than at the next collection.
        b.undoHistory.clear();
        // The undo queue too: its removed-and-inserted text counts against the app-wide UndoQueueBudget until
        // it is forgotten, and a closed tab should give the budget back now, not when the queue is collected.
        b.area.getUndoManager().forgetHistory();
        b.documentSnapshots.invalidate();
        b.views.hiddenRelease.cancel(); // (a pending wait holds this buffer weakly, so it is collectable either way)
        PreviewSurfaces.release(b.path, b); // rendered Mermaid/Typst pages only this document could show
        b.minimap.dispose(); // its retained content pixels (MinimapRetention) and its split follower's
        if (b.settledEditSub != null) {
            b.settledEditSub.unsubscribe();
            b.settledEditSub = null;
        }
        b.settledEdits.dispose();
        b.caretOps.disposeMultiCaret();
        b.previewPanes.previewGen++; // discard any in-flight preview result for this (now closed) buffer
        b.highlighter.highlightGen++; // discard any in-flight highlight result
        b.todoMarker.todoGen++; // discard any in-flight TODO scan result
        b.runGutter.runScanGen++; // discard any in-flight run scan result
        b.languageState.languageGen++; // discard any in-flight deferred grammar load
        // RichTextFX's own teardown, last: it stops the caret blink timer — a running timer is a GC root, so a
        // buffer closed while its editor had focus stayed reachable, with its whole window — and closes the
        // undo manager and the area's streams. The document text stays readable.
        b.area.dispose();
        if (b.area2 != null) {
            b.area2.dispose();
        }
    }
}
