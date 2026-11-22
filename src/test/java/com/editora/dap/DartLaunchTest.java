package com.editora.dap;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DartLaunchTest {

    private static final Path APP = Path.of("/work/app").toAbsolutePath();

    @Test
    void aPlainProgramDebugsOnDartsAdapterInItsPackageWithoutSdkFrames() {
        Path file = APP.resolve("bin/main.dart");
        DartLaunch.Plan plan = DartLaunch.plan(file, APP, "name: app\n", List.of("a"), "linux");
        assertSame(DebugLanguages.DART, plan.language());
        assertEquals(APP, plan.cwd());
        Map<String, Object> launch = plan.launch();
        assertEquals("dart", launch.get("type"));
        assertEquals("launch", launch.get("request"));
        assertEquals(file.toString(), launch.get("program"));
        assertEquals(APP.toString(), launch.get("cwd"));
        assertEquals(List.of("a"), launch.get("args"));
        assertEquals(false, launch.get("debugSdkLibraries"));
        assertEquals(false, launch.get("debugExternalPackageLibraries"));
        assertFalse(launch.containsKey("toolArgs"));
    }

    @Test
    void aLoneFileDebugsInItsFolder() {
        Path file = Path.of("/tmp/x/hello.dart").toAbsolutePath();
        DartLaunch.Plan plan = DartLaunch.plan(file, null, null, List.of(), "linux");
        assertSame(DebugLanguages.DART, plan.language());
        assertEquals(file.getParent(), plan.cwd());
    }

    @Test
    void aFlutterAppsFileDebugsOnFluttersAdapterOnTheDevice() {
        Path file = APP.resolve("lib/main.dart");
        DartLaunch.Plan plan =
                DartLaunch.plan(file, APP, "dependencies:\n  flutter:\n    sdk: flutter\n", List.of(), "chrome");
        assertSame(DebugLanguages.FLUTTER, plan.language());
        assertEquals(List.of("-d", "chrome"), plan.launch().get("toolArgs"));
        assertEquals(APP.toString(), plan.launch().get("cwd"));
    }

    @Test
    void theProfilesAreTheSdksOwnAdaptersOverTheirStandardStreams() {
        DebugLanguage dart = DebugLanguages.DART;
        assertEquals(DapServerRegistry.Kind.EXTERNAL, dart.kind());
        assertEquals(
                List.of("dart", "debug_adapter"),
                java.util.Objects.requireNonNull(dart.external()).command());
        assertEquals(ExternalAdapter.Transport.STDIO, dart.external().transport());
        assertSame(dart, DebugLanguages.forLanguage("dart"));
        assertTrue(DebugLanguages.all().contains(dart));
        assertEquals("All", ExceptionFilterCatalog.caughtFilter("dart"));
        assertEquals("Unhandled", ExceptionFilterCatalog.uncaughtFilter("dart"));
        assertEquals(
                List.of("flutter", "debug_adapter"),
                java.util.Objects.requireNonNull(DebugLanguages.FLUTTER.external())
                        .command());
        assertSame(DebugLanguages.FLUTTER, DebugLanguages.byId("flutter"));
        assertFalse(DebugLanguages.all().contains(DebugLanguages.FLUTTER), "not a language of its own");
        assertNull(DebugLanguages.forLanguage("flutter"));
    }
}
