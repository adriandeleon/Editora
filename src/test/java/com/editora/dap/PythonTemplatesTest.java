package com.editora.dap;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import com.editora.dap.PythonTemplates.Found;
import com.editora.dap.PythonTemplates.Mode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Django and Jinja template breakpoints: what a project uses, and debugpy's switches ({@link PythonTemplates}). */
class PythonTemplatesTest {

    /** What the project at {@code start} uses, its files keyed by path (written with / on every platform). */
    private static Found detect(Map<String, String> files, String start) {
        Map<Path, String> byPath = new HashMap<>();
        files.forEach((name, text) -> byPath.put(Path.of(name), text));
        return PythonTemplates.detect(Path.of(start), byPath::get, byPath::containsKey);
    }

    @Test
    void aProjectsFilesSayWhichEngineItUses() {
        Map<String, String> files = new HashMap<>();
        files.put("/work/shop/manage.py", "#!/usr/bin/env python\n");
        assertEquals(new Found(true, false), detect(files, "/work/shop"), "a manage.py: Django");
        assertEquals(new Found(true, false), detect(files, "/work/shop/orders/views"), "from a folder below it");

        files.clear();
        files.put("/work/api/requirements.txt", "Flask==3.0\nrequests\n");
        assertEquals(new Found(false, true), detect(files, "/work/api"), "Flask renders with Jinja");
        files.put("/work/api/pyproject.toml", "dependencies = [\"Django>=5\", \"jinja2\"]\n");
        assertEquals(new Found(true, true), detect(files, "/work/api"));

        files.clear();
        files.put("/work/tool/requirements.txt", "django-environ\npytest-flask-sqlalchemy-x\n");
        assertEquals(Found.NONE, detect(files, "/work/tool"), "a name that only starts with one is not it");
        assertEquals(Found.NONE, detect(Map.of(), "/work/tool"));
    }

    @Test
    void theModeChoosesTheSwitches() {
        Map<String, Object> launch = Map.of("type", "python", "request", "launch", "cwd", "/work/shop");
        Map<String, Object> auto = PythonTemplates.decorate(launch, Mode.AUTO, dir -> {
            assertEquals(Path.of("/work/shop"), dir, "the launch's folder is looked in");
            return new Found(true, false);
        });
        assertEquals(true, auto.get("django"));
        assertFalse(auto.containsKey("jinja"));

        Map<String, Object> on = PythonTemplates.decorate(launch, Mode.ON, dir -> Found.NONE);
        assertEquals(true, on.get("django"));
        assertEquals(true, on.get("jinja"));
        assertSame(launch, PythonTemplates.decorate(launch, Mode.OFF, dir -> new Found(true, true)));
        assertSame(launch, PythonTemplates.decorate(launch, Mode.AUTO, dir -> Found.NONE), "nothing used: as it is");
    }

    @Test
    void aLaunchJsonsOwnIsKeptAndOtherAdaptersAreLeftAlone() {
        Map<String, Object> own = Map.of("type", "debugpy", "request", "attach", "django", false);
        assertEquals(
                false, PythonTemplates.decorate(own, Mode.ON, dir -> Found.NONE).get("django"));
        Map<String, Object> program = Map.of("type", "python", "request", "launch", "program", "/work/api/app.py");
        assertEquals(
                Path.of("/work/api/app.py").toAbsolutePath().getParent(),
                PythonTemplates.folder(program, DapPaths.LOCAL),
                "else the program's folder");
        Map<String, Object> go = Map.of("type", "go", "request", "launch");
        assertSame(go, PythonTemplates.decorate(go, Mode.ON, dir -> Found.NONE));
    }

    /** A remote host's request names the host's folder: it is mapped as its adapter's paths say, never read here. */
    @Test
    void aRemoteSessionsFolderIsTheHostsNotAFileOfTheSameNameHere(@org.junit.jupiter.api.io.TempDir Path dir)
            throws Exception {
        Path zip = dir.resolve("host.zip");
        try (java.nio.file.FileSystem host = java.nio.file.FileSystems.newFileSystem(zip, Map.of("create", "true"))) {
            Path app = java.nio.file.Files.createDirectories(host.getPath("/srv/app"));
            java.nio.file.Files.writeString(app.resolve("manage.py"), "");
            DapPaths remote = new DapPaths() {
                @Override
                public boolean owns(Path file) {
                    return file.getFileSystem() == host;
                }

                @Override
                public String toAdapter(Path file) {
                    return file.toString();
                }

                @Override
                public Path fromAdapter(String path) {
                    return host.getPath(path);
                }
            };
            Map<String, Object> launch = Map.of("type", "python", "request", "launch", "cwd", "/srv/app");
            java.util.List<Path> asked = new java.util.ArrayList<>();
            PythonTemplates.decorate(launch, Mode.AUTO, remote, d -> {
                asked.add(d);
                return Found.NONE;
            });
            assertEquals(java.util.List.of(app), asked, "the host's folder, through its connection");
            assertEquals(Found.NONE, DapManager.templatesIn(app), "not read over the connection at launch");
            assertSame(
                    launch,
                    PythonTemplates.decorate(launch, Mode.AUTO, remote, DapManager::templatesIn),
                    "a remote launch is sent as it is");
        }
        Path local = java.nio.file.Files.createDirectories(dir.resolve("shop"));
        java.nio.file.Files.writeString(local.resolve("manage.py"), "");
        assertEquals(new Found(true, false), DapManager.templatesIn(local), "a folder on this machine is read");
    }

    @Test
    void theSettingNamesAMode() {
        assertEquals(Mode.AUTO, Mode.of(null));
        assertEquals(Mode.AUTO, Mode.of("whatever"));
        assertEquals(Mode.ON, Mode.of(" On "));
        assertEquals(Mode.OFF, Mode.of("off"));
        assertEquals("auto", Mode.AUTO.setting());
        assertTrue(PythonTemplates.REQUIREMENTS.contains("pyproject.toml"));
    }
}
