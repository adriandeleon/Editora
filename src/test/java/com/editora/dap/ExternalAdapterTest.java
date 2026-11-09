package com.editora.dap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** How an external debug adapter is started and launched: its command, its port, its announcement. */
class ExternalAdapterTest {

    private static final Pattern DELVE = Pattern.compile("DAP server listening at: \\S*:(\\d+)");

    @Test
    void theCommandTakesTheFoundExecutableAndThePort() {
        ExternalAdapter codelldb = new ExternalAdapter(
                List.of("codelldb", "--port", ExternalAdapter.PORT), ExternalAdapter.Transport.PORT_ARGUMENT, null);
        assertEquals(List.of("codelldb", "--port", "4711"), codelldb.argv("", 4711));
        assertEquals(
                List.of("/opt/codelldb/adapter/codelldb", "--port", "4711"),
                codelldb.argv("/opt/codelldb/adapter/codelldb", 4711));
        ExternalAdapter lldb = new ExternalAdapter(List.of("lldb-dap"), ExternalAdapter.Transport.STDIO, null);
        assertEquals(List.of("lldb-dap"), lldb.argv(null, -1));
        ExternalAdapter net = new ExternalAdapter(
                List.of("netcoredbg", "--server=" + ExternalAdapter.PORT),
                ExternalAdapter.Transport.PORT_ARGUMENT,
                null);
        assertEquals(List.of("netcoredbg", "--server=4711"), net.argv("", 4711), "a port inside an argument");
    }

    @Test
    void theAnnouncementSaysThePort() {
        ExternalAdapter dlv = new ExternalAdapter(
                List.of("dlv", "dap", "--listen=127.0.0.1:0"), ExternalAdapter.Transport.ANNOUNCED_PORT, DELVE);
        assertEquals(38697, dlv.announcedPort("DAP server listening at: 127.0.0.1:38697"));
        assertEquals(
                41000, dlv.announcedPort("2026-11-08T10:00:00Z info DAP server listening at: [::1]:41000"), "IPv6");
        assertEquals(-1, dlv.announcedPort("Type 'dlv help' for list of commands."));
        assertEquals(-1, dlv.announcedPort("DAP server listening at: 127.0.0.1:99999"), "no such port");
        assertEquals(-1, dlv.announcedPort(null));
        ExternalAdapter stdio = new ExternalAdapter(List.of("lldb-dap"), ExternalAdapter.Transport.STDIO, null);
        assertEquals(-1, stdio.announcedPort("DAP server listening at: 127.0.0.1:38697"));
        ExternalAdapter noGroup = new ExternalAdapter(
                List.of("x"), ExternalAdapter.Transport.ANNOUNCED_PORT, Pattern.compile("listening"));
        assertEquals(-1, noGroup.announcedPort("listening"), "a pattern without the port's group finds none");
    }

    @Test
    void aTransportNeedsWhatItReads() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ExternalAdapter(List.of(), ExternalAdapter.Transport.STDIO, null));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ExternalAdapter(List.of("dlv"), ExternalAdapter.Transport.ANNOUNCED_PORT, null));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ExternalAdapter(List.of("lldb-dap"), ExternalAdapter.Transport.STDIO, DELVE));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ExternalAdapter(List.of("codelldb"), ExternalAdapter.Transport.PORT_ARGUMENT, null));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ExternalAdapter(List.of("x", ExternalAdapter.PORT), ExternalAdapter.Transport.STDIO, null));
    }

    @Test
    void theLaunchRequestHasTheCommonFieldsAndTheLanguagesOwn() {
        Map<String, Object> launch = ExternalAdapter.launchRequest(
                "go", "/src/app", List.of("-v"), "/src", Map.of("GOFLAGS", "-mod=mod"), false, Map.of("mode", "debug"));
        assertEquals(
                Map.of(
                        "type", "go",
                        "request", "launch",
                        "program", "/src/app",
                        "args", List.of("-v"),
                        "cwd", "/src",
                        "env", Map.of("GOFLAGS", "-mod=mod"),
                        "stopOnEntry", false,
                        "mode", "debug"),
                launch);
        Map<String, Object> bare =
                ExternalAdapter.launchRequest("lldb", "/bin/app", List.of(), " ", Map.of(), true, Map.of());
        assertFalse(bare.containsKey("cwd"));
        assertFalse(bare.containsKey("env"));
        assertEquals(true, bare.get("stopOnEntry"));
        assertEquals(
                "attach",
                ExternalAdapter.launchRequest("x", "p", List.of(), null, Map.of(), false, Map.of("request", "attach"))
                        .get("request"),
                "the language's own fields win");
    }

    @Test
    void anExternalProfileSaysHowItStartsAndNoOtherDoes() {
        ExternalAdapter dlv =
                new ExternalAdapter(List.of("dlv", "dap"), ExternalAdapter.Transport.ANNOUNCED_PORT, DELVE);
        assertThrows(IllegalArgumentException.class, () -> profile(DapServerRegistry.Kind.EXTERNAL, null));
        assertThrows(IllegalArgumentException.class, () -> profile(DapServerRegistry.Kind.STDIO, dlv));
        DebugLanguage go = profile(DapServerRegistry.Kind.EXTERNAL, dlv);
        assertEquals(DapServerRegistry.Kind.EXTERNAL, go.spec().kind());
        assertNull(go.uncaughtFilter());
    }

    @Test
    void anAdaptersCommandIsFoundAsAPathOrOnThePath(@TempDir Path dir) throws Exception {
        Path exe = Files.writeString(dir.resolve("fake-dap"), "#!/bin/sh\n");
        boolean executable = exe.toFile().setExecutable(true);
        if (executable && Files.isExecutable(exe)) {
            assertEquals(exe.toString(), DapManager.locateExecutable(exe.toString()));
        }
        assertNull(DapManager.locateExecutable(dir.resolve("missing").toString()));
        assertNull(DapManager.locateExecutable("editora-no-such-debug-adapter"));
        assertNull(DapManager.locateExecutable("bad\0name"));
    }

    static DebugLanguage profile(DapServerRegistry.Kind kind, ExternalAdapter external) {
        return new DebugLanguage(
                "go",
                "Go",
                java.util.Set.of("go"),
                kind,
                "go",
                "go",
                "",
                List.of(),
                null,
                DebugLanguage.Availability.LOCATED,
                List.of(),
                null,
                null,
                false,
                "status.debug.unavailable",
                external);
    }

    @Test
    void anAdaptersPathCanLeadWithItsToolchain() {
        assertEquals(
                "/opt/go/bin:/usr/bin:/bin", DapManager.pathWithFirst(List.of("/opt/go/bin"), "/usr/bin:/bin", ":"));
        assertEquals(
                "/usr/bin:/bin", DapManager.pathWithFirst(List.of("/usr/bin"), "/usr/bin:/bin", ":"), "none twice");
        assertEquals("/opt/go/bin", DapManager.pathWithFirst(List.of("/opt/go/bin", " "), null, ":"));
    }

    @Test
    void goInstallPutsProgramsInGobinElseEachGopathElseHomeGoBin() {
        Path home = Path.of("/home/u");
        String sep = java.io.File.pathSeparator;
        assertEquals(
                List.of(Path.of("/g/bin"), Path.of("/a", "bin"), Path.of("/b", "bin"), home.resolve("go/bin")),
                DapManager.goBinDirs(Map.of("GOBIN", "/g/bin", "GOPATH", "/a" + sep + "/b"), home));
        assertEquals(List.of(home.resolve("go/bin")), DapManager.goBinDirs(Map.of(), home));
    }

    @Test
    void aLaunchJsonConfigurationIsLaidOverThePlannedLaunchButForTheConsole() {
        Map<String, Object> planned =
                Map.of("type", "python", "program", "/a.py", "console", "internalConsole", "cwd", "/");
        assertEquals(planned, DapManager.overlay(planned, null));
        Map<String, Object> out = DapManager.overlay(
                planned,
                Map.of("type", "debugpy", "program", "/b.py", "console", "integratedTerminal", "env", Map.of()));
        assertEquals("python", out.get("type"), "the adapter Editora started");
        assertEquals("/b.py", out.get("program"), "the configuration wins");
        assertEquals("internalConsole", out.get("console"), "Editora's console");
        assertEquals(Map.of(), out.get("env"));
        assertEquals("/", out.get("cwd"));
    }
}
