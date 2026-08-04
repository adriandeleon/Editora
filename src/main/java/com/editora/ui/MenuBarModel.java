package com.editora.ui;

import java.util.List;

/**
 * The menu bar's contents, as data: which commands appear under which menu, in what order (#763).
 *
 * <p>Editora is command-driven, and the command palette has always been the complete index of what it can
 * do — but only for someone who already knows what to search for. A menu bar is the browsable map: it is how
 * a newcomer discovers that "Refactor" or "VCS" exist as categories at all, and it is the loudest single
 * signal that separates an IDE from an editor with tool windows.
 *
 * <p>It is a table rather than construction code because everything a menu item needs already exists on the
 * {@link com.editora.command.Command} it names: the localized title ({@code command.<id>}), the description,
 * the live keybinding via {@link com.editora.command.KeymapManager#invertBindings()}, and whether it is
 * currently applicable via {@link Chrome#paletteEnabled}. So adding a command to a menu is one line here, and
 * nothing else has to change.
 *
 * <p>Deliberately <b>not</b> exhaustive: there are ~470 registered commands and a menu listing all of them
 * would be worse than no menu. This is the curated set a user would look for by browsing; the palette remains
 * the complete index.
 */
final class MenuBarModel {

    /** Placeholder entry rendering a separator line rather than a command. */
    static final String SEPARATOR = "---";

    /** One top-level menu: an i18n key for its title, and its ordered entries (command ids / separators). */
    record MenuSpec(String titleKey, List<String> entries) {}

    private MenuBarModel() {}

    /**
     * The menu bar, left to right. Menu names follow the convention shared by IntelliJ, Eclipse, NetBeans and
     * Visual Studio, so someone arriving from any of them finds things where they expect.
     */
    static List<MenuSpec> menus() {
        return List.of(
                new MenuSpec(
                        "menubar.file",
                        List.of(
                                "file.new",
                                "template.new",
                                "project.newFromTemplate",
                                "maven.newProject",
                                "file.open",
                                "project.open",
                                SEPARATOR,
                                "file.save",
                                "file.saveAs",
                                "file.saveAsAdmin",
                                SEPARATOR,
                                "buffer.close",
                                "buffer.closeOthers",
                                "buffer.closeAll",
                                SEPARATOR,
                                "preview.exportPdf",
                                "preview.exportDocx",
                                "preview.exportHtml",
                                "preview.print",
                                SEPARATOR,
                                "file.clearRecent",
                                "config.export",
                                SEPARATOR,
                                "app.quit")),
                new MenuSpec(
                        "menubar.edit",
                        List.of(
                                "edit.undo",
                                "edit.redo",
                                SEPARATOR,
                                "edit.cut",
                                "edit.copy",
                                "edit.paste",
                                "edit.copyWithHighlighting",
                                SEPARATOR,
                                "edit.selectAll",
                                "edit.expandSelection",
                                "edit.shrinkSelection",
                                "edit.selectAllOccurrences",
                                SEPARATOR,
                                "edit.toggleComment",
                                "edit.duplicateLine",
                                "edit.moveLineUp",
                                "edit.moveLineDown",
                                SEPARATOR,
                                "edit.stringOps",
                                "edit.completion")),
                new MenuSpec(
                        "menubar.find",
                        List.of(
                                "find.show",
                                "find.next",
                                "find.previous",
                                "find.replace",
                                SEPARATOR,
                                "search.inFiles",
                                "search.inFilesPopup",
                                SEPARATOR,
                                "edit.queryReplace",
                                "edit.occur")),
                new MenuSpec(
                        "menubar.view",
                        List.of(
                                "view.togglePreview",
                                "view.toggleSplitPreview",
                                SEPARATOR,
                                "view.splitEditorRight",
                                "view.splitEditorDown",
                                "view.unsplitEditorGroups",
                                SEPARATOR,
                                "view.splitVertical",
                                "view.splitHorizontal",
                                "view.unsplit",
                                SEPARATOR,
                                "view.toggleFold",
                                "view.foldAll",
                                "view.unfoldAll",
                                SEPARATOR,
                                "view.toggleToolbar",
                                "view.toggleStatusBar",
                                "view.toggleTabBar",
                                "view.toggleToolStripe",
                                "view.toggleMinimap",
                                "view.toggleLineNumbers",
                                "view.toggleWhitespace",
                                SEPARATOR,
                                "view.toggleSimpleMode",
                                "view.welcome")),
                new MenuSpec(
                        "menubar.navigate",
                        List.of(
                                "file.find",
                                "buffer.jump",
                                "lsp.gotoSymbol",
                                SEPARATOR,
                                "nav.goToLine",
                                "nav.back",
                                "nav.forward",
                                SEPARATOR,
                                "lsp.gotoDefinition",
                                "lsp.gotoImplementation",
                                "lsp.gotoTypeDefinition",
                                "lsp.findReferences",
                                SEPARATOR,
                                "nav.aceJump",
                                "bookmarks.jump")),
                new MenuSpec(
                        "menubar.code",
                        List.of(
                                "lsp.codeActions",
                                "lsp.rename",
                                "lsp.formatDocument",
                                "lsp.organizeImports",
                                SEPARATOR,
                                "lsp.hover",
                                "lsp.signatureHelp",
                                "lsp.callHierarchy",
                                "lsp.typeHierarchy",
                                SEPARATOR,
                                "snippets.insert",
                                "edit.expandAbbrev")),
                new MenuSpec(
                        "menubar.run",
                        List.of(
                                "file.run",
                                "file.runWithArgs",
                                "run.rerun",
                                "run.stop",
                                SEPARATOR,
                                "debug.start",
                                "debug.stop",
                                "debug.toggleBreakpoint",
                                SEPARATOR,
                                "debug.stepOver",
                                "debug.stepInto",
                                "debug.stepOut",
                                "debug.continue",
                                SEPARATOR,
                                "test.run",
                                "test.rerunFailed")),
                new MenuSpec(
                        "menubar.vcs",
                        List.of(
                                "git.commit",
                                "git.push",
                                "git.pull",
                                "git.fetch",
                                SEPARATOR,
                                "git.switchBranch",
                                "git.newBranch",
                                SEPARATOR,
                                "diff.vsHead",
                                "diff.compareWith",
                                "git.fileHistory",
                                SEPARATOR,
                                "git.stash",
                                "git.stashPop",
                                SEPARATOR,
                                "git.clone")),
                new MenuSpec(
                        "menubar.tools",
                        List.of(
                                "externalTool.run",
                                "macro.startRecording",
                                "macro.stopRecording",
                                "macro.replayLast",
                                SEPARATOR,
                                "template.manage",
                                "snippets.manage",
                                "plugins.browse",
                                SEPARATOR,
                                "install.languageServer",
                                "view.doctor")),
                new MenuSpec(
                        "menubar.window",
                        List.of(
                                "window.new",
                                "window.other",
                                SEPARATOR,
                                "buffer.next",
                                "buffer.togglePin",
                                SEPARATOR,
                                "window.maximize",
                                "window.fullScreen",
                                SEPARATOR,
                                "tool.project",
                                "tool.structure",
                                "tool.search",
                                "tool.problems")),
                new MenuSpec(
                        "menubar.help",
                        List.of(
                                "palette.show",
                                SEPARATOR,
                                "help.checkForUpdates",
                                "view.messageLog",
                                "view.debugLog",
                                SEPARATOR,
                                "help.about")));
    }

    /** Every command id referenced by the menu bar, separators excluded. */
    static List<String> allCommandIds() {
        return menus().stream()
                .flatMap(m -> m.entries().stream())
                .filter(e -> !SEPARATOR.equals(e))
                .distinct()
                .toList();
    }
}
