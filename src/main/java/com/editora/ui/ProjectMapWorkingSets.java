package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;

import com.editora.config.ProjectMapState;
import com.editora.config.ProjectMapWorkingSet;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * The Project Map's working sets (Map explore, position 1): named views of the Map — its open folders, selection,
 * zoom and pan, and open code cards with their Structure columns — kept per workspace in {@code WorkspaceState}
 * (schema v29). ⋯ → Working sets ▸ Save current as… names what the Map shows; one item per saved set shows it again
 * through the path a restart takes ({@link ProjectMapView#viewsApply}); Delete ▸ removes one. Only the sets of the
 * folder the Map shows are listed; a set keeps no Back/Forward places, so switching to one is one more place.
 */
final class ProjectMapWorkingSets {

    /** Sets kept per folder, at most: a menu, not a store. */
    static final int MAX_SETS = 30;

    private final ProjectMapView view;
    private final Menu menu = new Menu(tr("project.map.workingSets"));
    private List<ProjectMapWorkingSet> sets = new ArrayList<>();
    private Consumer<List<ProjectMapWorkingSet>> onChanged = ignored -> {};
    /** The set last saved or shown here, offered as the name when saving again. */
    private @Nullable String current;

    ProjectMapWorkingSets(ProjectMapView view) {
        this.view = view;
        menu.getStyleClass().add("project-map-working-sets");
        menu.setOnShowing(event -> fill());
        fill(); // never empty: a submenu with no items does not open
        // Its lists of sets are a pick away from the keyboard too (KBD-01, MENU-05).
        MapButtonCommands.bind(view, "projectMap.showWorkingSet", () -> pick(false));
        MapButtonCommands.bind(view, "projectMap.deleteWorkingSet", () -> pick(true));
    }

    /** Show Working Set… ({@code delete} false) and Delete Working Set…: the folder's sets, the one picked shown or deleted. */
    private void pick(boolean delete) {
        Path root = view.root;
        List<ProjectMapWorkingSet> mine = root != null && com.editora.vfs.Vfs.isLocal(root) ? forRoot() : List.of();
        view.pick(
                delete ? "projectMap.deleteWorkingSet" : "projectMap.showWorkingSet",
                mine.stream().map(ProjectMapWorkingSet::getName).toList(),
                -1,
                index -> {
                    if (delete) {
                        askAndDelete(mine.get(index).getName());
                    } else {
                        show(mine.get(index).getName());
                    }
                });
    }

    Menu menu() {
        return menu;
    }

    /** The workspace's sets (every folder's), and where the whole list goes after a change. */
    void setRemembered(
            @Nullable List<ProjectMapWorkingSet> stored, @Nullable Consumer<List<ProjectMapWorkingSet>> sink) {
        sets = stored == null ? new ArrayList<>() : new ArrayList<>(stored);
        onChanged = sink == null ? ignored -> {} : sink;
        fill();
    }

    /** The sets of the folder the Map shows, in the order they were saved. */
    List<ProjectMapWorkingSet> forRoot() {
        Path root = view.root;
        if (root == null) {
            return List.of();
        }
        String key = root.toString();
        return sets.stream().filter(set -> key.equals(set.getState().getRoot())).toList();
    }

    /** Builds the submenu for what is saved now. */
    void fill() {
        List<MenuItem> items = new ArrayList<>();
        Path root = view.root;
        boolean local = root != null && com.editora.vfs.Vfs.isLocal(root);
        MenuItem save =
                MapButtonCommands.row(new MenuItem(tr("project.map.workingSets.save")), "projectMap.saveWorkingSet");
        com.editora.editor.RowReasons.when(save, local, "row.unavailable.needsLocalFolder");
        save.setOnAction(event -> askAndSave());
        items.add(save);
        items.add(new SeparatorMenuItem());
        List<ProjectMapWorkingSet> mine = forRoot();
        if (!local) {
            items.add(disabled(tr("project.map.workingSets.local")));
        } else if (mine.isEmpty()) {
            items.add(disabled(tr("project.map.workingSets.none")));
        }
        Menu delete = new Menu(tr("project.map.workingSets.delete"));
        delete.getStyleClass().add("danger"); // a destructive row reads red, and so does each set under it
        for (ProjectMapWorkingSet set : mine) {
            MenuItem show = new MenuItem(set.getName());
            show.setMnemonicParsing(false);
            show.setOnAction(event -> show(set.getName()));
            items.add(show);
            MenuItem remove = new MenuItem(set.getName());
            remove.setMnemonicParsing(false);
            remove.getStyleClass().add("danger");
            remove.setOnAction(event -> askAndDelete(set.getName()));
            delete.getItems().add(remove);
        }
        if (!mine.isEmpty()) {
            items.add(new SeparatorMenuItem());
            items.add(delete);
        }
        menu.getItems().setAll(items);
    }

    /** A row that says something about the list (none here, a remote folder): a label, not a command. */
    private static MenuItem disabled(String text) {
        return com.editora.editor.RowReasons.placeholder(new MenuItem(text));
    }

    /** Save current as…: asks for a name (the current set's offered), then {@link #save}. */
    void askAndSave() {
        OverlayInput.Prompt prompt = view.viewsPrompt;
        if (prompt == null) {
            return;
        }
        prompt.show(
                tr("project.map.workingSets.saveTitle"),
                tr("project.map.workingSets.saveLabel"),
                current == null ? "" : current,
                this::save);
    }

    /**
     * Saves what the Map shows as {@code name}: a set of that name (ignoring case) is replaced where it stands, after
     * a question when it shows something else, else the set is added — up to {@link #MAX_SETS} per folder. False when
     * nothing was saved (no name, no local folder, the folder full, the set of that name kept).
     */
    boolean save(@Nullable String name) {
        String value = name == null ? "" : name.strip();
        ProjectMapState state = view.bearingsState();
        if (value.isEmpty() || state == null) {
            return false;
        }
        state.setHistory(List.of()); // a set is a view, not a trail of places: Back stays the user's
        state.setHistoryIndex(-1);
        int index = indexOf(value);
        if (index >= 0
                && !sameView(sets.get(index).getState(), state)
                && !Confirm.ask(owner(), replaceQuestion(sets.get(index).getName()))) {
            return false; // the set of that name shows something else, and the user kept it
        }
        if (index >= 0) {
            sets.set(index, new ProjectMapWorkingSet(sets.get(index).getName(), state));
            view.onStatus.accept(
                    tr("project.map.status.workingSet.updated", sets.get(index).getName()));
        } else if (forRoot().size() >= MAX_SETS) {
            view.onStatus.accept(tr("project.map.status.workingSet.full", MAX_SETS));
            return false;
        } else {
            sets.add(new ProjectMapWorkingSet(value, state));
            view.onStatus.accept(tr("project.map.status.workingSet.saved", value));
        }
        current = index >= 0 ? sets.get(index).getName() : value;
        changed();
        return true;
    }

    /** The question before the working set {@code name} is replaced by what the Map shows now. Pure. */
    static Confirm.Question replaceQuestion(String name) {
        return new Confirm.Question(
                tr("project.map.workingSets.replaceTitle"),
                null,
                tr("project.map.workingSets.replaceConfirm", name),
                tr("confirm.action.replace"));
    }

    /**
     * Whether two saved views are the same view, as they are stored: saving one over the other loses nothing. Pure.
     * Compared property by property, as they are stored, with no JSON made (PERF-01, Pauta 6.2.0): both ways, so a
     * map entry only one of them has counts.
     */
    static boolean sameView(ProjectMapState a, ProjectMapState b) {
        return SettingsModified.same(a, b) && SettingsModified.same(b, a);
    }

    private javafx.stage.@Nullable Window owner() {
        return view.getScene() == null ? null : view.getScene().getWindow();
    }

    /** Shows the set {@code name} of the folder the Map shows, as a restart shows the last session's state. */
    boolean show(String name) {
        int index = indexOf(name);
        if (index < 0) {
            return false;
        }
        ProjectMapWorkingSet set = sets.get(index);
        current = set.getName();
        view.viewsApply(copyOf(set.getState()));
        view.onStatus.accept(tr("project.map.status.workingSet.shown", set.getName()));
        return true;
    }

    /**
     * Delete Working Set… and the Delete submenu's row: asks, naming the set, then deletes it. False when the user
     * kept it, or there is no such set.
     */
    boolean askAndDelete(String name) {
        int index = indexOf(name);
        if (index < 0) {
            return false;
        }
        return Confirm.ask(owner(), deleteQuestion(sets.get(index).getName())) && delete(name);
    }

    /** The question before the working set {@code name} is deleted. Pure. */
    static Confirm.Question deleteQuestion(String name) {
        return new Confirm.Question(
                tr("project.map.workingSets.deleteTitle"),
                null,
                tr("project.map.workingSets.deleteConfirm", name),
                tr("confirm.action.delete"));
    }

    /** Deletes the set {@code name} of the folder the Map shows, with no question. */
    boolean delete(String name) {
        int index = indexOf(name);
        if (index < 0) {
            return false;
        }
        ProjectMapWorkingSet removed = sets.remove(index);
        if (removed.getName().equalsIgnoreCase(current == null ? "" : current)) {
            current = null;
        }
        view.onStatus.accept(tr("project.map.status.workingSet.deleted", removed.getName()));
        changed();
        return true;
    }

    /** Where {@code name} is among the sets of the folder the Map shows (ignoring case); -1 when it is not. */
    private int indexOf(String name) {
        Path root = view.root;
        if (root == null || name == null) {
            return -1;
        }
        String key = root.toString();
        String wanted = name.strip().toLowerCase(Locale.ROOT);
        for (int i = 0; i < sets.size(); i++) {
            ProjectMapWorkingSet set = sets.get(i);
            if (key.equals(set.getState().getRoot())
                    && set.getName().strip().toLowerCase(Locale.ROOT).equals(wanted)) {
                return i;
            }
        }
        return -1;
    }

    private void changed() {
        onChanged.accept(List.copyOf(sets));
        fill();
    }

    /**
     * A copy, so restoring a set (which prunes what is gone) never edits the stored one. The Back/Forward places are
     * dropped: the set's selection becomes one more place instead.
     */
    private static ProjectMapState copyOf(ProjectMapState state) {
        ProjectMapState copy = new ProjectMapState();
        copy.setRoot(state.getRoot());
        copy.setExpanded(state.getExpanded());
        copy.setSelected(state.getSelected());
        copy.setZoom(state.getZoom());
        copy.setOffsetX(state.getOffsetX());
        copy.setOffsetY(state.getOffsetY());
        List<ProjectMapState.Card> cards = new ArrayList<>();
        for (ProjectMapState.Card card : state.getCards()) {
            cards.add(card.copy()); // its caret and answers too (gap 9)
        }
        copy.setCards(cards);
        return copy;
    }
}
