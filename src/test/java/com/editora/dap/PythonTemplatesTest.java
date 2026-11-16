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

    private static Found detect(Map<String, String> files, String start) {
        return PythonTemplates.detect(
                Path.of(start), f -> files.get(f.toString()), f -> files.containsKey(f.toString()));
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
        assertEquals(Path.of("/work/api"), PythonTemplates.folder(program), "else the program's folder");
        Map<String, Object> go = Map.of("type", "go", "request", "launch");
        assertSame(go, PythonTemplates.decorate(go, Mode.ON, dir -> Found.NONE));
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
