package com.editora.http;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.editora.i18n.Messages.tr;

/**
 * Resolves {@code {{variable}}} references in a {@code .http} request: named variables come from the
 * environment files + the {@code @name = value} declarations above the request; {@code {{$…}}} are dynamic
 * built-ins (see {@link DynamicVars}); and {@code {{request.response.…}}} references pull from earlier
 * responses (see {@link CapturedResponses}). An environment value may itself use {@code {{other}}}.
 *
 * <p>A reference that cannot be resolved is replaced by {@code ""} — the request still goes out — and is
 * recorded in an {@link Unresolved} sink, which the executor turns into one warning per reference, so a typo
 * or a missing environment is visible instead of a puzzling 401. Pure given a fixed clock (the
 * dynamic/random aside), so it is unit-tested.
 */
public final class HttpVars {

    /** {@code {{ name }}} — no braces inside, so the scan is linear even on pathological input. */
    private static final Pattern VAR = Pattern.compile("\\{\\{([^{}]*)\\}\\}");

    /** How deep environment values may refer to one another (a longer chain is reported as unresolved). */
    private static final int MAX_DEPTH = 32;

    private HttpVars() {}

    /**
     * The references a substitution could not resolve, in first-seen order, each with a short reason. Not
     * thread-safe; one sink belongs to one request's resolution.
     */
    public static final class Unresolved {
        private final Map<String, String> reasons = new LinkedHashMap<>();

        /** Records {@code reference} (e.g. {@code {{token}}}) as unresolved for {@code reason}. */
        public void add(String reference, String reason) {
            reasons.putIfAbsent(reference, reason == null ? "" : reason);
        }

        void addAll(Unresolved other) {
            if (other != null) {
                other.reasons.forEach(this::add);
            }
        }

        public boolean isEmpty() {
            return reasons.isEmpty();
        }

        /** {@code {{reference}} → reason}, in first-seen order. */
        public Map<String, String> entries() {
            return Collections.unmodifiableMap(reasons);
        }

        /** One user-facing warning per unresolved reference. */
        public List<String> warnings() {
            List<String> out = new ArrayList<>();
            reasons.forEach((ref, why) -> out.add(tr("http.warn.unresolved", ref, why)));
            return out;
        }
    }

    /**
     * The variables one request sees: each name's value, plus the unresolved references inside that value
     * (an {@code @auth = Bearer {{token}}} with no {@code token}), which are reported where the name is used.
     */
    public static final class Scope {
        private final Map<String, String> values;
        private final Map<String, Unresolved> missing;

        private Scope(Map<String, String> values, Map<String, Unresolved> missing) {
            this.values = values;
            this.missing = missing;
        }

        /** A scope of already-resolved {@code values}. */
        public static Scope of(Map<String, String> values) {
            return new Scope(values == null ? Map.of() : values, Map.of());
        }

        /** The resolved name → value map. */
        public Map<String, String> values() {
            return Collections.unmodifiableMap(values);
        }

        boolean has(String name) {
            return values.containsKey(name);
        }

        String value(String name, Unresolved sink) {
            if (sink != null) {
                sink.addAll(missing.get(name));
            }
            return values.get(name);
        }
    }

    // --- building a scope ------------------------------------------------------------------------------

    /**
     * Builds the variables for one request: the environment's values (each resolved against the others, so
     * {@code "api": "{{base}}/api"} works), then {@code fileVars} layered on top in order (each resolved
     * against what is in effect at its line, so a later {@code @var} may use an earlier one, an environment
     * variable, a dynamic built-in, or — given {@code captured} — an earlier response).
     */
    public static Scope scope(
            Map<String, String> envVars,
            List<String[]> fileVars,
            ZonedDateTime now,
            CapturedResponses captured,
            Path dir) {
        Map<String, String> raw = envVars == null ? Map.of() : envVars;
        Map<String, String> values = new LinkedHashMap<>();
        Map<String, Unresolved> missing = new HashMap<>();
        EnvResolver env = new EnvResolver(raw, values, missing, now, dir);
        for (String key : raw.keySet()) {
            env.resolve(key, new HashSet<>());
        }
        if (fileVars != null) {
            for (String[] pair : fileVars) {
                Unresolved local = new Unresolved();
                Scope current = new Scope(values, missing);
                String v = expand(pair[1], current::has, current::value, now, captured, dir, local);
                values.put(pair[0], v);
                missing.put(pair[0], local);
            }
        }
        return new Scope(values, missing);
    }

    /** Resolves environment values that refer to other environment values, depth-first with a cycle guard. */
    private record EnvResolver(
            Map<String, String> raw,
            Map<String, String> values,
            Map<String, Unresolved> missing,
            ZonedDateTime now,
            Path dir) {

        String resolve(String key, Set<String> inProgress) {
            if (values.containsKey(key)) {
                return values.get(key);
            }
            String rawValue = raw.get(key);
            if (rawValue == null) {
                return null;
            }
            if (rawValue.indexOf("{{") < 0) {
                values.put(key, rawValue);
                return rawValue;
            }
            Unresolved local = new Unresolved();
            inProgress.add(key);
            String v = expand(
                    rawValue,
                    raw::containsKey,
                    (name, sink) -> {
                        if (!values.containsKey(name) && (inProgress.contains(name) || inProgress.size() > MAX_DEPTH)) {
                            sink.add(ref(name), tr("http.unresolved.reason.cycle"));
                            return "";
                        }
                        String inner = resolve(name, inProgress);
                        sink.addAll(missing.get(name));
                        return inner == null ? "" : inner;
                    },
                    now,
                    null,
                    dir,
                    local);
            inProgress.remove(key);
            values.put(key, v);
            missing.put(key, local);
            return v;
        }
    }

    /** The resolved values of {@link #scope} (no captured responses), for clock {@code now}. */
    public static Map<String, String> resolve(
            Map<String, String> envVars, List<String[]> fileVars, ZonedDateTime now, Path dir) {
        return new LinkedHashMap<>(scope(envVars, fileVars, now, null, dir).values());
    }

    /** {@link #substitute(String, Scope, ZonedDateTime, CapturedResponses, Path, Unresolved)} for a plain map,
     *  with no chaining/dir context and no record of what did not resolve. */
    public static String substitute(String text, Map<String, String> vars, ZonedDateTime now) {
        return substitute(text, Scope.of(vars), now, null, null, null);
    }

    /** {@link #resolve(Map, List, LocalDateTime, Path)} with no file directory (no {@code $dotenv}). */
    public static Map<String, String> resolve(Map<String, String> envVars, List<String[]> fileVars, LocalDateTime now) {
        return resolve(envVars, fileVars, now, null);
    }

    /** The resolved values of {@link #scope} for a wall-clock {@code now} in the system time zone. */
    public static Map<String, String> resolve(
            Map<String, String> envVars, List<String[]> fileVars, LocalDateTime now, Path dir) {
        return new LinkedHashMap<>(
                scope(envVars, fileVars, zoned(now), null, dir).values());
    }

    // --- substituting -------------------------------------------------------------------------------

    /** {@link #substitute(String, Map, LocalDateTime, CapturedResponses, Path)} with no chaining/dir context. */
    public static String substitute(String text, Map<String, String> vars, LocalDateTime now) {
        return substitute(text, vars, now, null, null);
    }

    /** {@link #substitute(String, Scope, ZonedDateTime, CapturedResponses, Path, Unresolved)} for a plain map,
     *  a wall-clock {@code now} in the system time zone, and no record of what did not resolve. */
    public static String substitute(
            String text, Map<String, String> vars, LocalDateTime now, CapturedResponses captured, Path dir) {
        return substitute(text, Scope.of(vars), zoned(now), captured, dir, null);
    }

    /**
     * Replaces every {@code {{name}}} in {@code text}: dynamic {@code $…} built-ins (resolved with {@code now}
     * and {@code dir}), a variable of {@code scope}, else a {@code request.response.…} reference (resolved
     * against {@code captured}). A reference that cannot be resolved becomes {@code ""} and is added to
     * {@code unresolved} (when non-null) with the reason. Values are inserted as is, never re-scanned.
     */
    public static String substitute(
            String text, Scope scope, ZonedDateTime now, CapturedResponses captured, Path dir, Unresolved unresolved) {
        Scope s = scope == null ? Scope.of(Map.of()) : scope;
        return expand(text, s::has, s::value, now, captured, dir, unresolved);
    }

    @FunctionalInterface
    private interface Has {
        boolean has(String name);
    }

    @FunctionalInterface
    private interface Value {
        String value(String name, Unresolved sink);
    }

    private static String expand(
            String text,
            Has has,
            Value value,
            ZonedDateTime now,
            CapturedResponses captured,
            Path dir,
            Unresolved sink) {
        if (text == null || text.isEmpty()) {
            return text == null ? "" : text;
        }
        if (text.indexOf("{{") < 0) {
            return text;
        }
        Unresolved out = sink == null ? new Unresolved() : sink;
        Matcher m = VAR.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String name = m.group(1).strip();
            m.appendReplacement(sb, Matcher.quoteReplacement(one(name, has, value, now, captured, dir, out)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String one(
            String name,
            Has has,
            Value value,
            ZonedDateTime now,
            CapturedResponses captured,
            Path dir,
            Unresolved sink) {
        String[] reason = new String[1];
        if (DynamicVars.isDynamic(name)) {
            String v = DynamicVars.resolve(name, now, dir, reason);
            if (v == null) {
                sink.add(ref(name), reason[0]);
                return "";
            }
            return v;
        }
        if (has.has(name)) {
            String v = value.value(name, sink);
            return v == null ? "" : v;
        }
        if (CapturedResponses.isResponseRef(name)) {
            String v = captured == null ? null : captured.lookup(name, reason);
            if (v == null) {
                String request = name.substring(0, name.indexOf(".response."));
                sink.add(ref(name), reason[0] != null ? reason[0] : tr("http.unresolved.reason.noResponse", request));
                return "";
            }
            return v;
        }
        sink.add(ref(name), tr("http.unresolved.reason.undefined"));
        return "";
    }

    private static String ref(String name) {
        return "{{" + name + "}}";
    }

    private static ZonedDateTime zoned(LocalDateTime now) {
        return now == null ? null : now.atZone(ZoneId.systemDefault());
    }
}
