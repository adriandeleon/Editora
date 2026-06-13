package com.editora.plugin;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import javafx.application.Platform;

/**
 * Installs a plugin from a remote registry entry (download + verify sha-256) or a local {@code .zip},
 * unpacking it into {@code <pluginsDir>/<id>/} through {@link Unzip} (zip-slip + size guards). Off-thread
 * (daemon executor + {@link Platform#runLater}), the {@code GitService}/{@code HttpClientService} idiom.
 * On success it re-runs {@link PluginManager#discover()}; enabling the plugin + persisting is left to the
 * caller (it owns the {@code PluginStore} + UI refresh) via the returned id.
 */
public final class PluginInstaller {

    private static final Logger LOG = Logger.getLogger(PluginInstaller.class.getName());
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(120);
    private static final long MAX_DOWNLOAD_BYTES = 128L * 1024 * 1024;

    /** Outcome of an install: {@code ok} + the installed plugin {@code id} + {@code name}, else an error. */
    public record Result(boolean ok, String id, String name, String error) { }

    private final PluginManager manager;
    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "plugin-installer");
        t.setDaemon(true);
        return t;
    });
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final ObjectMapper mapper = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public PluginInstaller(PluginManager manager) {
        this.manager = manager;
    }

    /** Downloads {@code entry.download}, verifies its sha-256, and installs it; posts a {@link Result} on FX. */
    public void installFromUrl(RegistryEntry entry, Consumer<Result> onResult) {
        exec.submit(() -> {
            Result r = installFromUrlSync(entry);
            Platform.runLater(() -> onResult.accept(r));
        });
    }

    /** Installs from a local {@code .zip} (no checksum — the user picked the file); posts a {@link Result}. */
    public void installFromZip(Path zip, Consumer<Result> onResult) {
        exec.submit(() -> {
            Result r = installFromZipSync(zip);
            Platform.runLater(() -> onResult.accept(r));
        });
    }

    private Result installFromZipSync(Path zip) {
        try {
            byte[] bytes = Files.readAllBytes(zip);
            if (bytes.length > MAX_DOWNLOAD_BYTES) {
                return new Result(false, "", "", "archive too large");
            }
            return installBytes(bytes);
        } catch (IOException e) {
            return new Result(false, "", "", "read failed: " + e.getMessage());
        }
    }

    private Result installFromUrlSync(RegistryEntry e) {
        if (e == null || !PluginRegistry.isHttps(e.download)) {
            return new Result(false, "", "", "download url must be https");
        }
        if (e.sha256 == null || e.sha256.isBlank()) {
            return new Result(false, "", "", "registry entry has no sha256");
        }
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(e.download.strip()))
                    .timeout(REQUEST_TIMEOUT).GET().build();
            HttpResponse<java.io.InputStream> resp =
                    client.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() / 100 != 2) {
                resp.body().close();
                return new Result(false, "", "", "HTTP " + resp.statusCode());
            }
            byte[] body;
            try (java.io.InputStream in = resp.body()) {
                body = PluginRegistry.readCapped(in, MAX_DOWNLOAD_BYTES); // bounded; aborts an oversized stream
            }
            String actual = sha256(body);
            if (!actual.equalsIgnoreCase(e.sha256.strip())) {
                return new Result(false, "", "", "checksum mismatch");
            }
            return installBytes(body);
        } catch (Exception ex) {
            LOG.log(Level.WARNING, "Plugin download failed: " + e.download, ex);
            return new Result(false, "", "", ex.getClass().getSimpleName()
                    + (ex.getMessage() == null ? "" : ": " + ex.getMessage()));
        }
    }

    /** Extracts the zip to a temp dir, finds the plugin root + id, moves it into the plugins dir, rescans. */
    private Result installBytes(byte[] zipBytes) {
        Path temp = null;
        try {
            temp = Files.createTempDirectory("editora-plugin-");
            Unzip.extract(new ByteArrayInputStream(zipBytes), temp);
            Path root = findPluginRoot(temp);
            if (root == null) {
                return new Result(false, "", "", "no plugin.json in archive");
            }
            PluginManifest m;
            try (InputStream in = Files.newInputStream(root.resolve("plugin.json"))) {
                m = PluginManager.parseManifest(mapper, in);
            }
            String id = m.id == null ? "" : m.id.strip();
            if (id.isBlank()) {
                id = root.getFileName() == null ? "" : root.getFileName().toString();
            }
            if (id.isBlank() || !isSafeId(id)) {
                return new Result(false, "", "", "invalid plugin id");
            }
            Path target = manager.pluginsDir().resolve(id);
            Files.createDirectories(manager.pluginsDir());
            deleteRecursively(target); // replace an existing install (update)
            moveDir(root, target);
            manager.discover(); // pick up the new plugin for the Settings list (loads next launch)
            return new Result(true, id, m.name == null || m.name.isBlank() ? id : m.name, null);
        } catch (IOException ex) {
            LOG.log(Level.WARNING, "Plugin install failed", ex);
            return new Result(false, "", "", ex.getMessage());
        } finally {
            if (temp != null) {
                deleteQuietly(temp);
            }
        }
    }

    /** The dir containing {@code plugin.json}: the extract root, else its sole subdirectory. */
    private static Path findPluginRoot(Path extracted) throws IOException {
        if (Files.isRegularFile(extracted.resolve("plugin.json"))) {
            return extracted;
        }
        try (Stream<Path> s = Files.list(extracted)) {
            List<Path> dirs = s.filter(Files::isDirectory).toList();
            if (dirs.size() == 1 && Files.isRegularFile(dirs.get(0).resolve("plugin.json"))) {
                return dirs.get(0);
            }
        }
        return null;
    }

    /** Conservative plugin-id sanity (folder-name safe): letters/digits/dash/dot/underscore, no traversal. */
    static boolean isSafeId(String id) {
        if (id == null || id.isBlank() || id.equals(".") || id.equals("..")) {
            return false;
        }
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.')) {
                return false;
            }
        }
        return !id.contains("..") && id.indexOf('/') < 0 && id.indexOf('\\') < 0;
    }

    /**
     * Compares dotted version strings numerically segment-by-segment (e.g. {@code 1.10 > 1.9}); a
     * non-numeric segment falls back to a case-insensitive string compare. Pure — unit-tested. Returns
     * negative/zero/positive like {@link Comparator}.
     */
    public static int compareVersions(String a, String b) {
        String[] as = (a == null ? "" : a.strip()).split("\\.");
        String[] bs = (b == null ? "" : b.strip()).split("\\.");
        int n = Math.max(as.length, bs.length);
        for (int i = 0; i < n; i++) {
            String x = (i < as.length && !as[i].isBlank()) ? as[i] : "0";
            String y = (i < bs.length && !bs[i].isBlank()) ? bs[i] : "0";
            Integer xi = tryInt(x);
            Integer yi = tryInt(y);
            int cmp;
            if (xi != null && yi != null) {
                cmp = Integer.compare(xi, yi);
            } else {
                cmp = x.toLowerCase(Locale.ROOT).compareTo(y.toLowerCase(Locale.ROOT));
            }
            if (cmp != 0) {
                return cmp < 0 ? -1 : 1;
            }
        }
        return 0;
    }

    private static Integer tryInt(String s) {
        try {
            return Integer.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** SHA-256 of the bytes as lowercase hex (mirrors {@code config.FileIdentity.sha256}). */
    static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static void moveDir(Path src, Path dest) throws IOException {
        try {
            Files.move(src, dest, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFailed) {
            // Cross-filesystem (temp dir on another volume): fall back to a recursive copy.
            copyRecursively(src, dest);
            deleteQuietly(src);
        }
    }

    private static void copyRecursively(Path src, Path dest) throws IOException {
        try (Stream<Path> s = Files.walk(src)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                Path rel = src.relativize(p);
                Path t = dest.resolve(rel.toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(t);
                } else {
                    Files.createDirectories(t.getParent());
                    Files.copy(p, t, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) s.sorted(Comparator.reverseOrder())::iterator) {
                Files.deleteIfExists(p);
            }
        }
    }

    private static void deleteQuietly(Path dir) {
        try {
            deleteRecursively(dir);
        } catch (IOException ignored) {
            // best-effort temp cleanup
        }
    }
}
