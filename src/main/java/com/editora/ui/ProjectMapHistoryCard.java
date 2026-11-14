package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import com.editora.editor.ControlNames;
import com.editora.editor.UiZoom;
import com.editora.git.BlameParser;
import com.editora.git.GitFormat;
import com.editora.git.LineHistory;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * A declaration's history on the Project Map (Map code browser gap 12): every commit that changed the declaration's
 * lines, newest first ({@code git log -L}), and under them either the part of the picked commit's diff that touched
 * it (Changes) or who last changed each of its lines now (Blame). It opens beside the declaration card it was asked
 * from — or, asked from a Structure row, where that row's card would — floats over the map like the other cards, and
 * one is open at a time: asking for another declaration's history fills it again. Enter or a double click on a commit,
 * or on a line in Blame, opens that commit's diff of the file in the editor window.
 */
final class ProjectMapHistoryCard extends MapFloatingCard {

    static final double DEFAULT_WIDTH = 480;
    static final double DEFAULT_HEIGHT = 540;
    static final double MIN_WIDTH = 320;
    static final double MIN_HEIGHT = 260;
    /** How many commits a history lists; it says when there were more. */
    static final int MAX_COMMITS = 100;

    /** What the lower half shows. */
    enum Mode {
        /** The picked commit's diff of the declaration. */
        CHANGES,
        /** Who last changed each of the declaration's lines. */
        BLAME
    }

    /** One of the declaration's lines as it is now, with the commit that last changed it (null: not known yet). */
    record BlameRow(int line, String text, BlameParser.@Nullable BlameLine blame) {}

    private final Label summary = new Label();
    private final ListView<LineHistory.Commit> commits = new ListView<>();
    private final ToggleGroup modes = new ToggleGroup();
    private final ToggleButton changesButton = new ModeButton(tr("project.map.history.changes"));
    private final ToggleButton blameButton = new ModeButton(tr("project.map.history.blame"));
    private final Button openCommit = new Button(tr("project.map.history.openCommit"));
    private final ListView<String> diff = new ListView<>();
    private final ListView<BlameRow> blame = new ListView<>();
    private final StackPane lower = new StackPane();

    private Consumer<LineHistory.Commit> onOpenCommit = commit -> {};
    private Consumer<Mode> onModeChanged = mode -> {};

    private @Nullable Path file;
    private String name = "";
    private int startLine = -1;
    private int endLine = -1;
    private LineHistory.@Nullable Result history;
    private List<String> lines = List.of();
    private @Nullable List<BlameParser.BlameLine> blamed;
    private Mode mode = Mode.CHANGES;
    /** Bumped by each {@link #show}: an answer for an earlier declaration is dropped. */
    private long shown;

    ProjectMapHistoryCard() {
        super("project-map-history-card", Icons.gitLog(), MIN_WIDTH, MIN_HEIGHT);
        summary.getStyleClass().add("project-map-history-summary");
        summary.setWrapText(true);
        summary.setMnemonicParsing(false);
        summary.setMinHeight(Region.USE_PREF_SIZE);

        commits.getStyleClass().add("project-map-history-commits");
        commits.setAccessibleText(tr("project.map.history.commitsAccessible"));
        commits.setCellFactory(list -> new CommitCell());
        commits.setPlaceholder(new Label(""));
        commits.getSelectionModel().selectedItemProperty().addListener((obs, was, now) -> commitPicked(now));
        commits.addEventHandler(KeyEvent.KEY_PRESSED, event -> {
            LineHistory.Commit picked = commits.getSelectionModel().getSelectedItem();
            if (event.getCode() == KeyCode.ENTER && picked != null) {
                onOpenCommit.accept(picked);
                event.consume();
            }
        });

        changesButton.setToggleGroup(modes);
        blameButton.setToggleGroup(modes);
        changesButton.setSelected(true);
        changesButton.setTooltip(ControlNames.tooltip(tr("project.map.history.changesTip")));
        blameButton.setTooltip(ControlNames.tooltip(tr("project.map.history.blameTip")));
        modes.selectedToggleProperty().addListener((obs, was, now) -> {
            if (now != null) {
                setMode(now == blameButton ? Mode.BLAME : Mode.CHANGES);
                onModeChanged.accept(mode);
            }
        });
        openCommit.getStyleClass().add("flat");
        openCommit.setMnemonicParsing(false);
        openCommit.setTooltip(ControlNames.tooltip(tr("project.map.history.openCommitTip")));
        openCommit.setOnAction(event -> {
            LineHistory.Commit picked = pickedCommit();
            if (picked != null) {
                onOpenCommit.accept(picked);
            }
        });
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox bar = UiZoom.spacing(new HBox(changesButton, blameButton, gap, openCommit), 4);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("project-map-history-bar");
        UiZoom.padding(bar, 4, 6, 4, 6);

        diff.getStyleClass().addAll("project-map-history-diff", "project-map-history-code");
        diff.setAccessibleText(tr("project.map.history.diffAccessible"));
        diff.setCellFactory(list -> new DiffCell());
        diff.setPlaceholder(placeholder(""));
        blame.getStyleClass().addAll("project-map-history-blame", "project-map-history-code");
        blame.setAccessibleText(tr("project.map.history.blameAccessible"));
        blame.setCellFactory(list -> new BlameCell());
        blame.setPlaceholder(placeholder(""));
        blame.getSelectionModel().selectedItemProperty().addListener((obs, was, now) -> blamePicked(now));
        blame.addEventHandler(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ENTER
                    && openBlamed(blame.getSelectionModel().getSelectedItem())) {
                event.consume();
            }
        });
        // The three lists' right-click menus: what Enter, a double-click and Open Commit do, and Copy (MENU-05).
        ListRowCommands.install(
                commits,
                ListRowCommands.buttonRow(commits, ListRowCommands.OPEN, openCommit, Icons.openInTab()),
                ListRowCommands.copyRow(
                        commits, () -> ListRowCommands.selectedText(commits, c -> c.hash() + " " + c.subject())));
        ListRowCommands.install(diff, ListRowCommands.copyRow(diff, () -> ListRowCommands.selectedText(diff, l -> l)));
        ListRowCommands.install(
                blame,
                ListRowCommands.row(
                        blame,
                        ListRowCommands.OPEN,
                        null,
                        Icons.openInTab(),
                        () -> openBlamed(blame.getSelectionModel().getSelectedItem())),
                ListRowCommands.copyRow(blame, () -> ListRowCommands.selectedText(blame, BlameRow::text)));
        lower.getChildren().setAll(diff, blame);
        blame.setVisible(false);
        VBox lowerBox = new VBox(bar, lower);
        VBox.setVgrow(lower, Priority.ALWAYS);

        SplitPane split = new SplitPane(commits, lowerBox);
        split.setOrientation(Orientation.VERTICAL);
        split.setDividerPositions(0.38);
        split.getStyleClass().add("project-map-history-split");
        VBox body = new VBox(summary, split);
        VBox.setVgrow(split, Priority.ALWAYS);
        UiZoom.padding(summary, 6, 10, 6, 10);
        frame.setCenter(body);
    }

    /**
     * Shows the history of {@code name}, the declaration on {@code file}'s 0-based lines {@code startLine..endLine},
     * whose text now is {@code text} (the whole file): "reading" until {@link #setHistory} brings the commits.
     * Returns the ask's number, which {@link #setHistory} and {@link #setBlame} are answered with.
     */
    long show(Path file, String name, int startLine, int endLine, String text) {
        this.file = file;
        this.name = name;
        this.startLine = startLine;
        this.endLine = endLine;
        this.lines = declarationLines(text, startLine, endLine);
        history = null;
        blamed = null;
        String heading = name.isBlank()
                ? tr("project.map.history.titleFile", com.editora.config.PathDisplay.fileName(file))
                : tr("project.map.history.title", name);
        title.setText(heading);
        title.setTooltip(ControlNames.tooltip(tr(
                "project.map.history.titleTip",
                com.editora.config.PathDisplay.fileName(file),
                startLine + 1,
                endLine + 1)));
        setAccessibleText(heading);
        summary.setText(tr("project.map.history.reading"));
        commits.getItems().clear();
        diff.getItems().clear();
        fillBlame();
        openCommit.setDisable(true);
        return ++shown;
    }

    /** The commits for the ask {@code ask} of {@link #show}; an older ask's are dropped. */
    void setHistory(long ask, LineHistory.Result result) {
        if (ask != shown) {
            return;
        }
        history = result;
        summary.setText(summaryText(result, System.currentTimeMillis() / 1000));
        commits.getItems().setAll(result.commits());
        diff.getItems().clear();
        if (!result.commits().isEmpty()) {
            commits.getSelectionModel().selectFirst();
        } else {
            diff.setPlaceholder(placeholder(""));
        }
        fillBlame();
    }

    /**
     * The file's text now is {@code text}, for the ask {@code ask} (the declaration card it was asked from had not
     * read its file yet when it was shown): Blame shows its lines. An empty text changes nothing.
     */
    void setLines(long ask, String text) {
        if (ask != shown || text.isEmpty()) {
            return;
        }
        lines = declarationLines(text, startLine, endLine);
        fillBlame();
    }

    /** The 0-based lines {@code startLine..endLine} of {@code text}, as many of them as it has. Pure. */
    static List<String> declarationLines(String text, int startLine, int endLine) {
        List<String> all = text.lines().toList();
        return startLine >= 0 && startLine < all.size() && endLine >= startLine
                ? List.copyOf(all.subList(startLine, Math.min(all.size(), endLine + 1)))
                : List.of();
    }

    /** Who last changed each line of the file, for the ask {@code ask}; an older ask's are dropped. */
    void setBlame(long ask, List<BlameParser.BlameLine> lines) {
        if (ask != shown) {
            return;
        }
        blamed = List.copyOf(lines);
        fillBlame();
    }

    /** What the line above the commits says of {@code result}, as of {@code nowSeconds}. Pure but for the language. */
    static String summaryText(LineHistory.Result result, long nowSeconds) {
        return switch (result.status()) {
            case UNAVAILABLE -> tr("project.map.history.unavailable");
            case NEW_LINES -> tr("project.map.history.newLines");
            case TOO_SLOW -> tr("project.map.history.tooSlow");
            case FAILED -> tr("project.map.history.failed", result.problem());
            case OK -> {
                List<LineHistory.Commit> list = result.commits();
                if (list.isEmpty()) {
                    yield tr("project.map.history.none");
                }
                String counts = tr(
                        result.truncated() ? "project.map.history.countsMore" : "project.map.history.counts",
                        list.size(),
                        result.authors().size());
                LineHistory.Commit oldest = list.get(list.size() - 1);
                if (result.truncated()) {
                    yield counts;
                }
                yield counts
                        + " "
                        + tr(
                                "project.map.history.since",
                                oldest.author(),
                                GitBlameCoordinator.relativeTimeLabel(oldest.epochSeconds(), nowSeconds));
            }
        };
    }

    private void commitPicked(LineHistory.@Nullable Commit commit) {
        openCommit.setDisable(commit == null);
        if (commit == null) {
            diff.getItems().clear();
            return;
        }
        diff.getItems().setAll(commit.patch());
        diff.scrollTo(0);
        blame.refresh(); // the picked commit's lines stand out
    }

    private void blamePicked(@Nullable BlameRow row) {
        BlameParser.BlameLine line = row == null ? null : row.blame();
        if (line == null) {
            return;
        }
        for (LineHistory.Commit commit : commits.getItems()) {
            if (commit.hash().equals(line.hash())) {
                commits.getSelectionModel().select(commit);
                commits.scrollTo(commit);
                return;
            }
        }
    }

    private boolean openBlamed(@Nullable BlameRow row) {
        BlameParser.BlameLine line = row == null ? null : row.blame();
        if (line == null || line.uncommitted()) {
            return false;
        }
        for (LineHistory.Commit commit : commits.getItems()) {
            if (commit.hash().equals(line.hash())) {
                onOpenCommit.accept(commit);
                return true;
            }
        }
        return false;
    }

    private void fillBlame() {
        List<BlameParser.BlameLine> known = blamed;
        List<BlameRow> rows = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            int line = startLine + i;
            BlameParser.BlameLine by = known != null && line < known.size() ? known.get(line) : null;
            rows.add(new BlameRow(line, lines.get(i), by));
        }
        blame.getItems().setAll(rows);
        blame.setPlaceholder(placeholder(known == null ? tr("project.map.history.reading") : ""));
    }

    /** Shows the picked commit's diff, or who changed each line. */
    void setMode(Mode requested) {
        mode = requested;
        diff.setVisible(mode == Mode.CHANGES);
        blame.setVisible(mode == Mode.BLAME);
        (mode == Mode.BLAME ? blameButton : changesButton).setSelected(true);
    }

    Mode mode() {
        return mode;
    }

    /** The commit picked in the list, or null. */
    LineHistory.@Nullable Commit pickedCommit() {
        return commits.getSelectionModel().getSelectedItem();
    }

    /** Picks the {@code index}th commit, the newest first. For tests and keys. */
    void pickCommit(int index) {
        commits.getSelectionModel().select(index);
    }

    /** Picks the {@code index}th line of Blame. For tests. */
    void pickBlameLine(int index) {
        blame.getSelectionModel().select(index);
    }

    /** Opens the picked line of Blame's commit, as Enter there does; whether there was one. For tests. */
    boolean openPickedBlameLine() {
        return openBlamed(blame.getSelectionModel().getSelectedItem());
    }

    String summaryText() {
        return summary.getText();
    }

    /** The commits listed, newest first. */
    List<LineHistory.Commit> commits() {
        return List.copyOf(commits.getItems());
    }

    /** The diff lines shown for the picked commit. */
    List<String> diffLines() {
        return List.copyOf(diff.getItems());
    }

    /** What each Blame row says, as its cell writes it. For tests. */
    List<String> blameTexts() {
        long now = System.currentTimeMillis() / 1000;
        return blame.getItems().stream().map(row -> blameLabel(row, now)).toList();
    }

    @Nullable
    Path file() {
        return file;
    }

    String declarationName() {
        return name;
    }

    int startLine() {
        return startLine;
    }

    int endLine() {
        return endLine;
    }

    LineHistory.@Nullable Result history() {
        return history;
    }

    /** What Open Commit, Enter or a double click on a commit does: its diff of the file opens in the editor. */
    void setOnOpenCommit(Consumer<LineHistory.Commit> action) {
        onOpenCommit = action;
    }

    /** Told when Changes or Blame is picked, so the next history card opens the same way. */
    void setOnModeChanged(Consumer<Mode> action) {
        onModeChanged = action;
    }

    /** Moves the keyboard into the card: its list of commits. */
    @Override
    void focusContent() {
        commits.requestFocus();
    }

    // --- rows -----------------------------------------------------------------------------------------------

    /** A commit's first line: its subject; its second: author, age, short hash, and the lines it added and removed. */
    static String commitDetail(LineHistory.Commit commit, long nowSeconds) {
        return tr(
                "project.map.history.commitDetail",
                commit.author(),
                GitBlameCoordinator.relativeTimeLabel(commit.epochSeconds(), nowSeconds),
                GitFormat.shortHash(commit.hash()),
                commit.added(),
                commit.removed());
    }

    /** A Blame row's annotation: who last changed the line and when, or that it is not committed (not known: ""). */
    static String blameAnnotation(BlameParser.@Nullable BlameLine line, long nowSeconds) {
        if (line == null) {
            return "";
        }
        if (line.uncommitted()) {
            return tr("project.map.history.uncommitted");
        }
        return tr(
                "project.map.history.blameBy",
                line.author(),
                GitBlameCoordinator.relativeTimeLabel(line.epochSeconds(), nowSeconds));
    }

    private static String blameLabel(BlameRow row, long nowSeconds) {
        String by = blameAnnotation(row.blame(), nowSeconds);
        return by.isEmpty() ? row.text() : by + " │ " + row.text();
    }

    /** A diff line's style: an added, removed or hunk-header line, or context (null). Pure. */
    static @Nullable String diffStyle(String line) {
        if (line.startsWith("@@")) {
            return "project-map-history-hunk";
        }
        if (line.startsWith("+")) {
            return "project-map-history-added";
        }
        if (line.startsWith("-")) {
            return "project-map-history-removed";
        }
        return null;
    }

    private static Label placeholder(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("project-map-history-placeholder");
        label.setWrapText(true);
        return label;
    }

    /** Changes or Blame: pressed again while on, it stays on, so one of the two always is. */
    private static final class ModeButton extends ToggleButton {
        ModeButton(String text) {
            super(text);
        }

        @Override
        public void fire() {
            if (!isSelected()) {
                super.fire();
            }
        }
    }

    private final class CommitCell extends ListCell<LineHistory.Commit> {
        private final Label subject = new Label();
        private final Label detail = new Label();
        private final VBox box = UiZoom.spacing(new VBox(subject, detail), 1);

        CommitCell() {
            subject.getStyleClass().add("project-map-history-subject");
            detail.getStyleClass().add("project-map-history-detail");
            subject.setMnemonicParsing(false);
            detail.setMnemonicParsing(false);
            subject.setMinWidth(0);
            detail.setMinWidth(0);
            setOnMouseClicked(event -> {
                if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2 && getItem() != null) {
                    onOpenCommit.accept(getItem());
                }
            });
        }

        @Override
        protected void updateItem(LineHistory.@Nullable Commit item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                setText(null);
                setAccessibleText(null);
                return;
            }
            long now = System.currentTimeMillis() / 1000;
            subject.setText(item.subject());
            detail.setText(commitDetail(item, now));
            setText(null);
            setGraphic(box);
            setAccessibleText(item.subject() + ", " + detail.getText());
            setTooltip(ControlNames.tooltip(item.hash() + "\n" + item.path()));
        }
    }

    private static final class DiffCell extends ListCell<String> {
        private static final List<String> STYLES =
                List.of("project-map-history-hunk", "project-map-history-added", "project-map-history-removed");

        @Override
        protected void updateItem(@Nullable String item, boolean empty) {
            super.updateItem(item, empty);
            getStyleClass().removeAll(STYLES);
            if (empty || item == null) {
                setText(null);
                return;
            }
            setText(item);
            String style = diffStyle(item);
            if (style != null) {
                getStyleClass().add(style);
            }
        }
    }

    private final class BlameCell extends ListCell<BlameRow> {
        private final Label by = new Label();
        private final Label code = new Label();
        private final HBox box = UiZoom.spacing(new HBox(by, code), 8);

        BlameCell() {
            by.getStyleClass().add("project-map-history-by");
            code.getStyleClass().add("project-map-history-line");
            // One width for every row's annotation, so the code starts in one column.
            by.setMinWidth(Region.USE_PREF_SIZE);
            UiZoom.prefWidth(by, 170);
            UiZoom.maxWidth(by, 170);
            code.setMinWidth(0);
            box.setAlignment(Pos.CENTER_LEFT);
            setOnMouseClicked(event -> {
                if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2) {
                    openBlamed(getItem());
                }
            });
        }

        @Override
        protected void updateItem(@Nullable BlameRow item, boolean empty) {
            super.updateItem(item, empty);
            getStyleClass().remove("project-map-history-picked");
            if (empty || item == null) {
                setGraphic(null);
                setText(null);
                setAccessibleText(null);
                return;
            }
            long now = System.currentTimeMillis() / 1000;
            by.setText(blameAnnotation(item.blame(), now));
            code.setText(item.text());
            LineHistory.Commit picked = pickedCommit();
            BlameParser.BlameLine line = item.blame();
            if (picked != null && line != null && picked.hash().equals(line.hash())) {
                getStyleClass().add("project-map-history-picked");
            }
            setText(null);
            setGraphic(box);
            setAccessibleText(blameLabel(item, now));
            setTooltip(line == null || line.uncommitted() ? null : ControlNames.tooltip(line.summary()));
        }
    }
}
