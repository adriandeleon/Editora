package com.editora.ui;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ButtonBase;
import javafx.stage.Stage;

import com.editora.command.Command;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * The commands behind the Project Map's buttons that only the mouse reached: the header bar's Back, Forward, help
 * and options, and the buttons of a card, a note or a column. A header command runs the method its button runs. A
 * card command acts on whatever the Map's keyboard is in, as {@link ProjectMapView#focusedCard} finds it: from the
 * Map scene's focus owner up, the first node that {@linkplain #bind took} the command runs it, and a column or a
 * note stuck on a card passes what it does not take on to its card. With the Map closed, or the keyboard in nothing
 * that takes the command, the status line says so.
 */
final class MapButtonCommands {

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
            HIERARCHY_FLIP);

    /** The node property a column or a note stuck on a card keeps its card under (see {@link #stuckOn}). */
    private static final String CARD = "editora.mapCard";

    private MapButtonCommands() {}

    static void register(MainController mc) {
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
    }

    /** Has {@code commandId} run {@code action} while the Map's keyboard is in {@code owner}: a card, a column. */
    static void bind(Node owner, String commandId, Runnable action) {
        owner.getProperties().put(commandId, action);
    }

    /** As {@link #bind(Node, String, Runnable)} for a button: it runs {@code action} too, under the command's name. */
    static void bind(Node owner, ButtonBase button, String commandId, Runnable action) {
        bind(owner, button, commandId, () -> CommandTips.title(commandId), action);
    }

    /**
     * As {@link #bind(Node, ButtonBase, String, Runnable)} for a button that keeps its own words ({@code title}) where
     * they say more than the command's: which column it closes, that a note closed here is kept.
     */
    static void bind(Node owner, ButtonBase button, String commandId, Supplier<String> title, Runnable action) {
        bind(owner, commandId, action);
        button.setOnAction(event -> action.run());
        CommandTips.name(button, commandId, title);
    }

    /** {@code attachment} (a column, a note) is stuck on {@code card}: a command it does not take is the card's. */
    static void stuckOn(Node attachment, Node card) {
        attachment.getProperties().put(CARD, card);
    }

    /**
     * What {@code commandId} runs where {@code focus} is, or null: asked of each node from it up, the way
     * {@link ProjectMapView#focusedCard} walks, a node {@link #stuckOn} a card going on from the card.
     */
    static @Nullable Runnable actionAt(@Nullable Node focus, String commandId) {
        Node node = focus;
        while (node != null) {
            if (node.hasProperties() && node.getProperties().get(commandId) instanceof Runnable action) {
                return action;
            }
            node = node.hasProperties() && node.getProperties().get(CARD) instanceof Node card
                    ? card
                    : node.getParent();
        }
        return null;
    }

    /** Runs {@code op} on the Map while its window is open; says that it is not otherwise. */
    private static void withMap(MainController mc, Consumer<ProjectMapView> op) {
        ProjectPanel panel = mc.projectPanel;
        if (panel == null || !panel.isMapShowing()) {
            mc.setStatus(tr("status.projectMap.notShowing"));
        } else {
            op.accept(panel.mapView()); // PLACEHOLDER: ProjectPanel has no accessor for its private mapView yet
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
    private static void raise(ProjectMapView view) {
        if (view.getScene() != null && view.getScene().getWindow() instanceof Stage stage && !stage.isFocused()) {
            stage.toFront();
            stage.requestFocus();
        }
    }
}
