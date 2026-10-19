package com.editora.build;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.editora.i18n.Messages.tr;

/**
 * The Clojure CLI {@link BuildActionsProvider}: an Aliases section of the {@code deps.edn} aliases that run
 * something ({@code -M:test}, {@code -X:build}, {@code -T:build}; see {@link ClojureProjects#aliases}), then a
 * Commands section of the CLI's own: download the dependencies ({@code -P}), print the dependency tree
 * ({@code -X:deps tree}) and the classpath ({@code -Spath}). No toggles. Pure.
 */
public final class ClojureCliActionsProvider implements BuildActionsProvider {

    private static final List<BuildAction.Row> COMMANDS = List.of(
            new BuildAction.Task("prepare (-P)", List.of("-P")),
            new BuildAction.Task("deps tree", List.of("-X:deps", "tree")),
            new BuildAction.Task("deps list", List.of("-X:deps", "list")),
            new BuildAction.Task("classpath (-Spath)", List.of("-Spath")));

    private final List<ClojureProjects.Alias> aliases;

    public ClojureCliActionsProvider(List<ClojureProjects.Alias> aliases) {
        this.aliases = aliases == null ? List.of() : List.copyOf(aliases);
    }

    @Override
    public List<BuildAction.Section> sections(Set<String> activeToggleIds) {
        List<BuildAction.Section> out = new ArrayList<>();
        if (!aliases.isEmpty()) {
            List<BuildAction.Row> rows = new ArrayList<>();
            for (ClojureProjects.Alias a : aliases) {
                String flag = a.kind().flag() + ":" + a.name();
                rows.add(new BuildAction.Task(flag, List.of(flag)));
            }
            out.add(new BuildAction.Section(tr("buildpopup.section.aliases"), rows));
        }
        out.add(new BuildAction.Section(tr("buildpopup.section.commands"), COMMANDS));
        return out;
    }
}
