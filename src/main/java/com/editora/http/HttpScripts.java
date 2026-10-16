package com.editora.http;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The pure half of running a response handler on Node.js ({@link NodeResponseScripts} launches it): the JSON object
 * the shim ({@code http-handler-shim.js}) reads on stdin, the result line it writes back, the command-line flags
 * that switch on Node's permission model, and the minimal environment Node is started with. Unit-tested.
 *
 * <p><b>The environment</b> (adl, 2026-10-15): Node gets only what it needs to start — {@code PATH}, the locale and
 * time zone, and on Windows the system folders — never the editor's own environment, so a script cannot read a
 * token from {@code process.env} around the secrets gate, and a {@code NODE_OPTIONS} cannot load code into it.
 *
 * <p><b>The permission model</b>: Node 22.13 and later take {@code --permission}, Node 20 to 22.12
 * {@code --experimental-permission}; with it, the script may read only the shim file, and may not write files, start
 * processes or workers, or load addons. Older Node runs the script without it. Network access is not restricted
 * (adl, 2026-10-15): a trusted script may make requests of its own.
 */
public final class HttpScripts {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The most tests and log lines read back from one handler (the shim caps them too). */
    static final int MAX_ENTRIES = 1000;

    /** The longest message, log line or error read back. */
    static final int MAX_TEXT = 10_000;

    private static final Pattern VERSION = Pattern.compile("v?(\\d+)\\.(\\d+)\\.(\\d+)");

    /** Environment variables Node may keep: what it needs to start and to format dates and text. */
    private static final List<String> KEPT = List.of("PATH", "LANG", "LC_ALL", "LC_CTYPE", "TZ");

    /** What Windows processes need to start (and Node's {@code os.tmpdir()}). */
    private static final List<String> KEPT_WINDOWS =
            List.of("SystemRoot", "SystemDrive", "windir", "TEMP", "TMP", "PATHEXT", "ComSpec");

    private HttpScripts() {}

    /** How Node is guarded. */
    public enum Guard {
        /** {@code --permission} (Node 22.13+). */
        PERMISSION,
        /** {@code --experimental-permission} (Node 20 to 22.12). */
        EXPERIMENTAL_PERMISSION,
        /** No permission model (Node before 20, or an unreadable version). */
        NONE
    }

    /** {@code {major, minor, patch}} of a {@code node --version} line ({@code v24.18.1}), or null. */
    public static int[] parseVersion(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = VERSION.matcher(text.strip());
        if (!m.find()) {
            return null;
        }
        try {
            return new int[] {Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))};
        } catch (NumberFormatException tooLong) {
            return null;
        }
    }

    /** The guard Node {@code version} supports. */
    public static Guard guardFor(int[] version) {
        if (version == null || version.length < 2) {
            return Guard.NONE;
        }
        int major = version[0];
        int minor = version[1];
        if (major > 22 || (major == 22 && minor >= 13)) {
            return Guard.PERMISSION;
        }
        return major >= 20 ? Guard.EXPERIMENTAL_PERMISSION : Guard.NONE;
    }

    /** The flags that switch {@code guard} on, letting the script read only {@code shim}. */
    public static List<String> flags(Guard guard, Path shim) {
        String allowRead = "--allow-fs-read=" + shim.toAbsolutePath();
        return switch (guard) {
            case PERMISSION -> List.of("--permission", allowRead);
            case EXPERIMENTAL_PERMISSION -> List.of("--experimental-permission", allowRead);
            case NONE -> List.of();
        };
    }

    /**
     * The flags that make Node wait for a debugger on a free 127.0.0.1 port: {@code --inspect-brk}, and under the
     * permission model {@code --allow-inspector}, without which Node ignores it (adl, 2026-10-16: Debug Request only).
     */
    public static List<String> debugFlags(Guard guard) {
        return guard == Guard.NONE
                ? List.of("--inspect-brk=127.0.0.1:0")
                : List.of("--allow-inspector", "--inspect-brk=127.0.0.1:0");
    }

    /** The environment Node is started with: {@code inherited} reduced to {@link #KEPT}, with {@code path} as PATH. */
    public static Map<String, String> environment(Map<String, String> inherited, String path, boolean windows) {
        Map<String, String> env = new LinkedHashMap<>();
        List<String> kept = new ArrayList<>(KEPT);
        if (windows) {
            kept.addAll(KEPT_WINDOWS);
        }
        if (inherited != null) {
            for (Map.Entry<String, String> e : inherited.entrySet()) {
                for (String k : kept) {
                    // Windows names are case-insensitive ("Path", "SYSTEMROOT").
                    if (windows ? k.equalsIgnoreCase(e.getKey()) : k.equals(e.getKey())) {
                        env.put(e.getKey(), e.getValue());
                    }
                }
            }
        }
        if (path != null && !path.isBlank()) {
            env.keySet().removeIf(k -> k.equalsIgnoreCase("PATH"));
            env.put(windows ? "Path" : "PATH", path);
        }
        return env;
    }

    /** One handler run's script: its source, the file stack traces name, and where the source starts in it. */
    public record Script(String source, String filename, int line, int column) {}

    /**
     * The shim's input for one handler: the {@code nonce} that marks its result line, the {@code script}, the response
     * of {@code exchange}, the request as sent (its values with {@code secrets} masked) and as written in
     * {@code request}, the environment's variables, the request's {@code @name = value} lines as written, and the
     * session variables.
     */
    public static String input(
            String nonce,
            Script script,
            HttpFile.Parsed request,
            HttpExchange exchange,
            Map<String, String> environment,
            Map<String, String> globals,
            List<String> secrets) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("nonce", nonce);
        ObjectNode s = root.putObject("script");
        s.put("source", script.source());
        s.put("filename", script.filename());
        s.put("line", script.line());
        s.put("column", script.column());

        HttpResult r = exchange.result();
        ObjectNode res = root.putObject("response");
        res.put("status", r.status());
        pairs(res.putArray("headers"), r.headers(), List.of());
        res.put("body", r.binary() ? "" : nullToEmpty(r.body()));
        ObjectNode ct = res.putObject("contentType");
        ct.put("mimeType", mimeType(r.contentType()));
        ct.put("charset", r.bodyCharset().toLowerCase(Locale.ROOT));

        ObjectNode req = root.putObject("request");
        req.put("method", exchange.method());
        req.put("url", SecretMask.mask(nullToEmpty(exchange.url()), secrets));
        req.put("urlRaw", request == null ? "" : nullToEmpty(request.url()));
        pairs(req.putArray("headers"), exchange.headers(), secrets);
        pairs(req.putArray("headersRaw"), request == null ? List.of() : request.headers(), List.of());
        req.put("body", SecretMask.mask(nullToEmpty(exchange.requestBody()), secrets));
        req.put("bodyRaw", request == null ? "" : nullToEmpty(request.body()));

        ObjectNode env = root.putObject("environment");
        if (environment != null) {
            environment.forEach(env::put);
        }
        ObjectNode vars = root.putObject("variables");
        if (request != null) {
            for (String[] v : request.variables()) {
                if (v != null && v.length >= 2) {
                    vars.put(v[0], v[1]); // a later declaration wins, as in the request
                }
            }
        }
        ObjectNode g = root.putObject("globals");
        if (globals != null) {
            globals.forEach(g::put);
        }
        return root.toString();
    }

    /**
     * The shim's input for one pre-request script ({@code "mode": "pre"}, no response): the request as far as it
     * resolves before the script ({@code url}, {@code headers}, {@code body}, every secret source kept as written) and
     * as written in {@code request}, its {@code @name = value} lines with the {@code variablesSoFar} an earlier
     * pre-request script set over them, the environment's variables and the session variables.
     */
    public static String preInput(
            String nonce,
            Script script,
            HttpFile.Parsed request,
            ResponseScripts.PreInput in,
            Map<String, String> variablesSoFar,
            Map<String, String> globals) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("nonce", nonce);
        root.put("mode", "pre");
        ObjectNode s = root.putObject("script");
        s.put("source", script.source());
        s.put("filename", script.filename());
        s.put("line", script.line());
        s.put("column", script.column());
        ObjectNode req = root.putObject("request");
        req.put("method", request.method());
        req.put("url", nullToEmpty(in.url()));
        req.put("urlRaw", nullToEmpty(request.url()));
        pairs(req.putArray("headers"), in.headers(), List.of());
        pairs(req.putArray("headersRaw"), request.headers(), List.of());
        req.put("body", nullToEmpty(in.body()));
        req.put("bodyRaw", nullToEmpty(request.body()));
        ObjectNode env = root.putObject("environment");
        in.environment().forEach(env::put);
        ObjectNode vars = root.putObject("variables");
        for (String[] v : request.variables()) {
            if (v != null && v.length >= 2) {
                vars.put(v[0], v[1]);
            }
        }
        if (variablesSoFar != null) {
            variablesSoFar.forEach(vars::put);
        }
        ObjectNode g = root.putObject("globals");
        if (globals != null) {
            globals.forEach(g::put);
        }
        return root.toString();
    }

    private static void pairs(ArrayNode into, List<String[]> pairs, List<String> secrets) {
        if (pairs == null) {
            return;
        }
        for (String[] p : pairs) {
            if (p != null && p.length >= 2) {
                ArrayNode pair = into.addArray();
                pair.add(nullToEmpty(p[0]));
                pair.add(SecretMask.mask(nullToEmpty(p[1]), secrets));
            }
        }
    }

    /** The media type of a Content-Type, without its parameters ({@code application/json}). */
    static String mimeType(String contentType) {
        if (contentType == null) {
            return "";
        }
        int semi = contentType.indexOf(';');
        return (semi < 0 ? contentType : contentType.substring(0, semi)).strip();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /**
     * What one script reported: its tests, log lines, session changes, the {@code request.variables} a pre-request
     * script set, and the error it stopped with.
     */
    public record Output(
            List<HttpTests.Test> tests,
            List<String> log,
            List<ResponseScripts.GlobalChange> changes,
            Map<String, String> variables,
            String error) {}

    /**
     * The result line marked {@code nonce} in {@code stdout} (the last one, should a script print the marker itself),
     * or null when there is none or it is not a result.
     */
    public static Output parseOutput(String stdout, String nonce) {
        if (stdout == null || nonce == null || nonce.isEmpty()) {
            return null;
        }
        int at = stdout.lastIndexOf(nonce);
        if (at < 0) {
            return null;
        }
        int end = stdout.indexOf('\n', at);
        String json = stdout.substring(at + nonce.length(), end < 0 ? stdout.length() : end);
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (Exception unreadable) {
            return null;
        }
        if (root == null || !root.isObject()) {
            return null;
        }
        List<HttpTests.Test> tests = new ArrayList<>();
        for (JsonNode t : root.path("tests")) {
            if (tests.size() >= MAX_ENTRIES) {
                break;
            }
            tests.add(new HttpTests.Test(
                    cut(t.path("name").asText("")),
                    t.path("passed").asBoolean(false),
                    cut(t.path("message").asText(""))));
        }
        List<String> log = new ArrayList<>();
        for (JsonNode l : root.path("log")) {
            if (log.size() >= MAX_ENTRIES) {
                break;
            }
            log.add(cut(l.asText("")));
        }
        List<ResponseScripts.GlobalChange> changes = new ArrayList<>();
        for (JsonNode c : root.path("globals")) {
            String kind = c.path(0).asText("");
            switch (kind) {
                case ResponseScripts.GlobalChange.SET -> {
                    String name = c.path(1).asText("");
                    if (!name.isEmpty()) {
                        changes.add(new ResponseScripts.GlobalChange(
                                kind, name, c.path(2).asText("")));
                    }
                }
                case ResponseScripts.GlobalChange.HEADER_SET -> {
                    String name = c.path(1).asText("");
                    if (!name.isEmpty()) {
                        changes.add(new ResponseScripts.GlobalChange(
                                kind, name, c.path(2).asText("")));
                    }
                }
                case ResponseScripts.GlobalChange.HEADER_CLEAR -> {
                    String name = c.path(1).asText("");
                    if (!name.isEmpty()) {
                        changes.add(new ResponseScripts.GlobalChange(kind, name, null));
                    }
                }
                case ResponseScripts.GlobalChange.CLEAR -> {
                    String name = c.path(1).asText("");
                    if (!name.isEmpty()) {
                        changes.add(new ResponseScripts.GlobalChange(kind, name, null));
                    }
                }
                case ResponseScripts.GlobalChange.CLEAR_ALL ->
                    changes.add(new ResponseScripts.GlobalChange(kind, null, null));
                default -> {}
            }
        }
        Map<String, String> variables = new LinkedHashMap<>();
        root.path("variables").properties().forEach(v -> {
            if (!v.getKey().isEmpty() && variables.size() < MAX_ENTRIES) {
                variables.put(v.getKey(), v.getValue().asText(""));
            }
        });
        JsonNode e = root.path("error");
        String error = e.isTextual() && !e.asText().isBlank() ? cut(e.asText()) : null;
        return new Output(tests, log, changes, variables, error);
    }

    private static String cut(String s) {
        return s.length() > MAX_TEXT ? s.substring(0, MAX_TEXT) + "…" : s;
    }
}
