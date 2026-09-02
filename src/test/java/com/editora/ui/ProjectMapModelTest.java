package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    void focusedExpansionReplacesSiblingBranchesAndCollapsesDescendants() {
        Path src = root.resolve("src");
        Path main = src.resolve("main");
        Path docs = root.resolve("docs");
        Set<Path> srcBranch = ProjectMapModel.toggleFocusedExpansion(root, Set.of(root), src);
        srcBranch = ProjectMapModel.toggleFocusedExpansion(root, srcBranch, main);
        assertEquals(Set.of(normalize(root), normalize(src), normalize(main)), srcBranch);

        Set<Path> docsBranch = ProjectMapModel.toggleFocusedExpansion(root, srcBranch, docs);
        assertEquals(Set.of(normalize(root), normalize(docs)), docsBranch);

        assertEquals(Set.of(normalize(root)), ProjectMapModel.toggleFocusedExpansion(root, docsBranch, docs));
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

    private static Path normalize(Path path) {
        return ProjectMapModel.normalize(path);
    }

    private static boolean has(List<ProjectMapModel.Entry> entries, Path path) {
        Path normalized = ProjectMapModel.normalize(path);
        return entries.stream().anyMatch(entry -> entry.path().equals(normalized));
    }
}
