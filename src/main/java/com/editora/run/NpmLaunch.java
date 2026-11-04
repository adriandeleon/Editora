package com.editora.run;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;

/**
 * An npm, pnpm, yarn or bun run on the Node.js that Run File would pick ({@link NodeRuntimes}): the package
 * manager's scripts then run {@code node} from the same folder, so {@code npm start} and Run on the same file
 * cannot disagree about the Node version, and a Node.js set only in Settings (not on PATH) still runs npm.
 *
 * <ul>
 *   <li><b>PATH.</b> The chosen Node's folder goes first, so the manager's own {@code #!/usr/bin/env node} and
 *       every {@code node} a script starts find it.</li>
 *   <li><b>Executable.</b> A bare manager name ({@code npm}) becomes the one beside that Node when it is there
 *       (nvm installs npm and npx beside node; {@code corepack enable} puts pnpm and yarn there too).</li>
 *   <li><b>Runtime chip.</b> Probed on that Node, except for Bun (its own runtime).</li>
 *   <li><b>Colour.</b> {@code FORCE_COLOR=1} unless {@code NO_COLOR} is set.</li>
 * </ul>
 *
 * <p>Pure: the caller resolves the Node (off the FX thread) and supplies the file check.
 *
 * @param argv the command line to start
 * @param env variables to set on top of the inherited (augmented) environment
 * @param probe the launch whose {@code --version} the runtime chip shows
 * @param node the Node.js the scripts run on (why it was chosen, a pin nvm lacks), or null when not chosen here
 */
public record NpmLaunch(
        List<String> argv, Map<String, String> env, List<String> probe, NodeRuntimes.@Nullable NodeRuntime node) {

    /**
     * Plans the launch.
     *
     * @param managerArgv the manager and its own leading arguments ({@code [pnpm]}, or a user's npm command)
     *     followed by the task ({@code run test})
     * @param node the Node.js chosen for this package, or null when none could be chosen here (a remote host)
     * @param inheritedPath the PATH the child would otherwise get (Editora's augmented PATH)
     * @param windows whether the run is on Windows ({@code npm.cmd}, {@code node.exe})
     * @param noColor whether {@code NO_COLOR} is set
     * @param isFile whether a path is an existing file
     */
    public static NpmLaunch plan(
            List<String> managerArgv,
            NodeRuntimes.@Nullable NodeRuntime node,
            @Nullable String inheritedPath,
            boolean windows,
            boolean noColor,
            Predicate<Path> isFile) {
        Map<String, String> env = new LinkedHashMap<>();
        if (!noColor) {
            env.put("FORCE_COLOR", "1");
        }
        List<String> argv = new ArrayList<>(managerArgv);
        Optional<Path> bin = node == null ? Optional.empty() : node.binDir();
        String exe = argv.isEmpty() ? "" : argv.get(0);
        String base = MissingProgram.baseName(exe);
        boolean bun = base.equals("bun") || base.equals("bunx");
        if (bin.isPresent()) {
            String path = inheritedPath == null || inheritedPath.isBlank()
                    ? bin.get().toString()
                    : bin.get() + File.pathSeparator + inheritedPath;
            env.put(windows ? "Path" : "PATH", path);
            if (!argv.isEmpty() && isBare(exe)) {
                for (String candidate :
                        windows ? List.of(exe + ".cmd", exe + ".exe", exe + ".bat", exe) : List.of(exe)) {
                    Path beside = bin.get().resolve(candidate);
                    if (isFile.test(beside)) {
                        argv.set(0, beside.toString());
                        break;
                    }
                }
            }
        }
        List<String> probe = bun || node == null ? List.copyOf(argv) : node.command();
        return new NpmLaunch(List.copyOf(argv), Map.copyOf(env), List.copyOf(probe), node);
    }

    /** Whether {@code exe} is a bare program name (looked up on PATH), not a path. */
    static boolean isBare(String exe) {
        return !exe.isEmpty()
                && exe.indexOf('/') < 0
                && exe.indexOf('\\') < 0
                && !exe.toLowerCase(Locale.ROOT).matches("[a-z]:.*");
    }
}
