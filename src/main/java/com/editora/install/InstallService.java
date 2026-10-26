package com.editora.install;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
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
import org.jspecify.annotations.Nullable;

/**
 * Runs {@link InstallCatalog} steps off the FX thread (the {@code GitService}/{@code MermaidService} idiom:
 * one daemon executor + {@link Platform#runLater} results), reusing the shared subprocess + download/unzip
 * infrastructure: {@link ProcessRunner} (npm/pip/tar, with the augmented-PATH executable resolution that
 * lets a GUI-launched app find them), {@link PluginRegistry#readCapped} for bounded HTTPS downloads, and the
 * hardened {@link Unzip} for the {@code .vsix} (ZIP) extracts. Tarballs go through the system {@code tar}.
 */
public final class InstallService {

    /**
     * Outcome of an install run: {@code ok} + a (possibly empty) error/detail {@code message}, plus the
     * resolved command for a per-OS binary install ({@code installedCommand}, the extracted binary path +
     * any suffix — the coordinator persists it into the server's Settings command; {@code null} otherwise).
     */
    public record Result(
            boolean ok, @Nullable String message, @Nullable String installedCommand) {
        public Result(boolean ok, @Nullable String message) {
            this(ok, message, null);
        }
    }

    /** Generous extraction caps for installer archives — native LS binaries (e.g. clangd) dwarf plugin caps. */
    private static final long MAX_ARCHIVE_ENTRY_BYTES = 512L * 1024 * 1024;

    private static final long MAX_ARCHIVE_TOTAL_BYTES = 1024L * 1024 * 1024;
    private static final int MAX_ARCHIVE_ENTRIES = 100_000;

    /** A bounded cap on any single download (vsix/tarball/JSON) so a hostile/oversized response can't OOM. */
    private static final long MAX_DOWNLOAD_BYTES = 200L * 1024 * 1024;

    private static final java.util.logging.Logger LOG =
            java.util.logging.Logger.getLogger(InstallService.class.getName());

    private static final Duration NET_TIMEOUT = Duration.ofMinutes(3);
    /** npm/pip can take minutes (compiling/native deps); generous but bounded. */
    private static final Duration CMD_TIMEOUT = Duration.ofMinutes(10);

    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "install-service");
        t.setDaemon(true);
        return t;
    });

    /** Built by the first download, not with the service (a {@code MainController} field initializer). */
    private final com.editora.io.LazyHttpClient client;

    /** The cap one download is held to ({@link #MAX_DOWNLOAD_BYTES} in production). */
    private final long maxDownloadBytes;

    public InstallService() {
        this(com.editora.io.LazyHttpClient.following(Duration.ofSeconds(20)), MAX_DOWNLOAD_BYTES);
    }

    /**
     * Test seam: the client the downloads go through and the size one download may reach, so a test can
     * answer the catalog's URLs from a loopback server and reach the cap with a small body. The URL rules in
     * {@link #download} are not part of the seam — they apply to every instance.
     */
    InstallService(com.editora.io.LazyHttpClient client, long maxDownloadBytes) {
        this.client = client;
        this.maxDownloadBytes = maxDownloadBytes;
    }

    /** The JDK home chosen in Settings → Build Tools (blank = none); the JVM servers run and are smoke-tested on it. */
    private volatile java.util.function.Supplier<String> jdkHome = () -> "";

    /** Wires the Settings → Build Tools JDK: it satisfies the {@link Prereq#JAVA} prerequisite and runs the
     *  post-install smoke, so a machine with no {@code java} on PATH can still install the Maven server (C4). */
    public void setJdkHomeSupplier(java.util.function.Supplier<String> jdkHome) {
        this.jdkHome = jdkHome == null ? () -> "" : jdkHome;
    }

    /** The configured JDK's {@code java} executable, or {@code ""} when none is set or it has none. */
    private String jdkJava() {
        String home;
        try {
            home = jdkHome.get();
        } catch (RuntimeException e) {
            return "";
        }
        return home == null || home.isBlank() ? "" : com.editora.run.JdkToolchain.javaExecutable(home);
    }

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
            if (onPath("go")) {
                have.add(Prereq.GO);
            }
            if (onPath("gem")) {
                have.add(Prereq.RUBY);
            }
            if (onPath("dotnet")) {
                have.add(Prereq.DOTNET);
            }
            if (onPath("rustup")) {
                have.add(Prereq.RUSTUP);
            }
            if (onPath("composer")) {
                have.add(Prereq.COMPOSER);
            }
            if (onPath("java") || !jdkJava().isBlank()) {
                have.add(Prereq.JAVA); // the Settings → Build Tools JDK counts: the packaged runtime has no java
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
            Result result = installSync(steps, configDir, id -> Platform.runLater(() -> onStepStart.accept(id)));
            Platform.runLater(() -> onResult.accept(result));
        });
    }

    /**
     * Runs {@code steps} on the calling thread and returns the outcome — what {@link #install} does on its
     * worker, without the FX hand-off. {@code onStepStart} is called on this thread. Never throws: a failed
     * step ends the run with a not-ok {@link Result} carrying its message.
     */
    Result installSync(List<Step> steps, Path configDir, Consumer<String> onStepStart) {
        try {
            String installedCommand = null;
            for (Step s : steps) {
                onStepStart.accept(s.id());
                String cmd = runStep(s, configDir);
                writeReceipt(s, configDir);
                if (cmd != null) {
                    installedCommand = cmd;
                }
            }
            return new Result(true, "", installedCommand);
        } catch (InstallException e) {
            return new Result(false, e.getMessage());
        } catch (Exception e) {
            return new Result(false, e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    /**
     * Records which version a pinned step just installed ({@link InstallReceipt}), so a later Editora that moves the
     * pin can tell this copy is older. A receipt that cannot be written does not fail the install: the copy works,
     * and Doctor reads its version from the files where it can.
     */
    private static void writeReceipt(Step s, Path configDir) {
        PinnedVersions.forStep(s.id()).ifPresent(pin -> {
            try {
                new InstallReceipt(pin.stepId(), pin.version(), java.time.Instant.now())
                        .write(configDir.resolve(s.destSubpath()));
            } catch (IOException | RuntimeException e) {
                LOG.log(java.util.logging.Level.WARNING, "could not record the installed version of " + s.id(), e);
            }
        });
    }

    /** Stops the worker (window dispose). */
    public void shutdown() {
        exec.shutdownNow();
        client.close();
    }

    // --- step execution ------------------------------------------------------------------------

    /** Runs one step; returns the resolved server command for an ARCHIVE step, else {@code null}. */
    private @Nullable String runStep(Step s, Path configDir) throws Exception {
        switch (s.kind()) {
            case NPM_GLOBAL -> runCommand(InstallCatalog.npmInstallGlobalArgv(s.npmPackages()), s.id());
            case PIP_TARGET -> {
                Path dest = configDir.resolve(s.destSubpath());
                Files.createDirectories(dest);
                String python = onPath("python3") ? "python3" : "python";
                // Python is on PATH but pip need not be (Debian/Ubuntu ship it separately): say that, rather than
                // the bare "No module named pip" of the install itself.
                if (!ProcessRunner.runInUserLocale(null, CMD_TIMEOUT, List.of(python, "-m", "pip", "--version"))
                        .ok()) {
                    throw new InstallException(s.id() + ": pip is not available for " + python
                            + " — install pip (on Debian or Ubuntu, the python3-pip package), then try again");
                }
                runCommand(InstallCatalog.pipInstallTargetArgv(python, dest, s.pipPackage()), s.id());
            }
            case VSIX -> installVsix(s, configDir);
            case TARBALL -> installTarball(s, configDir);
            case TOOL_COMMAND -> runCommand(s.npmPackages(), s.id()); // argv carried in npmPackages
            case PUPPETEER_BROWSER -> installPuppeteerChrome(s);
            case ARCHIVE -> {
                return installArchive(s, configDir);
            }
            case JVM_LSP_ZIP -> {
                return installJvmLspZip(s, configDir);
            }
            case PINNED_TOOL -> {
                return installPinnedTool(s, configDir);
            }
            case JVM_LSP_JAR -> {
                return installJvmLspJar(s, configDir);
            }
            case COURSIER_LSP -> {
                return installCoursierLsp(s, configDir);
            }
            case PINNED_SERVER -> {
                return installPinnedServer(s, configDir);
            }
        }
        return null;
    }

    /** Where pinned-tool recipes come from ({@link InstallCatalog#pinnedTool}); a test supplies its own. */
    private java.util.function.Function<String, java.util.Optional<InstallCatalog.PinnedTool>> pinnedTools =
            InstallCatalog::pinnedTool;

    /** Test seam for {@link #pinnedTools}: recipes whose hashes match a test's own small archives. */
    void setPinnedToolsForTest(
            java.util.function.Function<String, java.util.Optional<InstallCatalog.PinnedTool>> recipes) {
        this.pinnedTools = recipes;
    }

    /**
     * Downloads a pinned tool's asset for this platform, refuses it unless its SHA-256 is the one in the recipe,
     * and puts the program at {@code <dest>/<binary>}, executable. Returns the program's path.
     */
    private String installPinnedTool(Step s, Path configDir) throws Exception {
        InstallCatalog.PinnedTool tool = pinnedTools.apply(s.id()).orElse(null);
        if (tool == null) {
            throw new InstallException(s.id() + ": no recipe");
        }
        InstallCatalog.Platform platform = InstallCatalog.currentPlatform();
        InstallCatalog.PinnedAsset asset = tool.assetFor(platform);
        if (asset == null) {
            throw new InstallException(s.id() + ": no prebuilt binary for this OS/architecture");
        }
        byte[] data = download(asset.url());
        String actual = InstallCatalog.sha256Hex(data);
        if (!actual.equalsIgnoreCase(asset.sha256())) {
            throw new InstallException(s.id() + ": the download does not match its published checksum (expected "
                    + asset.sha256() + ", got " + actual + ") — not installed");
        }
        String fileName = InstallCatalog.toolFileName(tool.id(), platform);
        Path dest = configDir.resolve(s.destSubpath());
        replaceInstall(dest, staged -> {
            Path program = staged.resolve(fileName);
            if (asset.packing() == InstallCatalog.Packing.RAW) {
                Files.write(program, data);
            } else if (asset.packing() == InstallCatalog.Packing.GZ) {
                Files.write(program, gunzip(data));
            } else {
                Path unpacked = Files.createDirectory(staged.resolve(".unpacked"));
                String suffix = asset.packing() == InstallCatalog.Packing.ZIP ? ".zip" : ".tar.gz";
                extractArchive(s, suffix, data, unpacked);
                Path found = findBinary(unpacked, tool.binaryName(), false);
                if (found == null) {
                    throw new InstallException(s.id() + ": '" + tool.binaryName() + "' not found in the download");
                }
                Files.copy(found, program);
                deleteRecursively(unpacked);
            }
            makeExecutable(program);
            return null;
        });
        return dest.resolve(fileName).toString();
    }

    /** The jars a {@link InstallCatalog.Kind#JVM_LSP_ZIP} step adds beside its bundle, by step id; a test supplies its own. */
    private java.util.function.Function<String, List<InstallCatalog.JvmLspJar>> jvmLspExtraJars =
            InstallCatalog::jvmLspExtraJars;

    /** The request a freshly extracted JVM server must answer before it counts as installed; a test supplies its own. */
    @FunctionalInterface
    interface JvmLspSmokeCheck {
        /** Why the server launched by {@code argv} (whose jars are in {@code dir}) failed, or empty when it answered. */
        java.util.Optional<String> check(List<String> argv, Path dir);
    }

    private JvmLspSmokeCheck jvmLspSmoke = (argv, dir) -> JvmLspSmoke.hoverCheck(argv);

    /** Test seam for {@link #jvmLspExtraJars}: recipes whose hashes match a test's own bytes, or none. */
    void setJvmLspExtraJarsForTest(java.util.function.Function<String, List<InstallCatalog.JvmLspJar>> recipes) {
        this.jvmLspExtraJars = recipes;
    }

    /** Test seam for {@link #jvmLspSmoke}: a verdict without a process. */
    void setJvmLspSmokeForTest(JvmLspSmokeCheck smoke) {
        this.jvmLspSmoke = smoke;
    }

    /**
     * Downloads the self-contained JVM LSP bundle ({@code directUrl}), extracts it into the config dir, adds the
     * pinned jars the bundle leaves out ({@link InstallCatalog#jvmLspExtraJars}, each refused unless its SHA-256
     * is the catalog's), proves the server answers a request ({@link JvmLspSmoke}) and returns the
     * {@code java -cp "<dir>/*" <mainClass>} launch command. All of it happens in the staging folder: a bundle
     * that fails any of these leaves the previous install (or nothing) in place.
     */
    private String installJvmLspZip(Step s, Path configDir) throws Exception {
        byte[] data = download(s.directUrl());
        List<InstallCatalog.JvmLspJar> extras = jvmLspExtraJars.apply(s.id());
        List<byte[]> extraData = new ArrayList<>();
        for (InstallCatalog.JvmLspJar jar : extras) {
            byte[] bytes = download(jar.url());
            String actual = InstallCatalog.sha256Hex(bytes);
            if (!actual.equalsIgnoreCase(jar.sha256())) {
                throw new InstallException(s.id() + ": " + jar.fileName()
                        + " does not match its published checksum (expected " + jar.sha256() + ", got " + actual
                        + ") — not installed");
            }
            extraData.add(bytes);
        }
        Path dest = configDir.resolve(s.destSubpath());
        replaceInstall(dest, staged -> {
            Unzip.extract(
                    new ByteArrayInputStream(data),
                    staged,
                    MAX_ARCHIVE_ENTRY_BYTES,
                    MAX_ARCHIVE_TOTAL_BYTES,
                    MAX_ARCHIVE_ENTRIES);
            // The launch command is a "<dir>/*" classpath, so the jars have to be at the top. A body that is
            // not a zip at all (an error page served with 200) reads as an archive with no entries — without
            // this check that was a "successful" install of an empty folder.
            if (!hasTopLevelJar(staged)) {
                throw new InstallException(s.id() + ": no jar found in the downloaded bundle");
            }
            for (int i = 0; i < extras.size(); i++) {
                Files.write(staged.resolve(extras.get(i).fileName()), extraData.get(i));
            }
            // A bundle that starts and says it is ready can still fail every request inside the server (the
            // upstream zip without its `provided` jars did, for a whole release): ask it one before swapping it in.
            List<String> argv = new java.util.ArrayList<>(com.editora.run.ProgramArgs.commandLine(
                    InstallCatalog.jvmClasspathCommand(staged, InstallCatalog.LEMMINX_MAIN_CLASS)));
            String java = jdkJava();
            if (!java.isBlank() && !argv.isEmpty() && "java".equals(argv.get(0))) {
                argv.set(0, java); // the same JDK the coordinator runs the server on (JvmServerCommand.withJdk)
            }
            java.util.Optional<String> failed = jvmLspSmoke.check(List.copyOf(argv), staged);
            if (failed.isPresent()) {
                throw new InstallException(s.id() + ": the server was extracted but could not answer a request ("
                        + failed.get() + ") — not installed");
            }
            return null;
        });
        return InstallCatalog.jvmClasspathCommand(dest, InstallCatalog.LEMMINX_MAIN_CLASS);
    }

    /** Where server-jar recipes come from ({@link InstallCatalog#jvmLspJar}); a test supplies its own. */
    private java.util.function.Function<String, java.util.Optional<InstallCatalog.JvmLspJar>> jvmLspJars =
            InstallCatalog::jvmLspJar;

    /** Test seam for {@link #jvmLspJars}: a recipe whose hash matches a test's own bytes. */
    void setJvmLspJarsForTest(
            java.util.function.Function<String, java.util.Optional<InstallCatalog.JvmLspJar>> recipes) {
        this.jvmLspJars = recipes;
    }

    /**
     * Downloads a pinned server jar, refuses it unless its SHA-256 is the recipe's, puts it at
     * {@code <dest>/<file>} and returns the {@code java -jar "<file>"} launch command.
     */
    private String installJvmLspJar(Step s, Path configDir) throws Exception {
        InstallCatalog.JvmLspJar jar = jvmLspJars.apply(s.id()).orElse(null);
        if (jar == null) {
            throw new InstallException(s.id() + ": no recipe");
        }
        byte[] data = download(jar.url());
        String actual = InstallCatalog.sha256Hex(data);
        if (!actual.equalsIgnoreCase(jar.sha256())) {
            throw new InstallException(s.id() + ": the download does not match its published checksum (expected "
                    + jar.sha256() + ", got " + actual + ") — not installed");
        }
        Path dest = configDir.resolve(s.destSubpath());
        replaceInstall(dest, staged -> {
            Files.write(staged.resolve(jar.fileName()), data);
            return null;
        });
        return InstallCatalog.jvmJarCommand(dest.resolve(jar.fileName()));
    }

    /** Where coursier-resolved server recipes come from ({@link InstallCatalog#coursierLsp}); a test supplies its own. */
    private java.util.function.Function<String, java.util.Optional<InstallCatalog.CoursierLsp>> coursierLsps =
            InstallCatalog::coursierLsp;

    /** The coursier launcher a {@link InstallCatalog.Kind#COURSIER_LSP} step downloads; a test supplies its own. */
    private InstallCatalog.PinnedTool coursierTool = InstallCatalog.COURSIER_TOOL;

    /** Test seam: a coursier "release" whose hash matches a test's own launcher, and the server it resolves. */
    void setCoursierForTest(
            InstallCatalog.PinnedTool tool,
            java.util.function.Function<String, java.util.Optional<InstallCatalog.CoursierLsp>> recipes) {
        this.coursierTool = tool;
        this.coursierLsps = recipes;
    }

    /**
     * Installs a server published only as Maven artifacts (Metals): downloads coursier's pinned launcher for this
     * platform and refuses it unless its SHA-256 matches, runs {@code cs fetch <artifact>} with coursier's cache
     * inside the staging folder, copies every jar it names into {@code <dest>/lib}, and keeps {@code cs} beside
     * them. Returns the {@code java … -cp "<lib>/*" <mainClass>} launch command. Nothing outside the config dir
     * is written: the cache is the staging folder's, and goes with it.
     */
    private String installCoursierLsp(Step s, Path configDir) throws Exception {
        InstallCatalog.CoursierLsp server = coursierLsps.apply(s.id()).orElse(null);
        if (server == null) {
            throw new InstallException(s.id() + ": no recipe");
        }
        InstallCatalog.Platform platform = InstallCatalog.currentPlatform();
        InstallCatalog.PinnedAsset asset = coursierTool.assetFor(platform);
        if (asset == null) {
            throw new InstallException(s.id() + ": coursier publishes no launcher for this OS/architecture");
        }
        byte[] data = download(asset.url());
        String actual = InstallCatalog.sha256Hex(data);
        if (!actual.equalsIgnoreCase(asset.sha256())) {
            throw new InstallException(s.id() + ": the coursier download does not match its published checksum"
                    + " (expected " + asset.sha256() + ", got " + actual + ") — not installed");
        }
        String csName = platform == InstallCatalog.Platform.WIN_X64
                ? coursierTool.binaryName() + ".exe"
                : coursierTool.binaryName();
        Path dest = configDir.resolve(s.destSubpath());
        replaceInstall(dest, staged -> {
            Path cs = staged.resolve(csName);
            switch (asset.packing()) {
                case GZ -> Files.write(cs, gunzip(data));
                case RAW -> Files.write(cs, data);
                default -> {
                    Path unpacked = Files.createDirectory(staged.resolve(".unpacked"));
                    extractArchive(
                            s, asset.packing() == InstallCatalog.Packing.ZIP ? ".zip" : ".tar.gz", data, unpacked);
                    // Windows' zip holds cs-x86_64-pc-win32.exe: the launcher is named for its platform.
                    Path found = findBinary(unpacked, coursierTool.binaryName(), true);
                    if (found == null) {
                        throw new InstallException(s.id() + ": 'cs' not found in the coursier download");
                    }
                    Files.copy(found, cs);
                    deleteRecursively(unpacked);
                }
            }
            makeExecutable(cs);
            Path cache = Files.createDirectory(staged.resolve(".coursier-cache"));
            ProcessRunner.Result fetched = ProcessRunner.run(
                    staged,
                    CMD_TIMEOUT,
                    InstallCatalog.coursierFetchArgv(cs.toString(), server.artifact()),
                    java.util.Map.of("COURSIER_CACHE", cache.toString()));
            if (!fetched.ok()) {
                throw new InstallException(s.id() + ": coursier could not resolve " + server.artifact() + ": "
                        + (fetched.message().isBlank() ? "command failed" : fetched.message()));
            }
            Path lib = Files.createDirectory(staged.resolve("lib"));
            int copied = copyFetchedJars(fetched.out(), lib);
            deleteRecursively(cache);
            if (copied == 0) {
                throw new InstallException(s.id() + ": coursier resolved no jars for " + server.artifact());
            }
            return null;
        });
        return InstallCatalog.coursierLspCommand(dest.resolve("lib"), server);
    }

    /**
     * Copies each jar {@code cs fetch} printed (one path a line) into {@code lib}; a second jar with a name
     * already taken gets a numbered prefix, since the {@code lib/*} classpath needs them all. Returns how many.
     */
    static int copyFetchedJars(String fetchOutput, Path lib) throws IOException {
        int copied = 0;
        java.util.Set<String> names = new java.util.HashSet<>();
        for (String line : fetchOutput == null ? new String[0] : fetchOutput.split("\\R")) {
            String path = line.strip();
            if (!path.endsWith(".jar")) {
                continue; // coursier's progress lines go to stderr; anything else here is not a jar
            }
            Path jar = Path.of(path);
            if (!Files.isRegularFile(jar)) {
                continue;
            }
            String name = jar.getFileName().toString();
            String target = name;
            for (int i = 2; !names.add(target); i++) {
                target = i + "-" + name;
            }
            Files.copy(jar, lib.resolve(target));
            copied++;
        }
        return copied;
    }

    private static byte[] gunzip(byte[] data) throws IOException {
        try (var in = new java.util.zip.GZIPInputStream(new ByteArrayInputStream(data))) {
            return in.readAllBytes();
        }
    }

    /** Where pinned server distributions come from ({@link InstallCatalog#pinnedServer}); a test supplies its own. */
    private java.util.function.Function<String, java.util.Optional<InstallCatalog.PinnedServer>> pinnedServers =
            InstallCatalog::pinnedServer;

    /** Test seam for {@link #pinnedServers}: a recipe whose hash matches a test's own small archive. */
    void setPinnedServersForTest(
            java.util.function.Function<String, java.util.Optional<InstallCatalog.PinnedServer>> recipes) {
        this.pinnedServers = recipes;
    }

    /**
     * Streams a pinned server distribution for this platform to a file beside its install folder (a few hundred
     * MB: not into memory, nor into a {@code /tmp} that may be RAM), refuses it unless its SHA-256 is the
     * recipe's, unpacks it with the system {@code tar} and returns its launcher's command.
     */
    private String installPinnedServer(Step s, Path configDir) throws Exception {
        InstallCatalog.PinnedServer server = pinnedServers.apply(s.id()).orElse(null);
        if (server == null) {
            throw new InstallException(s.id() + ": no recipe");
        }
        InstallCatalog.PinnedAsset asset = server.assetFor(InstallCatalog.currentPlatform());
        if (asset == null) {
            throw new InstallException(s.id() + ": no prebuilt server for this OS/architecture");
        }
        Path dest = configDir.resolve(s.destSubpath());
        Files.createDirectories(dest.getParent());
        Path archive = Files.createTempFile(dest.getParent(), "." + s.id() + "-download", ".part");
        try {
            String actual = downloadTo(asset.url(), archive, server.maxBytes());
            if (!actual.equalsIgnoreCase(asset.sha256())) {
                throw new InstallException(s.id() + ": the download does not match its published checksum (expected "
                        + asset.sha256() + ", got " + actual + ") — not installed");
            }
            Path launcherInInstall = replaceInstall(dest, staged -> {
                ProcessRunner.Result r = ProcessRunner.runInUserLocale(
                        null, CMD_TIMEOUT, InstallCatalog.tarExtractArgvAuto(archive, staged));
                if (!r.ok()) {
                    throw new InstallException(s.id() + ": tar failed — " + r.message());
                }
                Path launcher = findBinary(staged, server.launcher(), false);
                if (launcher == null) {
                    throw new InstallException(s.id() + ": '" + server.launcher() + "' not found in the download");
                }
                makeExecutable(launcher);
                return staged.relativize(launcher);
            });
            Path launcherPath = Objects.requireNonNull(launcherInInstall, "the unpack step returns the launcher");
            return InstallCatalog.binaryCommand(dest.resolve(launcherPath.toString()), server.arguments());
        } finally {
            deleteQuietly(archive);
        }
    }

    /**
     * Downloads {@code url} into {@code file} (at most {@code maxBytes}) and returns its SHA-256, computed as it
     * arrives. The same URL rules as {@link #download}.
     */
    private String downloadTo(String url, Path file, long maxBytes) throws Exception {
        if (!PluginRegistry.isHttps(url)) {
            throw new InstallException("download url must be https: " + url);
        }
        if (!InstallCatalog.isTrustedDownloadUrl(url)) {
            throw new InstallException("download host is not one the install catalog uses: " + url);
        }
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(NET_TIMEOUT)
                .header("User-Agent", "Editora")
                .GET()
                .build();
        HttpResponse<InputStream> resp = client.get().send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() / 100 != 2) {
            resp.body().close();
            throw new InstallException("HTTP " + resp.statusCode() + " for " + url);
        }
        java.security.MessageDigest sha;
        try {
            sha = java.security.MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
        long total = 0;
        try (InputStream in = resp.body();
                java.io.OutputStream out = Files.newOutputStream(file)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > maxBytes) {
                    throw new InstallException("download larger than " + maxBytes + " bytes: " + url);
                }
                sha.update(buf, 0, n);
                out.write(buf, 0, n);
            }
        }
        return java.util.HexFormat.of().formatHex(sha.digest());
    }

    private static boolean hasTopLevelJar(Path dir) throws IOException {
        try (Stream<Path> list = Files.list(dir)) {
            return list.anyMatch(
                    p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".jar"));
        }
    }

    /** Prefix of the folder an install is unpacked into, beside its destination, until it has passed its checks. */
    static final String STAGING_PREFIX = ".installing-";

    /** Unpacks an install into {@code staged} and checks it; throws to reject it. */
    @FunctionalInterface
    private interface Unpack<T> {
        @Nullable
        T into(Path staged) throws Exception;
    }

    /**
     * Replaces the install at {@code dest} with what {@code unpack} produces — but only once {@code unpack}
     * has returned, i.e. the archive extracted completely and passed its checks.
     *
     * <p>Archives used to be extracted straight into {@code dest} after deleting what was there. An archive
     * that then turned out to be truncated, hostile or simply not the tool left a half-unpacked folder — one
     * holding {@code bin/jdtls} is detected as an installed server from then on — and had already destroyed
     * the working version. Now the work happens in a folder beside {@code dest} (same volume, so the swap is
     * a rename) and a failure leaves {@code dest} exactly as it was.
     */
    private static <T> @Nullable T replaceInstall(Path dest, Unpack<T> unpack) throws Exception {
        Path target = dest.toAbsolutePath();
        Path parent = target.getParent();
        if (parent == null) {
            throw new IOException("cannot install into a filesystem root: " + target);
        }
        Files.createDirectories(parent);
        String prefix = STAGING_PREFIX + target.getFileName() + "-";
        sweepAbandonedStaging(parent, prefix);
        // A short tag: the staging name is part of every extracted path, and Windows paths are finite.
        String tag = prefix
                + Integer.toHexString(
                        java.util.concurrent.ThreadLocalRandom.current().nextInt());
        Path staged = Files.createDirectory(parent.resolve(tag));
        try {
            T result = unpack.into(staged);
            swapIn(staged, target, parent.resolve(tag + ".old"));
            return result;
        } finally {
            deleteRecursively(staged);
        }
    }

    /** How long a staging folder may sit before it is taken for the remains of an install that was killed. */
    private static final Duration STAGING_ABANDONED_AFTER = Duration.ofDays(1);

    /**
     * Removes staging folders for this destination left by an install the app did not live to finish. Old
     * ones only: another window may be installing the same tool right now.
     */
    private static void sweepAbandonedStaging(Path parent, String prefix) {
        java.time.Instant cutoff = java.time.Instant.now().minus(STAGING_ABANDONED_AFTER);
        List<Path> abandoned;
        try (Stream<Path> list = Files.list(parent)) {
            abandoned = list.filter(p -> p.getFileName().toString().startsWith(prefix))
                    .filter(p -> {
                        try {
                            return Files.getLastModifiedTime(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                                    .toInstant()
                                    .isBefore(cutoff);
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .toList();
        } catch (IOException e) {
            return;
        }
        abandoned.forEach(InstallService::deleteRecursively);
    }

    /** Puts {@code staged} where {@code dest} is; the previous install comes back if that fails. */
    private static void swapIn(Path staged, Path dest, Path aside) throws IOException {
        boolean previous = false;
        if (Files.exists(dest, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            moveAside(dest, aside);
            previous = true;
        }
        try {
            Files.move(staged, dest);
        } catch (IOException e) {
            if (previous) {
                try {
                    Files.move(aside, dest);
                } catch (IOException ignored) {
                    // left under its staging name rather than deleted
                }
            }
            throw new IOException("could not replace the existing install at " + dest + ": " + e, e);
        }
        if (previous) {
            deleteRecursively(aside);
        }
    }

    /** How long a just-stopped server gets to let go of its files before the old install counts as in use. */
    static final int ASIDE_ATTEMPTS = 10;

    static final long ASIDE_WAIT_MILLIS = 300;

    /**
     * Renames the old install out of the way. On Windows a file another process holds open cannot be renamed:
     * a server stopped a moment ago may still be letting go, so it is tried again for a few seconds. Then the
     * install is refused and the old copy left whole. Deleting it in place instead, as before, could stop part
     * way through and leave neither copy (third review CT6).
     */
    static void moveAside(Path dest, Path aside) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < ASIDE_ATTEMPTS; attempt++) {
            try {
                Files.move(dest, aside);
                return;
            } catch (IOException busy) {
                last = busy;
                try {
                    Thread.sleep(ASIDE_WAIT_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        throw new IOException(
                "the existing install at " + dest + " is in use by another window or program; close it and try again",
                last);
    }

    /** Downloads + extracts a per-OS binary archive; returns the resolved server command (binary + suffix). */
    private String installArchive(Step s, Path configDir) throws Exception {
        InstallCatalog.ArchiveSpec spec = InstallCatalog.archiveSpec(s.id()).orElse(null);
        if (spec == null) {
            throw new InstallException(s.id() + ": no archive recipe");
        }
        String asset = spec.assetByPlatform().get(InstallCatalog.currentPlatform());
        if (asset == null) {
            throw new InstallException(s.id() + ": no prebuilt binary for this OS/architecture");
        }
        String json = new String(download(spec.apiUrl()), StandardCharsets.UTF_8);
        String url = pickArchiveUrl(json, asset);
        if (url == null) {
            throw new InstallException(s.id() + ": no download asset matched '" + asset + "'");
        }
        byte[] data = download(url);
        Path dest = configDir.resolve(s.destSubpath());
        Path binaryInInstall = replaceInstall(dest, staged -> {
            extractArchive(s, url, data, staged);
            Path binary = findBinary(staged, spec.binaryName(), spec.binaryPrefix());
            if (binary == null) {
                throw new InstallException(s.id() + ": '" + spec.binaryName() + "' not found after extraction");
            }
            makeExecutable(binary);
            return staged.relativize(binary);
        });
        Path binaryPath = Objects.requireNonNull(binaryInInstall, "the unpack step returns the binary");
        return InstallCatalog.binaryCommand(dest.resolve(binaryPath.toString()), spec.commandSuffix());
    }

    /** Extracts a downloaded binary archive (by the suffix of the URL it came from) into {@code dest}. */
    private void extractArchive(Step s, String url, byte[] data, Path dest) throws Exception {
        if (url.endsWith(".tar.gz") || url.endsWith(".tgz")) {
            Path tmp = Files.createTempFile("editora-dl", ".tar.gz");
            try {
                Files.write(tmp, data);
                ProcessRunner.Result r =
                        ProcessRunner.runInUserLocale(null, CMD_TIMEOUT, InstallCatalog.tarExtractArgv(tmp, dest));
                if (!r.ok()) {
                    throw new InstallException(s.id() + ": tar failed — " + r.message());
                }
            } finally {
                deleteQuietly(tmp);
            }
        } else if (url.endsWith(".tar.xz") || url.endsWith(".txz")) {
            // xz-compressed tarball (e.g. the typst CLI). System tar with -xf auto-detects xz (bsdtar/liblzma
            // on macOS, GNU tar + xz on Linux); the TAR prereq covers it.
            Path tmp = Files.createTempFile("editora-dl", ".tar.xz");
            try {
                Files.write(tmp, data);
                ProcessRunner.Result r =
                        ProcessRunner.runInUserLocale(null, CMD_TIMEOUT, InstallCatalog.tarExtractArgvAuto(tmp, dest));
                if (!r.ok()) {
                    throw new InstallException(s.id() + ": tar failed — " + r.message());
                }
            } finally {
                deleteQuietly(tmp);
            }
        } else {
            Unzip.extract(
                    new ByteArrayInputStream(data),
                    dest,
                    MAX_ARCHIVE_ENTRY_BYTES,
                    MAX_ARCHIVE_TOTAL_BYTES,
                    MAX_ARCHIVE_ENTRIES);
        }
    }

    /** Every archive suffix {@link #installArchive} can extract. The {@code .tar.xz}/{@code .txz} pair was
     *  missing here although its extraction branch existed, so the Typst CLI — published only as
     *  {@code .tar.xz} for Linux and macOS — never matched and "no download asset" was all an install said. */
    private static final java.util.regex.Pattern ARCHIVE_URL =
            java.util.regex.Pattern.compile("https://[^\"\\s]+?(?:\\.zip|\\.tar\\.gz|\\.tgz|\\.tar\\.xz|\\.txz)");

    /** The first archive URL (.zip/.tar.gz/.tgz/.tar.xz/.txz) in {@code json} that contains {@code substr} —
     *  skips the sibling {@code .sha256}/{@code .sig} checksum URLs that also contain the per-platform
     *  substring. */
    static @Nullable String pickArchiveUrl(String json, String substr) {
        java.util.regex.Matcher m = ARCHIVE_URL.matcher(json == null ? "" : json);
        while (m.find()) {
            if (m.group().contains(substr)) {
                return m.group();
            }
        }
        return null;
    }

    /** Walks {@code dest} for the LS binary (exact or {@code prefix} match; tolerates a Windows extension). */
    static @Nullable Path findBinary(Path dest, String name, boolean prefix) throws IOException {
        try (Stream<Path> s = Files.walk(dest)) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> linkStaysInside(dest, p))
                    .filter(p -> {
                        String f = p.getFileName().toString();
                        String base = f.endsWith(".exe") || f.endsWith(".bat") || f.endsWith(".cmd")
                                ? f.substring(0, f.lastIndexOf('.'))
                                : f;
                        return prefix ? base.startsWith(name) : base.equals(name);
                    })
                    .min(Comparator.comparingInt(p -> p.toString().length()))
                    .orElse(null);
        }
    }

    /**
     * False for a symbolic link that leads out of {@code root}. A tarball may carry links, and
     * {@code Files.isRegularFile} follows them: an entry named like the binary but linked to a file elsewhere
     * was taken for the binary, so that file was marked executable and the link became the server's command.
     * A link to another file of the same install (a stable name for a versioned binary) is fine.
     */
    private static boolean linkStaysInside(Path root, Path p) {
        if (!Files.isSymbolicLink(p)) {
            return true;
        }
        try {
            return p.toRealPath().startsWith(root.toRealPath());
        } catch (IOException e) {
            return false;
        }
    }

    /** Best-effort {@code chmod +x} (zip extraction drops the Unix exec bit); no-op on Windows. */
    static void makeExecutable(Path file) {
        try {
            var perms = new java.util.HashSet<>(Files.getPosixFilePermissions(file));
            perms.add(java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE);
            perms.add(java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE);
            perms.add(java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(file, perms);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows (no POSIX perms) or a transient FS error — the launcher resolves it regardless
        }
    }

    /**
     * Runs one package-manager command (npm/pip/gem/go/dotnet/rustup/composer). In the user's locale, not the
     * parse-stable C one: the output is only ever shown, and several of these (RubyGems, a JVM-based tool)
     * fail on non-ASCII package metadata or install paths when forced to ASCII.
     */
    private void runCommand(@Nullable List<String> argv, String id) throws InstallException {
        ProcessRunner.Result r = ProcessRunner.runInUserLocale(null, CMD_TIMEOUT, argv);
        if (!r.ok()) {
            throw new InstallException(id + ": " + (r.message().isBlank() ? "command failed" : r.message()));
        }
    }

    /**
     * Installs the headless Chrome mmdc renders with. Run from mmdc's own global dir (resolved via
     * {@code npm root -g}) so {@code npx puppeteer} picks mmdc's Puppeteer — and therefore the exact Chrome
     * version it expects — instead of a freshly-fetched latest that would leave a version mismatch.
     */
    private void installPuppeteerChrome(Step s) throws InstallException {
        Path cwd = null;
        try {
            ProcessRunner.Result root = ProcessRunner.run(null, CMD_TIMEOUT, List.of("npm", "root", "-g"));
            if (root.ok() && root.out() != null && !root.out().isBlank()) {
                Path mmdcDir =
                        Path.of(root.out().strip()).resolve("@mermaid-js").resolve("mermaid-cli");
                if (Files.isDirectory(mmdcDir)) {
                    cwd = mmdcDir;
                }
            }
        } catch (Exception ignore) {
            // fall back to the default working dir (npx fetches its own puppeteer)
        }
        ProcessRunner.Result r = ProcessRunner.runInUserLocale(cwd, CMD_TIMEOUT, s.npmPackages());
        if (!r.ok()) {
            throw new InstallException(s.id() + ": " + (r.message().isBlank() ? "command failed" : r.message()));
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
            // Replaces any previous copy, so a re-install is clean and the newest version wins — once the
            // new one has extracted completely and holds what it should.
            replaceInstall(dest, staged -> {
                ProcessRunner.Result r = ProcessRunner.runInUserLocale(
                        null, CMD_TIMEOUT, InstallCatalog.tarExtractArgv(tmpFile, staged));
                if (!r.ok()) {
                    throw new InstallException(s.id() + ": tar failed — " + r.message());
                }
                if (!verifyTarball(s, staged)) {
                    throw new InstallException(s.id() + ": expected files not found after extraction");
                }
                return null;
            });
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

    /**
     * Downloads the pinned Lombok jar ({@link InstallCatalog#LOMBOK}) into {@code dir}, refusing it unless its
     * SHA-256 is the catalog's, and returns where it is. Written beside its final name and moved, so a failed
     * download never leaves a half jar for jdtls to load.
     */
    public Path installLombok(Path dir) throws Exception {
        return installLombok(dir, InstallCatalog.LOMBOK);
    }

    /** As above, for the recipe {@code jar} (a test's own hash). */
    Path installLombok(Path dir, InstallCatalog.JvmLspJar jar) throws Exception {
        byte[] bytes = download(jar.url());
        String actual = InstallCatalog.sha256Hex(bytes);
        if (!actual.equalsIgnoreCase(jar.sha256())) {
            throw new InstallException(jar.fileName() + " does not match its published checksum (expected "
                    + jar.sha256() + ", got " + actual + ") — not installed");
        }
        Files.createDirectories(dir);
        Path target = dir.resolve(jar.fileName());
        Path part = dir.resolve(jar.fileName() + ".part");
        Files.write(part, bytes);
        Files.move(
                part,
                target,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        return target;
    }

    private byte[] download(@Nullable String url) throws Exception {
        if (!PluginRegistry.isHttps(url)) {
            throw new InstallException("download url must be https: " + url);
        }
        if (!InstallCatalog.isTrustedDownloadUrl(url)) {
            throw new InstallException("download host is not one the install catalog uses: " + url);
        }
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(NET_TIMEOUT)
                .header("User-Agent", "Editora")
                .GET()
                .build();
        HttpResponse<InputStream> resp = client.get().send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() / 100 != 2) {
            resp.body().close();
            throw new InstallException("HTTP " + resp.statusCode() + " for " + url);
        }
        try (InputStream in = resp.body()) {
            return PluginRegistry.readCapped(in, maxDownloadBytes);
        }
    }

    // --- helpers -------------------------------------------------------------------------------

    /** True if {@code exe} resolves to an executable on the augmented PATH (rewritten by resolveExecutable). */
    private static boolean onPath(String exe) {
        return !ProcessRunner.resolveExecutable(List.of(exe)).get(0).equals(exe);
    }

    private static @Nullable Path findJavaDebugJar(Path root) throws IOException {
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
