package com.editora.dap;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** PowerShell Editor Services as Editora starts it, and the launch requests it is sent. */
class PowerShellLaunchTest {

    private static final Path START = Path.of("/pses/PowerShellEditorServices/Start-EditorServices.ps1");

    @Test
    void psesStartsAsTheLanguageServerOrTheDebugAdapterOnStdio() {
        Path tmp = Path.of("/tmp");
        List<String> lsp = PowerShellLaunch.serviceArgs(START, tmp, false, 7);
        assertEquals(List.of("-NoLogo", "-NoProfile", "-NonInteractive", "-File", START.toString()), lsp.subList(0, 5));
        assertTrue(lsp.containsAll(List.of("-LanguageServiceOnly", "-Stdio")), lsp.toString());
        assertEquals(
                Path.of("/pses").toString(),
                lsp.get(lsp.indexOf("-BundledModulesPath") + 1),
                "PSScriptAnalyzer and PSReadLine sit beside PSES's own folder");
        assertEquals(tmp.resolve("editora-pses-lsp-7.log").toString(), lsp.get(lsp.indexOf("-LogPath") + 1));
        List<String> dap = PowerShellLaunch.serviceArgs(START, tmp, true, 7);
        assertTrue(dap.containsAll(List.of("-DebugServiceOnly", "-Stdio")), dap.toString());
        assertFalse(dap.contains("-LanguageServiceOnly"));
    }

    @Test
    void aScriptIsLaunchedWrappedWithItsArgumentsQuoted() {
        Map<String, Object> launch =
                PowerShellLaunch.launch(Path.of("/w/it's.ps1"), List.of("-Name", "a b"), Path.of("/w"));
        assertEquals("PowerShell", launch.get("type"));
        assertEquals("launch", launch.get("request"));
        assertEquals(Path.of("/w").toString(), launch.get("cwd"));
        assertEquals(false, launch.get("createTemporaryIntegratedConsole"));
        String script = (String) launch.get("script");
        assertTrue(
                script.startsWith("& { & '" + Path.of("/w/it''s.ps1") + "' '-Name' 'a b' } *>&1"),
                script); // its output: merged, then to stderr, which the Debug console shows
        assertTrue(script.endsWith("[Console]::Error.WriteLine($_) }"), script);
        assertTrue(script.contains("'WARNING: ' + $_.Message"), script);
    }

    @Test
    void aLaunchJsonConfigurationKeepsItsFieldsAndRunsWrapped() {
        Map<String, Object> body = new LinkedHashMap<>(Map.of(
                "type",
                "PowerShell",
                "request",
                "launch",
                "script",
                "/w/build.ps1",
                "args",
                List.of("-Release"),
                "cwd",
                "/w"));
        Map<String, Object> sent = PowerShellLaunch.fromLaunchJson(body);
        assertTrue(((String) sent.get("script")).startsWith("& { & '/w/build.ps1' '-Release' }"), sent.toString());
        assertFalse(sent.containsKey("args"), "they are in the command now");
        assertEquals("/w", sent.get("cwd"));

        Map<String, Object> command = PowerShellLaunch.fromLaunchJson(new LinkedHashMap<>(Map.of(
                "type",
                "PowerShell",
                "request",
                "launch",
                "script",
                "Invoke-Pester",
                "args",
                List.of("-Output Detailed"))));
        assertTrue(
                ((String) command.get("script")).startsWith("& { Invoke-Pester -Output Detailed }"),
                command.toString());

        Map<String, Object> attach =
                new LinkedHashMap<>(Map.of("type", "PowerShell", "request", "attach", "processId", 4));
        assertEquals(attach, PowerShellLaunch.fromLaunchJson(attach));
    }

    @Test
    void runFileRunsTheScriptOnPwshElseWindowsPowerShell() {
        assertEquals(
                List.of(
                        "pwsh",
                        "-NoLogo",
                        "-NoProfile",
                        "-File",
                        Path.of("/w/a.ps1").toString(),
                        "x"),
                PowerShellLaunch.runCommand("pwsh", Path.of("/w/a.ps1"), List.of("x")));
        assertEquals("pwsh", PowerShellLaunch.executable(n -> true, true));
        assertEquals("powershell", PowerShellLaunch.executable(n -> n.equals("powershell"), true));
        assertEquals("pwsh", PowerShellLaunch.executable(n -> false, false), "named, so its absence is said");
        assertTrue(PowerShellLaunch.isScript(Path.of("/w/A.PS1")));
        assertFalse(PowerShellLaunch.isScript(Path.of("/w/m.psm1")));
    }

    @Test
    void aPowerShellConfigurationIsRoutedToPses() {
        LaunchRoute.Route route = LaunchRoute.of(
                new LinkedHashMap<>(Map.of(
                        "type",
                        "PowerShell",
                        "request",
                        "launch",
                        "script",
                        "/w/a.ps1",
                        "name",
                        "Run",
                        "postDebugTask",
                        "x")),
                Map.of());
        assertEquals(LaunchRoute.Kind.POWERSHELL, route.kind());
        assertEquals(DebugLanguages.POWERSHELL, route.profile());
        assertFalse(route.request().containsKey("postDebugTask"));
        assertEquals(
                LaunchRoute.Kind.UNSUPPORTED,
                LaunchRoute.of(Map.of("type", "PowerShell", "request", "attach"), Map.of())
                        .kind());
    }
}
