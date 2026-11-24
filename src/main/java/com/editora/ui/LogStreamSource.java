package com.editora.ui;

import java.util.ArrayList;
import java.util.List;

import com.editora.run.ProgramArgs;

/**
 * Where a live log comes from: the command that prints it as it grows. Each source turns what the user typed
 * (a container's name, a unit, {@code host:/path}) into that command; Command takes the whole command line.
 * Every source starts with the last {@value #TAIL_LINES} lines, so the tab opens on recent history.
 */
enum LogStreamSource {
    /** Any command that writes a log to its output, run where the project's tools run. */
    COMMAND("command"),
    /** {@code docker logs -f} (or the configured engine's). */
    CONTAINER("container"),
    /** {@code kubectl logs -f} for a pod, deployment or job. */
    KUBERNETES("kubernetes"),
    /** {@code journalctl -f -u} for a systemd unit, with ISO timestamps. */
    JOURNAL("journal"),
    /** {@code tail -F} of a file on another machine, over the system's {@code ssh}. */
    SSH_FILE("ssh");

    /** Lines of history each stream starts with. */
    static final int TAIL_LINES = 1000;

    private final String id;

    LogStreamSource(String id) {
        this.id = id;
    }

    /** The key part of this source's messages ({@code log.stream.kind.<id>}, {@code log.stream.label.<id>}). */
    String id() {
        return id;
    }

    /**
     * The command for {@code typed}. {@code engine} is the container engine's command ({@code docker}).
     *
     * @throws IllegalArgumentException when {@code typed} is empty, has an unclosed quote, or (for SSH) is not
     *     {@code host:/path}
     */
    List<String> argv(String typed, List<String> engine) {
        String text = typed == null ? "" : typed;
        // host:/path is taken as typed (the path is quoted for the remote shell); the rest are shell words.
        List<String> words = this == SSH_FILE ? List.of(text.strip()) : ProgramArgs.parse(text);
        if (words.isEmpty() || words.get(0).isEmpty()) {
            throw new IllegalArgumentException("nothing typed");
        }
        List<String> argv = new ArrayList<>();
        switch (this) {
            case COMMAND -> argv.addAll(words);
            case CONTAINER -> {
                argv.addAll(engine.isEmpty() ? List.of("docker") : engine);
                argv.addAll(List.of("logs", "-f", "--tail", String.valueOf(TAIL_LINES)));
                argv.addAll(words);
            }
            case KUBERNETES -> {
                argv.addAll(List.of("kubectl", "logs", "-f", "--tail=" + TAIL_LINES));
                argv.addAll(words);
            }
            case JOURNAL -> {
                argv.addAll(List.of("journalctl", "-f", "-n", String.valueOf(TAIL_LINES), "-o", "short-iso", "-u"));
                argv.addAll(words);
            }
            case SSH_FILE -> {
                String spec = typed.strip();
                int colon = spec.indexOf(':');
                if (colon <= 0 || colon == spec.length() - 1) {
                    throw new IllegalArgumentException("not host:/path");
                }
                String host = spec.substring(0, colon);
                String path = spec.substring(colon + 1);
                argv.addAll(List.of("ssh", "-o", "BatchMode=yes", host));
                argv.add("tail -n " + TAIL_LINES + " -F " + shellQuote(path));
            }
        }
        return List.copyOf(argv);
    }

    /** The tab's name: what the stream shows, short. */
    String title(String typed) {
        String what = typed == null ? "" : typed.strip();
        return switch (this) {
            case COMMAND -> what;
            case CONTAINER -> what + " (container)";
            case KUBERNETES -> what + " (kubectl)";
            case JOURNAL -> what + " (journal)";
            case SSH_FILE -> what;
        };
    }

    /** {@code s} as one word for a POSIX shell: single-quoted, with its own single quotes escaped. */
    static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
