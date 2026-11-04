package com.editora.dap;

import java.util.List;
import java.util.function.Function;

import com.editora.run.NodeRuntimes;
import org.jspecify.annotations.Nullable;

/**
 * Which Node.js runs the vscode-js-debug server itself (review D101).
 *
 * <p>The program runs on the Node.js the user chose (a run configuration, the Node.js setting, a project's
 * {@code .nvmrc}). js-debug needs Node.js {@link JsLaunch#MIN_ADAPTER_NODE_MAJOR} or newer. When the chosen one is
 * older — a legacy project pinned to Node 16 in Settings — the server runs on another Node.js, found
 * <em>without</em> the setting: the newest nvm-installed one that is new enough, else {@code node} on PATH
 * ({@link NodeRuntimes#resolveForTool(int)}). The
 * program still runs on the old one; js-debug debugs it through the inspector.
 */
public final class JsAdapterNode {

    private JsAdapterNode() {}

    /**
     * The Node.js to run js-debug: {@code preferred} when its {@code version} runs the server (or is unknown), else
     * the first of {@code fallbacks} (other than {@code preferred}) whose version, asked with {@code versionOf}, does;
     * null when none does.
     */
    public static @Nullable String choose(
            String preferred,
            @Nullable String version,
            List<String> fallbacks,
            Function<String, @Nullable String> versionOf) {
        if (JsLaunch.runsAdapter(version)) {
            return preferred;
        }
        for (String candidate : fallbacks) {
            if (candidate == null || candidate.isBlank() || candidate.equals(preferred)) {
                continue;
            }
            String v = versionOf.apply(candidate);
            if (v != null && JsLaunch.runsAdapter(v)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * The Node.js to try when the chosen one is too old: the newest nvm Node.js new enough to run js-debug, else
     * {@code node} on PATH — both found without the Node.js setting or a {@code .nvmrc}
     * ({@link NodeRuntimes#resolveForTool(int)}). Off the FX thread.
     */
    public static List<String> localFallbacks() {
        return List.of(
                NodeRuntimes.resolveForTool(JsLaunch.MIN_ADAPTER_NODE_MAJOR).executable());
    }
}
