package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import com.editora.run.PythonInterpreters;
import com.editora.run.PythonInterpreters.PythonInterpreter;
import com.editora.run.PythonInterpreters.Source;

import static com.editora.i18n.Messages.tr;

/**
 * Pure: what the Python interpreter status-bar item and its picker show, and what a pick stores.
 *
 * <p>The item reads {@code Python 3.13.5 (.venv)}: the version, then why this interpreter — the environment
 * folder it lives in, else a word for where it came from ({@code activated}, {@code picked}, {@code PATH},
 * {@code setting}). The picker lists the project's environments, the Pythons on PATH, Browse… and Detect
 * automatically.
 */
final class PythonInterpreterView {

    private PythonInterpreterView() {}

    /** One row of the picker. {@code command} is what gets stored ("" for Detect automatically / Browse…). */
    record Choice(Kind kind, String command, String label, String detail) {}

    /** What a picker row does. */
    enum Kind {
        AUTO,
        VENV,
        PATH,
        CURRENT,
        BROWSE
    }

    /** The short word for where an interpreter came from (shown when it is not in an environment folder). */
    static String sourceWord(Source source) {
        return switch (source) {
            case CONFIGURATION -> tr("python.interpreter.source.configuration");
            case PROJECT_CHOICE -> tr("python.interpreter.source.picked");
            case VIRTUAL_ENV -> tr("python.interpreter.source.activated");
            case PROJECT_VENV -> tr("python.interpreter.source.venv");
            case SETTING -> tr("python.interpreter.source.setting");
            case PATH -> tr("python.interpreter.source.path");
        };
    }

    /** {@code "Python 3.13.5"} → {@code "3.13.5"}; anything else → "" (the item then shows the file name). */
    static String parseVersion(String versionLine) {
        if (versionLine == null) {
            return "";
        }
        var m = java.util.regex.Pattern.compile("Python\\s+(\\d+(?:\\.\\d+)*\\S*)")
                .matcher(versionLine.strip());
        return m.find() ? m.group(1) : "";
    }

    /** The status-bar text: {@code Python 3.13.5 (.venv)}, {@code Python 3.12 (PATH)}, {@code python3.11 (picked)}. */
    static String statusText(PythonInterpreter interp, String version) {
        String name = version == null || version.isBlank()
                ? fileName(interp.executablePath())
                : tr("python.interpreter.status", version);
        return name + " (" + reason(interp) + ")";
    }

    /** Why this interpreter: its environment folder's name when it lives in one, else the source word. */
    static String reason(PythonInterpreter interp) {
        if (interp.venv() != null && interp.source() != Source.PATH) {
            Path name = interp.venv().getFileName();
            if (name != null) {
                return interp.source() == Source.VIRTUAL_ENV
                        ? name + ", " + sourceWord(Source.VIRTUAL_ENV)
                        : name.toString();
            }
        }
        return sourceWord(interp.source());
    }

    /** The status-bar tooltip: the full command and why it was chosen. */
    static String tooltip(PythonInterpreter interp) {
        String why =
                switch (interp.source()) {
                    case CONFIGURATION -> tr("python.interpreter.why.configuration");
                    case PROJECT_CHOICE -> tr("python.interpreter.why.picked");
                    case VIRTUAL_ENV -> tr("python.interpreter.why.activated");
                    case PROJECT_VENV -> tr("python.interpreter.why.venv");
                    case SETTING -> tr("python.interpreter.why.setting");
                    case PATH -> tr("python.interpreter.why.path");
                };
        return String.join(" ", interp.command()) + "\n" + why + "\n" + tr("python.interpreter.clickToChange");
    }

    /**
     * The interpreter path to give Pyright: an absolute executable (as the machine the server runs on names it),
     * or null when there is none worth giving (a bare name nobody found on PATH — Pyright's own lookup does no
     * worse).
     */
    static String pythonPathForServer(PythonInterpreter interp) {
        return isAbsolute(interp.executablePath()) ? interp.executablePath() : null;
    }

    /**
     * Whether {@code exe} is an absolute path on any machine: {@code /…} (POSIX, also how a remote host or WSL
     * names it), {@code C:\\…} or {@code \\\\server\\…}. Checked as text, since a remote host's path need not be
     * absolute on this one.
     */
    static boolean isAbsolute(String exe) {
        return exe != null && (exe.startsWith("/") || exe.startsWith("\\\\") || exe.matches("[A-Za-z]:[\\\\/].*"));
    }

    private static String fileName(String exe) {
        try {
            Path p = Path.of(exe).getFileName();
            return p == null ? exe : p.toString();
        } catch (RuntimeException e) {
            return exe;
        }
    }

    /**
     * What a pick stores in the project's state: a path inside the project root is kept relative (so the
     * project folder can move, and a teammate's checkout means the same thing), anything else absolute.
     */
    static String storedChoice(Path projectRoot, String executable) {
        if (projectRoot == null || executable == null) {
            return executable == null ? "" : executable;
        }
        try {
            Path exe = Path.of(executable);
            Path root = projectRoot.toAbsolutePath().normalize();
            if (exe.isAbsolute() && exe.normalize().startsWith(root)) {
                return root.relativize(exe.normalize()).toString().replace('\\', '/');
            }
        } catch (RuntimeException e) {
            // not a path: stored as typed
        }
        return executable;
    }

    /** The names a system Python is found under on PATH, newest first after the plain names. */
    static List<String> pathNames(boolean windows) {
        List<String> names =
                new ArrayList<>(windows ? List.of("python", "python3", "py") : List.of("python3", "python"));
        for (int minor = 14; minor >= 8; minor--) {
            names.add("python3." + minor);
        }
        return names;
    }

    /**
     * The picker rows: Detect automatically first, then the current interpreter, the project's environments,
     * every distinct Python on PATH, and Browse…. Rows with the same executable are shown once.
     *
     * @param current what resolves now (shown first after Detect automatically)
     * @param venvs environment folders found for the project
     * @param onPath name → absolute path of that name on PATH (empty when absent)
     */
    static List<Choice> choices(
            PythonInterpreter current,
            List<Path> venvs,
            boolean windows,
            Function<String, Optional<String>> onPath,
            Function<String, String> versionOf) {
        Map<String, Choice> rows = new LinkedHashMap<>();
        List<Choice> out = new ArrayList<>();
        out.add(new Choice(
                Kind.AUTO, "", tr("python.interpreter.pick.auto"), tr("python.interpreter.pick.autoDetail")));
        if (current != null) {
            String exe = current.executablePath();
            rows.put(
                    exe,
                    new Choice(
                            Kind.CURRENT,
                            exe,
                            labelFor(exe, versionOf),
                            tr("python.interpreter.pick.current", reason(current))));
        }
        for (Path venv : venvs) {
            String exe = PythonInterpreters.venvPython(venv, windows).toString();
            rows.putIfAbsent(exe, new Choice(Kind.VENV, exe, labelFor(exe, versionOf), venv.toString()));
        }
        for (String name : pathNames(windows)) {
            Optional<String> found = onPath.apply(name);
            if (found != null && found.isPresent() && !rows.containsKey(found.get())) {
                String exe = found.get();
                if (windows
                        && exe.replace('/', '\\')
                                .toLowerCase(java.util.Locale.ROOT)
                                .contains("\\windowsapps\\")) {
                    continue; // the Microsoft Store alias, not a Python
                }
                rows.put(exe, new Choice(Kind.PATH, exe, labelFor(exe, versionOf), exe));
            }
        }
        out.addAll(rows.values());
        out.add(new Choice(
                Kind.BROWSE, "", tr("python.interpreter.pick.browse"), tr("python.interpreter.pick.browseDetail")));
        return List.copyOf(out);
    }

    private static String labelFor(String exe, Function<String, String> versionOf) {
        String v = versionOf == null ? "" : parseVersion(versionOf.apply(exe));
        return v.isEmpty() ? fileName(exe) : tr("python.interpreter.status", v) + "  —  " + fileName(exe);
    }
}
