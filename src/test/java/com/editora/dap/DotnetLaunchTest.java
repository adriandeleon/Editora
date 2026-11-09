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

/** How a C# file is built for netcoredbg, and which assembly is launched. */
class DotnetLaunchTest {

    @Test
    void aFileBelongsToTheNearestProjectAbove(@TempDir Path dir) throws Exception {
        Path app = Files.createDirectories(dir.resolve("app"));
        Files.writeString(app.resolve("app.csproj"), "<Project/>");
        Path deep = Files.createDirectories(app.resolve("Models/Shelf"));
        Path file = Files.writeString(deep.resolve("Item.cs"), "class Item {}");
        assertEquals(app.resolve("app.csproj"), DotnetLaunch.projectFile(file));
        Files.writeString(deep.resolve("Inner.csproj"), "<Project/>");
        assertEquals(deep.resolve("Inner.csproj"), DotnetLaunch.projectFile(file), "the nearest");
        Path lone = Files.writeString(
                Files.createDirectories(dir.resolve("scratch")).resolve("tally.cs"), "");
        assertNull(DotnetLaunch.projectFile(lone), "outside every project");
    }

    /** An absolute folder on any OS. */
    private static final Path W = Path.of("w").toAbsolutePath();

    @Test
    void ofSeveralProjectsTheOneNamedAfterTheFolder() {
        Path dir = W.resolve("Shelf");
        assertEquals(
                dir.resolve("Shelf.csproj"),
                DotnetLaunch.pick(List.of(dir.resolve("A.csproj"), dir.resolve("Shelf.csproj")), dir));
        assertEquals(
                dir.resolve("A.csproj"),
                DotnetLaunch.pick(List.of(dir.resolve("A.csproj"), dir.resolve("B.csproj")), dir));
        assertNull(DotnetLaunch.pick(List.of(), dir));
    }

    @Test
    void aProjectIsBuiltAndRunInItsFolderALoneFileAsAFileBasedApp() {
        Path csproj = W.resolve("Shelf/Shelf.csproj");
        DotnetLaunch.Plan project = DotnetLaunch.plan(W.resolve("Shelf/Program.cs"), csproj);
        assertTrue(project.project());
        assertEquals(W.resolve("Shelf"), project.root());
        assertEquals(List.of("build", csproj.toString()), project.buildArgs());
        assertEquals(List.of("build", csproj.toString(), "-getProperty:TargetPath"), project.pathArgs());
        assertEquals("Shelf", project.sessionName());

        Path lone = W.resolve("scratch/tally.cs");
        DotnetLaunch.Plan file = DotnetLaunch.plan(lone, null);
        assertFalse(file.project(), "the SDK's own targets: no trust asked");
        assertEquals(lone, file.target());
        assertEquals(W.resolve("scratch"), file.root());
        assertEquals("tally.cs", file.sessionName());
    }

    @Test
    void theAssemblyIsTheLastAbsoluteDllPrinted() {
        Path dll = W.resolve("Shelf/bin/Debug/net10.0/Shelf.dll");
        assertEquals(dll, DotnetLaunch.targetPath("  Restored\n" + dll + "\n"));
        assertEquals(
                W.resolve("a.exe"), DotnetLaunch.targetPath(W.resolve("a.exe").toString()));
        assertNull(DotnetLaunch.targetPath("bin/Debug/x.dll\n"), "not absolute");
        assertNull(DotnetLaunch.targetPath("Build FAILED.\n"));
        assertNull(DotnetLaunch.targetPath(""));
    }

    @Test
    void netcoredbgIsToldTheAssemblyAndItsFolder() {
        Path dll = W.resolve("Shelf/bin/Shelf.dll");
        Map<String, Object> launch = DotnetLaunch.launch(dll, List.of("--count", "3"), W.resolve("Shelf"));
        assertEquals("coreclr", launch.get("type"));
        assertEquals("launch", launch.get("request"));
        assertEquals(dll.toString(), launch.get("program"));
        assertEquals(List.of("--count", "3"), launch.get("args"));
        assertEquals(W.resolve("Shelf").toString(), launch.get("cwd"));
        assertEquals(false, launch.get("stopAtEntry"));
        assertEquals(true, launch.get("justMyCode"));
        assertEquals(Map.of("type", "coreclr", "request", "attach", "processId", 42L), DotnetLaunch.attach(42));
        assertEquals("1", DotnetLaunch.BUILD_ENV.get("DOTNET_NOLOGO"));
    }
}
