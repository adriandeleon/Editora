package com.editora.run;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import com.editora.run.PythonInterpreters.Inputs;
import com.editora.run.PythonInterpreters.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PythonInterpretersTest {

    @TempDir
    Path tmp;

    private static final Function<String, Optional<String>> NOTHING_ON_PATH = n -> Optional.empty();

    private Path venv(Path dir) throws Exception {
        Files.createDirectories(dir.resolve("bin"));
        Files.writeString(dir.resolve("pyvenv.cfg"), "home = /usr/bin\n");
        Files.writeString(dir.resolve("bin/python"), "");
        return dir;
    }

    private Inputs in(Path file, Path root, String configured, String choice, String setting, Map<String, String> env) {
        return new Inputs(file, root, configured, choice, setting, env, false, Files::isRegularFile, NOTHING_ON_PATH);
    }

    @Test
    void aProjectVenvIsFoundFromAFileDeepInTheProject() throws Exception {
        Path root = tmp.resolve("proj");
        Path v = venv(root.resolve(".venv"));
        Path file = root.resolve("src/pkg/sub/mod.py");
        Files.createDirectories(file.getParent());

        var py = PythonInterpreters.resolve(in(file, root, "", "", "", Map.of()));

        assertEquals(v.resolve("bin/python").toString(), py.executable());
        assertEquals(Source.PROJECT_VENV, py.source());
        assertEquals(v, py.venv());
    }

    @Test
    void aFolderWithoutPyvenvCfgIsNotAVenv() throws Exception {
        Path root = tmp.resolve("proj");
        Files.createDirectories(root.resolve("venv/bin"));
        Files.writeString(root.resolve("venv/bin/python"), "");

        var py = PythonInterpreters.resolve(in(root.resolve("a.py"), root, "", "", "", Map.of()));

        assertEquals(Source.PATH, py.source());
        assertEquals(List.of("python3"), py.command());
    }

    @Test
    void theWalkStopsAtTheProjectRoot() throws Exception {
        venv(tmp.resolve(".venv")); // above the project: not this project's
        Path root = tmp.resolve("proj");
        Files.createDirectories(root);

        var py = PythonInterpreters.resolve(in(root.resolve("a.py"), root, "", "", "", Map.of()));

        assertEquals(Source.PATH, py.source());
    }

    @Test
    void withNoProjectTheWalkStopsAtTheFirstProjectMarker() throws Exception {
        venv(tmp.resolve(".venv"));
        Path repo = tmp.resolve("repo");
        Files.createDirectories(repo.resolve(".git"));
        Path file = repo.resolve("tools/x.py");
        Files.createDirectories(file.getParent());

        assertEquals(
                Source.PATH,
                PythonInterpreters.resolve(in(file, null, "", "", "", Map.of())).source());

        Path v = venv(repo.resolve("venv"));
        var py = PythonInterpreters.resolve(in(file, null, "", "", "", Map.of()));
        assertEquals(v.resolve("bin/python").toString(), py.executable());
    }

    @Test
    void precedenceIsConfigurationThenChoiceThenActivatedThenVenvThenSettingThenPath() throws Exception {
        Path root = tmp.resolve("proj");
        venv(root.resolve(".venv"));
        Path active = venv(tmp.resolve("active"));
        Path file = root.resolve("a.py");
        Map<String, String> env = Map.of("VIRTUAL_ENV", active.toString());

        assertEquals(
                Source.CONFIGURATION,
                PythonInterpreters.resolve(in(file, root, "/opt/py/bin/python3 -X dev", "c", "s", env))
                        .source());
        assertEquals(
                Source.PROJECT_CHOICE,
                PythonInterpreters.resolve(in(file, root, "", "/opt/c/python", "s", env))
                        .source());
        var activated = PythonInterpreters.resolve(in(file, root, "", "", "s", env));
        assertEquals(Source.VIRTUAL_ENV, activated.source());
        assertEquals(active.resolve("bin/python").toString(), activated.executable());
        assertEquals(
                Source.PROJECT_VENV,
                PythonInterpreters.resolve(in(file, root, "", "", "s", Map.of()))
                        .source());
        Path bare = tmp.resolve("bare");
        Files.createDirectories(bare);
        assertEquals(
                Source.SETTING,
                PythonInterpreters.resolve(in(bare.resolve("a.py"), bare, "", "", "/usr/bin/python3.12", Map.of()))
                        .source());
    }

    @Test
    void anExplicitCommandKeepsItsArgumentsAndAnEnvPrefix() {
        Path root = tmp.resolve("proj");
        var py = PythonInterpreters.resolve(in(null, root, "python3 -X dev", "", "", Map.of()));
        assertEquals(List.of("python3", "-X", "dev"), py.command());
        assertEquals("python3", py.executable());
        assertEquals(List.of("-X", "dev"), py.args());

        var withEnv = PythonInterpreters.resolve(in(null, root, "env FOO=1 python3", "", "", Map.of()));
        assertEquals(List.of("env", "FOO=1", "python3"), withEnv.command());
        assertEquals(2, PythonInterpreters.executableIndex(withEnv.command()));
    }

    @Test
    void aRelativeChoiceIsTakenFromTheProjectRoot() throws Exception {
        Path root = tmp.resolve("proj");
        Path v = venv(root.resolve(".venv-312"));

        var py = PythonInterpreters.resolve(in(null, root, "", ".venv-312/bin/python", "", Map.of()));

        assertEquals(v.resolve("bin/python").toString(), py.executable());
        assertEquals(v, py.venv());
    }

    @Test
    void windowsPrefersThePyLauncherAndSkipsTheStoreStub() {
        Function<String, Optional<String>> store =
                n -> Optional.of("C:\\Users\\u\\AppData\\Local\\Microsoft\\WindowsApps\\" + n + ".exe");
        assertEquals(
                List.of("python"), PythonInterpreters.systemPython(true, store).command());

        Function<String, Optional<String>> withPy = n -> switch (n) {
            case "py" -> Optional.of("C:\\Windows\\py.exe");
            case "python3" -> Optional.of("C:\\Users\\u\\AppData\\Local\\Microsoft\\WindowsApps\\python3.exe");
            default -> Optional.empty();
        };
        assertEquals(
                List.of("C:\\Windows\\py.exe", "-3"),
                PythonInterpreters.systemPython(true, withPy).command());

        Function<String, Optional<String>> org = n -> "python".equals(n)
                ? Optional.of("C:\\Python313\\python.exe")
                : n.equals("python3")
                        ? Optional.of("C:\\Users\\u\\AppData\\Local\\Microsoft\\WindowsApps\\python3.exe")
                        : Optional.empty();
        assertEquals(
                List.of("C:\\Python313\\python.exe"),
                PythonInterpreters.systemPython(true, org).command());
    }

    @Test
    void posixUsesPython3ThenPython() {
        assertEquals(
                List.of("/usr/bin/python3"),
                PythonInterpreters.systemPython(
                                false, n -> "python3".equals(n) ? Optional.of("/usr/bin/python3") : Optional.empty())
                        .command());
        assertEquals(
                List.of("/usr/bin/python"),
                PythonInterpreters.systemPython(
                                false, n -> "python".equals(n) ? Optional.of("/usr/bin/python") : Optional.empty())
                        .command());
    }

    @Test
    void windowsVenvLayoutUsesScripts() {
        Path v = Path.of("C:/p/.venv");
        Set<Path> files = Set.of(v.resolve("pyvenv.cfg"), v.resolve("Scripts").resolve("python.exe"));
        assertTrue(PythonInterpreters.isVenv(v, files::contains, true));
        var py = PythonInterpreters.resolve(new Inputs(
                Path.of("C:/p/a.py"), Path.of("C:/p"), "", "", "", Map.of(), true, files::contains, NOTHING_ON_PATH));
        assertEquals(v.resolve("Scripts").resolve("python.exe").toString(), py.executable());
    }

    @Test
    void listVenvsOffersTheFixedNamesThenOtherEnvironmentFolders() throws Exception {
        Path root = tmp.resolve("proj");
        Path a = venv(root.resolve(".venv"));
        Path b = venv(root.resolve("py312-env"));
        Files.createDirectories(root.resolve("src"));

        assertEquals(List.of(a, b), PythonInterpreters.listVenvs(root, false));
    }

    @Test
    void pythonPathHasTheRootAndSrcWhenPresent() throws Exception {
        Path root = tmp.resolve("proj");
        Files.createDirectories(root);
        assertEquals(List.of(root.toString()), PythonInterpreters.pythonPathEntries(root, Files::isDirectory));
        Files.createDirectories(root.resolve("src"));
        assertEquals(
                List.of(root.toString(), root.resolve("src").toString()),
                PythonInterpreters.pythonPathEntries(root, Files::isDirectory));
        assertEquals(List.of(), PythonInterpreters.pythonPathEntries(null, Files::isDirectory));
    }

    @Test
    void venvOfANonVenvInterpreterIsNull() {
        assertNull(PythonInterpreters.venvOf(Path.of("/usr/bin/python3"), p -> false));
    }
}
