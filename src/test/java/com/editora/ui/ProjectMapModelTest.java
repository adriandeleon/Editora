package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectMapModelTest {

    @TempDir
    Path root;

    @Test
    void visibleSnapshotOnlyDescendsIntoExpandedDirectories() throws Exception {
        Path src = Files.createDirectory(root.resolve("src"));
        Path packageDir = Files.createDirectory(src.resolve("example"));
        Path source = Files.writeString(packageDir.resolve("App.java"), "class App {}");
        Path readme = Files.writeString(root.resolve("README.md"), "# Test");

        List<ProjectMapModel.Entry> collapsed = ProjectMapModel.loadVisible(root, Set.of(root), false);
        assertTrue(has(collapsed, src));
        assertTrue(has(collapsed, readme));
        assertFalse(has(collapsed, packageDir));

        List<ProjectMapModel.Entry> expanded = ProjectMapModel.loadVisible(root, Set.of(root, src, packageDir), false);
        assertTrue(has(expanded, packageDir));
        assertTrue(has(expanded, source));
    }

    @Test
    void hiddenEntriesFollowTheProjectVisibilitySetting() throws Exception {
        Path hidden = Files.writeString(root.resolve(".env"), "SECRET=test");

        assertFalse(has(ProjectMapModel.loadVisible(root, Set.of(root), false), hidden));
        assertTrue(has(ProjectMapModel.loadVisible(root, Set.of(root), true), hidden));
    }

    @Test
    void visibleSnapshotCapturesFileMetadataOffTheFxThread() throws Exception {
        Path file = Files.writeString(root.resolve("notes.txt"), "hello");

        ProjectMapModel.Entry entry = ProjectMapModel.loadVisible(root, Set.of(root), false).stream()
                .filter(candidate ->
                        candidate.path().equals(file.toAbsolutePath().normalize()))
                .findFirst()
                .orElseThrow();

        assertEquals(5, entry.size());
        assertTrue(entry.modifiedMillis() > 0);
        assertFalse(entry.symbolicLink());
    }

    @Test
    void statusChipsAreAlternativesAndTypeIsAnAdditionalConstraint() {
        ProjectMapModel.Entry java = new ProjectMapModel.Entry(root.resolve("App.java"), root, 1, false);
        var workingSet = new ProjectMapModel.Filters("", true, true, false, ProjectMapModel.TypeFilter.ALL);
        assertTrue(ProjectMapModel.matches(java, workingSet, true, false, false));
        assertTrue(ProjectMapModel.matches(java, workingSet, false, true, false));
        assertFalse(ProjectMapModel.matches(java, workingSet, false, false, true));

        var sourceOnly = new ProjectMapModel.Filters("app", false, false, false, ProjectMapModel.TypeFilter.SOURCE);
        assertTrue(ProjectMapModel.matches(java, sourceOnly, false, false, false));
        ProjectMapModel.Entry markdown = new ProjectMapModel.Entry(root.resolve("app.md"), root, 1, false);
        assertFalse(ProjectMapModel.matches(markdown, sourceOnly, false, false, false));
    }

    @Test
    void emphasizedMatchesKeepTheirVisibleAncestors() {
        Path src = root.resolve("src");
        Path file = src.resolve("App.java");
        List<ProjectMapModel.Entry> entries = List.of(
                new ProjectMapModel.Entry(root, null, 0, true),
                new ProjectMapModel.Entry(src, root, 1, true),
                new ProjectMapModel.Entry(file, src, 2, false),
                new ProjectMapModel.Entry(root.resolve("README.md"), root, 1, false));

        Set<Path> emphasized = ProjectMapModel.emphasized(entries, Set.of(file));
        assertTrue(emphasized.contains(ProjectMapModel.normalize(root)));
        assertTrue(emphasized.contains(ProjectMapModel.normalize(src)));
        assertTrue(emphasized.contains(ProjectMapModel.normalize(file)));
        assertFalse(emphasized.contains(ProjectMapModel.normalize(root.resolve("README.md"))));
    }

    @Test
    void expansionRetainsSiblingBranchesAndCollapsesOnlyItsOwnDescendants() {
        Path src = root.resolve("src");
        Path main = src.resolve("main");
        Path docs = root.resolve("docs");
        Set<Path> srcBranch = ProjectMapModel.toggleExpansion(root, Set.of(root), src);
        srcBranch = ProjectMapModel.toggleExpansion(root, srcBranch, main);
        assertEquals(Set.of(normalize(root), normalize(src), normalize(main)), srcBranch);

        Set<Path> bothBranches = ProjectMapModel.toggleExpansion(root, srcBranch, docs);
        assertEquals(Set.of(normalize(root), normalize(src), normalize(main), normalize(docs)), bothBranches);

        assertEquals(
                Set.of(normalize(root), normalize(src), normalize(main)),
                ProjectMapModel.toggleExpansion(root, bothBranches, docs));
        assertEquals(
                Set.of(normalize(root), normalize(docs)), ProjectMapModel.toggleExpansion(root, bothBranches, src));
    }

    @Test
    void searchMatchesExpandEveryAncestorWithoutIncludingOutsidePaths() {
        Path first = root.resolve("src/main/java/FileResult.java");
        Path second = root.resolve("src/test/java/FileResultTest.java");

        Set<Path> expanded = ProjectMapModel.expandedAncestors(
                root, List.of(first, second, root.resolveSibling("outside/Other.java")));

        assertEquals(
                Set.of(
                        normalize(root),
                        normalize(root.resolve("src")),
                        normalize(root.resolve("src/main")),
                        normalize(root.resolve("src/main/java")),
                        normalize(root.resolve("src/test")),
                        normalize(root.resolve("src/test/java"))),
                expanded);
    }

    @Test
    void siblingBranchesProduceIndependentColumnsAtTheSameDepth() {
        Path src = root.resolve("src");
        Path docs = root.resolve("docs");
        Path java = src.resolve("App.java");
        Path markdown = docs.resolve("guide.md");
        List<ProjectMapModel.Entry> entries = List.of(
                new ProjectMapModel.Entry(root, null, 0, true),
                new ProjectMapModel.Entry(src, root, 1, true),
                new ProjectMapModel.Entry(docs, root, 1, true),
                new ProjectMapModel.Entry(java, src, 2, false),
                new ProjectMapModel.Entry(markdown, docs, 2, false));

        List<ProjectMapModel.Column> columns = ProjectMapModel.columnsById(entries, Map.of(), Map.of());

        assertEquals(4, columns.size());
        assertEquals(
                Set.of(normalize(src), normalize(docs)),
                columns.stream()
                        .filter(column -> column.depth() == 2)
                        .map(ProjectMapModel.Column::parent)
                        .collect(Collectors.toSet()));
    }

    @Test
    void columnFiltersKeepAValidVisibleHierarchy() {
        Path src = root.resolve("src");
        Path docs = root.resolve("docs");
        Path java = src.resolve("App.java");
        Path markdown = docs.resolve("guide.md");
        List<ProjectMapModel.Entry> entries = List.of(
                new ProjectMapModel.Entry(root, null, 0, true),
                new ProjectMapModel.Entry(src, root, 1, true),
                new ProjectMapModel.Entry(docs, root, 1, true),
                new ProjectMapModel.Entry(java, src, 2, false),
                new ProjectMapModel.Entry(markdown, docs, 2, false));

        List<ProjectMapModel.Column> columns = ProjectMapModel.columns(entries, Map.of(1, "src"));
        assertEquals(
                List.of(normalize(src)),
                columns.get(1).entries().stream()
                        .map(ProjectMapModel.Entry::path)
                        .toList());
        assertEquals(
                List.of(normalize(java)),
                columns.get(2).entries().stream()
                        .map(ProjectMapModel.Entry::path)
                        .toList());
        assertEquals(2, columns.get(1).totalEntries());
    }

    @Test
    void columnsUseExplorerOrderingAndCanHideDotEntriesPerDepth() {
        Path src = root.resolve("src");
        Path docs = root.resolve("Docs");
        Path hidden = root.resolve(".config");
        Path hiddenChild = hidden.resolve("settings.json");
        Path readme = root.resolve("README.md");
        Path authors = root.resolve("authors.txt");
        List<ProjectMapModel.Entry> entries = List.of(
                new ProjectMapModel.Entry(root, null, 0, true),
                new ProjectMapModel.Entry(readme, root, 1, false),
                new ProjectMapModel.Entry(src, root, 1, true),
                new ProjectMapModel.Entry(authors, root, 1, false),
                new ProjectMapModel.Entry(hidden, root, 1, true),
                new ProjectMapModel.Entry(docs, root, 1, true),
                new ProjectMapModel.Entry(hiddenChild, hidden, 2, false));

        List<ProjectMapModel.Column> shown = ProjectMapModel.columns(entries, Map.of(), Map.of());
        assertEquals(
                List.of(normalize(hidden), normalize(docs), normalize(src), normalize(authors), normalize(readme)),
                shown.get(1).entries().stream().map(ProjectMapModel.Entry::path).toList());

        List<ProjectMapModel.Column> hiddenAtDepthOne = ProjectMapModel.columns(entries, Map.of(), Map.of(1, false));
        assertFalse(hiddenAtDepthOne.get(1).entries().stream()
                .anyMatch(entry -> entry.path().equals(normalize(hidden))));
        assertEquals(2, hiddenAtDepthOne.size(), "hidden ancestors must also hide descendant columns");
    }

    private static Path normalize(Path path) {
        return ProjectMapModel.normalize(path);
    }

    private static boolean has(List<ProjectMapModel.Entry> entries, Path path) {
        Path normalized = ProjectMapModel.normalize(path);
        return entries.stream().anyMatch(entry -> entry.path().equals(normalized));
    }
}
