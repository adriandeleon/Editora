package com.editora.ui;

import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Separator;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.util.StringConverter;

import com.editora.dap.DapManager;
import com.editora.dap.DapModels;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import static com.editora.i18n.Messages.tr;

/**
 * The "Debug" tool window, styled after IntelliJ's debugger: an icon-only grouped control toolbar
 * (resume / pause / stop / restart | step over / into / out / run to cursor), a thread selector over the
 * suspended thread's call stack (rich {@code name:line, File} cells), a lazily-expanding variables tree
 * with type-colored values, and an always-visible console that streams the debuggee's output and doubles
 * as an evaluate REPL. A {@link ToolWindowContent} modeled on {@link RunPanel}; the controller drives it
 * on the FX thread and handles actions via {@link Actions}.
 */
public final class DebugPanel extends VBox implements ToolWindowContent {

    private static final int MAX_CONSOLE_CHARS = 200_000;

    /** Callbacks into the controller / {@link DapManager}. */
    public interface Actions {
        /** Start a debug session when idle, or continue when suspended (the green play button). */
        void start();

        /** Pause a running program (DAP pause). */
        void pause();

        void stepOver();

        void stepInto();

        void stepOut();

        /** Resume and stop at the editor caret's line (temporary breakpoint). */
        void runToCursor();

        void stop();

        void restart();

        /** The user picked another thread in the dropdown: load its call stack. */
        void selectThread(int threadId);

        /** A call-stack frame was selected: jump the editor and load its variables. */
        void selectFrame(DapModels.StackFrameInfo frame);

        /** Lazily fetch the children of a variables reference (scope or expandable variable). */
        void loadChildren(int variablesReference, Consumer<List<DapModels.VariableInfo>> cb);

        /** Evaluate {@code expression} in the selected frame ({@code frameId}) and deliver the result. */
        void evaluate(String expression, int frameId, Consumer<String> cb);

        /** Evaluate a watch expression (context "watch") keeping the expandable reference + type. */
        void evaluateWatch(String expression, int frameId, Consumer<DapModels.EvalResult> cb);

        /** Set a variable's value (DAP setVariable on its container reference); delivers the new value. */
        void setVariable(int parentRef, String name, String value, Consumer<String> cb);
    }

    private final Actions actions;
    private final Label status = new Label();
    private final Button start = new Button();
    private final Button pause = new Button();
    private final Button stop = new Button();
    private final Button restart = new Button();
    private final Button stepOver = new Button();
    private final Button stepInto = new Button();
    private final Button stepOut = new Button();
    private final Button runToCursor = new Button();
    private final ComboBox<DapModels.ThreadInfo> threads = new ComboBox<>();
    private final ListView<DapModels.StackFrameInfo> stack = new ListView<>();
    private final TreeView<VarRow> variables = new TreeView<>();
    private final CodeArea console = new CodeArea();
    private final TextField evalInput = new TextField();

    private int selectedFrameId = -1;
    /** Guard so programmatically selecting the current thread in the combo doesn't re-fetch its stack. */
    private boolean settingThreads;
    /** Watch expressions (the "Watches" node merged into the variables tree, IntelliJ-style). */
    private final java.util.List<String> watches = new java.util.ArrayList<>();

    private Runnable onWatchesChanged = () -> {};
    private OverlayInput.Prompt prompt;
    /** Receives a double-clicked stack-trace location from the console (controller resolves + jumps). */
    private java.util.function.Consumer<com.editora.run.StackTraceLinks.Link> onLink;

    public void setOnLink(java.util.function.Consumer<com.editora.run.StackTraceLinks.Link> onLink) {
        this.onLink = onLink;
    }
    /** File name the current session was started for; shown beside the state so a session left running
     *  on another file is visibly bound to it. Cleared when the session ends. */
    private String sessionFile = "";

    private DapManager.State lastState = DapManager.State.INACTIVE;

    /** Row kinds in the variables tree (watches are merged into it, IntelliJ-style). */
    enum Kind {
        SCOPE,
        VARIABLE,
        WATCH,
        ADD_WATCH
    }

    /** A variables-tree row; {@code ref > 0} means expandable, {@code parentRef} is the DAP container
     *  reference (for set-variable), and {@code kind} drives rendering + context actions. */
    record VarRow(String name, String value, String type, int ref, int parentRef, Kind kind) {}

    public DebugPanel(Actions actions) {
        this.actions = actions;
        getStyleClass().add("debug-panel");
        getProperties().put("editora.ownsKeys", Boolean.TRUE);
        setSpacing(4);
        setPadding(new Insets(4));

        status.getStyleClass().add("debug-status");
        // IntelliJ-style grouped, icon-only toolbar; the session state + file sit at the right edge.
        HBox toolbar = new HBox(
                2,
                btn(start, "debug.start", actions::start, Icons.run()),
                btn(pause, "debug.pause", actions::pause, Icons.debugPause()),
                btn(stop, "debug.stop", actions::stop, Icons.debugStop()),
                btn(restart, "debug.restart", actions::restart, Icons.refresh()),
                groupSeparator(),
                btn(stepOver, "debug.stepOver", actions::stepOver, Icons.debugStepOver()),
                btn(stepInto, "debug.stepInto", actions::stepInto, Icons.debugStepInto()),
                btn(stepOut, "debug.stepOut", actions::stepOut, Icons.debugStepOut()),
                btn(runToCursor, "debug.runToCursor", actions::runToCursor, Icons.debugRunToCursor()),
                spacer(),
                status);
        start.getStyleClass().add("debug-start"); // green play accent
        toolbar.setAlignment(Pos.CENTER_LEFT);

        // Thread selector (IntelliJ's dropdown over the frames list).
        threads.getStyleClass().add("debug-threads");
        threads.setMaxWidth(Double.MAX_VALUE);
        threads.setPromptText(tr("debugpanel.threads"));
        threads.setConverter(new StringConverter<>() {
            @Override
            public String toString(DapModels.ThreadInfo t) {
                return t == null ? "" : t.name();
            }

            @Override
            public DapModels.ThreadInfo fromString(String s) {
                return null;
            }
        });
        threads.valueProperty().addListener((o, a, t) -> {
            if (!settingThreads && t != null) {
                actions.selectThread(t.id());
            }
        });

        // Call stack: rich cells — frame name + muted ":line, File" location.
        stack.getStyleClass().add("debug-stack"); // dense, borderless (Structure/Git panel idiom)
        stack.setCellFactory(v -> new ListCell<>() {
            @Override
            protected void updateItem(DapModels.StackFrameInfo f, boolean empty) {
                super.updateItem(f, empty);
                setText(null);
                if (empty || f == null) {
                    setGraphic(null);
                    return;
                }
                Text name = new Text(f.name());
                name.getStyleClass().add("debug-frame-name");
                TextFlow flow = new TextFlow(name);
                if (f.file() != null) {
                    Text loc = new Text(":" + (f.line() + 1) + ", " + f.file().getFileName());
                    loc.getStyleClass().add("debug-frame-loc");
                    flow.getChildren().add(loc);
                }
                setGraphic(flow);
            }
        });
        stack.getSelectionModel().selectedItemProperty().addListener((o, a, f) -> {
            if (f != null) {
                selectedFrameId = f.id();
                actions.selectFrame(f);
            }
        });

        // Variables: rich cells — name, muted " = ", type-colored value, muted type suffix.
        variables.getStyleClass().add("debug-vars"); // dense, borderless (Structure/Git panel idiom)
        variables.setShowRoot(false);
        variables.setRoot(new TreeItem<>());
        variables.setCellFactory(v -> new TreeCell<>() {
            @Override
            protected void updateItem(VarRow row, boolean empty) {
                super.updateItem(row, empty);
                setText(null);
                setGraphic(empty || row == null ? null : renderRow(row));
            }
        });
        // Watches interactions: double-click "+ Add watch…" adds, double-click a watch edits; the
        // context menu mirrors them (rebuilt per show for the selected row's kind).
        variables.setOnMouseClicked(e -> {
            if (e.getClickCount() != 2) {
                return;
            }
            VarRow row = selectedRow();
            if (row == null) {
                return;
            }
            if (row.kind() == Kind.ADD_WATCH) {
                addWatchPrompt();
            } else if (row.kind() == Kind.WATCH) {
                editWatchPrompt(row.name());
            } else if (row.kind() == Kind.VARIABLE && row.ref() <= 0 && row.parentRef() > 0) {
                setValuePrompt(); // leaf variable only — expandables keep double-click = expand
            }
        });
        variables.setOnKeyPressed(e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.F2) {
                VarRow row = selectedRow();
                if (row != null && row.kind() == Kind.VARIABLE && row.parentRef() > 0) {
                    setValuePrompt();
                    e.consume();
                }
            }
        });
        javafx.scene.control.ContextMenu varsMenu = new javafx.scene.control.ContextMenu();
        varsMenu.setOnShowing(e -> rebuildVarsMenu(varsMenu));
        variables.setContextMenu(varsMenu);

        console.setEditable(false);
        console.setWrapText(false);
        console.getStyleClass().addAll("editor-area", "debug-console");
        RunPanel.installLinkClicks(console, () -> onLink); // double-click a stack-trace line → jump
        evalInput.getStyleClass().add("debug-eval");
        evalInput.setPromptText(tr("debugpanel.evalPrompt"));
        evalInput.setOnAction(e -> runEval());

        Label stackHeader = sectionLabel("debugpanel.callStack");
        VBox stackBox = new VBox(2, stackHeader, threads, stack);
        VBox.setVgrow(stack, Priority.ALWAYS);
        Label varsHeader = sectionLabel("debugpanel.variables");
        VBox varsBox = new VBox(2, varsHeader, variables);
        VBox.setVgrow(variables, Priority.ALWAYS);
        SplitPane top = new SplitPane(stackBox, varsBox);
        top.setOrientation(Orientation.HORIZONTAL);
        top.setDividerPositions(0.42);

        VirtualizedScrollPane<CodeArea> consoleScroll = new VirtualizedScrollPane<>(console);
        VBox consoleBox = new VBox(2, sectionLabel("debugpanel.console"), consoleScroll, evalInput);
        VBox.setVgrow(consoleScroll, Priority.ALWAYS);
        SplitPane main = new SplitPane(top, consoleBox);
        main.setOrientation(Orientation.VERTICAL);
        main.setDividerPositions(0.6);
        VBox.setVgrow(main, Priority.ALWAYS);

        getChildren().addAll(toolbar, main);
        setState(DapManager.State.INACTIVE);
    }

    /** Builds the styled TextFlow for one variables-tree row. */
    private TextFlow renderRow(VarRow row) {
        TextFlow flow = new TextFlow();
        switch (row.kind()) {
            case SCOPE -> {
                Text name = new Text(row.name());
                name.getStyleClass().add("debug-scope-row");
                flow.getChildren().add(name);
            }
            case ADD_WATCH -> {
                Text add = new Text(row.name());
                add.getStyleClass().add("debug-add-watch");
                flow.getChildren().add(add);
            }
            case VARIABLE, WATCH -> {
                Text name = new Text(row.name());
                name.getStyleClass().add("debug-var-name");
                Text eq = new Text(" = ");
                eq.getStyleClass().add("debug-var-eq");
                Text value = new Text(row.value());
                value.getStyleClass().add(DebugValues.cssClass(DebugValues.kind(row.value())));
                flow.getChildren().addAll(name, eq, value);
                if (row.type() != null && !row.type().isBlank()) {
                    Text type = new Text("  " + row.type());
                    type.getStyleClass().add("debug-var-type");
                    flow.getChildren().add(type);
                }
            }
        }
        return flow;
    }

    private Label sectionLabel(String key) {
        Label l = new Label(tr(key));
        l.getStyleClass().add("debug-section");
        return l;
    }

    /** An icon-only toolbar button: the full command title lives in the tooltip (IntelliJ-style). */
    private Button btn(Button b, String key, Runnable action, Node icon) {
        b.setGraphic(icon);
        b.setTooltip(new Tooltip(tr("command." + key)));
        b.getStyleClass().addAll("flat", "debug-toolbar-button");
        b.setFocusTraversable(false);
        b.setOnAction(e -> action.run());
        return b;
    }

    private static Separator groupSeparator() {
        Separator s = new Separator(Orientation.VERTICAL);
        s.getStyleClass().add("debug-toolbar-separator");
        return s;
    }

    private static Region spacer() {
        Region r = new Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        return r;
    }

    // --- Driven by the controller (FX thread) ---------------------------------------------------

    /** Updates the toolbar/status to reflect the session state and enables the right controls. */
    public void setState(DapManager.State state) {
        lastState = state;
        boolean suspended = state == DapManager.State.SUSPENDED;
        boolean running = state == DapManager.State.RUNNING;
        boolean active = state != DapManager.State.INACTIVE;
        // Green play = Start when idle, Continue when paused; Pause is its complement while running.
        start.setDisable(!(state == DapManager.State.INACTIVE || suspended));
        pause.setDisable(!running);
        stepOver.setDisable(!suspended);
        stepInto.setDisable(!suspended);
        stepOut.setDisable(!suspended);
        runToCursor.setDisable(!suspended);
        stop.setDisable(!active);
        restart.setDisable(!active);
        evalInput.setDisable(!suspended);
        threads.setDisable(!suspended);
        if (!active) {
            sessionFile = "";
            stack.getItems().clear();
            variables.setRoot(new TreeItem<>());
            setThreads(List.of(), -1);
            selectedFrameId = -1;
        }
        refreshStatus();
    }

    /** Records the file the session is debugging; shown beside the state while the session lives. */
    public void setSessionFile(String fileName) {
        this.sessionFile = fileName == null ? "" : fileName;
        refreshStatus();
    }

    private void refreshStatus() {
        String state = tr("debugpanel.state." + lastState.name().toLowerCase(java.util.Locale.ROOT));
        status.setText(sessionFile.isEmpty() ? state : state + " — " + sessionFile);
    }

    /** Fills the thread dropdown and selects {@code currentThreadId} without re-fetching its stack. */
    public void setThreads(List<DapModels.ThreadInfo> list, int currentThreadId) {
        settingThreads = true;
        try {
            threads.getItems().setAll(list);
            for (DapModels.ThreadInfo t : list) {
                if (t.id() == currentThreadId) {
                    threads.setValue(t);
                    break;
                }
            }
            if (list.isEmpty()) {
                threads.setValue(null);
            }
        } finally {
            settingThreads = false;
        }
    }

    /** Shows the suspended thread's call stack and selects the top frame. */
    public void setCallStack(List<DapModels.StackFrameInfo> frames) {
        stack.getItems().setAll(frames);
        if (!frames.isEmpty()) {
            stack.getSelectionModel().select(0);
        }
    }

    /** Replaces the variables tree with the Watches node + the selected frame's scopes (each lazily
     *  expandable); watches re-evaluate against the newly selected frame. */
    public void setScopes(List<DapModels.ScopeInfo> scopes) {
        TreeItem<VarRow> root = new TreeItem<>();
        root.getChildren().add(buildWatchesNode());
        for (DapModels.ScopeInfo s : scopes) {
            TreeItem<VarRow> item = lazyItem(new VarRow(s.name(), "", "", s.variablesReference(), 0, Kind.SCOPE));
            item.setExpanded(!s.expensive());
            root.getChildren().add(item);
        }
        variables.setRoot(root);
    }

    // --- Watches ---------------------------------------------------------------------------------

    /** Injects the in-scene prompt used by the Add/Edit Watch (and Set Value) dialogs. */
    public void setPrompt(OverlayInput.Prompt prompt) {
        this.prompt = prompt;
    }

    /** The current watch expressions, in display order (persisted by the controller). */
    public List<String> getWatches() {
        return List.copyOf(watches);
    }

    /** Replaces the watch list (session restore / project switch); refreshes the tree if showing. */
    public void setWatches(List<String> list) {
        watches.clear();
        if (list != null) {
            list.stream().filter(w -> w != null && !w.isBlank()).forEach(watches::add);
        }
        refreshWatchesNode();
    }

    public void setOnWatchesChanged(Runnable r) {
        this.onWatchesChanged = r == null ? () -> {} : r;
    }

    /** The Watches tree node: one row per expression (evaluated async against the selected frame,
     *  expandable when the result is structured) + the trailing "+ Add watch…" action row. */
    private TreeItem<VarRow> buildWatchesNode() {
        TreeItem<VarRow> node = new TreeItem<>(new VarRow(tr("debugpanel.watches"), "", "", 0, 0, Kind.SCOPE));
        node.setExpanded(true);
        boolean suspended = lastState == DapManager.State.SUSPENDED;
        for (String expr : watches) {
            TreeItem<VarRow> item = new TreeItem<>(new VarRow(expr, suspended ? "…" : "", "", 0, 0, Kind.WATCH));
            node.getChildren().add(item);
            if (suspended) {
                actions.evaluateWatch(expr, selectedFrameId, r -> {
                    int idx = node.getChildren().indexOf(item);
                    if (idx >= 0) {
                        node.getChildren()
                                .set(
                                        idx,
                                        lazyItem(new VarRow(
                                                expr,
                                                r.result(),
                                                r.type() == null ? "" : r.type(),
                                                r.variablesReference(),
                                                0,
                                                Kind.WATCH)));
                    }
                });
            }
        }
        node.getChildren().add(new TreeItem<>(new VarRow(tr("debugpanel.addWatch"), "", "", 0, 0, Kind.ADD_WATCH)));
        return node;
    }

    /** Rebuilds just the Watches node in place (after add/edit/remove or a session-restore). */
    private void refreshWatchesNode() {
        TreeItem<VarRow> root = variables.getRoot();
        if (root == null || root.getChildren().isEmpty()) {
            return; // no session showing — the next setScopes builds it fresh
        }
        root.getChildren().set(0, buildWatchesNode());
    }

    private VarRow selectedRow() {
        TreeItem<VarRow> sel = variables.getSelectionModel().getSelectedItem();
        return sel == null ? null : sel.getValue();
    }

    /** Rebuilds the variables-tree context menu for the selected row's kind. */
    private void rebuildVarsMenu(javafx.scene.control.ContextMenu menu) {
        menu.getItems().clear();
        VarRow row = selectedRow();
        if (row != null && row.kind() == Kind.WATCH) {
            javafx.scene.control.MenuItem edit = new javafx.scene.control.MenuItem(tr("debugpanel.editWatch"));
            edit.setGraphic(Icons.edit());
            edit.setOnAction(e -> editWatchPrompt(row.name()));
            javafx.scene.control.MenuItem remove = new javafx.scene.control.MenuItem(tr("debugpanel.removeWatch"));
            remove.setGraphic(Icons.remove());
            remove.setOnAction(e -> removeWatch(row.name()));
            menu.getItems().addAll(edit, remove);
        }
        if (row != null && row.kind() == Kind.VARIABLE && row.parentRef() > 0) {
            javafx.scene.control.MenuItem set = new javafx.scene.control.MenuItem(tr("debugpanel.setValue"));
            set.setGraphic(Icons.edit());
            set.setOnAction(e -> setValuePrompt());
            menu.getItems().add(set);
        }
        javafx.scene.control.MenuItem add = new javafx.scene.control.MenuItem(tr("debugpanel.addWatch"));
        add.setGraphic(Icons.newFile());
        add.setOnAction(e -> addWatchPrompt());
        menu.getItems().add(add);
    }

    private void addWatchPrompt() {
        if (prompt == null) {
            return;
        }
        prompt.show(tr("debugpanel.addWatchTitle"), tr("debugpanel.watchExpr"), "", expr -> {
            if (expr != null && !expr.isBlank()) {
                watches.add(expr.strip());
                onWatchesChanged.run();
                refreshWatchesNode();
            }
        });
    }

    private void editWatchPrompt(String old) {
        if (prompt == null) {
            return;
        }
        prompt.show(tr("debugpanel.editWatchTitle"), tr("debugpanel.watchExpr"), old, expr -> {
            int idx = watches.indexOf(old);
            if (idx >= 0 && expr != null && !expr.isBlank()) {
                watches.set(idx, expr.strip());
                onWatchesChanged.run();
                refreshWatchesNode();
            }
        });
    }

    private void removeWatch(String expr) {
        if (watches.remove(expr)) {
            onWatchesChanged.run();
            refreshWatchesNode();
        }
    }

    /** Set Value… on the selected variable row: prompt pre-filled with the current value, then DAP
     *  setVariable; the row updates in place and watches re-evaluate (a set can change them). */
    private void setValuePrompt() {
        TreeItem<VarRow> item = variables.getSelectionModel().getSelectedItem();
        VarRow row = item == null ? null : item.getValue();
        if (prompt == null || row == null || row.kind() != Kind.VARIABLE || row.parentRef() <= 0) {
            return;
        }
        prompt.show(tr("debugpanel.setValueTitle"), row.name(), row.value(), value -> {
            if (value == null) {
                return;
            }
            actions.setVariable(row.parentRef(), row.name(), value, newValue -> {
                item.setValue(new VarRow(row.name(), newValue, row.type(), row.ref(), row.parentRef(), Kind.VARIABLE));
                refreshWatchesNode();
            });
        });
    }

    /** Palette-command entry points mirroring the panel's own controls. */
    public void addWatch() {
        addWatchPrompt();
    }

    public void setSelectedValue() {
        setValuePrompt(); // no-ops unless a settable leaf variable is selected
    }

    /** Focuses the evaluate (REPL) field so the user can type an expression (enabled only while suspended). */
    public void focusEvaluate() {
        if (!evalInput.isDisabled()) {
            evalInput.requestFocus();
        }
    }

    /** Matches the console font to the editor's code-area font (family + effective size). */
    public void setConsoleFont(String family, int size) {
        console.setStyle("-fx-font-family: \"" + family + "\"; -fx-font-size: " + size + "px;");
    }

    /** Appends program/console output (trimmed to a cap), auto-scrolling to the bottom. The DAP {@code stderr}
     *  category is tinted ({@code .text.run-stderr}) so error output stands out from normal program output. */
    public void appendOutput(String text, String category) {
        if (text == null) {
            return;
        }
        int start = console.getLength();
        console.appendText(text);
        if ("stderr".equals(category) && !text.isEmpty()) {
            StyleSpans<Collection<String>> spans = new StyleSpansBuilder<Collection<String>>()
                    .add(List.of("run-stderr"), text.length())
                    .create();
            console.setStyleSpans(start, spans);
        }
        int len = console.getLength();
        if (len > MAX_CONSOLE_CHARS) {
            console.deleteText(0, len - MAX_CONSOLE_CHARS);
        }
        console.moveTo(console.getLength());
        console.requestFollowCaret();
    }

    private void runEval() {
        String expr = evalInput.getText();
        if (expr == null || expr.isBlank()) {
            return;
        }
        appendOutput("> " + expr + "\n", "console");
        evalInput.clear();
        actions.evaluate(
                expr, selectedFrameId, result -> appendOutput((result == null ? "" : result) + "\n", "console"));
    }

    /** A tree item whose children are loaded on first expand (when {@code ref > 0}). */
    private TreeItem<VarRow> lazyItem(VarRow row) {
        TreeItem<VarRow> item = new TreeItem<>(row) {
            @Override
            public boolean isLeaf() {
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
                            item.getChildren()
                                    .add(lazyItem(new VarRow(
                                            v.name(),
                                            v.value(),
                                            v.type() == null ? "" : v.type(),
                                            v.variablesReference(),
                                            row.ref(),
                                            Kind.VARIABLE)));
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
