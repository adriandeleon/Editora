package com.editora.build;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.editora.maven.MavenLifecycle;
import com.editora.maven.MavenPluginPrefix;
import com.editora.maven.PomModel;

import static com.editora.i18n.Messages.tr;

/**
 * The Maven {@link BuildActionsProvider}: reads a parsed {@link PomModel} into the generic action model —
 * a Lifecycle section (one {@link BuildAction.Task} per phase), a Profiles section (one
 * {@link BuildAction.Toggle} per {@code <profile>}), a Plugins section (one Task per declared plugin
 * execution goal), and — for each checked profile that declares its own {@code <build>/<plugins>} — a nested
 * goals section. Active profiles merge into a single {@code -Pa,b} via {@link #toggleArgs}. This is the exact
 * behaviour of the old {@code MavenActionsPopup.rebuildRows()}, moved behind the tool-agnostic provider seam.
 */
public final class MavenActionsProvider implements BuildActionsProvider {

    private final PomModel model;

    public MavenActionsProvider(PomModel model) {
        this.model = model;
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
