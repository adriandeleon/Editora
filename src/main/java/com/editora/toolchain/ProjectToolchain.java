package com.editora.toolchain;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * The JDK, Maven and Gradle a project asks for, matched to what is installed. A tool's request comes from the
 * project's own choice ({@code .editora/settings.json}'s {@code toolchain}, applied in a trusted folder), else
 * its {@code .sdkmanrc} ({@code java=}, {@code maven=}, {@code gradle=}); a tool the project says nothing about
 * is left to the user's Settings, as before. The installs are jkit's and SDKMAN's ({@link JkitInstalls},
 * {@link SdkmanInstalls}) and every other JDK the machine has.
 *
 * <p>{@link #scan} reads the disk (off the FX thread); {@link #resolve} only matches, so it is unit-tested
 * without one.
 */
public final class ProjectToolchain {

    /** The tools a project can name, in the order they are shown. */
    public static final List<String> TOOLS = List.of(ToolInstall.JDK, ToolInstall.MAVEN, ToolInstall.GRADLE);

    private ProjectToolchain() {}

    /** Where a tool's request came from. */
    public enum Origin {
        /** {@code .editora/settings.json}'s {@code toolchain} section. */
        PROJECT_SETTINGS,
        /** The project's {@code .sdkmanrc}. */
        SDKMANRC
    }

    /**
     * One tool's request and the install that answers it, or none ({@link #missing}): then it can be installed
     * with jkit ({@link ToolRequest#jkitInstallArgs}) or SDKMAN ({@code sdk env install}).
     */
    public record Choice(
            ToolRequest request, Origin origin, @Nullable ToolInstall install) {
        public boolean missing() {
            return install == null;
        }

        public String tool() {
            return request.tool();
        }
    }

    /** Every tool's choice; a null one is a tool the project says nothing about. */
    public record Resolution(
            @Nullable Choice jdk,
            @Nullable Choice maven,
            @Nullable Choice gradle) {

        public static final Resolution NONE = new Resolution(null, null, null);

        public @Nullable Choice of(String tool) {
            return switch (tool) {
                case ToolInstall.JDK -> jdk;
                case ToolInstall.MAVEN -> maven;
                case ToolInstall.GRADLE -> gradle;
                default -> null;
            };
        }

        /** The home of {@code tool}'s chosen install; null when the project names none or it is not installed. */
        public @Nullable String home(String tool) {
            Choice c = of(tool);
            return c == null || c.install() == null ? null : c.install().home().toString();
        }

        /** The choices that name a tool that is not installed. */
        public List<Choice> missing() {
            List<Choice> out = new ArrayList<>();
            for (String tool : TOOLS) {
                Choice c = of(tool);
                if (c != null && c.missing()) {
                    out.add(c);
                }
            }
            return out;
        }

        /** What this resolution applies, as one string: compared to tell whether anything changed. */
        public String signature() {
            StringBuilder sb = new StringBuilder();
            for (String tool : TOOLS) {
                Choice c = of(tool);
                sb.append(tool).append('=');
                if (c != null) {
                    sb.append(c.origin())
                            .append(':')
                            .append(c.request().raw())
                            .append("->")
                            .append(home(tool));
                }
                sb.append(';');
            }
            return sb.toString();
        }
    }

    /** What is installed: each tool's installs, jkit's and SDKMAN's before the machine's other JDKs. */
    public record Installs(List<ToolInstall> jdks, List<ToolInstall> mavens, List<ToolInstall> gradles) {

        public static final Installs NONE = new Installs(List.of(), List.of(), List.of());

        public List<ToolInstall> of(String tool) {
            return switch (tool) {
                case ToolInstall.JDK -> jdks;
                case ToolInstall.MAVEN -> mavens;
                case ToolInstall.GRADLE -> gradles;
                default -> List.of();
            };
        }
    }

    /**
     * Reads what is installed: jkit's and SDKMAN's JDKs, Maven and Gradle, then {@code otherJdkHomes} (the
     * machine's other JDKs, found by {@code JavaRuntimes.discover}) not already among them, read from their
     * {@code release} files. Reads the disk: off the FX thread.
     */
    public static Installs scan(Path jkitHome, Path sdkmanDir, List<String> otherJdkHomes) {
        com.editora.perf.BlockingSites.enter("ProjectToolchain.scan");
        List<ToolInstall> jdks = new ArrayList<>();
        jdks.addAll(JkitInstalls.installs(jkitHome, ToolInstall.JDK));
        jdks.addAll(SdkmanInstalls.installs(sdkmanDir, ToolInstall.JDK));
        Set<Path> seen = new java.util.HashSet<>();
        for (ToolInstall i : jdks) {
            seen.add(real(i.home()));
        }
        for (String home : otherJdkHomes) {
            Path dir = Path.of(home);
            if (seen.add(real(dir))) {
                ToolInstall other = fromRelease(dir);
                if (other != null) {
                    jdks.add(other);
                }
            }
        }
        List<ToolInstall> mavens = new ArrayList<>(JkitInstalls.installs(jkitHome, ToolInstall.MAVEN));
        mavens.addAll(SdkmanInstalls.installs(sdkmanDir, ToolInstall.MAVEN));
        List<ToolInstall> gradles = new ArrayList<>(JkitInstalls.installs(jkitHome, ToolInstall.GRADLE));
        gradles.addAll(SdkmanInstalls.installs(sdkmanDir, ToolInstall.GRADLE));
        return new Installs(List.copyOf(jdks), List.copyOf(mavens), List.copyOf(gradles));
    }

    private static Path real(Path p) {
        try {
            return p.toRealPath();
        } catch (IOException | RuntimeException e) {
            return p.toAbsolutePath().normalize();
        }
    }

    /** A JDK described by its {@code release} file, or null without a readable version. */
    static @Nullable ToolInstall fromRelease(Path home) {
        Path release = home.resolve("release");
        Map<String, String> values;
        try {
            values = releaseValues(Files.readString(release));
        } catch (IOException | RuntimeException e) {
            return null;
        }
        String version = values.getOrDefault("JAVA_VERSION", "");
        if (version.isEmpty()) {
            return null;
        }
        String modules = values.getOrDefault("MODULES", "");
        Path name = home.getFileName();
        return new ToolInstall(
                ToolInstall.JDK,
                version,
                Vendors.fromRelease(values.get("IMPLEMENTOR"), values.get("IMPLEMENTOR_VERSION")),
                modules.contains("javafx.base"),
                home.toAbsolutePath().normalize(),
                ToolInstall.OTHER,
                name == null ? version : name.toString());
    }

    /** A {@code release} file's {@code KEY="value"} lines. Pure. */
    static Map<String, String> releaseValues(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String line : text.split("\\R")) {
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String value = line.substring(eq + 1).strip();
            if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1);
            }
            out.put(line.substring(0, eq).strip(), value);
        }
        return out;
    }

    /**
     * Matches each tool's request to an install. {@code project} is the project file's {@code toolchain}
     * section (tool → request), already left empty in an untrusted folder; {@code sdkmanrc} the project's
     * {@code .sdkmanrc} entries. A request for a folder matches that folder when it holds the tool. Pure but for
     * that folder check.
     */
    public static Resolution resolve(Map<String, String> project, Map<String, String> sdkmanrc, Installs installs) {
        return new Resolution(
                choose(ToolInstall.JDK, project, sdkmanrc, installs),
                choose(ToolInstall.MAVEN, project, sdkmanrc, installs),
                choose(ToolInstall.GRADLE, project, sdkmanrc, installs));
    }

    private static @Nullable Choice choose(
            String tool, Map<String, String> project, Map<String, String> sdkmanrc, Installs installs) {
        String own = project.get(tool);
        Origin origin = Origin.PROJECT_SETTINGS;
        if (own == null || own.isBlank()) {
            own = sdkmanrc.get(SdkmanInstalls.candidate(tool));
            origin = Origin.SDKMANRC;
        }
        ToolRequest request = ToolRequest.parse(tool, own);
        if (request == null) {
            return null;
        }
        ToolInstall install = request.path() != null ? atPath(tool, request.path()) : request.best(installs.of(tool));
        return new Choice(request, origin, install);
    }

    /** The tool at {@code dir} when it is one ({@code bin/java}, {@code bin/mvn}, {@code bin/gradle}). */
    static @Nullable ToolInstall atPath(String tool, Path dir) {
        String exe = switch (tool) {
            case ToolInstall.JDK -> "java";
            case ToolInstall.MAVEN -> "mvn";
            default -> "gradle";
        };
        Path bin = dir.resolve("bin");
        if (!Files.isRegularFile(bin.resolve(exe))
                && !Files.isRegularFile(bin.resolve(exe + ".exe"))
                && !Files.isRegularFile(bin.resolve(exe + ".cmd"))
                && !Files.isRegularFile(bin.resolve(exe + ".bat"))) {
            return null;
        }
        if (ToolInstall.JDK.equals(tool)) {
            ToolInstall described = fromRelease(dir);
            if (described != null) {
                return described;
            }
        }
        Path name = dir.getFileName();
        String label = name == null ? dir.toString() : name.toString();
        return new ToolInstall(tool, label, null, false, dir.toAbsolutePath().normalize(), ToolInstall.OTHER, label);
    }

    /** The executable of Maven or Gradle install {@code home}: {@code bin/mvn}, {@code bin/gradle(.bat)}. */
    public static String executable(String tool, String home, boolean windows) {
        String exe = ToolInstall.MAVEN.equals(tool) ? "mvn" : "gradle";
        Path bin = Path.of(home).resolve("bin");
        if (windows) {
            return bin.resolve(exe + (ToolInstall.MAVEN.equals(tool) ? ".cmd" : ".bat"))
                    .toString();
        }
        return bin.resolve(exe).toString();
    }
}
