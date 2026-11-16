package com.editora.ui;

import javafx.scene.control.Label;
import javafx.scene.control.TextField;

import com.editora.config.Settings;
import com.editora.dap.GoGoroutines;
import com.editora.editor.UiZoom;

import static com.editora.i18n.Messages.tr;

/**
 * Go's goroutines in the Threads view ({@link GoGoroutines}): Settings' options told to every Go session, and the
 * Threads view's Hide Runtime Goroutines and Filter Goroutines…, which change them for the window and its running
 * sessions at once. Part of {@link DebugCoordinator}, whose sessions and status it uses.
 */
final class GoroutineCommands {

    private final DebugCoordinator d;
    private final CoordinatorHost host;
    private final DebugCoordinator.Ops ops;

    GoroutineCommands(DebugCoordinator d, CoordinatorHost host, DebugCoordinator.Ops ops) {
        this.d = d;
        this.host = host;
        this.ops = ops;
    }

    /** The options Settings says. */
    static GoGoroutines.Options of(Settings s) {
        return new GoGoroutines.Options(
                s.isDebugGoHideRuntimeGoroutines(),
                GoGoroutines.labels(s.getDebugGoPprofLabels()),
                s.getDebugGoGoroutineFilter());
    }

    /** Tells every session Settings' options (a running Go session at once), and lists its goroutines again. */
    void apply() {
        GoGoroutines.Options options = of(host.settings());
        d.dap().setGoGoroutines(options, () -> {}); // sessions started from now on
        d.forEachSession(() -> {
            if (!d.dap().isActive()) {
                return; // no program to tell, and no Threads view to build before the window shows it
            }
            DebugSession session = d.s();
            d.dap().setGoGoroutines(options, () -> d.inSession(session, d::refreshThreads));
        });
    }

    /** Whether the runtime's goroutines are hidden. */
    boolean runtimeHidden() {
        return host.settings().isDebugGoHideRuntimeGoroutines();
    }

    /** Debug: Hide Runtime Goroutines ({@code debug.toggleRuntimeGoroutines}), on or off. */
    void toggleRuntime() {
        boolean hide = !runtimeHidden();
        ops.persistGoGoroutines(hide, host.settings().getDebugGoGoroutineFilter());
        apply();
        host.setStatus(tr(hide ? "status.debug.runtimeGoroutinesHidden" : "status.debug.runtimeGoroutinesShown"));
    }

    /** Debug: Filter Goroutines… ({@code debug.filterGoroutines}): Delve's filter, asked for; blank lists them all. */
    void filter() {
        Label label = new Label(tr("dialog.debug.goroutineFilterLabel"));
        TextField field = new TextField(host.settings().getDebugGoGoroutineFilter());
        field.setPrefColumnCount(32);
        field.setPromptText(tr("dialog.debug.goroutineFilterPrompt"));
        label.setLabelFor(field);
        Label hint = new Label(tr("dialog.debug.goroutineFilterHint"));
        hint.setWrapText(true);
        hint.getStyleClass().add("settings-note");
        UiZoom.maxWidth(hint, 420);
        com.editora.command.TextInputKeymap.installShared(field);
        OverlayInput.show(
                host.overlayHost(),
                tr("dialog.debug.goroutineFilterTitle"),
                UiZoom.spacing(new javafx.scene.layout.VBox(label, field, hint), 6),
                field,
                tr("dialog.ok"),
                null,
                () -> setFilter(field.getText()),
                null,
                false);
    }

    /** Sets Delve's goroutine filter to {@code filter} ("" for none), for the window and its sessions. */
    void setFilter(String filter) {
        String f = filter == null ? "" : filter.strip();
        ops.persistGoGoroutines(runtimeHidden(), f);
        apply();
        host.setStatus(f.isEmpty() ? tr("status.debug.goroutineFilterCleared") : tr("status.debug.goroutineFilter", f));
    }
}
