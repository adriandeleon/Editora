package com.editora.http;

import java.nio.file.Path;
import java.util.function.Supplier;

import com.editora.run.NodeRuntimes;
import org.jspecify.annotations.Nullable;

/**
 * Which Node.js runs the HTTP Client's scripts: the HTTP page's own "Node.js for HTTP scripts" when it is set,
 * else the Node.js every other Node feature uses ({@link NodeRuntimes}: the Node.js setting, a {@code .nvmrc} or
 * {@code .node-version} nvm has, then {@code node} on PATH). Before, a blank HTTP field meant "node on PATH"
 * whatever the Node.js setting said, so HTTP scripts and the debugger could run two different Nodes (review E6).
 */
public final class HttpNodeChoice {

    private HttpNodeChoice() {}

    /** Whether the HTTP field names a Node of its own ({@code ""} and a bare {@code node} mean "automatic"). */
    public static boolean explicit(String httpNodePath) {
        if (httpNodePath == null) {
            return false;
        }
        String s = httpNodePath.strip();
        return !s.isEmpty() && !s.equals("node");
    }

    /** The command to run: the explicit HTTP field, else what {@code automatic} resolves. Pure. */
    public static String command(String httpNodePath, Supplier<NodeRuntimes.NodeRuntime> automatic) {
        if (explicit(httpNodePath)) {
            return httpNodePath.strip();
        }
        NodeRuntimes.NodeRuntime runtime = automatic == null ? null : automatic.get();
        return runtime == null ? "node" : runtime.executable();
    }

    /**
     * As {@link #command(String, Supplier)} for a local {@code .http} file in {@code projectRoot}, with the
     * user's Node.js {@code setting}. Touches the disk and the PATH probe: off the FX thread.
     */
    public static String resolveLocal(
            String httpNodePath, String setting, @Nullable Path httpFile, @Nullable Path projectRoot) {
        return command(httpNodePath, () -> NodeRuntimes.resolveLocal(httpFile, projectRoot, "", setting));
    }

    /**
     * The command a settings page shows without touching the disk: the HTTP field, else the Node.js setting, else
     * {@code node}. Pure; the version-file step is left to the run.
     */
    public static String displayed(String httpNodePath, String setting) {
        if (explicit(httpNodePath)) {
            return httpNodePath.strip();
        }
        return setting == null || setting.isBlank() ? "node" : setting.strip();
    }
}
