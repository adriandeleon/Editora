package com.editora.dap;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

class ScriptLaunchLuaTest {

    private static final Path DIR = Path.of("/p").toAbsolutePath();

    @Test
    void aLuaScriptIsPlannedOnLocalLuaDebuggerOverAPipe() {
        ScriptLaunch.Plan plan = ScriptLaunch.plan("lua", DIR.resolve("main.lua"), List.of("a b"), DIR);
        assertSame(DebugLanguages.LUA, plan.language());
        assertEquals(List.of(), plan.tail());
        Map<String, Object> launch = plan.launch();
        assertEquals("lua-local", launch.get("type"));
        assertEquals("launch", launch.get("request"));
        assertEquals(
                Map.of("lua", "lua", "file", DIR.resolve("main.lua").toString(), "communication", "pipe"),
                launch.get("program"));
        assertEquals(List.of("a b"), launch.get("args"));
        assertEquals(DIR.toString(), launch.get("cwd"));
        assertEquals(false, launch.get("stopOnEntry"));
        assertFalse(ScriptLaunch.luaLaunch(DIR.resolve("m.lua"), List.of(), DIR).containsKey("args"), "none: none");
    }

    @Test
    void theRuntimeNamesTheExtensionAboveTheAdapterAndTheInstalledLua() {
        Path adapter = DIR.resolve("plugins/dap/lua/extension/extension/debugAdapter.js");
        Map<String, Object> launch = ScriptLaunch.withLuaRuntime(
                ScriptLaunch.luaLaunch(DIR.resolve("main.lua"), List.of(), DIR),
                adapter,
                name -> name.equals("lua5.4") ? "/usr/bin/lua5.4" : name);
        assertEquals(DIR.resolve("plugins/dap/lua/extension").toString(), launch.get("extensionPath"));
        assertEquals(DIR.toString(), launch.get("workspacePath"));
        assertEquals("/usr/bin/lua5.4", ((Map<?, ?>) launch.get("program")).get("lua"), "lua is not there: lua5.4");
    }

    @Test
    void noLuaFoundKeepsTheBareNameAndALaunchJsonsOwnValuesStay() {
        Map<String, Object> own = new java.util.LinkedHashMap<>();
        own.put("type", "lua-local");
        own.put("program", Map.of("lua", "/opt/luajit/bin/luajit", "file", "x.lua"));
        own.put("extensionPath", "/mine");
        own.put("workspacePath", "/ws");
        Map<String, Object> launch =
                ScriptLaunch.withLuaRuntime(own, DIR.resolve("ext/extension/debugAdapter.js"), name -> name);
        assertEquals("/mine", launch.get("extensionPath"));
        assertEquals("/ws", launch.get("workspacePath"));
        assertEquals("/ws", launch.get("cwd"), "no cwd: the workspace's");
        assertEquals("/opt/luajit/bin/luajit", ((Map<?, ?>) launch.get("program")).get("lua"));

        Map<String, Object> bare = ScriptLaunch.withLuaRuntime(
                ScriptLaunch.luaLaunch(DIR.resolve("main.lua"), List.of(), DIR),
                DIR.resolve("ext/extension/debugAdapter.js"),
                name -> name);
        assertEquals("lua", ((Map<?, ?>) bare.get("program")).get("lua"), "said by the adapter when it fails to start");
    }
}
