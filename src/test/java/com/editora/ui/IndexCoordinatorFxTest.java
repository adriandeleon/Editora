package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.editora.index.SymbolIndex;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The index coordinator's contract with the window: it indexes only on demand, it says so rather than
 * silently doing nothing when there is no project, and a project switch does not leave the previous
 * project's symbols answering queries.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IndexCoordinatorFxTest {

    private FxWindowFixture fx;

    @TempDir
    Path project;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
    }

    @AfterAll
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    /** A coordinator over {@code project}, with the statuses it emits captured. */
    private record Harness(IndexCoordinator coordinator, List<String> statuses, List<Path> opened) {}

    private Harness harness(Path root) throws Exception {
        List<String> statuses = new java.util.ArrayList<>();
        List<Path> opened = new java.util.ArrayList<>();
        com.editora.config.Settings settings = new com.editora.config.Settings();
        CoordinatorHostStub host = new CoordinatorHostStub() {
            @Override
            public com.editora.config.Settings settings() {
                return settings; // the stub's default is null, and isEnabled() reads the feature flag
            }

            @Override
            public void setStatus(String message) {
                statuses.add(message);
            }
        };
        IndexCoordinator c = FxTestSupport.callOnFx(() -> new IndexCoordinator(host, new IndexCoordinator.Ops() {
            @Override
            public Path projectRoot() {
                return root;
            }

            @Override
            public void openAndGoto(Path file, int line, int column) {
                opened.add(file);
            }

            @Override
            public boolean respectGitignore() {
                return false;
            }
        }));
        return new Harness(c, statuses, opened);
    }

    private static SymbolIndex indexOf(IndexCoordinator c) throws Exception {
        return FxTestSupport.field(c, "index");
    }

    /** Runs the walk + apply synchronously enough to assert on, by pumping the FX queue until it lands. */
    private static void buildAndSettle(IndexCoordinator c) throws Exception {
        FxTestSupport.runOnFx(() -> FxTestSupport.invoke(c, "rebuild"));
        for (int i = 0; i < 80 && indexOf(c).symbolCount() == 0; i++) {
            Thread.sleep(25);
            FxTestSupport.runOnFx(() -> {});
        }
    }

    @Test
    void nothingIsIndexedUntilSomethingAsks() throws Exception {
        Files.writeString(project.resolve("A.java"), "class Alpha { void go() {} }\n");
        Harness h = harness(project);
        assertEquals(0, indexOf(h.coordinator()).symbolCount(), "indexing at construction would tax every window");
    }

    @Test
    void aBuildFindsTheProjectsSymbols() throws Exception {
        Files.writeString(project.resolve("B.java"), "class Beta {\n    void run() {\n    }\n}\n");
        Harness h = harness(project);
        buildAndSettle(h.coordinator());
        List<String> names = indexOf(h.coordinator()).search("beta").stream()
                .map(hit -> hit.symbol().name())
                .toList();
        assertTrue(names.contains("Beta"), "expected Beta in " + names);
    }

    @Test
    void switchingProjectDropsThePreviousOnesSymbols() throws Exception {
        Files.writeString(project.resolve("C.java"), "class Gamma {}\n");
        Harness h = harness(project);
        buildAndSettle(h.coordinator());
        assertFalse(indexOf(h.coordinator()).search("gamma").isEmpty());
        FxTestSupport.runOnFx(() -> FxTestSupport.invoke(h.coordinator(), "onProjectChanged"));
        assertTrue(
                indexOf(h.coordinator()).search("gamma").isEmpty(),
                "answering with the previous project's symbols would send the user to another repository");
    }

    @Test
    void withNoProjectItSaysSoRatherThanDoingNothing() throws Exception {
        Harness h = harness(null);
        FxTestSupport.runOnFx(() -> FxTestSupport.invoke(h.coordinator(), "gotoSymbol"));
        assertFalse(h.statuses().isEmpty(), "a command that silently no-ops reads as broken");
    }

    @Test
    void disposeDoesNotThrow() throws Exception {
        Harness h = harness(project);
        FxTestSupport.runOnFx(() -> FxTestSupport.invoke(h.coordinator(), "dispose"));
    }
}
