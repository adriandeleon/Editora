package com.editora.search;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Pure parser for ripgrep's {@code --json} output: a stream of one JSON object per line, of type
 * {@code begin}/{@code match}/{@code end}/{@code summary}. Only {@code match} events are kept, mapped into the
 * existing {@link LineMatch}/{@link FileResult} shapes (1-based line + char column). Never throws; a malformed
 * line is skipped.
 *
 * <p>The one subtlety: rg reports submatch offsets as <b>byte</b> positions into the line's UTF-8 bytes, while
 * {@link LineMatch} columns are Java {@code char} (UTF-16) indices — see {@link #charIndexForByteOffset}.
 * Non-UTF-8 paths/lines (which rg emits as base64 {@code bytes} instead of {@code text}) are skipped, mirroring
 * the binary-file skip in {@link SearchService}.
 */
public final class RipgrepOutput {

    private static final ObjectMapper JSON = new ObjectMapper();

    private RipgrepOutput() {}

    /** Parse rg {@code --json} stdout into per-file results, in rg's emission order. */
    public static List<FileResult> parse(String stdout) {
        return parse(stdout, Integer.MAX_VALUE);
    }

    /** Parses at most {@code maxMatches}; bounds allocations when rg reports a very broad query. */
    public static List<FileResult> parse(String stdout, int maxMatches) {
        return parse(stdout, maxMatches, ignored -> true);
    }

    /** Parses only authoritative paths, so discarded open-file disk hits do not consume the match budget. */
    public static List<FileResult> parse(String stdout, int maxMatches, Predicate<Path> includePath) {
        if (stdout == null || stdout.isEmpty()) {
            return List.of();
        }
        Collector collector = new Collector(maxMatches, includePath);
        for (String line : stdout.split("\n", -1)) {
            if (collector.accept(line)) {
                break;
            }
        }
        return collector.results();
    }

    /**
     * The parser, one line at a time — so a caller reading rg's output as it is produced can stop rg at the
     * line that fills the match budget. Collecting all of stdout first meant a broad query kept rg searching
     * the whole tree (and Editora buffering megabytes of JSON) for results that were then thrown away.
     * Thread-safe: lines arrive on a pipe-reader thread and the results are read from the caller's.
     */
    public static final class Collector {
        private final Map<String, List<LineMatch>> byFile = new LinkedHashMap<>();
        private final int maxMatches;
        private final Predicate<Path> includePath;
        private int total;
        private boolean summary;

        public Collector(int maxMatches, Predicate<Path> includePath) {
            this.maxMatches = maxMatches;
            this.includePath = includePath;
        }

        /** Takes one line of {@code --json} output; true once the match budget is full (stop feeding). */
        public synchronized boolean accept(String line) {
            if (total >= maxMatches) {
                return true;
            }
            if (line == null || line.isBlank()) {
                return false;
            }
            try {
                JsonNode root = JSON.readTree(line);
                String type = text(root.get("type"));
                if ("summary".equals(type)) {
                    summary = true;
                }
                if (!"match".equals(type)) {
                    return false;
                }
                JsonNode data = root.get("data");
                String path = textField(data.get("path"));
                String lineText = textField(data.get("lines"));
                JsonNode lineNo = data.get("line_number");
                if (path == null || lineText == null || lineNo == null || !lineNo.isInt()) {
                    return false; // binary (base64 bytes) or missing fields
                }
                Path parsedPath = Path.of(path);
                if (!includePath.test(parsedPath)) {
                    return false;
                }
                String stripped = stripEol(lineText);
                List<LineMatch> matches = byFile.computeIfAbsent(path, k -> new ArrayList<>());
                JsonNode subs = data.get("submatches");
                if (subs == null || !subs.isArray()) {
                    return false;
                }
                for (JsonNode sub : subs) {
                    JsonNode start = sub.get("start");
                    JsonNode end = sub.get("end");
                    if (start == null || end == null) {
                        continue;
                    }
                    int col = charIndexForByteOffset(stripped, start.asInt());
                    int endCol = charIndexForByteOffset(stripped, end.asInt());
                    matches.add(new LineMatch(lineNo.asInt(), col + 1, Math.max(0, endCol - col), stripped));
                    total++;
                    if (total >= maxMatches) {
                        break;
                    }
                }
            } catch (Exception ignored) {
                // skip a malformed line
            }
            return total >= maxMatches;
        }

        /** Whether the budget filled, i.e. whether there may be matches beyond the ones collected. */
        public synchronized boolean full() {
            return total >= maxMatches;
        }

        /** Whether rg's closing {@code summary} event was seen: it ran to the end of its search. */
        public synchronized boolean sawSummary() {
            return summary;
        }

        /** Per-file results in rg's emission order. */
        public synchronized List<FileResult> results() {
            List<FileResult> out = new ArrayList<>(byFile.size());
            for (Map.Entry<String, List<LineMatch>> e : byFile.entrySet()) {
                if (!e.getValue().isEmpty()) {
                    out.add(new FileResult(Path.of(e.getKey()), e.getValue()));
                }
            }
            return out;
        }
    }

    /** rg objects carry text as {@code {"text": "..."}} (or {@code {"bytes": "<base64>"}} for non-UTF-8). */
    private static String textField(JsonNode node) {
        return node == null ? null : text(node.get("text"));
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }

    private static String stripEol(String s) {
        int end = s.length();
        if (end > 0 && s.charAt(end - 1) == '\n') {
            end--;
        }
        if (end > 0 && s.charAt(end - 1) == '\r') {
            end--;
        }
        return s.substring(0, end);
    }

    /**
     * The Java {@code char} (UTF-16) index at the given UTF-8 byte offset of {@code line} (offset assumed to
     * fall on a code-point boundary, as rg's submatch offsets do). For pure-ASCII lines this equals the byte
     * offset, so it matches the literal-search column exactly.
     */
    static int charIndexForByteOffset(String line, int byteOffset) {
        int bytes = 0;
        int i = 0;
        while (i < line.length() && bytes < byteOffset) {
            int cp = line.codePointAt(i);
            bytes += utf8Length(cp);
            i += Character.charCount(cp);
        }
        return i;
    }

    private static int utf8Length(int cp) {
        if (cp < 0x80) {
            return 1;
        } else if (cp < 0x800) {
            return 2;
        } else if (cp < 0x10000) {
            return 3;
        }
        return 4;
    }
}
