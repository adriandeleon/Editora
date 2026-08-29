package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.editora.index.SymbolIndex;
import com.editora.search.FuzzyMatch;
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

    /**
     * {@code searchFiles} selects the top {@code limit} with a bounded heap over cached relative paths
     * instead of relativizing everything and sorting every match (#876). As with the symbol side, the case
     * for the swap is that the output is unchanged — so this compares it against the algorithm it replaced.
     *
     * <p>The tree is built so that scores collide heavily: the same base names repeated under several
     * directories, which is exactly the shape a real project has and the shape where an eviction rule that
     * is subtly wrong about ties will diverge. A tree of distinct names would pass regardless.
     */
    @Test
    void fileSelectionReturnsExactlyWhatSortingEverythingWouldHave() throws Exception {
        String[] dirs = {"ui", "editor", "index", "ui/inner", "editor/deep"};
        String[] names = {"Main", "MainController", "Index", "Indexer", "Editor", "Search"};
        for (String d : dirs) {
            Files.createDirectories(project.resolve(d));
            for (String n : names) {
                Files.writeString(project.resolve(d).resolve(n + ".java"), "class " + n + " { void go() {} }\n");
            }
        }
        Harness h = harness(project);
        buildAndSettle(h.coordinator());

        List<String> rels = relativePathsOf(h.coordinator());
        assertFalse(rels.isEmpty(), "the walk found the tree");

        for (String q : List.of("m", "i", "e", "main", "index", "ui", "ui main", "editor", "zzz", "s")) {
            for (int limit : new int[] {1, 3, 5, 12, 40, 1000}) {
                List<String> actual = FxTestSupport.callOnFx(() -> hitPaths(h.coordinator(), q, limit));
                assertEquals(referenceFiles(rels, q, limit), actual, "query '" + q + "' limit " + limit);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> hitPaths(IndexCoordinator c, String query, int limit) {
        List<?> hits =
                (List<?>) FxTestSupport.call(c, "searchFiles", new Class[] {String.class, int.class}, query, limit);
        return hits.stream()
                .map(hit -> (String) FxTestSupport.call(hit, "relativePath", new Class[] {}))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static List<String> relativePathsOf(IndexCoordinator c) throws Exception {
        return FxTestSupport.callOnFx(() -> (List<String>) FxTestSupport.field(c, "projectRelPaths"));
    }

    /** The pre-#876 algorithm for the file source: score everything, sort it all, truncate. */
    private static List<String> referenceFiles(List<String> rels, String query, int limit) {
        record H(String rel, int score) {}
        List<H> hits = new java.util.ArrayList<>();
        for (String rel : rels) {
            FuzzyMatch.Match m = FuzzyMatch.ofPath(rel, query);
            if (m != null) {
                hits.add(new H(rel, m.score()));
            }
        }
        hits.sort(java.util.Comparator.comparingInt(H::score).reversed().thenComparing(H::rel));
        return hits.subList(0, Math.min(limit, hits.size())).stream()
                .map(H::rel)
                .toList();
    }
}
