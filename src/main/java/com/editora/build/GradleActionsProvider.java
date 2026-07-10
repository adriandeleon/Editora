package com.editora.build;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.editora.i18n.Messages.tr;

/**
 * The Gradle {@link BuildActionsProvider}: a Common section of the usual tasks (build/clean/test/assemble/
 * check/jar/run/bootRun — harmless "task not found" if a project lacks one) plus, once the user triggers the
 * popup's "Load all tasks…" action, an All-tasks section of every task {@code gradle tasks --all} reported
 * (fed in via {@link #addLoadedTasks}, deduped against the common set). Gradle's DSL can't be statically
 * parsed, so the common set + the freeform "Run custom…" box + this on-demand load are the discovery story.
 * No toggles. The loaded tasks live on this provider instance (reset on the next re-detect). Pure.
 */
public final class GradleActionsProvider implements BuildActionsProvider {

    private static final List<String> COMMON =
            List.of("build", "clean", "test", "assemble", "check", "jar", "run", "bootRun");

    private final List<String> discovered = new ArrayList<>();

    @Override
    public List<BuildAction.Section> sections(Set<String> activeToggleIds) {
        List<BuildAction.Section> out = new ArrayList<>();

        List<BuildAction.Row> common = new ArrayList<>();
        for (String t : COMMON) {
            common.add(new BuildAction.Task(t, List.of(t)));
        }
        out.add(new BuildAction.Section(tr("buildpopup.section.common"), common));

        if (!discovered.isEmpty()) {
            List<BuildAction.Row> all = new ArrayList<>();
            for (String t : discovered) {
                all.add(new BuildAction.Task(t, List.of(t)));
            }
            out.add(new BuildAction.Section(tr("buildpopup.section.tasks"), all));
        }
        return out;
    }

    @Override
    public void addLoadedTasks(List<String> tasks) {
        for (String t : tasks) {
            if (t != null && !t.isBlank() && !COMMON.contains(t) && !discovered.contains(t)) {
                discovered.add(t);
            }
        }
    }
}
