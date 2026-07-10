package com.editora.build;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;

import com.editora.config.Settings;
import com.editora.maven.PomModel;
import com.editora.maven.PomParser;
import com.editora.process.ProcessRunner;

/**
 * The set of build tools Editora integrates as a toolbar button + actions popup + streaming console (see
 * {@code ui.BuildCoordinator}). Each constant is self-describing: its marker file(s), the argv strategy for
 * launching it, its Settings enable/command accessors, its console {@link OutputStyle}, and how to parse its
 * marker file into a {@link BuildActionsProvider} (+ a short display label). Adding a tool (Cargo/Go/Gradle)
 * is a new constant here plus a provider + an icon + Settings fields + i18n — no {@code BuildCoordinator} or
 * {@code MainController} change. Pure logic (parsing/executable resolution); the UI concerns (icon, localized
 * labels, tool-window wiring) live in {@code ui}.
 */
public enum BuildTool {

    /** Apache Maven — {@code pom.xml}; prefers the project's own {@code mvnw} wrapper, else {@code mvn}. */
    MAVEN(
            "maven",
            "Maven",
            "mvn",
            List.of("pom.xml"),
            "view.toggleMavenSupport",
            Settings::isMavenSupport,
            Settings::setMavenSupport,
            Settings::getMavenCommand,
            Settings::setMavenCommand,
            OutputStyle.maven()) {
        @Override
        public Detected parse(Path root) throws Exception {
            PomModel model = PomParser.parseFile(root.resolve("pom.xml"));
            return new Detected(new MavenActionsProvider(model), model.artifactId());
        }

        @Override
        public List<String> executable(Path root, boolean isWindows, String override) {
            List<String> wrapper = List.of();
            if (isWindows) {
                if (Files.isRegularFile(root.resolve("mvnw.cmd"))) {
                    wrapper = List.of("mvnw.cmd");
                }
            } else if (Files.isRegularFile(root.resolve("mvnw"))) {
                wrapper = List.of("./mvnw");
            }
            return BuildExecutable.resolve(wrapper, override, "mvn");
        }
    },

    /** Node — {@code package.json}; runs the detected package manager (npm/yarn/pnpm/bun), no wrapper. */
    NPM(
            "npm",
            "npm",
            "npm",
            List.of("package.json"),
            "view.toggleNpmSupport",
            Settings::isNpmSupport,
            Settings::setNpmSupport,
            Settings::getNpmCommand,
            Settings::setNpmCommand,
            OutputStyle.passthrough()) {
        @Override
        public Detected parse(Path root) throws Exception {
            NpmProject project = NpmProject.parse(Files.readString(root.resolve("package.json")));
            return new Detected(
                    new NpmActionsProvider(project.scripts(), npmPackageManager(root, project)), project.name());
        }

        @Override
        public List<String> executable(Path root, boolean isWindows, String override) {
            String pm;
            try {
                pm = npmPackageManager(root, NpmProject.parse(Files.readString(root.resolve("package.json"))));
            } catch (Exception e) {
                pm = "npm"; // unreadable/removed package.json — fall back to the plain default command
            }
            return BuildExecutable.resolve(List.of(), override, pm);
        }
    },

    /** Rust — {@code Cargo.toml}; runs {@code cargo} (no wrapper), with a {@code --release} toggle. */
    CARGO(
            "cargo",
            "Cargo",
            "cargo",
            List.of("Cargo.toml"),
            "view.toggleCargoSupport",
            Settings::isCargoSupport,
            Settings::setCargoSupport,
            Settings::getCargoCommand,
            Settings::setCargoCommand,
            OutputStyle.passthrough()) {
        @Override
        public Detected parse(Path root) throws Exception {
            CargoProject project = CargoProject.parse(Files.readString(root.resolve("Cargo.toml")));
            return new Detected(
                    new CargoActionsProvider(project.binNames(), project.exampleNames()), project.packageName());
        }

        @Override
        public List<String> executable(Path root, boolean isWindows, String override) {
            return BuildExecutable.resolve(List.of(), override, "cargo");
        }
    },

    /** Go — {@code go.mod}/{@code go.work}; runs {@code go} (no wrapper), static subcommands. */
    GO(
            "go",
            "Go",
            "go",
            List.of("go.mod", "go.work"),
            "view.toggleGoSupport",
            Settings::isGoSupport,
            Settings::setGoSupport,
            Settings::getGoCommand,
            Settings::setGoCommand,
            OutputStyle.passthrough()) {
        @Override
        public Detected parse(Path root) throws Exception {
            Path goMod = root.resolve("go.mod");
            String label = Files.isRegularFile(goMod)
                    ? GoProject.parse(Files.readString(goMod)).moduleName()
                    : null; // a go.work workspace has no root module line
            return new Detected(new GoActionsProvider(), label);
        }

        @Override
        public List<String> executable(Path root, boolean isWindows, String override) {
            return BuildExecutable.resolve(List.of(), override, "go");
        }
    },

    /** Gradle — {@code build.gradle(.kts)}/{@code settings.gradle(.kts)}; prefers the {@code gradlew} wrapper,
     *  else {@code gradle}. Static common tasks + an on-demand {@code gradle tasks --all} enumeration. */
    GRADLE(
            "gradle",
            "Gradle",
            "gradle",
            List.of("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts"),
            "view.toggleGradleSupport",
            Settings::isGradleSupport,
            Settings::setGradleSupport,
            Settings::getGradleCommand,
            Settings::setGradleCommand,
            OutputStyle.passthrough()) {
        @Override
        public Detected parse(Path root) throws Exception {
            // The DSL can't be statically parsed; the provider is a static common-task set + on-demand load.
            Path name = root.getFileName();
            return new Detected(new GradleActionsProvider(), name == null ? null : name.toString());
        }

        @Override
        public List<String> executable(Path root, boolean isWindows, String override) {
            List<String> wrapper = List.of();
            if (isWindows) {
                if (Files.isRegularFile(root.resolve("gradlew.bat"))) {
                    wrapper = List.of("gradlew.bat");
                }
            } else if (Files.isRegularFile(root.resolve("gradlew"))) {
                wrapper = List.of("./gradlew");
            }
            return BuildExecutable.resolve(wrapper, override, "gradle");
        }

        @Override
        public Optional<String> taskLoadLabel() {
            return Optional.of("buildpopup.loadTasks");
        }

        @Override
        public List<String> loadTasks(Path root, boolean isWindows, String override) throws Exception {
            List<String> argv = new ArrayList<>(executable(root, isWindows, override));
            argv.add("tasks");
            argv.add("--all");
            ProcessRunner.Result result = ProcessRunner.run(root, TASK_LOAD_TIMEOUT, argv);
            return GradleTasks.parse(result.out());
        }
    };

    /**
     * A tool detected under a project root: the actions {@link BuildActionsProvider} it feeds the popup, plus a
     * short {@code label} (Maven's artifactId / npm's package name, may be {@code null}) for the Settings
     * "Found: …" status row.
     */
    public record Detected(BuildActionsProvider provider, String label) {}

    private final String id;
    private final String displayName;
    private final String commandExample;
    private final List<String> markers;
    private final String toggleCommandId;
    private final Predicate<Settings> enabledGetter;
    private final BiConsumer<Settings, Boolean> enabledSetter;
    private final Function<Settings, String> commandGetter;
    private final BiConsumer<Settings, String> commandSetter;
    private final OutputStyle outputStyle;

    BuildTool(
            String id,
            String displayName,
            String commandExample,
            List<String> markers,
            String toggleCommandId,
            Predicate<Settings> enabledGetter,
            BiConsumer<Settings, Boolean> enabledSetter,
            Function<Settings, String> commandGetter,
            BiConsumer<Settings, String> commandSetter,
            OutputStyle outputStyle) {
        this.id = id;
        this.displayName = displayName;
        this.commandExample = commandExample;
        this.markers = List.copyOf(markers);
        this.toggleCommandId = toggleCommandId;
        this.enabledGetter = enabledGetter;
        this.enabledSetter = enabledSetter;
        this.commandGetter = commandGetter;
        this.commandSetter = commandSetter;
        this.outputStyle = outputStyle;
    }

    /** Parses the tool's marker file under {@code root} into a provider + label; throws if it's malformed. */
    public abstract Detected parse(Path root) throws Exception;

    /** The argv prefix to launch the tool from {@code root} (project wrapper, else the Settings override, else
     *  the tool's default command). {@code override} is the Settings command override (blank = none). */
    public abstract List<String> executable(Path root, boolean isWindows, String override);

    /** For a DSL-based tool whose full task list can't be parsed up front (Gradle): the i18n key of the
     *  popup's on-demand "Load all tasks…" action. Empty for statically-parsed tools (no such action shown). */
    public Optional<String> taskLoadLabel() {
        return Optional.empty();
    }

    /** Enumerates task names for {@link #taskLoadLabel} by running the tool on a short-lived process (e.g.
     *  {@code gradle tasks --all}). Empty by default. Called off the FX thread by the coordinator. */
    public List<String> loadTasks(Path root, boolean isWindows, String override) throws Exception {
        return List.of();
    }

    /** The tool id ({@code "maven"}/{@code "npm"}) — also the command-id prefix and tool-window id. */
    public String id() {
        return id;
    }

    /** The brand display name shown in status messages / popup title (a proper noun, not localized). */
    public String displayName() {
        return displayName;
    }

    /** The default command name, shown as the Settings command-override field's prompt (e.g. {@code mvn}). */
    public String commandExample() {
        return commandExample;
    }

    /** The marker file names whose nearest ancestor directory roots the tool (no {@code .git} fallback). */
    public List<String> markers() {
        return markers;
    }

    /** The {@code view.toggle<Tool>Support} command id for this tool's Settings enable toggle. */
    public String toggleCommandId() {
        return toggleCommandId;
    }

    /** The per-line console color classifier for this tool's output. */
    public OutputStyle outputStyle() {
        return outputStyle;
    }

    public boolean enabledIn(Settings s) {
        return enabledGetter.test(s);
    }

    public void setEnabledIn(Settings s, boolean enabled) {
        enabledSetter.accept(s, enabled);
    }

    public String commandIn(Settings s) {
        return commandGetter.apply(s);
    }

    public void setCommandIn(Settings s, String command) {
        commandSetter.accept(s, command);
    }

    /** The build tools wired into the UI (each gets a toolbar button + console), in toolbar order. */
    public static List<BuildTool> enabled() {
        return List.of(MAVEN, NPM, CARGO, GO, GRADLE);
    }

    /** Timeout for the on-demand {@code gradle tasks} enumeration (a fresh Gradle daemon can be slow). */
    private static final Duration TASK_LOAD_TIMEOUT = Duration.ofSeconds(90);

    private static String npmPackageManager(Path root, NpmProject project) {
        return NpmPackageManager.detect(
                project.packageManagerField(),
                Files.exists(root.resolve("package-lock.json")),
                Files.exists(root.resolve("yarn.lock")),
                Files.exists(root.resolve("pnpm-lock.yaml")),
                Files.exists(root.resolve("bun.lockb")));
    }
}
