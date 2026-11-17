package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javafx.application.Platform;
import javafx.stage.Window;

import com.editora.dap.DapManager;
import com.editora.dap.DapModels;
import com.editora.dap.StepFilter;
import com.editora.dap.StepFilters;
import com.editora.editor.EditorBuffer;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * Don't step into in the window ({@link StepFilters} decides, this acts): a step that ends in filtered code is stepped
 * out of at once — for the debuggers that cannot skip code themselves, and for a filter added during a session — and
 * the call stack's Don't Step Into This… adds the selected frame's code to the list. Part of {@link DebugCoordinator},
 * whose session and status it uses.
 */
final class StepFilterCommands {

    /** The most steps out one step takes, through filtered code that calls more of it; the stop is then shown. */
    static final int MAX_STEPS_OUT = 8;

    private final DebugCoordinator d;
    private final CoordinatorHost host;
    private final DebugCoordinator.Ops ops;

    /** The filter form; a test answers it without a window. */
    interface Editor {
        Optional<StepFilter> edit(@Nullable Window owner, StepFilter initial, boolean isNew);
    }

    /** The form used in place of the dialog (tests). */
    @Nullable
    Editor editor;

    StepFilterCommands(DebugCoordinator d, CoordinatorHost host, DebugCoordinator.Ops ops) {
        this.d = d;
        this.host = host;
        this.ops = ops;
    }

    /**
     * A step that ended in code a Don't step into filter names: it is not shown, and the thread steps out of it (again,
     * while it ends in such code, up to {@link #MAX_STEPS_OUT} times). True when that is under way.
     */
    boolean stop(String reason, List<DapModels.StackFrameInfo> frames) {
        DebugSession session = d.s();
        if (!"step".equals(reason) || frames.size() < 2) {
            session.stepsOut = 0;
            return false; // a breakpoint, an exception, a pause — or nowhere to step out to
        }
        DapModels.StackFrameInfo top = frames.getFirst();
        List<String> patterns = session.dap.stepFilterPatterns();
        if (patterns.isEmpty()
                || session.stepsOut >= MAX_STEPS_OUT
                || !StepFilters.matchesAny(
                        session.dap.sessionLanguage(),
                        patterns,
                        top.name(),
                        top.file() == null ? null : top.file().toString())) {
            session.stepsOut = 0;
            return false;
        }
        session.stepsOut++;
        int serial = session.stopSerial;
        session.quietStop = serial; // not shown: the thread goes on out of it
        Platform.runLater(() -> d.inSession(session, () -> {
            if (serial == session.stopSerial && session.dap.state() == DapManager.State.SUSPENDED) {
                session.dap.stepOut();
            }
        }));
        return true;
    }

    /** The call stack's Don't Step Into This… for {@code frame}. */
    javafx.scene.control.MenuItem menuItem(DapModels.StackFrameInfo frame) {
        javafx.scene.control.MenuItem item = new javafx.scene.control.MenuItem(tr("debugpanel.dontStepInto"));
        item.setOnAction(e -> dontStepInto(frame));
        return com.editora.editor.MenuChords.tag(item, "debug.dontStepIntoFrame");
    }

    /**
     * Debug: Don't Step Into This… ({@code debug.dontStepIntoFrame}): the form for a new filter, offering the code of
     * {@code frame} (the frame shown when null) — its class, file, package or namespace ({@link StepFilters#suggest}).
     */
    void dontStepInto(DapModels.@Nullable StackFrameInfo frame) {
        DapModels.StackFrameInfo f = frame != null ? frame : d.s().shownFrame;
        if (f == null || d.dap().state() != DapManager.State.SUSPENDED) {
            host.setStatus(tr("debug.frame.notPaused"));
            return;
        }
        String language = d.dap().sessionLanguage();
        String file = f.file() == null ? null : f.file().toString();
        // The frame's file is read (when no tab holds it) and its package found off the FX thread (PERF-01,
        // Pauta 6.0.1); the form opens when the answer lands.
        EditorBuffer open = f.file() == null ? null : ops.bufferForPath(f.file());
        String openText = open == null ? null : open.getArea().getText();
        Path onDisk = f.file();
        com.editora.perf.OffFx.then(
                "StepFilterCommands.source",
                () -> StepFilters.sourcePackage(openText != null ? openText : read(onDisk)),
                pkg -> add(new StepFilter(true, language, StepFilters.suggest(language, f.name(), file, pkg.or("")))));
    }

    /** Opens the form for {@code initial}; a filter saved is added to Settings and told to every session. */
    void add(StepFilter initial) {
        Editor form = editor != null ? editor : StepFilterDialog::edit;
        form.edit(host.window(), initial, true).ifPresent(f -> {
            List<StepFilter> all = new ArrayList<>();
            host.settings().getDebugStepFilterList().forEach(x -> all.add(x.copy()));
            if (!all.contains(f)) {
                all.add(f);
            }
            ops.persistDebugStepFilters(all);
            d.forEachSession(() -> d.dap().setStepFilters(all));
            host.setStatus(tr("status.debug.stepFilterAdded", f.getPattern()));
        });
    }

    /** The text of {@code file} read from disk (the package a JVM class is in); "" when it cannot be. */
    private static String read(@Nullable Path file) {
        if (file == null) {
            return "";
        }
        try {
            return Files.size(file) > 2_000_000 ? "" : Files.readString(file);
        } catch (Exception e) {
            return "";
        }
    }
}
