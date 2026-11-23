package com.editora.dap;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * The requests vscode-firefox-debug ({@link DebugLanguages#FIREFOX}) is sent: a page opened in Firefox under the
 * debugger, Firefox attached to, and a launch.json {@code firefox} configuration completed. Its options differ from
 * js-debug's ({@link BrowserLaunch}): {@code file} for a page on disk, {@code pathMappings} with whole URLs, {@code
 * firefoxExecutable}, {@code profileDir}. Probed with vscode-firefox-debug 2.15.0 and Firefox 160: a page's script stops
 * at a breakpoint, by {@code file:} and through a served site's mapping; the profile folder is made and kept; Stop
 * leaves no Firefox. Pure.
 */
public final class FirefoxLaunch {

    private FirefoxLaunch() {}

    /** vscode-firefox-debug's configuration type, and the browser's id in {@link com.editora.web.Browsers}. */
    public static final String TYPE = "firefox";

    /** The port Firefox's debugger server is usually started on ({@code -start-debugger-server 6000}). */
    public static final int DEFAULT_PORT = 6000;

    /**
     * The {@code launch} request that opens {@code url} in Firefox under the debugger.
     *
     * @param webRoot where the page's files are (the project); null for none
     * @param served the base of a site Editora serves ({@code /token/}) and the folder it serves; null for another URL
     * @param executable Firefox's program; null or blank lets the adapter find it
     * @param profileDir the Firefox profile Editora keeps for debugging, made on first use and kept; null for a
     *     temporary one
     * @param firefoxArgs more command-line arguments for Firefox ({@code -headless} in tests)
     */
    public static Map<String, Object> launch(
            String url,
            @Nullable Path webRoot,
            BrowserLaunch.@Nullable Served served,
            @Nullable String executable,
            @Nullable Path profileDir,
            List<String> firefoxArgs) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", TYPE);
        m.put("name", "Editora (Firefox)");
        m.put("request", "launch");
        Path file = fileOf(url);
        if (file != null) {
            m.put("file", file.toString()); // a page on disk: the adapter maps its file: URLs itself
        } else {
            m.put("url", url);
        }
        Path root = served != null ? served.root() : webRoot;
        if (root != null && file == null) {
            m.put("webRoot", root.toString());
        }
        String base = served == null ? null : origin(url) + served.urlPath();
        if (base != null && served != null) {
            m.put(
                    "pathMappings",
                    List.of(Map.of("url", base, "path", withSlash(served.root().toString()))));
        }
        if (executable != null && !executable.isBlank()) {
            m.put("firefoxExecutable", executable);
        }
        if (profileDir != null) {
            m.put("profileDir", profileDir.toString());
            m.put("keepProfileChanges", true); // sign-ins and settings stay, as Chrome's debug profile keeps them
        }
        if (!firefoxArgs.isEmpty()) {
            m.put("firefoxArgs", List.copyOf(firefoxArgs));
        }
        return m;
    }

    /**
     * The {@code attach} request to a Firefox started with {@code -start-debugger-server port} (and remote debugging
     * allowed in its settings): its tabs under the debugger, mapped through {@code webRoot}.
     */
    public static Map<String, Object> attach(String host, int port, @Nullable Path webRoot) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", TYPE);
        m.put("name", "Editora (Firefox)");
        m.put("request", "attach");
        m.put("host", host);
        m.put("port", port);
        if (webRoot != null) {
            m.put("webRoot", webRoot.toString());
        }
        return m;
    }

    /**
     * A launch.json {@code firefox} configuration ({@code body}, without VS Code's own fields) as the adapter is sent
     * it: {@code webRoot} defaulting to {@code workspace} where a {@code url} is opened or attached to (the adapter
     * refuses a {@code webRoot} without one: a {@code file} maps itself), a launch's profile to Editora's ({@code profileDir}, kept),
     * an attach's port to {@link #DEFAULT_PORT} — what the configuration says wins.
     */
    public static Map<String, Object> configured(
            Map<String, Object> body, @Nullable Path workspace, @Nullable Path profileDir) {
        Map<String, Object> m = new LinkedHashMap<>(body);
        m.put("type", TYPE);
        boolean attach = "attach".equals(body.get("request"));
        if (workspace != null && (attach || body.containsKey("url"))) {
            m.putIfAbsent("webRoot", workspace.toString());
        }
        if (attach) {
            m.putIfAbsent("port", DEFAULT_PORT);
        } else {
            m.putIfAbsent("request", "launch");
            if (profileDir != null && !body.containsKey("profile")) {
                m.putIfAbsent("profileDir", profileDir.toString());
                m.putIfAbsent("keepProfileChanges", true);
            }
        }
        return m;
    }

    /** The file a {@code file:} URL names, or null for any other URL. */
    static @Nullable Path fileOf(String url) {
        try {
            URI uri = new URI(url);
            return "file".equalsIgnoreCase(uri.getScheme()) ? Path.of(uri) : null;
        } catch (URISyntaxException | IllegalArgumentException | java.nio.file.FileSystemNotFoundException e) {
            return null;
        }
    }

    /** {@code http://host:port} of {@code url}. */
    private static String origin(String url) {
        try {
            URI uri = new URI(url);
            return uri.getScheme() + "://" + uri.getRawAuthority();
        } catch (URISyntaxException e) {
            return url;
        }
    }

    private static String withSlash(String s) {
        return s.endsWith("/") || s.endsWith("\\") ? s : s + (s.contains("\\") && !s.contains("/") ? "\\" : "/");
    }
}
