package com.editora.ui;

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
import javafx.scene.control.ProgressIndicator;
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
import org.jspecify.annotations.Nullable;

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

    // --- The exception breakpoints (B10): Caught / Uncaught under one compact toolbar button ------------

    /**
     * Break on caught / uncaught exceptions, as checked items of one icon button: two text toggles took twice the
     * height of the icon buttons, outshone them when on, and read "Cau…" / "Uncau…" at 1000 px (review 2 C104).
     */
    private final javafx.scene.control.CheckMenuItem caughtToggle = new javafx.scene.control.CheckMenuItem();

    private final javafx.scene.control.CheckMenuItem uncaughtToggle = new javafx.scene.control.CheckMenuItem();

    private final javafx.scene.control.MenuButton exceptionsButton = new javafx.scene.control.MenuButton();

    private static final javafx.css.PseudoClass EXCEPTIONS_ON = javafx.css.PseudoClass.getPseudoClass("on");

    private javafx.scene.control.MenuButton exceptionsButton() {
        caughtToggle.setText(DebugPanelText.bareTitle(tr("command.debug.exceptions.caught")));
        uncaughtToggle.setText(DebugPanelText.bareTitle(tr("command.debug.exceptions.uncaught")));
        // The coordinator owns the state and writes it back through setExceptionToggles.
        caughtToggle.setOnAction(e -> actions.toggleExceptions(true));
        uncaughtToggle.setOnAction(e -> actions.toggleExceptions(false));
        exceptionsButton.getItems().setAll(caughtToggle, uncaughtToggle);
        exceptionsButton.setGraphic(Icons.warning());
        exceptionsButton.getStyleClass().addAll("flat", "debug-toolbar-button", "debug-exceptions");
        exceptionsButton.setFocusTraversable(false);
        exceptionsButton.setAccessibleText(tr("debugpanel.exceptions"));
        exceptionsButton.setTooltip(new Tooltip());
        showExceptionState();
        return exceptionsButton;
    }

    /** Shows whether exception breakpoints stop on caught and on uncaught exceptions. */
    public void setExceptionToggles(boolean caught, boolean uncaught) {
        caughtToggle.setSelected(caught);
        uncaughtToggle.setSelected(uncaught);
        showExceptionState();
    }

    private void showExceptionState() {
        boolean caught = caughtToggle.isSelected();
        boolean uncaught = uncaughtToggle.isSelected();
        String tip = DebugPanelText.exceptionsTip(caught, uncaught);
        exceptionsButton.getTooltip().setText(tip);
        exceptionsButton.setAccessibleHelp(tip);
        exceptionsButton.pseudoClassStateChanged(EXCEPTIONS_ON, caught || uncaught);
    }

    /** Callbacks into the controller / {@link DapManager}. */
    public interface Actions {
        /** Start a debug session when idle, or continue when suspended (the green play button). */
        void start();

        /** The call stack's context menu for {@code frame} of {@code frames} (Restart Frame); empty: no menu. */
        default List<javafx.scene.control.MenuItem> stackMenuItems(
                DapModels.StackFrameInfo frame, List<DapModels.StackFrameInfo> frames) {
            return List.of();
        }

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

        /** The program's threads, with which are suspended, for the Threads view (to {@code cb}, FX thread). */
        default void threadStatuses(Consumer<List<DapModels.ThreadStatus>> cb) {
            cb.accept(List.of());
        }

        /** Threads view → Pause Thread: suspends {@code threadId} alone. */
        default void pauseThread(int threadId) {}

        /** Threads view → Resume Thread: resumes {@code threadId} alone. */
        default void resumeThread(int threadId) {}

        /** Threads view → Show Frames: shows suspended thread {@code thread}'s call stack and variables. */
        default void showThread(DapModels.ThreadStatus thread) {
            selectThread(thread.id());
        }

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
        void evaluate(String expression, int frameId, Consumer<com.editora.dap.ConsoleEvaluation> cb);

        /** Evaluate a watch expression (context "watch") keeping the expandable reference + type. */
        void evaluateWatch(String expression, int frameId, Consumer<DapModels.EvalResult> cb);

        /** Set a variable's value (DAP setVariable on its container reference); delivers the new value. */
        void setVariable(int parentRef, String name, String value, Consumer<String> cb);

        /**
         * {@link #setVariable(int, String, String, Consumer)}, assigning {@code assignTo = value} in frame {@code
         * frameId} when the adapter cannot set a variable of that type (review 3, B208).
         */
        default void setVariable(
                int parentRef, String name, String value, String assignTo, int frameId, Consumer<String> cb) {
            setVariable(parentRef, name, value, cb);
        }

        /** Whether the session can watch a field (Variables → Break When Value Changes). */
        default boolean canWatchField() {
            return false;
        }

        /** A field watchpoint on {@code name}, a variable of container {@code parentRef}, when it is a field. */
        default void watchField(int parentRef, String name) {}

        /** Load a row the stop's time budget left unloaded (a click on it); delivers the loaded row (B201). */
        default void loadValue(DapModels.VariableInfo row, Consumer<DapModels.VariableInfo> cb) {
            cb.accept(row);
        }

        /**
         * Fetch the selected frame's variables, watches and inline values again: a console evaluation or Set Value
         * may have changed them (B205). False when there is nothing to refresh them from.
         */
        default boolean refreshValues() {
            return false;
        }

        /** Send a line typed in the console to the running program's standard input. */
        default void sendInput(String line) {}

        /** End the running program's standard input (end of file). */
        default void endInput() {}

        /**
         * The adapter's completions of {@code text} at 0-based {@code caret} in frame {@code frameId} (DAP
         * {@code completions}); none by default.
         */
        default void complete(String text, int caret, int frameId, Consumer<List<DapModels.CompletionTarget>> cb) {
            cb.accept(List.of());
        }

        /** Turn exception breakpoints on caught ({@code caught}) or uncaught exceptions on or off. */
        default void toggleExceptions(boolean caught) {}

        /** Whether numbers are shown in hexadecimal. */
        default boolean hexValues() {
            return false;
        }

        /** Show numbers in hexadecimal ({@code on}) or decimal, and fetch the values shown again. */
        default void setHexValues(boolean on) {}
    }

    private final Actions actions;
    private final Label status = new Label();
    /** The runtime the debugged program runs under; shown while its session lives (see {@link #setRuntime}). */
    private final Label runtime = RunPanel.runtimeChip();
    /** Spins while a before-launch build prepares, the session starts, or the program runs — not while paused. */
    private final ProgressIndicator busy = RunPanel.busySpinner();

    private final Button start = new Button();
    private final Button pause = new Button();
    private final Button stop = new Button();
    private final Button restart = new Button();
    private final Button stepOver = new Button();
    private final Button stepInto = new Button();
    private final Button stepOut = new Button();
    private final Button runToCursor = new Button();
    private final ComboBox<DapModels.ThreadInfo> threads = new ComboBox<>();

    /**
     * The Threads view: every thread of the program, suspended or running (IntelliJ's Threads tab, VS Code's threads
     * in its Call Stack), shown in place of the frames while {@link #threadsToggle} is on.
     */
    private final ListView<DapModels.ThreadStatus> threadList = new ListView<>();

    /** Switches the Call Stack area between the frames and the Threads view. */
    private final javafx.scene.control.ToggleButton threadsToggle =
            new javafx.scene.control.ToggleButton(tr("debugpanel.threadsView"));

    /** The Call Stack area's title: "Call Stack", or "Threads" while the Threads view is shown. */
    private @Nullable Label stackTitle;

    /** Coalesces the thread start/exit events of a busy program into one listing. */
    private final javafx.animation.PauseTransition threadsRefresh =
            new javafx.animation.PauseTransition(javafx.util.Duration.millis(250));

    private final ListView<DapModels.StackFrameInfo> stack = new ListView<>();
    private final TreeView<VarRow> variables = new TreeView<>();
    private final CodeArea console = AreaUndo.none(new CodeArea());
    private final ConsoleAppender appender = new ConsoleAppender(console, MAX_CONSOLE_CHARS);
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

    private @Nullable List<String> selectWanted;
    /** Watch expressions (the "Watches" node merged into the variables tree, IntelliJ-style). */
    private final java.util.List<String> watches = new java.util.ArrayList<>();

    private Runnable onWatchesChanged = () -> {};
    private OverlayInput.@Nullable Prompt prompt;

    /** The prompt of Add / Edit Watch: as {@link #prompt}, with completion in its field (null: {@link #prompt}). */
    private OverlayInput.@Nullable Prompt expressionPrompt;

    /** Completion in the evaluate field (follow-up 5). */
    private DebugCompletion evalCompletion;
    /** Receives a double-clicked stack-trace location from the console (controller resolves + jumps). */
    private java.util.function.@Nullable Consumer<com.editora.run.StackTraceLinks.Link> onLink;

    public void setOnLink(java.util.function.Consumer<com.editora.run.StackTraceLinks.Link> onLink) {
        this.onLink = onLink;
    }
    /** File name the current session was started for; shown beside the state so a session left running
     *  on another file is visibly bound to it. Cleared when the session ends. */
    private String sessionFile = "";

    private DapManager.State lastState = DapManager.State.INACTIVE;

    private boolean stoppedOnException;

    /** The state line's key when the program stopped on a method breakpoint or a field watchpoint, else null. */
    private @Nullable String stoppedOnMember;

    /** Which exception the program is stopped on ("IllegalStateException: boom"); empty until it is known. */
    private String stoppedException = "";

    /** Why Restart cannot be used for the session there is, else null — see {@link #setRestartBlocked}. */
    private @Nullable String restartBlocked;

    /** Holds the Restart button: a disabled button gets no mouse events, so its reason is shown from here. */
    private final javafx.scene.layout.StackPane restartHolder = new javafx.scene.layout.StackPane();

    private @Nullable Tooltip restartBlockedTip;

    /** The "evaluates only while paused" console hint was shown since the program last resumed. */
    private boolean evalHintShown;
    /** The session's program takes typed lines on its standard input (see {@link #setProgramInput}). */
    private boolean programInput;

    /** A before-launch build is in flight — see {@link #setPreparing}. */
    private boolean preparing;

    /** The keymap chord bound to a command id ("" when none), for the toolbar tooltips (review C18). */
    private java.util.function.Function<String, String> chordFor = id -> "";

    /** Whether a frame's source can be shown; frames without are drawn muted (review C16). */
    private java.util.function.Predicate<DapModels.StackFrameInfo> hasSource = f -> f.file() != null;

    /** The evaluate field's history (Up / Down, review C20). */
    private final DebugPanelText.History history = new DebugPanelText.History();

    /** The exception of the current exception stop (shown at the top of Variables, review C13), or null. */
    private DapModels.@Nullable ExceptionInfo exception;

    /** The exit code of the program of the session that just ended, or null (review C19). */
    private @Nullable Integer exitCode;

    /** Mute Breakpoints is on: the state line says so, or a session that never stops would puzzle. */
    private boolean breakpointsMuted;

    /** The thread the program stopped on (named first and marked in the selector), or -1. */
    private int stoppedThreadId = -1;

    /** Said in the call-stack list while there is no session: how to start one (review C22). */
    private final Label stackHint = new Label();

    /** Row kinds in the variables tree (watches are merged into it, IntelliJ-style). */
    enum Kind {
        SCOPE,
        VARIABLE,
        WATCH,
        ADD_WATCH,
        /** "Show N more…" under a container with more children than one page. */
        MORE,
        /** The exception of an exception stop and its message (review C13). */
        EXCEPTION
    }

    /** A variables-tree row; {@code ref > 0} means expandable, {@code parentRef} is the DAP container
     *  reference (for set-variable), and {@code kind} drives rendering + context actions. {@code named} /
     *  {@code indexed} are the child counts the adapter reported (0 = unknown). {@code failed} marks a watch
     *  the adapter could not evaluate: its {@code value} is the adapter's message, drawn as an error. */
    record VarRow(
            String name,
            String value,
            String type,
            int ref,
            int parentRef,
            Kind kind,
            int named,
            int indexed,
            boolean failed,
            DapModels.@Nullable ValueNote note) {
        VarRow(String name, String value, String type, int ref, int parentRef, Kind kind) {
            this(name, value, type, ref, parentRef, kind, 0, 0, false);
        }

        VarRow(String name, String value, String type, int ref, int parentRef, Kind kind, int named, int indexed) {
            this(name, value, type, ref, parentRef, kind, named, indexed, false);
        }

        VarRow(
                String name,
                String value,
                String type,
                int ref,
                int parentRef,
                Kind kind,
                int named,
                int indexed,
                boolean failed) {
            this(name, value, type, ref, parentRef, kind, named, indexed, failed, null);
        }

        /** Not loaded within the stop's budget: a click (or expanding it) loads it (B201). */
        boolean notLoaded() {
            return note == DapModels.ValueNote.NOT_LOADED;
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
        appender.onTrimmed(this::refreshStatus);
        getStyleClass().add("debug-panel");
        getProperties().put("editora.ownsKeys", Boolean.TRUE);
        setSpacing(4);
        setPadding(new Insets(4));

        status.getStyleClass().add("debug-status");
        restartHolder
                .getChildren()
                .add(btn(restart, DebugPanelText.Control.RESTART, actions::restart, Icons.refresh()));
        // IntelliJ-style grouped, icon-only toolbar; the session state + file sit at the right edge.
        HBox toolbar = new HBox(
                2,
                btn(start, DebugPanelText.Control.START, actions::start, Icons.run()),
                btn(pause, DebugPanelText.Control.PAUSE, actions::pause, Icons.debugPause()),
                btn(stop, DebugPanelText.Control.STOP, actions::stop, Icons.debugStop()),
                restartHolder,
                groupSeparator(),
                btn(stepOver, DebugPanelText.Control.STEP_OVER, actions::stepOver, Icons.debugStepOver()),
                btn(stepInto, DebugPanelText.Control.STEP_INTO, actions::stepInto, Icons.debugStepInto()),
                btn(stepOut, DebugPanelText.Control.STEP_OUT, actions::stepOut, Icons.debugStepOut()),
                btn(runToCursor, DebugPanelText.Control.RUN_TO_CURSOR, actions::runToCursor, Icons.debugRunToCursor()),
                groupSeparator(),
                exceptionsButton(),
                spacer(),
                busy,
                runtime,
                status);
        start.getStyleClass().add("debug-start"); // green play accent
        HBox.setMargin(busy, new Insets(0, 4, 0, 0)); // the toolbar packs its buttons 2px apart
        HBox.setMargin(runtime, new Insets(0, 6, 0, 0));
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
                if (t == null) {
                    return "";
                }
                String name = DebugPanelText.threadName(t.name());
                return t.id() == stoppedThreadId ? tr("debugpanel.threadPaused", name) : name;
            }

            @Override
            public DapModels.@Nullable ThreadInfo fromString(String s) {
                return null;
            }
        });
        threads.valueProperty().addListener((o, a, t) -> {
            if (!settingThreads && t != null) {
                actions.selectThread(t.id());
            }
        });
        buildThreadsView();

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
                if (StackFolding.isFold(f)) {
                    // Runtime/library frames folded away (review D105): one muted row, clicked to show them.
                    Text fold = new Text(tr("debugpanel.foldedFrames", f.line()));
                    fold.getStyleClass().addAll("debug-frame-name", "debug-frame-nosource", "debug-frame-fold");
                    setGraphic(new TextFlow(fold));
                    return;
                }
                Text name = new Text(DebugPanelText.frameName(f.name()));
                name.getStyleClass().add("debug-frame-name");
                TextFlow flow = new TextFlow(name);
                if (com.editora.dap.DapModels.HINT_LABEL.equals(f.hint())) {
                    name.getStyleClass().addAll("debug-frame-nosource", "debug-frame-fold"); // "await": a label
                    setGraphic(flow);
                    return;
                }
                if (!hasSource.test(f)) {
                    name.getStyleClass().add("debug-frame-nosource"); // the JDK, a library, a lambda proxy
                }
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
        stackHint.getStyleClass().add("debug-empty-hint");
        stackHint.setWrapText(true);
        stack.setPlaceholder(stackHint);
        stack.getSelectionModel().selectedItemProperty().addListener((o, a, f) -> {
            if (f != null && !settingStack) {
                if (StackFolding.isFold(f)) {
                    // Shows the folded frames, keeping the frame that was selected selected.
                    javafx.application.Platform.runLater(() -> expandFrames(a));
                    return;
                }
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
            if (f != null
                    && f == pressedOn[0]
                    && !StackFolding.isFold(f)
                    && e.getButton() == javafx.scene.input.MouseButton.PRIMARY) {
                reportFrame(f);
            }
        });
        // Call-stack context menu (Restart Frame), built for the frame it opens on; the keyboard's menu key too.
        javafx.scene.control.ContextMenu stackMenu = new javafx.scene.control.ContextMenu();
        stack.setOnContextMenuRequested(e -> {
            DapModels.StackFrameInfo f = stack.getSelectionModel().getSelectedItem();
            List<javafx.scene.control.MenuItem> items = new java.util.ArrayList<>(
                    f == null || StackFolding.isFold(f) ? List.of() : actions.stackMenuItems(f, allFrames));
            if (!allFrames.isEmpty()) {
                if (!items.isEmpty()) {
                    items.add(new javafx.scene.control.SeparatorMenuItem());
                }
                javafx.scene.control.MenuItem copy = new javafx.scene.control.MenuItem(tr("debugpanel.copyCallStack"));
                copy.setGraphic(Icons.copy());
                copy.setOnAction(ev -> copyCallStack());
                items.add(copy);
            }
            stackMenu.getItems().setAll(items);
            if (!items.isEmpty()) {
                stackMenu.show(stack, e.getScreenX(), e.getScreenY());
            }
            e.consume();
        });

        stack.setOnKeyPressed(e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.C && e.isShortcutDown() && !allFrames.isEmpty()) {
                copyCallStack();
                e.consume();
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
                setGraphic(empty || row == null ? null : renderRow(row, changed(getTreeItem(), row)));
            }
        });
        // Watches interactions: double-click "+ Add watch…" adds, double-click a watch edits; the
        // context menu mirrors them (rebuilt per show for the selected row's kind).
        variables.setOnMouseClicked(e -> {
            TreeItem<VarRow> clicked = variables.getSelectionModel().getSelectedItem();
            if (e.getClickCount() == 1
                    && clicked != null
                    && clicked.getValue() != null
                    && clicked.getValue().notLoaded()) {
                loadRow(clicked, false); // "click to load" (B201)
                return;
            }
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
            } else if (e.getCode() == javafx.scene.input.KeyCode.ENTER
                    && selectedRow() != null
                    && selectedRow().notLoaded()) {
                loadRow(variables.getSelectionModel().getSelectedItem(), false);
                e.consume();
            } else if (e.getCode() == javafx.scene.input.KeyCode.F2) {
                VarRow row = selectedRow();
                if (row != null && row.kind() == Kind.VARIABLE && row.parentRef() > 0) {
                    setValuePrompt();
                    e.consume();
                }
            } else if (e.getCode() == javafx.scene.input.KeyCode.C && e.isShortcutDown() && selectedRow() != null) {
                copyToClipboard(DebugPanelText.copyValue(selectedRow().value())); // review C7
                e.consume();
            } else if (e.getCode() == javafx.scene.input.KeyCode.DELETE
                    && selectedRow() != null
                    && selectedRow().kind() == Kind.WATCH) {
                removeWatch(selectedRow().name());
                e.consume();
            }
        });
        // Built for the row it opens on. ContextMenu.show() returns before ON_SHOWING when it has no items, so an
        // empty menu here never opened at all (review C101): a placeholder stands in until the first rebuild.
        javafx.scene.control.ContextMenu varsMenu =
                new javafx.scene.control.ContextMenu(new javafx.scene.control.MenuItem());
        varsMenu.setOnShowing(e -> rebuildVarsMenu(varsMenu));
        variables.setContextMenu(varsMenu);
        // From the Menu key or Shift+F10 it opens at the selected row, as in the other trees — not in the middle of
        // the tree (review 3, C207). A filter, so the control's own handler does not show it there first.
        variables.addEventFilter(javafx.scene.input.ContextMenuEvent.CONTEXT_MENU_REQUESTED, e -> {
            javafx.geometry.Bounds row = e.isKeyboardTrigger() ? RowContextMenu.selectedRowOnScreen(variables) : null;
            if (row != null) {
                e.consume();
                varsMenu.show(variables, row.getMinX() + RowContextMenu.anchorX(row.getWidth()), row.getMaxY());
            }
        });

        console.setEditable(false);
        // Wrapped: the console is a third of the strip, and a build error or the Attach hint was cut at its edge
        // with no way to read the rest but scrolling sideways (review 3, A104).
        console.setWrapText(true);
        console.getStyleClass().addAll("editor-area", "debug-console");
        installLinkClicks(); // click a stack-trace line → jump (review C21)
        // Tab leaves the read-only console for the evaluate field, Shift+Tab for Variables: the console's own Tab
        // handling kept keyboard focus in it for good (review C9).
        console.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.TAB && !e.isControlDown() && !e.isAltDown()) {
                (e.isShiftDown() || evalInput.isDisabled() ? variables : evalInput).requestFocus();
                e.consume();
            }
        });
        ConsoleNav.installShared(console); // configured-keymap scrolling while the console has focus
        evalInput.getStyleClass().add("debug-eval");
        evalInput.setPromptText(tr("debugpanel.evalPrompt"));
        evalInput.setOnAction(e -> runEval());
        evalCompletion = DebugCompletion.attach(evalInput, new DebugCompletion.Source() {
            @Override
            public boolean paused() {
                return lastState == DapManager.State.SUSPENDED && !inputMode();
            }

            @Override
            public void complete(String text, int caret, Consumer<List<DapModels.CompletionTarget>> cb) {
                actions.complete(text, caret, selectedFrameId, cb);
            }
        });
        evalInput.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            boolean up = e.getCode() == javafx.scene.input.KeyCode.UP;
            if ((up || e.getCode() == javafx.scene.input.KeyCode.DOWN) && !e.isControlDown() && !e.isAltDown()) {
                String text = up ? history.up(evalInput.getText()) : history.down();
                if (text != null) {
                    evalInput.setText(text);
                    evalInput.end();
                }
                e.consume();
            }
        });
        // Ctrl+D ends the program's input, as in a terminal — only while the field is the program's input.
        evalInput.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.D
                    && e.isControlDown()
                    && !e.isAltDown()
                    && !e.isMetaDown()
                    && !e.isShiftDown()
                    && inputMode()) {
                e.consume();
                actions.endInput();
            }
        });

        // The thread selector shares the header's row: on its own row it took a third of the default strip.
        Label stackLabel = sectionLabel("debugpanel.callStack");
        stackLabel.setMinWidth(Region.USE_PREF_SIZE); // the thread selector shrinks first ("Call St…", review C16)
        stackTitle = stackLabel;
        threadsToggle.setMinWidth(Region.USE_PREF_SIZE);
        Region threadsGap = spacer();
        threadsGap.visibleProperty().bind(threadsToggle.selectedProperty());
        threadsGap.managedProperty().bind(threadsToggle.selectedProperty());
        HBox stackHeader = new HBox(6, stackLabel, threads, threadsGap, threadsToggle);
        stackHeader.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(threads, Priority.ALWAYS);
        javafx.scene.layout.StackPane stackArea = new javafx.scene.layout.StackPane(stack, threadList);
        VBox stackBox = new VBox(2, stackHeader, stackArea);
        VBox.setVgrow(stackArea, Priority.ALWAYS);
        Label varsHeader = sectionLabel("debugpanel.variables");
        varsFilter.setPromptText(tr("debugpanel.filterPrompt"));
        varsFilter.setPrefColumnCount(12);
        varsFilter.getStyleClass().addAll("debug-eval", "debug-vars-filter");
        varsFilter.textProperty().addListener((o, was, now) -> applyFilter());
        varsFilter.setOnKeyPressed(e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE
                    && !varsFilter.getText().isEmpty()) {
                varsFilter.clear();
                e.consume();
            } else if (e.getCode() == javafx.scene.input.KeyCode.DOWN) {
                variables.requestFocus();
                if (variables.getSelectionModel().isEmpty()) {
                    variables.getSelectionModel().selectFirst();
                }
                e.consume();
            }
        });
        HBox varsHeaderRow = new HBox(6, varsHeader, spacer(), varsFilter);
        varsHeaderRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        VBox varsBox = new VBox(2, varsHeaderRow, variables);
        VBox.setVgrow(variables, Priority.ALWAYS);

        VirtualizedScrollPane<CodeArea> consoleScroll = new VirtualizedScrollPane<>(console);
        VBox consoleBox = new VBox(2, sectionLabel("debugpanel.console"), consoleScroll, evalInput);
        VBox.setVgrow(consoleScroll, Priority.ALWAYS);
        javafx.scene.control.ContextMenu consoleMenu = new javafx.scene.control.ContextMenu();
        javafx.scene.control.MenuItem clear = new javafx.scene.control.MenuItem(tr("debugpanel.clearConsole"));
        clear.setGraphic(Icons.remove());
        clear.setOnAction(e -> clearConsole());
        javafx.scene.control.MenuItem endInput = new javafx.scene.control.MenuItem(tr("debugpanel.endInput"));
        endInput.setOnAction(e -> actions.endInput());
        consoleMenu.setOnShowing(e -> endInput.setDisable(!inputMode()));
        consoleMenu.getItems().addAll(clear, endInput);
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
        // Variables gets the widest column: it is what a stop is read from (review C4).
        areas.setDividerPositions(sideBySide ? new double[] {0.24, 0.64} : new double[] {0.25, 0.65});
    }

    private void reportFrame(DapModels.StackFrameInfo frame) {
        if (lastState != DapManager.State.SUSPENDED) {
            return; // the frames of the previous stop: the program has moved on, there is nothing to show
        }
        selectedFrameId = frame.id();
        int index = allFrames.indexOf(frame);
        valueChanges.setFrame(DebugValueChanges.frameKey(
                selectedThreadId(),
                frame.name(),
                frame.file() == null ? null : frame.file().toString(),
                index < 0 ? -1 : allFrames.size() - index));
        actions.selectFrame(frame);
    }

    /**
     * Marks the runs drawn in the editor theme's token colours. Those colours are made for the editor's background;
     * on a light UI with a dark editor theme, or the other way round, they can vanish into the panel's own (review
     * 2 C111), so {@link #setPlainValueColours} draws them in the UI's text colour instead.
     */
    static final String TOKEN = "debug-token";

    private static final javafx.css.PseudoClass PLAIN_VALUES = javafx.css.PseudoClass.getPseudoClass("plain-values");

    /** Draws names and values in the UI's text colours instead of the editor theme's (see {@link #TOKEN}). */
    public void setPlainValueColours(boolean plain) {
        pseudoClassStateChanged(PLAIN_VALUES, plain);
        ConsoleColours.apply(console, plain); // the console's own lines too (review 3, C205)
    }

    /** Whether {@code node} (the Debug window, or the value popup's root) draws values in plain colours. */
    static void setPlainValueColours(Node node, boolean plain) {
        node.pseudoClassStateChanged(PLAIN_VALUES, plain);
    }

    /**
     * Pure: whether the editor theme's token colours need the plain fallback — the UI theme and the editor theme
     * disagree on light vs dark, judged by their backgrounds.
     */
    static boolean themesDisagree(javafx.scene.paint.Color uiBackground, javafx.scene.paint.Color editorBackground) {
        return (uiBackground.getBrightness() < 0.5) != (editorBackground.getBrightness() < 0.5);
    }

    /** Builds the styled TextFlow for one variables-tree row. */
    static TextFlow renderRow(VarRow row) {
        return renderRow(row, false);
    }

    /** {@link #renderRow(VarRow)}, highlighted when {@code changed}: its value differs from the previous stop's. */
    static TextFlow renderRow(VarRow row, boolean changed) {
        TextFlow flow = new TextFlow();
        if (changed) {
            flow.getStyleClass().add("debug-row-changed");
            flow.setAccessibleHelp(tr("debugpanel.valueChanged"));
        }
        switch (row.kind()) {
            case SCOPE -> {
                Text name = new Text(row.name());
                name.getStyleClass().add("debug-scope-row");
                flow.getChildren().add(name);
            }
            case EXCEPTION -> {
                Text text = new Text(row.name());
                text.getStyleClass().add("debug-val-error");
                flow.getChildren().add(text);
            }
            case ADD_WATCH, MORE -> {
                Text add = new Text(row.name());
                add.getStyleClass().add("debug-add-watch");
                flow.getChildren().add(add);
            }
            case VARIABLE, WATCH -> {
                // Name, value and type in the editor's syntax token colours (.text.<class> from the active
                // editor theme), so a value reads like the code it came from.
                Text name = new Text(row.name());
                name.getStyleClass().addAll("text", "variable", TOKEN);
                if (row.kind() == Kind.VARIABLE
                        && row.value().isEmpty()
                        && row.ref() > 0
                        && (row.type() == null || row.type().isBlank())) {
                    flow.getChildren().add(name); // a group (debugpy's "special variables"), not "name = "
                    return flow;
                }
                if (row.kind() == Kind.WATCH && row.value().isEmpty()) {
                    flow.getChildren().add(name); // not paused: no value, and no dangling " = " (review C19)
                    return flow;
                }
                Text eq = new Text(" = ");
                eq.getStyleClass().add("debug-var-eq");
                flow.getChildren().addAll(name, eq);
                if (row.failed()) {
                    // A watch that did not evaluate shows the adapter's message where its value would be: it
                    // must not be coloured as one ("Cannot evaluate…" is not a string or a number).
                    // On one line: wrapped, it drew over the rows below (review C17).
                    Text value = new Text(DebugPanelText.oneLine(row.value(), 160));
                    value.getStyleClass().add("debug-val-error");
                    flow.getChildren().add(value);
                } else {
                    for (DebugValues.Run run : DebugValues.runs(DebugValues.display(row.value()))) {
                        Text value = new Text(run.text());
                        value.getStyleClass().add("debug-val");
                        if (run.token() != null) {
                            value.getStyleClass().addAll("text", run.token(), TOKEN);
                        } else {
                            value.getStyleClass().add("debug-val-plain");
                        }
                        flow.getChildren().add(value);
                    }
                }
                if (row.note() != null) {
                    // Why the row shows only Type@id: not loaded yet (a click loads it), or no toString() (B201/B202).
                    Text note = new Text("  " + DebugPanelText.valueNote(row.note()));
                    note.getStyleClass().add("debug-val-note");
                    flow.getChildren().add(note);
                }
                if (row.type() != null && !row.type().isBlank()) {
                    Text type = new Text("  " + row.type());
                    type.getStyleClass().addAll("text", "type", TOKEN);
                    flow.getChildren().add(type);
                }
            }
        }
        flow.setMinWidth(Region.USE_PREF_SIZE); // one line per row; a long value scrolls rather than wraps
        return flow;
    }

    private Label sectionLabel(String key) {
        Label l = new Label(tr(key));
        l.getStyleClass().add("debug-section");
        return l;
    }

    /** An icon-only toolbar button: the command title, its keymap chord and its panel letter live in the
     *  tooltip (IntelliJ-style); the title is also its accessible name, for a screen reader (review C9). */
    private Button btn(Button b, DebugPanelText.Control control, Runnable action, Node icon) {
        b.setGraphic(icon);
        b.setTooltip(new Tooltip());
        b.getProperties().put(DebugPanelText.Control.class, control);
        b.getStyleClass().addAll("flat", "debug-toolbar-button");
        // Not a Tab stop: a click would then put focus in the panel, where the letters are commands.
        b.setFocusTraversable(false);
        b.setOnAction(e -> action.run());
        return b;
    }

    /** The toolbar tooltips and accessible names, from the keymap (and Start / Continue as the state says). */
    private void refreshTooltips() {
        for (Button b : List.of(start, pause, stop, restart, stepOver, stepInto, stepOut, runToCursor)) {
            if (!(b.getProperties().get(DebugPanelText.Control.class) instanceof DebugPanelText.Control c)) {
                continue; // every toolbar button is made by btn(), which records its control
            }
            String id = c == DebugPanelText.Control.START && lastState == DapManager.State.SUSPENDED
                    ? "debug.continue"
                    : c.commandId;
            String title = DebugPanelText.bareTitle(tr("command." + id));
            b.getTooltip()
                    .setText(DebugPanelText.tooltip(
                            title,
                            DebugPanelText.chordOf(chordFor::apply, c.commandId),
                            c.keyLabel(),
                            tr("debugpanel.inPanel")));
            b.setAccessibleText(title);
        }
        stackHint.setText(lastState == DapManager.State.INACTIVE ? emptyHint() : "");
    }

    /** What the empty call stack says while there is no session: how to start one (review C22). */
    private String emptyHint() {
        if (preparing) {
            return tr("debugpanel.preparingHint"); // the launch is on its way: Stop cancels it (review 3, C204)
        }
        String chord = chordFor.apply("debug.start");
        boolean bound = chord != null && !chord.isBlank();
        if (exitCode != null) {
            // The program just ended (the state line says how): how to go again, not how to set a breakpoint
            // beside the ones already set (review 2 C110).
            return bound ? tr("debugpanel.endedHintChord", chord) : tr("debugpanel.endedHint");
        }
        return bound ? tr("debugpanel.emptyHintChord", chord) : tr("debugpanel.emptyHint");
    }

    /** The keymap chord of a command id ("" when unbound): the toolbar tooltips name it (review C18). */
    public void setChordLookup(java.util.function.Function<String, String> chordFor) {
        this.chordFor = chordFor == null ? id -> "" : chordFor;
        refreshTooltips();
    }

    /** Whether a frame's source can be shown; frames without are drawn muted. */
    public void setSourceCheck(java.util.function.Predicate<DapModels.StackFrameInfo> hasSource) {
        this.hasSource = hasSource == null ? f -> f.file() != null : hasSource;
        stack.refresh();
    }

    /**
     * gdb/lldb-style single-key control while the Debug panel is focused: c=continue, p=pause, k=kill/stop,
     * r=restart, n=next/step-over, s=step-into, f=finish/step-out, u=until/run-to-cursor. Fires the matching
     * toolbar button (respecting its enabled state) and swallows the key so the stack/variables lists don't
     * treat it as type-ahead. Exempts the evaluate REPL field and any modified chord so normal typing and
     * global chords (e.g. C-x o to leave the panel) still work.
     */
    private void handleDebugKey(KeyEvent e) {
        if (evalInput.isFocused() || e.getTarget() == evalInput || e.getTarget() instanceof TextField) {
            return; // typing an expression in the REPL, or a filter for Variables
        }
        // Modified chords (incl. C-x o focus cycling) go to the global dispatcher; Stop and Restart need Shift.
        DebugPanelText.Control control = DebugPanelText.controlFor(
                e.getCode(),
                e.isShiftDown(),
                e.isControlDown() || e.isAltDown() || e.isMetaDown() || e.isShortcutDown());
        if (control == null) {
            return;
        }
        Button target = switch (control) {
            case START -> start;
            case PAUSE -> pause;
            case STOP -> stop;
            case RESTART -> restart;
            case STEP_OVER -> stepOver;
            case STEP_INTO -> stepInto;
            case STEP_OUT -> stepOut;
            case RUN_TO_CURSOR -> runToCursor;
        };
        if (!target.isDisabled()) {
            target.fire();
        } else if (target == restart && restartBlocked != null && lastState != DapManager.State.INACTIVE) {
            actions.restart(); // from the keyboard there is no tooltip to hover: the action says why not
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
        boolean wasRunning = lastState == DapManager.State.RUNNING;
        if (state == DapManager.State.SUSPENDED && lastState != DapManager.State.SUSPENDED) {
            valueChanges.newStop(); // this stop's values are compared with the ones shown before it
        } else if (state == DapManager.State.STARTING || state == DapManager.State.INACTIVE) {
            valueChanges.reset();
        }
        lastState = state;
        outputTrailing = false;
        if (state == DapManager.State.STARTING) {
            refused = null;
            exitCode = null; // a new session: the last one's outcome is no longer what the status says
            ansiParsers.clear(); // and none of its colours is still on
        }
        if (state != DapManager.State.SUSPENDED) {
            exception = null;
        }
        boolean suspended = state == DapManager.State.SUSPENDED;
        if (suspended || state == DapManager.State.INACTIVE) {
            evalHintShown = false;
        }
        boolean running = state == DapManager.State.RUNNING;
        boolean active = state != DapManager.State.INACTIVE;
        if (!(running && wasRunning)) {
            runToken++; // a continued event while it runs (js-debug stepping through skipped code) is no change
        }
        if (running && !wasRunning) {
            // A step's stop comes within a moment, and the stack stays meanwhile (no flicker). A program still
            // running after that ran on — a Step out of a callback into code it skips — and the old stack,
            // variables and "(paused)" no longer describe it (review D107).
            long token = runToken;
            javafx.animation.PauseTransition later = new javafx.animation.PauseTransition(STALE_STOP_DELAY);
            later.setOnFinished(e -> {
                if (token == runToken && lastState == DapManager.State.RUNNING) {
                    clearStaleStop();
                }
            });
            later.play();
        }
        // Green play = Start when idle, Continue when paused; Pause is its complement while running.
        start.setDisable(preparing || !(state == DapManager.State.INACTIVE || suspended));
        pause.setDisable(!running);
        stepOver.setDisable(!suspended);
        stepInto.setDisable(!suspended);
        stepOut.setDisable(!suspended);
        runToCursor.setDisable(!suspended);
        stop.setDisable(!active && !preparing);
        restart.setDisable(!active || restartBlocked != null);
        // Usable while the program runs as well as while it is paused (Enter only evaluates when paused):
        // disabling the field on every Step/Continue made JavaFX move focus out of the panel, the next stop
        // then handed focus to the editor, and the expression being typed went into the source file.
        evalInput.setDisable(!(suspended || running));
        if (state == DapManager.State.INACTIVE || state == DapManager.State.STARTING) {
            programInput = false; // the program of the session that ended; the next one says so itself
        }
        refreshInputPrompt();
        threads.setDisable(!suspended);
        if (threadsShown()) {
            refreshThreads(); // which threads are suspended changed with the session's state
        }
        if (!active) {
            sessionFile = "";
            setRuntime(null);
            stack.getItems().clear();
            allFrames = List.of();
            stoppedIn = "";
            variables.setRoot(idleRoot());
            setThreads(List.of(), -1);
            selectedFrameId = -1;
        }
        refreshStatus();
        refreshTooltips();
    }

    /**
     * A before-launch build is running for a debug launch: there is no session yet, but Stop must be able to
     * cancel the build and Start must not begin a second one.
     */
    public void setPreparing(boolean preparing) {
        setPreparing(preparing, null);
    }

    /**
     * As {@link #setPreparing(boolean)}, with the state line to show meanwhile ({@code null}: "Building…"). It said
     * "Not debugging", or the last session's "Exited with code 0", beside the spinner (review 3, C204).
     */
    public void setPreparing(boolean preparing, @Nullable String text) {
        this.preparing = preparing;
        preparingText = text;
        if (preparing) {
            refused = null;
        }
        boolean active = lastState != DapManager.State.INACTIVE;
        start.setDisable(preparing || !(!active || lastState == DapManager.State.SUSPENDED));
        stop.setDisable(!active && !preparing);
        refreshStatus();
        refreshTooltips(); // the call stack's hint: how to cancel, not how to start
    }

    /** The state line while {@link #preparing} with no session yet; null for "Building…". */
    private @Nullable String preparingText;

    /** The runtime the session's program runs under, once its probe answers; {@code null} hides the chip. */
    public void setRuntime(com.editora.run.@Nullable RuntimeVersion version) {
        RunPanel.showRuntime(runtime, version);
    }

    /** The runtime chip's text, or "" while it is hidden. */
    String runtimeText() {
        return runtime.isVisible() ? runtime.getText() : "";
    }

    /** Records the file the session is debugging; shown beside the state while the session lives. */
    public void setSessionFile(String fileName) {
        this.sessionFile = fileName == null ? "" : fileName;
        refreshStatus();
    }

    /**
     * A launch that never started — its build failed — says so in the state line ({@code text}) until the next
     * launch: the last session's "Exited with code 0" stood beside the build's errors (review 2, A105).
     */
    public void setLaunchRefused(@Nullable String text) {
        refused = text == null || text.isBlank() ? null : text;
        if (refused != null) {
            sessionFile = "";
        }
        refreshStatus();
    }

    /** The state line of a launch its build stopped ({@link #setLaunchRefused}); null otherwise. */
    private @Nullable String refused;

    private void refreshStatus() {
        if (refused != null && lastState == DapManager.State.INACTIVE) {
            status.setText(refused);
            RunPanel.showSpinner(busy, preparing);
            return;
        }
        String state = stateText();
        if (breakpointsMuted) {
            state = state + " · " + tr("debugpanel.muted");
        }
        // The console keeps only its last characters; say so, as the Run console does (review E107).
        String trimNote = appender.trimmed() ? " " + tr("run.outputTrimmed", MAX_CONSOLE_CHARS) : "";
        status.setText((sessionFile.isEmpty() ? state : state + " — " + sessionFile) + trimNote);
        RunPanel.showSpinner(
                busy, preparing || lastState == DapManager.State.STARTING || lastState == DapManager.State.RUNNING);
    }

    /** The state line's state: how far the session has got, or the launch getting ready for it. */
    private String stateText() {
        boolean idle = lastState == DapManager.State.INACTIVE;
        if (stoppedOnException && lastState == DapManager.State.SUSPENDED) {
            return stoppedException.isEmpty()
                    ? tr("debugpanel.state.exception")
                    : tr("debugpanel.state.exceptionNamed", stoppedException);
        }
        if (stoppedOnMember != null && lastState == DapManager.State.SUSPENDED) {
            return tr(stoppedOnMember); // java-debug's own words for these stops: "function"/"data breakpoint"
        }
        if (lastState == DapManager.State.RUNNING && outputTrailing) {
            return tr("debugpanel.state.ending"); // the program has ended; its last output is arriving (choice 27)
        }
        if (idle && preparing) {
            return preparingText != null ? preparingText : tr("debugpanel.state.building"); // review 3, C204
        }
        if (idle && exitCode != null) {
            return tr("debugpanel.state.exited", exitCode);
        }
        if (lastState == DapManager.State.SUSPENDED && !stoppedIn.isEmpty() && !stoppedIn.equals(sessionFile)) {
            return tr("debugpanel.state.suspendedIn", stoppedIn); // a child process or worker, not its file (D109)
        }
        return tr("debugpanel.state." + lastState.name().toLowerCase(java.util.Locale.ROOT));
    }

    /**
     * Restart cannot repeat the session there is (it is attached to a build or test run): the button is
     * disabled and {@code reason} explains it on hover. {@code null} makes Restart follow the session state.
     */
    public void setRestartBlocked(@Nullable String reason) {
        String blocked = reason == null || reason.isBlank() ? null : reason;
        if (java.util.Objects.equals(blocked, restartBlocked)) {
            return;
        }
        restartBlocked = blocked;
        if (restartBlockedTip != null) {
            Tooltip.uninstall(restartHolder, restartBlockedTip);
            restartBlockedTip = null;
        }
        if (blocked != null) {
            restartBlockedTip = new Tooltip(blocked);
            Tooltip.install(restartHolder, restartBlockedTip);
        }
        restart.setAccessibleHelp(blocked);
        restart.setDisable(lastState == DapManager.State.INACTIVE || blocked != null);
    }

    /** Names the exception of the current exception stop ("IllegalStateException: boom") in the state line. */
    public void setStoppedException(String summary) {
        stoppedException = summary == null ? "" : summary;
        refreshStatus();
    }

    /**
     * The exception of the current exception stop, shown as the first row of Variables with its message under it
     * (review C13): the state line alone was all an exception stop said.
     */
    public void setExceptionInfo(DapModels.ExceptionInfo info) {
        exception = info == null || info.isEmpty() ? null : info;
        TreeItem<VarRow> root = variables.getRoot();
        if (root == null) {
            return;
        }
        root.getChildren().removeIf(i -> i.getValue() != null && i.getValue().kind() == Kind.EXCEPTION);
        if (exception != null) {
            root.getChildren().addFirst(exceptionNode(exception));
        }
    }

    private static TreeItem<VarRow> exceptionNode(DapModels.ExceptionInfo info) {
        String type = info.type().isEmpty() ? tr("debugpanel.exception") : info.type();
        TreeItem<VarRow> node =
                new TreeItem<>(new VarRow(tr("debugpanel.exceptionRow", type), "", "", 0, 0, Kind.EXCEPTION));
        for (String line : info.message().split("\\R")) {
            if (!line.isBlank()) {
                node.getChildren().add(new TreeItem<>(new VarRow(line.strip(), "", "", 0, 0, Kind.EXCEPTION)));
            }
        }
        node.setExpanded(true);
        return node;
    }

    /** Mute Breakpoints turned on or off (the state line says "breakpoints muted" while it is on). */
    public void setBreakpointsMuted(boolean muted) {
        breakpointsMuted = muted;
        refreshStatus();
    }

    /** The program of the session that is ending exited with {@code code}: the state line keeps saying so. */
    public void setExitCode(int code) {
        exitCode = code;
        refreshStatus();
        refreshTooltips();
    }

    /** How long a resumed program runs before the last stop's stack and variables are cleared. */
    static final javafx.util.Duration STALE_STOP_DELAY = javafx.util.Duration.millis(600);

    /** Counts state changes, so a pending stale-stop clear knows it was overtaken. */
    private long runToken;

    /** The program runs on: no stack, the Watches alone, and no thread marked "(paused)". */
    private void clearStaleStop() {
        stack.getItems().clear();
        allFrames = List.of();
        variables.setRoot(idleRoot());
        selectedFrameId = -1;
        settingThreads = true;
        try {
            stoppedThreadId = -1;
            DapModels.ThreadInfo shown = threads.getValue();
            threads.setValue(null);
            threads.setValue(shown); // redrawn without "(paused)"
        } finally {
            settingThreads = false;
        }
    }

    // --- Threads view -------------------------------------------------------------------------------------

    private void buildThreadsView() {
        threadsToggle.getStyleClass().addAll("debug-threads-toggle", "small");
        threadsToggle.setFocusTraversable(false);
        threadsToggle.setTooltip(new javafx.scene.control.Tooltip(tr("debugpanel.threadsViewTip")));
        threadsToggle.selectedProperty().addListener((o, was, on) -> showThreadsView(on));
        threadList.getStyleClass().add("debug-stack");
        threadList.setVisible(false);
        Label empty = new Label(tr("debugpanel.noThreads"));
        empty.getStyleClass().add("debug-empty-hint");
        empty.setWrapText(true);
        threadList.setPlaceholder(empty);
        threadList.setCellFactory(v -> new ListCell<>() {
            @Override
            protected void updateItem(DapModels.ThreadStatus t, boolean empty) {
                super.updateItem(t, empty);
                setText(null);
                if (empty || t == null) {
                    setGraphic(null);
                    return;
                }
                Text name = new Text((t.shown() ? "▶ " : "") + DebugPanelText.threadName(t.name()));
                name.getStyleClass().add("debug-frame-name");
                if (t.shown()) {
                    name.getStyleClass().add("debug-thread-shown");
                }
                Text state = new Text("  " + tr(DebugPanelText.threadStateKey(t)));
                state.getStyleClass().add(t.paused() ? "debug-thread-paused" : "debug-frame-loc");
                setGraphic(new TextFlow(name, state));
            }
        });
        threadList.setOnMouseClicked(e -> {
            if (e.getButton() == javafx.scene.input.MouseButton.PRIMARY && e.getClickCount() == 2) {
                showSelectedThread();
            }
        });
        threadList.setOnKeyPressed(e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.ENTER) {
                showSelectedThread();
                e.consume();
            }
        });
        javafx.scene.control.ContextMenu menu = new javafx.scene.control.ContextMenu();
        menu.setOnShowing(e -> rebuildThreadMenu(menu));
        threadList.setContextMenu(menu);
        threadsRefresh.setOnFinished(e -> refreshThreads());
    }

    /** The thread menu for the selected row: Show Frames and Resume Thread when it is suspended, else Pause Thread. */
    private void rebuildThreadMenu(javafx.scene.control.ContextMenu menu) {
        menu.getItems().clear();
        DapModels.ThreadStatus t = threadList.getSelectionModel().getSelectedItem();
        if (t == null) {
            return;
        }
        if (t.paused()) {
            javafx.scene.control.MenuItem show = new javafx.scene.control.MenuItem(tr("debugpanel.showFrames"));
            show.setOnAction(e -> showSelectedThread());
            javafx.scene.control.MenuItem resume = new javafx.scene.control.MenuItem(tr("debugpanel.resumeThread"));
            resume.setGraphic(Icons.run());
            resume.setOnAction(e -> actions.resumeThread(t.id()));
            menu.getItems().addAll(show, resume);
        } else {
            javafx.scene.control.MenuItem pause = new javafx.scene.control.MenuItem(tr("debugpanel.pauseThread"));
            pause.setGraphic(Icons.debugPause());
            pause.setOnAction(e -> actions.pauseThread(t.id()));
            menu.getItems().add(pause);
        }
    }

    /** Shows the frames of the selected thread when it is suspended (Enter, a double-click, Show Frames). */
    private void showSelectedThread() {
        DapModels.ThreadStatus t = threadList.getSelectionModel().getSelectedItem();
        if (t == null || !t.paused()) {
            return;
        }
        threadsToggle.setSelected(false);
        if (!t.shown()) {
            actions.showThread(t);
        }
    }

    /** Whether the Threads view is shown in place of the frames. */
    boolean threadsShown() {
        return threadsToggle.isSelected();
    }

    /** Shows the Threads view ({@code true}) or the frames. */
    void showThreadsView(boolean on) {
        if (threadsToggle.isSelected() != on) {
            threadsToggle.setSelected(on); // comes back here through its listener
            return;
        }
        threadList.setVisible(on);
        stack.setVisible(!on);
        threads.setVisible(!on);
        threads.setManaged(!on);
        if (stackTitle != null) {
            stackTitle.setText(tr(on ? "debugpanel.threadsTitle" : "debugpanel.callStack"));
        }
        if (on) {
            refreshThreads();
            threadList.requestFocus();
        } else {
            threadsRefresh.stop();
        }
    }

    /** A thread started or ended: the Threads view, when shown, lists them again (at most every 250 ms). */
    public void threadsChanged() {
        if (threadsShown()) {
            threadsRefresh.playFromStart();
        }
    }

    /** Lists the threads again, keeping the selected one selected. */
    void refreshThreads() {
        if (lastState == DapManager.State.INACTIVE) {
            threadList.getItems().clear();
            return;
        }
        actions.threadStatuses(list -> {
            DapModels.ThreadStatus selected = threadList.getSelectionModel().getSelectedItem();
            List<DapModels.ThreadStatus> shown = DebugPanelText.visibleThreadStatuses(list);
            threadList.getItems().setAll(shown);
            if (selected != null) {
                for (DapModels.ThreadStatus t : shown) {
                    if (t.id() == selected.id()) {
                        threadList.getSelectionModel().select(t);
                        break;
                    }
                }
            }
        });
    }

    /** The Threads view's list (test read). */
    ListView<DapModels.ThreadStatus> threadList() {
        return threadList;
    }

    /** Fills the thread dropdown and selects {@code currentThreadId} without re-fetching its stack. */
    public void setThreads(List<DapModels.ThreadInfo> list, int currentThreadId) {
        settingThreads = true;
        try {
            stoppedThreadId = currentThreadId;
            // The JVM's own housekeeping threads left out, the stopped one first and marked (review C15).
            list = DebugPanelText.visibleThreads(list, currentThreadId);
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
        allFrames = List.copyOf(frames);
        framesExpanded = false;
        showFrames(index);
        java.nio.file.Path at = frames.isEmpty() ? null : frames.get(index).file();
        // DebugValues.sourceName: a jdt:// frame's last segment is a piece of its query (review 2, B107).
        String in = at == null ? "" : DebugValues.sourceName(at);
        if (!in.equals(stoppedIn)) {
            stoppedIn = in;
            refreshStatus();
        }
        if (!frames.isEmpty()) {
            reportFrame(frames.get(index));
        }
    }

    /** The file of the frame the program stopped in ("" when none), named when it is not the session's file. */
    private String stoppedIn = "";

    /** Every frame of the stack shown, folded rows included in {@link StackFolding} form or not. */
    private List<DapModels.StackFrameInfo> allFrames = List.of();

    /** The call stack shown, top first (every frame, folded or not). */
    List<DapModels.StackFrameInfo> callStack() {
        return allFrames;
    }

    /** Selects the listed frame {@code frameId} (reported like a click); false when it is not listed. */
    boolean selectFrameById(int frameId) {
        for (DapModels.StackFrameInfo f : stack.getItems()) {
            if (f.id() == frameId && !StackFolding.isFold(f)) {
                stack.getSelectionModel().select(f);
                return true;
            }
        }
        return false;
    }

    /** Whether the user unfolded this stack's runtime frames (until the next stop). */
    private boolean framesExpanded;

    /** Lists {@link #allFrames} — folded unless {@link #framesExpanded} — with frame {@code index} selected. */
    private void showFrames(int index) {
        StackFolding.Shown shown = StackFolding.fold(allFrames, index, framesExpanded);
        settingStack = true;
        try {
            stack.getItems().setAll(shown.rows());
            if (!shown.rows().isEmpty()) {
                int row = shown.selectedRow();
                stack.getSelectionModel().clearAndSelect(row);
                if (row > 0) {
                    stack.scrollTo(row - 1); // the selected frame in view, with the one it was called from
                }
            }
        } finally {
            settingStack = false;
        }
    }

    /** A fold row was chosen: every frame is listed, {@code previous} (the frame selected before) still selected. */
    private void expandFrames(DapModels.@Nullable StackFrameInfo previous) {
        if (framesExpanded) {
            return;
        }
        framesExpanded = true;
        int index = previous == null ? 0 : Math.max(0, allFrames.indexOf(previous));
        showFrames(index);
    }

    /**
     * Why the program is suspended, as the adapter's stop event named it ({@code null} when it is not). Only an
     * exception stop is called out: it otherwise looks exactly like a breakpoint hit on the same line.
     */
    public void setStopReason(@Nullable String reason) {
        String member = "function breakpoint".equals(reason)
                ? "debugpanel.state.methodBreakpoint"
                : "data breakpoint".equals(reason) ? "debugpanel.state.fieldWatchpoint" : null;
        if (!java.util.Objects.equals(member, stoppedOnMember)) {
            stoppedOnMember = member;
            refreshStatus();
        }
        boolean exception = "exception".equals(reason);
        boolean named = !stoppedException.isEmpty();
        if (!exception) {
            this.exception = null; // the exception row belongs to the exception stop only
        }
        stoppedException = ""; // a new stop (or none): the exception of the previous one is not this one's
        if (exception != stoppedOnException || named) {
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
        int stopped = stoppedThreadId;
        setThreads(List.copyOf(threads.getItems()), threadId);
        stoppedThreadId = stopped;
    }

    /** Replaces the variables tree with the Watches node + the selected frame's scopes (each lazily
     *  expandable); watches re-evaluate against the newly selected frame. */
    public void setScopes(List<DapModels.ScopeInfo> scopes) {
        scopeRows.clear();
        rememberTreeState();
        TreeItem<VarRow> old = variables.getRoot();
        TreeItem<VarRow> root = new TreeItem<>();
        root.getChildren().add(buildWatchesNode());
        int offset = 1;
        if (exception != null) {
            root.getChildren().addFirst(exceptionNode(exception));
            offset = 2;
        }
        for (DapModels.ScopeInfo s : scopes) {
            TreeItem<VarRow> item = lazyItem(new VarRow(s.name(), "", "", s.variablesReference(), 0, Kind.SCOPE));
            root.getChildren().add(item);
        }
        variables.setRoot(root);
        // Expanded only once the scope is in the tree: its children load on expansion and restore the
        // remembered rows by their path from the root.
        for (int i = 0; i < scopes.size(); i++) {
            TreeItem<VarRow> item = root.getChildren().get(i + offset);
            Boolean was = scopeExpanded(old, scopes.get(i).name());
            item.setExpanded(was != null ? was : !scopes.get(i).expensive());
            restoreSelection(item);
        }
    }

    /** Whether the scope called {@code name} was expanded in the tree being replaced, or null if not there. */
    private static @Nullable Boolean scopeExpanded(TreeItem<VarRow> oldRoot, String name) {
        if (oldRoot != null) {
            for (int i = 0; i < oldRoot.getChildren().size(); i++) {
                TreeItem<VarRow> scope = oldRoot.getChildren().get(i);
                if (scope.getValue() != null
                        && scope.getValue().kind() == Kind.SCOPE
                        && scope.getValue().name().equals(name)) {
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

    /** Injects the Add / Edit Watch prompt whose field completes expressions; null falls back to the plain one. */
    public void setExpressionPrompt(OverlayInput.Prompt prompt) {
        this.expressionPrompt = prompt;
    }

    private OverlayInput.@Nullable Prompt watchPrompt() {
        return expressionPrompt != null ? expressionPrompt : prompt;
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
                                r.indexedVariables(),
                                r.failed()));
                        node.getChildren().set(idx, evaluated);
                        noteValue(evaluated);
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
        int watchesAt = 0;
        while (watchesAt < root.getChildren().size() - 1
                && root.getChildren().get(watchesAt).getValue() != null
                && root.getChildren().get(watchesAt).getValue().kind() == Kind.EXCEPTION) {
            watchesAt++;
        }
        root.getChildren().set(watchesAt, buildWatchesNode());
    }

    private @Nullable VarRow selectedRow() {
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
        if (row != null && (row.kind() == Kind.VARIABLE || row.kind() == Kind.WATCH || row.kind() == Kind.EXCEPTION)) {
            // Review C7: a value could not be copied at all, and Add Watch started from an empty prompt.
            javafx.scene.control.MenuItem copyValue = new javafx.scene.control.MenuItem(tr("debugpanel.copyValue"));
            copyValue.setOnAction(e ->
                    copyToClipboard(row.kind() == Kind.EXCEPTION ? row.name() : DebugPanelText.copyValue(row.value())));
            menu.getItems().add(copyValue);
            if (row.kind() != Kind.EXCEPTION) {
                javafx.scene.control.MenuItem copyName = new javafx.scene.control.MenuItem(tr("debugpanel.copyName"));
                copyName.setOnAction(e -> copyToClipboard(row.name()));
                menu.getItems().add(copyName);
            }
            if (row.kind() == Kind.VARIABLE) {
                String expression = pathExpression(variables.getSelectionModel().getSelectedItem());
                javafx.scene.control.MenuItem toWatches =
                        new javafx.scene.control.MenuItem(tr("debugpanel.addToWatches", expression));
                toWatches.setGraphic(Icons.newFile());
                toWatches.setOnAction(e -> addWatchPrompt(expression));
                menu.getItems().add(toWatches);
                if (row.parentRef() > 0 && actions.canWatchField()) {
                    // IntelliJ's and VS Code's "Break When Value Changes": a watchpoint on the field this row is
                    javafx.scene.control.MenuItem watchField =
                            new javafx.scene.control.MenuItem(tr("debugpanel.watchField"));
                    watchField.setOnAction(e -> actions.watchField(row.parentRef(), row.name()));
                    menu.getItems().add(watchField);
                }
            }
            menu.getItems().add(new javafx.scene.control.SeparatorMenuItem());
        }
        TreeItem<VarRow> selectedItem = variables.getSelectionModel().getSelectedItem();
        if (offersGoToIndex(selectedItem)) {
            javafx.scene.control.MenuItem goTo = new javafx.scene.control.MenuItem(tr("debugpanel.goToIndex"));
            goTo.setOnAction(e -> goToIndexPrompt(selectedItem));
            menu.getItems().addAll(goTo, new javafx.scene.control.SeparatorMenuItem());
        }
        javafx.scene.control.MenuItem add = new javafx.scene.control.MenuItem(tr("debugpanel.addWatch"));
        add.setGraphic(Icons.newFile());
        add.setOnAction(e -> addWatchPrompt());
        menu.getItems().add(add);
        javafx.scene.control.CheckMenuItem hex = new javafx.scene.control.CheckMenuItem(tr("debugpanel.hexValues"));
        hex.setSelected(actions.hexValues());
        hex.setOnAction(e -> toggleHexValues());
        menu.getItems().addAll(new javafx.scene.control.SeparatorMenuItem(), hex);
    }

    /**
     * Debug: Show Numbers as Hexadecimal — on or off. The values are fetched again; what this stop compares with is
     * forgotten, or every number would read as changed.
     */
    public void toggleHexValues() {
        valueChanges.reset();
        actions.setHexValues(!actions.hexValues());
    }

    private void addWatchPrompt() {
        addWatchPrompt("");
    }

    /** Add Watch, its prompt pre-filled with {@code initial} (a variable's expression). */
    void addWatchPrompt(String initial) {
        if (watchPrompt() == null) {
            return;
        }
        watchPrompt().show(tr("debugpanel.addWatchTitle"), tr("debugpanel.watchExpr"), initial, expr -> {
            if (expr != null && !expr.isBlank()) {
                watches.add(expr.strip());
                onWatchesChanged.run();
                refreshWatchesNode();
            }
        });
    }

    private void editWatchPrompt(String old) {
        if (watchPrompt() == null) {
            return;
        }
        watchPrompt().show(tr("debugpanel.editWatchTitle"), tr("debugpanel.watchExpr"), old, expr -> {
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
            actions.setVariable(row.parentRef(), row.name(), value, pathExpression(item), selectedFrameId, newValue -> {
                item.setValue(new VarRow(
                        row.name(),
                        newValue,
                        row.type(),
                        row.ref(),
                        row.parentRef(),
                        Kind.VARIABLE,
                        row.named(),
                        row.indexed()));
                if (!actions.refreshValues()) { // the inline values and other rows may show it too (B205)
                    refreshWatchesNode();
                }
            });
        });
    }

    /**
     * Pure-ish: an expression naming a variables-tree row from its scope down — {@code names} for a local,
     * {@code obj.field} for a field, {@code arr[3]} for an element (the prompt it pre-fills is editable).
     */
    static String pathExpression(TreeItem<VarRow> item) {
        java.util.LinkedList<String> parts = new java.util.LinkedList<>();
        for (TreeItem<VarRow> i = item; i != null && i.getValue() != null; i = i.getParent()) {
            VarRow r = i.getValue();
            if (r.kind() != Kind.VARIABLE && r.kind() != Kind.WATCH) {
                break;
            }
            parts.addFirst(r.name());
        }
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (sb.isEmpty()) {
                sb.append(part);
            } else if (part.chars().allMatch(Character::isDigit)) {
                sb.append('[').append(part).append(']');
            } else {
                sb.append('.').append(part);
            }
        }
        return sb.toString();
    }

    /** Copies the whole call stack shown — folded runtime frames included — to the clipboard. */
    public void copyCallStack() {
        if (allFrames.isEmpty()) {
            return;
        }
        DapModels.ThreadInfo thread = threads.getValue();
        copyToClipboard(DebugPanelText.callStackText(thread == null ? null : thread.name(), allFrames));
    }

    /** Puts {@code text} on the system clipboard. */
    static void copyToClipboard(String text) {
        javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
        content.putString(text == null ? "" : text);
        javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
    }

    /** The Variables tree's selected row, its value copied (the {@code debug.copyValue} command). */
    public void copySelectedValue() {
        VarRow row = selectedRow();
        if (row != null) {
            copyToClipboard(DebugPanelText.copyValue(row.value()));
        }
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
        String style = "stderr".equals(category)
                ? "run-stderr" // the program's stderr in red
                : DapManager.COMMAND_CATEGORY.equals(category) ? "run-command" : null; // Editora's own lines, muted
        if (DapManager.COMMAND_CATEGORY.equals(category) || text.indexOf('\u001b') < 0 && !ansiOpen(category)) {
            text = DebugValues.stripAnsi(text);
            appender.append(text, DebugConsoleStyle.spans(text, style));
            return;
        }
        // The program's colours, drawn as the Run console draws them (review D104); the parser keeps a colour
        // that is still on across output events, per stream.
        com.editora.run.AnsiText.Chunk chunk = ansiParser(category).feed(text);
        String shown = chunk.text();
        if (shown.indexOf(com.editora.run.AnsiText.CLEAR_SCREEN) >= 0) {
            shown = shown.replace(String.valueOf(com.editora.run.AnsiText.CLEAR_SCREEN), "");
            appender.append(shown, DebugConsoleStyle.spans(shown, style)); // no colours rather than wrong ones
            return;
        }
        appender.append(shown, DebugConsoleStyle.spans(shown, style, chunk.runs()));
    }

    /** One ANSI parser per output category, so a colour left on in stdout does not tint stderr. */
    private final java.util.Map<String, com.editora.run.AnsiText.Parser> ansiParsers = new java.util.HashMap<>();

    private com.editora.run.AnsiText.Parser ansiParser(String category) {
        return ansiParsers.computeIfAbsent(
                category == null ? "" : category, c -> new com.editora.run.AnsiText.Parser());
    }

    /** Whether a colour the program turned on in {@code category} is still on. */
    private boolean ansiOpen(String category) {
        com.editora.run.AnsiText.Parser p = ansiParsers.get(category == null ? "" : category);
        return p != null && p.active();
    }

    /**
     * Whether the debugged program can be typed to: Editora started it itself and its input is still open.
     * The one field below the console then does two jobs, told apart by the session state alone — while the
     * program <em>runs</em> a line goes to its standard input, while it is <em>paused</em> the line is an
     * expression to evaluate (a paused program reads nothing; resume it to type to it). The prompt text says
     * which of the two the field is at the moment.
     */
    public void setProgramInput(boolean available) {
        programInput = available;
        refreshInputPrompt();
    }

    /** The field is the program's standard input right now (see {@link #setProgramInput}). */
    private boolean inputMode() {
        return programInput && lastState == DapManager.State.RUNNING;
    }

    private void refreshInputPrompt() {
        evalInput.setPromptText(tr(inputMode() ? "debugpanel.inputPrompt" : "debugpanel.evalPrompt"));
    }

    /** The program's input was ended (end of file): said in the console, where the typed lines are echoed. */
    public void showInputEnded() {
        appendNotice(tr("debugpanel.inputEnded"));
    }

    /**
     * Appends a line of Editora's own to the console, on a line of its own: a program's output often stops
     * short of a newline (a prompt), and the notice must not read as part of it.
     */
    public void appendNotice(String line) {
        appendLine(line, "console");
    }

    private void appendLine(String line, String category) {
        appender.flush(); // the line break below is decided by what the console ends with
        int length = console.getLength();
        boolean midLine = length > 0 && !"\n".equals(console.getText(length - 1, length));
        appendOutput((midLine ? "\n" : "") + line + "\n", category);
    }

    /**
     * The program has ended and its output is still arriving (the program console off, a flood): one muted line says
     * so, and the state line no longer says Running, nor is Pause offered (review 3, choice 27).
     */
    public void showOutputTrailing() {
        appendLine(tr("debug.notice.outputTrailing"), DapManager.COMMAND_CATEGORY);
        outputTrailing = true;
        pause.setDisable(true);
        refreshStatus();
    }

    /** {@link #showOutputTrailing} was said for the session there is (reset by the next state change). */
    private boolean outputTrailing;

    private void runEval() {
        String expr = evalInput.getText();
        if (inputMode()) {
            // Standard input: every line counts, an empty one included, and the program does not echo it.
            String line = expr == null ? "" : expr;
            appendOutput(line + "\n", "console");
            evalInput.clear();
            actions.sendInput(line);
            return;
        }
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
        history.add(expr);
        // The echo muted, a failure in red: the evaluate lines looked exactly like the program's output (C20).
        appendOutput("> " + expr + "\n", DapManager.COMMAND_CATEGORY);
        evalInput.clear();
        actions.evaluate(expr, selectedFrameId, result -> {
            String text = result == null ? "" : result.text();
            boolean failed = result != null && result.failed();
            appendOutput(text + "\n", failed ? "stderr" : "console");
            if (!failed && lastState == DapManager.State.SUSPENDED) {
                actions.refreshValues(); // `cnt.inc()` changed what Variables, watches and inline values show (B205)
            }
        });
    }

    /** Values shown at earlier stops, to highlight the ones that changed. */
    private final DebugValueChanges valueChanges = new DebugValueChanges();

    /** Whether {@code row} has a real value to compare: not a placeholder, a failed watch or a row not loaded. */
    private static boolean comparable(@Nullable VarRow row) {
        return row != null
                && (row.kind() == Kind.VARIABLE || row.kind() == Kind.WATCH)
                && !row.failed()
                && row.note() == null
                && !row.value().isEmpty()
                && !"…".equals(row.value());
    }

    /**
     * Remembers the value {@code item} shows at this stop — only a row of the tree shown: an answer to the previous
     * stop's request that arrives after this stop began belongs to a tree already replaced, and holds old values.
     */
    private void noteValue(TreeItem<VarRow> item) {
        VarRow row = item.getValue();
        if (comparable(row) && lastState == DapManager.State.SUSPENDED && inShownTree(item)) {
            valueChanges.record(pathOf(item), row.value());
        }
    }

    private boolean inShownTree(TreeItem<VarRow> item) {
        TreeItem<VarRow> top = item;
        while (top.getParent() != null) {
            top = top.getParent();
        }
        return top == variables.getRoot();
    }

    /** Whether {@code item}'s value differs from what it showed at an earlier stop in the same frame. */
    boolean changed(@Nullable TreeItem<VarRow> item, VarRow row) {
        return item != null
                && comparable(row)
                && lastState == DapManager.State.SUSPENDED
                && valueChanges.changed(pathOf(item), row.value());
    }

    // --- filter + go to index ------------------------------------------------------------------------

    /** Typed text that narrows each scope to the variables whose name contains it (any case). */
    private final TextField varsFilter = new TextField();

    /** Every row loaded under each scope of the tree shown, filtered out or not (in load order). */
    private final java.util.IdentityHashMap<TreeItem<VarRow>, List<TreeItem<VarRow>>> scopeRows =
            new java.util.IdentityHashMap<>();

    /** The Variables filter field (tests, and Find in the Debug window). */
    TextField variablesFilter() {
        return varsFilter;
    }

    private boolean filtering() {
        return !varsFilter.getText().isBlank();
    }

    /** A scope of the tree shown whose rows the filter narrows: not Watches, whose rows the user chose. */
    private boolean filterable(TreeItem<VarRow> item) {
        VarRow row = item.getValue();
        return row != null
                && row.kind() == Kind.SCOPE
                && item.getParent() == variables.getRoot()
                && !item.getChildren().stream()
                        .anyMatch(c -> c.getValue() != null && c.getValue().kind() == Kind.ADD_WATCH);
    }

    private boolean matchesFilter(TreeItem<VarRow> item) {
        VarRow row = item.getValue();
        String text = varsFilter.getText().strip().toLowerCase(java.util.Locale.ROOT);
        return text.isEmpty()
                || row != null && row.name().toLowerCase(java.util.Locale.ROOT).contains(text);
    }

    /** Shows, under each scope, the rows the filter lets through (all of them when it is empty). */
    private void applyFilter() {
        TreeItem<VarRow> root = variables.getRoot();
        if (root == null) {
            return;
        }
        for (TreeItem<VarRow> scope : root.getChildren()) {
            List<TreeItem<VarRow>> all = scopeRows.get(scope);
            if (all == null) {
                continue; // not loaded yet: its rows are filtered as they arrive
            }
            var children = scope.getChildren();
            TreeItem<VarRow> more =
                    !children.isEmpty() && children.get(children.size() - 1) instanceof MoreItem m ? m : null;
            List<TreeItem<VarRow>> shown = new java.util.ArrayList<>();
            for (TreeItem<VarRow> row : all) {
                if (matchesFilter(row)) {
                    shown.add(row);
                }
            }
            if (more != null) {
                shown.add(more);
            }
            children.setAll(shown);
        }
    }

    /** Whether {@code item} is a loaded, expanded container long enough for Go to Index. */
    static boolean offersGoToIndex(@Nullable TreeItem<VarRow> item) {
        if (item == null) {
            return false;
        }
        VarRow row = item.getValue();
        return row != null
                && (row.kind() == Kind.VARIABLE || row.kind() == Kind.WATCH)
                && row.ref() > 0
                && row.indexed() > DebugValues.PAGE_SIZE
                && item.isExpanded()
                && !item.getChildren().isEmpty();
    }

    /** Asks for an index of the selected container and shows its elements from there. */
    private void goToIndexPrompt(TreeItem<VarRow> item) {
        VarRow row = item.getValue();
        OverlayInput.Prompt ask = prompt != null ? prompt : watchPrompt(); // a number, not an expression to complete
        if (ask == null || row == null) {
            return;
        }
        ask.show(
                tr("debugpanel.goToIndexTitle", row.name()),
                tr("debugpanel.goToIndexLabel", row.indexed() - 1),
                "",
                text -> {
                    Integer index = DebugPanelText.parseIndex(text, row.indexed());
                    if (index != null) {
                        goToIndex(item, index);
                    }
                });
    }

    /**
     * Shows {@code item}'s elements from {@code index} on, one page at a time as before; Go to Index 0 goes back to
     * the start. Its named children (a list's fields) are left out of that view.
     */
    void goToIndex(TreeItem<VarRow> item, int index) {
        VarRow row = item.getValue();
        if (row == null || lastState != DapManager.State.SUSPENDED || index < 0 || index >= row.indexed()) {
            return;
        }
        item.getChildren().clear();
        if (DebugValues.fetchedByPage(row.indexed())) {
            requestPage(item, row, index);
        } else {
            actions.loadChildren(row.ref(), all -> showPage(item, row, all, Math.min(index, all.size())));
        }
    }

    /** A tree item whose children are loaded on first expand (when {@code ref > 0}). */
    private TreeItem<VarRow> lazyItem(VarRow row) {
        TreeItem<VarRow> item = new TreeItem<>(row) {
            @Override
            public boolean isLeaf() {
                return row.ref() <= 0;
            }
        };
        if (row.ref() > 0 && row.notLoaded()) {
            // Its one child is the value: expanding it loads the row in place, then shows its fields (B201).
            item.expandedProperty().addListener((o, was, now) -> {
                if (now && lastState == DapManager.State.SUSPENDED) {
                    loadRow(item, true);
                }
            });
        } else if (row.ref() > 0) {
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
        children.forEach(this::noteValue);
        if (filterable(item)) {
            scopeRows.computeIfAbsent(item, k -> new java.util.ArrayList<>()).addAll(children);
            if (filtering()) {
                item.getChildren().removeIf(c -> !(c instanceof MoreItem) && !matchesFilter(c));
                children = children.stream().filter(this::matchesFilter).toList();
            }
        }
        children.forEach(this::restoreState);
        if (select && !children.isEmpty()) {
            variables.getSelectionModel().select(children.get(0));
        }
    }

    private List<TreeItem<VarRow>> rows(VarRow parent, List<DapModels.VariableInfo> vars) {
        List<TreeItem<VarRow>> out = new java.util.ArrayList<>(vars.size());
        for (DapModels.VariableInfo v : vars) {
            out.add(lazyItem(variableRow(v, parent.ref())));
        }
        return out;
    }

    /** Pure: the tree row of variable {@code v} in container {@code parentRef}. */
    static VarRow variableRow(DapModels.VariableInfo v, int parentRef) {
        // A lazy row still lazy here was not resolved (the budget ran out, or the adapter did not answer): it loads
        // on a click like one the budget left (B201).
        DapModels.ValueNote note = v.note() != null
                ? v.note()
                : v.lazy() && v.variablesReference() > 0 ? DapModels.ValueNote.NOT_LOADED : null;
        return new VarRow(
                v.name(),
                v.value(),
                v.type() == null ? "" : v.type(),
                v.variablesReference(),
                parentRef,
                Kind.VARIABLE,
                v.namedVariables(),
                v.indexedVariables(),
                false,
                note);
    }

    /**
     * Loads a row the stop's budget left unloaded and puts the loaded row in its place (B201); {@code expand}: then
     * shows its fields. While it loads, the row says so; a tree rebuilt meanwhile is left alone.
     */
    private void loadRow(TreeItem<VarRow> item, boolean expand) {
        VarRow row = item == null ? null : item.getValue();
        if (row == null || !row.notLoaded() || lastState != DapManager.State.SUSPENDED) {
            return;
        }
        item.setValue(new VarRow(
                row.name(),
                row.value(),
                row.type(),
                row.ref(),
                row.parentRef(),
                row.kind(),
                row.named(),
                row.indexed(),
                false,
                DapModels.ValueNote.LOADING));
        DapModels.VariableInfo asked = new DapModels.VariableInfo(
                row.name(), row.value(), row.type(), row.ref(), row.named(), row.indexed(), true);
        actions.loadValue(asked, v -> {
            TreeItem<VarRow> parent = item.getParent();
            int idx = parent == null ? -1 : parent.getChildren().indexOf(item);
            if (idx < 0) {
                return;
            }
            boolean selected = variables.getSelectionModel().getSelectedItem() == item;
            TreeItem<VarRow> loaded = lazyItem(variableRow(v, row.parentRef()));
            parent.getChildren().set(idx, loaded);
            List<TreeItem<VarRow>> all = scopeRows.get(parent);
            if (all != null && all.indexOf(item) >= 0) {
                all.set(all.indexOf(item), loaded);
            }
            noteValue(loaded);
            if (selected) {
                variables.getSelectionModel().select(loaded);
            }
            if (expand && !loaded.isLeaf() && !loaded.getValue().notLoaded()) {
                loaded.setExpanded(true);
            }
        });
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

    /**
     * A click on a stack-trace line ({@code at Main.main(Main.java:22)}, drawn as a link) opens that line; one
     * click, as IntelliJ and VS Code do — it took a double-click before (review C21). A drag that selects text is
     * not a click on a link.
     */
    private void installLinkClicks() {
        console.setOnMouseClicked(e -> {
            if (onLink == null
                    || e.getButton() != javafx.scene.input.MouseButton.PRIMARY
                    || !e.isStillSincePress()
                    || console.getSelection().getLength() > 0) {
                return;
            }
            var hit = console.hit(e.getX(), e.getY());
            int par = console.offsetToPosition(
                            hit.getInsertionIndex(), org.fxmisc.richtext.model.TwoDimensional.Bias.Forward)
                    .getMajor();
            com.editora.run.StackTraceLinks.Link link = com.editora.run.StackTraceLinks.parse(
                    console.getParagraph(par).getText());
            if (link != null) {
                onLink.accept(link);
            }
        });
    }

    /** Empties the console (its context menu, and each new launch — see {@code DebugCoordinator}). */
    public void clearConsole() {
        appender.discard();
        console.clear();
        if (appender.trimmed()) {
            appender.resetTrimmed();
            refreshStatus();
        }
    }

    @Override
    public void focusFirstItem() {
        stack.requestFocus();
    }
}
