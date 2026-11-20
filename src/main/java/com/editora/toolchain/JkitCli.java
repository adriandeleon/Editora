package com.editora.toolchain;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.editora.process.ProcessRunner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jspecify.annotations.Nullable;

/**
 * Drives the {@code jkit} command (jkit-project.dev) through its {@code --json} output (schema version 1, jkit's
 * {@code docs/json.md}): one JSON document on stdout, also on failure, progress on stderr. Every method runs a
 * process: off the FX thread. Parsing is pure and unit-tested ({@link #parseInstall}, {@link #parseSearch}, …).
 */
public final class JkitCli {

    /** The {@code --json} schema this reads; a newer one is read as far as its fields still mean the same. */
    public static final int SCHEMA_VERSION = 1;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration QUICK = Duration.ofSeconds(30);
    private static final Duration NETWORK = Duration.ofMinutes(2);
    private static final Duration DOWNLOAD = Duration.ofMinutes(30);

    private JkitCli() {}

    /** A build that can be installed, from {@code jkit search --json}. */
    public record Available(
            String tool,
            String version,
            int major,
            @Nullable String vendor,
            boolean fx,
            boolean lts,
            boolean maintained,
            boolean preRelease,
            boolean installed) {

        /** {@code install jdk@21.0.9+10 temurin [--fx]}: the arguments that install exactly this build. */
        public List<String> installArgs() {
            List<String> args = new ArrayList<>(List.of("install", tool + "@" + version));
            if (vendor != null) {
                args.add(vendor);
            }
            if (fx) {
                args.add("--fx");
            }
            return List.copyOf(args);
        }
    }

    /** One release line from {@code jkit upgrade --json}. */
    public record UpgradeLine(
            String tool,
            int major,
            @Nullable String vendor,
            boolean fx,
            String from,
            @Nullable String to,
            String status,
            @Nullable String error) {

        public boolean available() {
            return "available".equals(status);
        }
    }

    /**
     * What a command answered: its document when it ran and printed one, else an error message (jkit's own, a
     * missing jkit, a jkit too old for {@code --json}). {@code exitCode} is the process's.
     */
    public record Outcome(@Nullable JsonNode doc, @Nullable String error, int exitCode, String stderr) {
        public boolean ok() {
            return error == null && doc != null;
        }
    }

    /**
     * The {@code jkit} program: on the augmented PATH, else where its packages install it ({@code /usr/bin},
     * {@code /usr/local/bin}, {@code ~/.local/bin}); null when there is none. Reads the disk: off the FX thread.
     */
    public static @Nullable String locate() {
        String onPath = ProcessRunner.resolveExecutable(List.of("jkit")).getFirst();
        if (!onPath.equals("jkit") && Files.isExecutable(Path.of(onPath))) {
            return onPath;
        }
        String home = System.getProperty("user.home", "");
        for (String dir : List.of("/usr/bin", "/usr/local/bin", home + "/.local/bin", home + "/bin")) {
            Path candidate = Path.of(dir, "jkit");
            if (Files.isExecutable(candidate)) {
                return candidate.toString();
            }
        }
        return null;
    }

    /** {@code jkit --version}'s version ({@code 0.2.0}), or null when it cannot be run. */
    public static @Nullable String version(String jkit) {
        ProcessRunner.Result r = ProcessRunner.run(null, QUICK, List.of(jkit, "--version"));
        if (!r.ok()) {
            return null;
        }
        String out = r.out().strip();
        int space = out.lastIndexOf(' ');
        return out.isEmpty() ? null : space >= 0 ? out.substring(space + 1) : out;
    }

    /** Runs {@code jkit <args> --json} and reads its document. */
    public static Outcome run(String jkit, List<String> args, Duration timeout) {
        return run(jkit, args, timeout, null, null);
    }

    /**
     * As {@link #run(String, List, Duration)}, handing each stderr line (progress, "downloading …") to
     * {@code live} as it comes; {@code cancel} stops it.
     */
    public static Outcome run(
            String jkit,
            List<String> args,
            Duration timeout,
            ProcessRunner.@Nullable LiveOutput live,
            ProcessRunner.@Nullable Cancellation cancel) {
        List<String> command = new ArrayList<>();
        command.add(jkit);
        command.addAll(args);
        command.add("--json");
        ProcessRunner.Result r = live == null
                ? ProcessRunner.run(null, timeout, command)
                : ProcessRunner.runLiveInUserLocale(
                        null,
                        timeout,
                        command,
                        Map.of(),
                        live,
                        cancel == null ? new ProcessRunner.Cancellation() : cancel);
        return outcome(r);
    }

    /** A finished command's outcome. Pure. */
    static Outcome outcome(ProcessRunner.Result r) {
        if (r.cancelled()) {
            return new Outcome(null, ProcessRunner.CANCELLED, r.exit(), r.err());
        }
        if (r.timedOut()) {
            return new Outcome(null, "jkit took too long and was stopped", r.exit(), r.err());
        }
        JsonNode doc = null;
        try {
            String out = r.out().strip();
            doc = out.isEmpty() ? null : JSON.readTree(out);
        } catch (Exception e) {
            doc = null;
        }
        if (doc == null || !doc.isObject() || !doc.has("schemaVersion")) {
            String err = r.err().strip();
            if (err.contains("Unknown option: '--json'") || err.contains("Unknown option: --json")) {
                return new Outcome(
                        null,
                        "this jkit is too old for Editora: update it to a version with --json",
                        r.exit(),
                        r.err());
            }
            String last = lastLine(err);
            return new Outcome(null, last.isEmpty() ? "jkit failed (exit " + r.exit() + ")" : last, r.exit(), r.err());
        }
        JsonNode error = doc.get("error");
        if (error != null && error.isObject()) {
            StringBuilder message = new StringBuilder(error.path("message").asText("jkit failed"));
            for (JsonNode hint : error.path("hints")) {
                message.append('\n').append(hint.asText());
            }
            return new Outcome(doc, message.toString(), r.exit(), r.err());
        }
        return new Outcome(doc, r.ok() ? null : "jkit failed (exit " + r.exit() + ")", r.exit(), r.err());
    }

    private static String lastLine(String text) {
        String[] lines = text.split("\\R");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].strip();
            if (!line.isEmpty()) {
                return line.startsWith("error: ") ? line.substring(7) : line;
            }
        }
        return "";
    }

    // --- commands -------------------------------------------------------------------------------------------

    /** {@code jkit list --json}: every install. */
    public static Outcome list(String jkit) {
        return run(jkit, List.of("list"), QUICK);
    }

    /** {@code jkit search <tool>[@version] [vendor] --json}. */
    public static Outcome search(String jkit, String tool, @Nullable String version, @Nullable String vendor) {
        List<String> args =
                new ArrayList<>(List.of("search", version == null || version.isBlank() ? tool : tool + "@" + version));
        if (vendor != null && !vendor.isBlank()) {
            args.add(vendor);
        }
        return run(jkit, args, NETWORK);
    }

    /** {@code jkit install …}: {@code installArgs} starts with {@code install}. */
    public static Outcome install(
            String jkit, List<String> installArgs, ProcessRunner.LiveOutput live, ProcessRunner.Cancellation cancel) {
        return run(jkit, installArgs, DOWNLOAD, live, cancel);
    }

    /** {@code jkit upgrade [--dry-run] --json}. */
    public static Outcome upgrade(
            String jkit,
            boolean dryRun,
            ProcessRunner.@Nullable LiveOutput live,
            ProcessRunner.@Nullable Cancellation cancel) {
        return run(
                jkit,
                dryRun ? List.of("upgrade", "--dry-run") : List.of("upgrade"),
                dryRun ? NETWORK : DOWNLOAD,
                live,
                cancel);
    }

    /** {@code jkit use <tool>@<version> [vendor] [--fx] --global --json}: makes an install the default. */
    public static Outcome useGlobal(String jkit, ToolInstall install) {
        return run(jkit, withTarget("use", install, "--global"), QUICK);
    }

    /** {@code jkit remove <tool>@<version> [vendor] [--fx] --json}. */
    public static Outcome remove(String jkit, ToolInstall install) {
        return run(jkit, withTarget("remove", install, null), QUICK);
    }

    /** {@code <command> <tool>@<exact version> [vendor] [--fx] [flag]} naming exactly {@code install}. Pure. */
    static List<String> withTarget(String command, ToolInstall install, @Nullable String flag) {
        List<String> args = new ArrayList<>(List.of(command, install.tool() + "@" + install.version()));
        if (install.vendor() != null) {
            args.add(install.vendor());
        }
        if (install.fx()) {
            args.add("--fx");
        }
        if (flag != null) {
            args.add(flag);
        }
        return List.copyOf(args);
    }

    // --- parsing --------------------------------------------------------------------------------------------

    /** An install object (list, install, use, remove): tool, version, vendor, fx, home. Pure. */
    public static @Nullable ToolInstall parseInstall(@Nullable JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        String tool = node.path("tool").asText("");
        String version = node.path("version").asText("");
        String home = node.path("home").asText("");
        if (tool.isEmpty() || version.isEmpty() || home.isEmpty()) {
            return null;
        }
        JsonNode vendor = node.get("vendor");
        return new ToolInstall(
                tool,
                version,
                vendor == null || vendor.isNull() ? null : vendor.asText(),
                node.path("fx").asBoolean(false),
                Path.of(home),
                ToolInstall.JKIT,
                node.path("id").asText(version));
    }

    /** {@code jkit list --json}'s installs. Pure. */
    public static List<ToolInstall> parseList(@Nullable JsonNode doc) {
        List<ToolInstall> out = new ArrayList<>();
        if (doc != null) {
            for (JsonNode node : doc.path("installed")) {
                ToolInstall i = parseInstall(node);
                if (i != null) {
                    out.add(i);
                }
            }
        }
        return out;
    }

    /** {@code jkit search --json}'s available builds. Pure. */
    public static List<Available> parseSearch(@Nullable JsonNode doc) {
        List<Available> out = new ArrayList<>();
        if (doc == null) {
            return out;
        }
        for (JsonNode n : doc.path("available")) {
            String version = n.path("version").asText("");
            if (version.isEmpty()) {
                continue;
            }
            JsonNode vendor = n.get("vendor");
            out.add(new Available(
                    n.path("tool").asText(doc.path("tool").asText("jdk")),
                    version,
                    n.path("major").asInt(0),
                    vendor == null || vendor.isNull() ? null : vendor.asText(),
                    n.path("fx").asBoolean(false),
                    n.path("lts").asBoolean(false),
                    n.path("maintained").asBoolean(true),
                    n.path("preRelease").asBoolean(false),
                    n.path("installed").asBoolean(false)));
        }
        return out;
    }

    /** {@code jkit upgrade --json}'s release lines. Pure. */
    public static List<UpgradeLine> parseUpgrade(@Nullable JsonNode doc) {
        List<UpgradeLine> out = new ArrayList<>();
        if (doc == null) {
            return out;
        }
        for (JsonNode n : doc.path("lines")) {
            JsonNode vendor = n.get("vendor");
            JsonNode to = n.get("to");
            JsonNode error = n.get("error");
            out.add(new UpgradeLine(
                    n.path("tool").asText(""),
                    n.path("major").asInt(0),
                    vendor == null || vendor.isNull() ? null : vendor.asText(),
                    n.path("fx").asBoolean(false),
                    n.path("from").asText(""),
                    to == null || to.isNull() ? null : to.asText(),
                    n.path("status").asText(""),
                    error == null || error.isNull()
                            ? null
                            : error.path("message").asText(error.asText())));
        }
        return out;
    }
}
