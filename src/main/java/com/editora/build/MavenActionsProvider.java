package com.editora.build;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.editora.maven.MavenLifecycle;
import com.editora.maven.MavenPluginGoals;
import com.editora.maven.MavenPluginPrefix;
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
 */
public final class MavenActionsProvider implements BuildActionsProvider {

    private final PomModel model;
    private final List<MavenPluginGoals.Descriptor> pluginGoals;

    public MavenActionsProvider(PomModel model) {
        this(model, List.of());
    }

    public MavenActionsProvider(PomModel model, List<MavenPluginGoals.Descriptor> pluginGoals) {
        this.model = model;
        this.pluginGoals = List.copyOf(pluginGoals);
    }

    @Override
    public List<BuildAction.Section> sections(Set<String> activeToggleIds) {
        List<BuildAction.Section> out = new ArrayList<>();

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
        return activeToggleIds.isEmpty() ? List.of() : List.of("-P" + String.join(",", activeToggleIds));
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
