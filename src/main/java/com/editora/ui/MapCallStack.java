package com.editora.ui;

import java.util.List;
import java.util.function.Consumer;

import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;

import com.editora.config.PathDisplay;
import com.editora.dap.DapModels;
import com.editora.editor.UiZoom;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * The call stack beside the Project Map's stop card (Map gaps round 3, item 2): the paused thread's frames, top first,
 * the one shown marked. Picking a frame — a click, or Enter — shows it in the debugger, as picking it in the Debug
 * window does, and the stop card follows. A frame the debugger has no file for is listed greyed; a row that only labels
 * the stack (an {@code await}) cannot be picked.
 */
final class MapCallStack extends MapSideColumn {

    private static final PseudoClass SHOWN = PseudoClass.getPseudoClass("shown");
    private static final PseudoClass NO_SOURCE = PseudoClass.getPseudoClass("no-source");

    private final ListView<DapModels.StackFrameInfo> frames = new ListView<>();
    private final Consumer<DapModels.StackFrameInfo> onPick;
    private int shownId = Integer.MIN_VALUE;

    MapCallStack(
            ProjectMapPreview card,
            javafx.scene.layout.Pane host,
            Consumer<DapModels.StackFrameInfo> onPick,
            Runnable onClose,
            Runnable onActivate,
            Runnable onMoved) {
        super(card, host, "project-map-call-stack", tr("project.map.debug.stackClose"), onClose, onActivate, onMoved);
        this.onPick = onPick;
        frames.getStyleClass().addAll("project-map-card-structure-tree", "project-map-call-stack-list");
        frames.setCellFactory(view -> new FrameCell());
        frames.setAccessibleHelp(tr("project.map.debug.stackHelp"));
        frames.addEventHandler(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ENTER) {
                pick(frames.getSelectionModel().getSelectedItem());
                event.consume();
            }
        });
        // Its right-click menu: what a click and Enter do, and Copy (MENU-05).
        ListRowCommands.install(
                frames,
                ListRowCommands.enabledWhen(
                        ListRowCommands.row(frames, ListRowCommands.OPEN, null, Icons.openInTab(), this::pickSelected),
                        () -> {
                            DapModels.StackFrameInfo frame =
                                    frames.getSelectionModel().getSelectedItem();
                            return frame != null && !onlyLabels(frame);
                        }),
                ListRowCommands.copyRow(frames, () -> ListRowCommands.selectedText(frames, DebugPanelText::frameText)));
        setBody(frames);
        setTitle(tr("project.map.debug.stackTitle"), frames);
    }

    /** Shows the selected frame in the debugger, as a click does; false when none, or one with no place, is. */
    boolean pickSelected() {
        DapModels.StackFrameInfo frame = frames.getSelectionModel().getSelectedItem();
        if (frame == null || onlyLabels(frame)) {
            return false;
        }
        pick(frame);
        return true;
    }

    /** Lists {@code stack}, top first, marking and selecting the frame {@code shown} (its id). */
    void show(List<DapModels.StackFrameInfo> stack, int shown) {
        shownId = shown;
        frames.getItems().setAll(stack);
        frames.getSelectionModel().clearSelection();
        for (int i = 0; i < stack.size(); i++) {
            if (stack.get(i).id() == shown) {
                frames.getSelectionModel().select(i);
                frames.scrollTo(Math.max(0, i - 2));
                break;
            }
        }
        frames.refresh();
        setTitle(tr("project.map.debug.stackTitleCount", stack.size()), frames);
    }

    /** The frames listed. For tests. */
    List<DapModels.StackFrameInfo> frames() {
        return List.copyOf(frames.getItems());
    }

    /** The list. For tests. */
    ListView<DapModels.StackFrameInfo> list() {
        return frames;
    }

    /** Shows {@code frame} in the debugger, unless it only labels the stack. */
    void pick(DapModels.@Nullable StackFrameInfo frame) {
        if (frame != null && !onlyLabels(frame)) {
            onPick.accept(frame);
        }
    }

    /** Whether {@code frame} only labels the stack (an {@code await}) or stands for folded frames: no place. */
    static boolean onlyLabels(DapModels.StackFrameInfo frame) {
        return DapModels.HINT_LABEL.equals(frame.hint()) || DapModels.HINT_FOLD.equals(frame.hint());
    }

    @Override
    protected Node focusTarget() {
        return frames;
    }

    /** A row's second line: the frame's file and 1-based line ("App.java:12"), or what stands for it. */
    static String where(DapModels.StackFrameInfo frame) {
        if (frame.file() != null) {
            return PathDisplay.fileName(frame.file()) + ":" + (frame.line() + 1);
        }
        if (frame.reference() != null) {
            return tr("project.map.debug.stackNoFile");
        }
        return tr("project.map.debug.stackNoSource");
    }

    private final class FrameCell extends ListCell<DapModels.StackFrameInfo> {
        private final Label name = new Label();
        private final Label place = new Label();
        private final Rectangle mark = new Rectangle(3, 14, Color.TRANSPARENT);
        private final HBox row;

        FrameCell() {
            name.getStyleClass().add("project-map-call-stack-name");
            place.getStyleClass().addAll("project-map-card-structure-line", "project-map-call-stack-place");
            name.setMinWidth(0);
            UiZoom.follow(mark, () -> {
                mark.setWidth(UiZoom.px(3));
                mark.setHeight(UiZoom.px(14));
            });
            mark.getStyleClass().add("project-map-call-stack-mark");
            VBox text = UiZoom.spacing(new VBox(name, place), 1);
            text.setMinWidth(0);
            HBox.setHgrow(text, Priority.ALWAYS);
            row = UiZoom.spacing(new HBox(mark, text), 6);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setMinWidth(Region.USE_PREF_SIZE);
            addEventHandler(MouseEvent.MOUSE_CLICKED, event -> {
                if (event.getButton() == MouseButton.PRIMARY && !isEmpty()) {
                    pick(getItem());
                }
            });
        }

        @Override
        protected void updateItem(DapModels.@Nullable StackFrameInfo frame, boolean empty) {
            super.updateItem(frame, empty);
            if (empty || frame == null) {
                setGraphic(null);
                setText(null);
                setAccessibleText(null);
                pseudoClassStateChanged(SHOWN, false);
                pseudoClassStateChanged(NO_SOURCE, false);
                return;
            }
            boolean label = onlyLabels(frame);
            name.setText(frame.name());
            place.setText(label ? "" : where(frame));
            place.setVisible(!label);
            place.setManaged(!label);
            setText(null);
            setGraphic(row);
            boolean shown = frame.id() == shownId;
            pseudoClassStateChanged(SHOWN, shown);
            pseudoClassStateChanged(NO_SOURCE, label || frame.file() == null);
            setAccessibleText(label ? frame.name() : tr("project.map.debug.stackRow", frame.name(), where(frame)));
        }
    }
}
