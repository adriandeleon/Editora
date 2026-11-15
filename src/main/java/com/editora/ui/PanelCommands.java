package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tab;
import javafx.stage.Window;

import com.editora.AppInfo;
import com.editora.command.Command;
import com.editora.dap.DapManager;
import com.editora.editor.EditorBuffer;
import com.editora.vfs.Vfs;

import static com.editora.i18n.Messages.tr;

/**
 * The commands behind the right-click rows and the buttons of the smaller panels, the debugger's popups and windows,
 * the diff and merge views, the find bars and the banners, which only the mouse reached (KBD-01 and MENU-05, Pauta
 * 6.0.1). Each is found the one way {@link FocusCommands} finds a control: the row is {@linkplain FocusCommands#row
 * tagged} with its command in the menu a node gives for its selection ({@link FocusCommands#menu}), or the button is
 * {@linkplain FocusCommands#press bound} to it, and the command looks from where the keyboard is, then in what the
 * window shows (the active tab, the open tool windows). It fires the row or presses the button, so it does exactly
 * what the click does, confirmation included, and only where the control is offered and enabled; otherwise the status
 * line says so.
 */
final class PanelCommands {

    /** The terminal's right-click rows (its view's and its tab's) and the lost connection's Reconnect. */
    static final List<String> TERMINAL = List.of(
            "terminal.copy",
            "terminal.paste",
            "terminal.selectAll",
            "terminal.copyLastOutput",
            "terminal.clearScrollback",
            "terminal.openLink",
            "terminal.copyLinkAddress",
            "terminal.newHere",
            "terminal.closeOthers",
            "terminal.closeRight",
            "terminal.reconnect");

    /** The Markdown preview's right-click rows. */
    static final List<String> PREVIEW =
            List.of("preview.copyAll", "preview.goToSource", "preview.enlarge", "preview.copyLink", "preview.copyCode");

    /**
     * The debugger's value popup's Pin row (its Copy and Add to Watches rows are the Variables view's commands) and the
     * Evaluate window's history of expressions.
     */
    static final List<String> DEBUG = List.of("debug.pinShownValue", "debug.evaluateHistory");

    /** The HTTP panel's "⋯" rows, its Headers table's and its Run All list's. */
    static final List<String> HTTP = List.of(
            "http.toggleResponseWrap",
            "http.saveResponse",
            "http.clearHistory",
            "http.copyHeaderValue",
            "http.copyAllHeaders",
            "http.runSelectedAgain",
            "http.goToSelectedRequest");

    /** The Markdown Lint window's row menu. */
    static final List<String> MARKDOWN_LINT = List.of(
            "markdownLint.fixSelected",
            "markdownLint.disableSelectedLine",
            "markdownLint.disableSelectedInFile",
            "markdownLint.disableSelectedInProject",
            "markdownLint.disableSelectedRule",
            "markdownLint.selectedRuleHelp",
            "markdownLint.copySelected");

    /** The CSV grid's rows and its header-row toggle. */
    static final List<String> CSV = List.of(
            "csv.revealFocusedCell", "csv.sortAscending", "csv.sortDescending", "csv.clearSort", "csv.toggleHeaderRow");

    /** The Doctor's buttons, and a check's row menu and buttons. */
    static final List<String> DOCTOR = List.of(
            "doctor.copyReport",
            "doctor.checkNewer",
            "doctor.copyRow",
            "doctor.copyRowPath",
            "doctor.revealRowPath",
            "doctor.openRowSettings",
            "doctor.installRow",
            "doctor.howToInstallRow",
            "doctor.clearRow");

    /** The TODO window's match menu. */
    static final List<String> TODO = List.of(
            "todo.markSelectedDone", "todo.reopenSelected", "todo.setSelectedPriority", "todo.editSelectedDescription");

    /** The diff, merge and review views' buttons and gutter chevrons. */
    static final List<String> DIFF = List.of(
            "diff.cycleWhitespace",
            "diff.toggleWordHighlight",
            "diff.toggleCollapseContext",
            "diff.toggleWrapLines",
            "diff.applyFinalNewline",
            "diff.applyResult",
            "diff.resetResult",
            "diff.applyChange",
            "diff.applyLine",
            "diff.previousFile",
            "diff.nextFile",
            "diff.applyPatchToWorkingTree",
            "diff.applyPatchToIndex",
            "merge.saveResult",
            "merge.acceptOurs",
            "merge.acceptTheirs",
            "merge.acceptBoth",
            "merge.acceptBase");

    /** The find bars' and Find in Files' options and buttons, and every search field's ✕. */
    static final List<String> FIND = List.of(
            "find.toggleCase",
            "find.toggleRegex",
            "find.toggleWholeWord",
            "find.togglePreserveCase",
            "find.toggleInSelection",
            "search.run",
            "search.replaceAll",
            "edit.clearSearchField");

    /** The debugger's secondary windows, its exception card, its window's view toggles and session strip. */
    static final List<String> DEBUG_WINDOWS = List.of(
            "debug.copyDump",
            "debug.openDumpAsText",
            "debug.openTableAsCsv",
            "debug.refreshTable",
            "debug.copyException",
            "debug.toggleThreadsView",
            "debug.showConsoleArea",
            "debug.switchSession",
            "debugLog.refresh",
            "debugLog.copyAll",
            "debugLog.clear",
            "debugLog.export");

    /** The encryption banners', locked view's and decrypted-block card's buttons. */
    static final List<String> CRYPTO = List.of(
            "crypto.decryptLocked",
            "crypto.cancelDecrypt",
            "crypto.setGpgPath",
            "crypto.showEncryptedText",
            "crypto.trashOriginal",
            "crypto.copyPeeked",
            "crypto.replacePeeked");

    /** The print preview's bar. */
    static final List<String> PRINT = List.of(
            "print.previousPage", "print.nextPage", "print.zoomIn", "print.zoomOut", "print.zoom", "print.pageSetup");

    /** The other panels' and windows' buttons. */
    static final List<String> OTHER = List.of(
            "http.runShownAgain",
            "http.toggleOnlyFailures",
            "http.imageFit",
            "http.imageActualSize",
            "git.toggleSignOff",
            "git.commitNow",
            "git.log.searchHistory",
            "git.peekPreviousChange",
            "git.peekNextChange",
            "git.copyPeekedOldLines",
            "github.toggleMine",
            "github.loadMore",
            "github.openReviewedPr",
            "github.openReviewedFiles",
            "github.submitReviewedPr",
            "github.refreshReviewedPr",
            "history.refresh",
            "history.toggleIgnoreWhitespace",
            "history.toggleHighlightWords",
            "hierarchy.toggleDirection",
            "buildOutput.clear",
            "buildOutput.stop",
            "agent.send",
            "help.copyProblemReport",
            "help.saveProblemReport",
            "messageLog.copyMessages",
            "remote.reconnectProject",
            "preview.refresh",
            "markdown.loadRemoteImages",
            "buffer.noticeAction",
            "structure.bookmarkSelected",
            "structure.noteSelected",
            "problems.openSelectedRuleDocs",
            "problems.copySelectedCode",
            "welcome.copyFocusedPath",
            "welcome.removeFocusedRecent");

    /** The Settings window's and its sub-windows' buttons and links that act (not those that edit one value). */
    static final List<String> SETTINGS = List.of(
            "settings.toggleModifiedOnly",
            "settings.resetAll",
            "settings.pageHelp",
            "settings.resetRow",
            "settings.editUserSettings",
            "settings.editProjectSettings",
            "settings.resetFontZoom",
            "settings.openUserThemesFolder",
            "settings.chooseJavaCleanups",
            "settings.installTool",
            "settings.followToolLink",
            "settings.restoreToolWindowLayout",
            "settings.moveToolWindowEarlier",
            "settings.moveToolWindowLater",
            "settings.revokeAllTrust",
            "settings.openSettingsFile",
            "settings.openSessionLog",
            "settings.restoreBackup",
            "settings.resetAllShortcuts",
            "settings.recordShortcut",
            "settings.resetShortcut",
            "settings.removeShortcut",
            "settings.openTechnicalDictionary",
            "settings.openPersonalDictionary",
            "settings.addDictionaryWord",
            "settings.addSnippet",
            "settings.addTemplate",
            "settings.addAbbreviation",
            "settings.addExternalTool",
            "settings.addTypeRenderer",
            "settings.addRemoteSite",
            "settings.addTodoPattern",
            "settings.removeTodoPattern",
            "sync.removeStoredCredentials",
            "sync.keepStoredCredentials",
            "sync.connect",
            "sync.disconnect",
            "sync.createGithubRepo",
            "sync.syncAnyway",
            "sync.cancel",
            "sync.openFolder",
            "mcp.newToken",
            "settings.testAiConnection",
            "plugins.reload",
            "plugins.removeFocused",
            "macro.addCommandStep",
            "macro.addTextStep",
            "macro.addKeyStep",
            "macro.chooseStepCommand",
            "macro.saveEdits",
            "macro.revertEdits",
            "macro.recordBinding",
            "macro.removeBinding",
            "toolbar.addSeparator",
            "run.addConfig",
            "deploy.addDeployment",
            "debug.addCompound");

    /** A panel's drop-down that sets what it shows, and the CSV header's rename: each opens or runs where it is. */
    static final List<String> VIEW_CHOICES = List.of(
            "structure.sortBy",
            "todo.groupBy",
            "github.filterByState",
            "http.showHistory",
            "debug.chooseThread",
            "csv.renameColumn");

    /** Every family, in palette order. */
    static final List<List<String>> FAMILIES = List.of(
            TERMINAL,
            PREVIEW,
            DEBUG,
            HTTP,
            MARKDOWN_LINT,
            CSV,
            DOCTOR,
            TODO,
            DIFF,
            FIND,
            DEBUG_WINDOWS,
            CRYPTO,
            PRINT,
            OTHER);

    private PanelCommands() {}

    /** Registers the commands on {@code mc}'s registry. */
    static void register(MainController mc) {
        for (List<String> family : FAMILIES) {
            for (String id : family) {
                mc.registry.register(Command.of(id, () -> run(mc, id, Window.getWindows())));
            }
        }
        // The gutter's rows for a lambda breakpoint and for method breakpoints and watchpoints, on the caret's line.
        mc.registry.register(Command.of(
                "debug.toggleStopOnLine",
                () -> withBuffer(
                        mc,
                        b -> mc.debugCoordinator.ifDebug(
                                () -> mc.debugCoordinator.lambdaBreakpoints().toggleStopOnLineAtCaret(b)))));
        mc.registry.register(Command.of(
                "debug.toggleMarkedMembersEnabled",
                () -> mc.debugCoordinator.ifDebug(
                        () -> mc.debugCoordinator.memberBreakpoints().flipMarkAtCaret())));
        mc.registry.register(Command.of(
                "debug.removeMarkedMembers",
                () -> mc.debugCoordinator.ifDebug(
                        () -> mc.debugCoordinator.memberBreakpoints().removeMarkAtCaret())));
        // The Welcome page's and the About box's links to Editora's pages.
        mc.registry.register(Command.of("help.whatsNew", () -> mc.openExternalUrl(AppInfo.releaseNotesUrl())));
        mc.registry.register(Command.of("help.homepage", () -> mc.openExternalUrl(AppInfo.HOMEPAGE)));
        mc.registry.register(Command.of("help.releases", () -> mc.openExternalUrl(AppInfo.RELEASES_PAGE)));
        mc.registry.register(Command.of("help.copyAboutDetails", () -> {
            AboutDialog.copyDetails(mc.config.isDev() ? AppInfo.gitCommit() : "", mc.config.getSettingsFile());
            mc.setStatus(tr("about.copied"));
        }));
        // Alt+click's Quick Evaluate, at the caret; the format bar's heading level, asked as a list.
        mc.registry.register(Command.of(
                "debug.quickEvaluate",
                () -> mc.debugCoordinator.ifDebug(() -> {
                    EditorBuffer b = mc.activeBuffer();
                    if (b == null || !b.quickEvaluateAtCaret()) { // the hover is active only while paused
                        boolean paused = mc.debugCoordinator.dap().state() == DapManager.State.SUSPENDED;
                        mc.setStatus(tr(paused ? "status.debug.noValueAtCaret" : "palette.disabled.needsSuspended"));
                    }
                })));
        mc.registry.register(Command.of("markdown.setHeadingLevel", () -> setHeadingLevel(mc)));
        // Rows that name a thing (a crumb's folder, a tool window's icon): the command asks which.
        mc.registry.register(Command.of("toolbar.toggleCustomize", () -> {
            if (!mc.toolbarCoordinator.toggleCustomizeIfShown()) {
                mc.setStatus(tr("status.toolbar.hidden"));
            }
        }));
        mc.registry.register(Command.of(
                "view.hideToolWindowIcon",
                () -> choose(
                        mc,
                        "view.hideToolWindowIcon",
                        mc.toolWindows.getStripeToolWindows().stream()
                                .filter(mc.toolWindows::isVisible)
                                .toList(),
                        ToolWindow::getTitle,
                        tw -> mc.toolWindows.setVisible(tw, false))));
        mc.registry.register(Command.of(
                "file.revealFolderInFileManager",
                () -> chooseFolder(
                        mc, "file.revealFolderInFileManager", true, dir -> mc.revealInFileManager(dir, true, true))));
        mc.registry.register(Command.of(
                "file.openTerminalInFolder",
                () -> chooseFolder(
                        mc,
                        "file.openTerminalInFolder",
                        false,
                        dir -> mc.openTerminalAt(dir, true, Vfs.isLocal(dir)))));
    }

    /**
     * Markdown: Set Heading Level… — the format bar's Style drop-down for the active Markdown file's caret line: Normal
     * text or a heading level, picked from a list. Says so for a file that is not Markdown, or is read-only.
     */
    private static void setHeadingLevel(MainController mc) {
        List<String> names = new ArrayList<>(List.of(tr("markdown.heading.normal")));
        for (int level = 1; level <= 6; level++) {
            names.add(tr("markdown.heading.h" + level));
        }
        mc.previews.withMarkdown(b ->
                choose(mc, "markdown.setHeadingLevel", List.of(0, 1, 2, 3, 4, 5, 6), names::get, b::setHeadingLevel));
    }

    /** Runs {@code op} on the active buffer, or says that no file is open. */
    private static void withBuffer(MainController mc, Consumer<EditorBuffer> op) {
        EditorBuffer b = mc.activeBuffer();
        if (b == null) {
            mc.setStatus(tr("status.bookmarks.noFile"));
        } else {
            op.accept(b);
        }
    }

    /**
     * Asks which folder above the active file command {@code id} acts on, as the breadcrumb's crumbs offer them: its
     * own folder first, up to the root; local folders only for {@code localOnly}. Says so when there is no file.
     */
    private static void chooseFolder(MainController mc, String id, boolean localOnly, Consumer<Path> op) {
        EditorBuffer b = mc.activeBuffer();
        Path file = b == null ? null : b.getPath();
        if (file == null || (localOnly && !Vfs.isLocal(file))) {
            mc.setStatus(tr("status.bookmarks.noFile"));
            return;
        }
        List<Path> folders = new ArrayList<>();
        for (Path dir = file.getParent(); dir != null; dir = dir.getParent()) {
            folders.add(dir);
        }
        choose(mc, id, folders, dir -> Vfs.displayLabel(dir), op);
    }

    /** Asks for one of {@code items} for command {@code id} in a picker over the window, and runs {@code op} on it. */
    private static <T> void choose(
            MainController mc, String id, List<T> items, Function<T, String> label, Consumer<T> op) {
        if (items.isEmpty()) {
            mc.setStatus(tr("status.projectMap.nothingToPick", CommandTips.title(id)));
            return;
        }
        QuickOpen<T> picker =
                new QuickOpen<>(CommandTips.title(id), tr("project.map.pick.prompt"), () -> items, label, t -> "", op);
        picker.setOverlayHost(mc.overlayHost);
        picker.show(mc.stage);
    }

    /** Runs {@code id} where it is offered, looking in {@code windows}' focus owners, then in what {@code mc} shows. */
    static boolean run(MainController mc, String id, List<? extends Window> windows) {
        return FocusCommands.run(id, mc::setStatus, windows, showing(mc), rows -> pick(mc, id, rows));
    }

    /** Asks which of {@code rows} (a submenu's choices) command {@code id} is to fire, by their words. */
    static void pick(MainController mc, String id, List<MenuItem> rows) {
        QuickOpen<MenuItem> picker = new QuickOpen<>(
                CommandTips.title(id),
                tr("project.map.pick.prompt"),
                () -> rows,
                row -> Objects.requireNonNullElse(row.getText(), ""),
                row -> "",
                FocusCommands::choose);
        picker.setOverlayHost(mc.overlayHost);
        picker.show(mc.stage);
    }

    /**
     * Ties a find bar's Match Case, Regular Expression and Whole Word toggles to {@code find.toggleCase} and the
     * others, for the bar (or panel) {@code owner} the keyboard is in or the window shows.
     */
    static void bindFindOptions(Node owner, CheckBox matchCase, CheckBox regex, CheckBox word) {
        FocusCommands.bindIf(owner, "find.toggleCase", toggle(matchCase));
        FocusCommands.bindIf(owner, "find.toggleRegex", toggle(regex));
        FocusCommands.bindIf(owner, "find.toggleWholeWord", toggle(word));
    }

    /** Turns {@code box} over as a click on its toggle does; false, doing nothing, while it is disabled (no query). */
    static BooleanSupplier toggle(CheckBox box) {
        return () -> {
            if (box.isDisabled()) {
                return false;
            }
            box.fire();
            return true;
        };
    }

    /** What the window shows that a command may act on with the keyboard elsewhere: the active tab, the open tools. */
    static List<Node> showing(MainController mc) {
        List<Node> roots = new ArrayList<>();
        Tab tab = mc.activeTab();
        if (tab != null) {
            roots.add(tab.getContent());
        }
        for (ToolWindow window : mc.toolWindows.getOpenToolWindows()) {
            roots.add(window.contentIfBuilt());
        }
        roots.removeIf(Objects::isNull); // a tab or tool window with nothing built in it yet
        return roots;
    }
}
