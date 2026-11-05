package com.editora.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.editora.run.PythonInterpreters.PythonInterpreter;
import com.editora.run.PythonInterpreters.Source;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the Python interpreter item says, what its picker lists, and what a pick stores (adl's decision 1). */
class PythonInterpreterViewTest {

    @org.junit.jupiter.api.BeforeAll
    static void catalog() {
        com.editora.i18n.Messages.init("en");
    }

    private static final PythonInterpreter VENV =
            new PythonInterpreter(List.of("/p/.venv/bin/python"), Source.PROJECT_VENV, Path.of("/p/.venv"));
    private static final PythonInterpreter SYSTEM =
            new PythonInterpreter(List.of("/usr/bin/python3"), Source.PATH, null);

    @Test
    void theItemSaysTheVersionAndWhy() {
        assertEquals("Python 3.13.5 (.venv)", PythonInterpreterView.statusText(VENV, "3.13.5"));
        assertEquals(
                "Python 3.12.1 (" + tr("python.interpreter.source.path") + ")",
                PythonInterpreterView.statusText(SYSTEM, "3.12.1"));
        var activated = new PythonInterpreter(List.of("/w/env/bin/python"), Source.VIRTUAL_ENV, Path.of("/w/env"));
        assertEquals(
                "Python 3.11 (env, " + tr("python.interpreter.source.activated") + ")",
                PythonInterpreterView.statusText(activated, "3.11"));
        assertEquals(
                "python3 (" + tr("python.interpreter.source.path") + ")",
                PythonInterpreterView.statusText(SYSTEM, ""),
                "no version: the executable's name");
    }

    @Test
    void theVersionLineIsParsed() {
        assertEquals("3.13.5", PythonInterpreterView.parseVersion("Python 3.13.5"));
        assertEquals("3.14.0rc1", PythonInterpreterView.parseVersion("Python 3.14.0rc1\n"));
        assertEquals("", PythonInterpreterView.parseVersion("command not found"));
    }

    /** Pyright is given the executable itself — past an {@code env NAME=value} prefix — and only an absolute one. */
    @Test
    void theServerGetsTheAbsoluteExecutable() {
        assertEquals("/p/.venv/bin/python", PythonInterpreterView.pythonPathForServer(VENV));
        var env = new PythonInterpreter(
                List.of("env", "PYTHONWARNINGS=error", "/opt/py/bin/python3", "-X", "dev"), Source.SETTING, null);
        assertEquals("/opt/py/bin/python3", PythonInterpreterView.pythonPathForServer(env));
        assertNull(PythonInterpreterView.pythonPathForServer(
                new PythonInterpreter(List.of("python3"), Source.PATH, null)));
    }

    /** A pick inside the project is stored relative, so the project folder can move; outside, absolute. */
    @Test
    void aPickInsideTheProjectIsStoredRelative() {
        Path root = Path.of("/home/u/proj").toAbsolutePath(); // on Windows, under the current drive
        String inside = root.resolve(".venv").resolve("bin").resolve("python").toString();
        assertEquals(".venv/bin/python", PythonInterpreterView.storedChoice(root, inside));
        assertEquals("/usr/bin/python3", PythonInterpreterView.storedChoice(root, "/usr/bin/python3"));
        assertEquals("/usr/bin/python3", PythonInterpreterView.storedChoice(null, "/usr/bin/python3"));
    }

    /** Detect automatically, the one in use, the project's environments, the PATH Pythons once each, Browse…. */
    @Test
    void thePickerListsEachInterpreterOnce() {
        Map<String, String> onPath = Map.of(
                "python3", "/usr/bin/python3", "python", "/usr/bin/python3", "python3.12", "/usr/local/bin/python3.12");
        var rows = PythonInterpreterView.choices(
                VENV,
                Path.of("/p"),
                List.of(Path.of("/p/.venv"), Path.of("/p/.venv-old")),
                false,
                PythonInterpreterViewTest::posix,
                name -> Optional.ofNullable(onPath.get(name)),
                exe -> exe.endsWith("3.12") ? "Python 3.12.4" : "Python 3.13.5",
                exe -> exe);
        List<PythonInterpreterView.Kind> kinds =
                rows.stream().map(PythonInterpreterView.Choice::kind).toList();
        assertEquals(
                List.of(
                        PythonInterpreterView.Kind.AUTO,
                        PythonInterpreterView.Kind.CURRENT,
                        PythonInterpreterView.Kind.VENV,
                        PythonInterpreterView.Kind.PATH,
                        PythonInterpreterView.Kind.PATH,
                        PythonInterpreterView.Kind.BROWSE),
                kinds);
        assertEquals("/p/.venv/bin/python", rows.get(1).command());
        assertEquals("/p/.venv-old/bin/python", rows.get(2).command());
        assertTrue(rows.get(4).label().startsWith("Python 3.12.4"), rows.get(4).label());
    }

    /**
     * Every venv's executable is {@code python}: rows are named by their environment folder, relative to the project,
     * and a Python on PATH that is another name for one already listed ({@code python3} → {@code python3.13}) is shown
     * once — yet a venv, whose {@code python} links to the system one, is not folded into it (Python review 2, B107).
     */
    @Test
    void environmentsAreNamedByTheirFolderAndPathAliasesAreFolded() {
        Map<String, String> onPath = Map.of("python3", "/usr/bin/python3", "python3.13", "/usr/bin/python3.13");
        Map<String, String> real = Map.of(
                "/usr/bin/python3", "/usr/bin/python3.13",
                "/p/.venv/bin/python", "/usr/bin/python3.13",
                "/p/services/api/venv/bin/python", "/usr/bin/python3.13");
        var rows = PythonInterpreterView.choices(
                VENV,
                Path.of("/p"),
                List.of(Path.of("/p/.venv"), Path.of("/p/services/api/venv")),
                false,
                PythonInterpreterViewTest::posix,
                name -> Optional.ofNullable(onPath.get(name)),
                exe -> "Python 3.13.5",
                exe -> real.getOrDefault(exe, exe));
        List<String> labels =
                rows.stream().map(PythonInterpreterView.Choice::label).toList();
        assertEquals("Python 3.13.5  —  .venv", labels.get(1));
        assertEquals("Python 3.13.5  —  services/api/venv", labels.get(2));
        assertEquals("Python 3.13.5  —  python3", labels.get(3));
        assertEquals(5, rows.size(), "python3.13 is python3 again: " + labels);
    }

    /** Typing "venv" or a folder finds its row: the filter reads the detail and the command, not the label only. */
    @Test
    void theFilterReadsTheFolderAndPath() {
        var rows = PythonInterpreterView.choices(
                SYSTEM,
                Path.of("/p"),
                List.of(Path.of("/p/env-old")),
                false,
                PythonInterpreterViewTest::posix,
                name -> Optional.empty(),
                exe -> "Python 3.13.5",
                exe -> exe);
        PythonInterpreterView.Choice env = rows.get(2);
        assertTrue(PythonInterpreterView.searchKey(env).contains("env-old"), PythonInterpreterView.searchKey(env));
        assertTrue(PythonInterpreterView.searchKey(env).contains("/p/env-old/bin/python"));
    }

    /** A long path is cut in the middle, so its folder name at the end stays readable. */
    @Test
    void longPathsAreCutInTheMiddle() {
        String path = "/tmp/claude-1000/-home-adl-src-adl-Editora/b7fb910e-de8f/scratchpad/project/venv-other";
        String cut = PythonInterpreterView.elideMiddle(path, 40);
        assertEquals(40, cut.length());
        assertTrue(cut.endsWith("project/venv-other"), cut);
        assertTrue(cut.startsWith("/tmp/claude"), cut);
        assertEquals("/short", PythonInterpreterView.elideMiddle("/short", 40));
    }

    /**
     * Picking the row in use keeps the whole command — the {@code env} prefix and arguments — and a path with a space
     * comes back as one token (Python review 2, D107).
     */
    @Test
    void thePickKeepsTheWholeCommand() {
        var env = new PythonInterpreter(
                List.of("env", "PYTHONWARNINGS=error", "/usr/bin/python3", "-X", "dev"), Source.SETTING, null);
        var rows = PythonInterpreterView.choices(
                env,
                null,
                List.of(),
                false,
                PythonInterpreterViewTest::posix,
                name -> Optional.empty(),
                exe -> "",
                exe -> exe);
        String stored = PythonInterpreterView.storedChoice(null, rows.get(1).command());
        assertEquals("env PYTHONWARNINGS=error /usr/bin/python3 -X dev", stored);
        assertEquals(env.command(), com.editora.run.ProgramArgs.tokenize(stored));

        var launcher = new PythonInterpreter(List.of("C:\\Windows\\py.exe", "-3"), Source.PATH, null);
        assertEquals("C:\\Windows\\py.exe -3", PythonInterpreterView.commandText(launcher.command()));

        String spaced = "/Users/John Smith/proj/.venv/bin/python";
        String one = PythonInterpreterView.storedChoice(null, PythonInterpreterView.commandText(List.of(spaced)));
        assertEquals(List.of(spaced), com.editora.run.ProgramArgs.tokenize(one));
        Path root = Path.of("/Users/John Smith/proj").toAbsolutePath();
        String inProject =
                root.resolve(".venv").resolve("bin").resolve("python").toString();
        assertEquals(
                ".venv/bin/python",
                PythonInterpreterView.storedChoice(root, PythonInterpreterView.commandText(List.of(inProject))));
    }

    /** A POSIX machine's name for a path: as a remote host or WSL names it, also when Editora runs on Windows. */
    private static String posix(Path p) {
        return p.toString().replace('\\', '/');
    }

    /**
     * On Windows, a WSL or SSH project's environment is offered as that machine names it — {@code /p/.venv/bin/python},
     * not {@code \p\.venv\bin\python} — so it is the same row as the interpreter in use, and a pick stores a path
     * the remote Python is at.
     */
    @Test
    void aRemoteEnvironmentIsOfferedAsThatMachineNamesIt() {
        var rows = PythonInterpreterView.choices(
                VENV,
                Path.of("/p"),
                List.of(Path.of("/p/.venv")),
                false,
                PythonInterpreterViewTest::posix,
                name -> Optional.empty(),
                exe -> "",
                exe -> exe);
        assertEquals(
                List.of(
                        PythonInterpreterView.Kind.AUTO,
                        PythonInterpreterView.Kind.CURRENT,
                        PythonInterpreterView.Kind.BROWSE),
                rows.stream().map(PythonInterpreterView.Choice::kind).toList(),
                "the environment in use is listed once");
        assertEquals("/p/.venv/bin/python", rows.get(1).command());
    }

    /** The Microsoft Store alias is not a Python. */
    @Test
    void theWindowsStoreAliasIsNotListed() {
        var rows = PythonInterpreterView.choices(
                null,
                null,
                List.of(),
                true,
                Path::toString,
                name -> name.equals("python")
                        ? Optional.of("C:\\Users\\u\\AppData\\Local\\Microsoft\\WindowsApps\\python.exe")
                        : Optional.empty(),
                exe -> "",
                exe -> exe);
        assertTrue(rows.stream().noneMatch(r -> r.kind() == PythonInterpreterView.Kind.PATH));
    }

    /** A remote host's path is absolute as text, whatever this machine's own path rules are. */
    @Test
    void anAbsolutePathIsRecognisedOnAnyMachine() {
        assertTrue(PythonInterpreterView.isAbsolute("/home/u/proj/.venv/bin/python"));
        assertTrue(PythonInterpreterView.isAbsolute("C:\\py\\python.exe"));
        assertTrue(PythonInterpreterView.isAbsolute("\\\\server\\share\\python.exe"));
        org.junit.jupiter.api.Assertions.assertFalse(PythonInterpreterView.isAbsolute("python3"));
    }
}
