package com.editora.dap;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.jspecify.annotations.Nullable;

/**
 * A launch.json configuration's {@code serverReadyAction}, as VS Code reads it: a {@code pattern} looked for in the
 * program's output (by default {@code listening on.* (https?://\S+|[0-9]+)}, any case), the address made of what it
 * caught ({@code uriFormat}: {@code http://localhost:%s} for a port, the address itself for a URL), and what is done
 * with it once — opened in the browser, debugged in Chrome or Edge, or another configuration started. Pure, but for
 * {@link #feed}'s state.
 */
public final class ServerReadyAction {

    /** VS Code's default pattern: "listening on port 3000", "Now listening on: https://localhost:5001". */
    static final String PATTERN = "listening on.* (https?://\\S+|[0-9]+)";

    private static final String URI_PORT_FORMAT = "http://localhost:%s";
    private static final String URI_FORMAT = "%s";
    private static final Pattern ANSI =
            Pattern.compile("\u001B\\[[0-?]*[ -/]*[@-~]|\u001B\\][^\u0007\u001B]*(\u0007|\u001B\\\\)");

    /** What is done with the address. */
    public enum Kind {
        /** {@code openExternally}: the address in the user's browser. */
        OPEN_EXTERNALLY,
        /** {@code debugWithChrome}: a Chrome launch on the address. */
        DEBUG_WITH_CHROME,
        /** {@code debugWithEdge}: an Edge launch on the address. */
        DEBUG_WITH_EDGE,
        /** {@code startDebugging}: launch.json's configuration {@link #name}, or the {@link #config} given. */
        START_DEBUGGING
    }

    private final Kind kind;
    private final Pattern pattern;
    private final @Nullable String uriFormat;
    private final String webRoot;
    private final String name;
    private final @Nullable Map<String, Object> config;
    private final boolean killOnServerStop;
    private final StringBuilder partial = new StringBuilder();
    private boolean fired;

    private ServerReadyAction(
            Kind kind,
            Pattern pattern,
            @Nullable String uriFormat,
            String webRoot,
            String name,
            @Nullable Map<String, Object> config,
            boolean killOnServerStop) {
        this.kind = kind;
        this.pattern = pattern;
        this.uriFormat = uriFormat;
        this.webRoot = webRoot;
        this.name = name;
        this.config = config;
        this.killOnServerStop = killOnServerStop;
    }

    /** Thrown for a {@code serverReadyAction} Editora cannot follow; its message says why. */
    public static final class Invalid extends Exception {
        public Invalid(String message) {
            super(message);
        }
    }

    /**
     * The {@code serverReadyAction} of {@code body} (a substituted configuration), with {@code workspace} the default
     * {@code webRoot}; null when it has none.
     */
    public static @Nullable ServerReadyAction of(Map<String, Object> body, String workspace) throws Invalid {
        if (!(body.get("serverReadyAction") instanceof Map<?, ?> raw)) {
            return null;
        }
        Map<String, Object> a = new LinkedHashMap<>();
        raw.forEach((k, v) -> a.put(String.valueOf(k), v));
        String action = text(a.get("action"));
        Kind kind = switch (action) {
            case "", "openExternally" -> Kind.OPEN_EXTERNALLY;
            case "debugWithChrome" -> Kind.DEBUG_WITH_CHROME;
            case "debugWithEdge" -> Kind.DEBUG_WITH_EDGE;
            case "startDebugging" -> Kind.START_DEBUGGING;
            default -> throw new Invalid("action \"" + action + "\"");
        };
        String regex = text(a.get("pattern"));
        Pattern pattern;
        try {
            pattern =
                    Pattern.compile(regex.isEmpty() ? PATTERN : regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        } catch (PatternSyntaxException e) {
            throw new Invalid("pattern \"" + regex + "\": " + e.getDescription());
        }
        String format = a.get("uriFormat") instanceof String f && !f.isEmpty() ? f : null;
        if (format != null && format.split("%s", -1).length > 2) {
            throw new Invalid("uriFormat \"" + format + "\" has more than one %s");
        }
        Map<String, Object> config = null;
        if (a.get("config") instanceof Map<?, ?> c) {
            config = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : c.entrySet()) {
                config.put(String.valueOf(e.getKey()), e.getValue());
            }
        }
        String name = text(a.get("name"));
        if (kind == Kind.START_DEBUGGING && config == null && name.isBlank()) {
            throw new Invalid("startDebugging without a name or config");
        }
        String webRoot = text(a.get("webRoot"));
        return new ServerReadyAction(
                kind,
                pattern,
                format,
                webRoot.isBlank() ? workspace : webRoot,
                name,
                config,
                Boolean.TRUE.equals(a.get("killOnServerStop")));
    }

    public Kind kind() {
        return kind;
    }

    /** {@code webRoot} for a Chrome or Edge launch: the workspace unless it says another. */
    public String webRoot() {
        return webRoot;
    }

    /** The configuration {@code startDebugging} starts by name; blank with a {@link #config}. */
    public String name() {
        return name;
    }

    /** The configuration {@code startDebugging} starts as given, or null to start {@link #name}. */
    public @Nullable Map<String, Object> config() {
        return config;
    }

    /** Whether the session started for the address is stopped when the server's ends. */
    public boolean killOnServerStop() {
        return killOnServerStop;
    }

    /**
     * {@code text}, the program's next output: the address, the first time a line of it (its colours taken out)
     * matches; null otherwise, and ever after. A line is looked at once it is whole, and also while it is not (a
     * server that prints its address without a newline).
     */
    public @Nullable Result feed(String text) {
        if (fired) {
            return null;
        }
        partial.append(text);
        int start = 0;
        for (int nl = partial.indexOf("\n"); nl >= 0; nl = partial.indexOf("\n", start)) {
            Result r = match(partial.substring(start, nl));
            start = nl + 1;
            if (r != null) {
                partial.setLength(0);
                return r;
            }
        }
        partial.delete(0, start);
        if (partial.length() > 8192) { // a line that never ends: its start is kept, as most of it
            partial.setLength(8192);
        }
        Result r = partial.isEmpty() ? null : match(partial.toString());
        if (r != null) {
            partial.setLength(0);
        }
        return r;
    }

    /** What a match came to: the address, or why none could be made of it. */
    public record Result(@Nullable String uri, String problem) {}

    private @Nullable Result match(String line) {
        Matcher m = pattern.matcher(ANSI.matcher(line).replaceAll("").replace("\r", ""));
        if (!m.find()) {
            return null;
        }
        fired = true;
        String caught = m.groupCount() >= 1 && m.group(1) != null ? m.group(1) : "";
        return uri(caught);
    }

    /** The address of what the pattern {@code caught}, as VS Code makes it. */
    Result uri(String caught) {
        if (caught.isEmpty()) {
            String format = uriFormat == null ? "" : uriFormat;
            return format.contains("%s")
                    ? new Result(null, "uriFormat uses %s but the pattern caught nothing")
                    : format.isEmpty()
                            ? new Result(null, "the pattern caught nothing and there is no uriFormat")
                            : new Result(format, "");
        }
        String format = uriFormat != null ? uriFormat : caught.matches("[0-9]+") ? URI_PORT_FORMAT : URI_FORMAT;
        if (!format.contains("%s")) {
            return new Result(format, "");
        }
        return new Result(format.replace("%s", caught), "");
    }

    /** The launch {@code debugWithChrome} or {@code debugWithEdge} starts on {@code uri}. */
    public Map<String, Object> browserLaunch(String uri) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", kind == Kind.DEBUG_WITH_EDGE ? "pwa-msedge" : "pwa-chrome");
        out.put("request", "launch");
        out.put("name", kind == Kind.DEBUG_WITH_EDGE ? "Edge" : "Chrome");
        out.put("url", uri);
        out.put("webRoot", webRoot);
        return out;
    }

    private static String text(@Nullable Object o) {
        return o instanceof String s ? s : "";
    }
}
