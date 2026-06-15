package com.editora.http;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pure parser for a {@code .env} file: {@code KEY=VALUE} lines, optional surrounding quotes, {@code #}
 * comments and blank lines skipped, an optional leading {@code export }. Backs the {@code {{$dotenv.NAME}}}
 * dynamic variable. Takes the file content as a string, so it is unit-tested.
 */
public final class DotEnv {

    private DotEnv() {}

    /** Parses {@code content} into {@code KEY → VALUE}, in declaration order. */
    public static Map<String, String> parse(String content) {
        Map<String, String> out = new LinkedHashMap<>();
        if (content == null || content.isBlank()) {
            return out;
        }
        for (String raw : content.split("\n", -1)) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("export ")) {
                line = line.substring("export ".length()).strip();
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            out.put(
                    line.substring(0, eq).strip(),
                    unquote(line.substring(eq + 1).strip()));
        }
        return out;
    }

    private static String unquote(String v) {
        if (v.length() >= 2 && ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'")))) {
            return v.substring(1, v.length() - 1);
        }
        return v;
    }
}
