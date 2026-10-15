package com.editora.command;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every keymap binds Run Request and Run All Requests of a .http file, each on one chord (C9). */
class HttpKeyBindingsTest {

    private static final List<String> KEYMAPS = List.of(
            "cua", "cua.mac", "emacs", "intellij", "intellij.mac", "sublime", "sublime.mac", "vscode", "vscode.mac");

    private static Map<String, String> load(String name) throws Exception {
        try (InputStream in = HttpKeyBindingsTest.class.getResourceAsStream("/com/editora/keymaps/" + name + ".json")) {
            assertNotNull(in, name);
            return new ObjectMapper().readValue(in, new TypeReference<Map<String, String>>() {});
        }
    }

    @Test
    void everyKeymapRunsOneRequestAndTheWholeFile() throws Exception {
        for (String name : KEYMAPS) {
            Map<String, String> map = load(name);
            for (String command : List.of("http.runRequest", "http.runFile")) {
                long chords = map.values().stream().filter(command::equals).count();
                assertEquals(1, chords, name + " binds " + command + " once");
            }
        }
        assertEquals("http.runFile", load("emacs").get("C-c C-r")); // beside restclient's C-c C-c
        assertEquals("http.runFile", load("vscode").get("C-M-S-r"));
        assertEquals("http.runFile", load("vscode.mac").get("M-Cmd-S-r"));
        assertTrue(load("intellij.mac").containsKey("C-M-S-r"), "IntelliJ on a Mac: Cmd+Alt+R is Debug Continue");
    }
}
