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
 *
 * <p>A graph laid out in two directions is not a list a virtualized control can show, and every frame of it is a Tab
 * stop, so the window draws a named number of boxes and of frames in a box instead (PERF-08, Pauta 6.0.1): the first
 * {@link #MAX_BOXES} boxes, the roots first and each box after the one below it, so what is drawn stays one tree; and
 * in a box its innermost {@link #MAX_BOX_FRAMES} frames, with a row that says how many more there are. The note says
 * how many boxes were left out, and Copy has the whole tree.
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

    /** The most boxes the window draws; the note says how many there are past them. */
    static final int MAX_BOXES = 100;

    /** The most frames a box lists; a row under them says how many more it has. */
    static final int MAX_BOX_FRAMES = 25;

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
        FocusCommands.press(root, "debug.copyDump", copy); // its Copy's command, while it has the keyboard
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
        int boxCount = stacks.boxes().size();
        if (boxCount > MAX_BOXES) {
            String capped = tr("parallelstacks.capped", MAX_BOXES, boxCount);
            noteText = noteText.isEmpty() ? capped : noteText + " " + capped;
        }
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
        Map<ParallelStacks.Box, ParallelStacks.Place> places = s.layout(
                UiZoom.px(BOX_WIDTH), UiZoom.px(HEADER), UiZoom.px(ROW), UiZoom.px(GAP), UiZoom.px(12), MAX_BOX_FRAMES);
        List<ParallelStacks.Box> all = s.boxes();
        // The roots first and every box after the one below it: the first MAX_BOXES are one tree.
        java.util.Set<ParallelStacks.Box> drawn =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        drawn.addAll(all.subList(0, Math.min(all.size(), MAX_BOXES)));
        List<Node> lines = new ArrayList<>();
        List<Node> boxes = new ArrayList<>();
        for (ParallelStacks.Box b : all) {
            ParallelStacks.Place p = places.get(b);
            if (p == null || !drawn.contains(b)) {
                continue; // every box is laid out: no box is left without a place
            }
            for (ParallelStacks.Box a : b.above()) {
                ParallelStacks.Place q = places.get(a);
                if (q == null || !drawn.contains(a)) {
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
        List<ParallelStacks.Place> drawnPlaces = drawn.stream()
                .map(places::get)
                .filter(java.util.Objects::nonNull)
                .toList();
        double right =
                drawnPlaces.stream().mapToDouble(p -> p.x() + p.width()).max().orElse(0);
        double bottom = drawnPlaces.stream()
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
        int frames = b.frames().size();
        int lowest = Math.max(0, frames - MAX_BOX_FRAMES);
        for (int i = frames - 1; i >= lowest; i--) { // innermost on top, as the stack grows up
            box.getChildren().add(frameRow(b.frames().get(i), shownEnds && i == frames - 1));
        }
        if (lowest > 0) {
            Label more = new Label(tr("parallelstacks.moreFrames", lowest));
            more.getStyleClass().addAll("parallel-stacks-frame-more", "thread-dump-note");
            more.setMaxWidth(Double.MAX_VALUE);
            more.setPrefHeight(UiZoom.px(ROW));
            box.getChildren().add(more);
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
