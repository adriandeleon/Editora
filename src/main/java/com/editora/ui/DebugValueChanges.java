package com.editora.ui;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * Which Variables-tree values changed since the previous stop, as VS Code, Visual Studio, Eclipse, NetBeans and
 * IntelliJ show them. Pure; FX thread only.
 *
 * <p>Values are kept per frame (its thread, method, file and depth from the bottom of the stack), so a step back
 * out of a call compares the caller's variables with what they were when it was last shown, and a recursive call
 * is not compared with its caller. Within one stop, a row is compared with the <em>previous</em> stops' value
 * only: picking another frame and back, or reloading the values after Set Value, does not clear a highlight.
 */
final class DebugValueChanges {

    /** Frames remembered; the least recently shown is forgotten first. */
    static final int MAX_FRAMES = 32;

    /** Rows remembered per frame: a 100k-element list paged open must not grow this without end. */
    static final int MAX_ROWS = 5_000;

    /** Values seen at earlier stops, by frame. */
    private final Map<String, Map<List<String>, String>> previous = lru();

    /** Values seen at this stop, by frame. */
    private final Map<String, Map<List<String>, String>> current = lru();

    private String frame = "";

    private static Map<String, Map<List<String>, String>> lru() {
        return new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Map<List<String>, String>> eldest) {
                return size() > MAX_FRAMES;
            }
        };
    }

    /** The key of a frame: the same activation of the same method across stops. */
    static String frameKey(int threadId, String name, @Nullable String file, int depthFromBottom) {
        return threadId + "\u0000" + name + "\u0000" + (file == null ? "" : file) + "\u0000" + depthFromBottom;
    }

    /** The program stopped again: what this stop showed becomes what the next one is compared with. */
    void newStop() {
        previous.putAll(current);
        current.clear();
    }

    /** A session started or ended: nothing is compared across sessions. */
    void reset() {
        previous.clear();
        current.clear();
        frame = "";
    }

    /** The frame whose variables the tree shows from now on. */
    void setFrame(String key) {
        frame = key;
    }

    /** {@code value} is shown at {@code path} (row names from the top-level node down) in the current frame. */
    void record(List<String> path, String value) {
        Map<List<String>, String> rows = current.computeIfAbsent(frame, k -> new HashMap<>());
        if (rows.size() < MAX_ROWS || rows.containsKey(path)) {
            rows.put(path, value);
        }
    }

    /** Whether the row at {@code path} showed another value at an earlier stop in this frame. A new row is not. */
    boolean changed(List<String> path, String value) {
        Map<List<String>, String> rows = previous.get(frame);
        String before = rows == null ? null : rows.get(path);
        return before != null && !before.equals(value);
    }
}
