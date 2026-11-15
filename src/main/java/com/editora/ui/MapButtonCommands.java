package com.editora.ui;

import java.util.List;
import java.util.function.Consumer;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.stage.Stage;

import com.editora.command.Command;

import static com.editora.i18n.Messages.tr;

/**
 * The commands behind the Project Map's buttons that only the mouse reached: the header bar's Back, Forward, help
 * and options, and the buttons of a card, a note or a column. A header command runs the method its button runs. A
 * card command acts on whatever the Map's keyboard is in, as {@link ProjectMapView#focusedCard} finds it: from the
 * Map scene's focus owner up, the first node that {@linkplain #bind took} the command runs it, and a column or a
 * note stuck on a card passes what it does not take on to its card. With the Map closed, or the keyboard in nothing
 * that takes the command, the status line says so.
 *
 * <p>The same walk serves the Map's menu rows ({@link MapMenuCommands}): a node may give the menu a right-click would
 * open there ({@link #menu}), and a command fires the row of it {@linkplain #row marked} with the command, or is
 * refused when the menu shows that row disabled. The walk itself, and the bindings, are {@link FocusCommands}', which the
 * other panels' commands use too; this class extends it so the Map's call sites name it as they did.
 */
final class MapButtonCommands extends FocusCommands {

    private static final String HIERARCHY_FLIP = "projectMap.hierarchyFlip";

    /** The card commands, in palette order: each runs what {@link #bind} gave it where the Map's keyboard is. */
    private static final List<String> CARD_COMMANDS = List.of(
            "projectMap.cardPreviousDeclaration",
            "projectMap.cardNextDeclaration",
            "projectMap.cardZoomOut",
            "projectMap.cardZoomIn",
            "projectMap.cardPin",
            "projectMap.cardStructure",
            "projectMap.cardNotes",
            "projectMap.cardClose",
            HIERARCHY_FLIP,
            "projectMap.changesOpenInEditor",
            "projectMap.changesCompare",
            "projectMap.trailStopNote");

    private MapButtonCommands() {}

    static void register(MainController mc) {
        // The list the Menu key on Back and Forward opens: with it a command, those two are not Tab stops.
        mc.registry.register(Command.of(
                "projectMap.history",
                () -> withMap(mc, view -> {
                    raise(view);
                    if (!view.showHistoryMenu()) {
                        mc.setStatus(tr("status.projectMap.noHistory"));
                    }
                })));
        // A trail stop's note never takes the keyboard (it is a sheet of labels), so its × has a command of its own.
        mc.registry.register(Command.of(
                "projectMap.trailNoteHide",
                () -> withMap(mc, view -> {
                    for (Node child : List.copyOf(view.viewsCanvasHost().getChildren())) {
                        if (child instanceof ProjectMapTrailNote note) {
                            note.close();
                            return;
                        }
                    }
                    mc.setStatus(tr("status.projectMap.noTrailNote"));
                })));
        mc.registry.register(Command.of("projectMap.back", () -> withMap(mc, view -> view.moveHistory(-1))));
        mc.registry.register(Command.of("projectMap.forward", () -> withMap(mc, view -> view.moveHistory(1))));
        mc.registry.register(Command.of(
                "projectMap.help",
                () -> withMap(mc, view -> {
                    raise(view);
                    view.toggleHelp();
                })));
        mc.registry.register(Command.of(
                "projectMap.options",
                () -> withMap(mc, view -> {
                    raise(view);
                    view.showOptions();
                })));
        for (String id : CARD_COMMANDS) {
            mc.registry.register(Command.of(id, () -> withMap(mc, view -> runWhereFocused(mc, view, id))));
        }
        MapMenuCommands.register(mc); // the rest of the Map's rows and buttons, found the same way
    }

    /** Runs {@code op} on the Map while its window is open; says that it is not otherwise. */
    static void withMap(MainController mc, Consumer<ProjectMapView> op) {
        ProjectPanel panel = mc.projectPanel;
        if (panel == null || !panel.isMapShowing()) {
            mc.setStatus(tr("status.projectMap.notShowing"));
        } else {
            op.accept(panel.mapView());
        }
    }

    /** Runs card command {@code id} on what the Map's keyboard is in, or says that nothing there takes it. */
    private static void runWhereFocused(MainController mc, ProjectMapView view, String id) {
        Scene scene = view.getScene();
        Runnable action = actionAt(scene == null ? null : scene.getFocusOwner(), id);
        if (action != null) {
            action.run();
        } else {
            mc.setStatus(
                    HIERARCHY_FLIP.equals(id) ? tr("status.projectMap.noHierarchy") : tr("status.projectMap.noCard"));
        }
    }

    /** Brings the Map's window forward and gives it the keyboard: the help and the menu are shown in it. */
    static void raise(ProjectMapView view) {
        if (view.getScene() != null && view.getScene().getWindow() instanceof Stage stage && !stage.isFocused()) {
            stage.toFront();
            stage.requestFocus();
        }
    }
}
