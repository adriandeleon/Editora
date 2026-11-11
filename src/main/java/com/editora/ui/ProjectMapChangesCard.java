package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import com.editora.git.GitChanges;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * The working tree's changes on the Project Map (Map code browser round 2): every file changed against HEAD below the
 * Map's folder — untracked ones too — with the lines it adds and removes, and under them the picked file's diff, its
 * lines numbered as in the old and the new file. Next and Previous Change (F7, Shift+F7, or the editor's keys on the
 * Map) step through the hunks of every file; Enter or a double click on a line opens the file's card at that line on
 * the map, Open in Editor the file there, Compare with HEAD the editor's diff of it. One is open at a time.
 */
final class ProjectMapChangesCard extends MapFloatingCard {

    static final double DEFAULT_WIDTH = 560;
    static final double DEFAULT_HEIGHT = 600;
    static final double MIN_WIDTH = 340;
    static final double MIN_HEIGHT = 280;

    private final Label summary = new Label();
    private final ListView<GitChanges.FileDiff> files = new ListView<>();
    private final ListView<GitChanges.Line> diff = new ListView<>();
    private final Button previous = new Button("↑");
    private final Button next = new Button("↓");
    private final Button openInEditor = new Button(tr("project.map.changes.openInEditor"));
    private final Button compare = new Button(tr("project.map.changes.compare"));
    private final Button refresh = new Button(tr("project.map.changes.refresh"));

    private BiConsumer<Path, Integer> onOpenLine = (file, line) -> {};
    private BiConsumer<Path, Integer> onOpenInEditor = (file, line) -> {};
    private Consumer<Path> onCompare = file -> {};
    private Runnable onRefresh = () -> {};
    private Consumer<String> onStatus = message -> {};

    private @Nullable Path root;
    private GitChanges.@Nullable Result result;
    /** The file to pick when the changes arrive (relative to the root), or null for the first. */
    private @Nullable String wanted;
    /** The hunk stepped to in the picked file, or -1. */
    private int hunk = -1;
    /** A step asked for before the changes arrived: +1 next, -1 previous, 0 none. */
    private int pendingStep;

    ProjectMapChangesCard() {
        super("project-map-changes-card", Icons.diff(), MIN_WIDTH, MIN_HEIGHT);
        summary.getStyleClass().add("project-map-changes-summary");
        summary.setWrapText(true);
        summary.setMnemonicParsing(false);
        summary.setMinHeight(Region.USE_PREF_SIZE);
        summary.setPadding(new Insets(6, 10, 6, 10));

        files.getStyleClass().add("project-map-changes-files");
        files.setAccessibleText(tr("project.map.changes.filesAccessible"));
        files.setCellFactory(list -> new FileCell());
        files.setPlaceholder(placeholder(""));
        files.getSelectionModel().selectedItemProperty().addListener((obs, was, now) -> filePicked(now));
        files.addEventHandler(KeyEvent.KEY_PRESSED, event -> {
            GitChanges.FileDiff file = files.getSelectionModel().getSelectedItem();
            if (event.getCode() == KeyCode.ENTER
                    && file != null
                    && !file.hunks().isEmpty()) {
                if (hunk < 0) {
                    showHunk(0);
                }
                diff.requestFocus();
                event.consume();
            }
        });

        diff.getStyleClass().addAll("project-map-changes-diff", "project-map-changes-code");
        diff.setAccessibleText(tr("project.map.changes.diffAccessible"));
        diff.setCellFactory(list -> new LineCell());
        diff.setPlaceholder(placeholder(""));
        diff.addEventHandler(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ENTER) {
                openLine(diff.getSelectionModel().getSelectedIndex(), event.isShortcutDown());
                event.consume();
            }
        });
        addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.F7 && !event.isControlDown() && !event.isAltDown() && !event.isMetaDown()) {
                step(!event.isShiftDown());
                event.consume();
            }
        });

        for (Button button : List.of(previous, next, openInEditor, compare, refresh)) {
            button.getStyleClass().add("flat");
            button.setMnemonicParsing(false);
            button.setFocusTraversable(false);
        }
        previous.setTooltip(new Tooltip(tr("project.map.changes.previousTip")));
        previous.setAccessibleText(tr("project.map.changes.previousTip"));
        next.setTooltip(new Tooltip(tr("project.map.changes.nextTip")));
        next.setAccessibleText(tr("project.map.changes.nextTip"));
        openInEditor.setTooltip(new Tooltip(tr("project.map.changes.openInEditorTip")));
        compare.setTooltip(new Tooltip(tr("project.map.changes.compareTip")));
        refresh.setTooltip(new Tooltip(tr("project.map.changes.refreshTip")));
        previous.setOnAction(event -> step(false));
        next.setOnAction(event -> step(true));
        openInEditor.setOnAction(event -> openLine(diff.getSelectionModel().getSelectedIndex(), true));
        compare.setOnAction(event -> {
            Path file = pickedPath();
            if (file != null) {
                onCompare.accept(file);
            }
        });
        refresh.setOnAction(event -> onRefresh.run());
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox bar = new HBox(4, previous, next, gap, openInEditor, compare, refresh);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("project-map-changes-bar");
        bar.setPadding(new Insets(4, 6, 4, 6));
        VBox lower = new VBox(bar, diff);
        VBox.setVgrow(diff, Priority.ALWAYS);

        SplitPane split = new SplitPane(files, lower);
        split.setOrientation(Orientation.VERTICAL);
        split.setDividerPositions(0.3);
        split.getStyleClass().add("project-map-changes-split");
        VBox body = new VBox(summary, split);
        VBox.setVgrow(split, Priority.ALWAYS);
        frame.setCenter(body);
        title.setText(tr("project.map.changes.title"));
        setAccessibleText(tr("project.map.changes.title"));
        updateButtons();
    }

    /**
     * Shows the changes below {@code root}, "reading" until {@link #setChanges} brings them, with {@code select} — a
     * file, or a folder whose first changed file is wanted — picked then; null keeps the file picked now.
     */
    void show(Path root, @Nullable Path select) {
        boolean sameRoot = root.equals(this.root);
        this.root = root;
        title.setTooltip(new Tooltip(root.toString()));
        if (select != null && select.startsWith(root)) {
            wanted = relative(root, select);
        } else if (!sameRoot) {
            wanted = null;
        } else {
            GitChanges.FileDiff now = files.getSelectionModel().getSelectedItem();
            wanted = now == null ? wanted : now.path();
        }
        if (!sameRoot) {
            result = null;
            files.getItems().clear();
        }
        if (result == null) {
            summary.setText(tr("project.map.changes.reading"));
        }
    }

    /** The changes read; the file wanted (or the one picked before) is picked, else the first. */
    void setChanges(GitChanges.Result found) {
        Path base = root;
        if (base == null) {
            return;
        }
        String keep = wanted;
        GitChanges.FileDiff before = files.getSelectionModel().getSelectedItem();
        if (keep == null && before != null) {
            keep = before.path();
        }
        int keptHunk = before != null && before.path().equals(keep) ? hunk : -1;
        result = found;
        wanted = null;
        summary.setText(summaryText(found));
        files.getItems().setAll(found.files());
        GitChanges.FileDiff pick = null;
        if (keep != null) {
            for (GitChanges.FileDiff file : found.files()) {
                if (file.path().equals(keep) || file.path().startsWith(keep.isEmpty() ? "" : keep + "/")) {
                    pick = file;
                    break;
                }
            }
        }
        if (pick == null && !found.files().isEmpty()) {
            pick = found.files().get(0);
        }
        if (pick != null) {
            files.getSelectionModel().select(pick);
            files.scrollTo(pick);
            if (keptHunk >= 0 && keptHunk < pick.hunks().size()) {
                showHunk(keptHunk);
            }
        } else {
            filePicked(null);
        }
        int step = pendingStep;
        pendingStep = 0;
        if (step != 0) {
            step(step > 0);
        }
        updateButtons();
    }

    /** What the line above the files says of {@code found}. Pure but for the language. */
    static String summaryText(GitChanges.Result found) {
        if (!found.ok()) {
            return tr("project.map.changes.unavailable");
        }
        if (found.files().isEmpty()) {
            return tr("project.map.changes.none");
        }
        int added = 0;
        int removed = 0;
        for (GitChanges.FileDiff file : found.files()) {
            added += file.added();
            removed += file.removed();
        }
        return tr(
                found.truncated() ? "project.map.changes.countsMore" : "project.map.changes.counts",
                found.files().size(),
                added,
                removed);
    }

    /**
     * Steps to the next ({@code forward}) or previous hunk of every file, going round, and says which of how many it
     * is on the status line; asked before the changes arrived, it steps once they do.
     */
    void step(boolean forward) {
        GitChanges.Result found = result;
        if (found == null) {
            pendingStep = forward ? 1 : -1;
            return;
        }
        List<int[]> all = new ArrayList<>(); // {file, hunk}
        for (int f = 0; f < found.files().size(); f++) {
            for (int h = 0; h < found.files().get(f).hunks().size(); h++) {
                all.add(new int[] {f, h});
            }
        }
        if (all.isEmpty()) {
            onStatus.accept(tr("project.map.changes.none"));
            return;
        }
        int file = files.getSelectionModel().getSelectedIndex();
        int at = -1;
        for (int i = 0; i < all.size(); i++) {
            int[] place = all.get(i);
            if (place[0] < file || place[0] == file && place[1] <= hunk) {
                at = i; // the last place at or before where we stand
            }
        }
        boolean standing = at >= 0 && all.get(at)[0] == file && all.get(at)[1] == hunk;
        int target;
        if (forward) {
            target = (at + 1) % all.size();
        } else {
            int before = standing ? at - 1 : at;
            target = before < 0 ? all.size() - 1 : before;
        }
        int[] place = all.get(target);
        files.getSelectionModel().select(place[0]);
        files.scrollTo(Math.max(0, place[0] - 2));
        showHunk(place[1]);
        GitChanges.FileDiff picked = found.files().get(place[0]);
        onStatus.accept(
                tr("project.map.changes.step", target + 1, all.size(), picked.path(), picked.hunkLine(place[1])));
    }

    private void showHunk(int index) {
        GitChanges.FileDiff file = files.getSelectionModel().getSelectedItem();
        if (file == null || index < 0 || index >= file.hunks().size()) {
            return;
        }
        hunk = index;
        int header = file.hunks().get(index);
        int first = header;
        for (int i = header + 1; i < file.lines().size() && file.lines().get(i).kind() != GitChanges.Kind.HUNK; i++) {
            GitChanges.Kind kind = file.lines().get(i).kind();
            if (kind == GitChanges.Kind.ADDED || kind == GitChanges.Kind.REMOVED) {
                first = i;
                break;
            }
        }
        diff.getSelectionModel().select(first);
        diff.scrollTo(Math.max(0, header - 1));
    }

    private void filePicked(GitChanges.@Nullable FileDiff file) {
        hunk = -1;
        if (file == null) {
            diff.getItems().clear();
            diff.setPlaceholder(placeholder(""));
        } else {
            diff.getItems().setAll(file.lines());
            diff.setPlaceholder(placeholder(
                    file.binary()
                            ? tr("project.map.changes.binary")
                            : file.truncated() && file.lines().isEmpty()
                                    ? tr("project.map.changes.tooLarge")
                                    : tr("project.map.changes.empty")));
            diff.scrollTo(0);
        }
        updateButtons();
    }

    private void updateButtons() {
        boolean any = result != null
                && result.files().stream().anyMatch(file -> !file.hunks().isEmpty());
        previous.setDisable(!any);
        next.setDisable(!any);
        GitChanges.FileDiff picked = files.getSelectionModel().getSelectedItem();
        openInEditor.setDisable(picked == null || picked.status() == 'D');
        compare.setDisable(picked == null);
    }

    /** Opens the picked file's card (or, with {@code inEditor}, the file in the editor) at the diff's line {@code index}. */
    private void openLine(int index, boolean inEditor) {
        Path file = pickedPath();
        GitChanges.FileDiff picked = files.getSelectionModel().getSelectedItem();
        if (file == null || picked == null || picked.status() == 'D') {
            return;
        }
        int header = index < 0 ? -1 : picked.hunks().indexOf(index);
        int line = index < 0
                ? (hunk >= 0 ? picked.hunkLine(hunk) : 1)
                : header >= 0 ? picked.hunkLine(header) : picked.nearestNewLine(index);
        (inEditor ? onOpenInEditor : onOpenLine).accept(file, line - 1);
    }

    /** The picked file's path, or null. */
    @Nullable
    Path pickedPath() {
        Path base = root;
        GitChanges.FileDiff file = files.getSelectionModel().getSelectedItem();
        return base == null || file == null ? null : base.resolve(file.path()).normalize();
    }

    private static String relative(Path root, Path path) {
        return root.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    /** The folder whose changes are shown, or null before the first. */
    @Nullable
    Path root() {
        return root;
    }

    GitChanges.@Nullable Result changes() {
        return result;
    }

    String summaryText() {
        return summary.getText();
    }

    /** The hunk stepped to in the picked file, or -1. */
    int hunk() {
        return hunk;
    }

    /** The diff's line picked, or -1. */
    int pickedLine() {
        return diff.getSelectionModel().getSelectedIndex();
    }

    /** Picks the diff's line {@code index} and opens it, as Enter there does (with {@code inEditor}: Ctrl+Enter). */
    void openPickedLine(int index, boolean inEditor) {
        diff.getSelectionModel().select(index);
        openLine(index, inEditor);
    }

    /** Picks the file at {@code index}. For tests and keys. */
    void pickFile(int index) {
        files.getSelectionModel().select(index);
    }

    /** What Enter or a double click on a line does: the file's card at that 0-based line. */
    void setOnOpenLine(BiConsumer<Path, Integer> action) {
        onOpenLine = action;
    }

    /** What Open in Editor and Ctrl+Enter do: the file in the editor at that 0-based line. */
    void setOnOpenInEditor(BiConsumer<Path, Integer> action) {
        onOpenInEditor = action;
    }

    /** What Compare with HEAD does: the editor's diff of the file. */
    void setOnCompare(Consumer<Path> action) {
        onCompare = action;
    }

    void setOnRefresh(Runnable action) {
        onRefresh = action;
    }

    /** Where Next and Previous Change say which change of how many they reached. */
    void setOnStatus(Consumer<String> action) {
        onStatus = action;
    }

    /** Moves the keyboard into the card: its list of files. */
    @Override
    void focusContent() {
        files.requestFocus();
    }

    // --- rows -----------------------------------------------------------------------------------------------

    /** A file's counts: the lines it adds and removes, or that it is binary or too large to show. */
    static String fileCounts(GitChanges.FileDiff file) {
        if (file.binary()) {
            return tr("project.map.changes.binaryShort");
        }
        if (file.truncated() && file.lines().isEmpty()) {
            return tr("project.map.changes.tooLargeShort");
        }
        return tr("project.map.changes.fileCounts", file.added(), file.removed());
    }

    /** The status letter's words, for the tooltip and screen readers. */
    static String statusName(char status) {
        return switch (status) {
            case 'A' -> tr("project.map.changes.added");
            case 'D' -> tr("project.map.changes.deleted");
            case 'U' -> tr("project.map.changes.untracked");
            default -> tr("project.map.changes.modified");
        };
    }

    private static String statusClass(char status) {
        return switch (status) {
            case 'A' -> "git-status-added";
            case 'D' -> "git-status-deleted";
            case 'U' -> "git-status-untracked";
            default -> "git-status-modified";
        };
    }

    private static Label placeholder(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("project-map-changes-placeholder");
        label.setWrapText(true);
        return label;
    }

    private final class FileCell extends ListCell<GitChanges.FileDiff> {
        private static final List<String> STATUS =
                List.of("git-status-added", "git-status-deleted", "git-status-untracked", "git-status-modified");
        private final Label letter = new Label();
        private final Label name = new Label();
        private final Label counts = new Label();
        private final HBox box = new HBox(8, letter, name, counts);

        FileCell() {
            letter.getStyleClass().add("project-map-changes-letter");
            name.getStyleClass().add("project-map-changes-path");
            counts.getStyleClass().add("project-map-changes-counts");
            letter.setMinWidth(Region.USE_PREF_SIZE);
            counts.setMinWidth(Region.USE_PREF_SIZE);
            name.setMinWidth(0);
            name.setMnemonicParsing(false);
            HBox.setHgrow(name, Priority.ALWAYS);
            name.setMaxWidth(Double.MAX_VALUE);
            box.setAlignment(Pos.CENTER_LEFT);
            setOnMouseClicked(event -> {
                if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2 && getItem() != null) {
                    if (!getItem().hunks().isEmpty()) {
                        showHunk(0);
                        openLine(diff.getSelectionModel().getSelectedIndex(), false);
                    }
                }
            });
        }

        @Override
        protected void updateItem(GitChanges.@Nullable FileDiff item, boolean empty) {
            super.updateItem(item, empty);
            letter.getStyleClass().removeAll(STATUS);
            if (empty || item == null) {
                setGraphic(null);
                setText(null);
                setAccessibleText(null);
                setTooltip(null);
                return;
            }
            letter.setText(String.valueOf(item.status()));
            letter.getStyleClass().add(statusClass(item.status()));
            name.setText(item.path());
            counts.setText(fileCounts(item));
            setText(null);
            setGraphic(box);
            setAccessibleText(item.path() + ", " + statusName(item.status()) + ", " + counts.getText());
            setTooltip(new Tooltip(item.path() + "\n" + statusName(item.status())));
        }
    }

    private final class LineCell extends ListCell<GitChanges.Line> {
        private static final List<String> STYLES = List.of(
                "project-map-changes-hunk",
                "project-map-changes-added",
                "project-map-changes-removed",
                "project-map-changes-note",
                "project-map-changes-context");
        private final Label oldNumber = new Label();
        private final Label newNumber = new Label();
        private final Label code = new Label();
        private final HBox box = new HBox(0, oldNumber, newNumber, code);

        LineCell() {
            for (Label number : List.of(oldNumber, newNumber)) {
                number.getStyleClass().add("project-map-changes-number");
                number.setMinWidth(Region.USE_PREF_SIZE);
                number.setPrefWidth(40);
                number.setAlignment(Pos.CENTER_RIGHT);
            }
            code.getStyleClass().add("project-map-changes-line");
            code.setMinWidth(0);
            code.setMnemonicParsing(false);
            code.setPadding(new Insets(0, 0, 0, 8));
            box.setAlignment(Pos.CENTER_LEFT);
            setOnMouseClicked(event -> {
                if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2 && !isEmpty()) {
                    openLine(getIndex(), false);
                }
            });
        }

        @Override
        protected void updateItem(GitChanges.@Nullable Line item, boolean empty) {
            super.updateItem(item, empty);
            getStyleClass().removeAll(STYLES);
            if (empty || item == null) {
                setGraphic(null);
                setText(null);
                setAccessibleText(null);
                return;
            }
            boolean numbered = item.kind() != GitChanges.Kind.HUNK; // a header's numbers are in its text
            oldNumber.setText(numbered && item.oldLine() > 0 ? Integer.toString(item.oldLine()) : "");
            newNumber.setText(numbered && item.newLine() > 0 ? Integer.toString(item.newLine()) : "");
            String marker = switch (item.kind()) {
                case ADDED -> "+ ";
                case REMOVED -> "− ";
                case CONTEXT -> "  ";
                default -> "";
            };
            code.setText(marker + item.text().replace("\t", "    "));
            setText(null);
            setGraphic(box);
            getStyleClass()
                    .add(
                            switch (item.kind()) {
                                case HUNK -> "project-map-changes-hunk";
                                case ADDED -> "project-map-changes-added";
                                case REMOVED -> "project-map-changes-removed";
                                case NOTE -> "project-map-changes-note";
                                case CONTEXT -> "project-map-changes-context";
                            });
            setAccessibleText(
                    switch (item.kind()) {
                        case ADDED -> tr("project.map.changes.lineAdded", item.newLine(), item.text());
                        case REMOVED -> tr("project.map.changes.lineRemoved", item.oldLine(), item.text());
                        default -> item.text();
                    });
        }
    }
}
