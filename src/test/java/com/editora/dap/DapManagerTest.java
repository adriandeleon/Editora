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
}
