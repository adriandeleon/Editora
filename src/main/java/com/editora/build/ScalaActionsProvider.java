package com.editora.build;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.editora.i18n.Messages.tr;

/**
 * The {@link BuildActionsProvider} of the Scala build tools — sbt, Mill and Scala CLI: a Common section of the
 * usual tasks plus, for sbt and Mill, an All-tasks section of each subproject's or module's tasks once the user
 * triggers "Load all tasks…" ({@code sbt projects} / {@code mill resolve __.compile}, fed in through
 * {@link #addLoadedTasks}). None of the three build definitions can be read without running the tool (they are
 * Scala programs), so this is Gradle's discovery story. No toggles. Pure.
 */
public final class ScalaActionsProvider implements BuildActionsProvider {

    /** One common task: its label and the argv after the tool's executable. */
    private record Common(String label, List<String> args) {}

    private final List<Common> common;
    private final List<String> discovered = new ArrayList<>();

    private ScalaActionsProvider(List<Common> common) {
        this.common = List.copyOf(common);
    }

    /** sbt: its standard tasks, each run as a batch command ({@code sbt compile}). */
    public static ScalaActionsProvider sbt() {
        List<Common> tasks = new ArrayList<>();
        for (String t : List.of("compile", "test", "run", "clean", "package", "Test/compile", "update", "doc")) {
            tasks.add(new Common(t, List.of(t)));
        }
        return new ScalaActionsProvider(tasks);
    }

    /** Mill: every module's compile and test ({@code __} is all modules), clean, and the module list. */
    public static ScalaActionsProvider mill() {
        List<Common> tasks = new ArrayList<>();
        for (String t : List.of("__.compile", "__.test", "clean", "resolve _")) {
            tasks.add(new Common(t, List.of(t.split(" "))));
        }
        return new ScalaActionsProvider(tasks);
    }

    /** Scala CLI: its commands over the project folder ({@code scala-cli compile .}). */
    public static ScalaActionsProvider scalaCli() {
        List<Common> tasks = new ArrayList<>();
        for (String t : List.of("compile", "run", "test", "package", "fmt", "clean")) {
            tasks.add(new Common(t, List.of(t, ".")));
        }
        return new ScalaActionsProvider(tasks);
    }

    @Override
    public List<BuildAction.Section> sections(Set<String> activeToggleIds) {
        List<BuildAction.Section> out = new ArrayList<>();
        List<BuildAction.Row> rows = new ArrayList<>();
        for (Common c : common) {
            rows.add(new BuildAction.Task(c.label(), c.args()));
        }
        out.add(new BuildAction.Section(tr("buildpopup.section.common"), rows));
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
            boolean isCommon = common.stream().anyMatch(c -> c.label().equals(t));
            if (t != null && !t.isBlank() && !isCommon && !discovered.contains(t)) {
                discovered.add(t);
            }
        }
    }
}
