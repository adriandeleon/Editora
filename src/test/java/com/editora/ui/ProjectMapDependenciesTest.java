package com.editora.ui;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Dependencies card's views of the project's file-to-file uses (Map code browser gap 14). */
class ProjectMapDependenciesTest {

    private static ProjectMapImports.Edge edge(String from, String to) {
        return new ProjectMapImports.Edge(from, to, 2, "import " + to + ";");
    }

    /**
     * app/Main uses core/Service, core/Repo and util/Strings; core/Service uses core/Repo and util/Strings;
     * core/Repo uses db/Db, which uses core/Repo back; Lonely.java uses nothing.
     */
    private static ProjectMapDependencies sample() {
        List<ProjectMapImports.Edge> edges = List.of(
                edge("app/Main.java", "core/Service.java"),
                edge("app/Main.java", "core/Repo.java"),
                edge("app/Main.java", "util/Strings.java"),
                edge("core/Service.java", "core/Repo.java"),
                edge("core/Service.java", "util/Strings.java"),
                edge("core/Repo.java", "db/Db.java"),
                edge("db/Db.java", "core/Repo.java"));
        Set<String> files = new LinkedHashSet<>();
        edges.forEach(e -> {
            files.add(e.from());
            files.add(e.to());
        });
        files.add("Lonely.java");
        return new ProjectMapDependencies(files, edges, false);
    }

    @Test
    void aroundAFileItsUsersAndWhatItUses() {
        ProjectMapDependencies deps = sample();
        assertEquals(6, deps.fileCount());
        assertEquals(7, deps.edgeCount());
        assertFalse(deps.truncated());
        assertTrue(deps.read("Lonely.java"));
        assertFalse(deps.read("README.md"));

        ProjectMapDependencies.Around service =
                deps.around("core/Service.java", false, ProjectMapDependencies.Grouping.FILES);
        assertEquals(List.of(new ProjectMapDependencies.Node("app/Main.java", false, 1, false)), service.users());
        assertEquals(
                List.of("core/Repo.java", "util/Strings.java"),
                service.uses().stream().map(ProjectMapDependencies.Node::rel).toList());
        assertEquals(1, service.userFiles());
        assertEquals(2, service.usedFiles());
        assertEquals(0, service.insideFiles());

        ProjectMapDependencies.Around repo =
                deps.around("core/Repo.java", false, ProjectMapDependencies.Grouping.FILES);
        assertTrue(
                repo.uses().contains(new ProjectMapDependencies.Node("db/Db.java", false, 1, true)),
                "Db uses Repo back: on both sides");

        ProjectMapDependencies.Around byFolder =
                deps.around("core/Repo.java", false, ProjectMapDependencies.Grouping.FOLDERS);
        assertEquals(
                List.of(
                        new ProjectMapDependencies.Node("app", true, 1, false),
                        new ProjectMapDependencies.Node("core", true, 1, false),
                        new ProjectMapDependencies.Node("db", true, 1, true)),
                byFolder.users());

        ProjectMapDependencies.Around lonely = deps.around("Lonely.java", false, ProjectMapDependencies.Grouping.FILES);
        assertTrue(lonely.users().isEmpty() && lonely.uses().isEmpty());
    }

    @Test
    void aroundAFolderOnlyWhatIsOutsideItCounts() {
        ProjectMapDependencies deps = sample();
        ProjectMapDependencies.Around core = deps.around("core", true, ProjectMapDependencies.Grouping.FOLDERS);
        assertEquals(
                List.of(
                        new ProjectMapDependencies.Node("app", true, 2, false),
                        new ProjectMapDependencies.Node("db", true, 1, true)),
                core.users(),
                "Service's use of Repo is inside");
        assertEquals(
                List.of(
                        new ProjectMapDependencies.Node("db", true, 1, true),
                        new ProjectMapDependencies.Node("util", true, 1, false)),
                core.uses());
        assertEquals(2, core.userFiles());
        assertEquals(2, core.usedFiles());
        assertEquals(2, core.insideFiles());

        assertEquals(
                List.of("db/Db.java"),
                deps.why("core", true, core.users().get(1), true).stream()
                        .map(ProjectMapImports.Edge::from)
                        .toList());
        assertEquals(
                List.of("app/Main.java -> core/Repo.java", "app/Main.java -> core/Service.java"),
                deps.why("core", true, core.users().get(0), true).stream()
                        .map(e -> e.from() + " -> " + e.to())
                        .sorted()
                        .toList());
        ProjectMapDependencies.Around file =
                deps.around("core/Service.java", false, ProjectMapDependencies.Grouping.FILES);
        assertEquals(
                List.of("core/Service.java -> util/Strings.java"),
                deps.why("core/Service.java", false, file.uses().get(1), false).stream()
                        .map(e -> e.from() + " -> " + e.to())
                        .toList());

        ProjectMapDependencies.Around whole = deps.around("", true, ProjectMapDependencies.Grouping.FOLDERS);
        assertTrue(whole.users().isEmpty() && whole.uses().isEmpty(), "everything is inside the project");
        assertEquals(6, whole.insideFiles());
    }

    @Test
    void aFoldersMatrixIsLayeredWithItsCyclesMarked() {
        ProjectMapDependencies deps = sample();
        ProjectMapDependencies.Matrix matrix = deps.matrix("");
        assertEquals("", matrix.folder());
        assertEquals(
                List.of("Lonely.java", "app", "core", "db", "util"),
                matrix.parts().stream().map(ProjectMapDependencies.Part::rel).toList(),
                "nothing uses Lonely or app; core and db use each other; util uses nothing");
        assertEquals(
                new ProjectMapDependencies.Part("Lonely.java", false, 1),
                matrix.parts().get(0));
        assertEquals(
                new ProjectMapDependencies.Part("core", true, 2), matrix.parts().get(2));
        assertArrayEquals(new int[] {0, 0, 2, 0, 1}, matrix.cells()[1]);
        assertArrayEquals(new int[] {0, 0, 0, 1, 1}, matrix.cells()[2]);
        assertArrayEquals(new int[] {0, 0, 1, 0, 0}, matrix.cells()[3], "below the diagonal: the cycle");
        assertTrue(matrix.cycle()[2][3] && matrix.cycle()[3][2]);
        assertFalse(matrix.cycle()[1][2]);
        assertEquals(6, matrix.uses());
        assertEquals(1, matrix.cycles());
        assertEquals(0, matrix.left());

        assertEquals(
                List.of("db/Db.java -> core/Repo.java"),
                deps.why("", "db", "core").stream()
                        .map(e -> e.from() + " -> " + e.to())
                        .toList());
        assertTrue(deps.matrix("Lonely.java").parts().isEmpty(), "a file has no parts");
    }

    @Test
    void aMatrixStartsWhereTheFilesBranchAndKeepsTheBusiestParts() {
        String base = "src/main/java/com/acme/";
        ProjectMapDependencies chain = new ProjectMapDependencies(
                List.of(base + "a/A.java", base + "b/B.java"),
                List.of(edge(base + "a/A.java", base + "b/B.java")),
                true);
        assertEquals("src/main/java/com/acme", chain.matrixFolder(""));
        assertEquals("src/main/java/com/acme", chain.matrix("src").folder());
        assertEquals(base + "a", chain.matrixFolder(base + "a"), "a folder of files stays");
        assertEquals("nowhere", chain.matrixFolder("nowhere"));
        assertTrue(chain.truncated());

        List<String> files = new ArrayList<>();
        List<ProjectMapImports.Edge> edges = new ArrayList<>();
        int count = ProjectMapDependencies.MAX_PARTS + 5;
        for (int i = 0; i < count; i++) {
            files.add("f" + i + "/F.java");
            if (i > 0 && i < ProjectMapDependencies.MAX_PARTS) {
                edges.add(edge("f" + (i - 1) + "/F.java", "f" + i + "/F.java"));
            }
        }
        ProjectMapDependencies.Matrix many = new ProjectMapDependencies(files, edges, false).matrix("");
        assertEquals(ProjectMapDependencies.MAX_PARTS, many.parts().size());
        assertEquals(5, many.left());
        assertEquals("f0", many.parts().get(0).rel(), "a chain is in its order");
        assertEquals(0, many.cycles());
    }

    @Test
    void partsAndInsideness() {
        assertEquals("a/b", ProjectMapDependencies.partOf("a/b/C.java", "a"));
        assertEquals("a/C.java", ProjectMapDependencies.partOf("a/C.java", "a"));
        assertEquals("x", ProjectMapDependencies.partOf("x/y.java", ""));
        assertEquals("y.java", ProjectMapDependencies.partOf("y.java", ""));
        assertTrue(ProjectMapDependencies.inside("a/b", ""));
        assertTrue(ProjectMapDependencies.inside("a/b", "a"));
        assertTrue(ProjectMapDependencies.inside("a", "a"));
        assertFalse(ProjectMapDependencies.inside("ab/c", "a"));
        assertEquals(0, ProjectMapDependencies.EMPTY.fileCount());
    }
}
