package com.editora.build;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure coverage of the npm action provider: scripts → {@code run <name>}, install/ci built-ins, no toggles. */
class NpmActionsProviderTest {

    private static List<BuildAction.Task> tasks(BuildActionsProvider p) {
        return p.sections(Set.of()).stream()
                .flatMap(s -> s.rows().stream())
                .filter(r -> r instanceof BuildAction.Task)
                .map(r -> (BuildAction.Task) r)
                .toList();
    }

    @Test
    void eachScriptRunsViaRunAndCommonHasInstallNoCiForPnpm() {
        var p = new NpmActionsProvider(List.of("build", "test"), "pnpm");
        var tasks = tasks(p);
        assertTrue(tasks.stream()
                .anyMatch(t -> t.label().equals("build") && t.args().equals(List.of("run", "build"))));
        assertTrue(tasks.stream()
                .anyMatch(t -> t.label().equals("test") && t.args().equals(List.of("run", "test"))));
        assertTrue(tasks.stream().anyMatch(t -> t.args().equals(List.of("install"))));
        assertFalse(tasks.stream().anyMatch(t -> t.args().equals(List.of("ci"))), "ci is npm-only");
    }

    @Test
    void ciOfferedOnlyForNpm() {
        assertTrue(tasks(new NpmActionsProvider(List.of(), "npm")).stream()
                .anyMatch(t -> t.args().equals(List.of("ci"))));
    }

    @Test
    void noScriptsSectionWhenEmptyButBuiltInsRemain() {
        var p = new NpmActionsProvider(List.of(), "yarn");
        // No script tasks, but install is still there (built-ins only when scripts are missing).
        assertEquals(1, tasks(p).size());
        assertEquals(List.of("install"), tasks(p).get(0).args());
    }

    @Test
    void noTogglesForNpm() {
        assertTrue(new NpmActionsProvider(List.of("build"), "npm")
                .toggleArgs(Set.of("anything"))
                .isEmpty());
    }
}
