package com.editora.build;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.editora.i18n.Messages.tr;

/**
 * The Cargo {@link BuildActionsProvider}: a Commands section of the standard subcommands (build/run/test/…
 * plus clippy/fmt), an optional Targets section of {@code run --bin X}/{@code run --example Y} for each
 * explicit {@code [[bin]]}/{@code [[example]]} (additive — never replacing the plain {@code run}), and an
 * Options section with a {@code --release} {@link BuildAction.Toggle} that composes into every run's argv. A
 * virtual {@code [workspace]} manifest simply has no targets. Pure.
 */
public final class CargoActionsProvider implements BuildActionsProvider {

    private static final List<String> SUBCOMMANDS =
            List.of("build", "run", "test", "check", "clean", "doc", "bench", "update", "clippy", "fmt");

    private final List<String> binNames;
    private final List<String> exampleNames;

    public CargoActionsProvider(List<String> binNames, List<String> exampleNames) {
        this.binNames = List.copyOf(binNames);
        this.exampleNames = List.copyOf(exampleNames);
    }

    @Override
    public List<BuildAction.Section> sections(Set<String> activeToggleIds) {
        List<BuildAction.Section> out = new ArrayList<>();

        List<BuildAction.Row> commands = new ArrayList<>();
        for (String c : SUBCOMMANDS) {
            commands.add(new BuildAction.Task(c, List.of(c)));
        }
        out.add(new BuildAction.Section(tr("buildpopup.section.commands"), commands));

        if (!binNames.isEmpty() || !exampleNames.isEmpty()) {
            List<BuildAction.Row> targets = new ArrayList<>();
            for (String b : binNames) {
                targets.add(new BuildAction.Task("run --bin " + b, List.of("run", "--bin", b)));
            }
            for (String e : exampleNames) {
                targets.add(new BuildAction.Task("run --example " + e, List.of("run", "--example", e)));
            }
            out.add(new BuildAction.Section(tr("buildpopup.section.targets"), targets));
        }

        out.add(new BuildAction.Section(
                tr("buildpopup.section.options"),
                List.of(new BuildAction.Toggle("release", tr("cargopopup.release")))));
        return out;
    }

    @Override
    public List<String> toggleArgs(Set<String> activeToggleIds) {
        return activeToggleIds.contains("release") ? List.of("--release") : List.of();
    }
}
