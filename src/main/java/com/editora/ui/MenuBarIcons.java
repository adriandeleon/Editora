package com.editora.ui;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import javafx.scene.Node;

/**
 * The glyph shown beside a main-menu item, by command id.
 *
 * <p>Every right-click menu in Editora already carries an icon on every item — a convention with
 * {@link Icons}, {@link com.editora.editor.MenuIcons} and {@link FileIcons} behind it — while the menu bar
 * carried none. This closes that, reusing the glyphs those surfaces already use so an action looks the same
 * wherever it is reached from: Save is the same floppy on the toolbar, in the File menu and in a context
 * menu.
 *
 * <p><b>Deliberately partial.</b> Around half the ~135 menu entries map to something; the rest return
 * {@code null} and render with an empty icon column. An invented glyph is worse than none — a picture that
 * does not mean the thing it sits beside has to be read *and then* discounted — so an entry gets one only
 * where an existing icon genuinely depicts it. The column is reserved either way, so a menu with a mix stays
 * aligned (see {@code MainMenuBar.newRow}).
 *
 * <p>Not used on macOS, where the system menu bar owns rendering and does not show item graphics — which is
 * also the platform convention there.
 */
final class MenuBarIcons {

    private MenuBarIcons() {}

    /**
     * Command id → glyph factory. A {@link Supplier} rather than a {@link Node}: every menu row needs its
     * own instance, since a node belongs to one scene.
     *
     * <p>A table rather than a switch so a test can walk every entry — a mapping whose id is a typo is
     * otherwise invisible, rendering as the empty icon column that half the entries legitimately have.
     */
    private static final Map<String, Supplier<Node>> ICONS = new LinkedHashMap<>();

    private static void add(String commandId, Supplier<Node> glyph) {
        ICONS.put(commandId, glyph);
    }

    static {
        add("file.new", Icons::newFile);
        add("file.newFileOfType", Icons::fileSheet);
        add("template.new", Icons::template);
        add("project.newFromTemplate", Icons::template);
        add("template.manage", Icons::template);
        add("maven.newProject", Icons::maven);
        add("file.open", Icons::open);
        add("file.find", Icons::open);
        add("project.open", Icons::openFolder);
        add("file.save", Icons::save);
        add("file.saveAs", Icons::saveAs);
        add("file.saveAsAdmin", Icons::saveAs);
        add("config.export", Icons::saveAs);
        add("buffer.close", Icons::closeTab);
        add("buffer.closeOthers", Icons::closeOtherTabs);
        add("buffer.closeAll", Icons::closeAllTabs);
        add("buffer.togglePin", Icons::pin);
        add("file.clearRecent", Icons::trash);
        add("app.quit", Icons::quit);
        add("edit.undo", Icons::undo);
        add("edit.redo", Icons::redo);
        add("edit.cut", Icons::cut);
        add("edit.copy", Icons::copy);
        add("edit.copyWithHighlighting", Icons::copy);
        add("edit.paste", Icons::paste);
        add("edit.selectAll", Icons::selectAll);
        add("edit.selectAllOccurrences", Icons::selectAll);
        add("edit.moveLineUp", Icons::arrowUp);
        add("edit.moveLineDown", Icons::arrowDown);
        add("find.show", Icons::find);
        add("find.replace", Icons::find);
        add("edit.queryReplace", Icons::find);
        add("edit.occur", Icons::find);
        add("find.next", Icons::chevronRight);
        add("find.previous", Icons::chevronLeft);
        add("search.inFiles", Icons::findInFiles);
        add("search.inFilesPopup", Icons::findInFiles);
        add("lsp.findReferences", Icons::findInFiles);
        add("view.splitVertical", Icons::splitVertical);
        add("view.splitEditorRight", Icons::splitVertical);
        add("view.splitHorizontal", Icons::splitHorizontal);
        add("view.splitEditorDown", Icons::splitHorizontal);
        add("view.togglePreview", Icons::previewOnly);
        add("view.toggleSplitPreview", Icons::previewOnly);
        add("view.toggleSimpleMode", Icons::simpleMode);
        add("view.doctor", Icons::doctor);
        add("window.fullScreen", Icons::maximize);
        add("window.maximize", Icons::maximize);
        add("nav.back", Icons::chevronLeft);
        add("nav.forward", Icons::chevronRight);
        add("bookmarks.jump", Icons::bookmark);
        add("tool.project", Icons::project);
        add("tool.structure", Icons::structure);
        add("lsp.gotoSymbol", Icons::structure);
        add("tool.search", Icons::findInFiles);
        add("tool.problems", Icons::problems);
        add("lsp.rename", Icons::edit);
        add("lsp.callHierarchy", Icons::outline);
        add("lsp.typeHierarchy", Icons::outline);
        add("snippets.insert", Icons::fileSheet);
        add("snippets.manage", Icons::settings);
        add("file.run", Icons::run);
        add("file.runWithArgs", Icons::run);
        add("debug.continue", Icons::run);
        add("run.rerun", Icons::refresh);
        add("help.checkForUpdates", Icons::refresh);
        add("git.fetch", Icons::refresh);
        add("run.stop", Icons::stopSquare);
        add("debug.start", Icons::debug);
        add("debug.stepOver", Icons::debugStepOver);
        add("debug.stepInto", Icons::debugStepInto);
        add("debug.stepOut", Icons::debugStepOut);
        add("debug.stop", Icons::debugStop);
        add("test.run", Icons::testResults);
        add("test.rerunFailed", Icons::refresh);
        add("git.commit", Icons::git);
        add("git.switchBranch", Icons::git);
        add("git.newBranch", Icons::plus);
        add("git.clone", Icons::git);
        add("git.push", Icons::gitPush);
        add("git.pull", Icons::arrowDown);
        add("git.stash", Icons::stash);
        add("git.stashPop", Icons::stash);
        add("git.unstash", Icons::stash);
        add("tool.gitLog", Icons::gitLog);
        add("git.fileHistory", Icons::history);
        add("diff.vsHead", Icons::diff);
        add("diff.compareWith", Icons::diff);
        add("externalTool.run", Icons::tools);
        add("plugins.browse", Icons::plugin);
        add("install.languageServer", Icons::plugin);
        add("palette.show", Icons::palette);
        add("view.debugLog", Icons::terminal);
        add("help.about", Icons::about);
    }

    /** The glyph for {@code commandId}, or {@code null} when nothing existing depicts it. */
    static Node forCommand(String commandId) {
        Supplier<Node> glyph = commandId == null ? null : ICONS.get(commandId);
        return glyph == null ? null : glyph.get();
    }

    /** Every mapped command id, for the test that keeps this table and the menu model in step. */
    static Set<String> mappedCommandIds() {
        return Set.copyOf(ICONS.keySet());
    }
}
