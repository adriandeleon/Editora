package com.editora.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class JavaProjectRootTest {

    @Test
    void findsMavenRootWalkingUp(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("pom.xml"), "<project/>");
        Path src = Files.createDirectories(dir.resolve("src/main/java/app"));
        Path file = Files.writeString(src.resolve("Main.java"), "class Main {}");
        assertEquals(dir, JavaProjectRoot.find(file));
    }

    @Test
    void findsGradleRoot(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("build.gradle"), "");
        Path file = Files.writeString(dir.resolve("App.java"), "class App {}");
        assertEquals(dir, JavaProjectRoot.find(file));
    }

    @Test
    void findsGradleKtsSettingsRoot(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("settings.gradle.kts"), "");
        Path sub = Files.createDirectories(dir.resolve("app/src"));
        Path file = Files.writeString(sub.resolve("A.java"), "class A {}");
        assertEquals(dir, JavaProjectRoot.find(file));
    }

    @Test
    void nullOutsideAnyProject(@TempDir Path dir) throws IOException {
        Path file = Files.writeString(dir.resolve("Loose.java"), "class Loose {}");
        assertNull(JavaProjectRoot.find(file));
    }

    @Test
    void nullForNullFile() {
        assertNull(JavaProjectRoot.find(null));
    }
}
