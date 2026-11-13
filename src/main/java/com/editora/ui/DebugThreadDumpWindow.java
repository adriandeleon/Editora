package com.editora.ui;

import java.util.List;

import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import com.editora.dap.DapModels;
import com.editora.editor.ControlNames;
import com.editora.editor.UiZoom;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * The Thread Dump window (IntelliJ's Get Thread Dump): every thread of the program at one moment, each with its call
 * stack, in a window of its own beside the editor. On the left the threads — those whose stacks are the same merged
 * into one row (Merge Identical Stacks), with what each was doing — and on the right the selected one's stack, whose
 * frames open their code (double-click or Enter). A filter keeps the threads whose name or a frame matches. Copy puts
 * the dump on the clipboard as text, Open as Text in an editor tab, and Take Again takes a new one.
 *
 * <p>The dump is a snapshot: its frames are the stop's, and still open their files after the program went on.
 */
final class DebugThreadDumpWindow {

    /** The window's side of the debugger. */
    interface Actions {
        /** Opens a frame's code: its file at its line, a library's source, or the debugger's own copy of it. */
        void openFrame(DapModels.StackFrameInfo frame);

        /** Opens {@code text} in a read-only editor tab named {@code title}. */
        void openText(String title, String text);

        /** Takes a new dump, which this window then shows; a no-op without a session. */
        void takeAgain();

        /** Whether a session is there to take a new dump of. */
        boolean canTakeAgain();
    }

    static final double WIDTH = 860;
    static final double HEIGHT = 520;

    private final Actions actions;
    private final Stage stage = new Stage();
    private final Label heading = new Label();
    private final Label note = new Label();
    private final TextField filter = new TextField();
    private final CheckBox merge = new CheckBox(tr("threaddump.merge"));
    private final ListView<ThreadDump.Group> threadList = new ListView<>();
    private final ListView<DapModels.StackFrameInfo> frameList = new ListView<>();
    private final Button takeAgain = new Button(tr("threaddump.takeAgain"));
    private @Nullable ThreadDump dump;

    DebugThreadDumpWindow(Actions actions) {
        this.actions = actions;
        build();
    }

    private void build() {
        stage.setTitle(tr("threaddump.title"));
        stage.initModality(Modality.NONE);

        heading.getStyleClass().add("debug-section");
        note.getStyleClass().add("thread-dump-note");
        note.setWrapText(true);
        note.managedProperty().bind(note.visibleProperty());

        filter.setPromptText(tr("threaddump.filter"));
        filter.setAccessibleText(tr("threaddump.filter"));
        filter.textProperty().addListener((o, was, now) -> fill());
        filter.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.DOWN) {
                threadList.requestFocus();
                e.consume();
            }
        });
        HBox.setHgrow(filter, Priority.ALWAYS);
        merge.setSelected(true);
        merge.selectedProperty().addListener((o, was, now) -> fill());
        HBox top = UiZoom.spacing(new HBox(filter, merge), 8);
        top.setAlignment(Pos.CENTER_LEFT);

        threadList.getStyleClass().add("thread-dump-threads");
        threadList.setAccessibleText(tr("threaddump.threads"));
        threadList.setPlaceholder(new Label(tr("threaddump.noThreads")));
        threadList.setCellFactory(v -> new ThreadCell());
        threadList.getSelectionModel().selectedItemProperty().addListener((o, was, now) -> showFrames(now));

        frameList.getStyleClass().add("thread-dump-frames");
        frameList.setAccessibleText(tr("threaddump.frames"));
        frameList.setPlaceholder(new Label(tr("threaddump.noStack")));
        frameList.setCellFactory(v -> new FrameCell());
        frameList.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                openSelectedFrame();
            }
        });
        frameList.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) {
                openSelectedFrame();
                e.consume();
            } else if (e.getCode() == KeyCode.C && e.isShortcutDown()) {
                copySelectedStack();
                e.consume();
            }
        });
        threadList.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER || e.getCode() == KeyCode.RIGHT) {
                frameList.requestFocus();
                if (frameList.getSelectionModel().isEmpty()
                        && !frameList.getItems().isEmpty()) {
                    frameList.getSelectionModel().selectFirst();
                }
                e.consume();
            } else if (e.getCode() == KeyCode.C && e.isShortcutDown()) {
                copySelectedStack();
                e.consume();
            }
        });

        SplitPane split = new SplitPane(threadList, frameList);
        split.setOrientation(Orientation.HORIZONTAL);
        split.setDividerPositions(0.38);
        VBox.setVgrow(split, Priority.ALWAYS);

        Button copy = new Button(tr("threaddump.copy"));
        copy.setGraphic(Icons.copy());
        copy.setOnAction(e -> {
            ThreadDump d = dump;
            if (d != null) {
                DebugPanel.copyToClipboard(d.text(merge.isSelected()));
            }
        });
        Button asText = new Button(tr("threaddump.openAsText"));
        asText.setOnAction(e -> {
            ThreadDump d = dump;
            if (d != null) {
                actions.openText(d.title(), d.text(merge.isSelected()));
            }
        });
        takeAgain.setOnAction(e -> actions.takeAgain());
        Button close = new Button(tr("threaddump.close"));
        close.setCancelButton(true);
        close.setOnAction(e -> stage.hide());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = UiZoom.spacing(new HBox(copy, asText, takeAgain, spacer, close), 8);
        buttons.setAlignment(Pos.CENTER_LEFT);

        VBox root = UiZoom.spacing(new VBox(heading, note, top, split, buttons), 6);
        UiZoom.padding(root, 10);
        root.getStyleClass().addAll("debug-panel", "thread-dump-window");
        Scene scene = new Scene(root, UiZoom.px(WIDTH), UiZoom.px(HEIGHT));
        scene.addEventHandler(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ESCAPE && !e.isConsumed()) {
                if (!filter.getText().isEmpty() && filter.isFocused()) {
                    filter.clear();
                } else {
                    stage.hide();
                }
                e.consume();
            }
        });
        stage.setScene(scene);
        UiZoom.follow(stage, () -> {
            stage.setMinWidth(UiZoom.px(480));
            stage.setMinHeight(UiZoom.px(300));
        });
    }

    /** Shows {@code dump} over {@code owner}: its threads listed, the first one's stack open. */
    void show(Window owner, ThreadDump dump) {
        if (stage.getOwner() == null && owner != null) {
            stage.initOwner(owner);
            mirrorStylesheets(owner);
        }
        this.dump = dump;
        heading.setText(dump.title());
        long running = dump.runningWithoutStack();
        String noteText = dump.pausedForIt()
                ? tr("threaddump.pausedForIt")
                : running > 0 ? tr("threaddump.runningHint", running) : "";
        note.setText(noteText);
        note.setVisible(!noteText.isEmpty());
        takeAgain.setDisable(!actions.canTakeAgain());
        fill();
        threadList.getSelectionModel().selectFirst();
        if (stage.isShowing()) {
            stage.toFront();
        } else {
            WindowPlacement.centerOnOwner(stage, owner, UiZoom.px(WIDTH), UiZoom.px(HEIGHT));
            stage.show();
        }
        stage.requestFocus();
        threadList.requestFocus();
    }

    /** The session ended (or another started): there is nothing to take a new dump of. */
    void sessionChanged() {
        takeAgain.setDisable(!actions.canTakeAgain());
    }

    boolean isShowing() {
        return stage.isShowing();
    }

    void hide() {
        stage.hide();
    }

    /** Lists the rows that pass the filter, keeping the selected thread selected when it still does. */
    private void fill() {
        ThreadDump d = dump;
        if (d == null) {
            return;
        }
        ThreadDump.Group selected = threadList.getSelectionModel().getSelectedItem();
        List<ThreadDump.Group> rows = d.groups(merge.isSelected()).stream()
                .filter(g -> ThreadDump.matches(g, filter.getText()))
                .toList();
        threadList.getItems().setAll(rows);
        if (selected != null) {
            for (ThreadDump.Group g : rows) {
                if (g.threads().contains(selected.first())) {
                    threadList.getSelectionModel().select(g);
                    return;
                }
            }
        }
        threadList.getSelectionModel().selectFirst();
        if (rows.isEmpty()) {
            showFrames(null);
        }
    }

    private void showFrames(ThreadDump.@Nullable Group group) {
        frameList.getItems().setAll(group == null ? List.of() : group.frames());
    }

    private void openSelectedFrame() {
        DapModels.StackFrameInfo frame = frameList.getSelectionModel().getSelectedItem();
        if (frame != null) {
            actions.openFrame(frame);
        }
    }

    /** Copies the selected row's stack, as Copy Call Stack copies the Debug window's. */
    private void copySelectedStack() {
        ThreadDump d = dump;
        ThreadDump.Group g = threadList.getSelectionModel().getSelectedItem();
        if (d == null || g == null) {
            return;
        }
        StringBuilder out = new StringBuilder(d.rowHeading(g)).append('\n');
        for (DapModels.StackFrameInfo f : g.frames()) {
            out.append("    at ").append(DebugPanelText.frameText(f)).append('\n');
        }
        DebugPanel.copyToClipboard(out.toString());
    }

    /** A thread row: its name (merged: the names and how many), muted what it was doing and its depth. */
    private final class ThreadCell extends ListCell<ThreadDump.Group> {
        @Override
        protected void updateItem(ThreadDump.Group g, boolean empty) {
            super.updateItem(g, empty);
            ThreadDump d = dump;
            if (empty || g == null || d == null) {
                setText(null);
                setGraphic(null);
                setTooltip(null);
                return;
            }
            setText(null);
            Label name = new Label(
                    g.threads().size() == 1
                            ? ThreadDump.name(g.first())
                            : tr(
                                    "threaddump.merged",
                                    ThreadDump.name(g.first()),
                                    g.threads().size() - 1));
            Label state = new Label(d.stateText(g.first())
                    + (g.frames().isEmpty()
                            ? ""
                            : " · " + tr("threaddump.depth", g.frames().size())));
            state.getStyleClass().add("thread-dump-state");
            HBox row = UiZoom.spacing(new HBox(name, state), 8);
            row.setAlignment(Pos.CENTER_LEFT);
            setGraphic(row);
            if (g.threads().size() > 1) {
                StringBuilder names = new StringBuilder();
                for (DapModels.ThreadStack t : g.threads()) {
                    names.append(names.isEmpty() ? "" : "\n").append(ThreadDump.name(t));
                }
                setTooltip(ControlNames.tooltip(names.toString()));
            } else {
                setTooltip(null);
            }
        }
    }

    /** A frame row: as a stack trace line names it, muted when there is no code to open. */
    private static final class FrameCell extends ListCell<DapModels.StackFrameInfo> {
        @Override
        protected void updateItem(DapModels.StackFrameInfo f, boolean empty) {
            super.updateItem(f, empty);
            getStyleClass().remove("thread-dump-no-source");
            if (empty || f == null) {
                setText(null);
                return;
            }
            setText(DebugPanelText.frameText(f));
            if (f.file() == null && f.sourceUri() == null && f.reference() == null) {
                getStyleClass().add("thread-dump-no-source");
            }
        }
    }

    /** A fresh Scene has no stylesheets: the owner's are mirrored, so a theme switch reaches the window too. */
    private void mirrorStylesheets(Window owner) {
        if (owner.getScene() == null) {
            return;
        }
        ObservableList<String> sheets = owner.getScene().getStylesheets();
        stage.getScene().getStylesheets().setAll(sheets);
        sheets.addListener((ListChangeListener<String>)
                change -> stage.getScene().getStylesheets().setAll(sheets));
    }

    // --- test reads ----------------------------------------------------------------------------------------

    ListView<ThreadDump.Group> threadList() {
        return threadList;
    }

    ListView<DapModels.StackFrameInfo> frameList() {
        return frameList;
    }

    TextField filter() {
        return filter;
    }

    CheckBox merge() {
        return merge;
    }

    Label note() {
        return note;
    }

    Button takeAgainButton() {
        return takeAgain;
    }

    Stage stage() {
        return stage;
    }

    @Nullable
    ThreadDump dump() {
        return dump;
    }
}
