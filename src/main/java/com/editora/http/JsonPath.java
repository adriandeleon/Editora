package com.editora.http;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * A tiny JSONPath <b>subset</b> evaluator (Jackson-backed) used to pull values out of a captured response
 * body for request chaining ({@code {{request.response.body.$.a.b}}}). Supports a leading {@code $}, dotted
 * fields ({@code .field}), bracketed fields ({@code ["a.b"]}/{@code ['a.b']}), array indices ({@code [0]}),
 * and {@code *} (the whole node). Any miss yields {@code ""} — it never throws, mirroring the unknown-variable
 * contract of {@link HttpVars}. Pure, so it is unit-tested.
 */
public final class JsonPath {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonPath() {}

    /** The value at {@code path} in {@code json} as text (objects/arrays serialized), or {@code ""}. */
    public static String eval(String json, String path) {
        if (json == null || json.isBlank()) {
            return "";
        }
        try {
            JsonNode node = node(MAPPER.readTree(json), path);
            if (node == null || node.isMissingNode() || node.isNull()) {
                return "";
            }
            return node.isValueNode() ? node.asText() : node.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** Walks {@code path} from {@code root}; {@code null} on any miss. */
    static JsonNode node(JsonNode root, String path) {
        JsonNode cur = root;
        for (String tok : tokenize(path)) {
            if (cur == null) {
                return null;
            }
            if (tok.equals("$") || tok.equals("*")) {
                continue; // root / whole-node passthrough
            }
            if (isIndex(tok)) {
                int idx = Integer.parseInt(tok);
                cur = cur.isArray() && idx >= 0 && idx < cur.size() ? cur.get(idx) : null;
            } else {
                cur = cur.isObject() ? cur.get(tok) : null;
            }
        }
        return cur;
    }

    /** Splits a path like {@code $.a.b[2]["c.d"]} into its field/index tokens. */
    static List<String> tokenize(String path) {
        List<String> out = new ArrayList<>();
        if (path == null) {
            return out;
        }
        String p = path.strip();
        int i = 0;
        int n = p.length();
        while (i < n) {
            char c = p.charAt(i);
            if (c == '$') {
                out.add("$");
                i++;
            } else if (c == '.') {
                i++; // the field name follows
            } else if (c == '[') {
                int end = p.indexOf(']', i);
                if (end < 0) {
                    break;
                }
                out.add(stripQuotes(p.substring(i + 1, end).strip()));
                i = end + 1;
            } else {
                int start = i;
                while (i < n && p.charAt(i) != '.' && p.charAt(i) != '[') {
                    i++;
                }
                out.add(p.substring(start, i));
            }
        }
        return out;
    }

    private static boolean isIndex(String tok) {
        if (tok.isEmpty()) {
            return false;
        }
        for (int i = 0; i < tok.length(); i++) {
            if (!Character.isDigit(tok.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static String stripQuotes(String s) {
        if (s.length() >= 2 && ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'")))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }
}
