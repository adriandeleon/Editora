package com.editora.ui;

import java.util.List;
import java.util.function.Consumer;

import javafx.scene.Node;
import javafx.scene.Scene;

import com.editora.command.Command;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * The commands behind the rest of the Project Map's right-click rows and buttons (KBD-01 and MENU-05, Pauta 6.0.1):
 * the options menu's rows, the filters, the zoom bar, the results strip, a column's header, the row menu of the map
 * and of a card's Structure column, a card's own menu, and the bars and menus of the other cards.
 *
 * <p>Each command does what its row or button does to what the Map's keyboard is on. From the Map scene's focus
 * owner up ({@link MapButtonCommands#find}), the first node that bound the command runs it, and the first whose
 * menu has a row for it fires that row, built as a right-click would build it for the selected row or the caret:
 * the map's rows, a card, a column stuck on a card, a list or a graph in a card. Past them the Map itself answers:
 * its filters, its zoom bar, and its options menu. A row the menu would show disabled is refused, and with nothing
 * there that offers the command, or the Map closed, the status line says so.
 */
final class MapMenuCommands {

    /** The commands, in palette order. */
    static final List<String> IDS = List.of(
            // the options menu
            "projectMap.toggleKeepZoom",
            "projectMap.toggleFocusNewColumn",
            "projectMap.toggleSingleClickOpens",
            "projectMap.toggleCompactChains",
            "projectMap.toggleCompactRows",
            "projectMap.toggleHideIgnored",
            "projectMap.toggleRecentChanges",
            "projectMap.toggleDependencyOverlay",
            "projectMap.toggleFollowDebugger",
            "projectMap.toggleHideNotes",
            "projectMap.toggleFollowActiveFile",
            "projectMap.flowLeftToRight",
            "projectMap.flowRightToLeft",
            "projectMap.flowTopToBottom",
            "projectMap.flowBottomToTop",
            "projectMap.overview",
            "projectMap.projectDependencies",
            "projectMap.projectMetrics",
            "projectMap.reviewChanges",
            "projectMap.switchCard",
            "projectMap.collapseAll",
            "projectMap.saveWorkingSet",
            "projectMap.showWorkingSet",
            "projectMap.deleteWorkingSet",
            "projectMap.newTrail",
            "projectMap.addTrailStop",
            "projectMap.playTrail",
            "projectMap.editTrail",
            "projectMap.trailEditPlaying",
            "projectMap.trailShowStop",
            "projectMap.exportPng",
            "projectMap.exportSvg",
            // the filters, the breadcrumb, the zoom bar and the results strip
            "projectMap.filterOpen",
            "projectMap.filterUnsaved",
            "projectMap.filterGitChanged",
            "projectMap.filterBookmarks",
            "projectMap.filterPersonalNotes",
            "projectMap.filterProblems",
            "projectMap.filterType",
            "projectMap.toggleHideUnmatched",
            "projectMap.clearFilters",
            "projectMap.breadcrumb",
            "projectMap.zoomOut",
            "projectMap.zoomIn",
            "projectMap.fitView",
            "projectMap.centerSelection",
            "projectMap.resetView",
            "projectMap.previousMatch",
            "projectMap.nextMatch",
            "projectMap.goToMatch",
            // a row of the map, a declaration, a card
            "projectMap.preview",
            "projectMap.openInEditor",
            "projectMap.openNarrowed",
            "projectMap.related",
            "projectMap.rowStructure",
            "projectMap.personalNotes",
            "projectMap.columnShowHidden",
            "projectMap.columnPin",
            "projectMap.closeColumn",
            "projectMap.closeOtherBranches",
            "projectMap.showDefinition",
            "projectMap.showDeclaration",
            "projectMap.showTypeDefinition",
            "projectMap.showImplementations",
            "projectMap.showSuperMethod",
            "projectMap.showReferences",
            "projectMap.showCallers",
            "projectMap.showSupertypes",
            "projectMap.showSubtypes",
            "projectMap.showCallHierarchy",
            "projectMap.showTypeHierarchy",
            "projectMap.showSymbolGraph",
            "projectMap.editorDefinition",
            "projectMap.editorReferences",
            "projectMap.editorImplementation",
            "projectMap.editorTypeHierarchy",
            "projectMap.editorCallHierarchy",
            "projectMap.dependencies",
            "projectMap.changes",
            "projectMap.metrics",
            "projectMap.declarationHistory",
            "projectMap.fileHistory",
            "projectMap.annotate",
            "projectMap.copyPermalink",
            "projectMap.cardCopy",
            "projectMap.showInStructure",
            "projectMap.moveToFreeSpace",
            "projectMap.cardFindPrevious",
            "projectMap.cardFindNext",
            "projectMap.cardFindMatchCase",
            "projectMap.cardFindClose",
            "projectMap.cardGoToLineClose",
            "projectMap.cardToggleBreakpoint",
            "projectMap.cardRun",
            "projectMap.revealInFileManager",
            // the other cards
            "projectMap.cardBack",
            "projectMap.cardRefresh",
            "projectMap.showOnMap",
            "projectMap.openCard",
            "projectMap.focusHere",
            "projectMap.lookInside",
            "projectMap.graphFromHere",
            "projectMap.depsAround",
            "projectMap.depsInside",
            "projectMap.cardFiles",
            "projectMap.cardFolders",
            "projectMap.cardParts",
            "projectMap.historyChanges",
            "projectMap.historyBlame",
            "projectMap.changesPrevious",
            "projectMap.changesNext",
            "projectMap.changesAgainst",
            "projectMap.placesTests",
            "projectMap.allPlaces",
            "projectMap.allNotes",
            "projectMap.trailRename");

    private MapMenuCommands() {}

    static void register(MainController mc) {
        for (String id : IDS) {
            mc.registry.register(Command.of(id, () -> MapButtonCommands.withMap(mc, view -> run(view, id))));
        }
    }

    /** Runs command {@code id} on what the Map's keyboard is on, or says on the Map's status line why not. */
    static void run(ProjectMapView view, String id) {
        MapButtonCommands.raise(view); // it acts in the Map's window, which the palette may have left behind
        if (!runOffered(view, id, view.onStatus)) {
            view.onStatus.accept(tr("status.projectMap.notOffered", CommandTips.title(id)));
        }
    }

    /**
     * Runs command {@code id} where the Map's keyboard is, as {@link #run} does, and answers whether anything there
     * took it: false when nothing offers it (nothing is said then), true when it ran or was refused on {@code status}.
     */
    static boolean runOffered(ProjectMapView view, String id, Consumer<String> status) {
        Scene scene = view.getScene();
        Node focus = scene == null ? null : scene.getFocusOwner();
        MapButtonCommands.Found found = MapButtonCommands.find(focus, id);
        if (found == null && !within(focus, view)) {
            found = MapButtonCommands.find(view, id); // the keyboard is outside the map: its search field
        }
        if (found == null) {
            return false;
        }
        java.util.function.BooleanSupplier action = found.action();
        if (action == null || !action.getAsBoolean()) {
            String reason = found.reason(); // a grayed row says why, as its tooltip does (KBD-04)
            status.accept(
                    reason == null
                            ? tr("status.projectMap.rowUnavailable", CommandTips.title(id))
                            : tr("status.commandUnavailable", CommandTips.title(id), reason));
        }
        return true;
    }

    /** Whether {@code node} is {@code view} or inside it, so a walk up from it has asked the view already. */
    private static boolean within(@Nullable Node node, Node view) {
        for (Node at = node; at != null; at = at.getParent()) {
            if (at == view) {
                return true;
            }
        }
        return false;
    }
}
