package com.editora.ui;

import static com.editora.i18n.Messages.tr;

import java.util.List;
import java.util.function.Consumer;

import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import com.editora.dap.DapManager;
import com.editora.dap.DapModels;

/**
 * The "Debug" tool window: a control toolbar (resume / step over / into / out / stop / restart), the
 * suspended thread's call stack, a lazily-expanding variables tree (scopes → variables), and a console
 * that streams the debuggee's output and doubles as an evaluate REPL. A {@link ToolWindowContent} modeled
 * on {@link RunPanel}; the controller drives it on the FX thread and handles actions via {@link Actions}.
 */
public final class DebugPanel extends VBox implements ToolWindowContent {

    private static final int MAX_CONSOLE_CHARS = 200_000;

    /** Callbacks into the controller / {@link DapManager}. */
    public interface Actions {
        /** Start a debug session when idle, or continue when suspended (the green play button). */
        void start();

        void stepOver();

        void stepInto();

        void stepOut();

        void stop();

        void restart();

        /** A call-stack frame was selected: jump the editor and load its variables. */
        void selectFrame(DapModels.StackFrameInfo frame);

        /** Lazily fetch the children of a variables reference (scope or expandable variable). */
        void loadChildren(int variablesReference, Consumer<List<DapModels.VariableInfo>> cb);

        /** Evaluate {@code expression} in the selected frame ({@code frameId}) and deliver the result. */
        void evaluate(String expression, int frameId, Consumer<String> cb);
    }

    private final Actions actions;
    private final Label status = new Label();
    private final Button start = new Button();
    private final Button stepOver = new Button();
    private final Button stepInto = new Button();
    private final Button stepOut = new Button();
    private final Button stop = new Button();
    private final Button restart = new Button();
    private final ListView<DapModels.StackFrameInfo> stack = new ListView<>();
    private final TreeView<VarRow> variables = new TreeView<>();
    private final TextArea console = new TextArea();
    private final TextField evalInput = new TextField();

    private int selectedFrameId = -1;

    /** A variables-tree row: either a scope or a variable; {@code ref > 0} means it can be expanded. */
    private record VarRow(String label, int ref) {
        @Override public String toString() {
            return label;
        }
    }

    public DebugPanel(Actions actions) {
        this.actions = actions;
        getStyleClass().add("debug-panel");
        getProperties().put("editora.ownsKeys", Boolean.TRUE);
        setSpacing(6);
        setPadding(new Insets(6));

        status.getStyleClass().add("debug-status");
        HBox toolbar = new HBox(6, status, spacer(),
                btn(start, "debug.start", actions::start, Icons.run()),
                btn(stepOver, "debug.stepOver", actions::stepOver, Icons.debugStepOver()),
                btn(stepInto, "debug.stepInto", actions::stepInto, Icons.debugStepInto()),
                btn(stepOut, "debug.stepOut", actions::stepOut, Icons.debugStepOut()),
                btn(stop, "debug.stop", actions::stop, Icons.debugStop()),
                btn(restart, "debug.restart", actions::restart, Icons.refresh()));
        start.getStyleClass().add("debug-start"); // green play accent
        toolbar.setAlignment(Pos.CENTER_LEFT);

        // Call stack: name + file:line
        stack.setCellFactory(v -> new ListCell<>() {
            @Override protected void updateItem(DapModels.StackFrameInfo f, boolean empty) {
                super.updateItem(f, empty);
                if (empty || f == null) {
                    setText(null);
                } else {
                    String loc = f.file() == null ? "" : "  " + f.file().getFileName() + ":" + (f.line() + 1);
                    setText(f.name() + loc);
                }
            }
        });
        stack.getSelectionModel().selectedItemProperty().addListener((o, a, f) -> {
            if (f != null) {
                selectedFrameId = f.id();
                actions.selectFrame(f);
            }
        });

        variables.setShowRoot(false);
        variables.setRoot(new TreeItem<>());

        console.setEditable(false);
        console.setWrapText(false);
        console.getStyleClass().add("debug-console");
        evalInput.setPromptText(tr("debugpanel.evalPrompt"));
        evalInput.setOnAction(e -> runEval());

        Label stackHeader = sectionLabel("debugpanel.callStack");
        VBox stackBox = new VBox(2, stackHeader, stack);
        VBox.setVgrow(stack, Priority.ALWAYS);
        Label varsHeader = sectionLabel("debugpanel.variables");
        VBox varsBox = new VBox(2, varsHeader, variables);
        VBox.setVgrow(variables, Priority.ALWAYS);
        SplitPane top = new SplitPane(stackBox, varsBox);
        top.setOrientation(Orientation.HORIZONTAL);
        top.setDividerPositions(0.42);

        VBox consoleBox = new VBox(2, sectionLabel("debugpanel.console"), console, evalInput);
        VBox.setVgrow(console, Priority.ALWAYS);
        SplitPane main = new SplitPane(top, consoleBox);
        main.setOrientation(Orientation.VERTICAL);
        main.setDividerPositions(0.6);
        VBox.setVgrow(main, Priority.ALWAYS);

        getChildren().addAll(toolbar, main);
        setState(DapManager.State.INACTIVE);
    }

    private Label sectionLabel(String key) {
        Label l = new Label(tr(key));
        l.getStyleClass().add("debug-section");
        return l;
    }

    private Button btn(Button b, String key, Runnable action, javafx.scene.Node icon) {
        String title = tr("command." + key);
        b.setGraphic(icon);
        // Short label on the button (drop the "Debug: " / "Débogage : " prefix); full title in the tooltip.
        b.setText(title.replaceFirst("^[^:]*:\\s*", ""));
        b.setTooltip(new javafx.scene.control.Tooltip(title));
        b.getStyleClass().add("flat");
        b.setFocusTraversable(false);
        b.setOnAction(e -> action.run());
        return b;
    }

    private static Region spacer() {
        Region r = new Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        return r;
    }

    // --- Driven by the controller (FX thread) ---------------------------------------------------

    /** Updates the toolbar/status to reflect the session state and enables the right controls. */
    public void setState(DapManager.State state) {
        boolean suspended = state == DapManager.State.SUSPENDED;
        boolean active = state != DapManager.State.INACTIVE;
        // Green play = Start when idle, Continue when paused; disabled while running/starting.
        start.setDisable(!(state == DapManager.State.INACTIVE || suspended));
        stepOver.setDisable(!suspended);
        stepInto.setDisable(!suspended);
        stepOut.setDisable(!suspended);
        stop.setDisable(!active);
        restart.setDisable(!active);
        evalInput.setDisable(!suspended);
        status.setText(tr("debugpanel.state." + state.name().toLowerCase(java.util.Locale.ROOT)));
        if (!active) {
            stack.getItems().clear();
            variables.setRoot(new TreeItem<>());
            selectedFrameId = -1;
        }
    }

    /** Shows the suspended thread's call stack and selects the top frame. */
    public void setCallStack(List<DapModels.StackFrameInfo> frames) {
        stack.getItems().setAll(frames);
        if (!frames.isEmpty()) {
            stack.getSelectionModel().select(0);
        }
    }

    /** Replaces the variables tree with the selected frame's scopes (each lazily expandable). */
    public void setScopes(List<DapModels.ScopeInfo> scopes) {
        TreeItem<VarRow> root = new TreeItem<>();
        for (DapModels.ScopeInfo s : scopes) {
            TreeItem<VarRow> item = lazyItem(new VarRow(s.name(), s.variablesReference()));
            item.setExpanded(!s.expensive());
            root.getChildren().add(item);
        }
        variables.setRoot(root);
    }

    /** Appends program/console output (trimmed to a cap), auto-scrolling to the bottom. */
    public void appendOutput(String text, String category) {
        if (text == null) {
            return;
        }
        console.appendText(text);
        int len = console.getLength();
        if (len > MAX_CONSOLE_CHARS) {
            console.deleteText(0, len - MAX_CONSOLE_CHARS);
        }
        console.positionCaret(console.getLength());
    }

    private void runEval() {
        String expr = evalInput.getText();
        if (expr == null || expr.isBlank()) {
            return;
        }
        appendOutput("> " + expr + "\n", "console");
        evalInput.clear();
        actions.evaluate(expr, selectedFrameId,
                result -> appendOutput((result == null ? "" : result) + "\n", "console"));
    }

    /** A tree item whose children are loaded on first expand (when {@code ref > 0}). */
    private TreeItem<VarRow> lazyItem(VarRow row) {
        TreeItem<VarRow> item = new TreeItem<>(row) {
            @Override public boolean isLeaf() {
                return row.ref() <= 0;
            }
        };
        if (row.ref() > 0) {
            boolean[] loaded = {false};
            item.expandedProperty().addListener((o, was, now) -> {
                if (now && !loaded[0]) {
                    loaded[0] = true;
                    actions.loadChildren(row.ref(), vars -> {
                        item.getChildren().clear();
                        for (DapModels.VariableInfo v : vars) {
                            String label = v.name() + " = " + v.value()
                                    + (v.type() == null || v.type().isBlank() ? "" : "  (" + v.type() + ")");
                            item.getChildren().add(lazyItem(new VarRow(label, v.variablesReference())));
                        }
                    });
                }
            });
        }
        return item;
    }

    @Override
    public void focusFirstItem() {
        stack.requestFocus();
    }
}
