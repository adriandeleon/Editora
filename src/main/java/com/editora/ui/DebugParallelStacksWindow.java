package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Line;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import com.editora.dap.DapModels;
import com.editora.editor.ControlNames;
import com.editora.editor.UiZoom;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * The Parallel Stacks window (Visual Studio's): every thread's call stack at one moment as one tree of boxes
 * ({@link ParallelStacks}), the outermost calls at the bottom, each box naming its threads (all of them on hover) and
 * listing the frames they share. The boxes the shown thread passes through are drawn as such, and its frame in each.
 * A frame opens its code (click, or Enter on it). Copy puts the tree on the clipboard as text, and Take Again takes
 * the stacks again — pausing a running program for a moment, as Thread Dump does.
 */
final class DebugParallelStacksWindow {

    /** The window's side of the debugger. */
    interface Actions {
        /** Opens a frame's code: its file at its line, a library's source, or the debugger's own copy of it. */
        void openFrame(DapModels.StackFrameInfo frame);

        /** Takes the stacks again, which this window then shows; a no-op without a session. */
        void takeAgain();

        /** Whether a session is there to take the stacks of. */
        boolean canTakeAgain();
    }

    static final double WIDTH = 900;
    static final double HEIGHT = 600;

    /** A box's width, its heading's height, a frame row's height and the space between boxes, at 100 % zoom. */
    static final double BOX_WIDTH = 250;

    static final double HEADER = 26;
    static final double ROW = 20;
    static final double GAP = 28;

    private final Actions actions;
    private final Stage stage = new Stage();
    private final Label heading = new Label();
    private final Label note = new Label();
    private final Pane canvas = new Pane();
    private final ScrollPane scroll = new ScrollPane(canvas);
    private final Button takeAgain = new Button(tr("threaddump.takeAgain"));
    private @Nullable ParallelStacks stacks;

    DebugParallelStacksWindow(Actions actions) {
        this.actions = actions;
        build();
    }

    private void build() {
        stage.setTitle(tr("parallelstacks.title"));
        stage.initModality(Modality.NONE);
        heading.getStyleClass().add("debug-section");
        note.getStyleClass().add("thread-dump-note");
        note.setWrapText(true);
        note.managedProperty().bind(note.visibleProperty());

        canvas.getStyleClass().add("parallel-stacks-canvas");
        scroll.getStyleClass().add("parallel-stacks-scroll");
        scroll.setPannable(true);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        Button copy = new Button(tr("threaddump.copy"));
        copy.setGraphic(Icons.copy());
        copy.setOnAction(e -> {
            ParallelStacks s = stacks;
            if (s != null) {
                DebugPanel.copyToClipboard(s.text());
            }
        });
        takeAgain.setOnAction(e -> actions.takeAgain());
        Button close = new Button(tr("threaddump.close"));
        close.setCancelButton(true);
        close.setOnAction(e -> stage.hide());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = UiZoom.spacing(new HBox(copy, takeAgain, spacer, close), 8);
        buttons.setAlignment(Pos.CENTER_LEFT);

        VBox root = UiZoom.spacing(new VBox(heading, note, scroll, buttons), 6);
        UiZoom.padding(root, 10);
        root.getStyleClass().addAll("debug-panel", "parallel-stacks-window");
        Scene scene = new Scene(root, UiZoom.px(WIDTH), UiZoom.px(HEIGHT));
        scene.addEventHandler(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ESCAPE && !e.isConsumed()) {
                stage.hide();
                e.consume();
            }
        });
        stage.setScene(scene);
        UiZoom.follow(stage, () -> {
            stage.setMinWidth(UiZoom.px(480));
            stage.setMinHeight(UiZoom.px(320));
            ParallelStacks s = stacks;
            if (s != null) {
                draw(s);
            }
        });
    }

    /** Shows {@code stacks} over {@code owner}, scrolled to the bottom, where the threads start. */
    void show(Window owner, ParallelStacks stacks) {
        if (stage.getOwner() == null && owner != null) {
            stage.initOwner(owner);
            mirrorStylesheets(owner);
        }
        this.stacks = stacks;
        ThreadDump dump = stacks.dump();
        heading.setText(tr("parallelstacks.heading", dump.title()));
        long without = stacks.withoutStack();
        String noteText = without > 0
                ? tr("parallelstacks.withoutStack", without)
                : dump.pausedForIt() ? tr("threaddump.pausedForIt") : "";
        note.setText(noteText);
        note.setVisible(!noteText.isEmpty());
        takeAgain.setDisable(!actions.canTakeAgain());
        draw(stacks);
        if (stage.isShowing()) {
            stage.toFront();
        } else {
            WindowPlacement.centerOnOwner(stage, owner, UiZoom.px(WIDTH), UiZoom.px(HEIGHT));
            stage.show();
        }
        stage.requestFocus();
        scroll.setVvalue(1); // the roots
        scroll.setHvalue(0);
        Node first = canvas.lookup(".parallel-stacks-frame");
        if (first != null) {
            first.requestFocus();
        }
    }

    /** The session ended (or another started): there is nothing to take the stacks of. */
    void sessionChanged() {
        takeAgain.setDisable(!actions.canTakeAgain());
    }

    boolean isShowing() {
        return stage.isShowing();
    }

    void hide() {
        stage.hide();
    }

    /** Draws the boxes where the layout puts them, and a line from each box down to the box below it. */
    private void draw(ParallelStacks s) {
        Map<ParallelStacks.Box, ParallelStacks.Place> places =
                s.layout(UiZoom.px(BOX_WIDTH), UiZoom.px(HEADER), UiZoom.px(ROW), UiZoom.px(GAP), UiZoom.px(12));
        List<Node> lines = new ArrayList<>();
        List<Node> boxes = new ArrayList<>();
        for (ParallelStacks.Box b : s.boxes()) {
            ParallelStacks.Place p = places.get(b);
            if (p == null) {
                continue; // every box is laid out: no box is left without a place
            }
            for (ParallelStacks.Box a : b.above()) {
                ParallelStacks.Place q = places.get(a);
                if (q == null) {
                    continue;
                }
                Line line = new Line(p.centerX(), p.y(), q.centerX(), q.bottom());
                line.getStyleClass().add("parallel-stacks-edge");
                if (b.hasShownThread() && a.hasShownThread()) {
                    line.getStyleClass().add("parallel-stacks-shown");
                }
                lines.add(line);
            }
            boxes.add(box(b, p));
        }
        canvas.getChildren().setAll(lines);
        canvas.getChildren().addAll(boxes);
        double right = places.values().stream()
                .mapToDouble(p -> p.x() + p.width())
                .max()
                .orElse(0);
        double bottom = places.values().stream()
                .mapToDouble(ParallelStacks.Place::bottom)
                .max()
                .orElse(0);
        canvas.setPrefSize(right + UiZoom.px(12), bottom + UiZoom.px(12));
        canvas.setMinSize(right + UiZoom.px(12), bottom + UiZoom.px(12));
    }

    private VBox box(ParallelStacks.Box b, ParallelStacks.Place p) {
        Label head = new Label(ParallelStacks.heading(b));
        head.getStyleClass().add("parallel-stacks-heading");
        head.setMaxWidth(Double.MAX_VALUE);
        head.setMinHeight(UiZoom.px(HEADER));
        head.setPrefHeight(UiZoom.px(HEADER));
        StringBuilder names = new StringBuilder();
        for (DapModels.ThreadStack t : b.threads()) {
            names.append(names.isEmpty() ? "" : "\n").append(ThreadDump.name(t));
        }
        head.setTooltip(ControlNames.tooltip(names.toString()));
        VBox box = new VBox(head);
        box.getStyleClass().add("parallel-stacks-box");
        if (b.hasShownThread()) {
            box.getStyleClass().add("parallel-stacks-shown");
        }
        // The shown thread's own top frame: in the last box on its way up.
        boolean shownEnds = b.hasShownThread() && b.above().stream().noneMatch(ParallelStacks.Box::hasShownThread);
        for (int i = b.frames().size() - 1; i >= 0; i--) { // innermost on top, as the stack grows up
            box.getChildren()
                    .add(frameRow(
                            b.frames().get(i), shownEnds && i == b.frames().size() - 1));
        }
        box.setLayoutX(p.x());
        box.setLayoutY(p.y());
        box.setPrefSize(p.width(), p.height());
        box.setMinSize(p.width(), p.height());
        box.setMaxSize(p.width(), p.height());
        return box;
    }

    private Label frameRow(ParallelStacks.Frame f, boolean shownTop) {
        Label row = new Label(f.text());
        row.getStyleClass().add("parallel-stacks-frame");
        if (f.shownThreadsFrame() != null && shownTop) {
            row.getStyleClass().add("parallel-stacks-current");
        }
        row.setMaxWidth(Double.MAX_VALUE);
        row.setPrefHeight(UiZoom.px(ROW));
        row.setFocusTraversable(true);
        row.setAccessibleText(f.text());
        row.setTooltip(ControlNames.tooltip(DebugPanelText.frameText(f.opened())));
        row.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY) {
                row.requestFocus();
                actions.openFrame(f.opened());
            }
        });
        row.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER || e.getCode() == KeyCode.SPACE) {
                actions.openFrame(f.opened());
                e.consume();
            }
        });
        return row;
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

    Pane canvas() {
        return canvas;
    }

    Label note() {
        return note;
    }

    Label heading() {
        return heading;
    }

    Button takeAgainButton() {
        return takeAgain;
    }

    @Nullable
    ParallelStacks stacks() {
        return stacks;
    }
}
