package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.editora.editor.EditorBuffer;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * launch.json and the user's own debug adapters: Debug: Start Configuration and Debug: Start Compound with their
 * pickers, the inputs, variables and {@code preLaunchTask} of a configuration, the debugger each configuration's type
 * starts on ({@link com.editora.dap.LaunchRoute}), and {@code debug-adapters.json}. Part of {@link DebugCoordinator}.
 */
final class LaunchJsonSessions {

    private final DebugCoordinator d; // its host and ops are read when used: this is made with the coordinator

    LaunchJsonSessions(DebugCoordinator d) {
        this.d = d;
    }

    /** The user's own adapters ({@code debug-adapters.json}), by type, with what F5 sends each. */
    private java.util.Map<String, com.editora.dap.CustomAdapters.Adapter> customAdapters = java.util.Map.of();

    /** The launch.json configuration last started, by launch.json: the picker offers it first. */
    private final java.util.Map<Path, String> lastConfiguration = new java.util.HashMap<>();

    /** The user's {@code debug-adapters.json}: the config folder's (null without one). */
    private @Nullable Path userAdaptersFile() {
        Path config = d.host.configDir();
        return config == null ? null : config.resolve("debug-adapters.json");
    }

    /**
     * Reads the user's own adapters — the config folder's {@code debug-adapters.json}, then a trusted project's
     * {@code .editora/debug-adapters.json}, whose adapters replace those of the same type — and looks for each one's
     * program. One that cannot be read is said in the status bar.
     */
    void loadCustomAdapters(boolean on) {
        List<Path> paths = new ArrayList<>();
        Path user = userAdaptersFile();
        if (user != null) {
            paths.add(user);
        }
        Path project = d.ops.projectRoot();
        if (project != null && d.host.projectScope().trusted()) {
            paths.add(project.resolve(".editora").resolve("debug-adapters.json"));
        }
        long read = ++adaptersRead; // the files are read off the FX thread (PERF-01): the last ask's answer wins
        d.planOffFx("debug-adapters-read", () -> DebugPlanning.readAdapters(paths), answer -> {
            var found = answer.value();
            if (read != adaptersRead || found == null) {
                return;
            }
            List<com.editora.dap.CustomAdapters.Adapter> adapters = com.editora.dap.CustomAdapters.merge(found.files());
            java.util.Map<String, com.editora.dap.CustomAdapters.Adapter> byType = new java.util.LinkedHashMap<>();
            adapters.forEach(a -> byType.put(a.profile().id(), a));
            customAdapters = byType;
            com.editora.dap.DebugLanguages.setCustom(adapters.stream()
                    .map(com.editora.dap.CustomAdapters.Adapter::profile)
                    .toList());
            for (var a : adapters) {
                d.dap().configureExternal(a.profile(), on, "", f -> d.applyGating());
            }
            if (!found.problems().isEmpty()) {
                d.host.setStatus(tr("status.debug.customAdaptersInvalid", String.join("; ", found.problems())));
            }
        });
    }

    private long adaptersRead;

    /** Debug: Edit Debug Adapters: the user's {@code debug-adapters.json}, written with an example the first time. */
    void editCustomAdapters() {
        Path file = userAdaptersFile();
        if (file == null) {
            return;
        }
        d.planOffFx("debug-adapters-edit", () -> DebugPlanning.ensureAdaptersFile(file), answer -> {
            if (answer.failure() != null) {
                d.host.setStatus(tr("debug.error.startFailed", answer.why()));
            } else {
                d.ops.openPath(file);
            }
        });
    }

    /** F5 on a file of one of the user's own adapters' languages: its {@code launch} template, variables put in. */
    void debugCustomFile(EditorBuffer b, Path path, com.editora.dap.DebugLanguage profile, List<String> args) {
        com.editora.dap.CustomAdapters.Adapter adapter = customAdapters.get(profile.id());
        if (adapter == null || adapter.launch().isEmpty()) {
            d.host.setStatus(tr("status.debug.customNoLaunch", profile.displayName()));
            return;
        }
        Path root = d.ops.projectRoot();
        Path workspace = root != null && path.toAbsolutePath().startsWith(root.toAbsolutePath())
                ? root
                : java.util.Objects.requireNonNullElse(path.toAbsolutePath().getParent(), path);
        java.util.Map<String, Object> request = new java.util.LinkedHashMap<>();
        request.put("type", profile.id());
        request.put("request", "launch");
        try {
            Object body = com.editora.dap.LaunchJson.substitute(
                    adapter.launch(),
                    launchContext(workspace, b, java.util.Map.of()),
                    java.util.Map.of(),
                    new java.util.HashMap<>());
            if (body instanceof java.util.Map<?, ?> m) {
                m.forEach((k, v) -> request.put(String.valueOf(k), v));
            }
        } catch (com.editora.dap.LaunchJson.LaunchJsonException e) {
            d.host.setStatus(tr("status.debug.launchVariable", e.getMessage()));
            return;
        } catch (com.editora.dap.LaunchJson.Cancelled e) {
            return;
        }
        if (!request.containsKey("args") && !args.isEmpty()) {
            request.put("args", args);
        }
        d.startExternalSession(path, profile, request, String.valueOf(path.getFileName()));
    }

    /** What VS Code's variables stand for: {@code workspace}, the active file and its caret, the answered inputs. */
    private com.editora.dap.LaunchJson.Context launchContext(
            Path workspace, @Nullable EditorBuffer b, java.util.Map<String, String> answers) {
        Path file = b == null ? null : b.getPath();
        int line = b == null ? 1 : DebugCoordinator.caretLine(b) + 1;
        String selected = b == null ? "" : b.getFocusedArea().getSelectedText();
        return new com.editora.dap.LaunchJson.Context(
                workspace,
                file,
                line,
                selected,
                System.getenv(),
                Path.of(System.getProperty("user.home", "")),
                in -> answers.get(in.id()));
    }

    /**
     * Debug: Start Configuration: the configurations and compounds of the project's {@code .vscode/launch.json} (or the
     * nearest one above the active file), picked from a list — the one started last first — and started as VS Code
     * starts them: beside the running session, when there is one.
     */
    void startLaunchConfiguration() {
        EditorBuffer b = d.host.activeBuffer();
        readLaunchJson(b, true, launch -> {
            if (launch != null) {
                startLaunchConfiguration(launch, b);
            }
        });
    }

    private void startLaunchConfiguration(DebugCoordinator.LaunchFile launch, @Nullable EditorBuffer b) {
        if (launch.parsed().configurations().isEmpty()
                && launch.parsed().compounds().isEmpty()) {
            d.host.setStatus(tr("status.debug.noConfigurations", launch.path()));
            return;
        }
        List<LaunchChoice> choices = new ArrayList<>();
        launch.parsed().configurations().forEach(c -> choices.add(new LaunchChoice(c, null)));
        launch.parsed().compounds().forEach(c -> choices.add(new LaunchChoice(null, c)));
        String last = lastConfiguration.get(launch.path());
        choices.sort(java.util.Comparator.comparing(c -> !c.name().equals(last)));
        QuickOpen<LaunchChoice> picker = new QuickOpen<>(
                tr("debug.launchJson.title"),
                tr("debug.launchJson.prompt"),
                () -> choices,
                LaunchChoice::name,
                LaunchChoice::detail,
                c -> {
                    if (c != null) {
                        lastConfiguration.put(launch.path(), c.name());
                        startLaunchChoice(launch, c, b);
                    }
                });
        picker.setOverlayHost(d.host.overlayHost());
        picker.show(d.host.window());
    }

    /** The launch.json for the active file's project, read off the FX thread ({@link DebugPlanning#readLaunchJson}). */
    private void readLaunchJson(
            @Nullable EditorBuffer b, boolean sayNone, Consumer<DebugCoordinator.@Nullable LaunchFile> then) {
        DebugPlanning.readLaunchJson(
                d.host, d.ops.projectRoot(), b == null ? null : b.getPath(), sayNone, d.asThisSession(then));
    }

    /** An entry of Debug: Start Configuration: one of launch.json's configurations, or one of its compounds. */
    record LaunchChoice(
            com.editora.dap.LaunchJson.@Nullable Configuration configuration,
            com.editora.dap.LaunchJson.@Nullable Compound compound) {

        String name() {
            com.editora.dap.LaunchJson.Configuration c = configuration;
            com.editora.dap.LaunchJson.Compound group = compound;
            return c != null ? c.name() : group != null ? group.name() : "";
        }

        String detail() {
            com.editora.dap.LaunchJson.Configuration c = configuration;
            com.editora.dap.LaunchJson.Compound group = compound;
            return c != null
                    ? c.type() + " · " + c.request()
                    : tr(
                            "debug.compound.detail",
                            group == null ? 0 : group.configurations().size());
        }
    }

    /** Starts {@code choice} of {@code launch}: a configuration, or every configuration of a compound. */
    private void startLaunchChoice(DebugCoordinator.LaunchFile launch, LaunchChoice choice, @Nullable EditorBuffer b) {
        com.editora.dap.LaunchJson.Configuration c = choice.configuration();
        com.editora.dap.LaunchJson.Compound group = choice.compound();
        if (c != null) {
            runLaunchConfiguration(launch.path(), launch.workspace(), launch.parsed(), c, b);
        } else if (group != null) {
            runLaunchCompound(launch, group, b);
        }
    }

    /**
     * Starts launch.json compound {@code group}: asks the inputs its configurations use (each once), runs its
     * {@code preLaunchTask}, then starts each configuration in a session of its own, in the order listed — each with
     * its own {@code preLaunchTask}. Stopping one stops them all when the compound says {@code stopAll}.
     */
    void runLaunchCompound(
            DebugCoordinator.LaunchFile launch, com.editora.dap.LaunchJson.Compound group, @Nullable EditorBuffer b) {
        List<com.editora.dap.LaunchJson.Configuration> members = new ArrayList<>();
        for (String name : group.configurations()) {
            com.editora.dap.LaunchJson.Configuration c = launch.parsed().configuration(name);
            if (c == null) {
                d.host.setStatus(tr("status.debug.compoundMissing", group.name(), name));
                return;
            }
            members.add(c);
        }
        if (members.isEmpty()) {
            d.host.setStatus(tr("status.debug.compoundEmpty", group.name()));
            return;
        }
        String os = com.editora.dap.LaunchJson.osKey(com.editora.Os.current());
        List<java.util.Map<String, Object>> bodies = members.stream()
                .map(c -> com.editora.dap.LaunchJson.forPlatform(c.body(), os))
                .toList();
        List<String> ids = bodies.stream()
                .flatMap(body -> com.editora.dap.LaunchJson.inputsUsed(body).stream())
                .distinct()
                .toList();
        askInputs(launch.parsed().inputs(), ids, 0, new java.util.LinkedHashMap<>(), answers -> {
            DebugCompound compound = new DebugCompound(group.name(), group.stopAll());
            DebugSession first = d.sessionForLaunch();
            Runnable startAll = () -> {
                for (int i = 0; i < members.size(); i++) {
                    DebugSession target = i == 0 ? first : d.openSession();
                    String name = members.get(i).name();
                    java.util.Map<String, Object> body = bodies.get(i);
                    compound.join(target);
                    d.inSession(
                            target,
                            () -> resolveAndRun(
                                    launch.path(), launch.workspace(), launch.parsed(), name, body, answers, b));
                }
            };
            if (group.preLaunchTask().isBlank()) {
                startAll.run();
                return;
            }
            d.inSession(
                    first,
                    () -> preLaunchTask(launch.path(), launch.workspace(), group.preLaunchTask(), b, answers, argv -> {
                        d.nameSession(group.name());
                        d.beforeLaunch()
                                .run(
                                        d.host,
                                        new BeforeLaunchStep.Command(
                                                group.preLaunchTask(), argv, "status.run.preLaunchTask"),
                                        launch.workspace(),
                                        java.util.Map.of(),
                                        d.beforeLaunchConsole(),
                                        () -> d.later(startAll)); // once the step has ended: its service is free again
                    }));
        });
    }

    /**
     * Debugs compound {@code compound} of saved run configurations: each in a session of its own, in the order listed,
     * as Debug ▸ Run Configuration debugs one. Says which configuration is missing, and starts none, when one is.
     */
    void debugRunCompound(com.editora.config.RunCompound compound) {
        List<com.editora.config.RunConfiguration> saved = d.ops.runConfigurations();
        List<com.editora.config.RunConfiguration> members = new ArrayList<>();
        for (String name : compound.configurations()) {
            com.editora.config.RunConfiguration cfg = saved.stream()
                    .filter(c -> c.name().equals(name))
                    .findFirst()
                    .orElse(null);
            if (cfg == null) {
                d.host.setStatus(tr("status.debug.compoundMissing", compound.name(), name));
                return;
            }
            members.add(cfg);
        }
        if (members.isEmpty()) {
            d.host.setStatus(tr("status.debug.compoundEmpty", compound.name()));
            return;
        }
        DebugCompound group = new DebugCompound(compound.name(), compound.stopAll());
        for (int i = 0; i < members.size(); i++) {
            DebugSession target = i == 0 ? d.sessionForLaunch() : d.openSession();
            com.editora.config.RunConfiguration cfg = members.get(i);
            group.join(target);
            d.inSession(target, () -> d.debugConfig(cfg));
        }
    }

    /**
     * Debug: Start Compound — the project's compounds (its own and its launch.json's), picked from a list and debugged
     * together; the last entry makes a new one.
     */
    void startCompound() {
        EditorBuffer b = d.host.activeBuffer();
        readLaunchJson(b, false, launch -> startCompound(launch, b));
    }

    private void startCompound(DebugCoordinator.@Nullable LaunchFile launch, @Nullable EditorBuffer b) {
        List<CompoundChoice> choices = new ArrayList<>();
        d.ops.runCompounds().forEach(c -> choices.add(new CompoundChoice(c, null)));
        if (launch != null) {
            launch.parsed().compounds().forEach(c -> choices.add(new CompoundChoice(null, c)));
        }
        choices.add(new CompoundChoice(null, null)); // New Compound…
        QuickOpen<CompoundChoice> picker = new QuickOpen<>(
                tr("debug.compound.title"),
                tr("debug.compound.prompt"),
                () -> choices,
                CompoundChoice::name,
                CompoundChoice::detail,
                c -> {
                    if (c == null) {
                        return;
                    }
                    com.editora.config.RunCompound own = c.own();
                    com.editora.dap.LaunchJson.Compound fromJson = c.launchJson();
                    if (own != null) {
                        debugRunCompound(own);
                    } else if (fromJson != null && launch != null) {
                        runLaunchCompound(launch, fromJson, b);
                    } else {
                        d.ops.editCompounds(null);
                    }
                });
        picker.setOverlayHost(d.host.overlayHost());
        picker.show(d.host.window());
    }

    /** Debug: Edit Compounds… — the project's own compounds, in their editor. */
    void editCompounds() {
        d.ops.editCompounds(
                d.ops.runCompounds().isEmpty()
                        ? null
                        : d.ops.runCompounds().getFirst().name());
    }

    /** An entry of Debug: Start Compound: one of the project's own, one of its launch.json's, or New Compound…. */
    record CompoundChoice(
            com.editora.config.@Nullable RunCompound own, com.editora.dap.LaunchJson.@Nullable Compound launchJson) {

        String name() {
            com.editora.config.RunCompound o = own;
            com.editora.dap.LaunchJson.Compound j = launchJson;
            return o != null ? o.name() : j != null ? j.name() : tr("debug.compound.new");
        }

        String detail() {
            com.editora.config.RunCompound o = own;
            com.editora.dap.LaunchJson.Compound j = launchJson;
            if (o != null) {
                return String.join(", ", o.configurations());
            }
            return j != null ? "launch.json · " + String.join(", ", j.configurations()) : "";
        }
    }

    /** Runs configuration {@code c}: asks its inputs, then {@link #resolveAndRun}. */
    void runLaunchConfiguration(
            Path launchJson,
            Path workspace,
            com.editora.dap.LaunchJson.File parsed,
            com.editora.dap.LaunchJson.Configuration c,
            @Nullable EditorBuffer b) {
        java.util.Map<String, Object> body = com.editora.dap.LaunchJson.forPlatform(
                c.body(), com.editora.dap.LaunchJson.osKey(com.editora.Os.current()));
        askInputs(
                parsed.inputs(),
                com.editora.dap.LaunchJson.inputsUsed(body),
                0,
                new java.util.LinkedHashMap<>(),
                answers -> d.inSession(
                        d.sessionForLaunch(),
                        () -> resolveAndRun(launchJson, workspace, parsed, c.name(), body, answers, b)));
    }

    /** Asks inputs {@code ids} from {@code index} on, one after another, then hands the answers to {@code done}. */
    void askInputs(
            java.util.Map<String, com.editora.dap.LaunchJson.Input> inputs,
            List<String> ids,
            int index,
            java.util.Map<String, String> answers,
            Consumer<java.util.Map<String, String>> done) {
        if (index >= ids.size()) {
            done.accept(answers);
            return;
        }
        String id = ids.get(index);
        com.editora.dap.LaunchJson.Input in = inputs.get(id);
        if (in == null) {
            d.host.setStatus(tr("status.debug.launchVariable", "${input:" + id + "}"));
            return;
        }
        String title = in.description().isBlank() ? id : in.description();
        Consumer<String> next = value -> {
            answers.put(id, value);
            askInputs(inputs, ids, index + 1, answers, done);
        };
        if ("pickString".equals(in.type()) && !in.options().isEmpty()) {
            QuickOpen<String> pick = new QuickOpen<>(title, title, in::options, o -> o, o -> "", o -> {
                if (o != null) {
                    next.accept(o);
                }
            });
            pick.setOverlayHost(d.host.overlayHost());
            pick.show(d.host.window());
        } else {
            d.host.promptText(title, title, in.defaultValue(), next);
        }
    }

    /**
     * Puts the variables in, runs the {@code preLaunchTask} (a plain {@code tasks.json} command), and starts the
     * configuration on its debugger ({@link com.editora.dap.LaunchRoute}).
     */
    void resolveAndRun(
            Path launchJson,
            Path workspace,
            com.editora.dap.LaunchJson.File parsed,
            String name,
            java.util.Map<String, Object> raw,
            java.util.Map<String, String> answers,
            @Nullable EditorBuffer b) {
        java.util.Map<String, Object> body;
        try {
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> substituted =
                    (java.util.Map<String, Object>) com.editora.dap.LaunchJson.substitute(
                            raw,
                            launchContext(workspace, b, answers),
                            java.util.Map.of(),
                            new java.util.HashMap<>(answers));
            body = substituted == null ? raw : substituted;
        } catch (com.editora.dap.LaunchJson.LaunchJsonException e) {
            d.host.setStatus(tr("status.debug.launchVariable", e.getMessage()));
            return;
        } catch (com.editora.dap.LaunchJson.Cancelled e) {
            return;
        }
        // Restart runs all of this again (the task included), not only the adapter's start: an edited program is
        // saved and built again before it is debugged.
        Runnable again = () -> resolveAndRun(launchJson, workspace, parsed, name, raw, answers, b);
        boolean launch = !"attach".equals(body.get("request"));
        Runnable[] start = new Runnable[1];
        start[0] = () -> {
            // The adapter reads the program from disk: edits are saved first (off the FX thread, then this again).
            DebugSession session = d.s(); // the saves answer later: then again as this session
            if (!d.savedOrThen(d.buffersUnder(workspace, null), () -> d.inSession(session, start[0]))) {
                return;
            }
            d.s().followUp = d.followUps.followUp(launchJson, workspace, parsed, name, body, answers, b);
            startRoute(workspace, name, body, b);
            if (launch) {
                d.s().relaunch = again;
            }
        };
        if (!(body.get("preLaunchTask") instanceof String task) || task.isBlank()) {
            start[0].run();
            return;
        }
        preLaunchTask(launchJson, workspace, task, b, answers, argv -> {
            if (d.beforeLaunchActive() || d.s().pendingBuild != null) {
                d.host.setStatus(tr("status.run.busy"));
                return;
            }
            d.beforeLaunch()
                    .run(
                            d.host,
                            new BeforeLaunchStep.Command(task, argv, "status.run.preLaunchTask"),
                            workspace,
                            java.util.Map.of(),
                            d.beforeLaunchConsole(),
                            () -> d.later(start[0]));
        });
    }

    /** The command line of {@code tasks.json}'s task {@code label}, to {@code then} ({@link DebugPlanning}). */
    void preLaunchTask(
            Path launchJson,
            Path workspace,
            String label,
            @Nullable EditorBuffer b,
            java.util.Map<String, String> answers,
            Consumer<List<String>> then) {
        DebugPlanning.preLaunchTask(
                d.host,
                launchJson,
                label,
                () -> launchContext(workspace, b, answers),
                answers,
                d.asThisSession(argv -> {
                    // A tasks.json command is the folder's own code: it runs only in a folder the user trusts, as a
                    // build does.
                    if (d.ops.trustBuild(workspace, List.of(tr("dialog.trust.item.preLaunchTask", label)))) {
                        Runnable[] run = new Runnable[1];
                        DebugSession session = d.s();
                        run[0] = () -> { // the task builds what is on disk: edits are saved first
                            if (d.savedOrThen(d.buffersUnder(workspace, null), () -> d.inSession(session, run[0]))) {
                                then.accept(argv);
                            }
                        };
                        run[0].run();
                    }
                }));
    }

    /** Starts a resolved configuration on the debugger its type names. */
    private void startRoute(Path workspace, String name, java.util.Map<String, Object> body, @Nullable EditorBuffer b) {
        java.util.Map<String, com.editora.dap.DebugLanguage> own = new java.util.LinkedHashMap<>();
        customAdapters.forEach((type, a) -> own.put(type, a.profile()));
        com.editora.dap.LaunchRoute.Route route = com.editora.dap.LaunchRoute.of(body, own);
        Path active = b == null ? null : b.getPath();
        Path program = body.get("program") instanceof String p && !p.isBlank() ? Path.of(p) : null;
        Path anchor = active != null ? active : program != null ? program : workspace;
        com.editora.dap.DebugLanguage profile = route.profile();
        switch (route.kind()) {
            case UNSUPPORTED -> d.host.setStatus(tr("status.debug.launchUnsupported", name, route.reason()));
            case JAVA -> debugJavaConfiguration(name, body, workspace);
            case DEBUGPY -> {
                if (!d.debugEffectiveFor("python")) {
                    d.host.setStatus(d.unavailableMessage("python", null));
                } else if (route.attach()) {
                    String[] where = DebugLaunchArgs.attachAddress(body, 5678);
                    d.ops.openToolWindow();
                    d.nameSession(name);
                    d.s().relaunch = null;
                    d.withClosedBreakpoints(
                            () -> d.dap().startPythonAttach(anchor, where[0], Integer.parseInt(where[1])));
                } else {
                    d.ops.openToolWindow();
                    d.nameSession(name);
                    Path file = program != null ? program : anchor;
                    d.s().relaunch = null;
                    d.withClosedBreakpoints(() -> d.dap().startConfigured(file, "python", route.request()));
                }
            }
            case JS_DEBUG -> {
                if (!d.debugEffectiveFor("javascript")) {
                    d.host.setStatus(d.unavailableMessage("javascript", null));
                } else if (route.attach()) {
                    String[] where =
                            DebugLaunchArgs.attachAddress(body, com.editora.dap.JsLaunch.DEFAULT_INSPECTOR_PORT);
                    d.ops.openToolWindow();
                    d.nameSession(name);
                    d.s().relaunch = null;
                    d.withClosedBreakpoints(() -> d.dap()
                            .startJsAttach(
                                    where[0],
                                    Integer.parseInt(where[1]),
                                    name,
                                    workspace,
                                    List.of(com.editora.dap.JsLaunch.NODE_INTERNALS),
                                    true));
                } else {
                    d.ops.openToolWindow();
                    d.nameSession(name);
                    Path file = program != null ? program : anchor;
                    d.s().relaunch = null;
                    d.withClosedBreakpoints(() -> d.dap().startConfigured(file, "javascript", route.request()));
                }
            }
            case BROWSER -> {
                if (!d.debugEffectiveFor("javascript")) {
                    d.host.setStatus(d.unavailableMessage("javascript", null));
                } else {
                    boolean edge = com.editora.dap.BrowserLaunch.EDGE.equals(
                            route.request().get("type"));
                    java.util.Map<String, Object> request = com.editora.dap.BrowserLaunch.configured(
                            route.request(), workspace, d.browser.browserProfile(edge ? "edge" : "chrome"));
                    d.ops.openToolWindow();
                    d.nameSession(name);
                    d.s().relaunch = null;
                    d.withClosedBreakpoints(() -> d.dap().startBrowser(workspace, request));
                }
            }
            case FIREFOX -> d.browser.startConfigured(workspace, name, anchor, route.request());
            case DELVE_REMOTE -> d.delve.startConfigured(name, route.request());
            case DELVE,
                    CODELLDB,
                    NETCOREDBG,
                    RDBG,
                    PHP_DEBUG,
                    BASH_DEBUG,
                    LUA_DEBUG,
                    ELIXIR_LS,
                    POWERSHELL,
                    GDB,
                    CUSTOM -> {
                if (profile == null) {
                    return;
                }
                // GDB and the user's own adapters are ready when their program was found; Delve and CodeLLDB as F5
                // finds them (their language's switch too).
                boolean byProgram = route.kind() == com.editora.dap.LaunchRoute.Kind.GDB
                        || route.kind() == com.editora.dap.LaunchRoute.Kind.CUSTOM;
                boolean ready = byProgram
                        ? d.debugSupportEnabled() && d.dap().isExternalLocated(profile)
                        : d.debugEffectiveFor(
                                profile.editorLanguages().iterator().next());
                if (!ready) {
                    d.host.setStatus(
                            route.kind() == com.editora.dap.LaunchRoute.Kind.GDB
                                    ? tr("status.debug.gdbMissing")
                                    : byProgram
                                            ? tr("debug.error.customUnavailable")
                                            : d.unavailableMessage(profile.id(), null));
                    return;
                }
                java.util.Map<String, Object> request = new java.util.LinkedHashMap<>(route.request());
                if (route.kind() == com.editora.dap.LaunchRoute.Kind.DELVE
                        && !route.attach()
                        && !request.containsKey("output")) {
                    request.put("output", com.editora.dap.GoLaunch.debugBinary()); // not __debug_bin in the package
                }
                if (route.kind() == com.editora.dap.LaunchRoute.Kind.CODELLDB
                        && request.get("cargo") instanceof java.util.Map<?, ?> cargo) {
                    cargoThenStart(workspace, name, anchor, profile, request, cargo);
                    return;
                }
                if (route.kind() == com.editora.dap.LaunchRoute.Kind.RDBG) {
                    rdbgSession(anchor, request, route.attach(), name);
                    return;
                }
                d.startExternalSession(anchor, profile, request, name);
            }
        }
    }

    /**
     * An {@code rdbg} configuration: a launch runs its script under rdbg ({@link com.editora.dap.LaunchRoute#rdbgTail});
     * an attach connects to the program already under {@code rdbg --open} on its {@code debugPort}.
     */
    private void rdbgSession(Path anchor, java.util.Map<String, Object> request, boolean attach, String label) {
        d.ops.openToolWindow();
        d.nameSession(label);
        com.editora.dap.DebugLanguage ruby = com.editora.dap.DebugLanguages.RUBY;
        Runnable start = attach
                ? () -> d.withClosedBreakpoints(() ->
                        d.dap().startPortAttach(anchor, ruby, com.editora.dap.LaunchRoute.rdbgPort(request), request))
                : () -> d.withClosedBreakpoints(() ->
                        d.dap().startExternal(anchor, ruby, request, com.editora.dap.LaunchRoute.rdbgTail(request)));
        d.s().relaunch = start;
        start.run();
    }

    /**
     * CodeLLDB's {@code cargo} field, as its VS Code extension runs it: {@code cargo <args>} (after the folder's
     * trust), then the executable its JSON messages name — the one {@code filter} ({@code name}, {@code kind})
     * picks — becomes the {@code program}.
     */
    private void cargoThenStart(
            Path workspace,
            String name,
            Path anchor,
            com.editora.dap.DebugLanguage profile,
            java.util.Map<String, Object> request,
            java.util.Map<?, ?> cargo) {
        if (!d.factsKnown(
                "debug-cargo-trust-look",
                d.facts.executable(com.editora.build.BuildTool.CARGO, workspace),
                () -> cargoThenStart(workspace, name, anchor, profile, request, cargo))) {
            return;
        }
        List<String> cargoArgv = d.ops.buildExecutable(com.editora.build.BuildTool.CARGO, workspace);
        if (cargoArgv == null) {
            return; // not trusted
        }
        List<String> args = new ArrayList<>();
        if (cargo.get("args") instanceof List<?> l) {
            l.forEach(a -> args.add(String.valueOf(a)));
        }
        java.util.Map<?, ?> filter = cargo.get("filter") instanceof java.util.Map<?, ?> f ? f : java.util.Map.of();
        List<String> build = new ArrayList<>(cargoArgv);
        build.addAll(args);
        List<String> json = new ArrayList<>(build);
        json.add("--message-format=json");
        if (d.beforeLaunchActive() || d.s().pendingBuild != null) {
            d.host.setStatus(tr("status.run.busy"));
            return;
        }
        d.beforeLaunch()
                .run(
                        d.host,
                        new BeforeLaunchStep.Command(name, build, "status.run.cargoBuild"),
                        workspace,
                        java.util.Map.of(),
                        d.beforeLaunchConsole(),
                        () -> d.startOffFx("editora-cargo-artifacts", session -> () -> {
                            var listed = com.editora.dap.RustLaunch.listArtifacts(
                                    workspace, json, DebugCoordinator.CARGO_LIST_TIMEOUT);
                            d.laterIn(session, () -> {
                                Path exe = listed.ok()
                                        ? com.editora.dap.LaunchRoute.cargoExecutable(
                                                com.editora.dap.RustLaunch.artifacts(listed.out()),
                                                args.contains("test"),
                                                filter.get("name") instanceof String n ? n : null,
                                                filter.get("kind") instanceof String k ? k : null)
                                        : null;
                                if (exe == null) {
                                    d.host.setStatus(tr("status.debug.cargoNoArtifact", name));
                                    return;
                                }
                                java.util.Map<String, Object> resolved = new java.util.LinkedHashMap<>(request);
                                resolved.remove("cargo");
                                resolved.put("program", exe.toString());
                                Runnable whole = d.s().relaunch; // Restart builds with cargo again (resolveAndRun)
                                d.startExternalSession(anchor, profile, resolved, name);
                                if (whole != null) {
                                    d.s().relaunch = whole;
                                }
                            });
                        }));
    }

    /**
     * A {@code java} configuration as a run configuration of this window ({@code mainClass}, {@code projectName},
     * {@code args}, {@code vmArgs}, {@code cwd}, {@code env}), debugged as one is; a {@code mainClass} that is a
     * {@code .java} file debugs that file.
     */
    private void debugJavaConfiguration(String name, java.util.Map<String, Object> body, Path workspace) {
        String mainClass = body.get("mainClass") instanceof String m ? m : "";
        if (mainClass.endsWith(".java")) {
            Path file = Path.of(mainClass);
            d.ops.openPath(file);
            EditorBuffer opened = d.ops.bufferForPath(file);
            if (opened != null) {
                d.launchBuffer(opened);
            }
            return;
        }
        if (mainClass.isBlank()) {
            d.host.setStatus(tr("status.debug.launchUnsupported", name, "a Java configuration without a mainClass"));
            return;
        }
        d.debugConfig(com.editora.dap.LaunchRoute.javaRunConfiguration(name, body, workspace));
    }
}
