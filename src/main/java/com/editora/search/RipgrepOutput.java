package com.editora.search;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        Map<String, List<LineMatch>> byFile = new LinkedHashMap<>();
        if (stdout == null || stdout.isEmpty()) {
            return List.of();
        }
        for (String line : stdout.split("\n", -1)) {
            if (line.isBlank()) {
                continue;
            }
            try {
                JsonNode root = JSON.readTree(line);
                if (!"match".equals(text(root.get("type")))) {
                    continue;
                }
                JsonNode data = root.get("data");
                String path = textField(data.get("path"));
                String lineText = textField(data.get("lines"));
                JsonNode lineNo = data.get("line_number");
                if (path == null || lineText == null || lineNo == null || !lineNo.isInt()) {
                    continue; // binary (base64 bytes) or missing fields
                }
                String stripped = stripEol(lineText);
                List<LineMatch> matches = byFile.computeIfAbsent(path, k -> new ArrayList<>());
                JsonNode subs = data.get("submatches");
                if (subs == null || !subs.isArray()) {
                    continue;
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
                }
            } catch (Exception ignored) {
                // skip a malformed line
            }
        }
        List<FileResult> out = new ArrayList<>(byFile.size());
        for (Map.Entry<String, List<LineMatch>> e : byFile.entrySet()) {
            if (!e.getValue().isEmpty()) {
                out.add(new FileResult(Path.of(e.getKey()), e.getValue()));
            }
        }
        return out;
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
