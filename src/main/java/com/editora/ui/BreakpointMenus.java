package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import javafx.scene.Node;

import com.editora.config.Breakpoint;
import com.editora.dap.DapManager;
import com.editora.dap.DapModels;
import com.editora.editor.EditorBuffer;
import com.editora.editor.UiZoom;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * The debugger's breakpoint commands and menus: the Breakpoints list, Mute Breakpoints, temporary, recording and lambda
 * breakpoints at the caret, the right-click menu of a breakpoint mark in the gutter, and the editor menu's debugger
 * rows (Debug File, the Breakpoints submenu, Run to Cursor, Show Value, Add to Watches). Part of
 * {@link DebugCoordinator}.
 */
final class BreakpointMenus {

    private final DebugCoordinator d; // its host and ops are read when used: this is made with the coordinator

    BreakpointMenus(DebugCoordinator d) {
        this.d = d;
    }

    /** Debug: Breakpoints — the palette-style list of every breakpoint (see {@link BreakpointsPicker}). */
    void showBreakpoints() {
        breakpointsPicker().show();
    }

    /** The breakpoints of the window's files, and what can be done to them (see {@link BreakpointsPicker}). */
    BreakpointsPicker breakpointsPicker() {
        return new BreakpointsPicker(
                        d.host,
                        d.ops,
                        d::allManagers,
                        this::toggleMuteBreakpoints,
                        () -> d.breakpointsMuted,
                        d.memberBreakpoints())
                .sendingClosed(this::sendClosed)
                .instructions(d.instructionBreakpoints);
    }

    /** Debug: Move Breakpoint to Group… — on the caret line (see {@link BreakpointGroups#moveToGroupAtCaret}). */
    void moveBreakpointToGroupAtCaret() {
        EditorBuffer b = d.breakpointBuffer();
        if (b != null) {
            d.groups().moveToGroupAtCaret(b);
        }
    }

    /** Debug: Enable / Disable Breakpoint Group… (see {@link BreakpointGroups#toggleGroupPicker}). */
    void toggleBreakpointGroup() {
        d.groups().toggleGroupPicker();
    }

    /** Debug: Export Breakpoints… (see {@link BreakpointGroups#exportBreakpoints}). */
    void exportBreakpoints() {
        d.groups().exportBreakpoints();
    }

    /** Debug: Import Breakpoints… (see {@link BreakpointGroups#importBreakpoints}). */
    void importBreakpoints() {
        d.groups().importBreakpoints();
    }

    /** The file a breakpoint store key names, or null (a remote host not connected, a key it cannot read). */
    private static @Nullable Path storedPath(String key) {
        try {
            return com.editora.vfs.Vfs.parseStorable(key);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Breakpoints just saved for files with no tab ({@code saved}, by file key; an empty list: none left) are sent to
     * each live session as its start would have: re-anchored off the FX thread, triggered ones held back, lambdas
     * aimed at. An import mid-session, an edit in the Breakpoints list or on a Map card.
     */
    void sendClosed(Map<String, List<Breakpoint>> saved) {
        // Muted, each session still learns them (Unmute sends what it knows); only the adapter is told nothing yet.
        Map<String, List<Breakpoint>> copy = new LinkedHashMap<>(saved);
        d.forEachSession(() -> {
            if (!d.dap().isActive()) {
                return;
            }
            Predicate<Breakpoint> sent = d.sentPredicate(d.s());
            d.startOffFx("debug-import", session -> () -> {
                List<DapModels.FileBreakpoints> computed = ClosedFileBreakpoints.of(
                        copy, Set.of(), RemoteExec::runsFor, ClosedFileBreakpoints::readLinesOrNull, sent);
                d.laterIn(session, () -> {
                    if (!d.dap().isActive()) {
                        return;
                    }
                    Map<String, DapModels.FileBreakpoints> byKey = new LinkedHashMap<>();
                    computed.forEach(fb -> byKey.put(DebugCoordinator.key(fb.file()), fb));
                    for (String k : copy.keySet()) { // a file left with none is told so: its last one was removed
                        Path p = byKey.containsKey(k) ? null : storedPath(k);
                        if (p != null) {
                            byKey.put(k, new DapModels.FileBreakpoints(p, List.of()));
                        }
                    }
                    for (DapModels.FileBreakpoints fb : byKey.values()) {
                        if (d.ops.bufferForPath(fb.file()) == null) { // opened meanwhile: its tab sent them
                            d.s().closedBreakpoints.put(DebugCoordinator.key(fb.file()), fb);
                            if (!d.breakpointsMuted) {
                                d.dap().updateBreakpoints(fb);
                            }
                        }
                    }
                });
            });
        });
    }

    /**
     * Debug: Mute Breakpoints — none of them stops the program until muted again; a live session is told at once
     * (every file it knows is sent again: empty while muted, its enabled breakpoints after).
     */
    void toggleMuteBreakpoints() {
        d.breakpointsMuted = !d.breakpointsMuted;
        d.memberBreakpoints().push(); // and method breakpoints and field watchpoints
        d.forEachSession(() -> {
            d.dap().setExceptionsMuted(d.breakpointsMuted); // exception stops too (review 2, B109)
            if (d.dap().isActive()) {
                Set<String> open = new HashSet<>();
                d.host.forEachBuffer(b -> {
                    if (b.getPath() != null && open.add(DebugCoordinator.key(b.getPath()))) {
                        DapModels.FileBreakpoints fb = d.fileBreakpoints(b, b.getPath());
                        d.s().sentBreakpoints.put(b.getPath(), fb);
                        d.dap().updateBreakpoints(fb);
                    }
                });
                for (DapModels.FileBreakpoints fb : d.s().closedBreakpoints.values()) {
                    if (fb.file() != null && !open.contains(DebugCoordinator.key(fb.file()))) {
                        d.dap()
                                .updateBreakpoints(
                                        d.breakpointsMuted ? new DapModels.FileBreakpoints(fb.file(), List.of()) : fb);
                    }
                }
            }
        });
        d.ops.persistDebugBreakpointsMuted(d.breakpointsMuted);
        showBreakpointsMuted();
        d.panel().setBreakpointsMuted(d.breakpointsMuted);
        d.host.setStatus(tr(d.breakpointsMuted ? "status.debug.breakpointsMuted" : "status.debug.breakpointsUnmuted"));
    }

    /** Shows Mute Breakpoints on every breakpoint glyph (faded, and said on hover) and in the Debug window. */
    void showBreakpointsMuted() {
        String hover = d.breakpointsMuted ? tr("debug.breakpoint.muted") : ""; // "": not muted
        d.host.forEachBuffer(b -> b.getBreakpointManager().setMuted(hover));
        d.forEachPanel(panel -> panel.setBreakpointsMuted(d.breakpointsMuted));
    }

    /**
     * Debug: Toggle Temporary Breakpoint — a breakpoint on the caret line that is removed once the program stops on
     * it (IntelliJ's Ctrl+Alt+Shift+F8). On a line with a temporary breakpoint it removes it; on one with an
     * ordinary breakpoint it makes that one temporary.
     */
    void toggleTemporaryBreakpointAtCaret() {
        EditorBuffer b = d.breakpointBuffer();
        if (b == null) {
            return;
        }
        int line = b.getFocusedArea().getCurrentParagraph();
        if (!d.breakpointAllowed(b, line, d.onSameLine(b, line, this::toggleTemporaryBreakpointAtCaret))) {
            return;
        }
        var mgr = b.getBreakpointManager();
        Breakpoint bp = mgr.get(line);
        if (bp != null && bp.temporary()) {
            b.toggleBreakpoint(line);
            return;
        }
        if (bp == null) {
            b.toggleBreakpoint(line);
        }
        mgr.setTemporary(line, true);
        b.refreshGutterLine(line);
        d.host.setStatus(tr("status.debug.temporaryAdded", line + 1));
    }

    /**
     * Debug: Toggle Recording Breakpoint — a breakpoint on the caret line whose stops Debug History keeps while the
     * program goes on, as Visual Studio's tracepoints and IntelliJ's non-suspending breakpoints log. On a line with a
     * recording breakpoint it removes it; on one with an ordinary breakpoint it makes that one record.
     */
    void toggleRecordingBreakpointAtCaret() {
        EditorBuffer b = d.breakpointBuffer();
        if (b == null) {
            return;
        }
        int line = b.getFocusedArea().getCurrentParagraph();
        if (!d.breakpointAllowed(b, line, d.onSameLine(b, line, this::toggleRecordingBreakpointAtCaret))) {
            return;
        }
        var mgr = b.getBreakpointManager();
        Breakpoint bp = mgr.get(line);
        if (bp != null && bp.recording()) {
            b.toggleBreakpoint(line);
            return;
        }
        if (bp == null) {
            b.toggleBreakpoint(line);
        }
        mgr.setRecording(line, true);
        b.refreshGutterLine(line);
        d.host.setStatus(tr("status.debug.recordingAdded", line + 1));
    }

    /**
     * The program stopped on a breakpoint: when it is a temporary one, it is removed, which also takes it out of the
     * live session. Waits for the stopped file's buffer to load: the stop may have just opened it.
     */
    void removeTemporaryBreakpointHit(String reason, List<DapModels.StackFrameInfo> frames) {
        if (!"breakpoint".equals(reason) || frames.isEmpty() || frames.get(0).file() == null) {
            return;
        }
        DapModels.StackFrameInfo top = frames.get(0);
        EditorBuffer b = d.ops.bufferForPath(top.file());
        if (b == null) {
            return;
        }
        d.ops.afterBufferLoad(b, () -> {
            var mgr = b.getBreakpointManager();
            int line = top.line() - mgr.regionFirstLine();
            Breakpoint bp = mgr.get(line);
            if (bp != null && bp.temporary() && bp.enabled() && !bp.isLogpoint()) {
                b.dropBreakpoint(line); // the program's removal, not the user's: no undo is recorded
                d.host.setStatus(tr("status.debug.temporaryRemoved", top.line() + 1));
            }
        });
    }

    /**
     * Debug: Toggle Lambda Breakpoint — a breakpoint that stops inside the lambda at the caret when its body runs
     * (see {@link LambdaBreakpoints}).
     */
    void toggleLambdaBreakpointAtCaret() {
        EditorBuffer b = d.breakpointBuffer();
        if (b != null
                && d.breakpointAllowed(
                        b,
                        b.getFocusedArea().getCurrentParagraph(),
                        d.onSameLine(
                                b, b.getFocusedArea().getCurrentParagraph(), this::toggleLambdaBreakpointAtCaret))) {
            d.lambdaBreakpoints().toggleAtCaret(b);
        }
    }

    /** Debug: Enable / Disable Breakpoint — flips the caret line's breakpoint without removing it. */
    void toggleBreakpointEnabledAtCaret() {
        EditorBuffer b = d.breakpointBuffer();
        if (b == null) {
            return;
        }
        int line = b.getFocusedArea().getCurrentParagraph();
        var mgr = b.getBreakpointManager();
        Breakpoint bp = mgr.get(line);
        if (bp == null) {
            d.host.setStatus(tr("status.debug.noBreakpointHere"));
            return;
        }
        mgr.setEnabled(line, !bp.enabled());
        b.refreshGutterLine(line);
    }

    /** Debug: Show Value at Caret — the value popup for the name at the caret, from the keyboard. */
    void showValueAtCaret() {
        EditorBuffer b = d.host.activeBuffer();
        if (d.dap().state() != DapManager.State.SUSPENDED) {
            d.host.setStatus(tr("palette.disabled.needsSuspended"));
        } else if (b == null || !b.showDebugValueAtCaret()) {
            d.host.setStatus(tr("status.debug.noValueAtCaret"));
        }
    }

    /**
     * F11 in the CUA and Sublime keymaps: Step Into while the program is paused, Full Screen otherwise — what F11
     * does in VS Code, whose debugging keys those keymaps now carry (review C8).
     */
    void stepIntoOrElse(Runnable otherwise) {
        if (d.debugSupportEnabled() && d.dap().state() == DapManager.State.SUSPENDED) {
            d.dap().stepInto();
        } else {
            otherwise.run();
        }
    }

    /** A right-click on a breakpoint glyph: Edit…, Enable / Disable, Remove (review C11). */
    void showGutterMenu(EditorBuffer b, int line, javafx.scene.input.ContextMenuEvent e) {
        var mgr = b.getBreakpointManager();
        Breakpoint bp = mgr.get(line);
        if (bp == null) {
            var mark = mgr.memberMark(line);
            if (mark != null) {
                showGutterMenu(memberGutterItems(b, line, mark), e);
            }
            return;
        }
        // Bare titles, as every other row of this menu ("Debug: Edit Breakpoint…" beside "Remove Breakpoint",
        // review 2 C110).
        javafx.scene.control.MenuItem edit = menuItem("debug.editBreakpoint", Icons.edit(), () -> {
            b.getFocusedArea().moveTo(line, 0);
            d.caretBreakpoints.editBreakpointAtCaret();
        });
        boolean enabled = bp.enabled();
        javafx.scene.control.MenuItem flip =
                new javafx.scene.control.MenuItem(tr(enabled ? "debug.gutter.disable" : "debug.gutter.enable"));
        // As Edit…: the caret goes to the line, and the command for the breakpoint there runs.
        flip.setOnAction(ev -> {
            b.getFocusedArea().moveTo(line, 0);
            toggleBreakpointEnabledAtCaret();
        });
        com.editora.editor.MenuChords.tag(flip, "debug.toggleBreakpointEnabled");
        javafx.scene.control.MenuItem remove = new javafx.scene.control.MenuItem(tr("debug.gutter.remove"));
        remove.setGraphic(Icons.remove());
        remove.getStyleClass().add("danger"); // a destructive row reads red
        remove.setOnAction(ev -> {
            if (mgr.isBreakpoint(line)) {
                b.getFocusedArea().moveTo(line, 0);
                d.caretBreakpoints.toggleBreakpointAtCaret();
            }
        });
        com.editora.editor.MenuChords.tag(remove, "debug.toggleBreakpoint");
        javafx.scene.control.MenuItem list = menuItem("debug.breakpoints", null, this::showBreakpoints);
        javafx.scene.control.MenuItem waitFor = menuItem("debug.addTriggeredBreakpoint", null, () -> {
            b.getFocusedArea().moveTo(line, 0);
            d.caretBreakpoints.addTriggeredBreakpointAtCaret();
        });
        javafx.scene.control.MenuItem toGroup = menuItem("debug.moveBreakpointToGroup", null, () -> {
            b.getFocusedArea().moveTo(line, 0);
            moveBreakpointToGroupAtCaret();
        });
        gutterMenu().getItems().setAll(edit, flip, remove, waitFor, toGroup);
        List<javafx.scene.control.MenuItem> lambdas = d.lambdaBreakpoints().choices(b, line, bp);
        if (!lambdas.isEmpty()) {
            gutterMenu().getItems().add(new javafx.scene.control.SeparatorMenuItem());
            gutterMenu().getItems().addAll(lambdas);
        }
        gutterMenu().getItems().addAll(new javafx.scene.control.SeparatorMenuItem(), list);
        showGutterMenu(List.copyOf(gutterMenu().getItems()), e);
    }

    /** The gutter menu of a method breakpoint's or field watchpoint's mark: Enable / Disable, Remove, Breakpoints. */
    private List<javafx.scene.control.MenuItem> memberGutterItems(
            EditorBuffer b, int line, com.editora.editor.debug.BreakpointManager.MemberMark mark) {
        List<javafx.scene.control.MenuItem> rows =
                new ArrayList<>(d.memberBreakpoints().gutterRows(b, line, mark));
        rows.addAll(List.of(
                new javafx.scene.control.SeparatorMenuItem(),
                menuItem("debug.breakpoints", null, this::showBreakpoints)));
        return rows;
    }

    private void showGutterMenu(List<javafx.scene.control.MenuItem> items, javafx.scene.input.ContextMenuEvent e) {
        gutterMenu().getItems().setAll(items);
        Node source = e.getSource() instanceof Node n ? n : null;
        if (source != null && source.getScene() != null && source.getScene().getWindow() != null) {
            gutterMenu().show(source, e.getScreenX(), e.getScreenY());
        } else if (d.host.window() != null) {
            gutterMenu().show(d.host.window(), e.getScreenX(), e.getScreenY());
        }
    }

    /** A breakpoint mark's right-click menu, made on the first such right-click (it is a popup window of its own). */
    private javafx.scene.control.@Nullable ContextMenu gutterMenu;

    javafx.scene.control.ContextMenu gutterMenu() {
        if (gutterMenu == null) {
            gutterMenu = new javafx.scene.control.ContextMenu();
        }
        return gutterMenu;
    }

    /**
     * Whether the editor menu offers Debug File of its own for a file of {@code language}: one that Run File can't
     * run ({@code runnable} false; a runnable file has Debug File beside Run File) but Debug can. That is a classic
     * Java main class outside a Maven or Gradle project ({@code inJavaProject} false), a Go, Rust, C or C++ file
     * without a program's {@code main} (a test file, a library file), and any C#, Ruby or PHP file, which only the
     * debugger's own launch runs. Pure.
     */
    static boolean offersOwnDebugFile(String language, boolean runnable, boolean inJavaProject) {
        if (runnable) {
            return false;
        }
        return switch (language) {
            case "java" -> !inJavaProject;
            case "go", "rust", "c", "cpp", "csharp", "ruby", "php" -> true;
            default -> false;
        };
    }

    /**
     * The editor's right-click debugger items (review C10): the Breakpoints submenu on a debuggable file, and while
     * paused Run to Cursor, Show Value and Add to Watches for the name at the click. Empty when debugging is off.
     */
    List<javafx.scene.control.MenuItem> editorMenuItems(EditorBuffer b) {
        List<javafx.scene.control.MenuItem> items = new ArrayList<>();
        if (!d.debugSupportEnabled() || !d.isDebuggableBuffer(b)) {
            return items;
        }
        Path menuPath = b.getPath();
        if (menuPath != null
                && offersDebugFile(b)
                // Not while this file is being debugged or built for it: it offered a second launch of it (review 2
                // C110). Another file's live launch is replaced only after asking (review 3, C202).
                && !d.launchLiveFor(menuPath)) {
            // Debug File sits beside Run File for a runnable file; a file Run File can't run but Debug can gets
            // it here, or Debug was only in the Run menu and the palette.
            javafx.scene.control.MenuItem debugFile = new javafx.scene.control.MenuItem(tr("editmenu.debugFile"));
            debugFile.setGraphic(Icons.debug());
            debugFile.setOnAction(e -> d.debugFile(b));
            items.add(com.editora.editor.MenuChords.tag(debugFile, "debug.debugFile"));
        }
        items.add(breakpointsMenu(b));
        if (d.dap().state() == DapManager.State.SUSPENDED) {
            items.add(menuItem("debug.runToCursor", Icons.debugRunToCursor(), d::debugRunToCursor));
            items.add(menuItem("debug.forceRunToCursor", Icons.debugRunToCursor(), d::debugForceRunToCursor));
            items.add(menuItem("debug.showValue", Icons.find(), this::showValueAtCaret));
            items.add(menuItem("debug.evaluate", Icons.debug(), d.valueWindows::evaluateExpression));
            String expression = watchExpression(b);
            if (expression != null) {
                javafx.scene.control.MenuItem watch =
                        new javafx.scene.control.MenuItem(tr("debugpanel.addToWatches", expression));
                watch.setGraphic(Icons.newFile()); // as Add to Watches in the Variables menu
                watch.setOnAction(ev -> addWatch(expression));
                items.add(com.editora.editor.MenuChords.tag(watch, "debug.addWatchAtCaret"));
            }
        }
        return items;
    }

    /**
     * The commands behind the Debug window's right-click rows ({@link DebugRowCommands}): runs the row that
     * {@code commandId} stands for on the window's selection, as choosing it would; says so when the window is closed
     * (its selection is not something the user can see) or the row is not offered for the selection.
     */
    void runPanelRow(String commandId) {
        if (!d.ops.isToolWindowOpen() || !d.panel().runMenuRow(commandId)) {
            d.host.setStatus(
                    d.ops.isToolWindowOpen()
                            ? d.panel().rowRefusal(commandId)
                            : tr("status.debug.rowNotOffered", DebugPanelText.bareTitle(tr("command." + commandId))));
        }
    }

    /** Whether the editor menu offers Debug File for {@code b}: a file Debug can launch on its own. */
    private boolean offersDebugFile(EditorBuffer b) {
        Path path = b.getPath();
        return path != null
                && offersOwnDebugFile(
                        b.getLanguage(),
                        b.isRunnable(),
                        "java".equals(b.getLanguage()) && JavaProjectRoot.inProjectOrUnknown(path));
    }

    /**
     * Debug: Debug Current File ({@code debug.debugFile}): what the editor menu's Debug File row does, for the active
     * file; says so when Debug File is not offered for it.
     */
    void debugActiveFile() {
        EditorBuffer b = d.host.activeBuffer();
        if (b != null && b.runScanPending()) { // whether it is runnable is the Run scan's, made off the FX thread
            b.whenRunScanned(d.bound(() -> {
                if (d.host.activeBuffer() == b) {
                    debugActiveFile();
                }
            }));
            return;
        }
        // A runnable file has the row beside Run File (the editor menu's own), any other one the Debug File of its own.
        if (b == null || !d.isDebuggableBuffer(b) || !((b.isRunnable() && !b.isHttpFile()) || offersDebugFile(b))) {
            d.host.setStatus(tr("status.debug.noDebugFile"));
            return;
        }
        d.debugFile(b);
    }

    /**
     * Debug: Debug Main Class at Caret ({@code debug.mainClassAtCaret}): what the editor menu's Debug row on a
     * {@code main} method does; says so when the caret is on none.
     */
    void debugMainClassAtCaret() {
        EditorBuffer b = d.host.activeBuffer();
        if (b != null && b.runScanPending()) { // the load's scan runs off FX: decide once it has landed
            b.whenRunScanned(d.bound(this::debugMainClassAtCaret));
            return;
        }
        var main = b == null ? null : b.mainTargetAtCaret();
        if (main == null) {
            d.host.setStatus(tr("status.debug.noMainAtCaret"));
            return;
        }
        d.debugMainClassNamed(main.fqn());
    }

    /** The expression the editor menu offers to watch in {@code b}: a one-line selection, else the one at the caret. */
    private static @Nullable String watchExpression(EditorBuffer b) {
        var area = b.getFocusedArea();
        String selected = area.getSelectedText();
        return selected != null && !selected.isBlank() && !selected.contains("\n")
                ? selected.strip()
                : com.editora.editor.debug.DebugExpressions.atCaret(area);
    }

    /** Opens the Debug window and asks to add {@code expression} to the watches, as Add to Watches rows do. */
    private void addWatch(String expression) {
        d.ops.openToolWindow();
        d.panel().addWatchPrompt(expression);
    }

    /**
     * Debug: Add Expression at Caret to Watches… ({@code debug.addWatchAtCaret}): what the editor menu's Add to
     * Watches row does while the program is paused; says so when it is not, or the caret is on no expression.
     */
    void addWatchAtCaret() {
        EditorBuffer b = d.host.activeBuffer();
        String expression = b == null || d.dap().state() != DapManager.State.SUSPENDED ? null : watchExpression(b);
        if (expression == null) {
            d.host.setStatus(tr("status.debug.noWatchAtCaret"));
            return;
        }
        addWatch(expression);
    }

    /**
     * The editor menu's <b>Breakpoints</b> submenu: every breakpoint action for the right-clicked line (the caret is
     * there by now), then the list and Mute Breakpoints. One row instead of four to six flat ones, which pushed the
     * rest of the menu down on every debuggable file — the shape of the LSP and AI submenus.
     */
    private javafx.scene.control.Menu breakpointsMenu(EditorBuffer b) {
        javafx.scene.control.Menu menu =
                new javafx.scene.control.Menu(tr("editmenu.breakpoints"), breakpointGlyph()); // icons: review 2 C110
        var items = menu.getItems();
        items.add(menuItem("debug.toggleBreakpoint", breakpointGlyph(), d.caretBreakpoints::toggleBreakpointAtCaret));
        items.add(
                menuItem("debug.toggleTemporaryBreakpoint", breakpointGlyph(), this::toggleTemporaryBreakpointAtCaret));
        items.add(
                menuItem("debug.toggleRecordingBreakpoint", breakpointGlyph(), this::toggleRecordingBreakpointAtCaret));
        if (LambdaBreakpoints.caretLineHasLambdas(b)) {
            items.add(menuItem("debug.toggleLambdaBreakpoint", breakpointGlyph(), this::toggleLambdaBreakpointAtCaret));
        }
        items.add(menuItem("debug.addLogpoint", logpointGlyph(), d.caretBreakpoints::addLogpointAtCaret));
        items.add(menuItem("debug.addTriggeredBreakpoint", breakpointGlyph(), d.caretBreakpoints::addTriggeredBreakpointAtCaret));
        items.add(menuItem("debug.editBreakpoint", Icons.edit(), d.caretBreakpoints::editBreakpointAtCaret));
        javafx.scene.control.MenuItem toGroup =
                menuItem("debug.moveBreakpointToGroup", null, this::moveBreakpointToGroupAtCaret);
        com.editora.editor.RowReasons.when(
                toGroup, DebugPanelText.hasBreakpointAtCaret(b), "row.unavailable.noBreakpointHere");
        items.add(toGroup);
        javafx.scene.control.MenuItem flip =
                menuItem("debug.toggleBreakpointEnabled", Icons.check(), this::toggleBreakpointEnabledAtCaret);
        com.editora.editor.RowReasons.when(
                flip, DebugPanelText.hasBreakpointAtCaret(b), "row.unavailable.noBreakpointHere");
        items.add(flip);
        items.add(new javafx.scene.control.SeparatorMenuItem());
        items.add(menuItem("debug.breakpoints", Icons.outline(), this::showBreakpoints));
        javafx.scene.control.CheckMenuItem mute =
                new javafx.scene.control.CheckMenuItem(DebugPanelText.bareTitle(tr("command.debug.muteBreakpoints")));
        mute.setGraphic(Icons.hidden());
        mute.setSelected(d.breakpointsMuted);
        mute.setOnAction(e -> toggleMuteBreakpoints());
        com.editora.editor.MenuChords.tag(mute, "debug.muteBreakpoints");
        items.add(mute);
        return menu;
    }

    private static javafx.scene.control.MenuItem menuItem(String commandId, @Nullable Node icon, Runnable action) {
        javafx.scene.control.MenuItem item =
                new javafx.scene.control.MenuItem(DebugPanelText.bareTitle(tr("command." + commandId)));
        com.editora.editor.MenuChords.tag(item, commandId);
        item.setGraphic(icon);
        item.setOnAction(e -> action.run());
        return item;
    }

    /** A small breakpoint dot for Toggle Breakpoint in the editor menu (the gutter's red, from CSS). */
    static Node breakpointGlyph() {
        javafx.scene.shape.Circle dot = new javafx.scene.shape.Circle();
        UiZoom.follow(dot, () -> dot.setRadius(UiZoom.px(4)));
        dot.getStyleClass().add("debug-menu-breakpoint");
        return dot;
    }

    /** Add Logpoint's mark in the same menu: the gutter's logpoint diamond, blue, at the dot's size. */
    private static Node logpointGlyph() {
        javafx.scene.shape.Polygon diamond = new javafx.scene.shape.Polygon();
        UiZoom.follow(diamond, () -> {
            double u = UiZoom.px(4);
            diamond.getPoints().setAll(u, 0.0, 2 * u, u, u, 2 * u, 0.0, u);
        });
        diamond.getStyleClass().add("debug-menu-logpoint");
        return diamond;
    }
}
