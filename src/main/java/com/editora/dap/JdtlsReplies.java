package com.editora.dap;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Reading what jdtls answers the debugger's {@code workspace/executeCommand}s ({@code vscode.java.*}): lsp4j hands
 * those untyped results back as gson elements or as plain collections, depending on the payload. Pure apart from
 * the log line a reply that cannot be read leaves (moved out of {@link DapManager}; same logger).
 */
final class JdtlsReplies {

    private static final Logger LOG = Logger.getLogger(DapManager.class.getName());

    private JdtlsReplies() {}

    /**
     * Reads {@code vscode.java.resolveMainClass}'s reply — {@code [{mainClass, projectName, filePath}, …]}.
     *
     * <p>Handles a raw {@link java.util.List} of {@link Map}s as well as a gson {@link JsonElement}, for the
     * same reason {@link #parseClasspath} does — and here it was not merely a hazard but the whole feature:
     * lsp4j hands this particular result back as an {@code ArrayList<LinkedTreeMap>}, so {@link #asJson}'s
     * {@code String.valueOf} fallback fed gson Java's {@code toString()} form,
     * {@code [{mainClass=com.example.App, filePath=/home/…}]}. Gson's lenient parser reads unquoted keys and
     * {@code =} separators, but not an unquoted value beginning {@code /} (it scans as a comment) — so every
     * absolute file path threw {@code MalformedJsonException}, {@code asJson} logged it at {@code FINE} and
     * returned null, and Editora reported "no main class was found" for a project whose main class jdtls had
     * just named. Confirmed against a real jdtls in {@code JdtlsResolveMainClassProbeTest}. Reading the list
     * directly removes the round trip entirely.
     */
    static List<DapManager.MainClassOption> parseMainClasses(Object res) {
        List<DapManager.MainClassOption> out = new ArrayList<>();
        if (res instanceof List<?> list) {
            for (Object e : list) {
                if (e instanceof Map<?, ?> m) {
                    out.add(new DapManager.MainClassOption(
                            str(m, "mainClass"), str(m, "projectName"), str(m, "filePath")));
                } else if (e instanceof JsonObject o) {
                    out.add(new DapManager.MainClassOption(
                            str(o, "mainClass"), str(o, "projectName"), str(o, "filePath")));
                }
            }
            return out;
        }
        JsonElement el = asJson(res);
        if (el != null && el.isJsonArray()) {
            for (JsonElement e : el.getAsJsonArray()) {
                if (e.isJsonObject()) {
                    JsonObject o = e.getAsJsonObject();
                    out.add(new DapManager.MainClassOption(
                            str(o, "mainClass"), str(o, "projectName"), str(o, "filePath")));
                }
            }
        }
        return out;
    }

    /**
     * resolveClasspath returns {@code [modulepaths, classpaths]} (a 2-element array of string arrays).
     *
     * <p>Handles a raw {@link java.util.List} as well as a gson {@link JsonElement}. lsp4j deserializes an
     * untyped {@code executeCommand} result to whichever the payload maps to, and the {@link #asJson}
     * fallback — {@code JsonParser.parseString(String.valueOf(res))} — round-trips a {@code List} through
     * Java's {@code toString()}, which emits <em>unquoted</em> elements. Gson's lenient parser accepts that
     * for a plain path but not for one containing a comma, bracket or space, so a classpath entry could be
     * silently dropped or mangled depending on where the project happens to live. Reading the list directly
     * removes the round trip entirely.
     */
    static void parseClasspath(Object res, List<String> modulepaths, List<String> classpaths) {
        if (res instanceof List<?> list) {
            if (!list.isEmpty()) {
                collectStrings(list.get(0), modulepaths);
            }
            if (list.size() >= 2) {
                collectStrings(list.get(1), classpaths);
            }
            return;
        }
        JsonElement el = asJson(res);
        if (el != null && el.isJsonArray()) {
            JsonArray arr = el.getAsJsonArray();
            if (arr.size() >= 1) {
                collectStrings(arr.get(0), modulepaths);
            }
            if (arr.size() >= 2) {
                collectStrings(arr.get(1), classpaths);
            }
        }
    }

    /** Collects strings from either a gson array or a plain {@link java.util.List}. */
    static void collectStrings(Object maybeList, List<String> out) {
        if (maybeList instanceof JsonElement el) {
            collectStrings(el, out);
            return;
        }
        if (maybeList instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof String s) {
                    out.add(s);
                } else if (o != null) {
                    out.add(String.valueOf(o));
                }
            }
        }
    }

    static void collectStrings(JsonElement el, List<String> out) {
        if (el != null && el.isJsonArray()) {
            for (JsonElement e : el.getAsJsonArray()) {
                if (e.isJsonPrimitive()) {
                    out.add(e.getAsString());
                }
            }
        }
    }

    static int asInt(Object res) {
        JsonElement el = asJson(res);
        if (el != null && el.isJsonPrimitive()) {
            try {
                return el.getAsInt();
            } catch (RuntimeException ignored) {
                // not a number
            }
        }
        if (res instanceof Number n) {
            return n.intValue();
        }
        return -1;
    }

    /**
     * A server reply that is meant to be a single string — a path, a name.
     *
     * <p>A {@link String} is taken <b>as the value</b> rather than as JSON text. lsp4j only hands back a
     * gson {@link JsonElement} for a reply it could not type; {@code vscode.java.resolveJavaExecutable}
     * answers a bare path, which arrives as a plain String and is not JSON — so routing it through
     * {@link #asJson} threw on every debug launch, logged a WARNING with a stack trace, and then fell back
     * to exactly this value anyway. The result was always right; the noise said otherwise.
     */
    static String asString(Object res) {
        if (res instanceof String s) {
            return s;
        }
        JsonElement el = asJson(res);
        return el != null && el.isJsonPrimitive() ? el.getAsString() : null;
    }

    static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    /** The {@link Map} twin of the above, for a reply lsp4j deserialized to plain collections. */
    static String str(Map<?, ?> m, String key) {
        Object v = m.get(key);
        return v == null ? null : String.valueOf(v);
    }

    /** Bounded rendering of a server reply for a log line — a classpath can be very long. */
    static String abbreviate(String s) {
        if (s == null) {
            return "null";
        }
        return s.length() <= 600 ? s : s.substring(0, 600) + "… (" + s.length() + " chars)";
    }

    /** lsp4j hands untyped executeCommand results back as gson {@link JsonElement}s; pass others through. */
    static JsonElement asJson(Object res) {
        if (res instanceof JsonElement el) {
            return el;
        }
        if (res == null) {
            return null;
        }
        try {
            return com.google.gson.JsonParser.parseString(String.valueOf(res));
        } catch (RuntimeException e) {
            // WARNING, not FINE: every caller turns null into an empty result, which reaches the user as a
            // statement about their project ("no main class was found"). A swallowed parse failure reported
            // as a fact about the workspace is how the resolveMainClass bug survived — say so in the log.
            LOG.log(
                    Level.WARNING,
                    e,
                    () -> "could not parse an executeCommand result of type "
                            + res.getClass().getName() + ": " + abbreviate(String.valueOf(res)));
            return null;
        }
    }
}
