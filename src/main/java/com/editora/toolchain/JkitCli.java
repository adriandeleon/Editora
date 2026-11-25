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
        String chosen = program.strip();
        if (!chosen.isEmpty()) {
            String expanded =
                    chosen.startsWith("~") ? System.getProperty("user.home", "~") + chosen.substring(1) : chosen;
            return Files.isExecutable(Path.of(expanded)) ? expanded : null;
        }
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

    /**
     * The jkit program Settings names (Build Tools ▸ jkit), blank to look for it on PATH. A plain value, never a
     * reference to a window or its settings: it is static, and a window must be released when it closes.
     */
    private static volatile String program = "";

    /** Uses {@code path} as the jkit program from now on (blank: look for it on PATH and where packages put it). */
    public static void useProgram(@Nullable String path) {
        program = path == null ? "" : path;
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
        return run(jkit, null, args, timeout, null, null);
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
        return run(jkit, null, args, timeout, live, cancel);
    }

    /**
     * As {@link #run(String, List, Duration, ProcessRunner.LiveOutput, ProcessRunner.Cancellation)}, in folder
     * {@code dir}: the project jkit finds its {@code jkit.toml} (or {@code .sdkmanrc}, …) from.
     */
    public static Outcome run(
            String jkit,
            @Nullable Path dir,
            List<String> args,
            Duration timeout,
            ProcessRunner.@Nullable LiveOutput live,
            ProcessRunner.@Nullable Cancellation cancel) {
        List<String> command = new ArrayList<>();
        command.add(jkit);
        command.addAll(args);
        command.add("--json");
        ProcessRunner.Result r = live == null
                ? ProcessRunner.run(dir, timeout, command)
                : ProcessRunner.runLiveInUserLocale(
                        dir,
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
        if (error != null && error.isObject() && tooOld(error.path("message").asText(""))) {
            return new Outcome(doc, TOO_OLD, r.exit(), r.err());
        }
        if (error != null && error.isObject()) {
            StringBuilder message = new StringBuilder(error.path("message").asText("jkit failed"));
            for (JsonNode hint : error.path("hints")) {
                message.append('\n').append(hint.asText());
            }
            return new Outcome(doc, message.toString(), r.exit(), r.err());
        }
        return new Outcome(doc, r.ok() ? null : "jkit failed (exit " + r.exit() + ")", r.exit(), r.err());
    }

    /** What Editora says when the installed jkit lacks a command it runs ({@code config} before 0.4.0, …). */
    public static final String TOO_OLD = "this jkit is too old for this: update jkit (0.4.0 or newer)";

    /** Whether jkit's error says it does not know the command at all (picocli's "Unmatched arguments"). */
    static boolean tooOld(String message) {
        return message.startsWith("Unmatched arguments from index 0");
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

    // --- the project ----------------------------------------------------------------------------------------

    /** {@code jkit status --json} in {@code dir}: what the project there pins, and what each pin picks. */
    public static Outcome status(String jkit, Path dir) {
        return run(jkit, dir, List.of("status"), QUICK, null, null);
    }

    /** {@code jkit env --dir <dir> --json}: the environment jkit gives a new process in {@code dir}. */
    public static Outcome env(String jkit, Path dir) {
        return run(jkit, dir, List.of("env", "--dir", dir.toString()), QUICK, null, null);
    }

    /**
     * {@code jkit pin <tool>@<version> [vendor] [--fx] [--here] --json} in {@code dir}: writes the pin to the
     * nearest {@code jkit.toml}, or to a new one in {@code dir} with {@code here}.
     */
    public static Outcome pin(String jkit, Path dir, PinSpec pin, boolean here) {
        return run(jkit, dir, pinArgs(pin, here), NETWORK, null, null);
    }

    /** {@code pin <tool>@<version> [vendor] [--fx] [--here]}. Pure. */
    static List<String> pinArgs(PinSpec pin, boolean here) {
        List<String> args =
                new ArrayList<>(List.of("pin", pin.tool() + "@" + (pin.version() == null ? "latest" : pin.version())));
        if (pin.vendor() != null) {
            args.add(pin.vendor());
        }
        if (pin.fx()) {
            args.add("--fx");
        }
        if (here) {
            args.add("--here");
        }
        return List.copyOf(args);
    }

    /** {@code jkit unpin <tool> --json} in {@code dir}. */
    public static Outcome unpin(String jkit, Path dir, String tool) {
        return run(jkit, dir, List.of("unpin", tool), QUICK, null, null);
    }

    /** {@code jkit sync [--dry-run] [--allow-unverified] --json} in {@code dir}: installs what the project pins. */
    public static Outcome sync(
            String jkit,
            Path dir,
            boolean dryRun,
            boolean allowUnverified,
            ProcessRunner.@Nullable LiveOutput live,
            ProcessRunner.@Nullable Cancellation cancel) {
        List<String> args = new ArrayList<>(List.of("sync"));
        if (dryRun) {
            args.add("--dry-run");
        }
        if (allowUnverified) {
            args.add("--allow-unverified");
        }
        return run(jkit, dir, args, dryRun ? NETWORK : DOWNLOAD, live, cancel);
    }

    // --- jkit itself ----------------------------------------------------------------------------------------

    /** {@code jkit doctor [--network] --json} in {@code dir} (the project it also checks), or anywhere. */
    public static Outcome doctor(String jkit, @Nullable Path dir, boolean network) {
        return run(jkit, dir, network ? List.of("doctor", "--network") : List.of("doctor"), NETWORK, null, null);
    }

    /** {@code jkit prune [--dry-run] --json}: removes builds a newer one of the same line replaced. */
    public static Outcome prune(String jkit, boolean dryRun) {
        return run(jkit, dryRun ? List.of("prune", "--dry-run") : List.of("prune"), QUICK);
    }

    /** {@code jkit config list --json}: jkit's own settings. */
    public static Outcome config(String jkit) {
        return run(jkit, List.of("config", "list"), QUICK);
    }

    /** {@code jkit config set <key> <value> --json}, or {@code unset} when {@code value} is null. */
    public static Outcome configSet(String jkit, String key, @Nullable String value) {
        return run(jkit, value == null ? List.of("config", "unset", key) : List.of("config", "set", key, value), QUICK);
    }

    /** {@code jkit self-update [--check] --json}. */
    public static Outcome selfUpdate(String jkit, boolean check) {
        return run(jkit, check ? List.of("self-update", "--check") : List.of("self-update"), DOWNLOAD);
    }

    /**
     * {@code jkit hook --install [--auto-switch]}: adds jkit's hook to the user's shell startup file. It prints
     * no JSON (it is about a shell), so its text output is the answer.
     */
    public static ProcessRunner.Result installHook(String jkit, boolean autoSwitch) {
        List<String> command = new ArrayList<>(List.of(jkit, "hook", "--install"));
        if (autoSwitch) {
            command.add("--auto-switch");
        }
        return ProcessRunner.run(null, QUICK, command);
    }

    // --- what the new commands answer -------------------------------------------------------------------------

    /** A pin: a version (null for the newest), a vendor for a JDK or GraalVM, and JavaFX. */
    public record PinSpec(
            String tool, @Nullable String version, @Nullable String vendor, boolean fx) {
        /** {@code 21 temurin JavaFX}, as a person reads it. */
        public String label() {
            StringBuilder sb = new StringBuilder(version == null ? "latest" : version);
            if (vendor != null) {
                sb.append(' ').append(vendor);
            }
            if (fx) {
                sb.append(" JavaFX");
            }
            return sb.toString();
        }
    }

    /**
     * One tool's line of {@code jkit status --json}: the project's pin (null without one) and the install it picks
     * (null when none is installed), what jkit would pick ({@code wantedSource}: shell, project, global or none),
     * the global default, and jkit's hint.
     */
    public record StatusTool(
            String tool,
            @Nullable PinSpec pin,
            @Nullable ToolInstall pinInstalled,
            String wantedSource,
            @Nullable String wantedHome,
            @Nullable String global,
            @Nullable String hint) {}

    /** {@code jkit status --json}: the project's folder and files (none without one), warnings, and each tool. */
    public record Status(
            @Nullable Path projectDir,
            List<String> files,
            List<String> warnings,
            @Nullable String projectError,
            @Nullable String projectSkipped,
            List<StatusTool> tools) {

        public @Nullable StatusTool of(String tool) {
            return tools.stream().filter(t -> t.tool().equals(tool)).findFirst().orElse(null);
        }

        /** The project files' names ({@code jkit.toml}, {@code .sdkmanrc}), for a message. */
        public String fileNames() {
            List<String> names = new ArrayList<>();
            for (String f : files) {
                Path name = Path.of(f).getFileName();
                names.add(name == null ? f : name.toString());
            }
            return String.join(", ", names);
        }
    }

    /** One check of {@code jkit doctor --json}: {@code level} ok, info, warn or fail; {@code fix} null when none. */
    public record DoctorCheck(
            String id,
            String level,
            String message,
            @Nullable String fix) {}

    /** One of jkit's settings ({@code jkit config list --json}). */
    public record ConfigSetting(
            String key,
            String value,
            String source,
            @Nullable String envVariable,
            @Nullable String defaultValue,
            String description) {}

    /** {@code jkit prune --json}: each build it removes (or would) and the bytes freed. */
    public record Prune(List<ToolInstall> builds, List<String> leftovers, List<String> cache, long bytes) {
        public boolean isEmpty() {
            return builds.isEmpty() && leftovers.isEmpty() && cache.isEmpty();
        }
    }

    /** {@code jkit sync --json}: each pin's status ({@code already-installed}, {@code installed}, {@code would-install}, {@code would-refuse}) and label. */
    public record SyncLine(String tool, String status, String label) {}

    /** {@code jkit self-update --json}: versions, status (up-to-date, available, updated, managed) and how it was installed. */
    public record SelfUpdate(
            String current,
            @Nullable String latest,
            String status,
            @Nullable String installedWith,
            @Nullable String command) {}

    private static @Nullable String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    /** A pin object ({@code version}, {@code vendor}, {@code fx}), or null. Pure. */
    static @Nullable PinSpec parsePin(String tool, @Nullable JsonNode n) {
        if (n == null || !n.isObject()) {
            return null;
        }
        return new PinSpec(
                tool, text(n, "version"), text(n, "vendor"), n.path("fx").asBoolean(false));
    }

    /** {@code jkit status --json}. Pure. */
    public static Status parseStatus(@Nullable JsonNode doc) {
        if (doc == null) {
            return new Status(null, List.of(), List.of(), null, null, List.of());
        }
        JsonNode project = doc.get("project");
        Path dir = null;
        List<String> files = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (project != null && project.isObject()) {
            String d = text(project, "dir");
            dir = d == null ? null : Path.of(d);
            project.path("files").forEach(f -> files.add(f.asText()));
            project.path("warnings").forEach(w -> warnings.add(w.asText()));
        }
        List<StatusTool> tools = new ArrayList<>();
        for (JsonNode t : doc.path("tools")) {
            String tool = t.path("tool").asText("");
            if (tool.isEmpty()) {
                continue;
            }
            JsonNode wanted = t.path("wanted");
            JsonNode global = t.get("global");
            tools.add(new StatusTool(
                    tool,
                    parsePin(tool, t.get("pin")),
                    parseInstall(t.get("pinInstalled")),
                    wanted.path("source").asText("none"),
                    text(wanted, "home"),
                    global == null || !global.isObject() ? null : text(global, "target"),
                    text(t, "hint")));
        }
        return new Status(
                dir,
                List.copyOf(files),
                List.copyOf(warnings),
                text(doc, "projectError"),
                text(doc, "projectSkipped"),
                List.copyOf(tools));
    }

    /** {@code jkit doctor --json}'s checks. Pure. */
    public static List<DoctorCheck> parseDoctor(@Nullable JsonNode doc) {
        List<DoctorCheck> out = new ArrayList<>();
        if (doc != null) {
            for (JsonNode c : doc.path("checks")) {
                out.add(new DoctorCheck(
                        c.path("id").asText(""),
                        c.path("level").asText("info"),
                        c.path("message").asText(""),
                        text(c, "fix")));
            }
        }
        return out;
    }

    /** {@code jkit config list --json}'s settings. Pure. */
    public static List<ConfigSetting> parseConfig(@Nullable JsonNode doc) {
        List<ConfigSetting> out = new ArrayList<>();
        if (doc != null) {
            for (JsonNode c : doc.path("settings")) {
                String key = c.path("key").asText("");
                if (!key.isEmpty()) {
                    out.add(new ConfigSetting(
                            key,
                            c.path("value").asText(""),
                            c.path("source").asText("default"),
                            text(c, "envVariable"),
                            text(c, "default"),
                            c.path("description").asText("")));
                }
            }
        }
        return out;
    }

    /** {@code jkit prune --json}. Pure. */
    public static Prune parsePrune(@Nullable JsonNode doc) {
        List<ToolInstall> builds = new ArrayList<>();
        List<String> leftovers = new ArrayList<>();
        List<String> cache = new ArrayList<>();
        if (doc == null) {
            return new Prune(builds, leftovers, cache, 0);
        }
        for (JsonNode b : doc.path("builds")) {
            ToolInstall i = parseInstall(b.get("install"));
            if (i != null) {
                builds.add(i);
            }
        }
        doc.path("leftovers").forEach(l -> leftovers.add(l.asText()));
        doc.path("cache").forEach(c -> cache.add(c.asText()));
        return new Prune(
                List.copyOf(builds),
                List.copyOf(leftovers),
                List.copyOf(cache),
                doc.path("bytes").asLong(0));
    }

    /** {@code jkit sync --json}'s tools. Pure. */
    public static List<SyncLine> parseSync(@Nullable JsonNode doc) {
        List<SyncLine> out = new ArrayList<>();
        if (doc != null) {
            for (JsonNode t : doc.path("tools")) {
                out.add(new SyncLine(
                        t.path("tool").asText(""),
                        t.path("status").asText(""),
                        t.path("label").asText("")));
            }
        }
        return out;
    }

    /** {@code jkit self-update --json}, or null without a document. Pure. */
    public static @Nullable SelfUpdate parseSelfUpdate(@Nullable JsonNode doc) {
        if (doc == null || !doc.has("status")) {
            return null;
        }
        return new SelfUpdate(
                doc.path("current").asText(""),
                text(doc, "latest"),
                doc.path("status").asText(""),
                text(doc, "installedWith"),
                text(doc, "command"));
    }

    /** {@code jkit env --json}'s variables ({@code JAVA_HOME}, …) and the folders to put first on PATH. Pure. */
    public static Map<String, String> parseEnv(@Nullable JsonNode doc) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        if (doc != null) {
            doc.path("variables")
                    .properties()
                    .forEach(e -> out.put(e.getKey(), e.getValue().asText()));
            String path = text(doc, "path");
            if (path != null) {
                out.put("PATH", path);
            }
        }
        return out;
    }
}
