package com.editora.ui;

import java.nio.file.Path;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javafx.application.Platform;

import com.editora.config.Settings;
import com.editora.config.WorkspaceState;
import com.editora.editor.EditorBuffer;
import com.editora.logviewer.LogFileNames;
import com.editora.logviewer.LogFilter;
import com.editora.logviewer.LogLevel;
import com.editora.logviewer.LogNavigation;
import com.editora.logviewer.LogPatterns;
import com.editora.logviewer.LogQuery;
import com.editora.logviewer.LogTailService;
import com.editora.text.LanguageRegistry;
import com.editora.vfs.Vfs;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * Owns the server-log-viewer feature, extracted from {@code MainController} as the first
 * <em>feature coordinator</em>: it holds the log state (the tail service + the per-buffer follow and control
 * maps) and all the log logic, and reaches back into the window only through {@link CoordinatorHost}.
 * {@code MainController} keeps thin one-line delegations at the call sites (open/save-as/rename, tab close,
 * settings apply, file load, command registration).
 *
 * <p>The filter and follow state themselves live in the buffer ({@code LogView}). The control bar and the
 * palette commands both go through {@link #applyFilter} and are both shown from that state, so neither can
 * drift from what the view is doing. See {@code docs/subsystems/log-viewer.md}.
 */
final class LogViewerCoordinator {

    /** A log at least this long (characters) is filtered off the FX thread. */
    static final int ASYNC_FILTER_CHARS = 512 * 1024;

    /** How much of a file's start is looked at to decide whether an unnamed-as-such file is a log. */
    private static final int SNIFF_CHARS = 8 * 1024;

    /**
     * Where a follow of a buffer resumes: the byte offset its text reaches in the file, valid for as long as
     * the buffer's on-disk snapshot is still the size it was when the offset was taken. A save or a reload
     * moves the snapshot, and then the offset says nothing about the new file.
     */
    private record Resume(long offset, long snapshotSize) {}

    private final CoordinatorHost host;
    private final LogTailService logTailService = new LogTailService();
    private final ExecutorService filterExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "log-filter");
        t.setDaemon(true);
        return t;
    });

    private final Map<EditorBuffer, Resume> resumes = new IdentityHashMap<>();
    /** Active {@code tail -f} handles, keyed by buffer. */
    private final Map<EditorBuffer, LogTailService.Handle> logFollows = new IdentityHashMap<>();
    /** The control bar docked on each log buffer. */
    private final Map<EditorBuffer, LogControlBar> logBars = new IdentityHashMap<>();
    /** The newest filter asked of each buffer: an older one still being computed is dropped when it returns. */
    private final Map<EditorBuffer, Integer> filterRequests = new IdentityHashMap<>();
    /** Buffers whose remembered view was put back already: a reload keeps what the user has now. */
    private final Set<EditorBuffer> restored = java.util.Collections.newSetFromMap(new IdentityHashMap<>());

    LogViewerCoordinator(CoordinatorHost host) {
        this.host = host;
    }

    /** Whether the log viewer is active (default-on setting, suppressed in Simple UI mode). */
    boolean isEnabled() {
        return host.settings().isLogViewer() && !host.simpleModeActive();
    }

    private boolean isLogFile(@Nullable Path file) {
        return file != null
                && "log".equals(LanguageRegistry.forFileName(file.getFileName().toString()));
    }

    /** Whether {@code file} should get log-aware loading (feature on, a local file named as a log). */
    boolean handlesLogFile(@Nullable Path file) {
        return isEnabled() && isLogFile(file) && Vfs.isLocal(file);
    }

    /**
     * Records where a later Follow resumes: the byte offset the freshly loaded text reaches. A buffer that
     * is being followed when it is reloaded goes on from there.
     */
    void recordLoadOffset(EditorBuffer buffer, long offset) {
        boolean following = logFollows.containsKey(buffer);
        stopFollow(buffer); // its offset was a position in the text that has just been replaced
        resumes.put(buffer, new Resume(offset, buffer.diskSnapshot().size()));
        if (following) {
            startFollow(buffer);
        }
    }

    /**
     * Decides from its first lines whether a just-loaded file is a log although its name does not say so
     * ({@code server.out}, an extensionless file in {@code /var/log}), and shows it as one if it is.
     */
    void sniffLoaded(EditorBuffer buffer, @Nullable String content) {
        Path file = buffer.getPath();
        if (!isEnabled()
                || content == null
                || file == null
                || buffer.isLog()
                || !LanguageRegistry.plaintext().equals(buffer.getLanguage())
                || !LogFileNames.mayBeLog(file.getFileName().toString())
                || Boolean.FALSE.equals(remembered(file)
                        .map(WorkspaceState.LogViewState::getViewAsLog)
                        .orElse(null))) {
            return; // the user said this one is not a log
        }
        String sample = content.length() > SNIFF_CHARS ? content.substring(0, SNIFF_CHARS) : content;
        if (LogPatterns.looksLikeLog(sample)) {
            buffer.setLogViewForced(true);
            ensureControl(buffer);
        }
    }

    /**
     * Reconciles the log viewer with its setting (mirrors {@code applyHtmlPreviewSupport}): (un)attaches the
     * control bar + level overlay on log buffers and stops any follow when disabled. Runs at startup and
     * on every settings apply.
     */
    void applySupport() {
        boolean on = isEnabled();
        host.forEachBuffer(b -> {
            ensureControl(b);
            b.setLogHighlightEnabled(on && b.isLog());
        });
    }

    /** Attaches/removes the log control bar so it shows only on log buffers with the feature on. */
    void ensureControl(EditorBuffer buffer) {
        boolean want = isEnabled() && buffer.isLog();
        boolean has = buffer.hasLogControl();
        if (want && !has) {
            LogControlBar bar = new LogControlBar(
                    following -> toggleFollow(buffer, following),
                    (min, query) -> applyFilter(buffer, min, query, false),
                    () -> buffer.getFocusedArea().requestFocus());
            logBars.put(buffer, bar);
            buffer.setLogControl(bar);
            buffer.setLogHighlightEnabled(true);
            buffer.setOnLogStateChanged(() -> syncBar(buffer));
            syncBar(buffer);
        } else if (!want && has) {
            buffer.setOnLogStateChanged(() -> {});
            buffer.setLogControl(null);
            logBars.remove(buffer);
            stopFollow(buffer);
            if (buffer.isLogFiltered()) {
                buffer.applyLogFilter(null, LogQuery.NONE); // no control left to clear it with
            }
            buffer.setLogHighlightEnabled(false);
        }
    }

    /** Makes the bar show the buffer's filter, follow and line counts. */
    private void syncBar(EditorBuffer buffer) {
        LogControlBar bar = logBars.get(buffer);
        if (bar == null) {
            return;
        }
        bar.setFollowing(buffer.isLogFollowing());
        bar.showFilter(buffer.getLogMinLevel(), buffer.getLogQuery());
        boolean filtered = buffer.isLogFiltered();
        bar.showState(
                filtered,
                filtered ? buffer.logVisibleLines() : 0,
                filtered ? buffer.logTotalLines() : 0,
                buffer.isLogTrimmed(),
                filtered && buffer.isLogPatternSlow());
    }

    // --- memory across sessions ------------------------------------------------------------------

    private java.util.Optional<WorkspaceState.LogViewState> remembered(@Nullable Path file) {
        WorkspaceState ws = host.workspaceState();
        return file == null || ws == null
                ? java.util.Optional.empty()
                : java.util.Optional.ofNullable(ws.getLogViews().get(file.toString()));
    }

    /**
     * Puts back how a just-loaded file was last shown: as a log although its name is not a log's, and the
     * level floor and filter it had. Once per buffer: a reload keeps whatever the user has made of it since.
     */
    void restoreView(EditorBuffer buffer) {
        if (!restored.add(buffer) || !isEnabled()) {
            return;
        }
        WorkspaceState.LogViewState state = remembered(buffer.getPath()).orElse(null);
        if (state == null) {
            return;
        }
        if (Boolean.TRUE.equals(state.getViewAsLog()) && !buffer.isLog()) {
            buffer.setLogViewForced(true);
            ensureControl(buffer);
        }
        if (!buffer.isLog()) {
            return;
        }
        LogLevel min = null;
        if (state.getMinLevel() != null) {
            try {
                min = LogLevel.valueOf(state.getMinLevel());
            } catch (IllegalArgumentException ignored) {
                // a level this build does not know: every level
            }
        }
        LogQuery query = new LogQuery(state.getInclude(), state.getExclude(), state.isMatchCase()).normalized();
        if (min != null || !query.isEmpty()) {
            applyFilter(buffer, min, query, false);
        }
    }

    /** Stores the buffer's level floor and filter, keeping its View as Log choice. */
    private void rememberFilter(EditorBuffer buffer) {
        remember(
                buffer,
                remembered(buffer.getPath())
                        .map(WorkspaceState.LogViewState::getViewAsLog)
                        .orElse(null));
    }

    /**
     * Stores how {@code buffer} is shown; a file shown the way a fresh open would show it has no entry. Saves
     * only when that changed, so putting a view back does not write the session again.
     */
    private void remember(EditorBuffer buffer, @Nullable Boolean viewAsLog) {
        Path file = buffer.getPath();
        WorkspaceState ws = host.workspaceState();
        if (file == null || ws == null) {
            return;
        }
        LogLevel min = buffer.isLog() ? buffer.getLogMinLevel() : null;
        LogQuery query = buffer.isLog() ? buffer.getLogQuery() : LogQuery.NONE;
        WorkspaceState.LogViewState state = new WorkspaceState.LogViewState(
                viewAsLog,
                min == null ? null : min.name(),
                query.include(),
                query.exclude(),
                !query.isEmpty() && query.matchCase());
        Map<String, WorkspaceState.LogViewState> views = ws.getLogViews();
        String key = file.toString();
        boolean empty = state.equals(new WorkspaceState.LogViewState());
        WorkspaceState.LogViewState before = views.get(key);
        if (empty ? before == null : state.equals(before)) {
            return;
        }
        if (empty) {
            views.remove(key);
        } else {
            views.put(key, state);
        }
        host.requestSave();
    }

    /** Cancels a buffer's follow + drops its per-buffer state (called when its tab closes). */
    void onBufferClosed(EditorBuffer buffer) {
        restored.remove(buffer);
        stopFollow(buffer);
        logBars.remove(buffer);
        resumes.remove(buffer);
        filterRequests.remove(buffer);
    }

    /** Stops the tail-follow poll thread and the filter worker (window dispose). */
    void shutdown() {
        logTailService.shutdown();
        filterExecutor.shutdownNow();
    }

    // --- follow ----------------------------------------------------------------------------------

    private void toggleFollow(EditorBuffer buffer, boolean follow) {
        if (follow) {
            startFollow(buffer);
        } else {
            stopFollow(buffer);
            host.setStatus(tr("status.log.followStopped"));
        }
        syncBar(buffer);
    }

    private void startFollow(EditorBuffer buffer) {
        Path file = buffer.getPath();
        if (file == null || !Vfs.isLocal(file)) {
            host.setStatus(tr("status.log.followUnavailable"));
            syncBar(buffer);
            return;
        }
        stopFollow(buffer);
        long start = resumeOffset(buffer, file);
        buffer.setLogFollowing(true);
        // The follow keeps the buffer's on-disk snapshot at what it has read. If the snapshot is ever anything
        // else, something other than the follow wrote or re-read the file (a save, a reload), and the offset
        // this follow is reading from is no longer a position in that file.
        long[] snapshotSize = {buffer.diskSnapshot().size()};
        LogTailService.Handle[] self = new LogTailService.Handle[1];
        self[0] = logTailService.follow(file, start, buffer.logCharset(), new LogTailService.Listener() {
            private boolean current(LogTailService.FileState state) {
                if (logFollows.get(buffer) != self[0]) {
                    return false;
                }
                if (buffer.diskSnapshot().size() != snapshotSize[0]) {
                    stopFollow(buffer);
                    resumes.remove(buffer); // this follow's offset is not a position in the file as it is now
                    startFollow(buffer); // from the new snapshot
                    return false;
                }
                buffer.setDiskSnapshot(state.modifiedMillis(), state.size());
                snapshotSize[0] = state.size();
                return true;
            }

            @Override
            public void appended(String text, LogTailService.FileState state) {
                if (current(state)) {
                    buffer.appendLogText(text);
                }
            }

            @Override
            public void rotated(String text, LogTailService.FileState state) {
                if (logFollows.get(buffer) == self[0] && buffer.isDirty()) {
                    // A rotation replaces the whole buffer. The user typed into this one: showing the new
                    // file would drop that text. Stop here instead, and leave the on-disk snapshot alone so
                    // that saving these edits over the rotated file asks first.
                    stopFollow(buffer);
                    resumes.remove(buffer); // the offset belonged to the file that was rotated away
                    syncBar(buffer);
                    host.setStatus(tr("status.log.followStoppedEdited"));
                    return;
                }
                if (current(state)) {
                    buffer.resetLogContent(text);
                    host.setStatus(tr("status.log.rotated"));
                }
            }

            @Override
            public void missing() {
                if (logFollows.get(buffer) == self[0]) {
                    host.setStatus(tr("status.log.followWaiting"));
                }
            }

            @Override
            public void error(String message) {
                if (logFollows.get(buffer) == self[0]) {
                    logFollows.remove(buffer);
                    buffer.setLogFollowing(false);
                    syncBar(buffer);
                    host.setStatus(tr("status.log.followError", message));
                }
            }
        });
        logFollows.put(buffer, self[0]);
        host.setStatus(tr("status.log.following"));
    }

    /** The byte offset the buffer's text reaches in {@code file}: where following it starts without a gap. */
    private long resumeOffset(EditorBuffer buffer, Path file) {
        EditorBuffer.DiskSnapshot snapshot = buffer.diskSnapshot();
        Resume resume = resumes.get(buffer);
        if (resume != null && resume.snapshotSize() == snapshot.size()) {
            return resume.offset();
        }
        // No snapshot: from the file's end, measured on the follow's own thread (PERF-01, Pauta 6.2.0).
        return snapshot.modifiedMillis() >= 0 ? snapshot.size() : -1;
    }

    private void stopFollow(EditorBuffer buffer) {
        LogTailService.Handle handle = logFollows.remove(buffer);
        if (handle == null) {
            return;
        }
        handle.stop();
        // What reached the buffer, not the file's size now: lines written since the last poll, and all those
        // written while paused, are read when following resumes.
        resumes.put(buffer, new Resume(handle.offset(), buffer.diskSnapshot().size()));
        buffer.setLogFollowing(false);
    }

    // --- filter ----------------------------------------------------------------------------------

    /**
     * Shows only the records of {@code buffer} at or above {@code min} that {@code query} keeps — its patterns
     * are regular expressions, or plain text when not valid ones. A large log is filtered off the FX thread;
     * the view changes when the result is in.
     *
     * @param announce say so in the status bar (a command did this, not a keystroke in the pattern field)
     */
    private void applyFilter(EditorBuffer buffer, @Nullable LogLevel min, LogQuery query, boolean announce) {
        LogQuery wanted = query.normalized();
        int request = filterRequests.merge(buffer, 1, Integer::sum);
        boolean clearing = min == null && wanted.isEmpty();
        if (buffer.showsLogFilter(min, wanted)) {
            filtered(buffer, clearing, announce);
            return;
        }
        String source = clearing ? null : buffer.logFilterSource();
        if (source == null || source.length() < ASYNC_FILTER_CHARS) {
            buffer.applyLogFilter(min, wanted);
            filtered(buffer, clearing, announce);
            return;
        }
        int epoch = buffer.logFilterEpoch();
        LogFilter.Patterns compiled = LogFilter.compile(wanted);
        filterExecutor.execute(() -> {
            LogFilter.Run run = LogFilter.run(source, min, compiled);
            Platform.runLater(() -> {
                if (buffer.isDisposed() || filterRequests.getOrDefault(buffer, 0) != request) {
                    return; // closed, or a newer filter was asked for while this one was computed
                }
                if (buffer.installLogFilter(run, epoch, min, wanted)) {
                    filtered(buffer, false, announce);
                } else {
                    applyFilter(buffer, min, wanted, announce); // the text changed under it: again
                }
            });
        });
    }

    private void filtered(EditorBuffer buffer, boolean cleared, boolean announce) {
        syncBar(buffer);
        rememberFilter(buffer);
        if (announce) {
            host.setStatus(tr(cleared ? "status.log.filterCleared" : "status.log.filtered"));
        }
    }

    // --- commands (the palette and the control bar share these) ----------------------------------

    void toggleFollowCommand() {
        ifLog(b -> toggleFollow(b, !b.isLogFollowing()));
    }

    /** Shows the active file as a log, or stops doing so — for a file whose name does not make it one. */
    void viewAsLog() {
        EditorBuffer b = host.activeBuffer();
        if (b == null) {
            return;
        }
        if (!isEnabled()) {
            host.setStatus(tr("status.log.disabled"));
            return;
        }
        if (b.isLog() && !b.isLogViewForced()) {
            host.setStatus(tr("status.log.viewAsLog")); // a log by name: nothing to switch
            return;
        }
        boolean on = !b.isLogViewForced();
        b.setLogViewForced(on);
        ensureControl(b); // off: also stops a follow and clears a filter
        // Off is remembered only where the first lines could make it a log again on the next open.
        Path file = b.getPath();
        boolean sniffable =
                file != null && LogFileNames.mayBeLog(file.getFileName().toString());
        remember(b, on ? Boolean.TRUE : sniffable ? Boolean.FALSE : null);
        host.setStatus(tr(on ? "status.log.viewAsLog" : "status.log.viewAsLogOff"));
    }

    void setLevelFilter() {
        ifLog(b -> {
            List<String> labels = LogControlBar.levelLabels();
            QuickOpen<String> picker = new QuickOpen<>(
                    tr("log.level.pick"), tr("log.level.prompt"), () -> labels, s -> s, s -> "", choice -> {
                        int idx = Math.max(0, labels.indexOf(choice));
                        applyFilter(b, LogControlBar.LEVELS.get(idx), b.getLogQuery(), true);
                    });
            picker.setOverlayHost(host.overlayHost());
            picker.show(host.window());
        });
    }

    void setRegexFilter() {
        ifLog(b -> {
            LogQuery query = b.getLogQuery();
            String current = query.include() == null ? "" : query.include();
            host.promptText(
                    tr("log.filter.title"),
                    tr("log.filter.label"),
                    current,
                    text -> applyFilter(b, b.getLogMinLevel(), b.getLogQuery().withInclude(text), true));
        });
    }

    /** Asks for the pattern of records to hide, keeping the rest of the filter. */
    void setHideFilter() {
        ifLog(b -> {
            LogQuery query = b.getLogQuery();
            String current = query.exclude() == null ? "" : query.exclude();
            host.promptText(tr("log.hide.title"), tr("log.hide.label"), current, text -> {
                LogQuery now = b.getLogQuery();
                applyFilter(b, b.getLogMinLevel(), new LogQuery(now.include(), text, now.matchCase()), true);
            });
        });
    }

    /**
     * Next/Previous Problem in a log: a log has no language server, and its problems are its warnings and
     * errors — so the chord the keymap gives Next Problem steps through those. Returns false (doing nothing)
     * when the active buffer is not a log with the viewer on.
     */
    boolean stepProblemInLog(boolean forward) {
        EditorBuffer b = host.activeBuffer();
        if (!isEnabled() || b == null || !b.isLog()) {
            return false;
        }
        jumpError(forward);
        return true;
    }

    /** Puts the keyboard in the control bar's pattern field. */
    void focusFilter() {
        ifLog(b -> {
            LogControlBar bar = logBars.get(b);
            if (bar != null) {
                bar.focusPattern();
            }
        });
    }

    void clearFilter() {
        ifLog(b -> applyFilter(b, null, LogQuery.NONE, true));
    }

    void jumpToNextError() {
        jumpError(true);
    }

    void jumpToPreviousError() {
        jumpError(false);
    }

    /** Moves the caret to the next/previous line at WARN or higher in the active log (wrapping). */
    private void jumpError(boolean forward) {
        ifLog(b -> {
            var area = b.getFocusedArea();
            int target = LogNavigation.nextLevelLine(
                    i -> area.getParagraph(i).getText(),
                    area.getParagraphs().size(),
                    area.getCurrentParagraph(),
                    forward,
                    LogLevel.WARN);
            if (target < 0) {
                host.setStatus(tr("status.log.noProblemLines"));
                return;
            }
            b.jumpToLine(target);
        });
    }

    /** Runs {@code action} only when the active buffer is a log buffer and the feature is on. */
    private void ifLog(java.util.function.Consumer<EditorBuffer> action) {
        EditorBuffer b = host.activeBuffer();
        if (!isEnabled()) {
            host.setStatus(tr("status.log.disabled"));
        } else if (b == null || !b.isLog()) {
            host.setStatus(tr("status.log.notLog"));
        } else {
            action.accept(b);
        }
    }

    void toggleViewer() {
        Settings s = host.settings();
        s.setLogViewer(!s.isLogViewer());
        host.requestSave();
        applySupport();
        host.syncSettingsWindow();
        host.setStatus(tr(s.isLogViewer() ? "status.log.enabled" : "status.log.disabledEcho"));
    }
}
