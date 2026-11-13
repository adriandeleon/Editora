package com.editora.run;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** JS review C110: a Node.js or npm configuration's form speaks its own words. */
class RunConfigPromptsTest {

    @Test
    void nodeAndNpmNameTheirRowsAndBuildStepInTheirOwnWords() {
        assertEquals("settings.runConfig.beforeLaunchPromptNode", RunConfigPrompts.beforeLaunch("node"));
        assertEquals("settings.runConfig.beforeLaunchPromptNode", RunConfigPrompts.beforeLaunch("npm"));
        assertEquals("settings.runConfig.beforeLaunchPrompt", RunConfigPrompts.beforeLaunch("java"));
        assertEquals(
                List.of(
                        "settings.runConfig.targetScript",
                        "settings.runConfig.interpreterNode",
                        "settings.runConfig.interpreterOptionsNode"),
                RunConfigPrompts.labels("node"));
        assertEquals(
                "settings.runConfig.targetScript",
                RunConfigPrompts.labels("npm").get(0));
        assertEquals(
                "settings.runConfig.target", RunConfigPrompts.labels("shell").get(0));
        assertEquals(
                "settings.runConfig.interpreter",
                RunConfigPrompts.labels("python").get(1));
    }

    /** A C, C++, Go or Rust configuration's target is a source file, and its blank folder is the file's. */
    @Test
    void aCompiledTypeSpeaksOfASourceFile() {
        for (String type : new String[] {"c", "cpp", "go", "rust"}) {
            assertEquals(
                    "settings.runConfig.targetSource",
                    RunConfigPrompts.labels(type).get(0),
                    type);
            assertEquals("settings.runConfig.targetPromptSource", RunConfigPrompts.target(type), type);
            assertEquals("settings.runConfig.workingDirPromptSource", RunConfigPrompts.workingDir(type), type);
            assertEquals("settings.runConfig.beforeLaunchPromptSource", RunConfigPrompts.beforeLaunch(type), type);
        }
        assertEquals("settings.runConfig.workingDirPrompt", RunConfigPrompts.workingDir("shell"));
    }
}
