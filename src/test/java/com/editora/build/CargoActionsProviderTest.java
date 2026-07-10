package com.editora.build;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure coverage of the Cargo action list: subcommands, additive targets, and the --release toggle args. */
class CargoActionsProviderTest {

    private static List<String> taskLabels(BuildActionsProvider p) {
        return p.sections(Set.of()).stream()
                .flatMap(s -> s.rows().stream())
                .filter(r -> r instanceof BuildAction.Task)
                .map(r -> ((BuildAction.Task) r).label())
                .toList();
    }

    @Test
    void offersTheStandardSubcommandsAndAReleaseToggle() {
        CargoActionsProvider p = new CargoActionsProvider(List.of(), List.of());
        List<String> labels = taskLabels(p);
        assertTrue(labels.contains("build"));
        assertTrue(labels.contains("run"));
        assertTrue(labels.contains("clippy"));
        assertTrue(labels.contains("fmt"));

        boolean hasReleaseToggle = p.sections(Set.of()).stream()
                .flatMap(s -> s.rows().stream())
                .anyMatch(r -> r instanceof BuildAction.Toggle t && t.id().equals("release"));
        assertTrue(hasReleaseToggle);
    }

    @Test
    void binAndExampleTargetsAreAdditiveToThePlainRun() {
        CargoActionsProvider p = new CargoActionsProvider(List.of("cli"), List.of("demo"));
        List<String> labels = taskLabels(p);
        assertTrue(labels.contains("run"), "the plain run is never replaced");
        assertTrue(labels.contains("run --bin cli"));
        assertTrue(labels.contains("run --example demo"));
    }

    @Test
    void releaseToggleContributesTheReleaseFlagWhenActive() {
        CargoActionsProvider p = new CargoActionsProvider(List.of(), List.of());
        assertEquals(List.of(), p.toggleArgs(Set.of()));
        assertEquals(List.of("--release"), p.toggleArgs(Set.of("release")));
    }

    @Test
    void noTargetsSectionWhenThereAreNoExplicitTargets() {
        CargoActionsProvider p = new CargoActionsProvider(List.of(), List.of());
        assertFalse(labelForTargets(p), "no run --bin / run --example rows without explicit targets");
    }

    private static boolean labelForTargets(BuildActionsProvider p) {
        return taskLabels(p).stream().anyMatch(l -> l.startsWith("run --"));
    }
}
