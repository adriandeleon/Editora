package com.editora.completion;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Without a Bash server, a script's own names complete after the snippets (Bash review C9). */
class ShellBufferNamesTest {

    private static final String SCRIPT = """
            #!/usr/bin/env bash
            GREETING="hello"
            log_info() { echo "$*"; }
            function greet_all {
                local name
                for item in "$@"; do
                    read -r answer
                    log_info "$GREETING $name $item"
                done
            }
            export PATH_EXTRA=/opt/bin
            """;

    private static List<String> names(String text, String typed) {
        int at = text.lastIndexOf(typed);
        return ShellBufferNames.complete(text, at, typed, 12).stream()
                .map(Completion::label)
                .toList();
    }

    @Test
    void functionsThenVariablesWhereACommandGoes() {
        String text = SCRIPT + "lo";
        assertEquals(List.of("log_info"), names(text, "lo"));
        assertEquals(
                List.of("greet_all", "GREETING"), names(SCRIPT + "gr", "gr"), "prefix in any case, functions first");
        assertTrue(names(SCRIPT + "PA", "PA").contains("PATH_EXTRA"));
        assertTrue(names(SCRIPT + "it", "it").contains("item"), "a for loop's variable");
        assertTrue(names(SCRIPT + "na", "na").contains("name"), "a local without a value");
    }

    @Test
    void onlyVariablesAfterADollar() {
        assertEquals(List.of("GREETING"), names(SCRIPT + "echo $GR", "GR"));
        assertEquals(List.of("GREETING"), names(SCRIPT + "echo ${GR", "GR"));
        assertEquals(List.of(), names(SCRIPT + "echo $lo", "lo"), "a function is no variable");
    }

    @Test
    void theWordBeingTypedIsNotOfferedForItself() {
        assertEquals(List.of(), names("#!/bin/bash\nzzz", "zzz"));
        assertEquals(List.of(), ShellBufferNames.complete("x", 0, "", 12));
        assertEquals(List.of(), ShellBufferNames.complete("x".repeat(ShellBufferNames.MAX_SCAN_CHARS + 1), 0, "x", 12));
    }
}
