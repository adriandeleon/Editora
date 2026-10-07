package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import javafx.application.Platform;

import com.editora.process.CommandLog;

/**
 * The {@link CommandLog} behind the Output window's Git tab: marshals what {@code GitService} reports on its
 * worker and stream-reader threads onto the FX thread.
 *
 * <p>A finished command and the start of a long one (a clone) are passed straight on. Progress lines are
 * queued and drained together, so the hundred updates git counts through a phase in a burst cost one console
 * edit a drain instead of one each.
 */
final class GitConsoleLog implements CommandLog {

    private final Consumer<Entry> onRecord;
    private final Consumer<List<String>> onStarted;
    private final BiConsumer<String, Boolean> onProgress;

    /** Lines waiting for the FX thread; guarded by itself. */
    private final List<Map.Entry<String, Boolean>> pending = new ArrayList<>();

    /** Every callback runs on the FX thread; {@code onProgress} takes a line and whether it is transient. */
    GitConsoleLog(Consumer<Entry> onRecord, Consumer<List<String>> onStarted, BiConsumer<String, Boolean> onProgress) {
        this.onRecord = onRecord;
        this.onStarted = onStarted;
        this.onProgress = onProgress;
    }

    @Override
    public void record(Entry entry) {
        Platform.runLater(() -> onRecord.accept(entry));
    }

    @Override
    public void started(List<String> argv) {
        Platform.runLater(() -> onStarted.accept(argv));
    }

    @Override
    public void progress(String line, boolean transientLine) {
        boolean schedule;
        synchronized (pending) {
            schedule = pending.isEmpty();
            pending.add(Map.entry(line, transientLine));
        }
        if (schedule) {
            Platform.runLater(this::drain);
        }
    }

    private void drain() {
        List<Map.Entry<String, Boolean>> lines;
        synchronized (pending) {
            lines = new ArrayList<>(pending);
            pending.clear();
        }
        for (Map.Entry<String, Boolean> line : visible(lines)) {
            onProgress.accept(line.getKey(), line.getValue());
        }
    }

    /** {@code lines} without the transient ones that the next line of the same drain would overwrite anyway. */
    static List<Map.Entry<String, Boolean>> visible(List<Map.Entry<String, Boolean>> lines) {
        List<Map.Entry<String, Boolean>> shown = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (!lines.get(i).getValue() || i + 1 == lines.size()) {
                shown.add(lines.get(i));
            }
        }
        return shown;
    }
}
