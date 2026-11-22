package com.editora.ui;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ACC-02 (Pauta 6.2.0): a pane, label or canvas the app makes clickable is a button to a screen reader and to the
 * keyboard, or something else does what its click does. Read from the source: every click handler in
 * {@code src/main/java} ({@code setOnMouseClicked}, {@code setOnMousePressed}, {@code setOnMouseReleased}, and
 * {@code addEventHandler}/{@code addEventFilter} for {@code MOUSE_CLICKED}, {@code MOUSE_PRESSED} or
 * {@code MOUSE_RELEASED}) is listed in {@code click-handlers.tsv} by its file, receiver and method, with the
 * reason it needs no path of its own: it is on a {@code Control}, a command or a key does the same, or it is no
 * action (a drag, raising a card). A node that acts as a button is a {@code Pressable*} and is not listed.
 *
 * <p>What it cannot see: a handler whose call is split over two lines before the event type, a handler added
 * through a helper of another name, and whether a listed reason is still true.
 */
@Tag("guard")
class ClickHandlersGuardTest {

    private static final Pattern HANDLER = Pattern.compile(
            "(?:([\\w.()\\[\\]]+)\\.)?(setOnMouseClicked|setOnMousePressed|setOnMouseReleased|addEventHandler"
                    + "|addEventFilter)\\(\\s*(?:javafx\\.scene\\.input\\.)?"
                    + "(MouseEvent\\.(?:MOUSE_CLICKED|MOUSE_PRESSED|MOUSE_RELEASED))?");

    /** Every handler site in the source, as {@code path<TAB>receiver<TAB>method}, with its first line. */
    static Map<String, String> sites(Path root) throws IOException {
        Map<String, String> found = new TreeMap<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                List<String> lines = Files.readAllLines(file);
                String path = root.relativize(file).toString().replace('\\', '/');
                for (int i = 0; i < lines.size(); i++) {
                    Matcher m = HANDLER.matcher(lines.get(i));
                    while (m.find()) {
                        if (m.group(2).startsWith("addEvent") && m.group(3) == null) {
                            continue; // a key, scroll or other event
                        }
                        String receiver = m.group(1) == null ? "this" : m.group(1);
                        found.putIfAbsent(path + "\t" + receiver + "\t" + m.group(2), path + ":" + (i + 1));
                    }
                }
            }
        }
        return found;
    }

    /** The allow-list: key to reason. */
    private static Map<String, String> allowed() throws IOException {
        Map<String, String> allowed = new TreeMap<>();
        try (InputStream in = ClickHandlersGuardTest.class.getResourceAsStream("click-handlers.tsv")) {
            assertNotNull(in, "click-handlers.tsv");
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] parts = line.split("\t", 4);
                assertEquals(4, parts.length, "path, receiver, method and reason: " + line);
                assertTrue(!parts[3].isBlank(), "a reason: " + line);
                allowed.put(parts[0] + "\t" + parts[1] + "\t" + parts[2], parts[3]);
            }
        }
        return allowed;
    }

    @Test
    void everyClickHandlerIsAPressableOrListedWithItsReason() throws Exception {
        Map<String, String> sites = sites(Path.of("src/main/java"));
        Map<String, String> allowed = allowed();
        List<String> unlisted = new ArrayList<>();
        sites.forEach((key, where) -> {
            if (!allowed.containsKey(key)) {
                unlisted.add(where + " (" + key.replace('\t', ' ') + ")");
            }
        });
        assertEquals(
                List.of(),
                unlisted,
                "a click handler on a node: make it a PressableLabel/PressableBox/PressablePane, or list it in"
                        + " click-handlers.tsv with the command or key that does the same");
        Set<String> stale = new TreeSet<>(allowed.keySet());
        stale.removeAll(sites.keySet());
        assertEquals(Set.of(), stale, "listed in click-handlers.tsv but no longer in the source");
    }
}
