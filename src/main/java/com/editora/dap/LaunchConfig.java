package com.editora.dap;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the argument maps for the DAP {@code launch} and {@code attach} requests understood by the
 * Microsoft java-debug adapter. Pure shaping (unit-tested): the maps are passed straight to
 * {@code DapClient.launch/attach} as the request body. Only the fields the adapter needs are emitted;
 * blank/empty optional fields are omitted so the adapter falls back to its defaults.
 */
public final class LaunchConfig {

    private LaunchConfig() {
    }

    /**
     * A {@code launch} request body. {@code mainClass} is required; {@code projectName}/{@code classPaths}/
     * {@code modulePaths}/{@code javaExec}/{@code cwd}/{@code args} are optional (omitted when blank/empty).
     * Program args are joined into a single string (the adapter accepts a string or array).
     */
    public static Map<String, Object> launch(String mainClass, String projectName, List<String> classPaths,
            List<String> modulePaths, String javaExec, String cwd, List<String> args, boolean stopOnEntry) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "java");
        m.put("name", "Editora (Launch)");
        m.put("request", "launch");
        m.put("mainClass", mainClass == null ? "" : mainClass);
        if (notBlank(projectName)) {
            m.put("projectName", projectName);
        }
        if (notEmpty(classPaths)) {
            m.put("classPaths", classPaths);
        }
        if (notEmpty(modulePaths)) {
            m.put("modulePaths", modulePaths);
        }
        if (notBlank(javaExec)) {
            m.put("javaExec", javaExec);
        }
        if (notBlank(cwd)) {
            m.put("cwd", cwd);
        }
        if (notEmpty(args)) {
            m.put("args", String.join(" ", args));
        }
        m.put("console", "internalConsole");
        m.put("stopOnEntry", stopOnEntry);
        return m;
    }

    /** An {@code attach} request body for a running JVM (JDWP). Blank host defaults to {@code localhost}. */
    public static Map<String, Object> attach(String host, int port) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "java");
        m.put("name", "Editora (Attach)");
        m.put("request", "attach");
        m.put("hostName", notBlank(host) ? host : "localhost");
        m.put("port", port);
        return m;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static boolean notEmpty(List<?> l) {
        return l != null && !l.isEmpty();
    }
}
