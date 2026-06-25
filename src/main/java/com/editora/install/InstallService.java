package com.editora.install;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.stream.Stream;

import javafx.application.Platform;

import com.editora.dap.DebugAdapterLocator;
import com.editora.install.InstallCatalog.Prereq;
import com.editora.install.InstallCatalog.Step;
import com.editora.plugin.PluginRegistry;
import com.editora.plugin.Unzip;
import com.editora.process.ProcessRunner;

/**
 * Runs {@link InstallCatalog} steps off the FX thread (the {@code GitService}/{@code MermaidService} idiom:
 * one daemon executor + {@link Platform#runLater} results), reusing the shared subprocess + download/unzip
 * infrastructure: {@link ProcessRunner} (npm/pip/tar, with the augmented-PATH executable resolution that
 * lets a GUI-launched app find them), {@link PluginRegistry#readCapped} for bounded HTTPS downloads, and the
 * hardened {@link Unzip} for the {@code .vsix} (ZIP) extracts. Tarballs go through the system {@code tar}.
 */
public final class InstallService {

    /** Outcome of an install run: {@code ok} + a (possibly empty) error/detail {@code message}. */
    public record Result(boolean ok, String message) {}

    /** A bounded cap on any single download (vsix/tarball/JSON) so a hostile/oversized response can't OOM. */
    private static final long MAX_DOWNLOAD_BYTES = 200L * 1024 * 1024;

    private static final Duration NET_TIMEOUT = Duration.ofMinutes(3);
    /** npm/pip can take minutes (compiling/native deps); generous but bounded. */
    private static final Duration CMD_TIMEOUT = Duration.ofMinutes(10);

    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "install-service");
        t.setDaemon(true);
        return t;
    });

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** Probes which {@link Prereq}s are present on the augmented PATH, off-thread; posts the set on FX. */
    public void detectPrereqs(Consumer<Set<Prereq>> onResult) {
        exec.submit(() -> {
            Set<Prereq> have = EnumSet.noneOf(Prereq.class);
            if (onPath("npm")) {
                have.add(Prereq.NPM);
            }
            if (onPath("python3") || onPath("python")) {
                have.add(Prereq.PYTHON);
            }
            if (onPath("tar")) {
                have.add(Prereq.TAR);
            }
            Set<Prereq> result = Set.copyOf(have);
            Platform.runLater(() -> onResult.accept(result));
        });
    }

    /**
     * Installs {@code steps} (already filtered to the missing ones) into {@code configDir}, off-thread.
     * {@code onStepStart} is fired (on the FX thread) with each step's id just before it runs, and a final
     * {@link Result} is posted on the FX thread.
     */
    public void install(List<Step> steps, Path configDir, Consumer<String> onStepStart, Consumer<Result> onResult) {
        exec.submit(() -> {
            try {
                for (Step s : steps) {
                    Platform.runLater(() -> onStepStart.accept(s.id()));
                    runStep(s, configDir);
                }
                Platform.runLater(() -> onResult.accept(new Result(true, "")));
            } catch (InstallException e) {
                Platform.runLater(() -> onResult.accept(new Result(false, e.getMessage())));
            } catch (Exception e) {
                String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                Platform.runLater(() -> onResult.accept(new Result(false, msg)));
            }
        });
    }

    /** Stops the worker (window dispose). */
    public void shutdown() {
        exec.shutdownNow();
    }

    // --- step execution ------------------------------------------------------------------------

    private void runStep(Step s, Path configDir) throws Exception {
        switch (s.kind()) {
            case NPM_GLOBAL -> runCommand(InstallCatalog.npmInstallGlobalArgv(s.npmPackages()), s.id());
            case PIP_TARGET -> {
                Path dest = configDir.resolve(s.destSubpath());
                Files.createDirectories(dest);
                String python = onPath("python3") ? "python3" : "python";
                runCommand(InstallCatalog.pipInstallTargetArgv(python, dest, s.pipPackage()), s.id());
            }
            case VSIX -> installVsix(s, configDir);
            case TARBALL -> installTarball(s, configDir);
        }
    }

    private void runCommand(List<String> argv, String id) throws InstallException {
        ProcessRunner.Result r = ProcessRunner.run(null, CMD_TIMEOUT, argv);
        if (!r.ok()) {
            throw new InstallException(id + ": " + (r.message().isBlank() ? "command failed" : r.message()));
        }
    }

    private void installVsix(Step s, Path configDir) throws Exception {
        byte[] vsix = download(resolveDownloadUrl(s));
        Path tmp = Files.createTempDirectory("editora-vsix");
        try {
            Unzip.extract(new ByteArrayInputStream(vsix), tmp);
            Path dest = configDir.resolve(s.destSubpath());
            Files.createDirectories(dest);
            if (s.extractJarOnly()) {
                Path jar = findJavaDebugJar(tmp);
                if (jar == null) {
                    throw new InstallException(s.id() + ": plugin jar not found in the downloaded extension");
                }
                // Replace any previous copy so the newest version wins on auto-detect.
                try (Stream<Path> existing = Files.list(dest)) {
                    existing.filter(p ->
                                    DebugAdapterLocator.matches(p.getFileName().toString()))
                            .forEach(InstallService::deleteQuietly);
                }
                Files.copy(jar, dest.resolve(jar.getFileName().toString()));
            } else {
                copyTree(tmp, dest);
            }
        } finally {
            deleteRecursively(tmp);
        }
    }

    private void installTarball(Step s, Path configDir) throws Exception {
        byte[] tgz = download(resolveDownloadUrl(s));
        Path tmpFile = Files.createTempFile("editora-dl", ".tar.gz");
        Path dest = configDir.resolve(s.destSubpath());
        try {
            Files.write(tmpFile, tgz);
            // Replace any previous copy so a re-install is clean and the newest version wins.
            deleteRecursively(dest);
            Files.createDirectories(dest);
            ProcessRunner.Result r = ProcessRunner.run(null, CMD_TIMEOUT, InstallCatalog.tarExtractArgv(tmpFile, dest));
            if (!r.ok()) {
                throw new InstallException(s.id() + ": tar failed — " + r.message());
            }
            if (!verifyTarball(s, dest)) {
                throw new InstallException(s.id() + ": expected files not found after extraction");
            }
        } finally {
            deleteQuietly(tmpFile);
        }
    }

    /** Resolves a step's download URL: direct ({@code directUrl}), else the asset matched in the API JSON. */
    private String resolveDownloadUrl(Step s) throws Exception {
        if (s.directUrl() != null) {
            return s.directUrl();
        }
        String json = new String(download(s.apiUrl()), StandardCharsets.UTF_8);
        String url = InstallCatalog.firstMatch(json, s.assetPattern());
        if (url == null) {
            throw new InstallException(s.id() + ": no download asset found at " + s.apiUrl());
        }
        return url;
    }

    private byte[] download(String url) throws Exception {
        if (!PluginRegistry.isHttps(url)) {
            throw new InstallException("download url must be https: " + url);
        }
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(NET_TIMEOUT)
                .header("User-Agent", "Editora")
                .GET()
                .build();
        HttpResponse<InputStream> resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() / 100 != 2) {
            resp.body().close();
            throw new InstallException("HTTP " + resp.statusCode() + " for " + url);
        }
        try (InputStream in = resp.body()) {
            return PluginRegistry.readCapped(in, MAX_DOWNLOAD_BYTES);
        }
    }

    // --- helpers -------------------------------------------------------------------------------

    /** True if {@code exe} resolves to an executable on the augmented PATH (rewritten by resolveExecutable). */
    private static boolean onPath(String exe) {
        return !ProcessRunner.resolveExecutable(List.of(exe)).get(0).equals(exe);
    }

    private static Path findJavaDebugJar(Path root) throws IOException {
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> DebugAdapterLocator.matches(p.getFileName().toString()))
                    .max(Comparator.comparing(p -> p.getFileName().toString()))
                    .orElse(null);
        }
    }

    private static boolean verifyTarball(Step s, Path dest) throws IOException {
        String entry = s.verifyEntry();
        if (entry == null) {
            return true;
        }
        if (entry.contains("/")) {
            // A specific relative launcher path, e.g. bin/jdtls (or its Windows .bat variant).
            return Files.exists(dest.resolve(entry)) || Files.exists(dest.resolve(entry + ".bat"));
        }
        // A bare file name to find anywhere beneath dest, e.g. dapDebugServer.js.
        try (Stream<Path> walk = Files.walk(dest)) {
            return walk.anyMatch(p -> p.getFileName().toString().equals(entry));
        }
    }

    private static void copyTree(Path src, Path dest) throws IOException {
        try (Stream<Path> walk = Files.walk(src)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                Path target = dest.resolve(src.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(p, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(InstallService::deleteQuietly);
        } catch (IOException ignored) {
            // best effort — leftovers don't break the install
        }
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // best effort
        }
    }

    /** Internal checked failure carrying a user-facing message. */
    private static final class InstallException extends Exception {
        InstallException(String message) {
            super(message);
        }
    }
}
