package com.editora.build;

import java.util.List;

/**
 * The toolkit-free model the build-tool actions popup renders — a flat list of {@link Section}s of {@link Row}s,
 * produced per tool by a {@link BuildActionsProvider}. This generalizes the Maven popup's Lifecycle /
 * Profiles / Plugins sections into a shape every build tool (Gradle/npm/Cargo/Go) can emit:
 *
 * <ul>
 *   <li>a {@link Task} is a runnable — the popup runs {@code executable + task.args + provider.toggleArgs(active)};
 *   <li>a {@link Toggle} is a checkable modifier (Maven's {@code <profile>}s; Cargo's {@code --release}) — flipping
 *       it re-queries {@link BuildActionsProvider#sections} (so a tool can reveal extra rows, e.g. a checked
 *       profile's nested goals) and contributes to {@link BuildActionsProvider#toggleArgs}.
 * </ul>
 *
 * <p>The popup owns its own "Run custom…" / "Load all tasks…" action rows; those are not modeled here.
 */
public final class BuildAction {

    private BuildAction() {}

    /** A titled group of rows (e.g. "Lifecycle", "Scripts", "Targets"). */
    public record Section(String title, List<Row> rows) {}

    /** A row: either a runnable {@link Task} or a checkable {@link Toggle}. */
    public sealed interface Row permits Task, Toggle {}

    /**
     * A runnable task. {@code args} is the argv tail appended after the tool's executable (e.g. {@code
     * ["package"]} for Maven, {@code ["run", "build"]} for npm, {@code ["run", "--bin", "x"]} for Cargo);
     * {@code tooltip} may be empty.
     */
    public record Task(String label, List<String> args, String tooltip) implements Row {
        public Task(String label, List<String> args) {
            this(label, args, "");
        }
    }

    /** A checkable modifier. {@code note} is an optional trailing annotation (Maven's "(active by default)"). */
    public record Toggle(String id, String label, String note) implements Row {
        public Toggle(String id, String label) {
            this(id, label, "");
        }
    }
}
