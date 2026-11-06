package com.editora.lsp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.editora.process.ProcessRunner;
import org.jspecify.annotations.Nullable;

/**
 * Where a Node.js-based language server finds what it runs with: the tsserver typescript-language-server is told
 * to use, the TypeScript SDK astro-ls loads, the Node.js bin folder an npm server runs on, and how far up a
 * search for project-supplied code may go (only within a trusted folder). Reads PATH and the disk: off the FX
 * thread.
 */
final class NodeServerPaths {

    private NodeServerPaths() {}

    /**
     * The tsserver for a local TypeScript-server root: the folder's own only within its trusted part, else the
     * one beside the installed server; never a folder's own for the shared loose-files root. Off the FX thread
     * (PATH and disk).
     */
    static TypeScriptServer.Choice typeScriptChoice(
            Path root, @Nullable Path trustedCeiling, List<String> command, boolean loose) {
        return typeScriptChoice(root, trustedCeiling, command, loose, null);
    }

    /**
     * {@link #typeScriptChoice(Path, Path, List, boolean)} where Editora's own TypeScript ({@code ownTsserver}, null:
     * not installed) comes before the one beside the server: that one is often a TypeScript 6 or 7 a user installed
     * globally, where Node.js types go missing or there is no tsserver at all (JS review B101).
     */
    static TypeScriptServer.Choice typeScriptChoice(
            Path root, @Nullable Path trustedCeiling, List<String> command, boolean loose, @Nullable Path ownTsserver) {
        Path trusted = trustedCeiling == null || loose ? null : TypeScriptServer.findTsserver(root, trustedCeiling);
        Path any = loose ? null : TypeScriptServer.findTsserver(root, null);
        Path executable = typeScriptServerExecutable(command);
        return TypeScriptServer.choose(
                trusted,
                any,
                ownTsserver != null ? ownTsserver : TypeScriptServer.installedTsserver(executable),
                TypeScriptServer.installedPackageVersion(executable),
                loose);
    }

    /**
     * Where typescript-language-server itself is, links followed: the command's word naming it (a custom command
     * may run it through {@code node} or a wrapper), else the command's program. Null when it cannot be found.
     */
    public static @Nullable Path typeScriptServerExecutable(List<String> command) {
        if (command == null || command.isEmpty()) {
            return null;
        }
        String word = command.get(0);
        for (String w : command) {
            if (w.contains("typescript-language-server")) {
                word = w;
                break;
            }
        }
        List<String> resolved = ProcessRunner.resolveExecutable(List.of(word));
        if (resolved.isEmpty()) {
            return null;
        }
        try {
            Path exe = Path.of(resolved.get(0)).toAbsolutePath().normalize();
            return Files.exists(exe) ? exe.toRealPath() : null;
        } catch (java.io.IOException | java.nio.file.InvalidPathException e) {
            return null;
        }
    }

    /** Whether {@code serverId}'s install is an npm package — a Node.js script (HTML, CSS, JSON, YAML, Bash…). Pure. */
    static boolean nodeScriptServer(String serverId) {
        return com.editora.install.InstallCatalog.npmPackage(serverId) != null;
    }

    /**
     * The bin folder of the Node.js a server under {@code root} runs with, or null: the Settings Node.js, then —
     * only when {@code projectVersionFile} (tsserver, which runs the project's own TypeScript) — the project's
     * {@code .nvmrc}/{@code .node-version}, then PATH. Editora's own npm servers never follow a project's pin: an
     * old pinned Node.js cannot run them (HTML review 2 A103).
     */
    static @Nullable Path nodeBinDir(Path root, String nodeSetting, boolean projectVersionFile) {
        try {
            Path file = projectVersionFile ? root.resolve("package.json") : null;
            return com.editora.run.NodeRuntimes.resolveLocal(file, null, "", nodeSetting)
                    .binDir()
                    .orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * The topmost ancestor of {@code root} (or {@code root} itself) that is still trusted — how far up a
     * search for project-supplied code may go — or {@code null} when {@code root} is not trusted at all.
     */
    static @Nullable Path trustedCeiling(Path root, java.util.function.Predicate<Path> trusted) {
        if (root == null || trusted == null) {
            return null;
        }
        Path dir = root.toAbsolutePath().normalize();
        if (!trusted.test(dir)) {
            return null;
        }
        for (Path parent = dir.getParent(); parent != null && trusted.test(parent); parent = parent.getParent()) {
            dir = parent;
        }
        return dir;
    }

    /**
     * Finds the TypeScript SDK required by astro-ls, which loads and <b>runs</b> the JavaScript in that
     * directory during {@code initialize}. The folder's own TypeScript is therefore taken only from a
     * trusted folder: {@code trustedCeiling} ({@link #trustedCeiling}) is null for an untrusted root, and
     * otherwise bounds the upward walk (npm/pnpm workspace hoisting) so it never leaves the trusted folder.
     * Next comes the SDK beside the resolved astro-ls installation, which is the user's own. A trusted
     * folder with neither gets its deterministic project-local path, which produces Astro's own clear
     * "can't find TypeScript" error; an untrusted one gets {@code null} — the server is not started.
     */
    static @Nullable Path astroTypeScriptSdk(Path root, @Nullable Path trustedCeiling, List<String> command) {
        return astroTypeScriptSdk(root, trustedCeiling, command, null);
    }

    /** As above, with Editora's own TypeScript SDK ({@code ownSdk}, null: none) before the one beside astro-ls (B104). */
    static @Nullable Path astroTypeScriptSdk(
            Path root, @Nullable Path trustedCeiling, List<String> command, @Nullable Path ownSdk) {
        if (trustedCeiling != null) {
            Path projectSdk = findTypeScriptSdk(root, trustedCeiling);
            if (projectSdk != null) {
                return projectSdk;
            }
        }
        if (ownSdk != null) {
            return ownSdk;
        }
        if (command != null && !command.isEmpty()) {
            List<String> resolved = ProcessRunner.resolveExecutable(command);
            if (!resolved.isEmpty()) {
                try {
                    Path executable = Path.of(resolved.get(0)).toAbsolutePath().normalize();
                    if (Files.exists(executable)) {
                        executable = executable.toRealPath();
                    }
                    Path installedSdk = findTypeScriptSdk(executable.getParent(), null);
                    if (installedSdk != null) {
                        return installedSdk;
                    }
                } catch (java.io.IOException | java.nio.file.InvalidPathException ignored) {
                    // Fall through to the trusted project-local path, or to not starting.
                }
            }
        }
        if (trustedCeiling == null) {
            return null;
        }
        Path base = root == null ? Path.of("") : root;
        return base.toAbsolutePath().normalize().resolve("node_modules/typescript/lib");
    }

    /**
     * The first {@code node_modules/typescript/lib} walking up from {@code start}; {@code ceiling}, when
     * given, is the last directory looked at. Public for the trust prompt, which lists what trusting adds.
     */
    public static @Nullable Path findTypeScriptSdk(@Nullable Path start, @Nullable Path ceiling) {
        Path stop = ceiling == null ? null : ceiling.toAbsolutePath().normalize();
        for (Path dir = start == null ? null : start.toAbsolutePath().normalize(); dir != null; dir = dir.getParent()) {
            if (stop != null && !dir.startsWith(stop)) {
                break;
            }
            Path candidate = dir.resolve("node_modules/typescript/lib");
            if (Files.isRegularFile(candidate.resolve("typescript.js"))
                    || Files.isRegularFile(candidate.resolve("tsserverlibrary.js"))) {
                return candidate;
            }
        }
        return null;
    }
}
