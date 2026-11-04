package com.editora.ui;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.editora.dap.DapManager;
import org.jspecify.annotations.Nullable;

/**
 * Debug on a {@code node} or {@code npm} run configuration: the launch is planned the way Run plans it, so Debug
 * and Run start the program the same way. A {@code node} configuration goes through {@link NodeRunLauncher} (the
 * same {@code NodeRunPlan}: interpreter, Node arguments, the shebang, {@code --enable-source-maps}, the
 * environment, the working folder); an npm script runs the project's package manager ({@code npm run <script>})
 * under vscode-js-debug, which attaches to every Node process it starts. Pure helpers here; the caller supplies
 * what is resolved off the FX thread.
 */
final class NodeDebugConfigs {

    private NodeDebugConfigs() {}

    /**
     * The js-debug launch for a planned Node run: {@code argv} split at the script ({@code target}, as the
     * machine running it names it) into the runtime with its arguments, the script, and its arguments. Null when
     * the script is not in {@code argv} (nothing to split at), or there is no folder to run it in.
     */
    static DapManager.@Nullable JsProgram fromRunPlan(
            List<String> argv, String target, Map<String, String> env, @Nullable Path dir) {
        int at = argv == null || target == null ? -1 : programIndex(argv, target);
        if (at < 1 || dir == null) {
            return null;
        }
        target = argv.get(at);
        return new DapManager.JsProgram(
                argv.subList(0, at), target, argv.subList(at + 1, argv.size()), dir, env == null ? Map.of() : env);
    }

    /**
     * The js-debug launch for an npm script: {@code argv} is the package manager's whole command line
     * ({@code npm run start -- …}), launched as js-debug's {@code runtimeExecutable}/{@code runtimeArgs} with no
     * program of its own. {@code npmEnv} (colour, the configuration's variables) is set on top.
     */
    static DapManager.@Nullable JsProgram forPackageManager(List<String> argv, Path dir, Map<String, String> npmEnv) {
        if (argv == null || argv.isEmpty()) {
            return null;
        }
        return new DapManager.JsProgram(argv, "", List.of(), dir, new LinkedHashMap<>(npmEnv));
    }

    /**
     * Where the program is in a planned {@code argv}: {@code target} itself, else — a TypeScript source Run runs as
     * its compiled file — the first later word naming a script with the same name and a JavaScript or TypeScript
     * extension ({@code src/main.ts} → {@code dist/main.js}); -1 when neither is there.
     */
    static int programIndex(List<String> argv, String target) {
        int at = argv.indexOf(target);
        if (at >= 1) {
            return at;
        }
        String stem = stem(target);
        for (int i = 1; i < argv.size(); i++) {
            String word = argv.get(i);
            if (!word.startsWith("-")
                    && SCRIPT.matcher(word).matches()
                    && stem(word).equals(stem)) {
                return i;
            }
        }
        return -1;
    }

    private static final java.util.regex.Pattern SCRIPT =
            java.util.regex.Pattern.compile(".*\\.(?:[cm]?[jt]s|[jt]sx)$", java.util.regex.Pattern.CASE_INSENSITIVE);

    private static String stem(String path) {
        String name = path.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        int dot = name.indexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    /**
     * Whether js-debug can launch {@code runtime}: Node.js itself, or a Node-based runner it reaches through
     * {@code NODE_OPTIONS} (tsx, ts-node). Deno and Bun are not Node.js.
     */
    static boolean debuggableRuntime(List<String> runtime) {
        if (runtime == null || runtime.isEmpty()) {
            return false;
        }
        String exe = runtime.get(0);
        if (com.editora.run.NodeRunPlan.isNodeName(exe)) {
            return true;
        }
        String name = exe.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).toLowerCase(java.util.Locale.ROOT);
        name = name.replaceFirst("\\.(cmd|exe|ps1)$", "");
        return name.equals("tsx") || name.equals("ts-node") || name.equals("ts-node-esm");
    }
}
