package com.editora.ui;

import java.nio.file.Path;
import java.time.LocalTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.editora.dap.DapModels;
import org.jspecify.annotations.Nullable;

/**
 * The stops of a debug session, as they were shown: a "time travel lite" that lets the Debug window go back to an
 * earlier stop and read its call stack, the values its frames showed, its watches, inline and pinned values, though
 * the program has run on. Only what was already fetched while the stop was live is kept (nothing is asked of the
 * adapter for it), in memory, never on disk; the oldest stops are forgotten past {@link #capacity}. A session's stops
 * are kept after it ends, until the next one starts. Pure; FX thread only.
 */
final class DebugHistory {

    /** Stops kept by default ({@code Settings.debugHistoryStops}). */
    static final int DEFAULT_CAPACITY = 200;

    /** Rows kept per frame of a stop: a 100k-element list paged open must not fill the memory. */
    static final int MAX_ROWS = 2_000;

    /**
     * One row a frame showed: its path of names from the top-level node (a scope, Watches) down, its value and type,
     * and whether it had children (expanded at that stop or not).
     */
    record Row(List<String> path, String value, String type, boolean expandable) {

        Row {
            path = List.copyOf(path);
        }

        String name() {
            return path.getLast();
        }
    }

    /** The values a frame showed in the editor: its inline values beside line {@code line} of {@code file}. */
    record Inline(Path file, int line, Map<String, String> values) {

        Inline {
            values = Map.copyOf(values);
        }
    }

    /** One stop as it was shown. Its rows grow while it is the live stop, as they load. */
    static final class Stop {

        final int number;
        final String reason;
        final LocalTime time;
        final int threadId;
        final List<DapModels.StackFrameInfo> frames;
        final int shownFrame;
        private @Nullable String exception;
        private final Map<Integer, LinkedHashMap<List<String>, Row>> rows = new HashMap<>();
        private final Map<Integer, Inline> inline = new HashMap<>();
        private final Map<Path, Map<Integer, String>> pins = new LinkedHashMap<>();

        Stop(
                int number,
                String reason,
                LocalTime time,
                int threadId,
                List<DapModels.StackFrameInfo> frames,
                int shownFrame) {
            this.number = number;
            this.reason = reason;
            this.time = time;
            this.threadId = threadId;
            this.frames = List.copyOf(frames);
            this.shownFrame = frames.isEmpty() ? -1 : Math.max(0, Math.min(shownFrame, frames.size() - 1));
        }

        /** The rows frame {@code index} showed, in the order they loaded. */
        List<Row> rows(int index) {
            Map<List<String>, Row> r = rows.get(index);
            return r == null ? List.of() : List.copyOf(r.values());
        }

        /** Whether frame {@code index} showed any value at this stop. */
        boolean hasRows(int index) {
            Map<List<String>, Row> r = rows.get(index);
            return r != null && !r.isEmpty();
        }

        /** The value frame {@code index} showed at {@code path}, or null. */
        @Nullable
        String value(int index, List<String> path) {
            Map<List<String>, Row> r = rows.get(index);
            Row row = r == null ? null : r.get(path);
            return row == null ? null : row.value();
        }

        @Nullable
        Inline inline(int index) {
            return inline.get(index);
        }

        /** The pinned values painted at this stop, by file, then line. */
        Map<Path, Map<Integer, String>> pins() {
            return pins;
        }

        @Nullable
        String exception() {
            return exception;
        }

        /** The frame that was shown first: the one the stop selected. */
        DapModels.@Nullable StackFrameInfo top() {
            return shownFrame < 0 ? null : frames.get(shownFrame);
        }

        /** The key of frame {@code index} ({@link DebugValueChanges#frameKey}), to find it at other stops. */
        String frameKey(int index) {
            DapModels.StackFrameInfo f = frames.get(index);
            return DebugValueChanges.frameKey(
                    threadId, f.name(), f.file() == null ? null : f.file().toString(), frames.size() - index);
        }

        /** The index of the frame with key {@code key} at this stop, or -1. */
        int frameIndex(String key) {
            for (int i = 0; i < frames.size(); i++) {
                if (frameKey(i).equals(key)) {
                    return i;
                }
            }
            return -1;
        }
    }

    /** A value a row showed at one stop, for Show Value History. */
    record Seen(int stop, LocalTime time, String value, boolean changed) {}

    private final ArrayDeque<Stop> stops = new ArrayDeque<>();
    private int capacity = DEFAULT_CAPACITY;
    private int counter;

    /** Stops kept at most; 0 records none. Fewer than kept already forgets the oldest. */
    void setCapacity(int capacity) {
        this.capacity = Math.max(0, capacity);
        while (stops.size() > this.capacity) {
            stops.removeFirst();
        }
    }

    int capacity() {
        return capacity;
    }

    /** A new session: the last one's stops are forgotten and numbering starts again. */
    void reset() {
        stops.clear();
        counter = 0;
    }

    /** The program stopped: a new live stop, or null when nothing is recorded. */
    @Nullable
    Stop newStop(String reason, LocalTime time, int threadId, List<DapModels.StackFrameInfo> frames, int shown) {
        counter++;
        if (capacity == 0) {
            return null;
        }
        Stop stop = new Stop(counter, reason, time, threadId, frames, shown);
        stops.addLast(stop);
        while (stops.size() > capacity) {
            stops.removeFirst();
        }
        return stop;
    }

    /** The newest stop (the live one while the program is paused), or null. */
    @Nullable
    Stop last() {
        return stops.peekLast();
    }

    /** The stops kept, oldest first. */
    List<Stop> stops() {
        return List.copyOf(stops);
    }

    int size() {
        return stops.size();
    }

    boolean isEmpty() {
        return stops.isEmpty();
    }

    /** {@code value} showed at {@code path} in frame {@code frame} of the newest stop. */
    void record(int frame, List<String> path, String value, String type, boolean expandable) {
        Stop stop = last();
        if (stop == null || frame < 0 || frame >= stop.frames.size()) {
            return;
        }
        LinkedHashMap<List<String>, Row> rows = stop.rows.computeIfAbsent(frame, k -> new LinkedHashMap<>());
        if (rows.size() < MAX_ROWS || rows.containsKey(path)) {
            rows.put(List.copyOf(path), new Row(path, value, type, expandable));
        }
    }

    /** Frame {@code frame} of the newest stop painted {@code values} beside line {@code line} of {@code file}. */
    void recordInline(int frame, Path file, int line, Map<String, String> values) {
        Stop stop = last();
        if (stop != null && frame >= 0 && frame < stop.frames.size()) {
            stop.inline.put(frame, new Inline(file, line, values));
        }
    }

    /** The newest stop painted pinned values {@code shown} (by line) in {@code file}; null: none there. */
    void recordPins(Path file, @Nullable Map<Integer, String> shown) {
        Stop stop = last();
        if (stop == null) {
            return;
        }
        if (shown == null || shown.isEmpty()) {
            stop.pins.remove(file);
        } else {
            stop.pins.put(file, Map.copyOf(shown));
        }
    }

    /** The newest stop was on exception {@code summary}. */
    void recordException(String summary) {
        Stop stop = last();
        if (stop != null) {
            stop.exception = summary;
        }
    }

    /** The stop numbered {@code number}, or null when it was never kept or is forgotten. */
    @Nullable
    Stop byNumber(int number) {
        for (Stop s : stops) {
            if (s.number == number) {
                return s;
            }
        }
        return null;
    }

    /** The stop kept before {@code stop}, or null. */
    @Nullable
    Stop before(Stop stop) {
        Stop previous = null;
        for (Stop s : stops) {
            if (s == stop) {
                return previous;
            }
            previous = s;
        }
        return null;
    }

    /** The stop kept after {@code stop}, or null. */
    @Nullable
    Stop after(Stop stop) {
        boolean found = false;
        for (Stop s : stops) {
            if (found) {
                return s;
            }
            found = s == stop;
        }
        return null;
    }

    /**
     * Whether {@code path} showed another value in the same frame at the stop before {@code stop} that showed it —
     * what the Variables tree marks as changed, at a stop gone back to.
     */
    boolean changed(Stop stop, int frame, List<String> path, String value) {
        String key = stop.frameKey(frame);
        for (Stop s = before(stop); s != null; s = before(s)) {
            int i = s.frameIndex(key);
            String then = i < 0 ? null : s.value(i, path);
            if (then != null) {
                return !then.equals(value);
            }
        }
        return false;
    }

    /**
     * Show Value History: every stop kept at which the frame {@code frameKey} showed {@code path}, oldest first, each
     * marked when its value differs from the one before.
     */
    List<Seen> valueHistory(String frameKey, List<String> path) {
        List<Seen> out = new ArrayList<>();
        String before = null;
        for (Stop s : stops) {
            int i = s.frameIndex(frameKey);
            String value = i < 0 ? null : s.value(i, path);
            if (value != null) {
                out.add(new Seen(s.number, s.time, value, before != null && !before.equals(value)));
                before = value;
            }
        }
        return out;
    }
}
