package com.editora.build;

import java.util.List;
import java.util.Set;

/**
 * Produces the {@link BuildAction.Section}s a build tool's actions popup shows, given the currently-active
 * {@link BuildAction.Toggle} ids, plus the extra argv those active toggles contribute. One implementation per
 * tool (Maven/Gradle/npm/Cargo/Go), built from that tool's parsed project file. Pure — no toolkit, no I/O
 * beyond what the caller has already parsed.
 *
 * <p>{@link #sections} is re-queried on every toggle flip (so a tool like Maven can reveal a checked
 * profile's nested goals); a tool with no dynamic reveal simply ignores {@code activeToggleIds} there.
 */
public interface BuildActionsProvider {

    /** The sections to display given the active toggle ids. */
    List<BuildAction.Section> sections(Set<String> activeToggleIds);

    /** The extra argv contributed by the active toggles (e.g. Maven's merged {@code -Pa,b}), appended after a
     *  task's own args. Empty when no toggle contributes args. */
    default List<String> toggleArgs(Set<String> activeToggleIds) {
        return List.of();
    }

    /** Merges tasks discovered on demand (Gradle's "Load all tasks…", which enumerates {@code gradle tasks}
     *  on a short-lived process) into a provider whose task list can't be fully parsed up front. No-op for the
     *  statically-parsed tools. */
    default void addLoadedTasks(List<String> tasks) {}
}
