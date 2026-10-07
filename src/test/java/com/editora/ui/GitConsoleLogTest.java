package com.editora.ui;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GitConsoleLogTest {

    @Test
    void aDrainShowsOnlyTheProgressThatWouldSurviveIt() {
        assertEquals(
                List.of(
                        Map.entry("Cloning into 'x'...", false),
                        Map.entry("Counting 100%, done.", false),
                        Map.entry("Receiving  2%", true)),
                GitConsoleLog.visible(List.of(
                        Map.entry("Cloning into 'x'...", false),
                        Map.entry("Counting  1%", true),
                        Map.entry("Counting 50%", true),
                        Map.entry("Counting 100%, done.", false),
                        Map.entry("Receiving  1%", true),
                        Map.entry("Receiving  2%", true))));
    }
}
