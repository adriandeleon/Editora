package com.editora.plugin;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

import javafx.application.Platform;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

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
    public record Result(boolean ok, String id, String name, String error) {}

    private final PluginManager manager;
    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "plugin-installer");
        t.setDaemon(true);
        return t;
    });
    /** Built by the first download, not with the installer (constructed for every window at startup). */
    private final com.editora.io.LazyHttpClient client = com.editora.io.LazyHttpClient.following(CONNECT_TIMEOUT);

    private final ObjectMapper mapper = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public PluginInstaller(PluginManager manager) {
        this.manager = manager;
    }

    /** Stops the worker and releases the HTTP client (window dispose). */
    public void shutdown() {
        exec.shutdownNow();
        client.close();
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
                    .timeout(REQUEST_TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<java.io.InputStream> resp = client.get().send(req, HttpResponse.BodyHandlers.ofInputStream());
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
            return new Result(
                    false,
                    "",
                    "",
                    ex.getClass().getSimpleName() + (ex.getMessage() == null ? "" : ": " + ex.getMessage()));
        }
    }

    /**
     * Extracts the zip to a temp dir, finds the plugin root + id, puts it in the plugins dir, rescans.
     *
     * <p>An update keeps what the old install owned. "Replace an existing install" used to be a recursive
     * delete of {@code plugins/<id>/} followed by a move, which wiped the plugin's {@code data/} directory
     * (the writable store {@code PluginContext.dataDir()} hands out) on every update, and left nothing at all
     * if the move then failed. Now the new version is brought next to the old one first, the two folders are
     * swapped by rename, {@code data/} is carried over, and the old folder is deleted last; a failure in
     * between puts the old install back.
     */
    Result installBytes(byte[] zipBytes) {
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
            if (isReservedId(id)) {
                return new Result(false, "", "", "plugin id \"" + id + "\" is reserved");
            }
            Path pluginsDir = manager.pluginsDir();
            Path target = pluginsDir.resolve(id);
            Files.createDirectories(pluginsDir);
            boolean update = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
            if (update && !Files.isRegularFile(target.resolve("plugin.json"))) {
                // Whatever this is, it is not an install of this plugin: never delete it to make room.
                return new Result(false, "", "", "a folder named \"" + id + "\" that is not a plugin is in the way");
            }
            Path incoming = Files.createTempDirectory(pluginsDir, STAGING_PREFIX + id + ".new-");
            Path previous = null;
            boolean previousIsSpare = false; // true once the new install is complete and holds the old data
            try {
                Files.delete(incoming); // moveDir needs the name free; the unique name stays ours
                moveDir(root, incoming); // the slow, fallible part (a cross-volume copy) happens beside the old one
                if (update) {
                    Path aside = Files.createTempDirectory(pluginsDir, STAGING_PREFIX + id + ".old-");
                    Files.delete(aside);
                    Files.move(target, aside);
                    previous = aside;
                }
                try {
                    Files.move(incoming, target);
                    if (previous != null) {
                        carryOverData(previous, target);
                    }
                    previousIsSpare = true;
                } catch (IOException | RuntimeException failure) {
                    if (previous != null) {
                        // Put the old install back, data and all. If even that fails it stays where it is,
                        // under its staging name, rather than being deleted.
                        deleteQuietly(target);
                        Files.move(previous, target);
                    }
                    throw failure;
                }
            } finally {
                deleteQuietly(incoming);
                if (previous != null && previousIsSpare) {
                    deleteQuietly(previous);
                }
            }
            manager.discover(); // pick up the new plugin for the Settings list (loads next launch)
            return new Result(true, id, m.name == null || m.name.isBlank() ? id : m.name, null);
        } catch (IOException | RuntimeException ex) {
            LOG.log(Level.WARNING, "Plugin install failed", ex);
            return new Result(false, "", "", ex.getMessage());
        } finally {
            if (temp != null) {
                deleteQuietly(temp);
            }
        }
    }

    /** Prefix of the folders an install stages beside the plugins; {@link PluginManager#discover} skips them. */
    static final String STAGING_PREFIX = ".installing-";

    /** The per-plugin writable store ({@code PluginContext.dataDir()}), preserved across updates. */
    static final String DATA_DIR = "data";

    /**
     * Moves the old install's {@code data/} into the new one. Files the new archive ships under
     * {@code data/} are defaults: where the user already has a file of that name, the user's file wins.
     */
    private static void carryOverData(Path previous, Path target) throws IOException {
        Path oldData = previous.resolve(DATA_DIR);
        if (!Files.isDirectory(oldData, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Path newData = target.resolve(DATA_DIR);
        if (!Files.exists(newData, LinkOption.NOFOLLOW_LINKS)) {
            Files.move(oldData, newData);
            return;
        }
        copyRecursively(oldData, newData);
    }

    /**
     * Folder names under {@code <config>/plugins} that belong to Editora itself: the in-app installer puts
     * downloaded language servers, debug adapters and the Typst CLI there ({@code plugins/lsp},
     * {@code plugins/dap}, {@code plugins/typst}). A plugin with one of these ids would be installed over
     * — and removed together with — every one of those tools.
     */
    private static final Set<String> RESERVED_IDS = Set.of("lsp", "dap", "typst");

    /** Whether {@code id} names one of the built-in tool folders (compared without case: some volumes fold it). */
    public static boolean isReservedId(String id) {
        return id != null && RESERVED_IDS.contains(id.strip().toLowerCase(Locale.ROOT));
    }

    /**
     * The folder that removing {@code plugin} may delete: its own install directory, and only when that is a
     * real folder directly inside {@code pluginsDir} and not one of the built-in tool folders. Null otherwise.
     *
     * <p>Removal used to delete {@code pluginsDir.resolve(manifest id)}. The id comes from a file anyone can
     * write: {@code ".."} resolved to the whole Editora config directory, an absolute path to that path, and
     * another plugin's name to that plugin.
     */
    public static Path removableDir(Path pluginsDir, PluginDescriptor plugin) {
        if (pluginsDir == null || plugin == null || plugin.dir() == null) {
            return null;
        }
        Path root = pluginsDir.toAbsolutePath().normalize();
        Path dir = plugin.dir().toAbsolutePath().normalize();
        if (!root.equals(dir.getParent()) || dir.getFileName() == null) {
            return null;
        }
        String name = dir.getFileName().toString();
        if (isReservedId(name) || name.startsWith(".")) {
            return null;
        }
        return Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS) ? dir : null;
    }

    /**
     * Deletes an installed plugin's folder — the one {@link #removableDir} approves, nothing else.
     *
     * @return false when the plugin has no removable folder, or part of it could not be deleted
     */
    public static boolean deleteInstalled(Path pluginsDir, PluginDescriptor plugin) {
        Path dir = removableDir(pluginsDir, plugin);
        if (dir == null) {
            return false;
        }
        deleteQuietly(dir);
        return !Files.exists(dir, LinkOption.NOFOLLOW_LINKS);
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

    /**
     * Conservative plugin-id sanity (folder-name safe): letters/digits/dash/dot/underscore, no traversal, and
     * no leading dot (a dot-folder is not listed as a plugin, so it could never be removed again).
     */
    static boolean isSafeId(String id) {
        if (id == null || id.isBlank() || id.startsWith(".")) {
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
     *
     * <p>A semver <b>pre-release</b> suffix ({@code 1.0.0-rc1}) ranks <i>below</i> the same numeric core
     * ({@code 1.0.0}), per the spec. Comparing the raw last segments instead made {@code "0"} sort before
     * {@code "0-rc1"} — i.e. the release looked *older* than its own release candidate, so a user running an
     * {@code -rcN} build (which the release pipeline publishes as a pre-release) was never told the final
     * version had shipped.
     */
    public static int compareVersions(String a, String b) {
        String an = (a == null ? "" : a.strip());
        String bn = (b == null ? "" : b.strip());
        String ap = preRelease(an);
        String bp = preRelease(bn);
        String[] as = core(an).split("\\.");
        String[] bs = core(bn).split("\\.");
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
        if (ap.isEmpty() && bp.isEmpty()) {
            return 0;
        }
        if (ap.isEmpty()) {
            return 1; // a release outranks its own pre-release
        }
        if (bp.isEmpty()) {
            return -1;
        }
        int cmp = ap.toLowerCase(Locale.ROOT).compareTo(bp.toLowerCase(Locale.ROOT));
        return cmp == 0 ? 0 : (cmp < 0 ? -1 : 1);
    }

    /** The numeric core of a version — everything before a {@code -pre.release} suffix. */
    private static String core(String v) {
        int dash = v.indexOf('-');
        return dash < 0 ? v : v.substring(0, dash);
    }

    /** The {@code -pre.release} suffix of a version, without the dash ({@code ""} when there is none). */
    private static String preRelease(String v) {
        int dash = v.indexOf('-');
        return dash < 0 || dash + 1 >= v.length() ? "" : v.substring(dash + 1);
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
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
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
