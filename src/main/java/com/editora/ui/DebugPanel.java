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
import javafx.scene.input.KeyEvent;
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

        /**
         * Fetch {@code count} indexed children of a container from {@code start} (DAP variable paging), or
         * every named child when {@code indexed} is false. Only asked of a container whose child counts the
         * adapter reported. The default has no paging to offer and fetches everything.
         */
        default void loadChildrenPage(
                int variablesReference,
                boolean indexed,
                int start,
                int count,
                Consumer<List<DapModels.VariableInfo>> cb) {
            loadChildren(variablesReference, cb);
        }

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

    private boolean settingStack; // setCallStack is replacing the stack and reports the top frame itself

    /** The three areas; side by side in a wide (bottom-docked) window, stacked in a narrow (side-docked) one. */
    private final SplitPane areas = new SplitPane();

    /**
     * Name paths of the variables that were expanded, and the selected one, when the tree was last replaced
     * (a step, another frame). Rows of the new tree that match are re-expanded / re-selected as they load, so
     * inspecting {@code obj.inner} across several steps does not mean re-opening it after each one.
     */
    private java.util.Set<List<String>> expandWanted = java.util.Set.of();

    private List<String> selectWanted;
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

    private boolean stoppedOnException;

    /** The "evaluates only while paused" console hint was shown since the program last resumed. */
    private boolean evalHintShown;

    /** A before-launch build is in flight — see {@link #setPreparing}. */
    private boolean preparing;

    /** Row kinds in the variables tree (watches are merged into it, IntelliJ-style). */
    enum Kind {
        SCOPE,
        VARIABLE,
        WATCH,
        ADD_WATCH,
        /** "Show N more…" under a container with more children than one page. */
        MORE
    }

    /** A variables-tree row; {@code ref > 0} means expandable, {@code parentRef} is the DAP container
     *  reference (for set-variable), and {@code kind} drives rendering + context actions. {@code named} /
     *  {@code indexed} are the child counts the adapter reported (0 = unknown). */
    record VarRow(String name, String value, String type, int ref, int parentRef, Kind kind, int named, int indexed) {
        VarRow(String name, String value, String type, int ref, int parentRef, Kind kind) {
            this(name, value, type, ref, parentRef, kind, 0, 0);
        }
    }

    /** The "show more" row of a container: activating it loads the next page of that container's children. */
    private static final class MoreItem extends TreeItem<VarRow> {
        private final Runnable load;
        private boolean requested; // a second double-click must not fetch the same page twice

        MoreItem(String label, Runnable load) {
            super(new VarRow(label, "", "", 0, 0, Kind.MORE));
            this.load = load;
        }
    }

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
                btn(start, "debug.start", "C", actions::start, Icons.run()),
                btn(pause, "debug.pause", "P", actions::pause, Icons.debugPause()),
                btn(stop, "debug.stop", "K", actions::stop, Icons.debugStop()),
                btn(restart, "debug.restart", "R", actions::restart, Icons.refresh()),
                groupSeparator(),
                btn(stepOver, "debug.stepOver", "N", actions::stepOver, Icons.debugStepOver()),
                btn(stepInto, "debug.stepInto", "S", actions::stepInto, Icons.debugStepInto()),
                btn(stepOut, "debug.stepOut", "F", actions::stepOut, Icons.debugStepOut()),
                btn(runToCursor, "debug.runToCursor", "U", actions::runToCursor, Icons.debugRunToCursor()),
                spacer(),
                status);
        start.getStyleClass().add("debug-start"); // green play accent
        toolbar.setAlignment(Pos.CENTER_LEFT);
        // gdb-style single-key shortcuts, live only while focus is somewhere in this panel (the eval field
        // is exempt so the REPL types normally). Bare letters aren't bound in any keymap, so the scene
        // KeyDispatcher lets them fall through to this capture-phase filter untouched.
        addEventFilter(KeyEvent.KEY_PRESSED, this::handleDebugKey);

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
                    // A native frame has no line (-1): show where it is without inventing a ":0".
                    Text loc = new Text(
                            (f.line() >= 0 ? ":" + (f.line() + 1) : "") + ", " + DebugValues.sourceName(f.file()));
                    loc.getStyleClass().add("debug-frame-loc");
                    flow.getChildren().add(loc);
                }
                setGraphic(flow);
            }
        });
        stack.getSelectionModel().selectedItemProperty().addListener((o, a, f) -> {
            if (f != null && !settingStack) {
                reportFrame(f);
            }
        });
        // A click on the frame that is already selected changes no selection, so the listener stays silent;
        // it is still how the user asks to be taken back to that frame's line (after closing its tab, say).
        DapModels.StackFrameInfo[] pressedOn = {null};
        stack.addEventFilter(
                javafx.scene.input.MouseEvent.MOUSE_PRESSED,
                e -> pressedOn[0] = stack.getSelectionModel().getSelectedItem());
        stack.setOnMouseClicked(e -> {
            DapModels.StackFrameInfo f = stack.getSelectionModel().getSelectedItem();
            if (f != null && f == pressedOn[0] && e.getButton() == javafx.scene.input.MouseButton.PRIMARY) {
                reportFrame(f);
            }
        });

        // Variables: rich cells — name, muted " = ", type-colored value, muted type suffix.
        variables.getStyleClass().add("debug-vars"); // dense, borderless (Structure/Git panel idiom)
        variables.setShowRoot(false);
        variables.setRoot(idleRoot());
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
            } else if (row.kind() == Kind.MORE) {
                showMore(variables.getSelectionModel().getSelectedItem());
            } else if (row.kind() == Kind.WATCH) {
                editWatchPrompt(row.name());
            } else if (row.kind() == Kind.VARIABLE && row.ref() <= 0 && row.parentRef() > 0) {
                setValuePrompt(); // leaf variable only — expandables keep double-click = expand
            }
        });
        variables.setOnKeyPressed(e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.ENTER
                    && variables.getSelectionModel().getSelectedItem() instanceof MoreItem more) {
                showMore(more);
                e.consume();
            } else if (e.getCode() == javafx.scene.input.KeyCode.F2) {
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
        ConsoleNav.installShared(console); // configured-keymap scrolling while the console has focus
        evalInput.getStyleClass().add("debug-eval");
        evalInput.setPromptText(tr("debugpanel.evalPrompt"));
        evalInput.setOnAction(e -> runEval());

        // The thread selector shares the header's row: on its own row it took a third of the default strip.
        HBox stackHeader = new HBox(6, sectionLabel("debugpanel.callStack"), threads);
        stackHeader.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(threads, Priority.ALWAYS);
        VBox stackBox = new VBox(2, stackHeader, stack);
        VBox.setVgrow(stack, Priority.ALWAYS);
        Label varsHeader = sectionLabel("debugpanel.variables");
        VBox varsBox = new VBox(2, varsHeader, variables);
        VBox.setVgrow(variables, Priority.ALWAYS);

        VirtualizedScrollPane<CodeArea> consoleScroll = new VirtualizedScrollPane<>(console);
        VBox consoleBox = new VBox(2, sectionLabel("debugpanel.console"), consoleScroll, evalInput);
        VBox.setVgrow(consoleScroll, Priority.ALWAYS);
        javafx.scene.control.ContextMenu consoleMenu = new javafx.scene.control.ContextMenu();
        javafx.scene.control.MenuItem clear = new javafx.scene.control.MenuItem(tr("debugpanel.clearConsole"));
        clear.setGraphic(Icons.remove());
        clear.setOnAction(e -> clearConsole());
        consoleMenu.getItems().add(clear);
        console.setContextMenu(consoleMenu);

        // Call stack | variables | console. Stacked on top of each other they shared the height of the
        // bottom strip three ways: at the default tool-window height the call stack had no rows at all.
        areas.getItems().addAll(stackBox, varsBox, consoleBox);
        SplitPane main = areas;
        VBox.setVgrow(main, Priority.ALWAYS);
        arrange(true);
        widthProperty().addListener((o, a, w) -> {
            if (w.doubleValue() > 0) { // 0 = not laid out / hidden: says nothing about where it is docked
                arrange(w.doubleValue() >= SIDE_BY_SIDE_MIN_WIDTH);
            }
        });

        getChildren().addAll(toolbar, main);
        setState(DapManager.State.INACTIVE);
    }

    /** Narrower than this (docked at a side), the three areas are stacked instead of laid side by side. */
    static final double SIDE_BY_SIDE_MIN_WIDTH = 640;

    private Orientation arranged;

    private void arrange(boolean sideBySide) {
        Orientation wanted = sideBySide ? Orientation.HORIZONTAL : Orientation.VERTICAL;
        if (arranged == wanted) {
            return; // keep the dividers where the user dragged them
        }
        arranged = wanted;
        areas.setOrientation(wanted);
        areas.setDividerPositions(sideBySide ? new double[] {0.26, 0.58} : new double[] {0.3, 0.62});
    }

    private void reportFrame(DapModels.StackFrameInfo frame) {
        if (lastState != DapManager.State.SUSPENDED) {
            return; // the frames of the previous stop: the program has moved on, there is nothing to show
        }
        selectedFrameId = frame.id();
        actions.selectFrame(frame);
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
            case ADD_WATCH, MORE -> {
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

    /** An icon-only toolbar button: the full command title + its single-key shortcut live in the tooltip
     *  (IntelliJ-style), so the shortcut is discoverable on hover. */
    private Button btn(Button b, String key, String shortcut, Runnable action, Node icon) {
        b.setGraphic(icon);
        b.setTooltip(new Tooltip(tr("command." + key) + "  (" + shortcut + ")"));
        b.getStyleClass().addAll("flat", "debug-toolbar-button");
        b.setFocusTraversable(false);
        b.setOnAction(e -> action.run());
        return b;
    }

    /**
     * gdb/lldb-style single-key control while the Debug panel is focused: c=continue, p=pause, k=kill/stop,
     * r=restart, n=next/step-over, s=step-into, f=finish/step-out, u=until/run-to-cursor. Fires the matching
     * toolbar button (respecting its enabled state) and swallows the key so the stack/variables lists don't
     * treat it as type-ahead. Exempts the evaluate REPL field and any modified chord so normal typing and
     * global chords (e.g. C-x o to leave the panel) still work.
     */
    private void handleDebugKey(KeyEvent e) {
        if (e.isControlDown() || e.isAltDown() || e.isMetaDown() || e.isShortcutDown()) {
            return; // leave modified chords (incl. C-x o focus cycling) to the global dispatcher
        }
        if (evalInput.isFocused() || e.getTarget() == evalInput) {
            return; // typing an expression in the REPL
        }
        Button target =
                switch (e.getCode()) {
                    case C -> start;
                    case P -> pause;
                    case K -> stop;
                    case R -> restart;
                    case N -> stepOver;
                    case S -> stepInto;
                    case F -> stepOut;
                    case U -> runToCursor;
                    default -> null;
                };
        if (target == null) {
            return;
        }
        if (!target.isDisabled()) {
            target.fire();
        }
        e.consume(); // reserve these letters in the panel even when the action is currently disabled
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
        if (suspended || state == DapManager.State.INACTIVE) {
            evalHintShown = false;
        }
        boolean running = state == DapManager.State.RUNNING;
        boolean active = state != DapManager.State.INACTIVE;
        // Green play = Start when idle, Continue when paused; Pause is its complement while running.
        start.setDisable(preparing || !(state == DapManager.State.INACTIVE || suspended));
        pause.setDisable(!running);
        stepOver.setDisable(!suspended);
        stepInto.setDisable(!suspended);
        stepOut.setDisable(!suspended);
        runToCursor.setDisable(!suspended);
        stop.setDisable(!active && !preparing);
        restart.setDisable(!active);
        // Usable while the program runs as well as while it is paused (Enter only evaluates when paused):
        // disabling the field on every Step/Continue made JavaFX move focus out of the panel, the next stop
        // then handed focus to the editor, and the expression being typed went into the source file.
        evalInput.setDisable(!(suspended || running));
        threads.setDisable(!suspended);
        if (!active) {
            sessionFile = "";
            stack.getItems().clear();
            variables.setRoot(idleRoot());
            setThreads(List.of(), -1);
            selectedFrameId = -1;
        }
        refreshStatus();
    }

    /**
     * A before-launch build is running for a debug launch: there is no session yet, but Stop must be able to
     * cancel the build and Start must not begin a second one.
     */
    public void setPreparing(boolean preparing) {
        this.preparing = preparing;
        boolean active = lastState != DapManager.State.INACTIVE;
        start.setDisable(preparing || !(!active || lastState == DapManager.State.SUSPENDED));
        stop.setDisable(!active && !preparing);
    }

    /** Records the file the session is debugging; shown beside the state while the session lives. */
    public void setSessionFile(String fileName) {
        this.sessionFile = fileName == null ? "" : fileName;
        refreshStatus();
    }

    private void refreshStatus() {
        String state = stoppedOnException && lastState == DapManager.State.SUSPENDED
                ? tr("debugpanel.state.exception")
                : tr("debugpanel.state." + lastState.name().toLowerCase(java.util.Locale.ROOT));
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

    /**
     * Shows the suspended thread's call stack, selects the top frame and reports it through
     * {@link Actions#selectFrame} — on <em>every</em> call. The report must not ride on the selection
     * listener: a stop whose top frame {@code equals()} the previous one (debugpy reuses frame ids, so a
     * re-hit breakpoint is the identical record) leaves the selected item unchanged, the listener silent, and
     * the variables and execution line on the previous stop's values.
     */
    public void setCallStack(List<DapModels.StackFrameInfo> frames) {
        setCallStack(frames, 0);
    }

    /** As {@link #setCallStack(List)}, selecting frame {@code selected} — the topmost one that has source. */
    public void setCallStack(List<DapModels.StackFrameInfo> frames, int selected) {
        int index = Math.max(0, Math.min(selected, frames.size() - 1));
        settingStack = true;
        try {
            stack.getItems().setAll(frames);
            if (!frames.isEmpty()) {
                stack.getSelectionModel().clearAndSelect(index);
                if (index > 0) {
                    stack.scrollTo(index - 1); // the selected frame in view, with the one it was called from
                }
            }
        } finally {
            settingStack = false;
        }
        if (!frames.isEmpty()) {
            reportFrame(frames.get(index));
        }
    }

    /**
     * Why the program is suspended, as the adapter's stop event named it ({@code null} when it is not). Only an
     * exception stop is called out: it otherwise looks exactly like a breakpoint hit on the same line.
     */
    public void setStopReason(String reason) {
        boolean exception = "exception".equals(reason);
        if (exception != stoppedOnException) {
            stoppedOnException = exception;
            refreshStatus();
        }
    }

    /** The thread shown in the dropdown, or -1. */
    int selectedThreadId() {
        DapModels.ThreadInfo t = threads.getValue();
        return t == null ? -1 : t.id();
    }

    /** Shows {@code threadId} in the dropdown without reporting a selection (the caller loads its stack). */
    void showThread(int threadId) {
        setThreads(List.copyOf(threads.getItems()), threadId);
    }

    /** Replaces the variables tree with the Watches node + the selected frame's scopes (each lazily
     *  expandable); watches re-evaluate against the newly selected frame. */
    public void setScopes(List<DapModels.ScopeInfo> scopes) {
        rememberTreeState();
        TreeItem<VarRow> old = variables.getRoot();
        TreeItem<VarRow> root = new TreeItem<>();
        root.getChildren().add(buildWatchesNode());
        for (DapModels.ScopeInfo s : scopes) {
            TreeItem<VarRow> item = lazyItem(new VarRow(s.name(), "", "", s.variablesReference(), 0, Kind.SCOPE));
            root.getChildren().add(item);
        }
        variables.setRoot(root);
        // Expanded only once the scope is in the tree: its children load on expansion and restore the
        // remembered rows by their path from the root.
        for (int i = 0; i < scopes.size(); i++) {
            TreeItem<VarRow> item = root.getChildren().get(i + 1);
            Boolean was = scopeExpanded(old, scopes.get(i).name());
            item.setExpanded(was != null ? was : !scopes.get(i).expensive());
            restoreSelection(item);
        }
    }

    /** Whether the scope called {@code name} was expanded in the tree being replaced, or null if not there. */
    private static Boolean scopeExpanded(TreeItem<VarRow> oldRoot, String name) {
        if (oldRoot != null) {
            for (int i = 1; i < oldRoot.getChildren().size(); i++) { // 0 is the Watches node
                TreeItem<VarRow> scope = oldRoot.getChildren().get(i);
                if (scope.getValue() != null && scope.getValue().name().equals(name)) {
                    return scope.isExpanded();
                }
            }
        }
        return null;
    }

    /** The variables tree with only the Watches node: what is shown while nothing is suspended. */
    private TreeItem<VarRow> idleRoot() {
        TreeItem<VarRow> root = new TreeItem<>();
        root.getChildren().add(buildWatchesNode());
        return root;
    }

    // --- expansion + selection carried across a tree replacement -----------------------------------

    /** The row names from the top-level node down to {@code item}. */
    private static List<String> pathOf(TreeItem<VarRow> item) {
        java.util.LinkedList<String> path = new java.util.LinkedList<>();
        for (TreeItem<VarRow> i = item; i != null && i.getValue() != null; i = i.getParent()) {
            path.addFirst(i.getValue().name());
        }
        return List.copyOf(path);
    }

    private void rememberTreeState() {
        java.util.Set<List<String>> expanded = new java.util.HashSet<>();
        TreeItem<VarRow> root = variables.getRoot();
        if (root != null) {
            for (TreeItem<VarRow> top : root.getChildren()) {
                collectExpanded(top, expanded);
            }
        }
        TreeItem<VarRow> selected = variables.getSelectionModel().getSelectedItem();
        // An empty tree (a new session's first stop) has nothing to say: keep what the last one remembered.
        if (!expanded.isEmpty() || selected != null) {
            expandWanted = expanded;
            selectWanted = selected == null ? null : pathOf(selected);
        }
    }

    private static void collectExpanded(TreeItem<VarRow> item, java.util.Set<List<String>> out) {
        VarRow row = item.getValue();
        if (row == null || !item.isExpanded() || item.isLeaf()) {
            return;
        }
        if (row.kind() == Kind.VARIABLE || row.kind() == Kind.WATCH) {
            out.add(pathOf(item));
        }
        for (TreeItem<VarRow> child : item.getChildren()) {
            collectExpanded(child, out);
        }
    }

    /** Re-expands / re-selects {@code item} if it is one of the rows remembered from the replaced tree. */
    private void restoreState(TreeItem<VarRow> item) {
        if (!item.isLeaf() && !expandWanted.isEmpty() && expandWanted.contains(pathOf(item))) {
            item.setExpanded(true); // loads its children, which restores the level below in turn
        }
        restoreSelection(item);
    }

    private void restoreSelection(TreeItem<VarRow> item) {
        if (selectWanted != null && selectWanted.equals(pathOf(item))) {
            selectWanted = null;
            variables.getSelectionModel().select(item);
        }
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
                        boolean wasSelected = variables.getSelectionModel().getSelectedItem() == item;
                        TreeItem<VarRow> evaluated = lazyItem(new VarRow(
                                expr,
                                r.result(),
                                r.type() == null ? "" : r.type(),
                                r.variablesReference(),
                                0,
                                Kind.WATCH,
                                r.namedVariables(),
                                r.indexedVariables()));
                        node.getChildren().set(idx, evaluated);
                        if (wasSelected) {
                            variables.getSelectionModel().select(evaluated);
                        }
                        restoreState(evaluated);
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
            variables.setRoot(idleRoot());
            return;
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
                item.setValue(new VarRow(
                        row.name(),
                        newValue,
                        row.type(),
                        row.ref(),
                        row.parentRef(),
                        Kind.VARIABLE,
                        row.named(),
                        row.indexed()));
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

    /** Focuses the evaluate (REPL) field so the user can type an expression (enabled only during a session). */
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
        text = DebugValues.stripAnsi(text); // colour codes of a logger / Node script are not text to read
        int start = console.getLength();
        int caretBefore = console.getCaretPosition();
        boolean follow = caretBefore >= start; // scrolled back? stay put
        console.appendText(text);
        if ("stderr".equals(category) && !text.isEmpty()) {
            StyleSpans<Collection<String>> spans = new StyleSpansBuilder<Collection<String>>()
                    .add(List.of("run-stderr"), text.length())
                    .create();
            console.setStyleSpans(start, spans);
        }
        ConsoleNav.afterAppend(console, caretBefore, follow, MAX_CONSOLE_CHARS);
    }

    private void runEval() {
        String expr = evalInput.getText();
        if (expr == null || expr.isBlank()) {
            return;
        }
        if (lastState != DapManager.State.SUSPENDED) {
            // Nothing to evaluate against while the program runs; the typed text is kept. Say so — once per
            // run, not per Enter: the field is the only input in sight, and a program waiting on its standard
            // input looked as if it had swallowed the line.
            if (!evalHintShown) {
                evalHintShown = true;
                appendOutput(tr("debugpanel.evalNeedsPause") + "\n", "console");
            }
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
                // Only while suspended: a row left over from the previous stop names a reference the adapter
                // has already dropped, and asking for it would leave the row empty for good.
                if (now && !loaded[0] && lastState == DapManager.State.SUSPENDED) {
                    loaded[0] = true;
                    loadChildren(item, row);
                }
            });
        }
        return item;
    }

    /**
     * Loads the children of an expanded container, never more than {@link DebugValues#PAGE_SIZE} rows at a
     * time. When the adapter reported the element count, only that page is <em>requested</em> (DAP {@code
     * start}/{@code count}); otherwise everything is fetched in one response — off the FX thread — and the
     * tree pages through what came back. Either way the rows reach the tree in one {@code addAll}: adding
     * tens of thousands of children one at a time to an expanded, showing item froze the window for seconds.
     */
    private void loadChildren(TreeItem<VarRow> item, VarRow row) {
        if (!DebugValues.fetchedByPage(row.indexed())) {
            actions.loadChildren(row.ref(), all -> showPage(item, row, all, 0));
            return;
        }
        if (row.named() > 0) {
            actions.loadChildrenPage(row.ref(), false, 0, 0, named -> {
                append(item, rows(row, named), false);
                requestPage(item, row, 0);
            });
        } else {
            requestPage(item, row, 0);
        }
    }

    /** Asks the adapter for the indexed children {@code start…} of {@code row} and appends them. */
    private void requestPage(TreeItem<VarRow> item, VarRow row, int start) {
        int count = DebugValues.nextPage(start, row.indexed());
        actions.loadChildrenPage(row.ref(), true, start, count, page -> {
            if (page.size() > count) {
                // The adapter ignored start/count and sent the whole remainder: page through that instead.
                showPage(item, row, page, 0);
                return;
            }
            append(item, rows(row, page), dropMoreRow(item));
            int shown = start + page.size();
            int next = DebugValues.nextPage(shown, row.indexed());
            if (next > 0 && !page.isEmpty()) {
                item.getChildren().add(moreRow(next, row.indexed() - shown, () -> requestPage(item, row, shown)));
            }
        });
    }

    /** Appends the next page of {@code all} — children already fetched — from {@code start}. */
    private void showPage(TreeItem<VarRow> item, VarRow row, List<DapModels.VariableInfo> all, int start) {
        int end = start + DebugValues.nextPage(start, all.size());
        append(item, rows(row, all.subList(start, end)), dropMoreRow(item));
        int next = DebugValues.nextPage(end, all.size());
        if (next > 0) {
            item.getChildren().add(moreRow(next, all.size() - end, () -> showPage(item, row, all, end)));
        }
    }

    /** Adds one page of rows; {@code select} keeps the selection where the "show more" row just was. */
    private void append(TreeItem<VarRow> item, List<TreeItem<VarRow>> children, boolean select) {
        item.getChildren().addAll(children); // one change event, however many rows
        children.forEach(this::restoreState);
        if (select && !children.isEmpty()) {
            variables.getSelectionModel().select(children.get(0));
        }
    }

    private List<TreeItem<VarRow>> rows(VarRow parent, List<DapModels.VariableInfo> vars) {
        List<TreeItem<VarRow>> out = new java.util.ArrayList<>(vars.size());
        for (DapModels.VariableInfo v : vars) {
            out.add(lazyItem(new VarRow(
                    v.name(),
                    v.value(),
                    v.type() == null ? "" : v.type(),
                    v.variablesReference(),
                    parent.ref(),
                    Kind.VARIABLE,
                    v.namedVariables(),
                    v.indexedVariables())));
        }
        return out;
    }

    private static MoreItem moreRow(int next, int remaining, Runnable load) {
        return new MoreItem(tr("debugpanel.showMore", next, remaining), load);
    }

    /** Removes the trailing "show more" row, if any; returns whether it was the selected row. */
    private boolean dropMoreRow(TreeItem<VarRow> item) {
        var children = item.getChildren();
        if (children.isEmpty() || !(children.get(children.size() - 1) instanceof MoreItem more)) {
            return false;
        }
        boolean selected = variables.getSelectionModel().getSelectedItem() == more;
        children.remove(children.size() - 1);
        return selected;
    }

    /** Activates a "show more" row (double-click / Enter): loads the next page of its container. */
    private void showMore(TreeItem<VarRow> selected) {
        if (selected instanceof MoreItem more && !more.requested && lastState == DapManager.State.SUSPENDED) {
            more.requested = true;
            more.load.run();
        }
    }

    /** Empties the console (its context menu, and each new launch — see {@code DebugCoordinator}). */
    public void clearConsole() {
        console.clear();
    }

    @Override
    public void focusFirstItem() {
        stack.requestFocus();
    }
}
