package com.editora.run;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The runtime a launched program runs under — "Java 25.0.3 (Temurin)", "Python 3.13.5", "Node.js 22.11.0" —
 * as the Run and Debug tool windows show it beside their state.
 *
 * <p>Pure, in two halves: {@link #probeFor} turns the command a run launches into the command that asks the
 * same executable for its version ({@code java -XshowSettings:properties -version}, {@code python3 --version},
 * …), and {@link #parse} reads that probe's output. The probe runs the very executable the launch names, in
 * the launch's folder and environment, so a selected JDK, a virtualenv's {@code python}, or a pyenv shim
 * reading {@code .python-version} reports what the program actually got rather than what is first on PATH.
 *
 * @param label the short chip text, e.g. {@code Java 25.0.3 (Temurin)}
 * @param detail the runtime's own version banner plus, where it says, its install location (the tooltip)
 */
public record RuntimeVersion(String label, String detail) {

    /** Runtimes {@link #probeFor} recognizes, each with the arguments that ask for its version. */
    public enum Kind {
        JAVA("-XshowSettings:properties", "-version"),
        PYTHON("--version"),
        NODE("--version"),
        BASH("--version"),
        ZSH("--version"),
        FISH("--version"),
        MAKE("--version"),
        GROOVY("--version"),
        /** {@code kotlinc}: the compiler that runs a Kotlin script, and compiles a Kotlin program. */
        KOTLIN("-version");

        final List<String> versionArgs;

        Kind(String... versionArgs) {
            this.versionArgs = List.of(versionArgs);
        }
    }

    /** A version probe: the {@link Kind} it asks about and its argv. */
    public record Probe(Kind kind, List<String> argv) {
        public Probe {
            argv = List.copyOf(argv);
        }
    }

    private static final Pattern PYTHON_NAME = Pattern.compile("(?:python|pypy)[0-9.]*|py");
    private static final Pattern PROPERTY = Pattern.compile("^\\s+([\\w.]+) = (.*)$");
    private static final Pattern JAVA_BANNER_VERSION = Pattern.compile("version \"([^\"]+)\"");
    private static final Pattern PYTHON_VERSION = Pattern.compile("Python (\\d+\\.\\d+(?:\\.\\d+)?\\S*)");
    private static final Pattern PYPY_VERSION = Pattern.compile("PyPy (\\d+\\.\\d+(?:\\.\\d+)?)");
    private static final Pattern NODE_VERSION = Pattern.compile("^v(\\d+\\.\\d+\\.\\d+\\S*)");
    private static final Pattern BASH_VERSION = Pattern.compile("bash, version (\\d+\\.\\d+(?:\\.\\d+)?)");
    private static final Pattern ZSH_VERSION = Pattern.compile("^zsh (\\d+\\.\\d+(?:\\.\\d+)?)");
    private static final Pattern FISH_VERSION = Pattern.compile("^fish, version (\\d+\\.\\d+(?:\\.\\d+)?)");
    private static final Pattern MAKE_VERSION = Pattern.compile("^GNU Make (\\d+\\.\\d+(?:\\.\\d+)?)");
    /** {@code Groovy Version: 5.1.3 JVM: 25.0.4 Vendor: Eclipse Adoptium OS: Linux} → 5.1.3 on Java 25.0.4. */
    private static final Pattern GROOVY_VERSION =
            Pattern.compile("^Groovy Version: (\\d+\\.\\d+(?:\\.\\d+)?\\S*)(?:\\s+JVM: (\\S+))?");
    /** The distribution in {@code java.vendor.version}: "Temurin-25.0.3+9" → "Temurin", "Zulu21.38+21-CA" → "Zulu". */
    private static final Pattern DISTRIBUTION = Pattern.compile("^([A-Za-z][A-Za-z ]*?)[\\s-]*(?:\\d.*)?$");

    /**
     * The command that asks {@code launch}'s runtime for its version, or {@code null} when the launched
     * executable is not one this knows (a plain {@code sh}, a native binary, an empty command). An npm
     * script reports the {@code node} beside its {@code npm} (or on PATH, for a bare {@code npm}).
     */
    public static Probe probeFor(List<String> launch) {
        if (launch == null
                || launch.isEmpty()
                || launch.get(0) == null
                || launch.get(0).isBlank()) {
            return null;
        }
        String executable = launch.get(0);
        String name = baseName(executable);
        Kind kind;
        switch (name) {
            case "java" -> kind = Kind.JAVA;
            case "node", "nodejs" -> kind = Kind.NODE;
            case "npm", "npx", "pnpm", "yarn" -> {
                kind = Kind.NODE;
                executable = sibling(executable, "node");
            }
            case "bash" -> kind = Kind.BASH;
            case "zsh" -> kind = Kind.ZSH;
            case "fish" -> kind = Kind.FISH;
            case "make", "gmake" -> kind = Kind.MAKE;
            case "groovy" -> kind = Kind.GROOVY;
            case "kotlinc", "kotlinc-jvm" -> kind = Kind.KOTLIN;
            default -> kind = PYTHON_NAME.matcher(name).matches() ? Kind.PYTHON : null;
        }
        if (kind == null) {
            return null;
        }
        List<String> argv = new ArrayList<>();
        argv.add(executable);
        argv.addAll(kind.versionArgs);
        return new Probe(kind, argv);
    }

    /**
     * Reads a probe's combined output (stderr then stdout: {@code java -version} and Python 2 print to
     * stderr). Returns {@code null} when it names no version — a missing executable, a non-GNU {@code make}.
     */
    public static RuntimeVersion parse(Probe probe, String output) {
        if (probe == null || output == null || output.isBlank()) {
            return null;
        }
        return switch (probe.kind()) {
            case JAVA -> parseJava(output);
            case PYTHON -> parsePython(output, probe.argv().get(0));
            case NODE -> simple(output, NODE_VERSION, "Node.js ", probe.argv().get(0));
            case BASH -> simple(output, BASH_VERSION, "Bash ", probe.argv().get(0));
            case ZSH -> simple(output, ZSH_VERSION, "Zsh ", probe.argv().get(0));
            case FISH -> simple(output, FISH_VERSION, "fish ", probe.argv().get(0));
            case MAKE -> simple(output, MAKE_VERSION, "GNU Make ", probe.argv().get(0));
            case GROOVY -> parseGroovy(output, probe.argv().get(0));
            case KOTLIN -> parseKotlin(output, probe.argv().get(0));
        };
    }

    /**
     * {@code -XshowSettings:properties -version}: the property dump gives the exact version, the distribution
     * ({@code java.vendor.version}) and the install ({@code java.home}); the banner after it is the tooltip.
     * A JVM that ignores the flag still prints the banner, whose quoted version is the fallback.
     */
    private static RuntimeVersion parseJava(String output) {
        String version = null;
        String vendorVersion = null;
        String home = null;
        List<String> banner = new ArrayList<>();
        for (String line : output.split("\\R")) {
            Matcher p = PROPERTY.matcher(line);
            if (p.matches()) {
                switch (p.group(1)) {
                    case "java.version" -> version = p.group(2).strip();
                    case "java.vendor.version" -> vendorVersion = p.group(2).strip();
                    case "java.home" -> home = p.group(2).strip();
                    default -> {}
                }
            } else if (!line.isBlank()
                    && !Character.isWhitespace(line.charAt(0))
                    && !line.startsWith("Property settings:")
                    && !line.startsWith("Picked up ")) {
                banner.add(line.strip());
            }
        }
        if (version == null) {
            Matcher m = JAVA_BANNER_VERSION.matcher(output);
            if (!m.find()) {
                return null;
            }
            version = m.group(1);
        }
        String distribution = distribution(vendorVersion);
        String label = "Java " + version + (distribution == null ? "" : " (" + distribution + ")");
        return new RuntimeVersion(label, detail(banner, home));
    }

    /** "Temurin-25.0.3+9" → "Temurin", "GraalVM CE 21.0.2+13.1" → "GraalVM CE"; null when there is none. */
    static String distribution(String vendorVersion) {
        if (vendorVersion == null || vendorVersion.isBlank()) {
            return null;
        }
        Matcher m = DISTRIBUTION.matcher(vendorVersion.strip());
        if (!m.matches()) {
            return null;
        }
        String name = m.group(1).strip();
        return name.isEmpty() || name.length() > 24 ? null : name;
    }

    /** {@code Python 3.13.5}; a PyPy says both, as {@code PyPy 7.3.17 (Python 3.10.14)}. */
    private static RuntimeVersion parsePython(String output, String executable) {
        Matcher python = PYTHON_VERSION.matcher(output);
        if (!python.find()) {
            return null;
        }
        Matcher pypy = PYPY_VERSION.matcher(output);
        String label = pypy.find()
                ? "PyPy " + pypy.group(1) + " (Python " + python.group(1) + ")"
                : "Python " + python.group(1);
        return new RuntimeVersion(label, detail(bannerLines(output), executable));
    }

    /** {@code Groovy 5.1.3 (Java 25.0.4)}: the JVM it runs on matters as much as Groovy's own version. */
    private static RuntimeVersion parseGroovy(String output, String executable) {
        for (String line : output.split("\\R")) {
            Matcher m = GROOVY_VERSION.matcher(line.strip());
            if (m.find()) {
                String label = "Groovy " + m.group(1) + (m.group(2) == null ? "" : " (Java " + m.group(2) + ")");
                return new RuntimeVersion(label, detail(bannerLines(output), executable));
            }
        }
        return null;
    }

    /** {@code Kotlin 2.4.21 (Java 25.0.4)}, from {@code info: kotlinc-jvm 2.4.21 (JRE 25.0.4+7-LTS)}. */
    private static RuntimeVersion parseKotlin(String output, String executable) {
        String[] version = KotlinRun.compilerVersion(output);
        if (version == null) {
            return null; // not the Kotlin compiler (JetBrains' toolchain CLI prints something else)
        }
        String label = "Kotlin " + version[0] + (version[1] == null ? "" : " (Java " + version[1] + ")");
        return new RuntimeVersion(label, detail(bannerLines(output), executable));
    }

    /** A runtime whose first matching line holds its version; the tooltip names the executable asked. */
    private static RuntimeVersion simple(String output, Pattern pattern, String prefix, String executable) {
        for (String line : output.split("\\R")) {
            Matcher m = pattern.matcher(line.strip());
            if (m.find()) {
                return new RuntimeVersion(prefix + m.group(1), detail(bannerLines(output), executable));
            }
        }
        return null;
    }

    /** At most the first three non-blank lines — enough for a banner, never a licence text. */
    private static List<String> bannerLines(String output) {
        List<String> lines = new ArrayList<>();
        for (String line : output.split("\\R")) {
            if (!line.isBlank()) {
                lines.add(line.strip());
                if (lines.size() == 3) {
                    break;
                }
            }
        }
        return lines;
    }

    private static String detail(List<String> banner, String location) {
        List<String> lines = new ArrayList<>(banner);
        if (location != null && !location.isBlank()) {
            lines.add(location);
        }
        return String.join("\n", lines);
    }

    /** The file name of {@code executable} without a Windows launcher extension, lower-cased. */
    static String baseName(String executable) {
        String s = executable.strip();
        int slash = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
        String name = s.substring(slash + 1).toLowerCase(Locale.ROOT);
        for (String ext : List.of(".exe", ".cmd", ".bat")) {
            if (name.endsWith(ext)) {
                return name.substring(0, name.length() - ext.length());
            }
        }
        return name;
    }

    /** {@code name} in {@code executable}'s folder, or the bare {@code name} for a bare command. */
    private static String sibling(String executable, String name) {
        int slash = Math.max(executable.lastIndexOf('/'), executable.lastIndexOf('\\'));
        return slash < 0 ? name : executable.substring(0, slash + 1) + name;
    }
}
