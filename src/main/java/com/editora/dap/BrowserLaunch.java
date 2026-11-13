package com.editora.dap;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * Debugging a web page's JavaScript in Chrome, Edge or another Chromium browser, through the same vscode-js-debug that
 * debugs Node.js: what Editora asks js-debug for ({@code pwa-chrome} / {@code pwa-msedge}, launch or attach), and the
 * small decisions around it. Pure (unit-tested).
 *
 * <ul>
 *   <li>A page Editora serves itself (an HTML file, through the live preview's loopback server) is at
 *       {@code http://127.0.0.1:<port>/<token>/…}: {@code pathMapping} maps the token's folder to the site's root,
 *       so a breakpoint in {@code app.js} binds to {@code /<token>/app.js}.</li>
 *   <li>Any other URL (a dev server) maps through {@code webRoot}: the project, as VS Code's default
 *       {@code ${workspaceFolder}}.</li>
 *   <li>{@code userDataDir}: a browser profile of Editora's own, kept between sessions (signed-in pages stay signed
 *       in) and apart from the user's everyday profile, which a running browser holds locked.</li>
 *   <li>{@code runtimeExecutable}: the browser's program; {@code stable} lets js-debug find Chrome or Edge itself.</li>
 * </ul>
 */
public final class BrowserLaunch {

    private BrowserLaunch() {}

    /** js-debug's launch type for Chrome and the Chromium browsers. */
    public static final String CHROME = "pwa-chrome";

    /** js-debug's launch type for Microsoft Edge. */
    public static final String EDGE = "pwa-msedge";

    /** The port Chrome and Edge listen on for a debugger, as VS Code's attach configurations default to. */
    public static final int DEFAULT_PORT = 9222;

    /**
     * How long js-debug gives a browser it starts to be ready for the debugger ({@code timeout}), in milliseconds: as
     * long as Editora waits for the launch to be answered. js-debug's own 10 seconds are too few for a browser's first
     * start on a machine (nothing of it in memory yet), and when they run out it ends the session.
     */
    public static final long LAUNCH_TIMEOUT_MILLIS = DapClient.requestTimeoutSeconds() * 1000;

    /** launch.json types that debug a browser, as VS Code knows them. */
    private static final Set<String> TYPES = Set.of("chrome", CHROME, "msedge", EDGE);

    /**
     * The browsers js-debug can drive (they speak the Chrome DevTools Protocol), by {@link com.editora.web.Browsers}
     * id, in the order one is chosen when the user has not picked one.
     */
    public static final List<String> BROWSER_IDS = List.of("chrome", "edge", "chromium", "brave", "vivaldi");

    /** Whether {@code type} (a launch.json configuration's) is a browser's. */
    public static boolean isBrowserType(@Nullable String type) {
        return type != null && TYPES.contains(type);
    }

    /** js-debug's type for a launch.json {@code type}: {@code chrome} → {@code pwa-chrome}, {@code msedge} → Edge's. */
    public static String adapterType(String type) {
        return "msedge".equals(type) || EDGE.equals(type) ? EDGE : CHROME;
    }

    /** js-debug's type for the browser {@code browserId}, or null for one js-debug cannot drive (Firefox, Safari). */
    public static @Nullable String typeFor(String browserId) {
        if (!BROWSER_IDS.contains(browserId)) {
            return null;
        }
        return "edge".equals(browserId) ? EDGE : CHROME;
    }

    /**
     * The browser to debug in among {@code found} (detected ids): {@code chosen} when it is found and debuggable,
     * else {@code preview} (the HTML preview's browser) when it is, else the first of {@link #BROWSER_IDS} found;
     * null when none is.
     */
    public static @Nullable String pick(List<String> found, @Nullable String chosen, @Nullable String preview) {
        for (String id : java.util.Arrays.asList(chosen, preview)) {
            if (id != null && !id.isBlank() && typeFor(id) != null && found.contains(id)) {
                return id;
            }
        }
        for (String id : BROWSER_IDS) {
            if (found.contains(id)) {
                return id;
            }
        }
        return null;
    }

    /**
     * The {@code launch} request that opens {@code url} in a browser under the debugger.
     *
     * @param type {@link #CHROME} or {@link #EDGE}
     * @param webRoot where the page's files are (the project); null leaves js-debug's default
     * @param served the base of a site Editora serves ({@code http://127.0.0.1:port/token}) and the folder it serves;
     *     null for any other URL
     * @param executable the browser's program; null or blank lets js-debug find the stable release
     * @param userDataDir the browser profile Editora keeps for debugging; null for js-debug's own
     * @param browserArgs more command-line arguments for the browser ({@code --headless=new} in tests)
     * @return the request, with {@link #LAUNCH_TIMEOUT_MILLIS} for the browser to start in
     */
    public static Map<String, Object> launch(
            String type,
            String url,
            @Nullable Path webRoot,
            @Nullable Served served,
            @Nullable String executable,
            @Nullable Path userDataDir,
            List<String> browserArgs) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("name", "Editora (Browser)");
        m.put("request", "launch");
        m.put("url", url);
        Path root = served != null ? served.root() : webRoot;
        if (root != null) {
            m.put("webRoot", root.toString());
        }
        if (served != null) {
            m.put(
                    "pathMapping",
                    Map.of(served.urlPath(), withSlash(served.root().toString())));
        }
        m.put("runtimeExecutable", executable == null || executable.isBlank() ? "stable" : executable);
        if (!browserArgs.isEmpty()) {
            m.put("runtimeArgs", List.copyOf(browserArgs));
        }
        if (userDataDir != null) {
            m.put("userDataDir", userDataDir.toString());
        }
        m.put("timeout", LAUNCH_TIMEOUT_MILLIS);
        return m;
    }

    /**
     * The {@code attach} request to a browser started with {@code --remote-debugging-port}: its pages under the
     * debugger, mapped through {@code webRoot}.
     */
    public static Map<String, Object> attach(String type, String host, int port, @Nullable Path webRoot) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("name", "Editora (Browser)");
        m.put("request", "attach");
        m.put("address", host);
        m.put("port", port);
        if (webRoot != null) {
            m.put("webRoot", webRoot.toString());
        }
        return m;
    }

    /**
     * A launch.json browser configuration ({@code body}, already without VS Code's own fields) as js-debug is sent
     * it: the type js-debug knows, {@code webRoot} defaulting to {@code workspace}, and a launch's profile defaulting
     * to Editora's ({@code userDataDir}) and its {@code timeout} to {@link #LAUNCH_TIMEOUT_MILLIS} — what the
     * configuration says wins.
     */
    public static Map<String, Object> configured(
            Map<String, Object> body, @Nullable Path workspace, @Nullable Path userDataDir) {
        Map<String, Object> m = new LinkedHashMap<>(body);
        m.put("type", adapterType(String.valueOf(body.get("type"))));
        if (workspace != null) {
            m.putIfAbsent("webRoot", workspace.toString());
        }
        boolean attach = "attach".equals(body.get("request"));
        if (!attach) {
            m.putIfAbsent("request", "launch");
            if (userDataDir != null) {
                m.putIfAbsent("userDataDir", userDataDir.toString());
            }
            m.putIfAbsent("timeout", LAUNCH_TIMEOUT_MILLIS);
        } else {
            m.putIfAbsent("port", DEFAULT_PORT);
        }
        return m;
    }

    /** A site Editora serves: its URL's first path segment ({@code /token}) and the folder behind it. */
    public record Served(String urlPath, Path root) {

        /** The site whose page is {@code url} ({@code http://127.0.0.1:port/token/…}), served from {@code root}. */
        public static Optional<Served> of(String url, Path root) {
            try {
                String path = new URI(url).getRawPath();
                if (path == null || path.length() < 2) {
                    return Optional.empty();
                }
                int slash = path.indexOf('/', 1);
                return Optional.of(new Served(withSlash(slash < 0 ? path : path.substring(0, slash)), root));
            } catch (URISyntaxException e) {
                return Optional.empty();
            }
        }
    }

    /**
     * What the user typed as a page to debug, as a URL: {@code localhost:5173} and {@code example.com/app} get
     * {@code http://}; an {@code http}, {@code https} or {@code file} URL is kept. Empty for anything else.
     */
    public static Optional<String> url(@Nullable String input) {
        String s = input == null ? "" : input.strip();
        if (s.isEmpty() || s.chars().anyMatch(Character::isWhitespace)) {
            return Optional.empty();
        }
        String lower = s.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://") && !lower.startsWith("file:")) {
            if (lower.contains("://")) {
                return Optional.empty();
            }
            s = "http://" + s;
        }
        try {
            URI uri = new URI(s);
            boolean file = "file".equalsIgnoreCase(uri.getScheme());
            if (!file && (uri.getHost() == null || uri.getHost().isBlank())) {
                return Optional.empty();
            }
            return Optional.of(s);
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
    }

    /**
     * A short name for the session debugging {@code url}: {@code localhost:5173}, {@code example.com/app}, a file's
     * name; a page Editora serves by its file's name ({@code servedFile}).
     */
    public static String label(String url, @Nullable Path servedFile) {
        if (servedFile != null && servedFile.getFileName() != null) {
            return servedFile.getFileName().toString();
        }
        try {
            URI uri = new URI(url);
            if ("file".equalsIgnoreCase(uri.getScheme())) {
                String path = uri.getPath() == null ? "" : uri.getPath();
                int slash = path.lastIndexOf('/');
                return slash < 0 ? path : path.substring(slash + 1);
            }
            String host = uri.getHost() == null ? "" : uri.getHost();
            String port = uri.getPort() < 0 ? "" : ":" + uri.getPort();
            String path = uri.getPath() == null || "/".equals(uri.getPath()) ? "" : uri.getPath();
            return host + port + path;
        } catch (URISyntaxException e) {
            return url;
        }
    }

    private static String withSlash(String s) {
        return s.endsWith("/") || s.endsWith("\\") ? s : s + (s.contains("\\") && !s.contains("/") ? "\\" : "/");
    }
}
