package com.editora.dap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What Debug File hands debugpy for a web app's file, and when a launch follows the reloader's child. */
class PythonWebLaunchTest {

    private static final String FLASK = "from flask import Flask\n\napp = Flask(__name__)\n";

    @Test
    void aFlaskAppInAPackageDebugsUnderFlaskFromTheFolderAboveIt(@TempDir Path dir) throws Exception {
        Path pkg = Files.createDirectories(dir.resolve("shop"));
        Files.writeString(pkg.resolve("__init__.py"), "");
        Path app = Files.writeString(pkg.resolve("app.py"), FLASK);

        PythonLaunch.Target target = DapManager.webOrScript(app, FLASK, List.of("--port", "5001"), true);

        assertNull(target.program());
        assertEquals("flask", target.module());
        assertEquals(List.of("--app", "shop.app:app", "run", "--reload", "--port", "5001"), target.args());
        assertEquals(dir.toString(), target.cwd());
        assertTrue(DapManager.servesWeb(target, FLASK, null), "the reloader's child is followed");
    }

    @Test
    void anyOtherFileDebugsAsItself(@TempDir Path dir) throws Exception {
        Path tool = Files.writeString(dir.resolve("tool.py"), "print('hi')\n");

        PythonLaunch.Target target = DapManager.webOrScript(tool, "print('hi')\n", List.of("a"), true);

        assertEquals(tool.toString(), target.program());
        assertEquals(List.of("a"), target.args());
        assertFalse(DapManager.servesWeb(target, "print('hi')\n", null));
    }

    @Test
    void anAppThatStartsItsOwnServerOrALaunchJsonServerAlsoFollowsChildren(@TempDir Path dir) throws Exception {
        String own = FLASK + "\nif __name__ == \"__main__\":\n    app.run(debug=True)\n";
        Path app = Files.writeString(dir.resolve("app.py"), own);
        PythonLaunch.Target target = DapManager.webOrScript(app, own, List.of(), true);
        assertEquals(app.toString(), target.program(), "it runs as it is");
        assertTrue(DapManager.servesWeb(target, own, null), "app.run(debug=True) reloads in a child too");

        PythonLaunch.Target plain =
                PythonLaunch.Target.script(dir.resolve("run.py").toString(), List.of(), null);
        assertTrue(DapManager.servesWeb(plain, "", Map.of("module", "uvicorn")), "a launch.json's module");
        assertFalse(DapManager.servesWeb(plain, "", Map.of("module", "pytest")));
    }

    @Test
    void aHostLaunchTurnsTheReloaderOff() {
        PythonLaunch.Target uvicorn = PythonLaunch.Target.module("uvicorn", List.of("main:app", "--reload"), "/srv");
        assertEquals(List.of("main:app"), DapManager.withoutReload(uvicorn).args());
        assertEquals("/srv", DapManager.withoutReload(uvicorn).cwd());
    }
}
