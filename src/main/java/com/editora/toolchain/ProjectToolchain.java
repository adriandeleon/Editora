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
 * The JDK, Maven, Gradle, sbt and GraalVM a project asks for, matched to what is installed. A tool's request comes
 * from the project's own choice ({@code .editora/settings.json}'s {@code toolchain}, applied in a trusted folder),
 * else its project files: as jkit reads them when jkit is installed ({@code jkit status}: {@code jkit.toml},
 * {@code .sdkmanrc}, {@code .tool-versions}, {@code .java-version}), else its {@code .sdkmanrc}. A tool the project
 * says nothing about is left to the user's Settings, as before. The installs are jkit's and SDKMAN's
 * ({@link JkitInstalls}, {@link SdkmanInstalls}) and every other JDK the machine has.
 *
 * <p>{@link #scan} reads the disk (off the FX thread); {@link #resolve} only matches, so it is unit-tested
 * without one.
 */
public final class ProjectToolchain {

    /** The tools a project can name, in the order they are shown. */
    public static final List<String> TOOLS =
            List.of(ToolInstall.JDK, ToolInstall.MAVEN, ToolInstall.GRADLE, ToolInstall.SBT, ToolInstall.GRAALVM);

    private ProjectToolchain() {}

    /** Where a tool's request came from. */
    public enum Origin {
        /** {@code .editora/settings.json}'s {@code toolchain} section. */
        PROJECT_SETTINGS,
        /** The project's {@code .sdkmanrc}, read by Editora (jkit is not installed). */
        SDKMANRC,
        /** A project file as jkit reads it ({@code jkit.toml}, {@code .sdkmanrc}, {@code .tool-versions}, …). */
        JKIT
    }

    /**
     * One request from a project file: the tool, what it asks for, where it is written ({@code file}, a name a
     * person reads) and the install jkit picked for it (null when jkit did not answer or has none installed).
     */
    public record FilePin(
            ToolRequest request,
            Origin origin,
            String file,
            @Nullable ToolInstall picked) {}

    /**
     * One tool's request and the install that answers it, or none ({@link #missing}): then it can be installed
     * with jkit ({@code jkit sync}, {@link ToolRequest#jkitInstallArgs}) or SDKMAN ({@code sdk env install}).
     * {@code file} names where the request is written.
     */
    public record Choice(
            ToolRequest request,
            Origin origin,
            String file,
            @Nullable ToolInstall install) {
        public boolean missing() {
            return install == null;
        }

        public String tool() {
            return request.tool();
        }
    }

    /** Every tool's choice; a tool absent from {@code choices} is one the project says nothing about. */
    public record Resolution(Map<String, Choice> choices) {

        public static final Resolution NONE = new Resolution(Map.of());

        public Resolution {
            choices = Map.copyOf(choices);
        }

        public @Nullable Choice of(String tool) {
            return choices.get(tool);
        }

        public @Nullable Choice jdk() {
            return of(ToolInstall.JDK);
        }

        public @Nullable Choice maven() {
            return of(ToolInstall.MAVEN);
        }

        public @Nullable Choice gradle() {
            return of(ToolInstall.GRADLE);
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
    public record Installs(
            List<ToolInstall> jdks, List<ToolInstall> mavens, List<ToolInstall> gradles, List<ToolInstall> sbts) {

        public static final Installs NONE = new Installs(List.of(), List.of(), List.of(), List.of());

        public Installs(List<ToolInstall> jdks, List<ToolInstall> mavens, List<ToolInstall> gradles) {
            this(jdks, mavens, gradles, List.of());
        }

        /** {@code tool}'s installs; GraalVM's are the GraalVM-based JDKs. */
        public List<ToolInstall> of(String tool) {
            return switch (tool) {
                case ToolInstall.JDK -> jdks;
                case ToolInstall.MAVEN -> mavens;
                case ToolInstall.GRADLE -> gradles;
                case ToolInstall.SBT -> sbts;
                case ToolInstall.GRAALVM ->
                    jdks.stream().filter(ToolInstall::graalvm).toList();
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
        List<ToolInstall> sbts = new ArrayList<>(JkitInstalls.installs(jkitHome, ToolInstall.SBT));
        sbts.addAll(SdkmanInstalls.installs(sdkmanDir, ToolInstall.SBT));
        return new Installs(List.copyOf(jdks), List.copyOf(mavens), List.copyOf(gradles), List.copyOf(sbts));
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
     * {@code .sdkmanrc} entries, read by Editora. A request for a folder matches that folder when it holds the
     * tool. Pure but for that folder check.
     */
    public static Resolution resolve(Map<String, String> project, Map<String, String> sdkmanrc, Installs installs) {
        return resolve(project, sdkmanrcPins(sdkmanrc), installs);
    }

    /**
     * As {@link #resolve(Map, Map, Installs)}, with the project files' requests already read ({@link #jkitPins}
     * from jkit, {@link #sdkmanrcPins} without it): a tool's own project setting wins over its file pin.
     */
    public static Resolution resolve(Map<String, String> project, List<FilePin> pins, Installs installs) {
        Map<String, Choice> out = new LinkedHashMap<>();
        for (String tool : TOOLS) {
            Choice c = choose(tool, project, pins, installs);
            if (c != null) {
                out.put(tool, c);
            }
        }
        return new Resolution(out);
    }

    /** {@code .sdkmanrc}'s {@code java=}, {@code maven=}, {@code gradle=} and {@code sbt=} as requests. Pure. */
    public static List<FilePin> sdkmanrcPins(Map<String, String> sdkmanrc) {
        List<FilePin> out = new ArrayList<>();
        for (String tool : List.of(ToolInstall.JDK, ToolInstall.MAVEN, ToolInstall.GRADLE, ToolInstall.SBT)) {
            ToolRequest r = ToolRequest.parse(tool, sdkmanrc.get(SdkmanInstalls.candidate(tool)));
            if (r != null) {
                out.add(new FilePin(r, Origin.SDKMANRC, SdkmanRc.FILE, null));
            }
        }
        return out;
    }

    /** What {@code jkit status --json} says the project pins, each with the install jkit picked. Pure. */
    public static List<FilePin> jkitPins(JkitCli.Status status) {
        List<FilePin> out = new ArrayList<>();
        String file = status.fileNames();
        for (JkitCli.StatusTool t : status.tools()) {
            JkitCli.PinSpec pin = t.pin();
            if (pin == null || !TOOLS.contains(t.tool())) {
                continue;
            }
            out.add(new FilePin(ToolRequest.of(pin), Origin.JKIT, file.isEmpty() ? "jkit" : file, t.pinInstalled()));
        }
        return out;
    }

    private static @Nullable Choice choose(
            String tool, Map<String, String> project, List<FilePin> pins, Installs installs) {
        ToolRequest own = ToolRequest.parse(tool, project.get(tool));
        if (own != null) {
            ToolInstall install = own.path() != null ? atPath(tool, own.path()) : own.best(installs.of(tool));
            return new Choice(own, Origin.PROJECT_SETTINGS, ".editora/settings.json", install);
        }
        for (FilePin pin : pins) {
            ToolRequest r = pin.request();
            if (!r.tool().equals(tool)) {
                continue;
            }
            ToolInstall install = pin.picked() != null
                    ? pin.picked()
                    : r.path() != null ? atPath(tool, r.path()) : r.best(installs.of(tool));
            return new Choice(r, pin.origin(), pin.file(), install);
        }
        return null;
    }

    /** The tool at {@code dir} when it is one ({@code bin/java}, {@code bin/mvn}, {@code bin/gradle}). */
    static @Nullable ToolInstall atPath(String tool, Path dir) {
        String exe = switch (tool) {
            case ToolInstall.JDK, ToolInstall.GRAALVM -> "java";
            case ToolInstall.MAVEN -> "mvn";
            case ToolInstall.SBT -> "sbt";
            default -> "gradle";
        };
        Path bin = dir.resolve("bin");
        if (!Files.isRegularFile(bin.resolve(exe))
                && !Files.isRegularFile(bin.resolve(exe + ".exe"))
                && !Files.isRegularFile(bin.resolve(exe + ".cmd"))
                && !Files.isRegularFile(bin.resolve(exe + ".bat"))) {
            return null;
        }
        if (ToolInstall.JDK.equals(tool) || ToolInstall.GRAALVM.equals(tool)) {
            ToolInstall described = fromRelease(dir);
            if (described != null) {
                return described;
            }
        }
        Path name = dir.getFileName();
        String label = name == null ? dir.toString() : name.toString();
        return new ToolInstall(tool, label, null, false, dir.toAbsolutePath().normalize(), ToolInstall.OTHER, label);
    }

    /** The executable of a Maven, Gradle or sbt install {@code home}: {@code bin/mvn}, {@code bin/gradle(.bat)}. */
    public static String executable(String tool, String home, boolean windows) {
        String exe = ToolInstall.MAVEN.equals(tool) ? "mvn" : ToolInstall.SBT.equals(tool) ? "sbt" : "gradle";
        Path bin = Path.of(home).resolve("bin");
        if (windows) {
            return bin.resolve(exe + (ToolInstall.MAVEN.equals(tool) ? ".cmd" : ".bat"))
                    .toString();
        }
        return bin.resolve(exe).toString();
    }
}
