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
                List.of(Path.of("/p/.venv"), Path.of("/p/.venv-old")),
                false,
                PythonInterpreterViewTest::posix,
                name -> Optional.ofNullable(onPath.get(name)),
                exe -> exe.endsWith("3.12") ? "Python 3.12.4" : "Python 3.13.5");
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
                List.of(Path.of("/p/.venv")),
                false,
                PythonInterpreterViewTest::posix,
                name -> Optional.empty(),
                exe -> "");
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
                List.of(),
                true,
                Path::toString,
                name -> name.equals("python")
                        ? Optional.of("C:\\Users\\u\\AppData\\Local\\Microsoft\\WindowsApps\\python.exe")
                        : Optional.empty(),
                exe -> "");
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
