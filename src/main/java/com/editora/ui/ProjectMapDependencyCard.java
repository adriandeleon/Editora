package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.CubicCurve;

import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * The Project Map's Dependencies card (Map code browser gap 14): what a file or folder uses of the project and what
 * uses it, read from the files' import lines ({@link ProjectMapImports}). <b>Around</b> draws the subject in the
 * middle, what uses it on the left and what it uses on the right, file by file or folder by folder, the most uses
 * first, those on both sides marked; <b>Inside</b> (a folder) is a matrix of how its parts use each other, in an order
 * where each uses only those after it, so a mark below the diagonal is a cycle, and the parts in a cycle are marked.
 * A press on a file or folder, or on a cell, lists the import lines behind it, each a press from its card; Enter or a
 * double-click moves the card onto it, and Back (Backspace) returns. One is open at a time.
 */
final class ProjectMapDependencyCard extends MapFloatingCard {

    static final double DEFAULT_WIDTH = 640;
    static final double DEFAULT_HEIGHT = 540;
    static final double MIN_WIDTH = 380;
    static final double MIN_HEIGHT = 300;
    /** The most files or folders drawn on a side; the card says how many more there are. */
    static final int MAX_NODES = 60;

    /** What the card shows of its subject. */
    enum View {
        AROUND,
        INSIDE
    }

    /** Where the card was, for Back. */
    record Place(String rel, boolean folder, View view) {}

    private final Button back = new Button("‹");
    private final ToggleGroup views = new ToggleGroup();
    private final ToggleButton aroundButton = new StayOnButton(tr("project.map.deps.around"));
    private final ToggleButton insideButton = new StayOnButton(tr("project.map.deps.inside"));
    private final ToggleGroup groupings = new ToggleGroup();
    private final ToggleButton filesButton = new StayOnButton(tr("project.map.deps.files"));
    private final ToggleButton foldersButton = new StayOnButton(tr("project.map.deps.folders"));
    private final Button refresh = new Button();
    private final Label summary = new Label();
    private final GraphPane graph = new GraphPane();
    private final ScrollPane graphScroll = new ScrollPane(graph);
    private final GridPane matrix = new GridPane();
    private final ScrollPane matrixScroll = new ScrollPane(matrix);
    private final Label empty = new Label();
    private final Label whyTitle = new Label();
    private final ListView<ProjectMapImports.Edge> why = new ListView<>();

    private Consumer<Path> onReveal = path -> {};
    private BiConsumer<Path, Integer> onOpen = (path, line) -> {};
    private Runnable onRefresh = () -> {};

    /** The project's folder; meaningful once {@link #shown}. */
    private Path root = Path.of("");

    private @Nullable ProjectMapDependencies dependencies;
    private boolean reading;
    private String subject = "";
    private boolean folder = true;
    private boolean shown;
    private View view = View.AROUND;
    private ProjectMapDependencies.Grouping fileGrouping = ProjectMapDependencies.Grouping.FILES;
    private ProjectMapDependencies.Grouping folderGrouping = ProjectMapDependencies.Grouping.FOLDERS;
    private final Deque<Place> backStack = new ArrayDeque<>();
    private ProjectMapDependencies.@Nullable Around around;
    private ProjectMapDependencies.@Nullable Matrix shownMatrix;
    private @Nullable Region picked;
    /** Inside's row names and cells with uses (null where none), as drawn. */
    private final List<Button> rowButtons = new ArrayList<>();

    private @Nullable Button[][] cellButtons = new Button[0][0];
    /** Set while the toggles are set to match the card, so their listeners leave it alone. */
    private boolean syncing;

    ProjectMapDependencyCard() {
        super("project-map-deps-card", Icons.dependencies(), MIN_WIDTH, MIN_HEIGHT);
        back.getStyleClass().addAll("flat", "project-map-deps-back");
        back.setMnemonicParsing(false);
        back.setTooltip(new Tooltip(tr("project.map.deps.backTip")));
        back.setAccessibleText(tr("project.map.deps.backTip"));
        back.setOnAction(event -> goBack());
        aroundButton.setToggleGroup(views);
        insideButton.setToggleGroup(views);
        aroundButton.setTooltip(new Tooltip(tr("project.map.deps.aroundTip")));
        insideButton.setTooltip(new Tooltip(tr("project.map.deps.insideTip")));
        views.selectedToggleProperty().addListener((obs, was, now) -> {
            if (now != null && !syncing) {
                view = now == insideButton ? View.INSIDE : View.AROUND;
                render();
            }
        });
        filesButton.setToggleGroup(groupings);
        foldersButton.setToggleGroup(groupings);
        filesButton.setTooltip(new Tooltip(tr("project.map.deps.filesTip")));
        foldersButton.setTooltip(new Tooltip(tr("project.map.deps.foldersTip")));
        groupings.selectedToggleProperty().addListener((obs, was, now) -> {
            if (now != null && !syncing) {
                ProjectMapDependencies.Grouping chosen = now == foldersButton
                        ? ProjectMapDependencies.Grouping.FOLDERS
                        : ProjectMapDependencies.Grouping.FILES;
                if (folder) {
                    folderGrouping = chosen;
                } else {
                    fileGrouping = chosen;
                }
                render();
            }
        });
        refresh.getStyleClass().add("flat");
        refresh.setGraphic(Icons.refresh());
        refresh.setTooltip(new Tooltip(tr("project.map.deps.refreshTip")));
        refresh.setAccessibleText(tr("project.map.deps.refreshTip"));
        refresh.setOnAction(event -> onRefresh.run());
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        Region divider = new Region();
        divider.setMinWidth(8);
        HBox bar = new HBox(4, back, aroundButton, insideButton, divider, filesButton, foldersButton, gap, refresh);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("project-map-deps-bar");
        bar.setPadding(new Insets(4, 6, 4, 6));

        summary.getStyleClass().add("project-map-deps-summary");
        summary.setWrapText(true);
        summary.setMnemonicParsing(false);
        summary.setMinHeight(Region.USE_PREF_SIZE);
        summary.setPadding(new Insets(6, 10, 6, 10));

        graphScroll.setFitToWidth(true);
        graphScroll.getStyleClass().add("project-map-deps-scroll");
        matrix.getStyleClass().add("project-map-deps-matrix");
        matrix.setHgap(2);
        matrix.setVgap(2);
        matrix.setPadding(new Insets(8));
        matrixScroll.getStyleClass().add("project-map-deps-scroll");
        empty.getStyleClass().add("project-map-deps-empty");
        empty.setWrapText(true);
        empty.setPadding(new Insets(16));
        StackPane.setAlignment(empty, Pos.TOP_LEFT);
        StackPane content = new StackPane(graphScroll, matrixScroll, empty);

        whyTitle.getStyleClass().add("project-map-deps-why-title");
        whyTitle.setMnemonicParsing(false);
        whyTitle.setPadding(new Insets(4, 8, 4, 8));
        whyTitle.setMaxWidth(Double.MAX_VALUE);
        why.getStyleClass().add("project-map-deps-why");
        why.setAccessibleText(tr("project.map.deps.whyAccessible"));
        why.setCellFactory(list -> new WhyCell());
        why.setPlaceholder(placeholder(tr("project.map.deps.whyEmpty")));
        why.addEventHandler(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ENTER
                    && openEdge(why.getSelectionModel().getSelectedItem())) {
                event.consume();
            }
        });
        VBox whyBox = new VBox(whyTitle, why);
        VBox.setVgrow(why, Priority.ALWAYS);
        SplitPane split = new SplitPane(content, whyBox);
        split.setOrientation(Orientation.VERTICAL);
        split.setDividerPositions(0.68);
        split.getStyleClass().add("project-map-deps-split");
        VBox body = new VBox(bar, summary, split);
        VBox.setVgrow(split, Priority.ALWAYS);
        frame.setCenter(body);
        addEventHandler(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.BACK_SPACE && !event.isShortcutDown() && goBack()) {
                event.consume();
            }
        });
        render();
    }

    // --- what it shows ----------------------------------------------------------------------------------------

    /**
     * Moves the card onto {@code rel} ({@code ""}: the project at {@code root}), a folder or a file; the place it was
     * on goes on its Back list. The project itself opens Inside; a file Around; another folder as the last one did.
     */
    void show(Path root, String rel, boolean isFolder) {
        if (!shown || !root.equals(this.root)) {
            backStack.clear();
            shown = false;
        }
        View next = !isFolder ? View.AROUND : rel.isEmpty() ? View.INSIDE : view;
        moveTo(root, new Place(rel, isFolder, next), true);
    }

    private void moveTo(Path at, Place place, boolean remember) {
        Place here = new Place(subject, folder, view);
        if (remember && shown && !here.equals(place)) {
            backStack.push(here);
        }
        root = at;
        subject = place.rel();
        folder = place.folder();
        view = place.folder() ? place.view() : View.AROUND;
        shown = true;
        render();
    }

    /** The answer of a reading: the card shows the subject with it. */
    void setDependencies(ProjectMapDependencies found) {
        dependencies = found;
        reading = false;
        render();
    }

    /** A reading is under way: until it ends, the card says so (or, with an earlier answer, shows that one). */
    void setReading() {
        reading = true;
        if (dependencies == null) {
            render();
        }
    }

    /** Goes back to where the card was before; whether there was a place to go back to. */
    boolean goBack() {
        Place previous = backStack.poll();
        if (previous == null) {
            return false;
        }
        moveTo(root, previous, false);
        return true;
    }

    private void render() {
        syncing = true;
        try {
            (view == View.INSIDE ? insideButton : aroundButton).setSelected(true);
            (grouping() == ProjectMapDependencies.Grouping.FOLDERS ? foldersButton : filesButton).setSelected(true);
        } finally {
            syncing = false;
        }
        insideButton.setDisable(!folder);
        filesButton.setDisable(view == View.INSIDE);
        foldersButton.setDisable(view == View.INSIDE);
        back.setDisable(backStack.isEmpty());
        String name = displayName(subject, folder);
        String heading = tr("project.map.deps.title", name);
        title.setText(heading);
        title.setTooltip(new Tooltip(subject.isEmpty() ? name : subject));
        setAccessibleText(heading);
        picked = null;
        why.getItems().clear();
        whyTitle.setText(tr("project.map.deps.whyTitle"));
        around = null;
        shownMatrix = null;
        ProjectMapDependencies known = dependencies;
        if (known == null) {
            summary.setText(reading || shown ? tr("project.map.deps.reading") : "");
            showOnly(empty);
            empty.setText("");
            return;
        }
        if (view == View.INSIDE) {
            renderInside(known);
        } else {
            renderAround(known);
        }
    }

    private ProjectMapDependencies.Grouping grouping() {
        return folder ? folderGrouping : fileGrouping;
    }

    private void showOnly(Node visible) {
        graphScroll.setVisible(visible == graphScroll);
        matrixScroll.setVisible(visible == matrixScroll);
        empty.setVisible(visible == empty);
    }

    private void renderAround(ProjectMapDependencies known) {
        if (!folder && !known.read(subject)) {
            summary.setText(tr("project.map.deps.notRead"));
            empty.setText("");
            graph.clear();
            showOnly(empty);
            return;
        }
        ProjectMapDependencies.Around found = known.around(subject, folder, grouping());
        around = found;
        summary.setText(aroundSummary(found, known));
        if (found.users().isEmpty() && found.uses().isEmpty()) {
            empty.setText(tr(folder ? "project.map.deps.aloneFolder" : "project.map.deps.alone"));
            graph.clear();
            showOnly(empty);
            return;
        }
        graph.fill(found);
        showOnly(graphScroll);
        graphScroll.setVvalue(0);
    }

    /** What the line above Around says of {@code found}. Pure but for the language. */
    static String aroundSummary(ProjectMapDependencies.Around found, ProjectMapDependencies known) {
        StringBuilder text = new StringBuilder();
        if (found.folder()) {
            text.append(tr("project.map.deps.inFolder", found.insideFiles())).append(' ');
        }
        text.append(tr(
                found.folder() ? "project.map.deps.countsFolder" : "project.map.deps.counts",
                found.userFiles(),
                found.usedFiles()));
        long mutual = found.uses().stream()
                .filter(ProjectMapDependencies.Node::mutual)
                .count();
        if (mutual > 0) {
            text.append(' ')
                    .append(tr(
                            found.grouping() == ProjectMapDependencies.Grouping.FOLDERS
                                    ? "project.map.deps.mutualFolders"
                                    : "project.map.deps.mutualFiles",
                            mutual));
        }
        if (known.truncated()) {
            text.append(' ').append(tr("project.map.deps.truncated", known.fileCount()));
        }
        return text.toString();
    }

    private void renderInside(ProjectMapDependencies known) {
        ProjectMapDependencies.Matrix found = known.matrix(subject);
        shownMatrix = found;
        summary.setText(insideSummary(found, known, displayName(found.folder(), true)));
        matrix.getChildren().clear();
        rowButtons.clear();
        cellButtons = new Button[found.parts().size()][found.parts().size()];
        if (found.parts().size() < 2) {
            empty.setText(tr("project.map.deps.noParts"));
            showOnly(empty);
            return;
        }
        List<ProjectMapDependencies.Part> parts = found.parts();
        int n = parts.size();
        for (int c = 0; c < n; c++) {
            Label number = new Label(Integer.toString(c + 1));
            number.getStyleClass().add("project-map-deps-column");
            number.setTooltip(new Tooltip(partName(found.folder(), parts.get(c))));
            number.setMinWidth(CELL);
            number.setAlignment(Pos.CENTER);
            matrix.add(number, c + 1, 0);
        }
        for (int r = 0; r < n; r++) {
            ProjectMapDependencies.Part part = parts.get(r);
            Button header = new Button((r + 1) + "  " + partName(found.folder(), part));
            header.getStyleClass().addAll("flat", "project-map-deps-row");
            header.setMnemonicParsing(false);
            header.setMaxWidth(Double.MAX_VALUE);
            header.setAlignment(Pos.CENTER_LEFT);
            header.setTooltip(new Tooltip(tr("project.map.deps.partTip", part.rel(), part.files())));
            View look = part.folder() ? View.INSIDE : View.AROUND;
            header.setOnAction(event -> moveTo(root, new Place(part.rel(), part.folder(), look), true));
            header.setContextMenu(placeMenu(part.rel(), part.folder()));
            matrix.add(header, 0, r + 1);
            rowButtons.add(header);
            for (int c = 0; c < n; c++) {
                Region cell = cell(found, r, c);
                if (cell instanceof Button button) {
                    cellButtons[r][c] = button;
                }
                matrix.add(cell, c + 1, r + 1);
            }
        }
        showOnly(matrixScroll);
        matrixScroll.setVvalue(0);
        matrixScroll.setHvalue(0);
    }

    private static final double CELL = 26;

    private Region cell(ProjectMapDependencies.Matrix found, int r, int c) {
        int count = found.cells()[r][c];
        if (count == 0) {
            Region blank = new Region();
            blank.getStyleClass().add(r == c ? "project-map-deps-self" : "project-map-deps-blank");
            blank.setMinSize(CELL, CELL);
            blank.setPrefSize(CELL, CELL);
            return blank;
        }
        List<ProjectMapDependencies.Part> parts = found.parts();
        String from = partName(found.folder(), parts.get(r));
        String to = partName(found.folder(), parts.get(c));
        Button cell = new Button(Integer.toString(count));
        cell.getStyleClass().addAll("project-map-deps-cell", "project-map-deps-weight-" + weight(count));
        if (found.cycle()[r][c]) {
            cell.getStyleClass().add("project-map-deps-cycle");
        }
        cell.setMnemonicParsing(false);
        cell.setMinSize(CELL, CELL);
        cell.setPrefSize(CELL, CELL);
        cell.setMaxSize(CELL, CELL);
        String tip =
                tr(found.cycle()[r][c] ? "project.map.deps.cellCycleTip" : "project.map.deps.cellTip", from, to, count);
        cell.setTooltip(new Tooltip(tip));
        cell.setAccessibleText(tip);
        ProjectMapDependencies known = Objects.requireNonNull(dependencies); // drawn from it
        cell.setOnAction(event -> pick(
                cell,
                tr("project.map.deps.whyCell", from, to),
                known.why(found.folder(), parts.get(r).rel(), parts.get(c).rel())));
        return cell;
    }

    /** How dark a matrix cell is: 1 for one use, up to 4 for many. Pure. */
    static int weight(int count) {
        return count >= 20 ? 4 : count >= 6 ? 3 : count >= 2 ? 2 : 1;
    }

    /** What the line above Inside says of {@code found}, whose folder is called {@code name}. Pure but for the language. */
    static String insideSummary(ProjectMapDependencies.Matrix found, ProjectMapDependencies known, String name) {
        StringBuilder text = new StringBuilder(
                tr("project.map.deps.insideCounts", name, found.parts().size(), found.uses()));
        text.append(' ')
                .append(tr(
                        found.cycles() == 0 ? "project.map.deps.noCycles" : "project.map.deps.cycles", found.cycles()));
        if (found.left() > 0) {
            text.append(' ').append(tr("project.map.deps.partsLeft", found.left()));
        }
        if (known.truncated()) {
            text.append(' ').append(tr("project.map.deps.truncated", known.fileCount()));
        }
        return text.toString();
    }

    /** A part's name in its matrix: its path below the folder, {@code /} after a folder's. */
    private static String partName(String folder, ProjectMapDependencies.Part part) {
        String name = folder.isEmpty() ? part.rel() : part.rel().substring(folder.length() + 1);
        return part.folder() ? name + "/" : name;
    }

    /** A file's or folder's name as the card writes it; the project's own name for the root. */
    private String displayName(String rel, boolean isFolder) {
        if (rel.isEmpty()) {
            Path name = root.getFileName();
            return name == null || name.toString().isEmpty() ? tr("project.map.deps.project") : name.toString();
        }
        return isFolder ? rel + "/" : ProjectMapImports.fileName(rel);
    }

    // --- picking and moving -------------------------------------------------------------------------------------

    private void pick(Region source, String heading, List<ProjectMapImports.Edge> edges) {
        if (picked != null) {
            picked.getStyleClass().remove("project-map-deps-picked");
        }
        picked = source;
        source.getStyleClass().add("project-map-deps-picked");
        whyTitle.setText(heading);
        why.getItems().setAll(edges); // never empty: what is drawn stands for a use at least
        why.getSelectionModel().selectFirst();
        why.scrollTo(0);
    }

    private boolean openEdge(ProjectMapImports.@Nullable Edge edge) {
        if (edge == null) {
            return false;
        }
        onOpen.accept(root.resolve(edge.from()), edge.line());
        return true;
    }

    private void reveal(String rel) {
        onReveal.accept(root.resolve(rel)); // "" resolves to the root itself
    }

    /** The menu of a file or folder on the card: move onto it, show it on the map, open it. */
    private ContextMenu placeMenu(String rel, boolean isFolder) {
        List<MenuItem> items = new ArrayList<>();
        MenuItem focus = new MenuItem(tr("project.map.deps.focusHere"));
        focus.setOnAction(event -> moveTo(root, new Place(rel, isFolder, View.AROUND), true));
        items.add(focus);
        if (isFolder) {
            MenuItem inside = new MenuItem(tr("project.map.deps.insideHere"));
            inside.setOnAction(event -> moveTo(root, new Place(rel, true, View.INSIDE), true));
            items.add(inside);
        }
        MenuItem show = new MenuItem(tr("project.map.deps.showOnMap"));
        show.setOnAction(event -> reveal(rel));
        items.add(show);
        if (!isFolder) {
            MenuItem open = new MenuItem(tr("project.map.deps.openCard"));
            open.setOnAction(event -> onOpen.accept(root.resolve(rel), -1));
            items.add(open);
        }
        return new ContextMenu(items.toArray(MenuItem[]::new));
    }

    // --- callbacks and state ------------------------------------------------------------------------------------

    /** What Show on Map does: the file or folder is revealed on the map. */
    void setOnReveal(Consumer<Path> action) {
        onReveal = action;
    }

    /** What a press on an import line (or Open) does: the file's card opens at the 0-based line ({@code -1}: whole). */
    void setOnOpen(BiConsumer<Path, Integer> action) {
        onOpen = action;
    }

    /** What the refresh button does: the project is read again. */
    void setOnRefresh(Runnable action) {
        onRefresh = action;
    }

    @Override
    void focusContent() {
        if (view == View.AROUND && graphScroll.isVisible()) {
            graph.subject.requestFocus();
        } else if (view == View.INSIDE && matrixScroll.isVisible()) {
            rowButtons.get(0).requestFocus(); // the matrix is drawn with two rows at least
        } else {
            refresh.requestFocus();
        }
    }

    /** The project's folder, or null before the card was shown. */
    @Nullable
    Path root() {
        return shown ? root : null;
    }

    String subject() {
        return subject;
    }

    boolean subjectIsFolder() {
        return folder;
    }

    View view() {
        return view;
    }

    String summaryText() {
        return summary.getText();
    }

    String whyHeading() {
        return whyTitle.getText();
    }

    /** The import lines listed, as {@code from:line → to}. For tests. */
    List<String> whyLines() {
        return why.getItems().stream()
                .map(edge -> edge.from() + ":" + (edge.line() + 1) + " → " + edge.to())
                .toList();
    }

    /** What Around shows: the paths of the users, then of the used, as drawn. For tests. */
    List<String> usersShown() {
        return graph.paths(true);
    }

    List<String> usesShown() {
        return graph.paths(false);
    }

    /** The matrix shown, or null. For tests. */
    ProjectMapDependencies.@Nullable Matrix matrixShown() {
        return shownMatrix;
    }

    /** Picks the drawn file or folder at {@code rel} on a side, as a press does; whether it was there. For tests. */
    boolean pickNode(String rel, boolean users) {
        NodeButton button = graph.button(rel, users);
        if (button == null) {
            return false;
        }
        button.fire();
        return true;
    }

    /** Moves onto the drawn file or folder at {@code rel}, as Enter on it does; whether it was there. For tests. */
    boolean enterNode(String rel, boolean users) {
        NodeButton button = graph.button(rel, users);
        if (button == null) {
            return false;
        }
        focusHere(button.node);
        return true;
    }

    /** Presses the matrix cell of row {@code r}, column {@code c}; whether it had uses. For tests. */
    boolean pickCell(int r, int c) {
        Button cell = cellButtons[r][c];
        if (cell == null) {
            return false;
        }
        cell.fire();
        return true;
    }

    /** Presses the name of the matrix's row {@code r}. For tests. */
    void pressRow(int r) {
        rowButtons.get(r).fire();
    }

    /** Opens the import line picked in the list, as Enter does; whether there was one. For tests. */
    boolean openPickedLine() {
        return openEdge(why.getSelectionModel().getSelectedItem());
    }

    private void focusHere(ProjectMapDependencies.Node node) {
        moveTo(root, new Place(node.rel(), node.folder(), View.AROUND), true);
        focusContent();
    }

    private static Label placeholder(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("project-map-deps-empty");
        label.setWrapText(true);
        return label;
    }

    // --- Around's drawing ---------------------------------------------------------------------------------------

    /** A file or folder drawn around the subject. */
    private final class NodeButton extends Button {
        final ProjectMapDependencies.Node node;
        final boolean user;

        NodeButton(ProjectMapDependencies.Node node, boolean user) {
            this.node = node;
            this.user = user;
            String name = node.folder() ? displayName(node.rel(), true) : ProjectMapImports.fileName(node.rel());
            setText(name);
            setMnemonicParsing(false);
            getStyleClass()
                    .addAll(
                            "project-map-deps-node",
                            node.folder() ? "project-map-deps-folder" : "project-map-deps-file");
            if (node.mutual()) {
                getStyleClass().add("project-map-deps-mutual");
            }
            setTextOverrun(node.folder() ? OverrunStyle.LEADING_ELLIPSIS : OverrunStyle.CENTER_ELLIPSIS);
            Label count = new Label((node.mutual() ? "⇄ " : "") + node.count());
            count.getStyleClass().add("project-map-deps-count");
            setGraphic(count);
            setContentDisplay(ContentDisplay.RIGHT);
            setGraphicTextGap(6);
            setAlignment(Pos.CENTER_LEFT);
            String path = node.rel().isEmpty() ? displayName("", true) : node.rel();
            String tip = tr(
                    user
                            ? node.mutual() ? "project.map.deps.userMutualTip" : "project.map.deps.userTip"
                            : node.mutual() ? "project.map.deps.usedMutualTip" : "project.map.deps.usedTip",
                    path,
                    node.count());
            setTooltip(new Tooltip(tip));
            setAccessibleText(tip);
            ProjectMapDependencies known = Objects.requireNonNull(dependencies); // drawn from it
            setOnAction(event -> pick(
                    this,
                    tr(user ? "project.map.deps.whyUser" : "project.map.deps.whyUsed", name),
                    known.why(subject, folder, node, user)));
            addEventFilter(KeyEvent.KEY_PRESSED, event -> {
                if (event.getCode() == KeyCode.ENTER) {
                    focusHere(node);
                    event.consume();
                }
            });
            addEventHandler(MouseEvent.MOUSE_CLICKED, event -> {
                if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2) {
                    focusHere(node);
                    event.consume();
                }
            });
            setContextMenu(placeMenu(node.rel(), node.folder()));
        }
    }

    /**
     * Around's picture: the users in a column on the left, the subject in the middle, the used on the right, each
     * joined to the subject by a curve as thick as its uses are many. Laid out by hand, so the curves meet the boxes.
     */
    private final class GraphPane extends Region {
        private static final double PAD = 10;
        private static final double GAP = 40;
        private static final double ROW = 4;

        final Button subject = new Button();
        private final Label usersHeader = new Label();
        private final Label usesHeader = new Label();
        private final Label usersMore = new Label();
        private final Label usesMore = new Label();
        private final Group curves = new Group();
        private final List<NodeButton> users = new ArrayList<>();
        private final List<NodeButton> uses = new ArrayList<>();
        private final List<CubicCurve> userCurves = new ArrayList<>();
        private final List<CubicCurve> useCurves = new ArrayList<>();

        GraphPane() {
            getStyleClass().add("project-map-deps-graph");
            subject.getStyleClass().add("project-map-deps-subject");
            subject.setMnemonicParsing(false);
            subject.setWrapText(true);
            subject.setOnAction(event -> reveal(ProjectMapDependencyCard.this.subject));
            for (Label header : List.of(usersHeader, usesHeader)) {
                header.getStyleClass().add("project-map-deps-side");
            }
            for (Label more : List.of(usersMore, usesMore)) {
                more.getStyleClass().add("project-map-deps-more");
            }
            curves.setManaged(false);
            curves.setMouseTransparent(true);
        }

        void clear() {
            users.clear();
            uses.clear();
            userCurves.clear();
            useCurves.clear();
            curves.getChildren().clear();
            getChildren().clear();
        }

        void fill(ProjectMapDependencies.Around found) {
            clear();
            String name = displayName(found.subject(), found.folder());
            subject.setText(name);
            String tip = tr("project.map.deps.subjectTip", found.subject().isEmpty() ? name : found.subject());
            subject.setTooltip(new Tooltip(tip));
            subject.setAccessibleText(tip);
            subject.setContextMenu(placeMenu(found.subject(), found.folder()));
            usersHeader.setText(tr("project.map.deps.usedBy", found.users().size()));
            usesHeader.setText(tr("project.map.deps.uses", found.uses().size()));
            getChildren().addAll(curves, usersHeader, usesHeader, subject);
            add(found.users(), true, users, userCurves, usersMore);
            add(found.uses(), false, uses, useCurves, usesMore);
            requestLayout();
        }

        private void add(
                List<ProjectMapDependencies.Node> nodes,
                boolean user,
                List<NodeButton> buttons,
                List<CubicCurve> lines,
                Label more) {
            int shown = Math.min(nodes.size(), MAX_NODES);
            for (int i = 0; i < shown; i++) {
                ProjectMapDependencies.Node node = nodes.get(i);
                NodeButton button = new NodeButton(node, user);
                CubicCurve curve = new CubicCurve();
                curve.getStyleClass().add("project-map-deps-edge");
                if (node.mutual()) {
                    curve.getStyleClass().add("project-map-deps-mutual");
                }
                curve.setStrokeWidth(1 + Math.min(3, Math.log(node.count()) / Math.log(2)));
                buttons.add(button);
                lines.add(curve);
                curves.getChildren().add(curve);
                getChildren().add(button);
            }
            if (nodes.size() > shown) {
                more.setText(tr("project.map.deps.more", nodes.size() - shown));
                getChildren().add(more);
            }
        }

        List<String> paths(boolean user) {
            return (user ? users : uses)
                    .stream().map(button -> button.node.rel()).toList();
        }

        @Nullable
        NodeButton button(String rel, boolean user) {
            for (NodeButton button : user ? users : uses) {
                if (button.node.rel().equals(rel)) {
                    return button;
                }
            }
            return null;
        }

        private double columnWidth(double width) {
            return Math.max(90, (width - 2 * PAD - 2 * GAP) / 3);
        }

        private double sideHeight(List<NodeButton> buttons, Label header, Label more, double column) {
            double h = header.prefHeight(column) + ROW;
            for (NodeButton button : buttons) {
                h += button.prefHeight(column) + ROW;
            }
            if (getChildren().contains(more)) {
                h += more.prefHeight(column);
            }
            return h;
        }

        @Override
        protected double computePrefWidth(double height) {
            return 3 * 90 + 2 * GAP + 2 * PAD;
        }

        @Override
        protected double computePrefHeight(double width) {
            double column = columnWidth(width > 0 ? width : computePrefWidth(-1));
            return 2 * PAD
                    + Math.max(
                            subject.prefHeight(column),
                            Math.max(
                                    sideHeight(users, usersHeader, usersMore, column),
                                    sideHeight(uses, usesHeader, usesMore, column)));
        }

        @Override
        protected void layoutChildren() {
            if (!getChildren().contains(subject)) {
                return;
            }
            double width = getWidth();
            double column = columnWidth(width);
            double height = Math.max(getHeight() - 2 * PAD, 0);
            double subjectHeight = subject.prefHeight(column);
            double middleX = PAD + column + GAP;
            double subjectWidth = Math.min(column, Math.max(subject.prefWidth(-1), 80));
            double subjectX = middleX + (column - subjectWidth) / 2;
            double subjectY = PAD + Math.max(0, (height - subjectHeight) / 2);
            subject.resizeRelocate(subjectX, subjectY, subjectWidth, subjectHeight);
            double midY = subjectY + subjectHeight / 2;
            layoutSide(users, usersHeader, usersMore, userCurves, PAD, column, height, subjectX, midY, true);
            layoutSide(
                    uses,
                    usesHeader,
                    usesMore,
                    useCurves,
                    PAD + 2 * (column + GAP),
                    column,
                    height,
                    subjectX + subjectWidth,
                    midY,
                    false);
        }

        private void layoutSide(
                List<NodeButton> buttons,
                Label header,
                Label more,
                List<CubicCurve> lines,
                double x,
                double column,
                double height,
                double subjectEdge,
                double midY,
                boolean left) {
            double total = sideHeight(buttons, header, more, column);
            double y = PAD + Math.max(0, (height - total) / 2);
            double headerHeight = header.prefHeight(column);
            header.resizeRelocate(x, y, column, headerHeight);
            y += headerHeight + ROW;
            for (int i = 0; i < buttons.size(); i++) {
                NodeButton button = buttons.get(i);
                double w = Math.min(column, button.prefWidth(-1));
                double h = button.prefHeight(column);
                double bx = left ? x + column - w : x;
                button.resizeRelocate(bx, y, w, h);
                CubicCurve curve = lines.get(i);
                double startX = left ? bx + w : bx;
                double startY = y + h / 2;
                double bend = Math.abs(subjectEdge - startX) / 2;
                curve.setStartX(startX);
                curve.setStartY(startY);
                curve.setEndX(subjectEdge);
                curve.setEndY(midY);
                curve.setControlX1(left ? startX + bend : startX - bend);
                curve.setControlY1(startY);
                curve.setControlX2(left ? subjectEdge - bend : subjectEdge + bend);
                curve.setControlY2(midY);
                y += h + ROW;
            }
            if (getChildren().contains(more)) {
                more.resizeRelocate(x, y, column, more.prefHeight(column));
            }
        }
    }

    /** An import line behind what was picked: where it is, and the line itself. */
    private final class WhyCell extends ListCell<ProjectMapImports.Edge> {
        private final Label where = new Label();
        private final Label line = new Label();
        private final VBox box = new VBox(1, where, line);

        WhyCell() {
            where.getStyleClass().add("project-map-deps-where");
            line.getStyleClass().add("project-map-deps-line");
            where.setMnemonicParsing(false);
            line.setMnemonicParsing(false);
            addEventHandler(MouseEvent.MOUSE_CLICKED, event -> {
                if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2 && getItem() != null) {
                    openEdge(getItem());
                }
            });
        }

        @Override
        protected void updateItem(ProjectMapImports.@Nullable Edge item, boolean empty) {
            super.updateItem(item, empty);
            setText(null);
            if (empty || item == null) {
                setGraphic(null);
                setAccessibleText(null);
                return;
            }
            String text = tr(
                    "project.map.deps.whyLine",
                    ProjectMapImports.fileName(item.from()),
                    item.line() + 1,
                    ProjectMapImports.fileName(item.to()));
            where.setText(text);
            line.setText(item.text());
            setGraphic(box);
            setTooltip(new Tooltip(item.from() + "\n" + item.to()));
            setAccessibleText(text + ", " + item.text());
        }
    }

    /** A toggle that stays on when pressed again, so one of its group always is. */
    private static final class StayOnButton extends ToggleButton {
        StayOnButton(String text) {
            super(text);
            setMnemonicParsing(false);
        }

        @Override
        public void fire() {
            if (!isSelected()) {
                super.fire();
            }
        }
    }
}
