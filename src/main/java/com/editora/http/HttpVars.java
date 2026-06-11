package com.editora.http;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves {@code {{variable}}} references in a {@code .http} request (replacing what {@code ijhttp} used
 * to do): named variables come from the environment file + the file's {@code @name = value} declarations;
 * {@code {{$uuid}}}/{@code {{$timestamp}}}/{@code {{$randomInt}}}/{@code {{$isoTimestamp}}} are dynamic
 * built-ins. Pure given a fixed clock (the dynamic uuid/random aside), so the resolution + timestamp logic
 * is unit-tested.
 */
public final class HttpVars {

    private static final Pattern VAR = Pattern.compile("\\{\\{\\s*([^}]*?)\\s*\\}\\}");

    private HttpVars() {
    }

    /**
     * Builds the effective variable map: the environment variables, then the file's {@code @var}
     * declarations layered on top (each resolved against the accumulating map, so a later {@code @var}
     * may reference an earlier one or an env var).
     */
    public static Map<String, String> resolve(Map<String, String> envVars, List<String[]> fileVars,
            LocalDateTime now) {
        Map<String, String> map = new LinkedHashMap<>(envVars == null ? Map.of() : envVars);
        if (fileVars != null) {
            for (String[] pair : fileVars) {
                map.put(pair[0], substitute(pair[1], map, now));
            }
        }
        return map;
    }

    /** Replaces every {@code {{name}}} in {@code text} with its value (built-ins resolved; unknown → ""). */
    public static String substitute(String text, Map<String, String> vars, LocalDateTime now) {
        if (text == null || text.isEmpty()) {
            return text == null ? "" : text;
        }
        Matcher m = VAR.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(value(m.group(1), vars, now)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String value(String name, Map<String, String> vars, LocalDateTime now) {
        if (name.startsWith("$")) {
            return builtin(name, now);
        }
        return vars == null ? "" : vars.getOrDefault(name, "");
    }

    private static String builtin(String name, LocalDateTime now) {
        return switch (name) {
            case "$uuid", "$random.uuid" -> UUID.randomUUID().toString();
            case "$timestamp" -> String.valueOf(now.toEpochSecond(ZoneOffset.UTC));
            case "$isoTimestamp" -> now.atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT);
            case "$randomInt" -> String.valueOf(ThreadLocalRandom.current().nextInt(0, 1000));
            default -> "";
        };
    }
}
