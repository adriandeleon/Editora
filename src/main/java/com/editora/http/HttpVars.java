package com.editora.http;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves {@code {{variable}}} references in a {@code .http} request: named variables come from the
 * environment file + the file's {@code @name = value} declarations; {@code {{$…}}} are dynamic built-ins
 * (see {@link DynamicVars}); and {@code {{request.response.…}}} references pull from earlier responses in the
 * same run (see {@link CapturedResponses}). Pure given a fixed clock (the dynamic/random aside), so the
 * resolution + timestamp logic is unit-tested.
 */
public final class HttpVars {

    private static final Pattern VAR = Pattern.compile("\\{\\{\\s*([^}]*?)\\s*\\}\\}");

    private HttpVars() {}

    /** {@link #resolve(Map, List, LocalDateTime, Path)} with no file directory (no {@code $dotenv}). */
    public static Map<String, String> resolve(Map<String, String> envVars, List<String[]> fileVars, LocalDateTime now) {
        return resolve(envVars, fileVars, now, null);
    }

    /**
     * Builds the effective variable map: the environment variables, then the file's {@code @var}
     * declarations layered on top (each resolved against the accumulating map, so a later {@code @var}
     * may reference an earlier one, an env var, or a dynamic built-in).
     */
    public static Map<String, String> resolve(
            Map<String, String> envVars, List<String[]> fileVars, LocalDateTime now, Path dir) {
        Map<String, String> map = new LinkedHashMap<>(envVars == null ? Map.of() : envVars);
        if (fileVars != null) {
            for (String[] pair : fileVars) {
                map.put(pair[0], substitute(pair[1], map, now, null, dir));
            }
        }
        return map;
    }

    /** {@link #substitute(String, Map, LocalDateTime, CapturedResponses, Path)} with no chaining/dir context. */
    public static String substitute(String text, Map<String, String> vars, LocalDateTime now) {
        return substitute(text, vars, now, null, null);
    }

    /**
     * Replaces every {@code {{name}}} in {@code text}: dynamic {@code $…} built-ins (resolved with {@code now}
     * and {@code dir}), {@code request.response.…} references (resolved against {@code captured} when given),
     * else the variable map. Unknown references → {@code ""}.
     */
    public static String substitute(
            String text, Map<String, String> vars, LocalDateTime now, CapturedResponses captured, Path dir) {
        if (text == null || text.isEmpty()) {
            return text == null ? "" : text;
        }
        Matcher m = VAR.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(value(m.group(1), vars, now, captured, dir)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String value(
            String name, Map<String, String> vars, LocalDateTime now, CapturedResponses captured, Path dir) {
        if (DynamicVars.isDynamic(name)) {
            return DynamicVars.value(name, now, dir);
        }
        if (captured != null && CapturedResponses.isResponseRef(name)) {
            return captured.resolve(name);
        }
        return vars == null ? "" : vars.getOrDefault(name, "");
    }
}
