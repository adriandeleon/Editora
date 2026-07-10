package com.editora.build;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.editora.i18n.Messages.tr;

/**
 * The npm {@link BuildActionsProvider}: one runnable {@link BuildAction.Task} per {@code package.json} script
 * (run as {@code <pm> run <name>}, portable across npm/yarn/pnpm/bun) plus a small "Common" section of
 * built-ins ({@code install}; {@code ci} only when the manager is npm — yarn/pnpm/bun spell it differently).
 * No toggles. Scripts cover {@code test}/{@code start}/{@code build}/… so those aren't duplicated as built-ins;
 * a {@code package.json} with no scripts still offers the built-ins. Pure.
 */
public final class NpmActionsProvider implements BuildActionsProvider {

    private final List<String> scripts;
    private final String packageManager;

    public NpmActionsProvider(List<String> scripts, String packageManager) {
        this.scripts = List.copyOf(scripts);
        this.packageManager = packageManager;
    }

    @Override
    public List<BuildAction.Section> sections(Set<String> activeToggleIds) {
        List<BuildAction.Section> out = new ArrayList<>();
        if (!scripts.isEmpty()) {
            List<BuildAction.Row> rows = new ArrayList<>();
            for (String s : scripts) {
                rows.add(new BuildAction.Task(s, List.of("run", s)));
            }
            out.add(new BuildAction.Section(tr("buildpopup.section.scripts"), rows));
        }
        List<BuildAction.Row> common = new ArrayList<>();
        common.add(new BuildAction.Task("install", List.of("install")));
        if ("npm".equals(packageManager)) {
            common.add(new BuildAction.Task("ci", List.of("ci")));
        }
        out.add(new BuildAction.Section(tr("buildpopup.section.common"), common));
        return out;
    }
}
