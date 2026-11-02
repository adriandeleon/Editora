package com.editora.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;

import com.editora.config.MapTrails;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * The Project Map's trails (Map explore, position 2): guided tours through a project, kept in it as {@code
 * .editora/map-trails/<name>.json} ({@link MapTrails}) so a team can commit them. ⋯ → Trails ▸ New trail…, Add this
 * card as a stop (also at the end of a code card's menu), one Play item per trail, and Edit ▸ a trail.
 *
 * <p>Playing puts a small bar over the map — ‹ Stop 3 of 9 › the stop's title, Edit, × — and each stop reveals its
 * file on the map and opens its card at the stop's lines (a declaration's card for a declaration stop), with the
 * stop's note stuck on as a sheet ({@link ProjectMapTrailNote}). The card opens through the path a restart's cards
 * take ({@link ProjectMapView#viewsShowCard}), so a stop played on a Map that has only just been given its folder
 * waits for its row and outline instead of opening nowhere. A stop whose file is gone says so; one whose declaration
 * is gone shows its lines in the file. A card a stop opened closes when the trail moves on.
 *
 * <p>Edit opens a card listing the trail's stops ({@link ProjectMapTrailEditor}): reorder, remove, rename a stop or
 * the trail, change a stop's note, play from a stop. Every change is written at once. Local folders only.
 */
final class ProjectMapTrails {

    private final ProjectMapView view;
    private final Menu menu = new Menu(tr("project.map.trails"));
    /** The trail New trail… made or Edit or Play last chose: where Add this card as a stop goes. File name stem. */
    private @Nullable String current;

    // Playing
    private MapTrails.@Nullable Listed playing;
    private int stop = -1;
    /** Bumped by every stop shown: a card that opens for a stop already left is not given that stop's note. */
    private int generation;
    /** The card the shown stop is on, and whether the trail opened it (it then closes when the trail moves on). */
    private @Nullable ProjectMapPreview stopCard;

    private boolean stopCardOpened;

    private final HBox bar = new HBox(4);
    private final Button previous = new Button("‹");
    private final Button next = new Button("›");
    private final Label position = new Label();
    private final Label title = new Label();
    private final Button editButton = new Button();
    private final Button closeButton = new Button("×");

    private @Nullable ProjectMapTrailEditor editor;

    ProjectMapTrails(ProjectMapView view) {
        this.view = view;
        menu.getStyleClass().add("project-map-trails");
        menu.setOnShowing(event -> fill());
        fill(); // never empty: a submenu with no items does not open
        buildBar();
    }

    Menu menu() {
        return menu;
    }

    private void buildBar() {
        bar.getStyleClass().add("project-map-trail-bar");
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        bar.setPickOnBounds(false);
        bar.setViewOrder(-1); // above the cards, as the zoom bar is
        bar.getProperties().put("editora.ownsKeys", Boolean.TRUE);
        for (Button button : List.of(previous, next, editButton, closeButton)) {
            button.getStyleClass().addAll("flat", "project-map-trail-bar-button");
            button.setMinWidth(Region.USE_PREF_SIZE);
        }
        previous.setOnAction(event -> step(-1));
        next.setOnAction(event -> step(1));
        Icons.name(previous, tr("project.map.trails.bar.previous"));
        Icons.name(next, tr("project.map.trails.bar.next"));
        editButton.setGraphic(Icons.edit());
        Icons.name(editButton, tr("project.map.trails.bar.edit"));
        editButton.setOnAction(event -> {
            MapTrails.Listed shown = playing;
            if (shown != null) {
                edit(shown.name());
            }
        });
        Icons.name(closeButton, tr("project.map.trails.bar.close"));
        closeButton.setOnAction(event -> close());
        position.getStyleClass().add("project-map-trail-bar-position");
        position.setMinWidth(Region.USE_PREF_SIZE);
        title.getStyleClass().add("project-map-trail-bar-title");
        title.setMinWidth(0);
        title.setMaxWidth(420);
        HBox.setHgrow(title, Priority.ALWAYS);
        bar.getChildren().setAll(previous, position, next, title, editButton, closeButton);
        bar.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ESCAPE) {
                close();
                view.focusMap();
                event.consume();
            }
        });
        StackPane.setAlignment(bar, Pos.TOP_CENTER);
        StackPane.setMargin(bar, new Insets(8, 120, 0, 120));
    }

    // --- the menu ---------------------------------------------------------------------------------------------

    /** The project root the trails are kept in: the Map's folder, when it is local; else null. */
    private @Nullable Path root() {
        Path root = view.root;
        return root != null && com.editora.vfs.Vfs.isLocal(root) ? root : null;
    }

    /** Builds the submenu for the trails on disk now. */
    void fill() {
        List<MenuItem> items = new ArrayList<>();
        Path root = root();
        MenuItem create = new MenuItem(tr("project.map.trails.new"));
        create.setDisable(root == null);
        create.setOnAction(event -> askNew(null));
        MenuItem add = new MenuItem(tr("project.map.trails.addStop"));
        add.setDisable(root == null || lastCodeCard() == null);
        add.setOnAction(event -> {
            ProjectMapPreview card = lastCodeCard();
            if (card != null) {
                addStop(card);
            }
        });
        items.add(create);
        items.add(add);
        items.add(new SeparatorMenuItem());
        List<MapTrails.Listed> listed = root == null ? List.of() : MapTrails.list(root);
        Menu edit = new Menu(tr("project.map.trails.edit"));
        if (root == null) {
            items.add(disabled(tr("project.map.trails.local")));
        } else if (listed.isEmpty()) {
            items.add(disabled(tr("project.map.trails.none")));
        }
        for (MapTrails.Listed trail : listed) {
            if (trail.trail() == null) {
                items.add(disabled(tr("project.map.trails.unreadable", trail.name())));
                continue;
            }
            MenuItem play = new MenuItem(tr("project.map.trails.play", trail.label()));
            play.setMnemonicParsing(false);
            play.setOnAction(event -> play(trail.name(), 0));
            items.add(play);
            MenuItem change =
                    new MenuItem(trail.readOnly() ? tr("project.map.trails.readOnly", trail.label()) : trail.label());
            change.setMnemonicParsing(false);
            change.setDisable(trail.readOnly());
            change.setOnAction(event -> edit(trail.name()));
            edit.getItems().add(change);
        }
        if (!edit.getItems().isEmpty()) {
            items.add(new SeparatorMenuItem());
            items.add(edit);
        }
        menu.getItems().setAll(items);
    }

    private static MenuItem disabled(String text) {
        MenuItem item = new MenuItem(text);
        item.setMnemonicParsing(false);
        item.setDisable(true);
        return item;
    }

    /** What a code card's menu adds: Add this card as a stop. */
    List<MenuItem> cardMenu(ProjectMapPreview card) {
        if (root() == null) {
            return List.of();
        }
        MenuItem add = new MenuItem(tr("project.map.trails.addStop"));
        add.setOnAction(event -> addStop(card));
        return List.of(add);
    }

    /** The code card used last: what the ⋯ menu's Add this card as a stop adds. */
    private @Nullable ProjectMapPreview lastCodeCard() {
        for (MapCards.OpenCard card : view.cards.openCards()) {
            if (!card.note() && card.node() instanceof ProjectMapPreview code && code.path() != null) {
                return code;
            }
        }
        return null;
    }

    // --- making a trail ------------------------------------------------------------------------------------

    /** New trail…: asks for a name; {@code then} gets the new trail's name once it is written. */
    void askNew(java.util.function.@Nullable Consumer<String> then) {
        OverlayInput.Prompt prompt = view.viewsPrompt;
        if (prompt == null || root() == null) {
            return;
        }
        prompt.show(tr("project.map.trails.newTitle"), tr("project.map.trails.newLabel"), "", name -> {
            String created = create(name);
            if (created != null && then != null) {
                then.accept(created);
            } else if (created != null) {
                edit(created); // an empty trail's card says how stops are added
            }
        });
    }

    /** Writes an empty trail called {@code name}; its file name stem, or null (blank, taken, or not written). */
    @Nullable
    String create(@Nullable String name) {
        Path root = root();
        String value = name == null ? "" : name.strip();
        String stem = MapTrails.fileName(value);
        if (root == null || stem.isEmpty()) {
            return null;
        }
        Path file = MapTrails.fileFor(root, stem);
        if (Files.exists(file)) {
            view.onStatus.accept(tr("project.map.status.trail.exists", value));
            return null;
        }
        try {
            MapTrails.save(root, stem, new MapTrails.Trail(value, List.of()));
        } catch (IOException failed) {
            view.onStatus.accept(tr("project.map.status.trail.saveFailed", value, reason(failed)));
            return null;
        }
        current = stem;
        view.onStatus.accept(tr(
                "project.map.status.trail.created",
                value,
                root.relativize(file).toString().replace('\\', '/')));
        return stem;
    }

    /**
     * Add this card as a stop: asks for the stop's note, then appends the card — its file, its declaration for a
     * declaration card, and its lines (a declaration's, else the selected lines or those in sight) — to the current
     * trail. With no current trail, New trail… comes first.
     */
    void addStop(ProjectMapPreview card) {
        MapTrails.Stop made = stopOf(card);
        if (made == null) {
            view.onStatus.accept(tr("project.map.status.trail.noCard"));
            return;
        }
        String target = current;
        Path root = root();
        if (target == null || root == null || !Files.exists(MapTrails.fileFor(root, target))) {
            askNew(created -> askNote(created, made));
            return;
        }
        askNote(target, made);
    }

    private void askNote(String trail, MapTrails.Stop made) {
        OverlayInput.Prompt prompt = view.viewsPrompt;
        Path root = root();
        if (prompt == null || root == null) {
            return;
        }
        MapTrails.Listed listed = MapTrails.read(MapTrails.fileFor(root, trail));
        String label = listed.label();
        prompt.show(
                tr("project.map.trails.stopTitle", label),
                tr("project.map.trails.stopLabel"),
                "",
                note -> append(trail, made.withNote(note == null ? "" : note.strip())));
    }

    /** Appends {@code made} to trail {@code name} and writes it; false when the trail could not be changed. */
    boolean append(String name, MapTrails.Stop made) {
        Path root = root();
        if (root == null) {
            return false;
        }
        MapTrails.Listed listed = MapTrails.read(MapTrails.fileFor(root, name));
        MapTrails.Trail trail = listed.trail();
        if (trail == null || listed.readOnly()) {
            view.onStatus.accept(tr(
                    "project.map.status.trail.notWritable",
                    listed.label(),
                    trail == null ? listed.problem() : "version " + trail.version()));
            return false;
        }
        if (trail.stops().size() >= MapTrails.MAX_STOPS) {
            view.onStatus.accept(tr("project.map.status.trail.full", MapTrails.MAX_STOPS));
            return false;
        }
        List<MapTrails.Stop> stops = new ArrayList<>(trail.stops());
        stops.add(made);
        if (!write(name, trail.withStops(stops))) {
            return false;
        }
        current = name;
        view.onStatus.accept(tr("project.map.status.trail.stopAdded", listed.label(), stops.size()));
        return true;
    }

    /** A stop made of {@code card}: null for a card that shows no file of the Map's folder. */
    MapTrails.@Nullable Stop stopOf(ProjectMapPreview card) {
        Path root = root();
        Path file = card.path();
        if (root == null || file == null) {
            return null;
        }
        String relative = ProjectMapView.bearingsRelative(root, file);
        if (relative == null || relative.isEmpty()) {
            return null;
        }
        Path key = keyOf(card);
        String declaration = key != null && ProjectMapStructure.isSymbolPath(key) && key.startsWith(file)
                ? file.relativize(key).toString().replace('\\', '/')
                : "";
        int[] lines = card.viewsStopLines();
        ProjectMapPreview.Range range = card.range();
        String name = range != null && !range.symbol().isBlank()
                ? range.symbol()
                : file.getFileName() == null ? relative : file.getFileName().toString();
        return new MapTrails.Stop(
                relative, declaration, lines == null ? 0 : lines[0], lines == null ? 0 : lines[1], name, "");
    }

    private @Nullable Path keyOf(ProjectMapPreview card) {
        for (MapCards.OpenCard open : view.cards.openCards()) {
            if (open.node() == card) {
                return open.key();
            }
        }
        return card.path();
    }

    /** Writes trail {@code name}; says why when it cannot. Refreshes what plays and edits it. */
    boolean write(String name, MapTrails.Trail trail) {
        Path root = root();
        if (root == null) {
            return false;
        }
        try {
            Path file = MapTrails.save(root, name, trail);
            refreshed(MapTrails.read(file));
            return true;
        } catch (MapTrails.NotWritableException refused) {
            view.onStatus.accept(tr("project.map.status.trail.notWritable", trail.title(), refused.getMessage()));
        } catch (IOException failed) {
            view.onStatus.accept(tr("project.map.status.trail.saveFailed", trail.title(), reason(failed)));
        }
        return false;
    }

    /** Renames trail {@code name} to {@code to}; the new name's stem, or null when it was not renamed. */
    @Nullable
    String rename(String name, String to) {
        Path root = root();
        String value = to == null ? "" : to.strip();
        if (root == null || MapTrails.fileName(value).isEmpty()) {
            return null;
        }
        MapTrails.Listed listed = MapTrails.read(MapTrails.fileFor(root, name));
        MapTrails.Trail trail = listed.trail();
        if (trail == null) {
            return null;
        }
        try {
            Path file = MapTrails.rename(root, name, value, trail);
            MapTrails.Listed renamed = MapTrails.read(file);
            if (name.equals(current)) {
                current = renamed.name();
            }
            MapTrails.Listed shown = playing;
            if (shown != null && shown.name().equals(name)) {
                playing = renamed;
            }
            ProjectMapTrailEditor open = editor;
            if (open != null && open.name().equals(name)) {
                open.show(renamed); // the editor follows the trail to its new file
            }
            refreshed(renamed);
            return renamed.name();
        } catch (java.nio.file.FileAlreadyExistsException taken) {
            view.onStatus.accept(tr("project.map.status.trail.exists", value));
        } catch (IOException failed) {
            view.onStatus.accept(tr("project.map.status.trail.saveFailed", value, reason(failed)));
        }
        return null;
    }

    private void refreshed(MapTrails.Listed listed) {
        MapTrails.Listed shown = playing;
        if (shown != null && shown.name().equals(listed.name()) && listed.trail() != null) {
            playing = listed;
            int count = listed.trail().stops().size();
            if (count == 0) {
                close();
            } else {
                stop = Math.min(stop, count - 1);
                updateBar();
            }
        }
        ProjectMapTrailEditor open = editor;
        if (open != null && (open.name().equals(listed.name()))) {
            open.show(listed);
        }
    }

    private static String reason(IOException failed) {
        return failed.getMessage() == null ? failed.getClass().getSimpleName() : failed.getMessage();
    }

    // --- playing ------------------------------------------------------------------------------------------------

    /** Play: reads trail {@code name} and shows its stop {@code index} (0-based). False when it cannot be played. */
    boolean play(String name, int index) {
        Path root = root();
        if (root == null) {
            return false;
        }
        MapTrails.Listed listed = MapTrails.read(MapTrails.fileFor(root, name));
        MapTrails.Trail trail = listed.trail();
        if (trail == null) {
            view.onStatus.accept(
                    tr("project.map.status.trail.unreadable", listed.file().getFileName(), listed.problem()));
            return false;
        }
        if (trail.stops().isEmpty()) {
            view.onStatus.accept(tr("project.map.status.trail.empty", listed.label()));
            return false;
        }
        playing = listed;
        current = listed.name();
        StackPane host = view.viewsCanvasHost();
        if (!host.getChildren().contains(bar)) {
            host.getChildren().add(bar);
        }
        showStop(Math.clamp(index, 0, trail.stops().size() - 1));
        return true;
    }

    /** ‹ and ›: the stop before or after the shown one. */
    void step(int delta) {
        MapTrails.Listed shown = playing;
        if (shown == null || shown.trail() == null) {
            return;
        }
        int target = stop + delta;
        if (target >= 0 && target < shown.trail().stops().size()) {
            showStop(target);
        }
    }

    /** Whether a trail is playing; the name of its file, its stop shown (0-based). For tests and the probe. */
    @Nullable
    String playingName() {
        MapTrails.Listed shown = playing;
        return shown == null ? null : shown.name();
    }

    int stopShown() {
        return stop;
    }

    HBox bar() {
        return bar;
    }

    @Nullable
    ProjectMapPreview stopCard() {
        return stopCard;
    }

    @Nullable
    ProjectMapTrailEditor editor() {
        return editor;
    }

    private void showStop(int index) {
        MapTrails.Listed shown = playing;
        Path root = root();
        if (shown == null || shown.trail() == null || root == null) {
            return;
        }
        int ticket = ++generation;
        leaveStop();
        stop = index;
        updateBar();
        MapTrails.Stop target = shown.trail().stops().get(index);
        Path file = ProjectMapView.bearingsResolve(root, target.file());
        if (file == null || target.file().isEmpty() || !Files.isRegularFile(file)) {
            view.onStatus.accept(tr("project.map.status.trail.stopGone", index + 1, target.file()));
            return;
        }
        view.revealPath(file);
        Path key = target.declaration().isEmpty()
                ? file
                : ProjectMapView.bearingsResolve(root, target.file() + "/" + target.declaration());
        if (key == null) {
            key = file;
        }
        Path wanted = key;
        boolean wasOpen = view.cards.codeCard(wanted) != null;
        view.viewsShowCard(wanted, card -> landed(ticket, index, target, file, wanted, card, wasOpen));
    }

    private void landed(
            int ticket,
            int index,
            MapTrails.Stop target,
            Path file,
            Path key,
            @Nullable ProjectMapPreview card,
            boolean wasOpen) {
        if (ticket != generation) {
            return; // the trail moved on (or closed) while this stop's card was waiting
        }
        if (card == null) {
            if (!key.equals(file)) {
                // The declaration is gone from the outline: its lines in the file instead.
                view.onStatus.accept(tr("project.map.status.trail.declarationGone", index + 1));
                boolean fileOpen = view.cards.codeCard(file) != null;
                view.viewsShowCard(file, opened -> landed(ticket, index, target, file, file, opened, fileOpen));
            }
            return;
        }
        stopCard = card;
        stopCardOpened = !wasOpen;
        if (key.equals(file) && target.startLine() > 0) {
            card.viewsRevealLines(target.startLine() - 1, target.endLine() - 1);
        }
        MapTrails.Listed shown = playing;
        int count = shown == null || shown.trail() == null
                ? 0
                : shown.trail().stops().size();
        if (!target.note().isBlank()) {
            ProjectMapTrailNote sheet = new ProjectMapTrailNote(
                    card,
                    view.viewsCanvasHost(),
                    tr("project.map.trails.note.header", index + 1, count),
                    target.note(),
                    target.startLine() - 1,
                    view.cards::viewsConnectorsMoved);
            sheet.setAvoid(() -> bar.getParent() == null ? null : bar.getBoundsInParent());
            view.cards.viewsAttach(card, sheet);
        }
    }

    /** The shown stop's note comes off its card; a card the trail opened closes. */
    private void leaveStop() {
        ProjectMapPreview card = stopCard;
        stopCard = null;
        if (card == null) {
            return;
        }
        view.cards.viewsDetach(card);
        if (stopCardOpened && view.cards.openCards().stream().anyMatch(open -> open.node() == card)) {
            view.cards.close(card);
        }
        stopCardOpened = false;
    }

    private void updateBar() {
        MapTrails.Listed shown = playing;
        if (shown == null || shown.trail() == null || stop < 0) {
            return;
        }
        List<MapTrails.Stop> stops = shown.trail().stops();
        int count = stops.size();
        MapTrails.Stop at = stops.get(Math.min(stop, count - 1));
        position.setText(tr("project.map.trails.bar.position", stop + 1, count));
        String name = stopTitle(at);
        title.setText(name);
        title.setTooltip(new javafx.scene.control.Tooltip(shown.label() + " — " + name));
        previous.setDisable(stop <= 0);
        next.setDisable(stop >= count - 1);
        editButton.setDisable(shown.readOnly());
        bar.setAccessibleText(tr("project.map.trails.bar.accessible", shown.label(), stop + 1, count, name));
    }

    /** What the bar and the editor call a stop: its title, else its file's name. Pure. */
    static String stopTitle(MapTrails.Stop stop) {
        if (!stop.title().isBlank()) {
            return stop.title();
        }
        String file = stop.file();
        int slash = file.lastIndexOf('/');
        return slash >= 0 ? file.substring(slash + 1) : file;
    }

    /** The Map shows another folder: the trail playing (and the one stops go to) were the last folder's. */
    void rootChanged() {
        close();
        current = null;
    }

    /** Close: the bar goes, the shown stop's note comes off (its card stays, as the user last saw it). */
    void close() {
        generation++;
        ProjectMapPreview card = stopCard;
        if (card != null) {
            view.cards.viewsDetach(card);
        }
        stopCard = null;
        stopCardOpened = false;
        playing = null;
        stop = -1;
        view.viewsCanvasHost().getChildren().remove(bar);
        ProjectMapTrailEditor open = editor;
        if (open != null) {
            open.dispose();
            editor = null;
        }
    }

    // --- editing ------------------------------------------------------------------------------------------------

    /** Edit: opens (or brings up) the card listing trail {@code name}'s stops. */
    void edit(String name) {
        Path root = root();
        if (root == null) {
            return;
        }
        MapTrails.Listed listed = MapTrails.read(MapTrails.fileFor(root, name));
        if (listed.trail() == null || listed.readOnly()) {
            view.onStatus.accept(tr(
                    "project.map.status.trail.notWritable",
                    listed.label(),
                    listed.trail() == null
                            ? listed.problem()
                            : "version " + listed.trail().version()));
            return;
        }
        current = listed.name();
        ProjectMapTrailEditor open = editor;
        if (open == null) {
            open = new ProjectMapTrailEditor(this, view.viewsCanvasHost());
            editor = open;
        }
        open.show(listed);
    }

    /** The editor card closed itself. */
    void editorClosed(ProjectMapTrailEditor closed) {
        if (editor == closed) {
            editor = null;
        }
    }

    /** Asks for a value in the Map window's prompt (the editor's Rename… and Note…). */
    void ask(String heading, String label, String initial, java.util.function.Consumer<String> then) {
        OverlayInput.Prompt prompt = view.viewsPrompt;
        if (prompt != null) {
            prompt.show(heading, label, initial, then);
        }
    }
}
