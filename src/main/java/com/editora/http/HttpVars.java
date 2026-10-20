package com.editora.http;

import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

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

    /**
     * {@code {{ name }}} — no braces inside, so the scan is linear even on pathological input — unless it is
     * escaped as {@code \{{}, which sends a literal {@code {{} (see {@link #ESCAPE}). This is the one scanner for
     * {@code {{…}}} in the package: the trust gate, the editor marks and the "Running …" label use it too (through
     * {@link #referencePattern()}), so they see exactly the references a run resolves, and none it does not.
     */
    private static final Pattern VAR = Pattern.compile("(?<!\\\\)\\{\\{([^{}]*)\\}\\}");

    /**
     * The escape for a literal {@code {{}: {@code \{{} in a request is sent as {@code {{} and never resolved
     * (Import cURL writes every {@code {{} of an imported command this way, so pasted text cannot read a variable).
     */
    public static final String ESCAPE = "\\{{";

    /** How deep environment values may refer to one another (a longer chain is reported as unresolved). */
    private static final int MAX_DEPTH = 32;

    private HttpVars() {}

    /** The {@code {{…}}} reference pattern every resolver and scanner shares; group 1 is the name, unstripped. */
    public static Pattern referencePattern() {
        return VAR;
    }

    /** {@code text} with each {@link #ESCAPE} turned into the literal {@code {{} it stands for (what is sent). */
    public static String unescape(String text) {
        return text == null || text.indexOf(ESCAPE) < 0 ? text : text.replace(ESCAPE, "{{");
    }

    /** {@code text} with every {@code {{} written as {@link #ESCAPE}, so none of it is resolved as a reference. */
    public static String escape(String text) {
        return text == null || text.indexOf("{{") < 0 ? text : text.replace("{{", ESCAPE);
    }

    /**
     * The references a substitution could not resolve, in first-seen order, each with a short reason. Not
     * thread-safe; one sink belongs to one request's resolution.
     */
    public static final class Unresolved {
        private final Map<String, String> reasons = new LinkedHashMap<>();
        private final Set<String> secretReads = new LinkedHashSet<>();
        private final Set<String> secretValues = new LinkedHashSet<>();
        private final Set<String> responseReads = new LinkedHashSet<>();
        private final Set<String> dynamicReads = new LinkedHashSet<>();
        private final Set<String> sessionReads = new LinkedHashSet<>();
        private final boolean dry;

        public Unresolved() {
            this(false);
        }

        private Unresolved(boolean dry) {
            this.dry = dry;
        }

        /**
         * A sink for a dry resolution: every secret source ({@link DynamicVars#isSecretSource}) is recorded but
         * left in the text as written, so the result can be shown (hosts in the trust dialog) without the secret.
         */
        public static Unresolved dry() {
            return new Unresolved(true);
        }

        /** Records {@code reference} (e.g. {@code {{token}}}) as unresolved for {@code reason}. */
        public void add(String reference, String reason) {
            reasons.putIfAbsent(reference, reason == null ? "" : reason);
        }

        void addAll(@Nullable Unresolved other) {
            if (other != null) {
                other.reasons.forEach(this::add);
                secretReads.addAll(other.secretReads);
                secretValues.addAll(other.secretValues);
                responseReads.addAll(other.responseReads);
                dynamicReads.addAll(other.dynamicReads);
                sessionReads.addAll(other.sessionReads);
            }
        }

        /**
         * The secret sources this resolution read — {@code $processEnv X}, {@code $env.X}, {@code $dotenv X} — as
         * written (stripped), in first-read order: directly, through an {@code @var} line or an environment value.
         * Only the references that reached the substituted text count, so it is exactly what a run would send.
         */
        public List<String> secretReads() {
            return List.copyOf(secretReads);
        }

        /**
         * The values those secret reads resolved to (never in a dry resolution; blank ones left out), so what is
         * kept of the run — the saved history — can mask them. Never persist or log this list itself.
         */
        public List<String> secretValues() {
            return List.copyOf(secretValues);
        }

        /**
         * The {@code {{name.response…}}} references this resolution used, resolved or not, directly or through an
         * {@code @var} line: text that comes from an earlier response (the trust gate cannot know such a host).
         */
        public List<String> responseReads() {
            return List.copyOf(responseReads);
        }

        /**
         * The variables it used, directly or through an {@code @var} line or an environment value, whose value a
         * script or a response set: session variables ({@code client.global}, {@code @capture}) and a pre-request
         * script's {@code request.variables}. Like {@link #responseReads()}, a host made of one is only known at the
         * send.
         */
        public List<String> sessionReads() {
            return List.copyOf(sessionReads);
        }

        /** The other {@code {{$…}}} built-ins it used ({@code $uuid}, {@code $timestamp}): a fresh value each time. */
        public List<String> dynamicReads() {
            return List.copyOf(dynamicReads);
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

        @Nullable
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
     * {@code "api": "{{base}}/api"} works) with {@code captured}'s session variables over them, then {@code fileVars}
     * layered on top in order (each resolved against what is in effect at its line, so a later {@code @var} may use
     * an earlier one, an environment or session variable, a dynamic built-in, or — given {@code captured} — an
     * earlier response). A session variable came from a response: its value is used as is, never resolved, so a
     * response cannot make a request read a secret. A {@code fileVars} entry with a third element is a value a
     * pre-request script set (already escaped, see {@link HttpFile.Parsed#withScriptVariables}). Every use of either
     * is recorded in {@link Unresolved#sessionReads()}.
     */
    public static Scope scope(
            Map<String, String> envVars,
            List<String[]> fileVars,
            ZonedDateTime now,
            @Nullable CapturedResponses captured,
            @Nullable Path dir) {
        return scope(envVars, fileVars, now, captured, dir, false);
    }

    /**
     * As {@link #scope(Map, List, ZonedDateTime, CapturedResponses, Path)}; when {@code dry}, a secret source in an
     * environment value or an {@code @var} line is recorded but kept as written (see {@link Unresolved#dry()}).
     */
    public static Scope scope(
            Map<String, String> envVars,
            List<String[]> fileVars,
            ZonedDateTime now,
            @Nullable CapturedResponses captured,
            @Nullable Path dir,
            boolean dry) {
        Map<String, String> raw = envVars == null ? Map.of() : envVars;
        Map<String, String> globals = captured == null ? Map.of() : captured.globals();
        if (!globals.isEmpty()) {
            Map<String, String> layered = new LinkedHashMap<>(raw);
            globals.forEach((name, value) -> layered.put(name, escape(value))); // escaped: inserted, never resolved
            raw = layered;
        }
        Map<String, String> values = new LinkedHashMap<>();
        Map<String, Unresolved> missing = new HashMap<>();
        Set<String> fileNames = new HashSet<>();
        if (fileVars != null) {
            fileVars.forEach(pair -> fileNames.add(pair[0]));
        }
        EnvResolver env = new EnvResolver(raw, fileNames, values, missing, now, dir, dry, globals.keySet());
        for (String key : raw.keySet()) {
            env.resolve(key, new HashSet<>());
        }
        if (fileVars != null) {
            for (String[] pair : fileVars) {
                Unresolved local = new Unresolved(dry);
                if (pair.length > 2) {
                    local.sessionReads.add(pair[0]); // set by a pre-request script
                }
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
            Set<String> fileNames,
            Map<String, String> values,
            Map<String, Unresolved> missing,
            ZonedDateTime now,
            @Nullable Path dir,
            boolean dry,
            Set<String> session) {

        @Nullable
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
                markSession(key);
                return rawValue;
            }
            Unresolved local = new Unresolved(dry);
            inProgress.add(key);
            String v = expand(
                    rawValue,
                    name -> raw.containsKey(name) || fileNames.contains(name),
                    (name, sink) -> {
                        if (!raw.containsKey(name)) {
                            // Environment values are resolved before, and without, the file's @var lines.
                            sink.add(ref(name), tr("http.unresolved.reason.envUsesFileVar"));
                            return "";
                        }
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
            markSession(key);
            return v;
        }

        /** A session variable's own entry records that it was read, so every value that uses it carries that on. */
        private void markSession(String key) {
            if (session.contains(key)) {
                missing.computeIfAbsent(key, k -> new Unresolved(dry))
                        .sessionReads
                        .add(key);
            }
        }
    }

    // --- substituting -------------------------------------------------------------------------------

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
        @Nullable
        String value(String name, Unresolved sink);
    }

    private static String expand(
            String text,
            Has has,
            Value value,
            ZonedDateTime now,
            @Nullable CapturedResponses captured,
            @Nullable Path dir,
            Unresolved sink) {
        if (text == null || text.isEmpty()) {
            return text == null ? "" : text;
        }
        if (text.indexOf("{{") < 0) {
            return text;
        }
        Unresolved out = sink == null ? new Unresolved() : sink;
        Matcher m = VAR.matcher(text);
        StringBuilder sb = new StringBuilder(text.length());
        int last = 0;
        while (m.find()) {
            sb.append(unescape(text.substring(last, m.start()))); // an escaped \{{ goes out as a literal {{
            String name = m.group(1).strip();
            sb.append(one(name, has, value, now, captured, dir, out));
            last = m.end();
        }
        sb.append(unescape(text.substring(last)));
        return sb.toString();
    }

    private static String one(
            String name,
            Has has,
            Value value,
            ZonedDateTime now,
            @Nullable CapturedResponses captured,
            @Nullable Path dir,
            Unresolved sink) {
        String[] reason = new String[1];
        if (DynamicVars.isSecretSource(name)) {
            sink.secretReads.add(name); // what the trust gate asks about: exactly what is read here
            if (sink.dry) {
                return ref(name);
            }
        }
        if (DynamicVars.isDynamic(name)) {
            if (!DynamicVars.isSecretSource(name)) {
                sink.dynamicReads.add(name);
            }
            String v = DynamicVars.resolve(name, now, dir, reason);
            if (v == null) {
                sink.add(ref(name), reason[0]);
                return "";
            }
            if (DynamicVars.isSecretSource(name) && !v.isBlank()) {
                sink.secretValues.add(v);
            }
            return v;
        }
        if (has.has(name)) {
            String v = value.value(name, sink);
            return v == null ? "" : v;
        }
        if (CapturedResponses.isResponseRef(name)) {
            sink.responseReads.add(name);
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
}
