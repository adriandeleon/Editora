package com.editora.build;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.editora.i18n.Messages.tr;

/**
 * The Babashka {@link BuildActionsProvider}: a Tasks section of the {@code bb.edn} tasks ({@code bb run <task>};
 * see {@link ClojureProjects#babashkaTasks}), then {@code bb tasks}, which lists them with their docs. No toggles.
 * Pure.
 */
public final class BabashkaActionsProvider implements BuildActionsProvider {

    private final List<String> tasks;

    public BabashkaActionsProvider(List<String> tasks) {
        this.tasks = tasks == null ? List.of() : List.copyOf(tasks);
    }

    @Override
    public List<BuildAction.Section> sections(Set<String> activeToggleIds) {
        List<BuildAction.Section> out = new ArrayList<>();
        if (!tasks.isEmpty()) {
            List<BuildAction.Row> rows = new ArrayList<>();
            for (String t : tasks) {
                rows.add(new BuildAction.Task(t, List.of("run", t)));
            }
            out.add(new BuildAction.Section(tr("buildpopup.section.tasks.bb"), rows));
        }
        out.add(new BuildAction.Section(
                tr("buildpopup.section.commands"), List.of(new BuildAction.Task("tasks", List.of("tasks")))));
        return out;
    }
}
