package com.editora.dap;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jspecify.annotations.Nullable;

/**
 * The last lines a stdio debug adapter wrote to its standard error. The stream has to be drained (an undrained
 * pipe blocks the adapter), and what it says is the only explanation there is when the adapter fails to start
 * the program — so it is kept, bounded, logged, and added to the failure message instead of being thrown away.
 */
final class AdapterStderr {

    private static final Logger LOG = Logger.getLogger(AdapterStderr.class.getName());

    static final int MAX_LINES = 20;
    static final int MAX_LINE_CHARS = 500;

    private final ArrayDeque<String> lines = new ArrayDeque<>();

    /** Starts draining {@code process}'s standard error on a virtual thread. */
    static AdapterStderr drain(Process process) {
        AdapterStderr tail = new AdapterStderr();
        Thread.ofVirtual().name("dap-adapter-stderr").start(() -> {
            try (BufferedReader r =
                    new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    tail.add(line);
                }
            } catch (IOException e) {
                // the adapter is gone; what was read is kept
            }
        });
        return tail;
    }

    void add(String line) {
        if (line == null || line.isBlank()) {
            return;
        }
        String kept = line.length() > MAX_LINE_CHARS ? line.substring(0, MAX_LINE_CHARS) + "…" : line;
        LOG.log(Level.FINE, "debug adapter: {0}", kept);
        synchronized (lines) {
            lines.addLast(kept);
            while (lines.size() > MAX_LINES) {
                lines.removeFirst();
            }
        }
    }

    /** The kept lines, oldest first, joined by newlines ("" when the adapter said nothing). */
    String text() {
        synchronized (lines) {
            return String.join("\n", lines);
        }
    }

    /** {@code "\n<what the adapter said>"} for a failure message, or "" when there is nothing (or no tail). */
    static String suffix(@Nullable AdapterStderr tail) {
        String text = tail == null ? "" : tail.text();
        return text.isEmpty() ? "" : "\n" + text;
    }
}
