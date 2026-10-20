package com.editora.build;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.editora.maven.MavenLifecycle;
import com.editora.maven.MavenPluginGoals;
import com.editora.maven.MavenPluginPrefix;
import com.editora.maven.MavenReactor;
import com.editora.maven.PomModel;

import static com.editora.i18n.Messages.tr;

/**
 * The Maven {@link BuildActionsProvider}: reads a parsed {@link PomModel} into the generic action model —
 * a Lifecycle section (one {@link BuildAction.Task} per phase), a Profiles section (one
 * {@link BuildAction.Toggle} per {@code <profile>}), a Plugins section (one Task per declared plugin
 * execution goal), and — for each checked profile that declares its own {@code <build>/<plugins>} — a nested
 * goals section. Active profiles merge into a single {@code -Pa,b} via {@link #toggleArgs}; a profile
 * switched to its third, explicitly-off state contributes {@code !id} there ({@code -Pdev,!ci}).
 *
 * <p>A profile row's note says why Maven would activate it on its own — {@code activeByDefault} (which Maven
 * drops as soon as any other profile of the same pom activates, hence "active unless another profile is"),
 * a JDK or OS range, a property, a file — and a profile listed in {@code ~/.m2/settings.xml}'s
 * {@code <activeProfiles>} is shown checked and locked: it is on whatever the argv says.
 *
 * <p>After those comes one collapsed section per plugin whose descriptor was resolvable
 * ({@link MavenPluginGoals}), listing every goal it offers. The pom-derived Plugins section above can only
 * show a goal that is bound in an {@code <execution>}, so a plugin meant to be invoked directly —
 * {@code javafx:run}, {@code spring-boot:run}, {@code exec:java} — had no row at all. The two overlap by
 * design and say different things: the flat section is <em>what this build runs</em> (with the phase and
 * execution id in its tooltip; a non-default execution runs as {@code prefix:goal@id}, so its own
 * {@code <configuration>} applies), a per-plugin group is <em>what this plugin can run</em>. Plugins declared
 * only under {@code <pluginManagement>} get a group titled "(managed)".
 *
 * <p>A module of a multi-module build gets a <b>Reactor</b> section first: one toggle that runs every task
 * from the reactor root with the module's upstream modules ({@code -f <root pom> -pl <module> -am}). Without
 * it, {@code compile} in a module that depends on a sibling fails with "Could not find artifact" until the
 * sibling was installed by hand; with it, Maven builds the sibling first, as it would from the root.
 */
public final class MavenActionsProvider implements BuildActionsProvider {

    /** The toggle id of the reactor switch; never a profile id (Maven profile ids cannot start with {@code @}). */
    public static final String REACTOR_TOGGLE = "@reactor";

    /**
     * What the tasks tree knows beyond the pom and its declared plugins' descriptors.
     *
     * @param managedGoals descriptors of the {@code <pluginManagement>}-only plugins
     * @param settingsActiveProfiles profile ids {@code ~/.m2/settings.xml} activates for every build
     * @param localRepositoryOnly whether the goal groups were read from <em>this</em> machine's local repository
     *     while the build runs elsewhere (a remote, WSL or container project) — the group titles then say so
     */
    public record Extras(
            List<MavenPluginGoals.Descriptor> managedGoals,
            Set<String> settingsActiveProfiles,
            boolean localRepositoryOnly) {
        public static final Extras NONE = new Extras(List.of(), Set.of(), false);
    }

    private final PomModel model;
    private final List<MavenPluginGoals.Descriptor> pluginGoals;
    private final MavenReactor.Reactor reactor;
    /** The root pom as the machine Maven runs on names it (a remote host's path when the build runs there). */
    private final String rootPomOnTarget;

    private final Extras extras;

    public MavenActionsProvider(PomModel model) {
        this(model, List.of());
    }

    public MavenActionsProvider(PomModel model, List<MavenPluginGoals.Descriptor> pluginGoals) {
        this(model, pluginGoals, null, null);
    }

    /**
     * @param reactor the module's reactor, or {@code null} for a top-level project (no Reactor section)
     * @param rootPomOnTarget the reactor's root pom as Maven's machine names it; {@code null} = the local path
     */
    public MavenActionsProvider(
            PomModel model,
            List<MavenPluginGoals.Descriptor> pluginGoals,
            MavenReactor.Reactor reactor,
            String rootPomOnTarget) {
        this(model, pluginGoals, reactor, rootPomOnTarget, Extras.NONE);
    }

    public MavenActionsProvider(
            PomModel model,
            List<MavenPluginGoals.Descriptor> pluginGoals,
            MavenReactor.Reactor reactor,
            String rootPomOnTarget,
            Extras extras) {
        this.model = model;
        this.pluginGoals = List.copyOf(pluginGoals);
        this.reactor = reactor;
        this.rootPomOnTarget = reactor == null
                ? null
                : rootPomOnTarget != null ? rootPomOnTarget : reactor.rootPom().toString();
        this.extras = extras == null ? Extras.NONE : extras;
    }

    /** The module's reactor, or {@code null}. */
    public MavenReactor.Reactor reactor() {
        return reactor;
    }

    /** The pom this provider was built from (the trust prompt lists its plugins). */
    public PomModel model() {
        return model;
    }

    @Override
    public List<BuildAction.Section> sections(Set<String> activeToggleIds) {
        List<BuildAction.Section> out = new ArrayList<>();

        if (reactor != null) {
            out.add(new BuildAction.Section(
                    tr("mavenpopup.reactor"),
                    List.of(new BuildAction.Toggle(
                            REACTOR_TOGGLE,
                            tr("mavenpopup.reactorToggle", reactor.rootName(), reactor.moduleSelector())))));
        }

        List<BuildAction.Row> phases = new ArrayList<>();
        for (String phase : MavenLifecycle.PHASES) {
            phases.add(new BuildAction.Task(phase, List.of(phase)));
        }
        out.add(new BuildAction.Section(tr("mavenpopup.lifecycle"), phases));

        if (!model.profiles().isEmpty()) {
            List<BuildAction.Row> profiles = new ArrayList<>();
            for (PomModel.Profile p : model.profiles()) {
                boolean locked = extras.settingsActiveProfiles().contains(p.id());
                profiles.add(new BuildAction.Toggle(p.id(), p.id(), profileNote(p, locked), !locked, locked));
            }
            out.add(new BuildAction.Section(tr("mavenpopup.profiles"), profiles));
        }

        List<BuildAction.Row> topGoals = goalTasks(model.plugins());
        if (!topGoals.isEmpty()) {
            out.add(new BuildAction.Section(tr("mavenpopup.plugins"), topGoals));
        }

        for (PomModel.Profile p : model.profiles()) {
            if (!activeToggleIds.contains(p.id())) {
                continue;
            }
            List<BuildAction.Row> profileGoals = goalTasks(p.plugins());
            if (!profileGoals.isEmpty()) {
                out.add(new BuildAction.Section(tr("mavenpopup.profilePlugins", p.id()), profileGoals));
            }
        }

        for (MavenPluginGoals.Descriptor plugin : pluginGoals) {
            addGoalGroup(out, plugin, false);
        }
        for (MavenPluginGoals.Descriptor plugin : extras.managedGoals()) {
            addGoalGroup(out, plugin, true);
        }
        return out;
    }

    /** The profile row's note: why Maven would activate it without {@code -P} (empty for a plain profile). */
    static String profileNote(PomModel.Profile p, boolean locked) {
        List<String> parts = new ArrayList<>();
        if (locked) {
            parts.add(tr("mavenpopup.settingsActive"));
        }
        if (p.activeByDefault()) {
            parts.add(tr("mavenpopup.activeByDefault"));
        }
        for (PomModel.Activation a : p.activations()) {
            parts.add(
                    switch (a.kind()) {
                        case JDK -> tr("mavenpopup.activation.jdk", a.value());
                        case OS -> tr("mavenpopup.activation.os", a.value());
                        case PROPERTY -> tr("mavenpopup.activation.property", a.value());
                        case FILE_EXISTS -> tr("mavenpopup.activation.fileExists", a.value());
                        case FILE_MISSING -> tr("mavenpopup.activation.fileMissing", a.value());
                    });
        }
        return parts.isEmpty() ? "" : "(" + String.join(" · ", parts) + ")";
    }

    private void addGoalGroup(List<BuildAction.Section> out, MavenPluginGoals.Descriptor plugin, boolean managed) {
        List<BuildAction.Row> rows = new ArrayList<>();
        for (MavenPluginGoals.Goal goal : plugin.goals()) {
            String label = plugin.goalPrefix() + ":" + goal.name();
            rows.add(new BuildAction.Task(label, List.of(label), goal.description()));
        }
        if (rows.isEmpty()) {
            return;
        }
        String title = plugin.goalPrefix();
        if (managed) {
            title = tr("mavenpopup.managedGroup", title);
        }
        if (extras.localRepositoryOnly()) {
            title = tr("mavenpopup.localRepositoryGroup", title);
        }
        String version = plugin.version() == null || plugin.version().isEmpty() ? "?" : plugin.version();
        String tooltip = plugin.newestLocal()
                ? tr("mavenpopup.goalGroupTooltipNewest", plugin.goalPrefix(), version)
                : tr("mavenpopup.goalGroupTooltip", plugin.goalPrefix(), version);
        out.add(new BuildAction.Section(title, rows, true, tooltip));
    }

    @Override
    public List<String> toggleArgs(Set<String> activeToggleIds) {
        Set<String> profiles = new LinkedHashSet<>(activeToggleIds);
        boolean viaReactor = profiles.remove(REACTOR_TOGGLE) && reactor != null;
        profiles.remove("!" + REACTOR_TOGGLE);
        List<String> out = new ArrayList<>();
        if (viaReactor) {
            out.addAll(MavenReactor.reactorArgs(rootPomOnTarget, reactor.moduleSelector()));
        }
        if (!profiles.isEmpty()) {
            out.add("-P" + String.join(",", profiles));
        }
        return List.copyOf(out);
    }

    /**
     * Whether an execution id is one Maven assigns itself — {@code default} (no id written) or
     * {@code default-<goal>} (a lifecycle binding's) — rather than one the pom named, which a run must
     * address as {@code prefix:goal@id} for the execution's own configuration to apply.
     */
    static boolean isDefaultExecution(String id, String goal) {
        return id == null || id.isEmpty() || id.equals("default") || id.equals("default-" + goal);
    }

    private static List<BuildAction.Row> goalTasks(List<PomModel.Plugin> plugins) {
        List<BuildAction.Row> out = new ArrayList<>();
        for (PomModel.Plugin plugin : plugins) {
            String prefix = MavenPluginPrefix.derive(plugin.groupId(), plugin.artifactId());
            for (PomModel.Execution exec : plugin.executions()) {
                for (String goal : exec.goals()) {
                    String label = prefix + ":" + goal;
                    String arg = isDefaultExecution(exec.id(), goal) ? label : label + "@" + exec.id();
                    String tooltip =
                            tr("mavenpopup.goalTooltip", exec.phase().isEmpty() ? "-" : exec.phase(), exec.id());
                    if (!arg.equals(label)) {
                        tooltip = tr("mavenpopup.goalTooltipRuns", tooltip, arg);
                    }
                    out.add(new BuildAction.Task(label, List.of(arg), tooltip));
                }
            }
        }
        return out;
    }
}
