package com.editora.command;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Every keymap binds Run Request, Run All Requests and Cancel of a .http file, each on one chord (C9, U10). */
class HttpKeyBindingsTest {

    private static final List<String> KEYMAPS = List.of(
            "cua", "cua.mac", "emacs", "intellij", "intellij.mac", "sublime", "sublime.mac", "vscode", "vscode.mac");

    private static Map<String, String> load(String name) throws Exception {
        try (InputStream in = HttpKeyBindingsTest.class.getResourceAsStream("/com/editora/keymaps/" + name + ".json")) {
            assertNotNull(in, name);
            return new ObjectMapper().readValue(in, new TypeReference<Map<String, String>>() {});
        }
    }

    /**
     * Chords the desktops keep for themselves (GNOME, KDE Plasma, macOS) in the forms the keymaps would use:
     * Run All was Ctrl+Alt+Shift+R, GNOME's "record a screencast", so it never reached Editora there.
     */
    private static final List<String> RESERVED = List.of(
            "C-M-S-r", // GNOME: record a screencast
            "C-M-t", // GNOME/Ubuntu, KDE: terminal
            "C-M-l", // KDE: lock screen
            "C-M-d", // older GNOME: show desktop
            "C-M-delete", // log out
            "C-M-escape", // KDE: kill a window
            "C-M-a", // KDE: activate the window demanding attention
            "M-Cmd-escape", // macOS: Force Quit
            "M-Cmd-d", // macOS: hide the Dock
            "C-Cmd-q", // macOS: lock screen
            "C-Cmd-space", // macOS: emoji picker
            "C-Cmd-f"); // macOS: full screen

    @Test
    void everyKeymapRunsOneRequestTheWholeFileAndCancelsEachOnOneChord() throws Exception {
        for (String name : KEYMAPS) {
            Map<String, String> map = load(name);
            for (String command : List.of("http.runRequest", "http.runFile", "http.cancelRequest")) {
                long chords = map.values().stream().filter(command::equals).count();
                assertEquals(1, chords, name + " binds " + command + " once");
            }
        }
        assertEquals("http.runFile", load("emacs").get("C-c C-r")); // beside restclient's C-c C-c
        assertEquals("http.cancelRequest", load("emacs").get("C-c C-k"));
        for (String name : List.of("cua", "sublime", "vscode", "intellij", "intellij.mac")) {
            assertEquals("http.runFile", load(name).get("C-M-S-enter"), name);
            assertEquals("http.cancelRequest", load(name).get("C-M-k"), name);
        }
        for (String name : List.of("cua.mac", "sublime.mac", "vscode.mac")) {
            assertEquals("http.runFile", load(name).get("M-Cmd-S-enter"), name);
            assertEquals("http.cancelRequest", load(name).get("M-Cmd-k"), name);
        }
    }

    @Test
    void noHttpChordIsOneTheDesktopKeepsForItself() throws Exception {
        for (String name : KEYMAPS) {
            for (Map.Entry<String, String> e : load(name).entrySet()) {
                if (e.getValue().startsWith("http.")) {
                    assertFalse(RESERVED.contains(e.getKey()), name + ": " + e.getKey() + " is a desktop shortcut");
                }
            }
        }
    }
}
