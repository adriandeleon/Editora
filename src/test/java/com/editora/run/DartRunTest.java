package com.editora.run;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DartRunTest {

    private static final Path APP = Path.of("/work/app").toAbsolutePath();

    @Test
    void theRunLineIsATopLevelMain() {
        assertEquals(2, DartRun.runLine("import 'dart:io';\n\nvoid main(List<String> args) {\n}\n"));
        assertEquals(0, DartRun.runLine("main() => print('hi');\n"));
        assertEquals(1, DartRun.runLine("// app\nFuture<void> main() async {}\n"));
        assertEquals(-1, DartRun.runLine("class A {\n  void main() {}\n}\n"), "a method is not the program's main");
        assertEquals(-1, DartRun.runLine("void mainly() {}\n"));
        assertEquals(-1, DartRun.runLine("// void main() {}\n"));
        assertEquals(-1, DartRun.runLine(null));
    }

    @Test
    void thePackageRootIsTheNearestFolderWithAPubspec() {
        Set<Path> there = Set.of(APP.resolve("pubspec.yaml"));
        assertEquals(APP, DartRun.packageRoot(APP.resolve("lib/src/main.dart"), there::contains));
        assertEquals(APP, DartRun.packageRoot(APP.resolve("bin/main.dart"), there::contains));
        assertNull(DartRun.packageRoot(Path.of("/elsewhere/a.dart").toAbsolutePath(), there::contains));
    }

    @Test
    void aFlutterAppDependsOnFluttersSdk() {
        String app = """
                name: app
                environment:
                  sdk: ^3.0.0

                dependencies:
                  flutter:
                    sdk: flutter
                  http: ^1.0.0

                dev_dependencies:
                  flutter_test:
                    sdk: flutter

                flutter:
                  uses-material-design: true
                """;
        assertTrue(DartRun.isFlutter(app));
        assertTrue(DartRun.isFlutter("dependencies:\n  flutter: {sdk: flutter}\n"));
        assertTrue(DartRun.isFlutter("dependencies:\n  # the SDK\n  flutter:  # it\n\n    sdk: flutter\n"));
        assertFalse(DartRun.isFlutter("name: cli\ndependencies:\n  args: ^2.0.0\n"));
        assertFalse(
                DartRun.isFlutter("dependencies:\n  args: ^2.0.0\ndev_dependencies:\n  flutter:\n    sdk: flutter\n"),
                "Flutter among the dev dependencies alone");
        assertFalse(DartRun.isFlutter("flutter:\n  uses-material-design: true\n"), "the flutter section");
        assertFalse(DartRun.isFlutter(null));
    }

    @Test
    void theDeviceDefaultsToThisComputersDesktop() {
        assertEquals("linux", DartRun.desktopDevice("Linux"));
        assertEquals("macos", DartRun.desktopDevice("Mac OS X"));
        assertEquals("windows", DartRun.desktopDevice("Windows 11"));
        assertEquals("chrome", DartRun.device(" chrome "));
        assertEquals(DartRun.desktopDevice(System.getProperty("os.name")), DartRun.device(""));
    }

    @Test
    void aPlainProgramRunsUnderDartRunInItsPackage() {
        Path file = APP.resolve("bin/main.dart");
        DartRun.Plan plan = DartRun.plan(file, APP, "name: app\n", List.of("a b", "c"), "dart", "flutter", "linux");
        assertFalse(plan.flutter());
        assertEquals(APP, plan.cwd());
        assertEquals(List.of("dart", "run", file.toString(), "a b", "c"), plan.argv());
    }

    @Test
    void aLoneFileRunsInItsFolder() {
        Path file = Path.of("/tmp/x/hello.dart").toAbsolutePath();
        DartRun.Plan plan = DartRun.plan(file, null, null, List.of(), "/sdk/bin/dart", "flutter", "linux");
        assertEquals(file.getParent(), plan.cwd());
        assertEquals(List.of("/sdk/bin/dart", "run", file.toString()), plan.argv());
    }

    @Test
    void aFlutterAppsFileRunsUnderFlutterRunOnTheDevice() {
        Path file = APP.resolve("lib/main.dart");
        DartRun.Plan plan = DartRun.plan(
                file,
                APP,
                "dependencies:\n  flutter:\n    sdk: flutter\n",
                List.of("x", "y z"),
                "dart",
                "flutter",
                "linux");
        assertTrue(plan.flutter());
        assertEquals(APP, plan.cwd());
        assertEquals(
                List.of(
                        "flutter",
                        "run",
                        "-d",
                        "linux",
                        "-t",
                        "lib/main.dart",
                        "--dart-entrypoint-args=x",
                        "--dart-entrypoint-args=y z"),
                plan.argv());
    }

    @Test
    void aDartFileIsACompiledRunType() {
        assertTrue(ScriptRunCommand.isCompiled(ScriptRunCommand.DART));
        assertTrue(CompiledRunLine.supports("dart"));
        assertEquals(1, CompiledRunLine.runLine("dart", "main.dart", "import 'x';\nvoid main() {}\n"));
        assertEquals(ScriptRunCommand.DART, RunConfigDefaults.typeFor("main.dart", "dart"));
        assertEquals(
                List.of("dart", "run", "bin/main.dart", "-v"),
                ScriptRunCommand.build("dart", "bin/main.dart", List.of("-v")));
        assertEquals(List.of(), ScriptRunCommand.build("dart", "", List.of()));
    }
}
