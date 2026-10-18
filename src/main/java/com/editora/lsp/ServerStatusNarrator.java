package com.editora.lsp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns a language server's raw status stream into the lines worth putting in the echo area and the message
 * log — above all, one that says when the server is done starting. Pure: the caller supplies the clock and
 * calls {@link #tick} once things have been quiet for {@link #QUIET_MILLIS}.
 *
 * <p>Passed through as they came, jdtls's statuses never reached a conclusion. Each job's Begin was logged
 * ("Publish Diagnostics (0%)") and its End, which usually has no text, was not, so the last line read as if
 * the work had stalled. Its own "Ready" arrives mid-startup with more importing after it, and "ServiceReady" is
 * an internal name. So:
 *
 * <ul>
 *   <li>Until the server is ready, every status shows as before (a repeat of the line just shown is skipped).
 *       Once it is ready and no work is outstanding for {@link #QUIET_MILLIS}, one {@link Line#ready()} line
 *       says so; the server's own "Ready"/"ServiceReady" words are not shown.
 *   <li>After that, background work (diagnostics, searches — every edit starts some) is shown only when it is
 *       still running after {@link #QUIET_MILLIS}, and work that was shown ends with another ready line, so the
 *       log never stops on a job that looks unfinished.
 * </ul>
 */
public final class ServerStatusNarrator {

    /** How long the server must have been quiet before it is called ready, or work must run before it shows. */
    public static final long QUIET_MILLIS = 800;

    /** A line to show: either the server's own {@code text}, or the narrator's "ready" line ({@code text} null). */
    public record Line(String serverId, String text) {
        public boolean ready() {
            return text == null;
        }
    }

    private static final class State {
        boolean ready; // the server says it is ready (and nothing since said it is starting again)
        boolean announced; // the ready line has been shown for this run of the server
        boolean failed;
        boolean narratesStartup; // sends its own "Starting" statuses (jdtls): only its own ready counts
        int outstanding; // progress Begins without an End yet
        String held; // a Begin's text not shown yet (after the ready line), with when it began
        long heldSince;
        boolean busyShown; // background work was shown, so its end owes a ready line
        long lastActivity;
        String lastShown;
    }

    private final Map<String, State> states = new HashMap<>();

    /**
     * Takes one status from {@code serverId} at {@code now}: {@code type} is what {@code LanguageServerSession}
     * reports (a JDT LS {@code language/status} type, "Progress"/"ProgressEnd", "Message", "Error",
     * "Launching"). Returns the line to show now, or null for none.
     */
    public Line onStatus(String serverId, String type, String message, long now) {
        String key = serverId == null ? "" : serverId;
        String t = type == null ? "" : type.toLowerCase(Locale.ROOT);
        String msg = message == null || message.isBlank() ? null : message.strip();
        if (t.equals("launching")) {
            states.remove(key); // a new process: whatever the last one said no longer holds
            return null;
        }
        State s = states.computeIfAbsent(key, k -> new State());
        s.lastActivity = now;
        switch (t) {
            case "progress" -> {
                s.outstanding++;
                if (s.announced) {
                    if (msg != null && s.held == null) {
                        s.held = msg;
                        s.heldSince = now;
                    }
                    return null;
                }
                return show(serverId, s, msg);
            }
            case "progressend" -> {
                s.outstanding = Math.max(0, s.outstanding - 1);
                if (s.announced) {
                    if (s.outstanding == 0) {
                        s.held = null;
                        if (s.busyShown) {
                            s.busyShown = false;
                            s.lastShown = null;
                            return new Line(serverId, null);
                        }
                    }
                    return null;
                }
                return show(serverId, s, msg);
            }
            case "error" -> {
                s.failed = true;
                s.ready = false;
                s.announced = false;
                s.outstanding = 0;
                s.held = null;
                s.busyShown = false;
                return show(serverId, s, msg);
            }
            case "starting" -> {
                s.failed = false;
                s.narratesStartup = true;
                if (!s.announced) {
                    s.ready = false; // jdtls: the handshake is done but the project is still importing
                }
                return show(serverId, s, msg);
            }
            case "started" -> {
                // jdtls's "Ready" here comes before the import is finished; the ready line comes later.
                return isReadyWord(msg) ? null : show(serverId, s, msg);
            }
            case "projectstatus" -> {
                return "ok".equalsIgnoreCase(msg) ? null : show(serverId, s, msg);
            }
            default -> {
                if (t.contains("ready")) {
                    if (msg == null && s.narratesStartup) {
                        // The handshake finishing (the session's own wordless ready): jdtls only starts
                        // importing the project after it, and says itself when that is done.
                        return null;
                    }
                    s.ready = true;
                    s.failed = false;
                    return isReadyWord(msg) ? null : show(serverId, s, msg);
                }
                return show(serverId, s, msg);
            }
        }
    }

    /**
     * Lines that are due at {@code now} — servers that have settled since they became ready, and background
     * work that has run long enough to be worth showing.
     */
    public List<Line> tick(long now) {
        List<Line> out = new ArrayList<>();
        states.forEach((key, s) -> {
            String serverId = key.isEmpty() ? null : key;
            if (!s.announced && s.ready && !s.failed && s.outstanding == 0 && now - s.lastActivity >= QUIET_MILLIS) {
                s.announced = true;
                s.lastShown = null;
                out.add(new Line(serverId, null));
            } else if (s.announced && s.held != null && s.outstanding > 0 && now - s.heldSince >= QUIET_MILLIS) {
                s.busyShown = true;
                s.lastShown = s.held;
                out.add(new Line(serverId, s.held));
                s.held = null;
            }
        });
        return out;
    }

    /** Whether a later {@link #tick} could still produce a line. */
    public boolean pending() {
        for (State s : states.values()) {
            if ((!s.announced && s.ready && !s.failed) || (s.announced && s.held != null)) {
                return true;
            }
        }
        return false;
    }

    /** Forgets everything said by {@code serverId}, or by every server when it is null. */
    public void forget(String serverId) {
        if (serverId == null) {
            states.clear();
        } else {
            states.remove(serverId);
        }
    }

    private static Line show(String serverId, State s, String msg) {
        if (msg == null || msg.equals(s.lastShown)) {
            return null;
        }
        s.lastShown = msg;
        return new Line(serverId, msg);
    }

    private static boolean isReadyWord(String msg) {
        return msg == null || msg.equalsIgnoreCase("ready") || msg.equalsIgnoreCase("serviceready");
    }
}
