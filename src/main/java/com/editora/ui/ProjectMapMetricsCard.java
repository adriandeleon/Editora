package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import com.editora.editor.UiZoom;
import com.editora.git.GitRecentChanges;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * The Project Map's Code Metrics card (Map code browser gap 15): a folder's files — or its parts, their files summed —
 * with their lines of code and of comments, their complexity, their commits of the last 90 days and their hotspot
 * score ({@link ProjectMapMetrics}), the hottest first, any column sortable; and the folder's totals and languages.
 * Enter or a double-click opens a file's card or looks inside a part; Back (Backspace) returns. One is open at a time.
 */
final class ProjectMapMetricsCard extends MapFloatingCard {

    static final double DEFAULT_WIDTH = 640;
    static final double DEFAULT_HEIGHT = 520;
    static final double MIN_WIDTH = 400;
    static final double MIN_HEIGHT = 280;
    /** The most rows listed; the card says when there were more. */
    static final int MAX_ROWS = 1_000;
    /** The most languages the summary names. */
    static final int MAX_LANGUAGES = 4;

    private final Button back = new Button("‹");
    private final ToggleGroup groupings = new ToggleGroup();
    private final ToggleButton filesButton = new MapStayOnToggle(tr("project.map.metrics.files"));
    private final ToggleButton partsButton = new MapStayOnToggle(tr("project.map.metrics.parts"));
    private final Button refresh = new Button();
    private final Label summary = new Label();
    private final TableView<ProjectMapMetrics.Row> table = new TableView<>();
    private final Label placeholder = new Label();

    private Consumer<Path> onReveal = path -> {};
    private Consumer<Path> onOpen = path -> {};
    private Consumer<Path> onDependencies = path -> {};
    private Runnable onRefresh = () -> {};

    private Path root = Path.of("");
    private boolean shown;
    private String subject = "";
    private boolean parts;
    private final Deque<String> backStack = new ArrayDeque<>();
    private @Nullable ProjectMapMetrics metrics;
    private GitRecentChanges.Result changes = GitRecentChanges.Result.NONE;
    private boolean syncing;

    ProjectMapMetricsCard() {
        super("project-map-metrics-card", Icons.metrics(), MIN_WIDTH, MIN_HEIGHT);
        back.getStyleClass().add("flat");
        back.setMnemonicParsing(false);
        Icons.name(back, tr("project.map.metrics.backTip"));
        back.setOnAction(event -> goBack());
        filesButton.setToggleGroup(groupings);
        partsButton.setToggleGroup(groupings);
        filesButton.setTooltip(new Tooltip(tr("project.map.metrics.filesTip")));
        partsButton.setTooltip(new Tooltip(tr("project.map.metrics.partsTip")));
        groupings.selectedToggleProperty().addListener((obs, was, now) -> {
            if (now != null && !syncing) {
                parts = now == partsButton;
                render();
            }
        });
        refresh.getStyleClass().add("flat");
        refresh.setGraphic(Icons.refresh());
        Icons.name(refresh, tr("project.map.metrics.refreshTip"));
        refresh.setOnAction(event -> onRefresh.run());
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox bar = UiZoom.spacing(new HBox(back, filesButton, partsButton, gap, refresh), 4);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("project-map-metrics-bar");
        UiZoom.padding(bar, 4, 6, 4, 6);

        summary.getStyleClass().add("project-map-metrics-summary");
        summary.setWrapText(true);
        summary.setMnemonicParsing(false);
        summary.setMinHeight(Region.USE_PREF_SIZE);
        UiZoom.padding(summary, 6, 10, 6, 10);

        table.getStyleClass().add("project-map-metrics-table");
        table.setAccessibleText(tr("project.map.metrics.tableAccessible"));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        placeholder.getStyleClass().add("project-map-metrics-empty");
        placeholder.setWrapText(true);
        table.setPlaceholder(placeholder);
        TableColumn<ProjectMapMetrics.Row, String> name = new TableColumn<>(tr("project.map.metrics.name"));
        name.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(rowName(subject, cell.getValue())));
        UiZoom.follow(table, name, () -> name.setPrefWidth(UiZoom.px(240)));
        table.getColumns().add(name);
        table.getColumns()
                .add(number("project.map.metrics.code", "project.map.metrics.codeTip", ProjectMapMetrics.Row::code));
        table.getColumns()
                .add(number(
                        "project.map.metrics.comments",
                        "project.map.metrics.commentsTip",
                        ProjectMapMetrics.Row::comments));
        table.getColumns()
                .add(number(
                        "project.map.metrics.complexity",
                        "project.map.metrics.complexityTip",
                        ProjectMapMetrics.Row::complexity));
        table.getColumns()
                .add(number(
                        "project.map.metrics.changes",
                        "project.map.metrics.changesTip",
                        ProjectMapMetrics.Row::changes));
        table.getColumns()
                .add(number(
                        "project.map.metrics.hotspot",
                        "project.map.metrics.hotspotTip",
                        ProjectMapMetrics.Row::hotspot));
        table.setRowFactory(view -> new MetricsRow());
        RowContextMenu.install(table); // the rows carry the menus; the Menu key / Shift+F10 arrive at the table
        table.addEventHandler(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ENTER
                    && enter(table.getSelectionModel().getSelectedItem())) {
                event.consume();
            }
        });
        VBox body = new VBox(bar, summary, table);
        VBox.setVgrow(table, Priority.ALWAYS);
        frame.setCenter(body);
        addEventHandler(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.BACK_SPACE && !event.isShortcutDown() && goBack()) {
                event.consume();
            }
        });
        render();
    }

    /** A column of whole numbers, right-aligned, headed by {@code key}'s text with {@code tip}'s; a negative one shows "—". */
    private static TableColumn<ProjectMapMetrics.Row, Integer> number(
            String key, String tip, Function<ProjectMapMetrics.Row, Integer> value) {
        TableColumn<ProjectMapMetrics.Row, Integer> column = new TableColumn<>();
        Label header = new Label(tr(key));
        header.setTooltip(new Tooltip(tr(tip)));
        column.setGraphic(header);
        column.setId(key);
        column.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(value.apply(cell.getValue())));
        column.setSortType(TableColumn.SortType.DESCENDING);
        double width = key.endsWith("comments") || key.endsWith("complexity") ? 84 : 66;
        UiZoom.follow(header, () -> column.setPrefWidth(UiZoom.px(width)));
        boolean hot = key.endsWith("hotspot");
        column.setCellFactory(view -> new TableCell<>() {
            @Override
            protected void updateItem(@Nullable Integer item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().removeIf(style -> style.startsWith("project-map-metrics-heat-"));
                if (empty || item == null) {
                    setText(null);
                    return;
                }
                setText(item < 0 ? "—" : String.format("%,d", item));
                if (hot) {
                    getStyleClass().add("project-map-metrics-heat-" + heat(item));
                }
            }
        });
        column.getStyleClass().add("project-map-metrics-number");
        return column;
    }

    /** How hot a hotspot score is drawn: 0 (cool) to 4. Pure. */
    static int heat(int score) {
        return score >= 60 ? 4 : score >= 30 ? 3 : score >= 10 ? 2 : score > 0 ? 1 : 0;
    }

    // --- what it shows ----------------------------------------------------------------------------------------

    /** Moves the card onto the folder {@code rel} of the project at {@code root} ({@code ""}: the project). */
    void show(Path root, String rel) {
        if (!shown || !root.equals(this.root)) {
            backStack.clear();
        } else if (!rel.equals(subject)) {
            backStack.push(subject);
        }
        this.root = root;
        this.subject = rel;
        shown = true;
        render();
    }

    /** The answer of a measuring: the card shows the folder with it. */
    void setMetrics(ProjectMapMetrics found) {
        metrics = found;
        render();
    }

    /** A measuring is under way: until it ends, the card says so (or, with an earlier answer, shows that one). */
    void setReading() {
        if (metrics == null) {
            render();
        }
    }

    /** The project's last 90 days of history, for the Changes and Hotspot columns. */
    void setChanges(GitRecentChanges.Result history) {
        if (history != changes) {
            changes = history;
            render();
        }
    }

    /** Goes back to the folder the card was on before; whether there was one. */
    boolean goBack() {
        String previous = backStack.poll();
        if (previous == null) {
            return false;
        }
        subject = previous;
        render();
        return true;
    }

    private void render() {
        syncing = true;
        (parts ? partsButton : filesButton).setSelected(true);
        syncing = false;
        back.setDisable(backStack.isEmpty());
        String name = folderName(subject);
        String heading = tr("project.map.metrics.title", name);
        title.setText(heading);
        title.setTooltip(new Tooltip(subject.isEmpty() ? name : subject));
        setAccessibleText(heading);
        ProjectMapMetrics known = metrics;
        if (known == null) {
            summary.setText(shown ? tr("project.map.metrics.reading") : "");
            placeholder.setText("");
            table.getItems().clear();
            return;
        }
        List<ProjectMapMetrics.Row> rows = known.rows(subject, parts, changes);
        summary.setText(summaryText(known, subject, name, changes, rows.size()));
        placeholder.setText(tr("project.map.metrics.none"));
        table.getSortOrder().clear();
        table.getItems().setAll(rows.subList(0, Math.min(rows.size(), MAX_ROWS)));
        if (!table.getItems().isEmpty()) {
            table.getSelectionModel().selectFirst();
            table.scrollTo(0);
        }
    }

    /**
     * What the line above the table says of the folder {@code folder}, called {@code name}: its totals, its languages,
     * what Changes and Hotspot mean or that there is no history, and what was left out. Pure but for the language.
     */
    static String summaryText(
            ProjectMapMetrics known, String folder, String name, GitRecentChanges.Result changes, int rows) {
        ProjectMapMetrics.Totals totals = known.totals(folder);
        StringBuilder text = new StringBuilder(tr(
                "project.map.metrics.totals",
                name,
                totals.files(),
                totals.code(),
                totals.comments(),
                totals.complexity()));
        List<String> shares = new ArrayList<>();
        for (Map.Entry<String, Integer> language : totals.languages().entrySet()) {
            if (shares.size() == MAX_LANGUAGES) {
                break;
            }
            shares.add(tr(
                    "project.map.metrics.languageShare",
                    language.getKey(),
                    ProjectMapMetrics.percent(language.getValue(), totals.code())));
        }
        if (!shares.isEmpty()) {
            text.append(' ').append(tr("project.map.metrics.languages", String.join(", ", shares)));
        }
        boolean history = !changes.head().isEmpty() && changes.problem().isEmpty();
        text.append(' ').append(tr(history ? "project.map.metrics.history" : "project.map.metrics.noHistory"));
        if (rows > MAX_ROWS) {
            text.append(' ').append(tr("project.map.metrics.rowsCut", MAX_ROWS));
        }
        if (known.truncated()) {
            text.append(' ').append(tr("project.map.metrics.truncated", known.fileCount()));
        }
        return text.toString();
    }

    /** A row's name: its path below the folder, {@code /} after a part's. Pure. */
    static String rowName(String folder, ProjectMapMetrics.Row row) {
        String name = folder.isEmpty() ? row.rel() : row.rel().substring(folder.length() + 1);
        return row.folder() ? name + "/" : name;
    }

    private String folderName(String rel) {
        if (!rel.isEmpty()) {
            return rel + "/";
        }
        Path name = root.getFileName();
        return name == null || name.toString().isEmpty() ? tr("project.map.metrics.project") : name.toString();
    }

    /** Enter or a double-click on {@code row}: a file's card opens, a part is looked inside. */
    private boolean enter(ProjectMapMetrics.@Nullable Row row) {
        if (row == null) {
            return false;
        }
        if (row.folder()) {
            show(root, row.rel());
            focusContent();
        } else {
            onOpen.accept(root.resolve(row.rel()));
        }
        return true;
    }

    private ContextMenu rowMenu(ProjectMapMetrics.Row row) {
        List<MenuItem> items = new ArrayList<>();
        if (row.folder()) {
            MenuItem inside = new MenuItem(tr("project.map.metrics.lookInside"));
            inside.setOnAction(event -> enter(row));
            items.add(inside);
        } else {
            MenuItem open = new MenuItem(tr("project.map.metrics.openCard"));
            open.setOnAction(event -> enter(row));
            items.add(open);
        }
        MenuItem show = new MenuItem(tr("project.map.metrics.showOnMap"));
        show.setOnAction(event -> onReveal.accept(root.resolve(row.rel())));
        MenuItem dependencies = new MenuItem(tr("project.map.metrics.dependencies"));
        dependencies.setOnAction(event -> onDependencies.accept(root.resolve(row.rel())));
        items.add(show);
        items.add(dependencies);
        return new ContextMenu(items.toArray(MenuItem[]::new));
    }

    private final class MetricsRow extends TableRow<ProjectMapMetrics.Row> {
        MetricsRow() {
            addEventHandler(MouseEvent.MOUSE_CLICKED, event -> {
                if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2) {
                    enter(getItem());
                }
            });
        }

        @Override
        protected void updateItem(ProjectMapMetrics.@Nullable Row item, boolean empty) {
            super.updateItem(item, empty);
            setContextMenu(empty || item == null ? null : rowMenu(item));
        }
    }

    // --- callbacks and state ------------------------------------------------------------------------------------

    /** What Show on Map does: the file or folder is revealed on the map. */
    void setOnReveal(Consumer<Path> action) {
        onReveal = action;
    }

    /** What Enter, a double-click or Open as Card on a file does: its card opens. */
    void setOnOpen(Consumer<Path> action) {
        onOpen = action;
    }

    /** What Dependencies on a row does: the Dependencies card opens on it. */
    void setOnDependencies(Consumer<Path> action) {
        onDependencies = action;
    }

    /** What the refresh button does: the project is measured again. */
    void setOnRefresh(Runnable action) {
        onRefresh = action;
    }

    @Override
    void focusContent() {
        table.requestFocus();
    }

    /** The project's folder, or null before the card was shown. */
    @Nullable
    Path root() {
        return shown ? root : null;
    }

    String subject() {
        return subject;
    }

    boolean showsParts() {
        return parts;
    }

    String summaryText() {
        return summary.getText();
    }

    /** The rows listed, in their order. For tests. */
    List<ProjectMapMetrics.Row> rowsShown() {
        return List.copyOf(table.getItems());
    }

    /** Picks the {@code index}th row. For tests. */
    void pickRow(int index) {
        table.getSelectionModel().select(index);
    }

    /** Enter on the picked row; whether there was one. For tests. */
    boolean enterPicked() {
        return enter(table.getSelectionModel().getSelectedItem());
    }

    /** The menu of the {@code index}th row. For tests. */
    ContextMenu menuOf(int index) {
        return rowMenu(table.getItems().get(index));
    }

    TableView<ProjectMapMetrics.Row> table() {
        return table;
    }
}
