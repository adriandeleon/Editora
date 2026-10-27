package com.editora.terminal;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Which tool reaches PRIMARY, for each shape of session and set of installed programs. */
class PrimarySelectionTest {

    /** Where the fake finder says a program is, spelled as this platform spells a path. */
    private static String bin(String name) {
        return Path.of("/usr/bin", name).toString();
    }

    private static Function<String, Path> installed(String... names) {
        Set<String> present = Set.of(names);
        return name -> present.contains(name) ? Path.of("/usr/bin", name) : null;
    }

    @Test
    void waylandUsesWlClipboard() {
        var tools = PrimarySelection.choose(
                Map.of("WAYLAND_DISPLAY", "wayland-0", "DISPLAY", ":0"), installed("wl-paste", "wl-copy", "xclip"));
        assertEquals(List.of(bin("wl-paste"), "--primary", "--no-newline"), tools.read());
        assertEquals(List.of(bin("wl-copy"), "--primary"), tools.write());
    }

    @Test
    void waylandWithoutWlClipboardFallsBackToXWaylandsTools() {
        var tools =
                PrimarySelection.choose(Map.of("WAYLAND_DISPLAY", "wayland-0", "DISPLAY", ":0"), installed("xclip"));
        assertEquals(List.of(bin("xclip"), "-o", "-selection", "primary"), tools.read());
    }

    @Test
    void halfOfWlClipboardIsNotEnough() {
        // Reading without writing would paste a PRIMARY that selecting here never sets.
        assertNull(PrimarySelection.choose(Map.of("WAYLAND_DISPLAY", "wayland-0"), installed("wl-paste")));
    }

    @Test
    void x11PrefersXclipThenXsel() {
        assertEquals(
                bin("xclip"),
                PrimarySelection.choose(Map.of("DISPLAY", ":0"), installed("xclip", "xsel"))
                        .read()
                        .get(0));
        var xsel = PrimarySelection.choose(Map.of("DISPLAY", ":0"), installed("xsel"));
        assertEquals(List.of(bin("xsel"), "-p", "-o"), xsel.read());
        assertEquals(List.of(bin("xsel"), "-p", "-i"), xsel.write());
    }

    @Test
    void noDisplayOrNoToolMeansNoPrimary() {
        assertNull(PrimarySelection.choose(Map.of(), installed("wl-paste", "wl-copy", "xclip")));
        assertNull(PrimarySelection.choose(Map.of("DISPLAY", ":0"), installed()));
        assertNull(PrimarySelection.choose(Map.of("DISPLAY", " "), installed("xclip")));
    }
}
