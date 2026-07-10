package com.editora.build;

import java.util.List;
import java.util.Set;

import static com.editora.i18n.Messages.tr;

/**
 * The Go {@link BuildActionsProvider}: a single Commands section of the standard {@code go} subcommands over
 * the whole module ({@code build ./...}, {@code test ./...}, {@code mod tidy}, …). Static — Go has no
 * declarative target list to parse (the {@code go.mod} module line is display-only, read separately for the
 * Settings label). No toggles. Pure.
 */
public final class GoActionsProvider implements BuildActionsProvider {

    private static final List<BuildAction.Row> TASKS = List.of(
            new BuildAction.Task("build", List.of("build", "./...")),
            new BuildAction.Task("run", List.of("run", ".")),
            new BuildAction.Task("test", List.of("test", "./...")),
            new BuildAction.Task("vet", List.of("vet", "./...")),
            new BuildAction.Task("fmt", List.of("fmt", "./...")),
            new BuildAction.Task("mod tidy", List.of("mod", "tidy")),
            new BuildAction.Task("mod download", List.of("mod", "download")),
            new BuildAction.Task("generate", List.of("generate", "./...")),
            new BuildAction.Task("clean", List.of("clean")),
            new BuildAction.Task("install", List.of("install")));

    @Override
    public List<BuildAction.Section> sections(Set<String> activeToggleIds) {
        return List.of(new BuildAction.Section(tr("buildpopup.section.commands"), TASKS));
    }
}
