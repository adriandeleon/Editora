package com.editora.install;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.editora.install.InstallCatalog.Lang;
import com.editora.install.InstallCatalog.Step;
import org.jspecify.annotations.Nullable;

/**
 * The language servers and tools Editora installs at an exact version, and whether an install holds that version.
 *
 * <p>An in-app install is idempotent on its folder, not on its version: before receipts, a server installed
 * under one pin stayed when a later Editora moved the pin, and nothing said so (jdtls was the first case, Java
 * review D8). Now every install of a pinned step leaves an {@link InstallReceipt}, and Doctor compares it with
 * the pin. An install from before receipts is read from what the release itself shows where it can be — the
 * version in a jar's or bundle's name, the version a tool prints — and is otherwise {@link Freshness#UNKNOWN}.
 *
 * <p>The tools Editora finds rather than installs have minimum versions instead: {@link VersionFloor}.
 * Everything here but the folder reads is pure.
 */
public final class PinnedVersions {

    private PinnedVersions() {}

    /**
     * One pinned install: its catalog step, the version Editora installs, and who installs it — a server
     * ({@code serverId}), a language bundle ({@code lang}) or a command-line tool ({@code tool}).
     */
    public record Pin(
            String stepId,
            String version,
            @Nullable String serverId,
            @Nullable Lang lang,
            boolean tool) {

        /** The catalog step that installs this pin. */
        public Step step() {
            // A language bundle's server (jdtls) is installed by its bundle, not as a server of its own.
            List<Step> steps = lang != null
                    ? InstallCatalog.steps(lang)
                    : serverId != null
                            ? InstallCatalog.serverInstall(serverId).orElse(List.of())
                            : InstallCatalog.toolInstall(stepId).orElse(List.of());
            return steps.stream()
                    .filter(s -> s.id().equals(stepId))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("no catalog step " + stepId));
        }

        /** Where the step installs, under the config dir. */
        public String destSubpath() {
            return step().destSubpath();
        }

        /** The version as shown to the user ({@code 1.63.0 (2026-10-19 build)} for jdtls). */
        public String label() {
            return PinnedVersions.label(stepId, version);
        }
    }

    /** How an install compares with its pin. */
    public enum Freshness {
        /** The pinned version, or a newer one. */
        CURRENT,
        /** Older than the pin: Editora is tested with something newer. */
        OUTDATED,
        /** No receipt, and nothing in the install says which release it is. */
        UNKNOWN
    }

    private static final List<Pin> PINS = List.of(
            new Pin("jdtls", InstallCatalog.JDTLS_VERSION + "." + InstallCatalog.JDTLS_BUILD, "java", Lang.JAVA, false),
            new Pin("js-debug", InstallCatalog.JS_DEBUG_VERSION, null, Lang.JAVASCRIPT, false),
            new Pin(InstallCatalog.LEMMINX_MAVEN_STEP, InstallCatalog.LEMMINX_MAVEN_VERSION, "maven-pom", null, false),
            new Pin("gls", InstallCatalog.GLS_VERSION, "groovy", null, false),
            new Pin("metals", InstallCatalog.METALS_VERSION, "scala", null, false),
            new Pin(InstallCatalog.KOTLIN_LSP_STEP, InstallCatalog.KOTLIN_LSP_VERSION, "kotlin", null, false),
            new Pin(InstallCatalog.CLOJURE_LSP_ID, InstallCatalog.CLOJURE_LSP_VERSION, "clojure", null, false),
            new Pin("shellcheck", InstallCatalog.SHELLCHECK_VERSION, null, null, true),
            new Pin("shfmt", InstallCatalog.SHFMT_VERSION, null, null, true));

    /** Every pinned install, in catalog order. */
    public static List<Pin> all() {
        return PINS;
    }

    /** The pin of catalog step {@code stepId}, or empty for a step that installs whatever is latest. */
    public static Optional<Pin> forStep(@Nullable String stepId) {
        return PINS.stream().filter(p -> p.stepId().equals(stepId)).findFirst();
    }

    /** The pin of the server {@code serverId} Editora installs ({@code java} → jdtls), or empty. */
    public static Optional<Pin> forServer(@Nullable String serverId) {
        return PINS.stream()
                .filter(p -> p.serverId() != null && p.serverId().equals(serverId))
                .findFirst();
    }

    /** How {@code installed} (null: unknown) compares with {@code pin}. Pure. */
    public static Freshness freshness(Pin pin, @Nullable String installed) {
        if (installed == null || installed.isBlank()) {
            return Freshness.UNKNOWN;
        }
        return Versions.compare(installed, pin.version()) < 0 ? Freshness.OUTDATED : Freshness.CURRENT;
    }

    /**
     * The version installed in {@code dir}, the pin's install folder: its receipt's, else what the files show
     * ({@link #fingerprint}), else null. A receipt another step wrote does not count.
     */
    public static @Nullable String installedVersion(Pin pin, Path dir) {
        InstallReceipt receipt = InstallReceipt.read(dir);
        if (receipt != null && receipt.id().equals(pin.stepId())) {
            return receipt.version();
        }
        return fingerprint(pin.stepId(), dir);
    }

    /**
     * {@link #installedVersion} of the pin's folder under {@code configDir} compared with the pin, or null when
     * Editora has installed nothing there. Reads files only (no process), so it is cheap enough for an install's
     * "is this already here?" check.
     */
    public static @Nullable Freshness installedFreshness(Pin pin, @Nullable Path configDir) {
        if (configDir == null) {
            return null;
        }
        Path dir = configDir.resolve(pin.destSubpath());
        if (!Files.isDirectory(dir)) {
            return null;
        }
        return freshness(pin, installedVersion(pin, dir));
    }

    private static final Pattern LEMMINX_MAVEN_JAR = Pattern.compile("lemminx-maven-(\\d[\\w.-]*)\\.jar");
    private static final Pattern GLS_JAR = Pattern.compile("gls-(\\d[\\w.-]*)\\.jar");
    private static final Pattern METALS_JAR = Pattern.compile("metals_2\\.13-(\\d[\\w.-]*)\\.jar");

    /** vscode-js-debug's bundle names itself and its version side by side: {@code "js-debug",Uu="1.117.0"}. */
    private static final Pattern JS_DEBUG_BUNDLE = Pattern.compile("\"js-debug\",[\\w$]+=\"(\\d+(?:\\.\\d+)+)\"");

    /**
     * The version an install shows without a receipt, or null: the core bundle's name for jdtls
     * ({@code location} may be its launcher), a jar's name for the Maven, Groovy and Scala servers, the bundle's
     * own constant for vscode-js-debug. The Kotlin server and the native tools say nothing in their files.
     */
    public static @Nullable String fingerprint(String stepId, Path location) {
        try {
            return switch (stepId) {
                case "jdtls" -> {
                    JdtlsVersion v = JdtlsVersion.installed(location);
                    yield v == null ? null : v.major() + "." + v.minor() + "." + v.patch() + "." + v.build();
                }
                case InstallCatalog.LEMMINX_MAVEN_STEP -> jarVersion(location, LEMMINX_MAVEN_JAR);
                case "gls" -> jarVersion(location, GLS_JAR);
                case "metals" -> jarVersion(location.resolve("lib"), METALS_JAR);
                case "js-debug" -> jsDebugVersion(location.resolve("js-debug/src/dapDebugServer.js"));
                default -> null;
            };
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static final Pattern CLOJURE_LSP_LINE = Pattern.compile("clojure-lsp\\s+(\\S+)");
    private static final Pattern SHELLCHECK_LINE = Pattern.compile("(?m)^version:\\s*(\\S+)");

    /**
     * The version in what a pinned tool printed for {@code --version}, or null: {@code clojure-lsp
     * 2026.10.15-11.42.38}, ShellCheck's {@code version: 0.11.0} line, shfmt's bare {@code v3.14.1}. Pure.
     */
    public static @Nullable String fromVersionOutput(String stepId, @Nullable String output) {
        if (output == null || output.isBlank()) {
            return null;
        }
        Matcher m = switch (stepId) {
            case InstallCatalog.CLOJURE_LSP_ID -> CLOJURE_LSP_LINE.matcher(output);
            case "shellcheck" -> SHELLCHECK_LINE.matcher(output);
            default -> null;
        };
        if (m != null) {
            return m.find() ? m.group(1) : null;
        }
        return "shfmt".equals(stepId) ? Versions.firstNumber(output) : null;
    }

    /** {@code version} as shown to the user: jdtls's build stamp as a date, everything else as it is. Pure. */
    public static String label(String stepId, String version) {
        if ("jdtls".equals(stepId)) {
            String[] parts = version.split("\\.");
            if (parts.length == 4) {
                try {
                    return new JdtlsVersion(
                                    Integer.parseInt(parts[0]),
                                    Integer.parseInt(parts[1]),
                                    Integer.parseInt(parts[2]),
                                    Long.parseLong(parts[3]))
                            .label();
                } catch (NumberFormatException e) {
                    return version;
                }
            }
        }
        return version;
    }

    /**
     * Whether any of {@code paths} (a launch command's tokens, a resolved executable) lies inside {@code dir} — that
     * is, whether what runs is the copy Editora installed there. A classpath's trailing {@code /*} is ignored. Pure
     * but for resolving symbolic links of paths that exist.
     */
    public static boolean anyInside(List<String> paths, @Nullable Path dir) {
        if (dir == null) {
            return false;
        }
        Path root = canonical(dir);
        for (String token : paths) {
            if (token == null || token.isBlank()) {
                continue;
            }
            String t = token.strip();
            if (t.endsWith("/*") || t.endsWith("\\*")) {
                t = t.substring(0, t.length() - 2);
            }
            try {
                Path p = Path.of(t);
                if (p.isAbsolute() && canonical(p).startsWith(root)) {
                    return true;
                }
            } catch (InvalidPathException e) {
                // not a path (a JVM option, a class name)
            }
        }
        return false;
    }

    private static Path canonical(Path p) {
        Path abs = p.toAbsolutePath().normalize();
        try {
            return Files.exists(abs) ? abs.toRealPath() : abs;
        } catch (IOException e) {
            return abs;
        }
    }

    private static @Nullable String jarVersion(Path dir, Pattern jar) throws IOException {
        if (!Files.isDirectory(dir)) {
            return null;
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(f -> jar.matcher(f.getFileName().toString()))
                    .filter(Matcher::matches)
                    .map(m -> m.group(1))
                    .max(Versions::compare)
                    .orElse(null);
        }
    }

    private static @Nullable String jsDebugVersion(Path bundle) throws IOException {
        if (!Files.isRegularFile(bundle) || Files.size(bundle) > 64L * 1024 * 1024) {
            return null;
        }
        Matcher m = JS_DEBUG_BUNDLE.matcher(Files.readString(bundle, StandardCharsets.ISO_8859_1));
        return m.find() ? m.group(1) : null;
    }
}
