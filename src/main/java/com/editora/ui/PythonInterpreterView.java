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
import org.jspecify.annotations.Nullable;

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
        String why = switch (interp.source()) {
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
    static @Nullable String pythonPathForServer(PythonInterpreter interp) {
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
     * What a pick stores in the project's state: the whole command — an {@code env NAME=value} prefix and arguments
     * such as {@code -X dev} or {@code py.exe -3} kept (Python review 2, D107) — as one line {@code ProgramArgs.tokenize}
     * reads back, a token with a space quoted. An executable inside the project root is kept relative (so the project
     * folder can move, and a teammate's checkout means the same thing), anything else absolute.
     */
    static String storedChoice(@Nullable Path projectRoot, String command) {
        if (command == null || command.isBlank()) {
            return "";
        }
        List<String> tokens = new ArrayList<>(com.editora.run.ProgramArgs.tokenize(command));
        int exe = PythonInterpreters.executableIndex(tokens);
        if (projectRoot != null) {
            try {
                Path path = Path.of(tokens.get(exe));
                Path root = projectRoot.toAbsolutePath().normalize();
                if (path.isAbsolute() && path.normalize().startsWith(root)) {
                    tokens.set(exe, root.relativize(path.normalize()).toString().replace('\\', '/'));
                }
            } catch (RuntimeException e) {
                // not a path: stored as typed
            }
        }
        return commandText(tokens);
    }

    /** {@code argv} as one line {@code ProgramArgs.tokenize} splits back into it: a token with a space is quoted. */
    static String commandText(List<String> argv) {
        List<String> words = new ArrayList<>();
        for (String token : argv) {
            boolean plain = !token.isEmpty()
                    && token.chars().noneMatch(c -> Character.isWhitespace(c) || c == '"' || c == '\'');
            words.add(plain ? token : token.indexOf('"') < 0 ? '"' + token + '"' : "'" + token + "'");
        }
        return String.join(" ", words);
    }

    /** {@code text} cut in the middle to {@code max} characters, so a long path keeps its folder name at the end. */
    static String elideMiddle(String text, int max) {
        if (text == null || text.length() <= max || max < 5) {
            return text == null ? "" : text;
        }
        int head = (max - 1) / 2;
        int tail = max - 1 - head;
        return text.substring(0, head) + "…" + text.substring(text.length() - tail);
    }

    /** How long a picker row's path may be before it is cut in the middle. */
    static final int DETAIL_MAX = 60;

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
     * every distinct Python on PATH, and Browse…. An environment is named by its folder ({@code .venv},
     * {@code services/api/.venv}) — every venv's executable is just {@code python}, so the rows read alike (Python
     * review 2, B107) — and a long path is cut in the middle. Rows that run the same interpreter are shown once: an
     * environment by its executable, a Python on PATH by the file it resolves to ({@code python3} →
     * {@code python3.13}); a venv's {@code python} links to the system one, yet is a different environment.
     *
     * @param current what resolves now (shown first after Detect automatically)
     * @param projectRoot the folder environment names are relative to (null: their folder names)
     * @param venvs environment folders found for the project, in Editora's view of the files
     * @param windows whether the machine the interpreter runs on is Windows ({@code Scripts\python.exe})
     * @param toTarget a path in Editora's view → as that machine names it ({@link Path#toString} here; a remote
     *     host or WSL is POSIX, also when Editora runs on Windows)
     * @param onPath name → absolute path of that name on PATH (empty when absent)
     * @param realPath an executable → the file it resolves to (itself when that cannot be known)
     */
    static List<Choice> choices(
            PythonInterpreter current,
            @Nullable Path projectRoot,
            List<Path> venvs,
            boolean windows,
            Function<Path, String> toTarget,
            Function<String, Optional<String>> onPath,
            Function<String, String> versionOf,
            Function<String, String> realPath) {
        Map<String, Choice> rows = new LinkedHashMap<>();
        List<Choice> out = new ArrayList<>();
        out.add(new Choice(
                Kind.AUTO, "", tr("python.interpreter.pick.auto"), tr("python.interpreter.pick.autoDetail")));
        if (current != null) {
            String exe = current.executablePath();
            String name = current.venv() != null ? envName(projectRoot, current.venv()) : fileName(exe);
            String detail =
                    tr("python.interpreter.pick.current", reason(current)) + "  " + elideMiddle(exe, DETAIL_MAX);
            rows.put(
                    current.venv() != null ? exe : realPath.apply(exe),
                    new Choice(Kind.CURRENT, commandText(current.command()), label(name, exe, versionOf), detail));
        }
        for (Path venv : venvs) {
            String exe = toTarget.apply(PythonInterpreters.venvPython(venv, windows));
            String detail = elideMiddle(toTarget.apply(venv), DETAIL_MAX);
            rows.putIfAbsent(
                    exe,
                    new Choice(
                            Kind.VENV,
                            commandText(List.of(exe)),
                            label(envName(projectRoot, venv), exe, versionOf),
                            detail));
        }
        for (String name : pathNames(windows)) {
            Optional<String> found = PythonInterpreters.real(onPath.apply(name)); // not the Microsoft Store alias
            if (found.isPresent()) {
                String exe = found.get();
                rows.putIfAbsent(
                        realPath.apply(exe),
                        new Choice(
                                Kind.PATH,
                                commandText(List.of(exe)),
                                label(fileName(exe), exe, versionOf),
                                elideMiddle(exe, DETAIL_MAX)));
            }
        }
        out.addAll(rows.values());
        out.add(new Choice(
                Kind.BROWSE, "", tr("python.interpreter.pick.browse"), tr("python.interpreter.pick.browseDetail")));
        return List.copyOf(out);
    }

    /** What the picker's filter matches: the row's words, and its whole command (a path cut in the middle too). */
    static String searchKey(Choice c) {
        return c.label() + " " + c.detail() + " " + c.command();
    }

    /** An environment folder's name: relative to the project root when inside it, else its own name. */
    static String envName(@Nullable Path projectRoot, Path venv) {
        try {
            if (projectRoot != null
                    && venv.normalize().startsWith(projectRoot.normalize())
                    && !venv.equals(projectRoot)) {
                return projectRoot
                        .normalize()
                        .relativize(venv.normalize())
                        .toString()
                        .replace('\\', '/');
            }
        } catch (RuntimeException e) {
            // another file system's path: its own name below
        }
        Path name = venv.getFileName();
        return name == null ? venv.toString() : name.toString();
    }

    private static String label(String name, String exe, Function<String, String> versionOf) {
        String v = versionOf == null ? "" : parseVersion(versionOf.apply(exe));
        return v.isEmpty() ? name : tr("python.interpreter.status", v) + "  —  " + name;
    }
}
