package com.editora.dap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DapManagerTest {

    @Test
    void mainClassFromFileUsesPackagePlusBaseName(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("Main.java");
        Files.writeString(f, "// a comment\npackage com.example.app;\n\npublic class Main { void main() {} }\n");
        assertEquals("com.example.app.Main", DapManager.mainClassFromFile(f));
    }

    @Test
    void mainClassFromFileDefaultPackageIsJustTheBaseName(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("Hello.java");
        Files.writeString(f, "public class Hello { public static void main(String[] a) {} }\n");
        assertEquals("Hello", DapManager.mainClassFromFile(f));
    }

    @Test
    void mainClassFromFileHandlesMissingFile(@TempDir Path dir) {
        // Unreadable file → default package, base name only (no crash).
        assertEquals("Gone", DapManager.mainClassFromFile(dir.resolve("Gone.java")));
    }

    @Test
    void mainClassFromFileUsesDeclaredClassNotFileName(@TempDir Path dir) throws IOException {
        // Class name differs from the file name — the declared type wins (the real cause of the
        // "Main class 'Hello' doesn't exist" error when HelloWorld lived in Hello.java).
        Path f = dir.resolve("Hello.java");
        Files.writeString(f, "public class HelloWorld { public static void main(String[] a) {} }\n");
        assertEquals("HelloWorld", DapManager.mainClassFromFile(f));
    }

    @Test
    void mainClassFromFileNullForNoName() {
        assertNull(DapManager.mainClassFromFile(null));
    }

    // --- run-to-cursor temp breakpoint merge ---------------------------------------------------

    @Test
    void withTempLineAddsTempToExistingFileBreakpoints() {
        Path f = Path.of("/x/A.java");
        var existing = java.util.List.of(new DapModels.FileBreakpoints(f,
                java.util.List.of(new DapModels.LineBreakpoint(3, null, null))));
        DapModels.FileBreakpoints merged = DapManager.withTempLine(existing, f, 10);
        assertEquals(f, merged.file());
        assertEquals(2, merged.breakpoints().size());
        assertEquals(10, merged.breakpoints().get(1).line());
    }

    @Test
    void withTempLineDoesNotDuplicateAnExistingLine() {
        Path f = Path.of("/x/A.java");
        var existing = java.util.List.of(new DapModels.FileBreakpoints(f,
                java.util.List.of(new DapModels.LineBreakpoint(10, "i > 3", null))));
        DapModels.FileBreakpoints merged = DapManager.withTempLine(existing, f, 10);
        assertEquals(1, merged.breakpoints().size());
        assertEquals("i > 3", merged.breakpoints().get(0).condition()); // the real one survives
    }

    @Test
    void withTempLineIgnoresOtherFilesAndHandlesEmpty() {
        Path f = Path.of("/x/A.java");
        var other = java.util.List.of(new DapModels.FileBreakpoints(Path.of("/x/B.java"),
                java.util.List.of(new DapModels.LineBreakpoint(1, null, null))));
        DapModels.FileBreakpoints merged = DapManager.withTempLine(other, f, 5);
        assertEquals(1, merged.breakpoints().size());
        assertEquals(5, merged.breakpoints().get(0).line());
        assertEquals(1, DapManager.withTempLine(null, f, 5).breakpoints().size());
    }
}
