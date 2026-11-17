package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Save as Run Configuration looks for its main class off the FX thread (PERF-01, Pauta 6.0.1); what it finds. */
class RunConfigSuggestedMainOffFxTest {

    @Test
    void theBuildsApplicationMainWinsElseTheFilesOwn(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("src/main/java/demo/Tool.java");
        Files.createDirectories(file.getParent());
        String text = "package demo;\n\npublic class Tool {\n    public static void main(String[] args) {\n    }\n}\n";
        Files.writeString(file, text);
        assertEquals("demo.Tool", RunConfigurationCoordinator.suggestedMainClass(file, text), "no build: the file's");

        Files.writeString(dir.resolve("build.gradle"), "application {\n  mainClass = 'demo.App'\n}\n");
        Path other = dir.resolve("src/main/java/demo/Other.java");
        String otherText = "package demo;\nclass Other {}\n";
        Files.writeString(other, otherText);
        assertEquals("demo.App", RunConfigurationCoordinator.suggestedMainClass(other, otherText), "the build's");
    }

    @Test
    void nothingToSuggest(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("Lib.java");
        Files.writeString(file, "class Lib {}\n");
        assertNull(RunConfigurationCoordinator.suggestedMainClass(file, "class Lib {}\n"));
    }
}
