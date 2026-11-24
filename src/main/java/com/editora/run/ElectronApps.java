package com.editora.run;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jspecify.annotations.Nullable;

/**
 * Electron apps, for Run File and Debug File: a package whose dependencies name {@code electron}, with Electron
 * installed in its {@code node_modules}. Its main-process files — the package's {@code main} (else {@code index.js}),
 * and any file that loads {@code electron} (a window module, a preload script) — run the app: Electron's own program on
 * the package's folder, as {@code npm start} ({@code electron .}) does, rather than Node.js on the file, which has no
 * {@code app} to start. Debugged, the main process runs under js-debug and the app's windows (its renderer, Chromium)
 * are attached to through Electron's {@code --remote-debugging-port}: VS Code's "Electron: Main" and "Electron:
 * Renderer" configurations, started together. Pure apart from the disk lookups the caller supplies.
 */
public final class ElectronApps {

    private ElectronApps() {}

    /**
     * An Electron app.
     *
     * @param root the package's folder (its {@code package.json}), where Electron runs
     * @param electron Electron's program ({@code node_modules/electron/dist/electron}, {@code Electron.app}'s on macOS)
     */
    public record App(Path root, Path electron) {}

    /** {@code require('electron')}, {@code from 'electron'}, {@code import('electron')}. */
    private static final Pattern LOADS_ELECTRON =
            Pattern.compile("(?:require|import)\\s*\\(\\s*['\"]electron['\"]\\s*\\)|\\bfrom\\s+['\"]electron['\"]");

    /** The extensions of the files Electron's main process can be started from. */
    private static final List<String> MAIN_EXTENSIONS = List.of(".js", ".mjs", ".cjs", ".ts", ".mts", ".cts");

    /**
     * The app {@code file} (text {@code text}) starts, if it is a main-process file of an Electron app: the nearest
     * {@code package.json} above it names {@code electron}, and Electron is installed (its {@code path.txt}, in this
     * package's {@code node_modules} or one above). {@code read} gives a file's text, or null.
     */
    public static Optional<App> of(
            Path file, String text, Predicate<Path> isFile, Function<Path, @Nullable String> read) {
        String name = file.getFileName() == null ? "" : file.getFileName().toString();
        if (MAIN_EXTENSIONS.stream().noneMatch(name::endsWith) || isUnder(file, "node_modules")) {
            return Optional.empty();
        }
        for (Path dir = file.toAbsolutePath().getParent(); dir != null; dir = dir.getParent()) {
            Path manifest = dir.resolve("package.json");
            if (!isFile.test(manifest)) {
                continue;
            }
            String json = read.apply(manifest);
            JsonNode pkg = parse(json);
            if (!dependsOnElectron(pkg)) {
                return Optional.empty(); // the nearest package is not an Electron app
            }
            boolean main = file.toAbsolutePath().normalize().equals(mainOf(dir, pkg));
            if (!main && !LOADS_ELECTRON.matcher(text).find()) {
                return Optional.empty(); // a renderer script, a build script: not the app's start
            }
            Path electron = electron(dir, isFile, read);
            return electron == null ? Optional.empty() : Optional.of(new App(dir, electron));
        }
        return Optional.empty();
    }

    /** Whether package {@code pkg} names {@code electron} among its dependencies. */
    static boolean dependsOnElectron(@Nullable JsonNode pkg) {
        if (pkg == null) {
            return false;
        }
        for (String key : List.of("devDependencies", "dependencies", "optionalDependencies")) {
            JsonNode deps = pkg.get(key);
            if (deps != null && deps.isObject() && deps.has("electron")) {
                return true;
            }
        }
        return false;
    }

    /** The package's main file: its {@code main}, else {@code index.js}, in {@code root}. */
    static Path mainOf(Path root, @Nullable JsonNode pkg) {
        JsonNode field = pkg == null ? null : pkg.get("main");
        String main = field != null && field.isTextual() && !field.asText().isBlank() ? field.asText() : "index.js";
        Path p = root.resolve(main).toAbsolutePath().normalize();
        return main.contains(".") && !main.endsWith("/") ? p : p.resolve("index.js");
    }

    /**
     * Electron's program for an app in {@code root}: {@code node_modules/electron/dist/} and the path its
     * {@code path.txt} names (written by Electron's install), from the nearest {@code node_modules} with it.
     */
    static @Nullable Path electron(Path root, Predicate<Path> isFile, Function<Path, @Nullable String> read) {
        for (Path dir = root; dir != null; dir = dir.getParent()) {
            Path pkg = dir.resolve("node_modules").resolve("electron");
            Path pathTxt = pkg.resolve("path.txt");
            if (isFile.test(pathTxt)) {
                String rel = read.apply(pathTxt);
                if (rel == null || rel.isBlank()) {
                    return null;
                }
                Path program = pkg.resolve("dist").resolve(rel.strip());
                return isFile.test(program) ? program : null;
            }
        }
        return null;
    }

    /** Electron's arguments for app {@code app} with its renderer debuggable on {@code port}, then {@code args}. */
    public static List<String> args(int port, List<String> args) {
        List<String> out = new ArrayList<>();
        if (port > 0) {
            out.add("--remote-debugging-port=" + port);
        }
        out.add(".");
        out.addAll(args);
        return out;
    }

    private static boolean isUnder(Path file, String folder) {
        for (Path p : file) {
            if (p.toString().equals(folder)) {
                return true;
            }
        }
        return false;
    }

    private static @Nullable JsonNode parse(@Nullable String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode n = new ObjectMapper().readTree(json);
            return n != null && n.isObject() ? n : null;
        } catch (Exception e) {
            return null; // a broken package.json is no app
        }
    }
}
