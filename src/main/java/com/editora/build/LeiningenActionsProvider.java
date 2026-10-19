package com.editora.build;

import java.util.List;
import java.util.Set;

import static com.editora.i18n.Messages.tr;

/**
 * The Leiningen {@link BuildActionsProvider}: a Commands section of the usual {@code lein} tasks over the project
 * ({@code run}, {@code test}, {@code check}, {@code uberjar}, …). Static — a {@code project.clj} is code, so its
 * aliases and plugins' tasks are not read; "Run custom…" covers them. No toggles. Pure.
 */
public final class LeiningenActionsProvider implements BuildActionsProvider {

    private static final List<BuildAction.Row> TASKS = List.of(
            new BuildAction.Task("run", List.of("run")),
            new BuildAction.Task("test", List.of("test")),
            new BuildAction.Task("check", List.of("check")),
            new BuildAction.Task("compile", List.of("compile")),
            new BuildAction.Task("jar", List.of("jar")),
            new BuildAction.Task("uberjar", List.of("uberjar")),
            new BuildAction.Task("install", List.of("install")),
            new BuildAction.Task("deps", List.of("deps")),
            new BuildAction.Task("deps :tree", List.of("deps", ":tree")),
            new BuildAction.Task("clean", List.of("clean")));

    @Override
    public List<BuildAction.Section> sections(Set<String> activeToggleIds) {
        return List.of(new BuildAction.Section(tr("buildpopup.section.commands"), TASKS));
    }
}
