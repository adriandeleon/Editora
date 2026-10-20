package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import javafx.scene.input.KeyCode;

import com.editora.dap.DapModels;
import org.jspecify.annotations.Nullable;

/**
 * Pure text and key decisions of the {@link DebugPanel}: which letter fires which control, what a toolbar
 * tooltip says, how a frame and a thread are named, what Copy Value copies, and the evaluate field's history.
 * Kept apart from the panel so each is unit-tested without a window.
 */
final class DebugPanelText {

    private DebugPanelText() {}

    /** The panel's single-letter controls, by the id of the command each one stands for. */
    enum Control {
        START("debug.start", "C", false),
        PAUSE("debug.pause", "P", false),
        STOP("debug.stop", "K", true),
        RESTART("debug.restart", "R", true),
        STEP_OVER("debug.stepOver", "N", false),
        STEP_INTO("debug.stepInto", "S", false),
        STEP_OUT("debug.stepOut", "F", false),
        RUN_TO_CURSOR("debug.runToCursor", "U", false);

        final String commandId;
        final String letter;
        /** Stop and Restart need Shift (adl, 2026-10-16): a stray k or r typed into the panel ended the program. */
        final boolean shift;

        Control(String commandId, String letter, boolean shift) {
            this.commandId = commandId;
            this.letter = letter;
            this.shift = shift;
        }

        /** How the key is written in a tooltip: {@code N}, {@code Shift+K}. */
        String keyLabel() {
            return shift ? "Shift+" + letter : letter;
        }
    }

    /**
     * The control a key press in the panel fires, or null. A modified chord (Ctrl/Alt/Meta) is never one, Stop and
     * Restart need Shift, and the other letters need it up — so typing a capital letter does not step.
     */
    static @Nullable Control controlFor(KeyCode code, boolean shift, boolean otherModifier) {
        if (code == null || otherModifier) {
            return null;
        }
        for (Control c : Control.values()) {
            if (c.letter.equals(code.getName()) && c.shift == shift) {
                return c;
            }
        }
        return null;
    }

    /** A command title without its palette category: "Debug: Step Over" → "Step Over". */
    static String bareTitle(String title) {
        int colon = title == null ? -1 : title.indexOf(": ");
        return colon < 0 ? (title == null ? "" : title) : title.substring(colon + 2);
    }

    /**
     * A toolbar tooltip: the action, its keymap chord (when the keymap binds one) and the panel letter —
     * "Step Over  (F10 · in this window: N)". The tooltip used to show only the letter, so the keymap's own chord
     * was nowhere on the panel (review C18).
     */
    static String tooltip(String title, String chord, String panelKey, String inPanelLabel) {
        StringBuilder sb = new StringBuilder(bareTitle(title));
        List<String> keys = new ArrayList<>();
        if (chord != null && !chord.isBlank()) {
            keys.add(chord);
        }
        if (panelKey != null && !panelKey.isBlank()) {
            keys.add(inPanelLabel + " " + panelKey);
        }
        if (!keys.isEmpty()) {
            sb.append("  (").append(String.join(" · ", keys)).append(')');
        }
        return sb.toString();
    }

    /** A frame of a JVM hidden class (a lambda's proxy): {@code 0x000000003f040c00.accept(Object)}. */
    private static final Pattern HIDDEN_FRAME = Pattern.compile("^0x[0-9a-fA-F]+\\.(.*)$");

    /** How a frame is named in the call stack: a hidden lambda class shows as {@code <lambda>.accept(Object)}. */
    static String frameName(String name) {
        if (name == null) {
            return "";
        }
        var m = HIDDEN_FRAME.matcher(name);
        return m.matches() ? "<lambda>." + m.group(1) : name;
    }

    /** JVM housekeeping threads: every Java program has them, none is the user's (review C15). */
    private static final Set<String> JVM_THREADS = Set.of(
            "Reference Handler",
            "Finalizer",
            "Signal Dispatcher",
            "Notification Thread",
            "Common-Cleaner",
            "Attach Listener",
            "process reaper",
            "DestroyJavaVM");

    /** The thread's own name: java-debug calls it {@code Thread [main]}. */
    static String threadName(String name) {
        if (name == null) {
            return "";
        }
        if (name.startsWith("Thread [") && name.endsWith("]")) {
            return name.substring("Thread [".length(), name.length() - 1);
        }
        return name;
    }

    /** Whether {@code t} is a JVM housekeeping thread, left out of the thread list. */
    static boolean isJvmThread(DapModels.ThreadInfo t) {
        return t != null && JVM_THREADS.contains(threadName(t.name()));
    }

    /**
     * The threads the selector lists: the JVM's housekeeping threads left out (the stopped thread always stays),
     * the stopped one first.
     */
    static List<DapModels.ThreadInfo> visibleThreads(List<DapModels.ThreadInfo> all, int stoppedId) {
        List<DapModels.ThreadInfo> out = new ArrayList<>();
        for (DapModels.ThreadInfo t : all) {
            if (t.id() == stoppedId) {
                out.addFirst(t);
            } else if (!isJvmThread(t)) {
                out.add(t);
            }
        }
        return out;
    }

    /**
     * What Copy Value puts on the clipboard: a string value without its quotes and escapes (as IntelliJ and VS Code
     * copy it), anything else as shown.
     */
    static String copyValue(String shown) {
        if (shown == null) {
            return "";
        }
        if (shown.length() >= 2 && shown.startsWith("\"") && shown.endsWith("\"")) {
            return unescape(shown.substring(1, shown.length() - 1));
        }
        return shown;
    }

    private static String unescape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                switch (n) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case '"' -> sb.append('"');
                    case '\'' -> sb.append('\'');
                    case '\\' -> sb.append('\\');
                    default -> sb.append(c).append(n);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** One line of a failed watch's message: a long, wrapped error drew over the rows below it (review C17). */
    static String oneLine(String text, int max) {
        String flat =
                text == null ? "" : text.replace("\r", "").replace('\n', ' ').strip();
        return flat.length() <= max ? flat : flat.substring(0, max) + "…";
    }

    /**
     * The evaluate field's history (review C20): Up walks back through what was evaluated, Down forward, and
     * Down past the newest gives back what was being typed. A repeat of the newest entry is not added again.
     */
    static final class History {
        static final int MAX = 100;
        private final List<String> entries = new ArrayList<>();
        /** Where Up/Down is: entries.size() = not browsing. */
        private int cursor;

        private String draft = "";

        void add(String expression) {
            if (expression == null || expression.isBlank()) {
                return;
            }
            if (entries.isEmpty() || !entries.getLast().equals(expression)) {
                entries.add(expression);
                if (entries.size() > MAX) {
                    entries.removeFirst();
                }
            }
            cursor = entries.size();
            draft = "";
        }

        /** The older entry to show for Up from {@code current}, or null when there is none. */
        @Nullable
        String up(String current) {
            if (cursor == 0 || entries.isEmpty()) {
                return null;
            }
            if (cursor == entries.size()) {
                draft = current == null ? "" : current;
            }
            return entries.get(--cursor);
        }

        /** The newer entry for Down (the typed draft past the newest), or null when not browsing. */
        @Nullable
        String down() {
            if (cursor >= entries.size()) {
                return null;
            }
            cursor++;
            return cursor == entries.size() ? draft : entries.get(cursor);
        }

        List<String> entries() {
            return List.copyOf(entries);
        }
    }
}
