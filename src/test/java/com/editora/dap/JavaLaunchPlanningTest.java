package com.editora.dap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;

import com.editora.run.StackTraceLinks;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pure decisions behind a Java launch added by the launch fix pass (review A1, A2, A5). */
class JavaLaunchPlanningTest {

    @TempDir
    Path dir;

    @Test
    void theDeclaredPackageIsReadPastCommentsAndAnnotations() {
        assertEquals(
                "demo.sub",
                JavaLaunchSupport.declaredPackage(List.of(
                        "/* header", " * package not.this; */", "// package nor.this;", "package demo . sub ;")));
        assertEquals("", JavaLaunchSupport.declaredPackage(List.of("import java.util.*;", "class A {}")));
        assertEquals("", JavaLaunchSupport.declaredPackage(List.of("void main() {}")));
    }

    @Test
    void theSourceRootIsTheFileFolderWithOneLevelPerPackageSegment() {
        Path file = dir.resolve("src/a/b/Main.java");
        assertEquals(dir.resolve("src"), JavaLaunchSupport.sourceRoot(file, "a.b"));
        assertEquals(dir.resolve("src/a/b"), JavaLaunchSupport.sourceRoot(file, ""));
        assertNull(JavaLaunchSupport.sourceRoot(file, "x.b"), "folders that do not spell the package");
        assertNull(JavaLaunchSupport.sourceRoot(dir.resolve("Main.java"), "a.b.c.d.e.f.g.h.i.j.k.l.m.n.o.p"));
    }

    @Test
    void onlyJdtlsFoldersBelowTheRightRootThatHoldTheFileAreWrong() {
        Path root = dir.resolve("src");
        Path file = dir.resolve("src/demo/Main.java");
        List<Path> current = List.of(dir.resolve("src/demo"), dir, dir.resolve("src"), dir.resolve("other"));
        assertEquals(List.of(dir.resolve("src/demo")), JavaLaunchSupport.wrongSourceRoots(current, file, root));
        assertEquals(List.of(), JavaLaunchSupport.wrongSourceRoots(List.of(dir), file, root), "above: kept");
    }

    @Test
    void theClassIsQualifiedByTheDeclaredPackageWhateverJdtlsGuessed() {
        assertEquals("demo.Main", JavaLaunchSupport.qualified("Main", "demo"));
        assertEquals("demo.Main", JavaLaunchSupport.qualified("src.demo.Main", "demo"));
        assertEquals("Main", JavaLaunchSupport.qualified("mod/x.Main", ""));
    }

    @Test
    void jdtlsSourcePathRepliesAreRead() {
        Object reply = JsonParser.parseString(
                "{\"data\":[{\"path\":\"/w/project/src/demo\",\"displayPath\":\"project/src/demo\"}],\"status\":true}");
        assertEquals(List.of(Path.of("/w/project/src/demo")), JavaLaunchSupport.sourcePaths(reply));
        assertEquals(
                List.of(Path.of("/a")), JavaLaunchSupport.sourcePaths(Map.of("data", List.of(Map.of("path", "/a")))));
        assertTrue(JavaLaunchSupport.statusOk(Map.of("status", true, "message", "added")));
        assertFalse(JavaLaunchSupport.statusOk(JsonParser.parseString("{\"status\":false}")));
        assertFalse(JavaLaunchSupport.statusOk(null));
        assertEquals(List.of(), JavaLaunchSupport.sourcePaths("nonsense"));
    }

    @Test
    void aScriptsCompileReportNamesTheUsersFile() {
        Path copy = dir.resolve("editora-dap-1/hello.java");
        Path script = dir.resolve("hello");
        String report = JavaLaunchSupport.compilerReport("", copy + ":3: error: ';' expected\n", copy, script);
        assertEquals(script + ":3: error: ';' expected", report);
    }

    @Test
    void javacErrorLocationsAreConsoleLinks() {
        StackTraceLinks.Link link = StackTraceLinks.parse("/home/u/src/demo/Main.java:12: error: cannot find symbol");
        assertNotNull(link);
        assertEquals("/home/u/src/demo/Main.java", link.file());
        assertEquals(12, link.line());
        StackTraceLinks.Link windows = StackTraceLinks.parse("C:\\p\\Main.java:7: warning: [unchecked]");
        assertEquals(7, windows.line());
        assertEquals(
                "Main.java",
                StackTraceLinks.parse("\tat demo.Main.main(Main.java:4)").file(),
                "frames first");
    }

    @Test
    void classFilesAreJudgedAgainstTheirSource() throws Exception {
        Path source = Files.createDirectories(dir.resolve("src/demo")).resolve("Main.java");
        Files.writeString(source, "package demo;");
        Path classes = Files.createDirectories(dir.resolve("target/classes"));
        List<String> cp = List.of(classes.toString(), dir.resolve("lib.jar").toString());
        assertEquals(JavaLaunchSupport.ClassState.MISSING, JavaLaunchSupport.classState(cp, "demo.Main", source));
        assertEquals(
                JavaLaunchSupport.ClassState.UNKNOWN,
                JavaLaunchSupport.classState(cp, "demo.Main", null),
                "a class from no known source may be in the jar");
        Path cls = Files.createDirectories(classes.resolve("demo")).resolve("Main.class");
        Files.writeString(cls, "x");
        Files.setLastModifiedTime(cls, FileTime.fromMillis(1_000_000));
        Files.setLastModifiedTime(source, FileTime.fromMillis(2_000_000));
        assertEquals(JavaLaunchSupport.ClassState.STALE, JavaLaunchSupport.classState(cp, "demo.Main", source));
        Files.setLastModifiedTime(cls, FileTime.fromMillis(3_000_000));
        assertEquals(JavaLaunchSupport.ClassState.CURRENT, JavaLaunchSupport.classState(cp, "mod/demo.Main", source));
    }

    /** java-debug's JVM-version notice is dropped from the console; program output around it is kept (A17). */
    @Test
    void theJvmVersionNoticeIsNotProgramOutput() {
        String notice = "[Warn] The debugger and the debuggee are running in different versions of JVMs. You could"
                + " see wrong source mapping results.\nDebugger JVM version: 27\nDebuggee JVM version: 25.0.4\n";
        assertEquals("", JavaLaunchSupport.withoutJvmVersionWarning(notice));
        assertEquals("tick 1\n", JavaLaunchSupport.withoutJvmVersionWarning("Debuggee JVM version: 25\ntick 1\n"));
        String plain = "Debugger here\n";
        assertTrue(plain == JavaLaunchSupport.withoutJvmVersionWarning(plain), "untouched text is the same object");
    }
}
