package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import javafx.scene.input.KeyCode;

import com.editora.dap.DapModels;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

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
    static String tooltip(String title, @Nullable String chord, String panelKey, String inPanelLabel) {
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

    /**
     * Commands whose key is bound to a wrapper command instead: CUA and Sublime put F11 on "Step Into, or Full Screen
     * when not paused", so Step Into itself had no chord in its tooltip or the Run menu (review 2 C108).
     */
    private static final java.util.Map<String, String> CHORD_ALIASES =
            java.util.Map.of("debug.stepInto", "debug.stepIntoOrFullScreen");

    /** The chord {@code lookup} gives {@code id}, else the chord of the command that wraps it; null when none. */
    static @Nullable String chordOf(java.util.function.Function<String, @Nullable String> lookup, String id) {
        String chord = lookup.apply(id);
        if ((chord == null || chord.isBlank()) && CHORD_ALIASES.containsKey(id)) {
            chord = lookup.apply(CHORD_ALIASES.get(id));
        }
        return chord == null || chord.isBlank() ? null : chord;
    }

    /** The exception-breakpoint button's tooltip: which of caught / uncaught exceptions stop the program. */
    static String exceptionsTip(boolean caught, boolean uncaught) {
        String which = caught && uncaught
                ? tr("debugpanel.exceptions.both")
                : caught
                        ? tr("debugpanel.exceptions.caught")
                        : uncaught ? tr("debugpanel.exceptions.uncaught") : tr("debugpanel.exceptions.off");
        return tr("debugpanel.exceptions.tip", which);
    }

    /** A frame of a JVM hidden class (a lambda's proxy): {@code 0x000000003f040c00.accept(Object)}. */
    private static final Pattern HIDDEN_FRAME = Pattern.compile("^0x[0-9a-fA-F]+\\.(.*)$");

    /**
     * java-debug's name for a frame running a method that hot code replace has since replaced:
     * {@code Main.Obsolete method()[native method]}.
     */
    private static final Pattern OBSOLETE_FRAME =
            Pattern.compile("^(.*?)\\.?Obsolete method\\(\\)(?:\\[native method])?$");

    /** Whether java-debug names this frame as running a method hot code replace has since replaced. */
    static boolean isObsoleteFrame(String name) {
        return name != null && OBSOLETE_FRAME.matcher(name).matches();
    }

    /**
     * How a frame is named in the call stack: a hidden lambda class shows as {@code <lambda>.accept(Object)}, and
     * a method hot code replace left on its old code as {@code Main — old code (Restart Frame runs it again)}
     * (review 2, B103) rather than java-debug's {@code Main.Obsolete method()[native method]}.
     */
    static String frameName(String name) {
        if (name == null) {
            return "";
        }
        var obsolete = OBSOLETE_FRAME.matcher(name);
        if (obsolete.matches()) {
            return com.editora.i18n.Messages.tr("debugpanel.obsoleteFrame", obsolete.group(1));
        }
        var m = HIDDEN_FRAME.matcher(name);
        return m.matches() ? "<lambda>." + m.group(1) : name;
    }

    /**
     * Pure: a call stack as text to paste into a bug report or a chat, top frame first — the thread's name, then one
     * {@code at name(File.java:12)} line per frame, as a Java stack trace reads. A frame without a file shows only
     * its name; {@code threadName} may be blank.
     */
    static String callStackText(@Nullable String threadName, List<com.editora.dap.DapModels.StackFrameInfo> frames) {
        StringBuilder out = new StringBuilder();
        if (threadName != null && !threadName.isBlank()) {
            out.append(com.editora.i18n.Messages.tr("debugpanel.copiedStackThread", threadName.strip()))
                    .append('\n');
        }
        for (com.editora.dap.DapModels.StackFrameInfo f : frames) {
            out.append("    at ").append(frameName(f.name()));
            if (f.file() != null) {
                out.append('(').append(DebugValues.sourceName(f.file()));
                if (f.line() >= 0) {
                    out.append(':').append(f.line() + 1);
                }
                out.append(')');
            }
            out.append('\n');
        }
        return out.toString();
    }

    /**
     * Pure: the index typed into Go to Index, when it is one of a container's {@code size} elements: digits, maybe
     * in brackets ({@code [1500]}) or with {@code _} / {@code ,} separators. Null otherwise.
     */
    static @Nullable Integer parseIndex(@Nullable String text, int size) {
        if (text == null) {
            return null;
        }
        String digits = text.strip()
                .replaceAll("^\\[|\\]$", "")
                .replace("_", "")
                .replace(",", "")
                .strip();
        if (digits.isEmpty() || !digits.chars().allMatch(Character::isDigit) || digits.length() > 10) {
            return null;
        }
        long index = Long.parseLong(digits);
        return index < size ? (int) index : null;
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
            } else if (!isJvmThread(t) && !(all.size() > 1 && isPackageManagerThread(t))) {
                out.add(t);
            }
        }
        return out;
    }

    /**
     * The Threads view's rows: {@link #visibleThreads}'s filter (the JVM's own housekeeping threads and a package
     * manager's left out, unless suspended), in the adapter's order. Pure.
     */
    static List<DapModels.ThreadStatus> visibleThreadStatuses(List<DapModels.ThreadStatus> all) {
        List<DapModels.ThreadStatus> out = new ArrayList<>();
        for (DapModels.ThreadStatus t : all) {
            DapModels.ThreadInfo info = new DapModels.ThreadInfo(t.id(), t.name());
            if (t.paused() || (!isJvmThread(info) && !(all.size() > 1 && isPackageManagerThread(info)))) {
                out.add(t);
            }
        }
        return out;
    }

    /** The message key of a thread's state in the Threads view: running, or suspended and why. Pure. */
    static String threadStateKey(DapModels.ThreadStatus t) {
        if (!t.paused()) {
            return "debugpanel.thread.running";
        }
        return switch (t.reason() == null ? "" : t.reason()) {
            case "breakpoint" -> "debugpanel.thread.breakpoint";
            case "step" -> "debugpanel.thread.step";
            case "exception" -> "debugpanel.thread.exception";
            case "pause" -> "debugpanel.thread.pause";
            case "entry" -> "debugpanel.thread.entry";
            case "function breakpoint" -> "debugpanel.thread.method";
            case "data breakpoint" -> "debugpanel.thread.field";
            default -> "debugpanel.thread.suspended";
        };
    }

    private static final java.util.regex.Pattern PACKAGE_MANAGER_THREAD =
            java.util.regex.Pattern.compile("^(?:npm|npx|pnpm|yarn|bun)(?:\\.cmd)? \\[\\d+\\]$");

    /**
     * Whether {@code t} is the package manager js-debug attached to while it starts a script ({@code npm [4242]}):
     * it runs no code of the user's, so beside the script's own process it only crowds the dropdown (review D109).
     */
    static boolean isPackageManagerThread(DapModels.ThreadInfo t) {
        return t != null
                && t.name() != null
                && PACKAGE_MANAGER_THREAD.matcher(t.name().strip()).matches();
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

    /** What a Variables row adds after a value it could not show in full (review 3, B201/B202). */
    static String valueNote(com.editora.dap.DapModels.ValueNote note) {
        return switch (note) {
            case NOT_LOADED -> tr("debugpanel.note.notLoaded");
            case NO_TO_STRING_AFTER_PAUSE -> tr("debugpanel.note.afterPause");
            case TO_STRING_FAILED -> tr("debugpanel.note.toStringFailed");
            case LOADING -> tr("debugpanel.note.loading");
        };
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
