package com.editora.ui;

import java.io.IOException;
import java.nio.file.Path;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.editora.config.Settings;
import com.editora.editor.EditorBuffer;
import com.editora.editor.LanguageRegistry;
import com.editora.logviewer.LogFilter;
import com.editora.logviewer.LogLevel;
import com.editora.logviewer.LogTail;
import com.editora.logviewer.LogTailService;
import com.editora.vfs.Vfs;

import static com.editora.i18n.Messages.tr;

/**
 * Owns the server-log-viewer feature, extracted from {@code MainController} as the first
 * <em>feature coordinator</em>: it holds the log state (the tail service + the per-buffer follow/offset/control
 * maps) and all the log logic, and reaches back into the window only through the narrow {@link Host} interface.
 * {@code MainController} supplies an anonymous {@code Host} adapter and keeps thin one-line delegations at the
 * call sites (open/save-as/rename, tab close, settings apply, huge-file load, command registration).
 *
 * <p>Behaviour is identical to the inline version; the value is a smaller controller and a unit-testable seam
 * (the gating/dispatch logic can be exercised against a fake {@code Host}).
 */
final class LogViewerCoordinator {

    private final CoordinatorHost host;
    private final LogTailService logTailService = new LogTailService();
    /** Per-buffer byte offset at which a Follow resumes (load-time EOF, or the tail offset for a huge log). */
    private final Map<EditorBuffer, Long> logLoadOffset = new IdentityHashMap<>();
    /** Active {@code tail -f} handles, keyed by buffer. */
    private final Map<EditorBuffer, LogTailService.Handle> logFollows = new IdentityHashMap<>();
    /** The floating control attached to each log buffer (so error/teardown can reset its Follow toggle). */
    private final Map<EditorBuffer, LogControlBar> logBars = new IdentityHashMap<>();

    LogViewerCoordinator(CoordinatorHost host) {
        this.host = host;
    }

    /** Whether the log viewer is active (default-on setting, suppressed in Simple UI mode). */
    boolean isEnabled() {
        return host.settings().isLogViewer() && !host.simpleModeActive();
    }

    private boolean isLogFile(Path file) {
        return file != null
                && "log".equals(LanguageRegistry.forFileName(file.getFileName().toString()));
    }

    /** Whether {@code file} should get log-aware loading (feature on, a local {@code .log} file). */
    boolean handlesLogFile(Path file) {
        return isEnabled() && isLogFile(file) && Vfs.isLocal(file);
    }

    /** Records the byte offset a later Follow should resume from (called by the file-load path). */
    void recordLoadOffset(EditorBuffer buffer, long offset) {
        logLoadOffset.put(buffer, offset);
    }

    /**
     * Reconciles the log viewer with its setting (mirrors {@code applyHtmlPreviewSupport}): (un)attaches the
     * floating control + level overlay on log buffers and stops any follow when disabled. Runs at startup and
     * on every settings apply.
     */
    void applySupport() {
        boolean on = isEnabled();
        host.forEachBuffer(b -> {
            ensureControl(b);
            b.setLogHighlightEnabled(on && b.isLog());
            if (!on) {
                stopFollow(b);
            }
        });
    }

    /** Attaches/removes the floating log control so it shows only on log buffers with the feature on. */
    void ensureControl(EditorBuffer buffer) {
        boolean want = isEnabled() && buffer.isLog();
        boolean has = buffer.hasLogControl();
        if (want && !has) {
            LogControlBar bar = new LogControlBar(
                    following -> toggleFollow(buffer, following), (min, regex) -> applyFilter(buffer, min, regex));
            logBars.put(buffer, bar);
            buffer.setLogControl(bar);
            buffer.setLogHighlightEnabled(true);
        } else if (!want && has) {
            buffer.setLogControl(null);
            logBars.remove(buffer);
            stopFollow(buffer);
            buffer.setLogHighlightEnabled(false);
        }
    }

    /** Cancels a buffer's follow + drops its per-buffer state (called when its tab closes). */
    void onBufferClosed(EditorBuffer buffer) {
        stopFollow(buffer);
        logBars.remove(buffer);
        logLoadOffset.remove(buffer);
    }

    /** Stops the tail-follow poll thread (window dispose). */
    void shutdown() {
        logTailService.shutdown();
    }

    private void toggleFollow(EditorBuffer buffer, boolean follow) {
        if (follow) {
            startFollow(buffer);
        } else {
            stopFollow(buffer);
            host.setStatus(tr("status.log.followStopped"));
        }
    }

    private void startFollow(EditorBuffer buffer) {
        Path file = buffer.getPath();
        if (file == null || !Vfs.isLocal(file)) {
            host.setStatus(tr("status.log.followUnavailable"));
            LogControlBar bar = logBars.get(buffer);
            if (bar != null) {
                bar.setFollowing(false);
            }
            return;
        }
        stopFollow(buffer);
        long start = logLoadOffset.getOrDefault(buffer, host.fileSize(file));
        buffer.setLogFollowing(true);
        LogTailService.Handle handle = logTailService.follow(file, start, new LogTailService.Listener() {
            @Override
            public void appended(String text) {
                buffer.appendLogText(text);
            }

            @Override
            public void rotated(String fullText) {
                buffer.resetLogContent(fullText);
            }

            @Override
            public void error(String message) {
                logFollows.remove(buffer);
                buffer.setLogFollowing(false);
                LogControlBar bar = logBars.get(buffer);
                if (bar != null) {
                    bar.setFollowing(false);
                }
                host.setStatus(tr("status.log.followError", message));
            }
        });
        logFollows.put(buffer, handle);
        host.setStatus(tr("status.log.following"));
    }

    private void stopFollow(EditorBuffer buffer) {
        LogTailService.Handle handle = logFollows.remove(buffer);
        if (handle != null) {
            handle.stop();
        }
        buffer.setLogFollowing(false);
        Path file = buffer.getPath();
        if (file != null && Vfs.isLocal(file)) {
            try {
                logLoadOffset.put(buffer, LogTail.sizeOf(file)); // resume from EOF on a restart
            } catch (IOException ignored) {
                // keep the prior offset
            }
        }
    }

    /** Applies a level floor + regex (case-insensitive) to the active log buffer; reports a bad regex. */
    private void applyFilter(EditorBuffer buffer, LogLevel min, String regexText) {
        // The query is a case-insensitive regex, falling back to a literal substring when it isn't valid one
        // (so a partial regex typed live, or a plain string with metacharacters, still filters).
        Pattern pattern = regexText == null ? null : LogFilter.compileFilter(regexText.strip());
        buffer.applyLogFilter(min, pattern);
        host.setStatus(min == null && pattern == null ? tr("status.log.filterCleared") : tr("status.log.filtered"));
    }

    // --- commands (palette + the floating control share these) ---

    void toggleFollowCommand() {
        ifLog(() -> {
            EditorBuffer b = host.activeBuffer();
            boolean now = !b.isLogFollowing();
            toggleFollow(b, now);
            LogControlBar bar = logBars.get(b);
            if (bar != null) {
                bar.setFollowing(now && b.isLogFollowing());
            }
        });
    }

    void viewAsLog() {
        EditorBuffer b = host.activeBuffer();
        if (b == null) {
            return;
        }
        if (!isEnabled()) {
            host.setStatus(tr("status.log.disabled"));
            return;
        }
        b.setLogViewForced(true);
        ensureControl(b);
        host.setStatus(tr("status.log.viewAsLog"));
    }

    void setLevelFilter() {
        ifLog(() -> {
            EditorBuffer b = host.activeBuffer();
            List<String> labels = List.of(
                    tr("log.level.all"),
                    tr("log.level.trace"),
                    tr("log.level.debug"),
                    tr("log.level.info"),
                    tr("log.level.warn"),
                    tr("log.level.error"));
            LogLevel[] levels = {null, LogLevel.TRACE, LogLevel.DEBUG, LogLevel.INFO, LogLevel.WARN, LogLevel.ERROR};
            QuickOpen<String> picker = new QuickOpen<>(
                    tr("log.level.pick"), tr("log.level.prompt"), () -> labels, s -> s, s -> "", choice -> {
                        int idx = labels.indexOf(choice);
                        applyFilter(b, idx <= 0 ? null : levels[idx], currentRegexText(b));
                    });
            picker.setOverlayHost(host.overlayHost());
            picker.show(host.window());
        });
    }

    void setRegexFilter() {
        ifLog(() -> {
            EditorBuffer b = host.activeBuffer();
            host.promptText(tr("log.filter.title"), tr("log.filter.label"), "", text -> {
                LogControlBar bar = logBars.get(b);
                LogLevel min = bar != null ? bar.selectedLevel() : b.getLogMinLevel();
                applyFilter(b, min, text);
            });
        });
    }

    void clearFilter() {
        ifLog(() -> {
            EditorBuffer b = host.activeBuffer();
            applyFilter(b, null, null);
            LogControlBar bar = logBars.get(b);
            if (bar != null) {
                bar.clearFilterControls();
            }
        });
    }

    void jumpToNextError() {
        jumpError(true);
    }

    void jumpToPreviousError() {
        jumpError(false);
    }

    /** Moves the caret to the next/previous line at WARN or higher in the active log (wrapping). */
    private void jumpError(boolean forward) {
        ifLog(() -> {
            EditorBuffer b = host.activeBuffer();
            int from = b.getFocusedArea().getCurrentParagraph();
            int target =
                    com.editora.logviewer.LogNavigation.nextLevelLine(b.getContent(), from, forward, LogLevel.WARN);
            if (target < 0) {
                host.setStatus(tr("status.log.noError"));
                return;
            }
            b.jumpToLine(target);
        });
    }

    private String currentRegexText(EditorBuffer buffer) {
        LogControlBar bar = logBars.get(buffer);
        return bar == null ? null : bar.regexText();
    }

    /** Runs {@code action} only when the active buffer is a log buffer and the feature is on. */
    private void ifLog(Runnable action) {
        EditorBuffer b = host.activeBuffer();
        if (!isEnabled()) {
            host.setStatus(tr("status.log.disabled"));
        } else if (b == null || !b.isLog()) {
            host.setStatus(tr("status.log.notLog"));
        } else {
            action.run();
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
