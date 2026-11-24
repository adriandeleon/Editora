package com.editora.ui;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

import javafx.application.Platform;

import com.editora.editor.logviewer.LogView;
import com.editora.process.ExecSpec;
import com.editora.process.ExecTarget;
import org.jspecify.annotations.Nullable;

/**
 * A command whose output is a log as it grows ({@code docker logs -f}, {@code journalctl -f}), read into a tab.
 * Its standard output and error are read line by line on two daemon threads, so a line is never split between
 * them, and handed to the FX thread in batches: one {@link Platform#runLater} drains whatever arrived meanwhile.
 *
 * <p>Paused, the lines are kept back (at most {@link LogView#FOLLOW_CAP} characters of them, the oldest dropped)
 * and delivered on resume — like a paused Follow of a file, which reads what was written meanwhile.
 */
final class LogStream {

    /** Where the stream's lines and its end go, on the FX thread. */
    interface Sink {
        void text(String text);

        /** The command ended: {@code exit} is its exit code, {@code error} why it could not start (else null). */
        void ended(int exit, @Nullable String error);
    }

    private final ExecTarget target;
    private final ExecSpec spec;
    private final Sink sink;
    private final StringBuilder pending = new StringBuilder();
    private boolean drainQueued;
    private boolean paused;
    private volatile boolean stopped;
    private volatile @Nullable Process process;

    LogStream(ExecTarget target, ExecSpec spec, Sink sink) {
        this.target = target;
        this.spec = spec;
        this.sink = sink;
    }

    /** Starts the command off the FX thread (a remote target may take a while to answer). */
    void start() {
        Thread starter = new Thread(this::run, "log-stream");
        starter.setDaemon(true);
        starter.start();
    }

    private void run() {
        Process p;
        try {
            p = target.start(spec);
        } catch (IOException | RuntimeException e) {
            String why = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            Platform.runLater(() -> sink.ended(-1, why));
            return;
        }
        process = p;
        if (stopped) {
            p.destroy();
            return;
        }
        Thread err = new Thread(() -> readLines(p.getErrorStream(), this::arrived), "log-stream-err");
        err.setDaemon(true);
        err.start();
        readLines(p.getInputStream(), this::arrived); // this thread reads stdout
        try {
            err.join();
            int exit = p.waitFor();
            Platform.runLater(() -> {
                drain();
                if (!stopped) {
                    sink.ended(exit, null);
                }
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void readLines(InputStream in, Consumer<String> line) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            for (String l = r.readLine(); l != null; l = r.readLine()) {
                line.accept(l);
            }
        } catch (IOException ignored) {
            // the process went away: its end is reported by waitFor
        }
    }

    private void arrived(String line) {
        if (stopped) {
            return;
        }
        boolean queue;
        synchronized (pending) {
            pending.append(line).append('\n');
            if (pending.length() > LogView.FOLLOW_CAP) {
                int cut = pending.indexOf("\n", pending.length() - LogView.FOLLOW_CAP);
                pending.delete(0, cut < 0 ? pending.length() - LogView.FOLLOW_CAP : cut + 1);
            }
            queue = !drainQueued && !paused;
            drainQueued |= queue;
        }
        if (queue) {
            Platform.runLater(this::drain);
        }
    }

    /** Hands what arrived to the sink, unless paused or stopped. FX thread. */
    private void drain() {
        String text;
        synchronized (pending) {
            drainQueued = false;
            if (paused || stopped || pending.isEmpty()) {
                return;
            }
            text = pending.toString();
            pending.setLength(0);
        }
        sink.text(text);
    }

    /** Holds the lines back (true) or delivers them, with those kept back meanwhile (false). FX thread. */
    void setPaused(boolean paused) {
        synchronized (pending) {
            this.paused = paused;
        }
        if (!paused) {
            drain();
        }
    }

    /** Characters arrived and not yet delivered (held while paused). */
    int heldChars() {
        synchronized (pending) {
            return pending.length();
        }
    }

    /** Ends the command; nothing more reaches the sink. */
    void stop() {
        stopped = true;
        Process p = process;
        if (p != null) {
            p.destroy();
        }
    }
}
