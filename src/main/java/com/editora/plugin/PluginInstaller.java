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
 * Installs a plugin from a remote registry entry (download + verify sha-256) or a local {@code .zip}, in two
 * steps so nothing on disk changes until the user has seen what the plugin does:
 *
 * <ol>
 *   <li>{@link #stageFromUrl}/{@link #stageFromZip} (worker thread): download or read, check, and unpack through
 *       {@link Unzip} (zip-slip + size guards) into a staging folder beside the plugins
 *       ({@code plugins/.installing-*}), then read its manifest. A registry archive must be the plugin and
 *       version its entry names. The result is a {@link Staged} install, or a {@link PluginProblem}.
 *   <li>{@link #commit} (FX thread, after the consent): swap the staged folder into {@code plugins/<id>},
 *       carrying the old install's {@code data/} over — or, for a plugin running in this session, leave it as
 *       {@code plugins/.pending-<id>} for the next start ({@link #applyPending}); a running plugin's jar is open,
 *       and on Windows an open jar cannot be moved or deleted. {@link #discard} drops a staged install the user
 *       declined, so declining an update keeps the version that was there.
 * </ol>
 */
public final class PluginInstaller {

    private static final Logger LOG = Logger.getLogger(PluginInstaller.class.getName());
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(120);
    /** The longest a download may take once its headers arrived (a server can drip bytes for ever). */
    private static final Duration BODY_DEADLINE = Duration.ofMinutes(5);

    private static final long MAX_DOWNLOAD_BYTES = 128L * 1024 * 1024;

    /** Outcome of a one-step install (tests, tools): {@code ok} + the plugin {@code id} + {@code name}, or why not. */
    public record Result(boolean ok, String id, String name, String error, PluginProblem problem) {
        static Result failed(PluginProblem p) {
            return new Result(false, "", "", p.english(), p);
        }
    }

    /**
     * A plugin unpacked into a staging folder, waiting for the user's answer.
     *
     * @param root     the folder holding its {@code plugin.json} (the staging folder or its only subfolder)
     * @param staging  the staging folder to delete afterwards
     * @param manifest its manifest (id = the folder it will be installed as)
     * @param jars     its jar files, relative ({@code x.jar}, {@code lib/y.jar})
     * @param previous the manifest of the copy installed now, or null for a new plugin
     */
    public record Staged(Path root, Path staging, PluginManifest manifest, List<String> jars, PluginManifest previous) {
        public String id() {
            return manifest.id;
        }

        public String name() {
            return manifest.name == null || manifest.name.isBlank() ? manifest.id : manifest.name.strip();
        }

        public String version() {
            return manifest.version == null ? "" : manifest.version.strip();
        }

        /** The fingerprint the consent to this version is stored with (see {@link PluginDescriptor#digest}). */
        public String digest() {
            return PluginDescriptor.digest(manifest, jars);
        }
    }

    /** What staging produced: a {@link Staged} install, or the reason there is none. */
    public record Staging(Staged staged, PluginProblem problem) {
        public boolean ok() {
            return staged != null;
        }
    }

    /** How a commit went: installed now, waiting for the next start, or failed for {@code problem}. */
    public record Commit(boolean ok, boolean pending, PluginProblem problem) {}

    private final PluginManager manager;
    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "plugin-installer");
        t.setDaemon(true);
        return t;
    });
    /** Built by the first download, not with the installer (constructed for every window at startup). */
    private final com.editora.io.LazyHttpClient client;

    /** The cap a downloaded or picked archive is held to ({@link #MAX_DOWNLOAD_BYTES} in production). */
    private final long maxArchiveBytes;

    private final ObjectMapper mapper = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public PluginInstaller(PluginManager manager) {
        this(manager, com.editora.io.LazyHttpClient.following(CONNECT_TIMEOUT), MAX_DOWNLOAD_BYTES);
    }

    /**
     * Test seam: the client a registry download goes through and the archive size cap, so a test can serve
     * plugin archives from a loopback server and reach the cap with a small body. The HTTPS and checksum
     * rules are not part of the seam.
     */
    PluginInstaller(PluginManager manager, com.editora.io.LazyHttpClient client, long maxArchiveBytes) {
        this.manager = manager;
        this.client = client;
        this.maxArchiveBytes = maxArchiveBytes;
    }

    /** Stops the worker and releases the HTTP client (window dispose). */
    public void shutdown() {
        exec.shutdownNow();
        client.close();
    }

    /** Downloads {@code entry.download}, verifies it, and stages it; posts the {@link Staging} on FX. */
    public void stageFromUrl(RegistryEntry entry, Consumer<Staging> onResult) {
        submit(() -> stageFromUrlSync(entry), onResult);
    }

    /** Stages a local {@code .zip} (no checksum — the user picked the file); posts the {@link Staging} on FX. */
    public void stageFromZip(Path zip, Consumer<Staging> onResult) {
        submit(() -> stageFromZipSync(zip), onResult);
    }

    /** Runs {@code work} on the worker and always posts an answer — an Error included (a huge file, OOM). */
    private void submit(java.util.function.Supplier<Staging> work, Consumer<Staging> onResult) {
        exec.submit(() -> {
            Staging r;
            try {
                r = work.get();
            } catch (Throwable t) { // NOSONAR: the user must hear back whatever happened
                LOG.log(Level.WARNING, "Plugin install failed", t);
                r = new Staging(null, new PluginProblem(PluginProblem.Kind.FAILED, List.of(), String.valueOf(t)));
            }
            Staging result = r;
            Platform.runLater(() -> onResult.accept(result));
        });
    }

    /** {@link #stageFromZip} without the worker and the FX hand-off. Never throws an exception. */
    Staging stageFromZipSync(Path zip) {
        try {
            if (Files.size(zip) > maxArchiveBytes) { // before reading: a 3 GB pick must not reach the heap
                return failure(PluginProblem.of(PluginProblem.Kind.TOO_LARGE, megabytes(maxArchiveBytes)));
            }
            return stageBytes(Files.readAllBytes(zip), null);
        } catch (IOException e) {
            return failure(new PluginProblem(PluginProblem.Kind.FAILED, List.of(), "read failed: " + e.getMessage()));
        }
    }

    /** {@link #stageFromUrl} without the worker and the FX hand-off. Never throws an exception. */
    Staging stageFromUrlSync(RegistryEntry e) {
        if (e == null || !PluginRegistry.isHttps(e.download)) {
            return failure(PluginProblem.of(PluginProblem.Kind.NOT_HTTPS));
        }
        if (e.sha256 == null || e.sha256.isBlank()) {
            return failure(PluginProblem.of(PluginProblem.Kind.NO_CHECKSUM));
        }
        byte[] body;
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(e.download.strip()))
                    .timeout(REQUEST_TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<java.io.InputStream> resp = client.get().send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() / 100 != 2) {
                resp.body().close();
                return failure(PluginProblem.of(PluginProblem.Kind.HTTP_STATUS, String.valueOf(resp.statusCode())));
            }
            try (java.io.InputStream in = resp.body()) {
                // bounded in size and in time; aborts an oversized or dripping stream
                body = PluginRegistry.readCapped(in, maxArchiveBytes, BODY_DEADLINE);
            } catch (PluginRegistry.TooLargeException tooLarge) {
                return failure(PluginProblem.of(PluginProblem.Kind.TOO_LARGE, megabytes(maxArchiveBytes)));
            }
        } catch (Exception ex) {
            LOG.log(Level.WARNING, "Plugin download failed: " + e.download, ex);
            return failure(PluginProblem.network(e.download, ex));
        }
        if (!sha256(body).equalsIgnoreCase(e.sha256.strip())) {
            return failure(PluginProblem.of(PluginProblem.Kind.CHECKSUM_MISMATCH));
        }
        return stageBytes(body, e);
    }

    private static String megabytes(long bytes) {
        return String.valueOf(Math.max(1, bytes / (1024 * 1024)));
    }

    private static Staging failure(PluginProblem p) {
        return new Staging(null, p);
    }

    /**
     * Unpacks {@code zipBytes} into a staging folder beside the plugins and reads its manifest. A registry
     * install ({@code entry} not null) must be the plugin and version its entry names: an index that pointed
     * one entry at another plugin's archive downgraded or replaced that plugin without a word.
     */
    Staging stageBytes(byte[] zipBytes, RegistryEntry entry) {
        Path staging = null;
        boolean keep = false;
        try {
            Path pluginsDir = manager.pluginsDir();
            Files.createDirectories(pluginsDir);
            // Unpacked straight beside the plugins (same volume), so the final swap is a rename.
            staging = Files.createTempDirectory(pluginsDir, STAGING_PREFIX);
            try {
                Unzip.extract(new ByteArrayInputStream(zipBytes), staging);
            } catch (IOException unsafe) {
                return failure(new PluginProblem(
                        PluginProblem.Kind.ARCHIVE_INVALID, List.of(), String.valueOf(unsafe.getMessage())));
            }
            Path root = findPluginRoot(staging);
            if (root == null) {
                return failure(PluginProblem.of(PluginProblem.Kind.NO_MANIFEST));
            }
            PluginManifest m;
            try (InputStream in = Files.newInputStream(root.resolve("plugin.json"))) {
                m = PluginManager.parseManifest(mapper, in);
            } catch (IOException | RuntimeException bad) {
                PluginDescriptor.LoadProblem lp = PluginManager.manifestProblem(bad);
                List<String> at = lp.args().size() == 2 ? lp.args() : List.of("", "");
                return failure(new PluginProblem(PluginProblem.Kind.MANIFEST_INVALID, at, lp.detail()));
            }
            String id = m.id == null ? "" : m.id.strip();
            if (id.isBlank()) {
                id = root == staging ? "" : root.getFileName().toString();
            }
            if (id.isBlank() || !isSafeId(id)) {
                return failure(PluginProblem.of(PluginProblem.Kind.INVALID_ID, id));
            }
            if (isReservedId(id)) {
                return failure(PluginProblem.of(PluginProblem.Kind.RESERVED_ID, id));
            }
            m.id = id;
            if (entry != null) {
                String wantId = entry.id == null ? "" : entry.id.strip();
                if (!wantId.equals(id)) {
                    return failure(PluginProblem.of(PluginProblem.Kind.ID_MISMATCH, wantId, id));
                }
                String wantVersion = entry.version == null ? "" : entry.version.strip();
                String version = m.version == null ? "" : m.version.strip();
                if (!wantVersion.isEmpty() && !wantVersion.equals(version)) {
                    return failure(PluginProblem.of(PluginProblem.Kind.VERSION_MISMATCH, wantVersion, version));
                }
            }
            if (RetiredPlugins.isRetired(id)) {
                // The feature is built in now; installing the plugin again would run the two side by side.
                return failure(PluginProblem.of(PluginProblem.Kind.RETIRED, id));
            }
            Path target = pluginsDir.resolve(id);
            PluginManifest previous = null;
            if (Files.isSymbolicLink(target)) {
                // A plugin under development, linked in from its source folder: an install would move that
                // folder's data/ out of it and replace the link. Refused; the developer updates the source.
                return failure(PluginProblem.of(PluginProblem.Kind.SYMLINKED, id));
            }
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isRegularFile(target.resolve("plugin.json"))) {
                    // Whatever this is, it is not an install of this plugin: never delete it to make room.
                    return failure(PluginProblem.of(PluginProblem.Kind.IN_THE_WAY, id));
                }
                previous = readManifestOrStub(target, id);
            }
            keep = true;
            return new Staging(new Staged(root, staging, m, PluginManager.jarNames(root), previous), null);
        } catch (IOException | RuntimeException ex) {
            LOG.log(Level.WARNING, "Plugin install failed", ex);
            return failure(new PluginProblem(PluginProblem.Kind.FAILED, List.of(), String.valueOf(ex.getMessage())));
        } finally {
            if (!keep && staging != null) {
                deleteQuietly(staging);
            }
        }
    }

    /** The manifest in {@code dir}, or a stub naming {@code id} when it cannot be read. */
    private PluginManifest readManifestOrStub(Path dir, String id) {
        try (InputStream in = Files.newInputStream(dir.resolve("plugin.json"))) {
            return PluginManager.parseManifest(mapper, in);
        } catch (IOException | RuntimeException e) {
            PluginManifest stub = new PluginManifest();
            stub.id = id;
            stub.name = id;
            return stub;
        }
    }

    /** Drops a staged install the user declined. */
    public static void discard(Staged staged) {
        if (staged != null) {
            deleteQuietly(staged.staging());
        }
    }

    /**
     * Puts a staged install in place. With {@code deferToRestart} — the plugin runs in this session — it is left
     * as {@code plugins/.pending-<id>} and swapped in at the next start, before discovery; otherwise the old
     * install (if any) is swapped out by rename, its {@code data/} carried over, and deleted last. A failure in
     * between puts the old install back. Either way the staging folder is gone afterwards.
     */
    public Commit commit(Staged staged, boolean deferToRestart) {
        Path pluginsDir = manager.pluginsDir();
        try {
            if (deferToRestart) {
                Path pending = pluginsDir.resolve(PENDING_PREFIX + staged.id());
                deleteRecursively(pending); // an older waiting update is superseded
                moveDir(staged.root(), pending);
                Files.deleteIfExists(pluginsDir.resolve(REMOVE_PREFIX + staged.id())); // installing again wins
                return new Commit(true, true, null);
            }
            swapIn(pluginsDir, staged.root(), staged.id());
            return new Commit(true, false, null);
        } catch (IOException | RuntimeException ex) {
            LOG.log(Level.WARNING, "Plugin install failed", ex);
            return new Commit(
                    false,
                    false,
                    new PluginProblem(PluginProblem.Kind.FAILED, List.of(), String.valueOf(ex.getMessage())));
        } finally {
            deleteQuietly(staged.staging());
        }
    }

    /**
     * Moves {@code incoming} to {@code plugins/<id>}. An existing install is moved aside first, its
     * {@code data/} is carried into the new one, and it is deleted last; if the new one cannot be put in place,
     * the old one is put back, data and all.
     *
     * <p>An update used to be a recursive delete of {@code plugins/<id>/} followed by a move, which wiped the
     * plugin's {@code data/} (the writable store {@code PluginContext.dataDir()} hands out) on every update, and
     * left nothing at all if the move then failed.
     */
    private static void swapIn(Path pluginsDir, Path incoming, String id) throws IOException {
        Path target = pluginsDir.resolve(id);
        boolean update = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
        if (update && Files.isSymbolicLink(target)) {
            throw new IOException("the installed \"" + id + "\" is a link to another folder");
        }
        if (update && !Files.isRegularFile(target.resolve("plugin.json"))) {
            throw new IOException("a folder named \"" + id + "\" that is not a plugin is in the way");
        }
        Path previous = null;
        boolean previousIsSpare = false; // true once the new install is complete and holds the old data
        try {
            if (update) {
                Path aside = Files.createTempDirectory(pluginsDir, STAGING_PREFIX + id + ".old-");
                Files.delete(aside);
                Files.move(target, aside);
                previous = aside;
            }
            try {
                moveDir(incoming, target);
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
            if (previous != null && previousIsSpare) {
                deleteQuietly(previous);
            }
        }
    }

    /**
     * Stages and installs in one step, with no question in between — for tests and tools. {@code entry} (may be
     * null) is the registry entry the archive must match.
     */
    Result installBytes(byte[] zipBytes) {
        return installStaged(stageBytes(zipBytes, null));
    }

    /** {@link #installBytes} for a picked file. */
    Result installFromZipSync(Path zip) {
        return installStaged(stageFromZipSync(zip));
    }

    /** {@link #installBytes} for a registry entry. */
    Result installFromUrlSync(RegistryEntry e) {
        return installStaged(stageFromUrlSync(e));
    }

    private Result installStaged(Staging s) {
        if (!s.ok()) {
            return Result.failed(s.problem());
        }
        Commit c = commit(s.staged(), false);
        if (!c.ok()) {
            return Result.failed(c.problem());
        }
        manager.discover();
        return new Result(true, s.staged().id(), s.staged().name(), null, null);
    }

    /**
     * Marks {@code id} — a plugin running in this session, whose jar may be open — for removal at the next
     * start (a {@code plugins/.remove-<id>} marker, applied by {@link #applyPending} before discovery). A waiting
     * update of it is dropped.
     */
    public static boolean markForRemoval(Path pluginsDir, String id) {
        if (!isSafeId(id) || isReservedId(id)) {
            return false;
        }
        try {
            Files.createDirectories(pluginsDir);
            deleteRecursively(pluginsDir.resolve(PENDING_PREFIX + id));
            Path marker = pluginsDir.resolve(REMOVE_PREFIX + id);
            if (!Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
                Files.createFile(marker);
            }
            return true;
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Could not mark plugin " + id + " for removal", e);
            return false;
        }
    }

    /** How old a staging folder must be before {@link #applyPending} treats it as left by a crash. */
    static final Duration STALE_STAGING = Duration.ofHours(1);

    /**
     * Applies what a previous session left for the next start, before anything is discovered: removes the
     * plugins marked {@code .remove-<id>}, swaps each {@code .pending-<id>} update into place (keeping
     * {@code data/}), and deletes staging folders a crash left behind. Called once per launch by
     * {@link PluginManager#discover()}. Never throws; what fails stays for the next start.
     */
    static void applyPending(Path pluginsDir) {
        if (pluginsDir == null || !Files.isDirectory(pluginsDir)) {
            return;
        }
        List<Path> entries;
        try (Stream<Path> s = Files.list(pluginsDir)) {
            entries = s.sorted().toList();
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Could not list " + pluginsDir, e);
            return;
        }
        for (Path p : entries) {
            String name = p.getFileName().toString();
            if (!name.startsWith(REMOVE_PREFIX)) {
                continue;
            }
            String id = name.substring(REMOVE_PREFIX.length());
            if (!isSafeId(id) || isReservedId(id)) {
                deleteQuietly(p);
                continue;
            }
            Path dir = pluginsDir.resolve(id);
            deleteQuietly(pluginsDir.resolve(PENDING_PREFIX + id));
            if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS) || deletePluginFolder(dir)) {
                deleteQuietly(p);
            }
        }
        for (Path p : entries) {
            String name = p.getFileName().toString();
            if (name.startsWith(PENDING_PREFIX) && Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
                String id = name.substring(PENDING_PREFIX.length());
                if (!isSafeId(id) || isReservedId(id) || !Files.isRegularFile(p.resolve("plugin.json"))) {
                    deleteQuietly(p);
                    continue;
                }
                try {
                    swapIn(pluginsDir, p, id);
                } catch (IOException | RuntimeException e) {
                    LOG.log(Level.WARNING, "Could not apply the waiting update of plugin " + id, e);
                }
            } else if (name.startsWith(STAGING_PREFIX) && isStale(p)) {
                deleteQuietly(p); // a crash mid-install; nothing will ever pick it up
            }
        }
    }

    private static boolean isStale(Path p) {
        try {
            return Files.getLastModifiedTime(p, LinkOption.NOFOLLOW_LINKS)
                    .toInstant()
                    .isBefore(java.time.Instant.now().minus(STALE_STAGING));
        } catch (IOException e) {
            return false;
        }
    }

    /** Prefix of the folders an install stages beside the plugins; {@link PluginManager#discover} skips them. */
    static final String STAGING_PREFIX = ".installing-";

    /** {@code .pending-<id>}: an update of a plugin that ran when it was accepted, swapped in at the next start. */
    static final String PENDING_PREFIX = ".pending-";

    /** {@code .remove-<id>}: an empty file marking a plugin that ran when it was removed, deleted at the next start. */
    static final String REMOVE_PREFIX = ".remove-";

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
     * downloaded language servers, debug adapters, the Typst CLI and single-binary tools there
     * ({@code plugins/lsp}, {@code plugins/dap}, {@code plugins/typst}, {@code plugins/tools}). A plugin with
     * one of these ids would be installed over — and removed together with — every one of those tools. Config
     * export leaves these folders out too.
     */
    private static final Set<String> RESERVED_IDS = Set.of("lsp", "dap", "typst", "tools");

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
        return deletePluginFolder(dir);
    }

    /**
     * Deletes a plugin folder with its {@code plugin.json} last, carrying on past files that cannot be deleted
     * (a jar Windows keeps locked): what is left then still has its manifest, so it is still listed and can be
     * removed again. Deleting in reverse name order and stopping at the first failure used to remove the
     * manifest and {@code data/} of most plugins and leave an unlisted folder that also blocked a reinstall.
     *
     * @return whether the folder is gone
     */
    static boolean deletePluginFolder(Path dir) {
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            return true;
        }
        Path manifest = dir.resolve("plugin.json");
        List<Path> all;
        try (Stream<Path> s = Files.walk(dir)) {
            all = s.sorted(Comparator.reverseOrder()).toList();
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Could not list " + dir, e);
            return false;
        }
        boolean failed = false;
        for (Path p : all) {
            if (p.equals(manifest) || p.equals(dir)) {
                continue;
            }
            try {
                Files.deleteIfExists(p);
            } catch (IOException e) {
                failed = true;
                LOG.log(Level.FINE, "Could not delete " + p, e);
            }
        }
        if (failed) {
            return false;
        }
        try {
            Files.deleteIfExists(manifest);
            Files.deleteIfExists(dir);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Could not delete " + dir, e);
        }
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
     * <p>Semver rules where they matter: a <b>pre-release</b> suffix ({@code 1.0.0-rc1}) ranks <i>below</i> the
     * same numeric core ({@code 1.0.0}), and its dot-separated identifiers compare numerically when both are
     * numbers ({@code rc.10 > rc.2}, and {@code rc10 > rc2} by their trailing number); {@code +build} metadata is
     * ignored; a leading {@code v} is dropped ({@code v2.0 < 10.0}).
     *
     * <p>Comparing the raw last segments made {@code "0"} sort before {@code "0-rc1"} — i.e. the release looked
     * *older* than its own release candidate, so a user running an {@code -rcN} build (which the release pipeline
     * publishes as a pre-release) was never told the final version had shipped.
     */
    public static int compareVersions(String a, String b) {
        String an = normalizeVersion(a);
        String bn = normalizeVersion(b);
        String ap = preRelease(an);
        String bp = preRelease(bn);
        String[] as = core(an).split("\\.");
        String[] bs = core(bn).split("\\.");
        int n = Math.max(as.length, bs.length);
        for (int i = 0; i < n; i++) {
            String x = (i < as.length && !as[i].isBlank()) ? as[i] : "0";
            String y = (i < bs.length && !bs[i].isBlank()) ? bs[i] : "0";
            int cmp = compareIdentifier(x, y);
            if (cmp != 0) {
                return cmp;
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
        String[] ax = ap.split("\\.");
        String[] bx = bp.split("\\.");
        for (int i = 0; i < Math.min(ax.length, bx.length); i++) {
            int cmp = compareIdentifier(ax[i], bx[i]);
            if (cmp != 0) {
                return cmp;
            }
        }
        return Integer.compare(ax.length, bx.length) < 0 ? -1 : (ax.length == bx.length ? 0 : 1);
    }

    /** Numbers numerically; {@code rc10} vs {@code rc2} by their text, then their trailing number; else text. */
    private static int compareIdentifier(String x, String y) {
        Integer xi = tryInt(x);
        Integer yi = tryInt(y);
        int cmp;
        if (xi != null && yi != null) {
            cmp = Integer.compare(xi, yi);
        } else {
            String xs = x.replaceAll("\\d+$", "");
            String ys = y.replaceAll("\\d+$", "");
            Integer xn = xs.length() < x.length() ? tryInt(x.substring(xs.length())) : null;
            Integer yn = ys.length() < y.length() ? tryInt(y.substring(ys.length())) : null;
            cmp = xs.toLowerCase(Locale.ROOT).compareTo(ys.toLowerCase(Locale.ROOT));
            if (cmp == 0 && xn != null && yn != null) {
                cmp = Integer.compare(xn, yn);
            } else if (cmp == 0) {
                cmp = x.toLowerCase(Locale.ROOT).compareTo(y.toLowerCase(Locale.ROOT));
            }
        }
        return Integer.signum(cmp);
    }

    /** Strips blanks, a leading {@code v} and {@code +build} metadata. */
    private static String normalizeVersion(String v) {
        String s = v == null ? "" : v.strip();
        if (s.length() > 1 && (s.charAt(0) == 'v' || s.charAt(0) == 'V') && Character.isDigit(s.charAt(1))) {
            s = s.substring(1);
        }
        int plus = s.indexOf('+');
        return plus < 0 ? s : s.substring(0, plus);
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

    /** Deletes a retired plugin's folder ({@link RetiredPlugins}); only ever a direct child of the plugins dir. */
    static void deleteRecursivelyForRetirement(Path dir) throws IOException {
        deleteRecursively(dir);
    }

    private static void deleteQuietly(Path dir) {
        try {
            deleteRecursively(dir);
        } catch (IOException ignored) {
            // best-effort temp cleanup
        }
    }
}
