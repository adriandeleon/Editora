package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.Predicate;

import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;

import com.editora.editor.UiZoom;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * Every place of one answer — References, Callers, Implementations, Subtypes as Cards — as a list stuck beside the
 * card it was asked from (Map code browser gap 7). The cards show the first places; the list shows all of them,
 * grouped by file, each with its line's text, as IntelliJ's Find Usages and VS Code's References view do. A filter
 * keeps the places whose file or text holds what is typed, and Tests leaves out the places in test files and folders.
 * The places that have a card are marked. Enter or a double-click opens a place's card beside the card asked from,
 * with its edge, at that very place, the keyboard following (Back returns). The "+N more" chip opens it.
 */
final class MapResultsList extends MapSideColumn {

    private static final PseudoClass MESSAGE = PseudoClass.getPseudoClass("message");
    private static final PseudoClass OPENED = PseudoClass.getPseudoClass("opened");
    private static final PseudoClass FILE = PseudoClass.getPseudoClass("file");

    /** How many files' lines are read for the places' text. */
    static final int MAX_FILES_READ = 300;

    /** The longest line text a row shows. */
    static final int MAX_TEXT = 160;

    /** A place: a group of the answer (whose card it opens), the file, the 0-based line, and that line's text. */
    record Place(MapCardLinks.Group group, Path file, int line, String text) {}

    /** A row: a file, with how many places of the list it holds, or a place, or a line that says something. */
    record Row(@Nullable Path file, @Nullable Place place, String text, boolean message) {
        static Row message(String text) {
            return new Row(null, null, text, true);
        }
    }

    /** Opens a place's card; whether it opened (false: no room, every other card pinned). */
    interface Opener {
        boolean open(Place place);
    }

    private static final Executor READER = new java.util.concurrent.ThreadPoolExecutor(
            0,
            1,
            30,
            java.util.concurrent.TimeUnit.SECONDS,
            new java.util.concurrent.LinkedBlockingQueue<>(),
            runnable -> {
                Thread thread = new Thread(runnable, "project-map-results");
                thread.setDaemon(true);
                return thread;
            });

    private final @Nullable Path root;
    private final String name;
    private final Opener opener;
    private final Predicate<MapCardLinks.Group> opened;
    private final TreeView<Row> tree = new TreeView<>(new TreeItem<>(Row.message("")));
    private final TextField filter = new TextField();
    private final CheckBox tests = new CheckBox(tr("project.map.places.tests"));
    private final Label count = new Label();
    private List<Place> places = List.of();

    /**
     * @param root the Map's root (files are named from it)
     * @param name what was asked about, for the title
     * @param opened whether a group has a card of this answer now
     */
    MapResultsList(
            ProjectMapPreview card,
            Pane host,
            @Nullable Path root,
            String name,
            Opener opener,
            Predicate<MapCardLinks.Group> opened,
            Runnable onClose,
            Runnable onActivate,
            Runnable onMoved) {
        super(card, host, "project-map-results", tr("project.map.places.close"), onClose, onActivate, onMoved);
        this.root = root;
        this.name = name;
        this.opener = opener;
        this.opened = opened;
        filter.setPromptText(tr("project.map.places.filter"));
        filter.setAccessibleText(tr("project.map.places.filter"));
        filter.getStyleClass().add("project-map-results-filter");
        HBox.setHgrow(filter, Priority.ALWAYS);
        tests.setSelected(true);
        tests.setFocusTraversable(false);
        tests.setTooltip(com.editora.editor.ControlNames.tooltip(tr("project.map.places.testsHelp")));
        count.getStyleClass().add("project-map-card-structure-line");
        count.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        HBox filters = UiZoom.spacing(new HBox(filter, tests, count), 6);
        filters.setAlignment(Pos.CENTER_LEFT);
        UiZoom.padding(filters, 4, 6, 2, 8);
        tree.setShowRoot(false);
        tree.getStyleClass().addAll("project-map-card-structure-tree", "project-map-results-tree");
        tree.setCellFactory(view -> new RowCell());
        tree.setAccessibleHelp(tr("project.map.places.help"));
        setBody(filters, tree);
        filter.textProperty().addListener((obs, was, now) -> refill());
        tests.selectedProperty().addListener((obs, was, now) -> refill());
        // Down from the filter goes to the list, as a finder's does.
        filter.addEventHandler(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.DOWN || event.getCode() == KeyCode.ENTER) {
                selectFirstPlace();
                tree.requestFocus();
                event.consume();
            }
        });
        tree.addEventHandler(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ENTER) {
                openSelected();
                event.consume();
            }
        });
        setTitle(tr("project.map.places.title", name), tree);
        tree.getRoot().getChildren().setAll(new TreeItem<>(Row.message(tr("project.map.places.reading"))));
    }

    /**
     * Lists {@code hits} — the answer's places — under {@code groups}' declarations: each place belongs to the group
     * whose declaration holds its line (or its file's group), in the groups' order; a place in no group is not one of
     * the answer's. The lines' text is read off the FX thread, at most {@link #MAX_FILES_READ} files.
     */
    void show(List<MapCardLinks.Hit> hits, List<MapCardLinks.Group> groups, Executor ui) {
        List<Place> found = placesOf(hits, groups, file -> List.of());
        places = found;
        refill();
        List<Path> files =
                found.stream().map(Place::file).distinct().limit(MAX_FILES_READ).toList();
        READER.execute(() -> {
            Map<Path, List<String>> read = new HashMap<>();
            for (Path file : files) {
                read.put(file, lines(file));
            }
            List<Place> withText = placesOf(hits, groups, file -> read.getOrDefault(file, List.of()));
            ui.execute(() -> {
                if (!disposed()) {
                    places = withText;
                    refill();
                }
            });
        });
    }

    /**
     * The answer's places in the order of {@code groups} (ranked: code before tests, nearest first), each group's in
     * line order, with the text {@code lines} gives for its line. Pure.
     */
    static List<Place> placesOf(
            List<MapCardLinks.Hit> hits, List<MapCardLinks.Group> groups, Function<Path, List<String>> lines) {
        Map<MapCardLinks.Group, List<Place>> byGroup = new LinkedHashMap<>();
        groups.forEach(group -> byGroup.put(group, new ArrayList<>()));
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (MapCardLinks.Hit hit : hits) {
            MapCardLinks.Group group = groupOf(groups, hit.file(), hit.line());
            if (group == null || !seen.add(hit.file() + ":" + hit.line())) {
                continue; // not one of the answer's places, or a second place on a line already listed
            }
            List<String> text = lines.apply(hit.file());
            String line = hit.line() >= 0 && hit.line() < text.size()
                    ? text.get(hit.line()).strip()
                    : "";
            if (line.length() > MAX_TEXT) {
                line = line.substring(0, MAX_TEXT) + "…";
            }
            byGroup.computeIfAbsent(group, ignored -> new ArrayList<>())
                    .add(new Place(group, hit.file(), hit.line(), line));
        }
        List<Place> all = new ArrayList<>();
        for (List<Place> of : byGroup.values()) {
            of.sort(java.util.Comparator.comparingInt(Place::line));
            all.addAll(of);
        }
        return all;
    }

    /** The group of {@code file}'s 0-based {@code line}: the one whose declaration holds it, else the file's. Pure. */
    static MapCardLinks.@Nullable Group groupOf(List<MapCardLinks.Group> groups, Path file, int line) {
        MapCardLinks.Group whole = null;
        MapCardLinks.Group best = null;
        int bestSpan = Integer.MAX_VALUE;
        for (MapCardLinks.Group group : groups) {
            if (!group.file().equals(file)) {
                continue;
            }
            ProjectMapStructure.Node node = group.declaration();
            if (node == null) {
                whole = group;
            } else {
                ProjectMapModel.Symbol symbol = node.symbol();
                boolean holds = symbol.startLine() <= line && line <= symbol.endLine() || symbol.line() == line;
                int span = symbol.endLine() - symbol.startLine();
                if (holds && span < bestSpan) {
                    best = group;
                    bestSpan = span;
                }
            }
        }
        if (best != null) {
            return best;
        }
        return whole; // a group of no declaration holds its file's places (a server's caller outside the outline)
    }

    /** {@code file}'s lines (at most 1 MB read), or none when it cannot be read. */
    private static List<String> lines(Path file) {
        try {
            if (Files.size(file) > 1_000_000) {
                return List.of();
            }
            return Files.readAllLines(file);
        } catch (java.io.IOException | RuntimeException unreadable) {
            return List.of();
        }
    }

    /** Whether {@code place} is kept by the filter {@code query} (its file's path or its text) and the Tests box. */
    static boolean keeps(Place place, String relative, String query, boolean tests) {
        if (!tests && MapCardLinks.linksLooksLikeTest(relative)) {
            return false;
        }
        String wanted = query.strip().toLowerCase(Locale.ROOT);
        return wanted.isEmpty()
                || relative.toLowerCase(Locale.ROOT).contains(wanted)
                || place.text().toLowerCase(Locale.ROOT).contains(wanted);
    }

    /** Lists the places the filter keeps, by file, keeping the selection where it still is. */
    void refill() {
        Place selected = selectedPlace();
        String query = filter.getText();
        Map<Path, List<Place>> byFile = new LinkedHashMap<>();
        for (Place place : places) {
            if (keeps(place, relative(place.file()), query, tests.isSelected())) {
                byFile.computeIfAbsent(place.file(), file -> new ArrayList<>()).add(place);
            }
        }
        List<TreeItem<Row>> items = new ArrayList<>();
        int shown = 0;
        TreeItem<Row> reselect = null;
        for (Map.Entry<Path, List<Place>> entry : byFile.entrySet()) {
            Path file = entry.getKey();
            TreeItem<Row> fileItem = new TreeItem<>(new Row(
                    file,
                    null,
                    tr(
                            "project.map.places.file",
                            relative(file),
                            String.valueOf(entry.getValue().size())),
                    false));
            fileItem.setExpanded(true);
            for (Place place : entry.getValue()) {
                TreeItem<Row> item = new TreeItem<>(new Row(file, place, placeText(place), false));
                fileItem.getChildren().add(item);
                if (place.equals(selected)) {
                    reselect = item;
                }
                shown++;
            }
            items.add(fileItem);
        }
        if (items.isEmpty()) {
            items.add(new TreeItem<>(
                    Row.message(places.isEmpty() ? tr("project.map.places.reading") : tr("project.map.places.none"))));
        }
        tree.getRoot().getChildren().setAll(items);
        count.setText(
                shown == places.size()
                        ? tr("project.map.places.count", String.valueOf(shown), String.valueOf(byFile.size()))
                        : tr(
                                "project.map.places.countFiltered",
                                String.valueOf(shown),
                                String.valueOf(places.size()),
                                String.valueOf(byFile.size())));
        if (reselect != null) {
            tree.getSelectionModel().select(reselect);
        }
        moved();
    }

    /** A place's row: its line number and text. */
    static String placeText(Place place) {
        return tr("project.map.places.place", place.line() + 1, place.text());
    }

    private String relative(Path file) {
        return relative(root, file);
    }

    /** How a row names {@code file}: its project path, a library class's archive and name, else the whole path. Pure. */
    static String relative(@Nullable Path root, Path file) {
        if (root != null && file.startsWith(root)) {
            return com.editora.search.ProjectWalk.relativize(root, file);
        }
        MapLibrarySources.Library library = MapLibrarySources.of(file);
        return library == null ? file.toString() : library.where() + "/" + file.getFileName();
    }

    private @Nullable Place selectedPlace() {
        TreeItem<Row> item = tree.getSelectionModel().getSelectedItem();
        return item == null ? null : item.getValue().place();
    }

    private void selectFirstPlace() {
        if (selectedPlace() != null) {
            return;
        }
        for (TreeItem<Row> file : tree.getRoot().getChildren()) {
            if (!file.getChildren().isEmpty()) {
                tree.getSelectionModel().select(file.getChildren().getFirst());
                return;
            }
        }
    }

    /** Opens the selected place's card; false when no place is selected. */
    boolean openSelected() {
        Place place = selectedPlace();
        if (place == null) {
            return false;
        }
        opener.open(place);
        tree.refresh(); // its group may have a card now
        return true;
    }

    /** Opens {@code place} as picking it does. For tests. */
    boolean openPlaceForTest(Place place) {
        return opener.open(place);
    }

    /** The places of the answer it lists, filtered or not. For tests. */
    List<Place> places() {
        return places;
    }

    /** The rows shown, files and places, top to bottom. For tests. */
    List<String> rowsForTest() {
        List<String> rows = new ArrayList<>();
        for (TreeItem<Row> file : tree.getRoot().getChildren()) {
            rows.add(file.getValue().text());
            file.getChildren().forEach(place -> rows.add("  " + place.getValue().text()));
        }
        return rows;
    }

    TreeView<Row> tree() {
        return tree;
    }

    TextField filter() {
        return filter;
    }

    CheckBox tests() {
        return tests;
    }

    /** The groups of its places, for its owner's bookkeeping. */
    Set<MapCardLinks.Group> groups() {
        Set<MapCardLinks.Group> all = new java.util.LinkedHashSet<>();
        places.forEach(place -> all.add(place.group()));
        return all;
    }

    @Override
    protected Node focusTarget() {
        return tree;
    }

    private final class RowCell extends TreeCell<Row> {
        RowCell() {
            addEventHandler(MouseEvent.MOUSE_CLICKED, event -> {
                if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2 && !isEmpty()) {
                    openSelected();
                    event.consume();
                }
            });
        }

        @Override
        protected void updateItem(@Nullable Row row, boolean empty) {
            super.updateItem(row, empty);
            Row shown = empty ? null : row;
            Place place = shown == null ? null : shown.place();
            setText(shown == null ? null : shown.text());
            pseudoClassStateChanged(MESSAGE, shown != null && shown.message());
            pseudoClassStateChanged(FILE, shown != null && shown.file() != null && place == null);
            pseudoClassStateChanged(OPENED, place != null && opened.test(place.group()));
        }
    }
}
