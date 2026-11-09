package com.editora.dap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;

/**
 * Where a debug adapter Editora can install is found, as CodeLLDB's is: a configured path (the file itself, or a folder
 * holding it), else Editora's own install ({@code <config>/plugins/dap/<folder>}, then {@code ~/.editora} and
 * {@code ~/.editora-dev}), else a VS Code extension that ships it (the newest), else — for a program — the PATH, which
 * {@link DapManager#locateExternal} looks at.
 *
 * @param folder the folder under {@code plugins/dap} Editora's installer unpacks it into
 * @param file the file's name ({@code netcoredbg}; {@code phpDebug.js}); a program gets {@code .exe} on Windows
 * @param program whether it is a program (rather than a script something else runs)
 * @param inFolder where the file sits in an install or a configured folder, first match wins
 * @param extensionPrefixes the VS Code extension folders that ship it ({@code xdebug.php-debug-})
 * @param inExtension where it sits in such an extension
 */
public record InstalledAdapter(
        String folder,
        String file,
        boolean program,
        List<String> inFolder,
        List<String> extensionPrefixes,
        List<String> inExtension) {

    public InstalledAdapter {
        inFolder = List.copyOf(inFolder);
        extensionPrefixes = List.copyOf(extensionPrefixes);
        inExtension = List.copyOf(inExtension);
    }

    /**
     * netcoredbg (Samsung, MIT): its release archive holds {@code netcoredbg/netcoredbg}; the free C# extension for
     * VS Code-compatible editors ({@code muhammad-sammy.csharp}) ships it in {@code .debugger/netcoredbg}.
     */
    public static final InstalledAdapter NETCOREDBG = new InstalledAdapter(
            "csharp",
            "netcoredbg",
            true,
            List.of("netcoredbg/netcoredbg", "netcoredbg"),
            List.of("muhammad-sammy.csharp-"),
            List.of(".debugger/netcoredbg/netcoredbg", ".debugger/netcoredbg"));

    /**
     * vscode-php-debug (Xdebug's adapter, MIT): {@code out/phpDebug.js} in its {@code .vsix}'s {@code extension} folder,
     * run on Node.js; VS Code's extension is {@code xdebug.php-debug}.
     */
    public static final InstalledAdapter PHP_DEBUG = new InstalledAdapter(
            "php",
            "phpDebug.js",
            false,
            List.of("extension/out/phpDebug.js", "out/phpDebug.js", "phpDebug.js"),
            List.of("xdebug.php-debug-"),
            List.of("out/phpDebug.js"));

    /** Where the installer puts it, by this launch's config folder first. Pure construction. */
    List<Path> roots(Path home) {
        List<Path> roots = new ArrayList<>();
        Path configDir = DebugAdapterLocator.editoraConfigDir();
        if (configDir != null) {
            roots.add(configDir.resolve("plugins").resolve("dap").resolve(folder));
        }
        roots.add(home.resolve(".editora").resolve("plugins").resolve("dap").resolve(folder));
        roots.add(home.resolve(".editora-dev").resolve("plugins").resolve("dap").resolve(folder));
        return roots;
    }

    /**
     * The adapter's file: {@code configured} when not blank (the file, or a folder holding it), else Editora's install,
     * else a VS Code extension's. Empty when none — the caller may still look on the PATH. Blocking: it lists folders.
     */
    public Optional<Path> locate(@Nullable String configured, Path home) {
        boolean windows = java.io.File.pathSeparatorChar == ';';
        if (configured != null && !configured.isBlank()) {
            Path p;
            try {
                p = Path.of(configured.strip());
            } catch (java.nio.file.InvalidPathException e) {
                return Optional.empty();
            }
            if (Files.isRegularFile(p)) {
                return Optional.of(p);
            }
            return Files.isDirectory(p) ? in(p, inFolder, windows) : Optional.empty();
        }
        for (Path root : roots(home)) {
            Optional<Path> found = in(root, inFolder, windows);
            if (found.isPresent()) {
                return found;
            }
        }
        List<String> extensions = new ArrayList<>();
        for (Path dir : List.of(
                home.resolve(".vscode").resolve("extensions"),
                home.resolve(".vscode-server").resolve("extensions"),
                home.resolve(".vscode-oss").resolve("extensions"),
                home.resolve(".cursor").resolve("extensions"))) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> s = Files.list(dir)) {
                s.filter(d -> extensionPrefixes.stream()
                                .anyMatch(x -> d.getFileName().toString().startsWith(x)))
                        .filter(d -> in(d, inExtension, windows).isPresent())
                        .forEach(d -> extensions.add(d.toString()));
            } catch (IOException | RuntimeException ignored) {
                // unreadable — skip
            }
        }
        String newest = DebugAdapterLocator.newestByPathVersion(extensions);
        return newest == null ? Optional.empty() : in(Path.of(newest), inExtension, windows);
    }

    /** The file at the first of {@code relative} under {@code dir} that is there (a program gets {@code .exe} on Windows). */
    private Optional<Path> in(Path dir, List<String> relative, boolean windows) {
        for (String rel : relative) {
            Path p = dir.resolve(rel + (program && windows ? ".exe" : ""));
            if (Files.isRegularFile(p)) {
                return Optional.of(p);
            }
        }
        return Optional.empty();
    }
}
