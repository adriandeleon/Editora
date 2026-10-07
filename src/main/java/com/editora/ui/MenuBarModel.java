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

    /**
     * One menu: an i18n key for its title, its ordered entries (command ids / separators), and — after
     * those, below a separator — its submenus. A submenu is a {@code MenuSpec} too (it has none of its own).
     *
     * <p>Submenus exist for the one menu that outgrew a flat list: VCS had some sixty entries. The daily
     * actions stay in {@link #entries}; everything else is one level down, grouped by what it acts on.
     */
    record MenuSpec(String titleKey, List<String> entries, List<MenuSpec> submenus) {
        MenuSpec(String titleKey, List<String> entries) {
            this(titleKey, entries, List.of());
        }

        /** Every entry of the menu and of its submenus, top level first. */
        List<String> allEntries() {
            if (submenus.isEmpty()) {
                return entries;
            }
            List<String> all = new java.util.ArrayList<>(entries);
            for (MenuSpec submenu : submenus) {
                all.addAll(submenu.allEntries());
            }
            return all;
        }
    }

    private MenuBarModel() {}

    /**
     * The menu bar, left to right. Menu names follow the convention shared by IntelliJ, Eclipse, NetBeans and
     * Visual Studio, so someone arriving from any of them finds things where they expect.
     */
    static List<MenuSpec> menus() {
        return menus(false);
    }

    /**
     * The menu bar for the current UI mode. Simple UI mode gets the {@link #simpleMenus() reduced} table
     * rather than losing the menu bar altogether: the menu is the browsable map of what the editor can do,
     * and a beginner-facing mode is exactly where that map matters most. What Simple mode strips is the
     * <em>content</em> — its whole point is a minimal surface, and it also switches off LSP, debugging, Git,
     * HTTP, plugins, external tools and the test runner, so the Code/Run/VCS/Tools menus would otherwise
     * stand there fully grayed out, which is worse than not offering them.
     */
    static List<MenuSpec> menus(boolean simple) {
        return simple ? simpleMenus() : fullMenus();
    }

    private static List<MenuSpec> fullMenus() {
        return List.of(
                new MenuSpec(
                        "menubar.file",
                        List.of(
                                "file.new",
                                "file.newFileOfType",
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
                        // What is used every day stays at the top level; the rest is grouped below.
                        List.of(
                                "git.commit",
                                "git.commitAndPush",
                                "git.push",
                                "git.pull",
                                "git.fetch",
                                SEPARATOR,
                                "git.switchBranch",
                                "tool.gitLog",
                                SEPARATOR,
                                // Lit only while a merge, rebase, cherry-pick or revert waits to be finished.
                                "git.continueOperation",
                                "git.skipOperation",
                                "git.abortOperation",
                                SEPARATOR,
                                "git.init",
                                "git.clone"),
                        List.of(
                                new MenuSpec(
                                        "menubar.vcs.changes",
                                        List.of(
                                                "git.stageAll",
                                                "git.unstageAll",
                                                "git.discardAll",
                                                SEPARATOR,
                                                "git.commitAmend",
                                                "git.undoLastCommit",
                                                "git.commitMessageHistory",
                                                SEPARATOR,
                                                "git.nextChange",
                                                "git.previousChange",
                                                "git.peekChange",
                                                "git.revertHunk",
                                                "git.stageHunk",
                                                SEPARATOR,
                                                "diff.reviewUnstaged",
                                                "diff.reviewStaged",
                                                "merge.resolve",
                                                SEPARATOR,
                                                "git.addToGitignore")),
                                new MenuSpec(
                                        "menubar.vcs.branches",
                                        // The branch dropdown's per-row actions, for whoever looks in the menu.
                                        List.of(
                                                "git.newBranch",
                                                "git.newBranchFrom",
                                                "git.checkoutRevision",
                                                SEPARATOR,
                                                "git.mergeBranch",
                                                "git.rebaseOnto",
                                                "git.compareBranch",
                                                SEPARATOR,
                                                "git.renameBranch",
                                                "git.deleteBranch")),
                                new MenuSpec(
                                        "menubar.vcs.remotes",
                                        List.of(
                                                "git.pullRebase",
                                                "git.pullMerge",
                                                SEPARATOR,
                                                "git.pushTo",
                                                "git.pushForce",
                                                "git.pushTags",
                                                "git.fetchRemote",
                                                "git.toggleAutoFetch",
                                                SEPARATOR,
                                                "git.remotes",
                                                "git.worktrees")),
                                new MenuSpec(
                                        "menubar.vcs.tags",
                                        List.of("git.tag.create", "git.tag.push", "git.tag.delete")),
                                new MenuSpec(
                                        "menubar.vcs.stash",
                                        List.of(
                                                "git.stashes",
                                                "git.stash",
                                                "git.stashPop",
                                                // The picker, beside pop-latest: both are listed in the branch
                                                // dropdown.
                                                "git.unstash",
                                                "git.stashDrop")),
                                new MenuSpec("menubar.vcs.patches", List.of("git.applyPatch", "git.createPatch")),
                                new MenuSpec(
                                        "menubar.vcs.history",
                                        List.of(
                                                "git.log.search",
                                                "git.log.toggleAllBranches",
                                                "git.fileHistory",
                                                SEPARATOR,
                                                "git.toggleBlame",
                                                "git.blamePreviousRevision")),
                                new MenuSpec(
                                        "menubar.vcs.compare",
                                        List.of(
                                                "diff.vsHead",
                                                // The Project tree's "Compare with Branch / Tag / Revision",
                                                // for the active file.
                                                "diff.vsBranch",
                                                "diff.vsTag",
                                                "diff.vsCommit",
                                                SEPARATOR,
                                                "diff.compareWith",
                                                "diff.compareClipboard",
                                                "diff.compareBlank",
                                                "diff.compareDirectories")),
                                new MenuSpec(
                                        "menubar.vcs.github",
                                        List.of(
                                                "tool.github",
                                                "github.showPrs",
                                                "github.showIssues",
                                                "github.showRuns",
                                                SEPARATOR,
                                                "github.createPr",
                                                "github.viewPrDiff",
                                                "github.checkoutPr",
                                                "github.submitReview",
                                                SEPARATOR,
                                                "github.showChecks",
                                                "github.viewRunLog",
                                                "github.rerunRun",
                                                "github.rerunFailedJobs",
                                                "github.cancelRun",
                                                SEPARATOR,
                                                "github.openOnGitHub",
                                                "github.copyUrl",
                                                "github.refresh")))),
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
                                "help.documentation",
                                "help.checkForUpdates",
                                "view.messageLog",
                                "view.debugLog",
                                SEPARATOR,
                                "help.about")));
    }

    /**
     * The Simple UI mode menu: the handful of actions that still do something in a mode with no language
     * servers, no VCS, no tool windows and no gutter. Every entry is either a plain editing action or a way
     * back out — {@code view.toggleSimpleMode} is deliberately present, so the mode is never a one-way door
     * reachable only from the toolbar.
     */
    private static List<MenuSpec> simpleMenus() {
        return List.of(
                new MenuSpec(
                        "menubar.file",
                        List.of(
                                "file.new",
                                "file.open",
                                SEPARATOR,
                                "file.save",
                                "file.saveAs",
                                SEPARATOR,
                                "buffer.close",
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
                                SEPARATOR,
                                "edit.selectAll",
                                "edit.toggleComment")),
                new MenuSpec("menubar.find", List.of("find.show", "find.next", "find.previous", "find.replace")),
                new MenuSpec(
                        "menubar.view",
                        List.of(
                                "view.toggleWordWrap",
                                "view.toggleStatusBar",
                                SEPARATOR,
                                "view.settings",
                                SEPARATOR,
                                "view.toggleSimpleMode",
                                "view.welcome")),
                new MenuSpec("menubar.help", List.of("palette.show", "help.documentation", SEPARATOR, "help.about")));
    }

    /**
     * Every command id either menu bar references, separators excluded — the union, so the tests that check
     * each id exists, is localized and has an icon cover the Simple mode table too.
     */
    static List<String> allCommandIds() {
        return java.util.stream.Stream.concat(fullMenus().stream(), simpleMenus().stream())
                .flatMap(m -> m.allEntries().stream())
                .filter(e -> !SEPARATOR.equals(e))
                .distinct()
                .toList();
    }
}
