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
 * goals section. Active profiles merge into a single {@code -Pa,b} via {@link #toggleArgs}.
 *
 * <p>After those comes one collapsed section per plugin whose descriptor was resolvable
 * ({@link MavenPluginGoals}), listing every goal it offers. The pom-derived Plugins section above can only
 * show a goal that is bound in an {@code <execution>}, so a plugin meant to be invoked directly —
 * {@code javafx:run}, {@code spring-boot:run}, {@code exec:java} — had no row at all. The two overlap by
 * design and say different things: the flat section is <em>what this build runs</em> (with the phase and
 * execution id in its tooltip), a per-plugin group is <em>what this plugin can run</em>.
 *
 * <p>A module of a multi-module build gets a <b>Reactor</b> section first: one toggle that runs every task
 * from the reactor root with the module's upstream modules ({@code -f <root pom> -pl <module> -am}). Without
 * it, {@code compile} in a module that depends on a sibling fails with "Could not find artifact" until the
 * sibling was installed by hand; with it, Maven builds the sibling first, as it would from the root.
 */
public final class MavenActionsProvider implements BuildActionsProvider {

    /** The toggle id of the reactor switch; never a profile id (Maven profile ids cannot start with {@code @}). */
    public static final String REACTOR_TOGGLE = "@reactor";

    private final PomModel model;
    private final List<MavenPluginGoals.Descriptor> pluginGoals;
    private final MavenReactor.Reactor reactor;
    /** The root pom as the machine Maven runs on names it (a remote host's path when the build runs there). */
    private final String rootPomOnTarget;

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
        this.model = model;
        this.pluginGoals = List.copyOf(pluginGoals);
        this.reactor = reactor;
        this.rootPomOnTarget = reactor == null
                ? null
                : rootPomOnTarget != null ? rootPomOnTarget : reactor.rootPom().toString();
    }

    /** The module's reactor, or {@code null}. */
    public MavenReactor.Reactor reactor() {
        return reactor;
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
                profiles.add(new BuildAction.Toggle(
                        p.id(), p.id(), p.activeByDefault() ? tr("mavenpopup.activeByDefault") : ""));
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
            List<BuildAction.Row> rows = new ArrayList<>();
            for (MavenPluginGoals.Goal goal : plugin.goals()) {
                String label = plugin.goalPrefix() + ":" + goal.name();
                rows.add(new BuildAction.Task(label, List.of(label), goal.description()));
            }
            if (!rows.isEmpty()) {
                out.add(new BuildAction.Section(plugin.goalPrefix(), rows, true));
            }
        }
        return out;
    }

    @Override
    public List<String> toggleArgs(Set<String> activeToggleIds) {
        Set<String> profiles = new LinkedHashSet<>(activeToggleIds);
        boolean viaReactor = profiles.remove(REACTOR_TOGGLE) && reactor != null;
        List<String> out = new ArrayList<>();
        if (viaReactor) {
            out.addAll(MavenReactor.reactorArgs(rootPomOnTarget, reactor.moduleSelector()));
        }
        if (!profiles.isEmpty()) {
            out.add("-P" + String.join(",", profiles));
        }
        return List.copyOf(out);
    }

    private static List<BuildAction.Row> goalTasks(List<PomModel.Plugin> plugins) {
        List<BuildAction.Row> out = new ArrayList<>();
        for (PomModel.Plugin plugin : plugins) {
            String prefix = MavenPluginPrefix.derive(plugin.groupId(), plugin.artifactId());
            for (PomModel.Execution exec : plugin.executions()) {
                for (String goal : exec.goals()) {
                    String label = prefix + ":" + goal;
                    String tooltip =
                            tr("mavenpopup.goalTooltip", exec.phase().isEmpty() ? "-" : exec.phase(), exec.id());
                    out.add(new BuildAction.Task(label, List.of(label), tooltip));
                }
            }
        }
        return out;
    }
}
