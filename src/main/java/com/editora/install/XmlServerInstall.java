package com.editora.install;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * Editora's own copy of lemminx, the XML language server, and what an XML command left blank runs.
 *
 * <p>The installer used to write the downloaded binary's path into the XML command setting, as an override of a
 * default ({@code lemminx}) that is never on PATH under that name. "Reset to default", resetting all settings, or
 * importing settings from another OS then disconnected the copy still sitting in the config folder, and the only
 * remedy offered was a second download (review A9). Now the setting stays blank after an install, and blank — or
 * the bare default, or a path into Editora's own folder that no longer exists — means Editora's copy when there
 * is one, before anything on PATH.
 */
public final class XmlServerInstall {

    private XmlServerInstall() {}

    /** The command the registry runs for XML when the setting is blank. */
    private static final String DEFAULT_COMMAND = "lemminx";

    /** Where Editora installs lemminx under {@code configDir}. */
    public static Path folder(Path configDir) {
        return configDir.resolve(InstallCatalog.LEMMINX_DIR);
    }

    /**
     * Editora's lemminx under {@code configDir}, or null when it has none: the program the pinned install puts
     * there ({@code lemminx}, {@code lemminx.exe} on Windows), else the per-platform name an install from before
     * the pin kept ({@code lemminx-linux-x86_64}, …).
     */
    public static @Nullable Path ownCopy(@Nullable Path configDir) {
        if (configDir == null) {
            return null;
        }
        InstallCatalog.Platform platform = InstallCatalog.currentPlatform();
        Path dir = folder(configDir);
        Path pinned = dir.resolve(InstallCatalog.toolFileName(InstallCatalog.LEMMINX_STEP, platform));
        if (Files.isRegularFile(pinned)) {
            return pinned;
        }
        InstallCatalog.PinnedAsset asset = InstallCatalog.pinnedTool(InstallCatalog.LEMMINX_STEP)
                .map(t -> t.assetFor(platform))
                .orElse(null);
        String legacy = asset == null ? null : asset.member();
        if (legacy != null) {
            Path old = dir.resolve(legacy);
            if (Files.isRegularFile(old)) {
                return old;
            }
        }
        return null;
    }

    /**
     * The XML command that runs: {@code configured} as it is, unless it is blank, the bare default, or a path into
     * Editora's own lemminx folder that is gone (an update renamed the program, or the settings came from another
     * OS) — then Editora's copy, when there is one.
     */
    public static String effectiveCommand(@Nullable String configured, @Nullable Path configDir) {
        String raw = configured == null ? "" : configured.strip();
        if (configDir != null && isOwnJavaLemminx(raw, configDir)) {
            // What a fallback install stored before XML/pom review 3 (A21): the pom bundle's whole folder, which
            // put lemminx-maven into the plain XML server. Run the plain classpath out of the same bundle instead.
            String java = InstallService.javaLemminx(configDir);
            return java == null ? raw : java;
        }
        boolean useOwn = raw.isEmpty() || raw.equals(DEFAULT_COMMAND) || staleOwnPath(raw, configDir);
        if (!useOwn) {
            return raw;
        }
        Path own = ownCopy(configDir);
        if (own == null
                && configDir != null
                && Files.isRegularFile(folder(configDir).resolve(JAVA_FALLBACK))) {
            // The native build cannot run here and the install fell back to the Java lemminx: blank keeps it (A8).
            String java = InstallService.javaLemminx(configDir);
            return java == null ? raw : java;
        }
        return own == null ? raw : InstallCatalog.quoteCommandPath(own.toString());
    }

    /**
     * {@link #effectiveCommand(String, Path)}, a bare {@code java} (the Java lemminx a fallback left) run on {@code
     * jdkHome}, as a configured Java XML command is (A8).
     */
    public static String effectiveCommand(@Nullable String configured, @Nullable Path configDir, String jdkHome) {
        return com.editora.lsp.JvmServerCommand.withJdk(effectiveCommand(configured, configDir), jdkHome);
    }

    /**
     * The file in Editora's lemminx folder that says the native build cannot run on this system and XML runs on the
     * pom server's Java lemminx instead; it holds the reason. A later native install replaces the folder, and it.
     */
    static final String JAVA_FALLBACK = "java-fallback.txt";

    /**
     * Records that the native lemminx cannot run here ({@code reason}): removes the unrunnable program from {@code
     * dir} — the pinned name and the per-platform name an install from before the pin kept, which a blank command ran
     * again (XML/pom review 2, A8) — and writes {@link #JAVA_FALLBACK}. Best effort.
     */
    static void recordJavaFallback(Path dir, String reason) {
        try {
            Files.createDirectories(dir);
            InstallCatalog.Platform platform = InstallCatalog.currentPlatform();
            Files.deleteIfExists(dir.resolve(InstallCatalog.toolFileName(InstallCatalog.LEMMINX_STEP, platform)));
            String legacy = InstallCatalog.pinnedTool(InstallCatalog.LEMMINX_STEP)
                    .map(t -> t.assetFor(platform))
                    .map(InstallCatalog.PinnedAsset::member)
                    .orElse(null);
            if (legacy != null) {
                Files.deleteIfExists(dir.resolve(legacy));
            }
            Files.writeString(dir.resolve(JAVA_FALLBACK), reason == null ? "" : reason);
        } catch (java.io.IOException | InvalidPathException e) {
            // the command the install stores still runs the Java lemminx; only a later blank would not
        }
    }

    /**
     * Whether {@code configured} is not the user's own choice of server: blank, the bare default, or a path into
     * Editora's own lemminx folder (what an earlier install wrote into the setting). Such a command is replaced by
     * whatever Install puts there, so the install banner may offer Install for it.
     */
    public static boolean editorasOwn(@Nullable String configured, @Nullable Path configDir) {
        String raw = configured == null ? "" : configured.strip();
        return raw.isEmpty() || raw.equals(DEFAULT_COMMAND) || inOwnFolder(raw, configDir);
    }

    /**
     * Whether an installer's command for XML is Editora's native copy (a program in a {@code plugins/lsp/xml}
     * folder), which the setting need not hold — rather than another server it fell back to. Pure.
     */
    public static boolean isNativeInstall(@Nullable String installedCommand) {
        if (installedCommand == null || installedCommand.isBlank()) {
            return false;
        }
        List<String> argv = com.editora.run.ProgramArgs.commandLine(installedCommand);
        try {
            // the folder alone decides, so a path need not be absolute on this OS (a POSIX path on Windows)
            Path parent = argv.isEmpty() ? null : Path.of(argv.get(0)).getParent();
            return parent != null && parent.endsWith(Path.of(InstallCatalog.LEMMINX_DIR));
        } catch (InvalidPathException e) {
            return false;
        }
    }

    // --- the Java lemminx (XML/pom review 3, A21) ----------------------------------------------------------------

    /**
     * The jars plain lemminx needs, by artifact name, taken from the pom server's bundle when the native build cannot
     * run here: lemminx core, lsp4j and its JSON-RPC, gson, Xerces, the XML catalog resolver, Jing (RELAX NG) with
     * isorelax, remark and jsoup (documentation as Markdown), guava with failureaccess, and commons-lang3. Checked
     * against the real server: XSD, DTD and RELAX NG validation, completion and formatting work on exactly these.
     * Never lemminx-maven, Maven, Plexus, Sisu or the resolver: lemminx-maven joins any lemminx whose classpath holds
     * it (it is a ServiceLoader extension) and then resolves a pom and runs its plugins, trusted or not.
     */
    public static final List<String> PLAIN_LEMMINX_ARTIFACTS = List.of(
            "org.eclipse.lemminx",
            "org.eclipse.lsp4j",
            "org.eclipse.lsp4j.jsonrpc",
            "gson",
            "xercesImpl",
            "xml-resolver",
            "jing",
            "isorelax",
            "remark",
            "jsoup",
            "guava",
            "failureaccess",
            "commons-lang3");

    /**
     * The plain lemminx's jars in the pom server's bundle folder {@code mavenDir}, one per {@link
     * #PLAIN_LEMMINX_ARTIFACTS} entry ({@code <artifact>-<version>.jar}, the highest name when there are several), or
     * null when one is missing or the folder cannot be read. File lookups only.
     */
    public static @Nullable List<Path> plainLemminxJars(Path mavenDir) {
        List<String> names;
        try (java.util.stream.Stream<Path> list = Files.list(mavenDir)) {
            names = list.map(p -> p.getFileName().toString()).sorted().toList();
        } catch (java.io.IOException | RuntimeException e) {
            return null;
        }
        List<Path> jars = new java.util.ArrayList<>();
        for (String artifact : PLAIN_LEMMINX_ARTIFACTS) {
            String chosen = null;
            for (String name : names) {
                if (isJarOf(name, artifact)) {
                    chosen = name; // sorted: the last match is the highest version
                }
            }
            if (chosen == null) {
                return null;
            }
            jars.add(mavenDir.resolve(chosen));
        }
        return List.copyOf(jars);
    }

    /** Whether {@code fileName} is {@code <artifact>-<version>.jar} (a version starts with a digit). Pure. */
    static boolean isJarOf(String fileName, String artifact) {
        return fileName.endsWith(".jar")
                && fileName.length() > artifact.length() + 5
                && fileName.startsWith(artifact + "-")
                && Character.isDigit(fileName.charAt(artifact.length() + 1));
    }

    /**
     * {@code java -cp "<jar>:<jar>:…" org.eclipse.lemminx.XMLServerLauncher} over {@link #plainLemminxJars}, or null
     * when the bundle lacks one of them. A bare {@code java}, which the Maven JDK then replaces.
     */
    public static @Nullable String javaLemminxCommand(Path mavenDir) {
        List<Path> jars = plainLemminxJars(mavenDir);
        if (jars == null) {
            return null;
        }
        String cp = String.join(
                java.io.File.pathSeparator, jars.stream().map(Path::toString).toList());
        String quoted = cp.indexOf('"') < 0 ? '"' + cp + '"' : InstallCatalog.quoteCommandPath(cp);
        return "java -cp " + quoted + " " + InstallCatalog.LEMMINX_MAIN_CLASS;
    }

    /**
     * Whether {@code command} is a Java lemminx out of Editora's pom server bundle: {@code java -cp} whose every
     * classpath entry is in that bundle's folder (the one under {@code configDir}; any {@code plugins/lsp/maven}
     * folder when it is null), with lemminx's launcher as the main class — what an install that fell back to Java
     * stored. Pure but for path normalisation.
     */
    public static boolean isOwnJavaLemminx(@Nullable String command, @Nullable Path configDir) {
        if (command == null || command.isBlank()) {
            return false;
        }
        List<String> argv = com.editora.run.ProgramArgs.commandLine(command);
        if (argv.size() < 4 || !InstallCatalog.LEMMINX_MAIN_CLASS.equals(argv.get(argv.size() - 1))) {
            return false;
        }
        String cp = null;
        for (int i = 1; i + 1 < argv.size(); i++) {
            String a = argv.get(i);
            if (a.equals("-cp") || a.equals("-classpath") || a.equals("--class-path")) {
                cp = argv.get(i + 1);
            }
        }
        if (cp == null || cp.isBlank()) {
            return false;
        }
        try {
            Path bundle = configDir == null
                    ? null
                    : MavenServerInstall.folder(configDir).toAbsolutePath().normalize();
            for (String entry : cp.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator), -1)) {
                String e = entry.strip();
                boolean wildcard = e.endsWith("*");
                String path = wildcard ? e.substring(0, e.length() - 1) : e;
                Path folder = path.isEmpty()
                        ? null
                        : wildcard ? Path.of(path) : Path.of(path).getParent();
                if (folder == null || !inBundle(folder, bundle)) {
                    return false;
                }
            }
            return true;
        } catch (InvalidPathException e) {
            return false;
        }
    }

    private static boolean inBundle(Path folder, @Nullable Path bundle) {
        return bundle == null
                ? folder.endsWith(Path.of(InstallCatalog.LEMMINX_MAVEN_DIR))
                : folder.toAbsolutePath().normalize().equals(bundle);
    }

    private static boolean staleOwnPath(String raw, @Nullable Path configDir) {
        Path exe = executable(raw);
        return exe != null && inOwnFolder(raw, configDir) && !Files.exists(exe);
    }

    private static boolean inOwnFolder(String raw, @Nullable Path configDir) {
        Path exe = executable(raw);
        if (exe == null || configDir == null) {
            return false;
        }
        return exe.toAbsolutePath()
                .normalize()
                .startsWith(folder(configDir).toAbsolutePath().normalize());
    }

    /** The program {@code command} starts, when it is an absolute path; else null. */
    private static @Nullable Path executable(@Nullable String command) {
        if (command == null || command.isBlank()) {
            return null;
        }
        List<String> argv = com.editora.run.ProgramArgs.commandLine(command);
        if (argv.isEmpty()) {
            return null;
        }
        try {
            Path p = Path.of(argv.get(0));
            return p.isAbsolute() ? p : null;
        } catch (InvalidPathException e) {
            return null;
        }
    }
}
