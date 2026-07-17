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

    private LaunchConfig() {}

    /**
     * A {@code launch} request body. {@code mainClass} is required; {@code projectName}/{@code classPaths}/
     * {@code modulePaths}/{@code javaExec}/{@code cwd}/{@code args} are optional (omitted when blank/empty).
     * Program args are passed as an argv array, so an argument containing spaces stays one argument.
     */
    public static Map<String, Object> launch(
            String mainClass,
            String projectName,
            List<String> classPaths,
            List<String> modulePaths,
            String javaExec,
            String cwd,
            List<String> args,
            boolean stopOnEntry) {
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
            // The argv, NOT a joined string: ProgramArgs.tokenize already quote-parsed the user's input
            // into arguments, so re-joining on a space throws that grouping away — `"hello world" second`
            // would reach main() as three arguments while Run passes two. java-debug accepts an array
            // (as does the program(...) sibling below, which always did this correctly).
            m.put("args", args);
        }
        m.put("console", "internalConsole");
        m.put("stopOnEntry", stopOnEntry);
        return m;
    }

    /**
     * A {@code launch} request body for a single-program adapter (debugpy / vscode-js-debug). {@code type}
     * is the adapter's launch type ({@code "python"} or {@code "pwa-node"}); {@code program} is the script
     * to run (required). {@code cwd} + {@code stopOnEntry} are common; for {@code "python"} the
     * {@code runtimeExecutable} (the interpreter) is emitted as {@code "python"}, and for any other type it
     * is emitted as {@code "runtimeExecutable"} (the node binary) — both omitted when blank so the adapter
     * uses its default. Pure shaping (unit-tested).
     */
    public static Map<String, Object> program(
            String type, String program, String cwd, String runtimeExecutable, boolean stopOnEntry) {
        return program(type, program, cwd, runtimeExecutable, List.of(), stopOnEntry);
    }

    /** As {@link #program(String, String, String, String, boolean)} with program {@code args}
     *  (debugpy and js-debug both take an argv array; omitted when empty). */
    public static Map<String, Object> program(
            String type, String program, String cwd, String runtimeExecutable, List<String> args, boolean stopOnEntry) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type == null ? "" : type);
        m.put("name", "Editora (Launch)");
        m.put("request", "launch");
        m.put("program", program == null ? "" : program);
        if (notBlank(cwd)) {
            m.put("cwd", cwd);
        }
        if (notEmpty(args)) {
            m.put("args", args);
        }
        if (notBlank(runtimeExecutable)) {
            if ("python".equals(type)) {
                m.put("python", runtimeExecutable);
            } else {
                m.put("runtimeExecutable", runtimeExecutable);
            }
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
