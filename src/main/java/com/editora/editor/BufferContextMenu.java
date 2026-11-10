package com.editora.editor;

import java.util.List;

import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.input.Clipboard;

import com.editora.command.MenuChords;
import com.editora.editor.fold.FoldManager;
import com.editora.editor.http.HttpVariableFixes;
import com.editora.editor.markdown.MarkdownMenus;
import com.editora.editor.markdown.PreviewMenu;
import org.fxmisc.richtext.CodeArea;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * A buffer's right-click menu, built on each request for the view that was clicked: Run/Debug for the file,
 * test, main class or {@code .http} request there, the debugger and build-tool items, the language-server
 * submenu, AI actions, spelling suggestions, the markup submenus, Toggle Comment, the editing items, plugin
 * items, bookmarks and notes; trimmed to the editing items in Simple UI mode. The {@code ContextMenu} popup is
 * created on the first right-click.
 */
final class BufferContextMenu {

    private final EditorBuffer b;

    BufferContextMenu(EditorBuffer b) {
        this.b = b;
        this.menuView = b.area; // declared after the area in EditorBuffer, so it is set by now
    }

    Runnable lspFindReferencesAction = () -> {};
    Runnable lspHoverAction = () -> {};
    Runnable lspFormatAction = () -> {};
    private java.util.function.Consumer<String> lspCommand = id -> {}; // runs a command id (Refactor, Generate)
    Runnable lspRenameAction = () -> {};
    Runnable lspGotoImplementationAction = () -> {};
    Runnable lspGotoTypeDefinitionAction = () -> {};
    /** Whether this buffer's server advertises whole-document formatting (refreshed when it reports ready). */
    private boolean lspFormatAvailable;
    /** Whether this buffer's server advertises rename — gates the menu item (#676). */
    private boolean lspRenameAvailable;
    /** Whether this buffer's server advertises {@code textDocument/implementation} — gates the item (#735). */
    private boolean lspImplementationAvailable;
    /** Whether this buffer's server advertises {@code textDocument/typeDefinition} — gates the item (#736). */
    private boolean lspTypeDefinitionAvailable;
    /** The editor's right-click menu, built on the first request: a {@code ContextMenu} is a popup window
     *  with a scene of its own, which most buffers never open. */
    private @Nullable ContextMenu contextMenu;
    /** The view the context menu was last asked for: its items act where the user clicked, in either pane. */
    private CodeArea menuView;
    /** Supplies extra right-click items (plugin contributions), injected by the controller; null = none. */
    private java.util.function.@Nullable Supplier<List<MenuItem>> menuContributor;
    /** Build-tool actions for this file, shown next to the LSP submenu rather than at the menu's foot. */
    private java.util.function.@Nullable Supplier<List<MenuItem>> buildMenuContributor;
    /** Debugger actions at the click (breakpoint, Run to Cursor, Show Value…); null = none (review C10). */
    private java.util.function.@Nullable Supplier<List<MenuItem>> debugMenuContributor;

    /** Simple UI mode: the right-click menu keeps the editing actions only — see {@link #setSimpleMenu}. */
    private boolean simpleMenu;

    private ContextMenu contextMenu() {
        if (contextMenu == null) {
            contextMenu = new ContextMenu();
            contextMenu.getStyleClass().setAll("context-menu", "editor-context-menu");
        }
        return contextMenu;
    }

    void installContextMenu(CodeArea a) {
        a.setOnContextMenuRequested(e -> {
            ContextMenu contextMenu = contextMenu();
            menuView = a;
            a.requestFocus(); // "at the caret" items read the focused view
            List<MenuItem> items = new java.util.ArrayList<>();
            // First, and for every item below: one position the menu is about (and the caret moved to a
            // right-click outside the selection), so Paste, Run Test, the LSP items and Add Bookmark agree.
            ContextMenuTarget at = ContextMenuTarget.resolve(a, e, b::collapseCarets);
            // A JUnit test file runs/debugs the method there (or the class from its declaration)
            // rather than offering generic "Run File"; anything else runnable keeps that generic action.
            // Simple UI mode keeps the editing actions only: no Run/Debug, language-server, build, AI, markup,
            // bookmark or note items, as the Simple menu bar keeps none of them.
            com.editora.test.JavaTestScanner.TestTarget testTarget =
                    simpleMenu ? null : b.runGutter.testTargetAt(at.line(), false);
            if (testTarget != null) {
                boolean method = testTarget.methodName() != null;
                MenuItem runTests = MenuChords.tag(
                        new MenuItem(tr(method ? "editmenu.runTestMethod" : "editmenu.runTests")),
                        method ? "test.runAtCaret" : "test.runClassAtCaret");
                runTests.setGraphic(FoldManager.runGlyph("test-run-marker")); // blue, matching the test gutter ▶
                runTests.setOnAction(ev -> b.runGutter.testRunHandler.accept(testTarget));
                MenuItem debugTest =
                        new MenuItem(tr(method ? "testrunner.menu.debugTest" : "testrunner.menu.debugClass"));
                debugTest.setGraphic(MenuIcons.debug());
                if (method) {
                    MenuChords.tag(debugTest, "test.debugAtCaret");
                }
                debugTest.setOnAction(ev -> b.runGutter.testDebugHandler.accept(testTarget));
                items.addAll(java.util.List.of(runTests, debugTest));
                items.add(new SeparatorMenuItem());
            } else if (!simpleMenu && b.httpFeatureEnabled && b.isHttpFile() && b.runGutter.runnable) {
                // A .http file runs requests, never "Run File" (which launched `java requests.http`).
                MenuItem runRequest = new MenuItem(tr("editmenu.runHttpRequest"));
                runRequest.setGraphic(FoldManager.runGlyph());
                runRequest.setOnAction(ev -> b.httpGutter.hooks().run().accept(at.line()));
                MenuItem runAll = new MenuItem(tr("editmenu.runHttpFile"));
                runAll.setOnAction(ev -> b.httpGutter.hooks().runAll().run());
                items.addAll(java.util.List.of(runRequest, runAll));
                if (b.runGutter.httpDebugHandler != null
                        && com.editora.http.HttpScriptLines.requestHasScripts(b.getContent(), at.line())) {
                    MenuItem debugRequest = new MenuItem(tr("editmenu.debugHttpRequest"));
                    debugRequest.setGraphic(MenuIcons.debug());
                    debugRequest.setOnAction(ev -> {
                        java.util.function.IntConsumer handler = b.runGutter.httpDebugHandler;
                        if (handler != null) {
                            handler.accept(at.line());
                        }
                    });
                    items.add(debugRequest);
                }
                items.addAll(
                        HttpVariableFixes.menuItems(b.httpVariableFixesAt(a, at.offset()))); // "Use environment dev"
                items.add(new SeparatorMenuItem());
            } else if (!simpleMenu && b.runGutter.runnable && b.runGutter.runHandler != null && !b.isHttpFile()) {
                MenuItem run = MenuChords.tag(new MenuItem(tr("command.file.run")), "file.run");
                run.setGraphic(FoldManager.runGlyph()); // green play icon, matching the gutter glyph
                run.setOnAction(ev -> {
                    Runnable handler = b.runGutter.runHandler;
                    if (handler != null) {
                        handler.run();
                    }
                });
                items.add(run);
                if (b.runGutter.debugFileHandler != null && b.runGutter.debugFileShown.getAsBoolean()) {
                    MenuItem debugFile = new MenuItem(tr("editmenu.debugFile"));
                    debugFile.setGraphic(MenuIcons.debug());
                    debugFile.setOnAction(ev -> {
                        Runnable handler = b.runGutter.debugFileHandler;
                        if (handler != null) {
                            handler.run();
                        }
                    });
                    items.add(debugFile);
                }
                items.add(new SeparatorMenuItem());
            } else if (!simpleMenu) {
                // A Java class with a project main() gets Run/Debug Main Class items (green ▶ + bug icon).
                com.editora.run.MainMethodScanner.MainMethod mainTarget = b.runGutter.mainTargetAt(at.line());
                if (mainTarget != null) {
                    String simple = com.editora.test.TestSourceLocator.simpleName(mainTarget.fqn());
                    MenuItem runMain = new MenuItem(tr("editmenu.runMainClass", simple));
                    runMain.setGraphic(FoldManager.runGlyph());
                    runMain.setOnAction(ev -> b.runGutter.mainRunHandler.accept(mainTarget));
                    MenuItem debugMain = new MenuItem(tr("editmenu.debugMainClass", simple));
                    debugMain.setGraphic(MenuIcons.debug());
                    debugMain.setOnAction(ev -> b.runGutter.mainDebugHandler.accept(mainTarget));
                    items.add(runMain);
                    items.add(debugMain);
                    items.add(new SeparatorMenuItem());
                }
            }
            List<MenuItem> debugItems = debugMenuContributor == null || simpleMenu ? null : debugMenuContributor.get();
            if (debugItems != null && !debugItems.isEmpty()) {
                items.addAll(debugItems);
                items.add(new SeparatorMenuItem());
            }
            // LSP navigation (only when this buffer is served by a language server), for the symbol there.
            if (b.lspActive && !simpleMenu) {
                items.add(lspMenu(at.offset()));
                items.add(new SeparatorMenuItem());
            }
            // Beside the LSP submenu, not with the plugin-contributed items at the foot of the menu: both
            // are "what this file's toolchain can do", and reading them together is the point.
            if (buildMenuContributor != null && !simpleMenu) {
                List<MenuItem> build = buildMenuContributor.get();
                if (build != null && !build.isEmpty()) {
                    items.addAll(build);
                    items.add(new SeparatorMenuItem());
                }
            }
            // Use the same effective gate as the floating selection bar: the master + feature settings
            // are on and the provider's latest connectivity check succeeded. Keep the submenu available
            // even without a selection so the menu remains a stable map of enabled features; its
            // selection-scoped actions are disabled until there is text to act on.
            if (b.selectionBars.aiActionsEnabled && !simpleMenu) {
                items.add(aiActionsMenu());
                items.add(new SeparatorMenuItem());
            }
            items.addAll(b.spell.menuItems(at.offset(), contextMenu.getItems()));
            // One submenu per markup language, the way the LSP and build-tool actions are already grouped:
            // eight flat "Typst: …" entries pushed cut/copy/paste and the spelling suggestions down the menu
            // and made the file-type actions indistinguishable from the editing ones. Run/Debug stay at the
            // top level above — they are the actions you reach for without reading the menu.
            if (!simpleMenu && b.canFormatMarkdown()) {
                items.add(markupMenu(tr("editmenu.markdown"), MarkdownMenus.items(b, b.typstImagePasteHandler)));
                items.add(new SeparatorMenuItem());
            } else if (!simpleMenu && b.isTypst() && b.isEditable()) {
                items.add(markupMenu(tr("editmenu.typst"), typstMenuItems()));
                items.add(new SeparatorMenuItem());
            }
            if (b.supportsComments()) {
                MenuItem comment = MenuChords.tag(new MenuItem(tr("editmenu.toggleComment")), "edit.toggleComment");
                comment.setGraphic(MenuIcons.comment());
                comment.setOnAction(ev -> b.toggleComment());
                items.add(comment);
                items.add(new SeparatorMenuItem());
            }
            items.addAll(standardMenuItems());
            // Print / Export to PDF stay in Simple mode, as on its File menu; the controller already leaves out the
            // External Tools and plugin items there.
            if (menuContributor != null) { // plugin-contributed items
                List<MenuItem> extra = menuContributor.get();
                if (extra != null && !extra.isEmpty()) {
                    items.add(new SeparatorMenuItem());
                    items.addAll(extra);
                }
            }
            // Bookmarks: the gutter marker is display-only, so add/remove lives here (and in the palette).
            if (b.path != null && !simpleMenu) {
                items.add(new SeparatorMenuItem());
                int clickedLine = at.line();
                boolean marked = b.bookmarks.isBookmarked(clickedLine);
                MenuItem bookmark = MenuChords.tag(
                        new MenuItem(tr(marked ? "editmenu.removeBookmark" : "editmenu.addBookmark")),
                        "bookmarks.toggle");
                bookmark.setGraphic(MenuIcons.bookmark());
                bookmark.setOnAction(ev -> b.bookmarkToggleRequest.accept(b, clickedLine));
                items.add(bookmark);
            }
            if (b.path != null && b.notesEnabled && !simpleMenu) {
                items.add(new SeparatorMenuItem());
                boolean hasSelection = a.getSelection().getLength() > 0;
                MenuItem addNote = MenuChords.tag(
                        new MenuItem(tr(hasSelection ? "editmenu.addNoteSelection" : "editmenu.addNote")), "notes.add");
                addNote.setGraphic(MenuIcons.note());
                addNote.setOnAction(ev -> b.addNoteHandler.accept(b));
                items.add(addNote);
            }
            contextMenu.getItems().setAll(items);
            contextMenu.show(a, at.screenX(), at.screenY());
            e.consume();
        });
    }

    /** Injects a supplier of extra right-click menu items (plugin contributions); null clears it. */
    void setMenuContributor(java.util.function.Supplier<List<MenuItem>> contributor) {
        this.menuContributor = contributor;
    }

    /** The debugger's right-click items, placed above the LSP submenu (built per showing; null clears it). */
    void setDebugMenuContributor(java.util.function.Supplier<List<MenuItem>> contributor) {
        this.debugMenuContributor = contributor;
    }

    /**
     * Simple UI mode trims the right-click menu, as it trims the menu bar, to Toggle Comment, the spelling
     * suggestions, Cut/Copy/Paste, Undo/Redo, Select All and Print / Export to PDF; the preview menu loses
     * its extra export formats (see {@link PreviewMenu}). Read on each right-click,
     * so a toggle applies to the next one.
     */
    void setSimpleMenu(boolean simple) {
        this.simpleMenu = simple;
    }

    /** Whether the right-click menus are trimmed for Simple UI mode (the preview menus read it too). */
    boolean isSimpleMenu() {
        return simpleMenu;
    }

    /** Items placed immediately after the LSP submenu — see {@code installContextMenu}. */
    void setBuildMenuContributor(java.util.function.Supplier<List<MenuItem>> contributor) {
        this.buildMenuContributor = contributor;
    }

    /**
     * Wraps a markup language's formatting actions in one titled submenu — the {@link #lspMenu} shape.
     *
     * <p>The items keep their palette titles ("Markdown: Bold"), matching the Maven submenu rather than the
     * LSP one: those titles are the command titles, shared with the palette and the keybinding editor, and
     * duplicating them purely to drop a prefix inside the submenu would mean a second set of strings in six
     * catalogs that could then drift from the commands they name.
     */
    private Menu markupMenu(String title, List<MenuItem> actions) {
        Menu menu = new Menu(title, MenuIcons.textFormat());
        menu.getItems().addAll(actions);
        return menu;
    }

    /** AI selection actions, grouped under one row while the effective enabled-and-connected gate is on. */
    private Menu aiActionsMenu() {
        boolean hasSelection = menuView.getSelection().getLength() > 0;
        MenuItem explain = MenuChords.tag(
                new MenuItem(tr("command.ai.explainSelection"), MenuIcons.explain()), "ai.explainSelection");
        explain.setDisable(!hasSelection);
        explain.setOnAction(e -> b.requestExplainSelection());
        MenuItem rewrite = MenuChords.tag(
                new MenuItem(tr("command.ai.rewriteSelection"), MenuIcons.rewrite()), "ai.rewriteSelection");
        rewrite.setDisable(!hasSelection || !b.isEditable());
        rewrite.setOnAction(e -> b.requestRewriteSelection());
        Menu menu = new Menu(tr("editmenu.aiActions"), MenuIcons.ai());
        menu.getItems().addAll(explain, rewrite);
        return menu;
    }

    /**
     * The language-server actions, gathered under one <b>LSP</b> submenu.
     *
     * <p>A submenu rather than the flat list they used to be: a served buffer contributes up to seven items,
     * which pushed the editing actions everyone uses — cut/copy/paste, the spelling suggestions — far enough
     * down the menu to hunt for. Grouping them also names what they are, which a bare "Go to Definition"
     * sitting between "Paste" and a spelling suggestion does not.
     */
    private Menu lspMenu(int offset) {
        Menu menu = new Menu(tr("editmenu.lsp"), MenuIcons.code());
        menu.getItems().addAll(lspMenuItems(offset));
        return menu;
    }

    /** Go to Definition / Find References / Show Documentation — each moves the caret to {@code offset}
     *  first so it targets the right-clicked symbol, then runs the controller-supplied action. */
    private List<MenuItem> lspMenuItems(int offset) {
        List<MenuItem> items = new java.util.ArrayList<>();
        items.add(lspItem("command.lsp.gotoDefinition", MenuIcons.gotoDefinition(), offset, b.lspGotoDefinitionAction));
        if (lspImplementationAvailable) {
            items.add(lspItem(
                    "command.lsp.gotoImplementation",
                    MenuIcons.gotoImplementation(),
                    offset,
                    lspGotoImplementationAction));
        }
        if (lspTypeDefinitionAvailable) {
            items.add(lspItem(
                    "command.lsp.gotoTypeDefinition",
                    MenuIcons.gotoTypeDefinition(),
                    offset,
                    lspGotoTypeDefinitionAction));
        }
        items.add(lspItem("command.lsp.findReferences", MenuIcons.find(), offset, lspFindReferencesAction));
        items.add(lspItem("command.lsp.hover", MenuIcons.about(), offset, lspHoverAction));
        if (b.lspCodeActionsAvailable) { // quick fixes target the right-clicked spot
            items.add(lspItem("command.lsp.codeActions", MenuIcons.codeAction(), offset, b.lspCodeActionsAction));
        }
        if (lspRenameAvailable) { // rename the right-clicked symbol
            items.add(lspItem("command.lsp.rename", MenuIcons.rename(), offset, lspRenameAction));
        }
        items.add(1, lspCommandItem("lsp.peekDefinition", MenuIcons.find(), offset));
        if (b.lspCodeActionsAvailable) { // Refactor, Generate, the hierarchies had no right-click entry (Java review
            // B14)
            items.add(lspCommandItem("lsp.refactorThis", MenuIcons.codeAction(), offset));
            if ("java".equals(b.language)) {
                items.add(lspCommandItem("lsp.generate", MenuIcons.add(), offset));
                items.add(lspCommandItem("lsp.callHierarchy", MenuIcons.find(), offset));
                items.add(lspCommandItem("lsp.typeHierarchy", MenuIcons.find(), offset));
                items.add(lspCommandItem("lsp.gotoSuperMethod", MenuIcons.gotoImplementation(), offset)); // ER3
            }
        }
        if (lspFormatAvailable) {
            MenuItem format =
                    MenuChords.tag(new MenuItem(MenuTitles.of(tr("command.lsp.formatDocument"))), "lsp.formatDocument");
            format.setGraphic(MenuIcons.code());
            format.setOnAction(e -> lspFormatAction.run()); // whole-document — no caret positioning needed
            items.add(format);
        }
        return items;
    }

    /** A row that runs command {@code id} through the coordinator, at the right-clicked spot. */
    private MenuItem lspCommandItem(String id, Node icon, int offset) {
        return lspItem("command." + id, icon, offset, () -> lspCommand.accept(id));
    }

    private MenuItem lspItem(String titleKey, Node icon, int offset, Runnable action) {
        MenuItem item =
                MenuChords.tag(new MenuItem(MenuTitles.of(tr(titleKey))), titleKey.substring("command.".length()));
        item.setGraphic(icon);
        item.setOnAction(e -> {
            menuView.moveTo(offset); // in the view that was right-clicked
            action.run();
        });
        return item;
    }

    /** Typst inline-format actions for the right-click menu (Typst buffers only). Typst has no native
     *  strikethrough/task/pipe-table, so the set is bold/emphasis/raw/link/bullet. */
    private List<MenuItem> typstMenuItems() {
        MenuItem bold = MenuChords.tag(new MenuItem(tr("command.typst.bold")), "typst.bold");
        bold.setGraphic(MenuIcons.bold());
        bold.setOnAction(e -> b.formatInline("*"));
        MenuItem emph = MenuChords.tag(new MenuItem(tr("command.typst.emph")), "typst.emph");
        emph.setGraphic(MenuIcons.italic());
        emph.setOnAction(e -> b.formatInline("_"));
        MenuItem raw = MenuChords.tag(new MenuItem(tr("command.typst.raw")), "typst.raw");
        raw.setGraphic(MenuIcons.code());
        raw.setOnAction(e -> b.formatInline("`"));
        MenuItem link = MenuChords.tag(new MenuItem(tr("command.typst.link")), "typst.link");
        link.setGraphic(MenuIcons.link());
        link.setOnAction(e -> b.formatLinkFromClipboard());
        MenuItem bullet = MenuChords.tag(new MenuItem(tr("command.typst.bulletList")), "typst.bulletList");
        bullet.setGraphic(MenuIcons.bulletList());
        bullet.setOnAction(e -> b.formatBulletList());
        MenuItem table = MenuChords.tag(new MenuItem(tr("command.typst.insertTable")), "typst.insertTable");
        table.setGraphic(MenuIcons.table());
        table.setOnAction(e -> b.insertTypstTableInteractive());
        MenuItem outline = MenuChords.tag(new MenuItem(tr("command.typst.outline")), "typst.outline");
        outline.setGraphic(MenuIcons.bulletList());
        outline.setOnAction(e -> b.insertTypstOutline());
        MenuItem image = MenuChords.tag(new MenuItem(tr("command.typst.insertImage")), "typst.insertImage");
        image.setGraphic(MenuIcons.download());
        image.setOnAction(e -> {
            if (b.typstImagePasteHandler != null) {
                b.typstImagePasteHandler.run();
            }
        });
        return List.of(bold, emph, raw, link, bullet, table, outline, image);
    }

    /** An editing row titled by {@code key} that does what command {@code commandId} does (its chord shows). */
    private static MenuItem tableItem(String key, String commandId, Node icon, Runnable action) {
        MenuItem mi = MenuChords.tag(new MenuItem(tr(key)), commandId);
        mi.setGraphic(icon);
        mi.setOnAction(e -> action.run());
        return mi;
    }

    /** The Cut/Copy/Paste/Undo/Redo/Select All items, with state for the current selection/clipboard. */
    private List<MenuItem> standardMenuItems() {
        // Cut/Copy/Paste route through the multi-caret-aware path first (falling back to the native single-
        // caret op), so a box/column selection copies *every* caret's selection — matching the edit.cut/
        // copy/paste commands. Without this, copy() only grabs the primary caret's row. Like one caret,
        // several carets with nothing selected at any of them have nothing to cut or copy here: the fork's
        // multi-caret cut/copy would otherwise take their whole lines, whatever the "copy the line when
        // nothing is selected" setting says (this menu never did that for a single caret either).
        CodeArea view = menuView;
        MultiCarets carets = view == b.area2 ? b.caretOps.multiCaret2 : b.caretOps.multiCaret;
        boolean canCopy =
                carets != null ? carets.anySelection() : view.getSelection().getLength() > 0;
        boolean editable = b.isEditable();
        boolean hasClipboardText = Clipboard.getSystemClipboard().hasString();
        MenuItem cut = tableItem("editmenu.cut", "edit.cut", MenuIcons.cut(), () -> {
            if (!b.multiCaretCut()) {
                view.cut();
            }
        });
        cut.setDisable(!canCopy || !editable);
        MenuItem copy = tableItem("editmenu.copy", "edit.copy", MenuIcons.copy(), () -> {
            if (!b.multiCaretCopy()) {
                view.copy();
            }
        });
        copy.setDisable(!canCopy);
        MenuItem paste = tableItem("editmenu.paste", "edit.paste", MenuIcons.paste(), () -> {
            if (!b.multiCaretPaste()) {
                view.paste();
            }
        });
        paste.setDisable(!hasClipboardText || !editable);
        MenuItem undo = tableItem("editmenu.undo", "edit.undo", MenuIcons.undo(), () -> b.undoOrRedo(view, false));
        undo.setDisable(!view.isUndoAvailable());
        MenuItem redo = tableItem("editmenu.redo", "edit.redo", MenuIcons.redo(), () -> b.undoOrRedo(view, true));
        redo.setDisable(!view.isRedoAvailable());
        MenuItem selectAll = tableItem("editmenu.selectAll", "edit.selectAll", MenuIcons.selectAll(), view::selectAll);
        return List.of(cut, copy, paste, new SeparatorMenuItem(), undo, redo, new SeparatorMenuItem(), selectAll);
    }

    /** Runs a command by id from the right-click LSP menu: Peek Definition, Refactor This…, Generate…. */
    void setLspCommandRunner(java.util.function.Consumer<String> run) {
        this.lspCommand = run;
    }

    /** Whether to offer "Go to Implementation" in the right-click menu (the server's capability, #735). */
    void setLspImplementationAvailable(boolean available) {
        this.lspImplementationAvailable = available;
    }

    /** Whether to offer "Go to Type Definition" in the right-click menu (the server's capability, #736). */
    void setLspTypeDefinitionAvailable(boolean available) {
        this.lspTypeDefinitionAvailable = available;
    }

    /** Whether to offer "Format Document" in the right-click menu (the server's formatting capability). */
    void setLspFormatAvailable(boolean available) {
        this.lspFormatAvailable = available;
    }

    /** Whether to offer "Rename" in the right-click menu (the server's rename capability, #676). */
    void setLspRenameAvailable(boolean available) {
        this.lspRenameAvailable = available;
    }
}
