package com.editora.ui;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.editora.search.GitignoreFilter;
import com.editora.search.ProjectFileList;
import com.editora.ui.ProjectMapModel.Request;
import com.editora.ui.ProjectMapSearchResult.Kind;
import com.editora.ui.ProjectMapSearchResult.Match;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure checks for the Project Map review 3 search fixes: a complete file list instead of a bounded walk (A1, A3,
 * A16), folders and paths as candidates (A5), the ranking (A6, A7), declarations (E2) and remote listings (A4).
 */
class ProjectMapSearchReview3Test {

    private static final ProjectFileList.Settings DEFAULT = new ProjectFileList.Settings(true, false);

    @TempDir
    Path temp;

    // --- A1 / A3: the whole project, however deep ----------------------------------------------------

    @Test
    void aDeepTreeOfMoreThanTwentyThousandEntriesIsSearchedToTheBottom() throws IOException {
        Path root = root();
        // 20,100 shallow files ahead of the deep ones in breadth-first order: the old bounded walk (20,000
        // entries visited) never got past them.
        for (int dir = 0; dir < 100; dir++) {
            Path folder = Files.createDirectories(root.resolve("wide").resolve(String.format("d%03d", dir)));
            for (int file = 0; file < 201; file++) {
                Files.createFile(folder.resolve(String.format("f%03d.txt", file)));
            }
        }
        Path deep = root;
        List<Path> wanted = new ArrayList<>();
        for (int level = 0; level < 14; level++) {
            deep = deep.resolve("level" + level);
            if (level >= 8) {
                Files.createDirectories(deep);
                wanted.add(Files.createFile(deep.resolve("types.go")));
            }
        }
        ProjectPanel.SearchResult old =
                ProjectPanel.searchRanked(root, "types.go", false, GitignoreFilter.NONE, () -> false);
        assertTrue(old.partial(), "the tree filter's bounded walk still stops short (it is the tree's, not the Map's)");
        assertTrue(old.matches().isEmpty(), "and it saw none of the deep files: " + old.matches());

        ProjectFileList list = ProjectFileList.build(root, DEFAULT, () -> false);
        assertNotNull(list);
        assertTrue(list.fileCount() > 20_000, "every file is listed: " + list.fileCount());
        ProjectMapSearchResult found = search(root, list, "types.go");
        assertFalse(found.partial(), "the list covers the whole project");
        assertEquals(wanted.size(), found.total());
        assertEquals(
                Set.copyOf(wanted),
                Set.copyOf(found.matches().stream().map(Match::path).toList()),
                "every types.go below level 8 is found");
        assertEquals(wanted.getFirst(), found.first().path(), "the shallowest of the equal matches first");
    }

    @Test
    void aListingCutByTheDepthLimitSaysSoAsDoesTheTreeFilter() throws IOException {
        Path root = root();
        Path deep = root;
        for (int level = 0; level < ProjectFileList.MAX_DEPTH + 2; level++) {
            deep = deep.resolve("d");
        }
        Files.createDirectories(deep);
        Files.createFile(deep.resolve("bottom.txt"));
        ProjectFileList list = ProjectFileList.build(root, DEFAULT, () -> false);
        assertNotNull(list);
        assertTrue(list.truncated(), "a folder left unentered for depth makes the list partial (A16)");
        assertTrue(search(root, list, "bottom").partial());

        ProjectPanel.SearchResult tree =
                ProjectPanel.searchRanked(root, "bottom", false, GitignoreFilter.NONE, () -> false);
        assertTrue(tree.partial(), "the tree filter's depth limit is reported too (A16)");
    }

    @Test
    void ignoredFoldersAreNotListedAndHiddenOnesOnlyWhenAsked() throws IOException {
        Path root = root();
        Files.writeString(root.resolve(".gitignore"), "build/\n");
        write(root, "build/out/Main.class");
        write(root, "node_modules/left-pad/index.js");
        write(root, ".config/settings.json");
        write(root, ".git/HEAD");
        write(root, "src/Main.java");
        ProjectFileList list = ProjectFileList.build(root, DEFAULT, () -> false);
        assertNotNull(list);
        assertEquals(
                Set.of("src/Main.java"),
                Set.copyOf(list.snapshot().files()),
                "ignored, dependency and hidden folders are pruned; dot-files are not listed");
        ProjectFileList hidden = ProjectFileList.build(root, new ProjectFileList.Settings(true, true), () -> false);
        assertNotNull(hidden);
        assertTrue(hidden.snapshot().files().contains(".config/settings.json"), "Show hidden lists dot-folders");
        assertFalse(hidden.snapshot().files().contains(".git/HEAD"), "but never the repository's own folder");
        assertTrue(hidden.snapshot().folders().contains("src"));
    }

    @Test
    void theListFollowsWhatTheWatcherAndTheAppReport() throws IOException {
        Path root = root();
        Files.writeString(root.resolve(".gitignore"), "*.log\n");
        write(root, "pkg/a/one.go");
        write(root, "pkg/b/two.go");
        ProjectFileList list = ProjectFileList.build(root, DEFAULT, () -> false);
        assertNotNull(list);
        ProjectFileList.Snapshot before = list.snapshot();

        Path added = write(root, "pkg/a/three.go");
        Path log = write(root, "pkg/a/debug.log");
        Path created = Files.createDirectories(root.resolve("cmd/tool"));
        write(root, "cmd/tool/main.go");
        list.changed(List.of(added, log, root.resolve("cmd")));
        assertTrue(list.snapshot().files().contains("pkg/a/three.go"));
        assertFalse(list.snapshot().files().contains("pkg/a/debug.log"), "an ignored file stays out");
        assertTrue(list.snapshot().files().contains("cmd/tool/main.go"), "a created folder is listed below");
        assertTrue(list.snapshot().folders().containsAll(List.of("cmd", "cmd/tool")));
        assertTrue(created.toFile().exists());

        deleteTree(root.resolve("pkg"));
        list.changed(List.of(root.resolve("pkg")));
        assertFalse(list.snapshot().files().stream().anyMatch(rel -> rel.startsWith("pkg/")), "a gone folder goes");
        assertFalse(list.snapshot().folders().contains("pkg/a"));
        assertTrue(before.files().contains("pkg/b/two.go"), "an earlier snapshot is not changed under its reader");
        assertFalse(list.stale());
    }

    @Test
    void aCreatedFolderTooBigToListMarksTheListStale() throws IOException {
        Path root = root();
        write(root, "a.txt");
        ProjectFileList list = ProjectFileList.build(root, DEFAULT, () -> false);
        assertNotNull(list);
        Path big = Files.createDirectories(root.resolve("unpacked"));
        for (int index = 0; index < 5_010; index++) {
            Files.createFile(big.resolve("f" + index));
        }
        list.changed(List.of(big));
        assertTrue(list.stale(), "a checkout's worth of new files is listed again in full, not one by one");
    }

    // --- A5: folders and paths ----------------------------------------------------------------------

    @Test
    void aComponentsFolderRanksAboveFilesOfTheSameName() throws IOException {
        Path root = root();
        write(root, "cmd/kube-scheduler/scheduler.go");
        write(root, "staging/src/k8s.io/kube-scheduler/config/v1/types.go");
        write(root, "test/fixtures/doc-yaml/admin/high-availability/kube-scheduler.yaml");
        write(root, "cluster/gce/manifests/kube-scheduler.manifest");
        ProjectMapSearchResult found = search(root, "kube-scheduler");
        List<String> top =
                found.matches().stream().limit(2).map(Match::relativePath).toList();
        assertEquals(List.of("cmd/kube-scheduler", "staging/src/k8s.io/kube-scheduler"), top);
        assertEquals(Kind.FOLDER, found.first().kind());
        assertTrue(found.matches().stream()
                .anyMatch(m -> m.relativePath().equals("cluster/gce/manifests/kube-scheduler.manifest")));
    }

    @Test
    void aPathQueryNarrowsACommonNameAndATrailingSlashAsksForFolders() throws IOException {
        Path root = root();
        write(root, "staging/src/k8s.io/api/core/v1/types.go");
        write(root, "staging/src/k8s.io/api/apps/v1/types.go");
        write(root, "pkg/proxy/types.go");
        write(root, "cmd/kube-scheduler/app/server.go");
        ProjectMapSearchResult types = search(root, "core/v1/types.go");
        assertEquals("staging/src/k8s.io/api/core/v1/types.go", types.first().relativePath());
        assertEquals(
                ProjectSearchRank.EXACT, ProjectMapSearch.pathTier(types.first().relativePath(), "core/v1/types.go"));

        ProjectMapSearchResult folder = search(root, "cmd/kube-scheduler");
        assertEquals("cmd/kube-scheduler", folder.first().relativePath());
        assertEquals(Kind.FOLDER, folder.first().kind());

        ProjectMapSearchResult onlyFolders = search(root, "kube-scheduler/");
        assertTrue(onlyFolders.matches().stream().allMatch(m -> m.kind() == Kind.FOLDER), onlyFolders.toString());
        assertFalse(onlyFolders.isEmpty());

        ProjectMapSearchResult onlyFiles = search(root, "#kube-scheduler");
        assertTrue(onlyFiles.matches().stream().noneMatch(m -> m.kind() == Kind.FOLDER), "# is files only, as in SE");

        ProjectMapSearchResult words = search(root, "proxy types");
        assertEquals("pkg/proxy/types.go", words.first().relativePath(), "several words match across the path");
    }

    @Test
    void aFolderNeedsTheQueryAsWrittenNotScatteredLetters() throws IOException {
        Path root = root();
        write(root, "kustomize-builder-letters/readme.txt"); // k-u-b-e-l-e-t scattered
        write(root, "pkg/kubelet/kubelet.go");
        ProjectMapSearchResult found = search(root, "kubelet");
        assertTrue(found.matches().stream().noneMatch(m -> m.relativePath().equals("kustomize-builder-letters")));
        assertEquals("pkg/kubelet", found.first().relativePath());
    }

    // --- A6 / A7: ranking ---------------------------------------------------------------------------

    @Test
    void rootFilesRankAboveNestedFilesOfTheSameName() throws IOException {
        Path root = root();
        for (String rel : List.of("buildSrc/README.md", "docs/README.md", "README.md", "a/b/README.md")) {
            write(root, rel);
        }
        write(root, "go.mod");
        write(root, "staging/src/k8s.io/api/go.mod");
        assertEquals("README.md", search(root, "README.md").first().relativePath());
        assertEquals("README.md", search(root, "README").first().relativePath());
        assertEquals("go.mod", search(root, "go.mod").first().relativePath());
    }

    @Test
    void theTypeInItsOwnCaseAndSourceBeatResourcesTestsDocsAndVendor() throws IOException {
        Path root = root();
        write(root, "spring-context/src/test/resources/org/springframework/jmx/applicationContext.xml");
        write(root, "spring-webmvc/src/test/resources/org/springframework/web/applicationContext.xml");
        write(root, "spring-context/src/main/java/org/springframework/context/ApplicationContext.java");
        write(root, "framework-docs/modules/ROOT/pages/core/beans/annotation-config/autowired.adoc");
        write(root, "spring-beans/src/main/java/org/springframework/beans/factory/annotation/Autowired.java");
        write(root, "vendor/github.com/grpc/clientset.go");
        write(root, "staging/src/k8s.io/client-go/kubernetes/clientset.go");
        write(root, "pkg/scheduler/schedule_one_test.go");
        write(root, "pkg/scheduler/schedule_one.go");
        assertTrue(search(root, "ApplicationContext").first().relativePath().endsWith("ApplicationContext.java"));
        assertTrue(search(root, "Autowired").first().relativePath().endsWith("Autowired.java"));
        assertEquals(
                "staging/src/k8s.io/client-go/kubernetes/clientset.go",
                search(root, "clientset.go").first().relativePath(),
                "project code before vendored code");
        assertEquals(
                "pkg/scheduler/schedule_one.go",
                search(root, "schedule_one").first().relativePath());
        assertTrue(ProjectMapSearch.secondary("src/test/java/FooTest.java", false));
        assertTrue(ProjectMapSearch.secondary("pkg/a/b_test.go", false));
        assertFalse(ProjectMapSearch.secondary("src/main/java/Foo.java", false));
        assertTrue(ProjectMapSearch.vendor("vendor/k8s.io/api/types.go"));
        assertFalse(ProjectMapSearch.vendor("pkg/vendors/x.go"));
    }

    @Test
    void theMapsRowFilterMatchesTheNameAPathOrDeclarationQueryEndsWith() {
        assertEquals("types.go", ProjectMapSearch.nameFilter("core/v1/types.go"));
        assertEquals("kube-scheduler", ProjectMapSearch.nameFilter("cmd/kube-scheduler/"));
        assertEquals("NewScheduler", ProjectMapSearch.nameFilter("@NewScheduler"));
        assertEquals("Main", ProjectMapSearch.nameFilter("#Main"));
        assertEquals("panel", ProjectMapSearch.nameFilter("panel"));
        assertTrue(ProjectMapSearch.isSymbolQuery(" @Pod"));
        assertFalse(ProjectMapSearch.isSymbolQuery("#Pod"));
    }

    // --- E2: declarations ---------------------------------------------------------------------------

    @Test
    void declarationMatchesPutTheServersFirstOnceAndRankTheNameFirst() {
        Path root = temp.resolve("p").toAbsolutePath();
        Match server = Match.symbol(root.resolve("pkg/a.go"), "pkg/a.go", "NewScheduler", 10, "function");
        Match sameFromIndex = Match.symbol(root.resolve("pkg/a.go"), "pkg/a.go", "NewScheduler", 10, "function");
        Match exact = Match.symbol(root.resolve("cmd/b.go"), "cmd/b.go", "NewScheduler", 3, "function");
        Match longer = Match.symbol(root.resolve("cmd/c.go"), "cmd/c.go", "NewSchedulerCommand", 95, "function");
        Match test = Match.symbol(root.resolve("pkg/a_test.go"), "pkg/a_test.go", "NewScheduler", 1, "function");
        List<Match> ranked = ProjectMapSymbolSearch.ranked(
                "NewScheduler", List.of(server), List.of(longer, test, sameFromIndex, exact));
        assertEquals(List.of(server, exact, test, longer), ranked);
        assertEquals(
                List.of(
                        root.resolve("pkg/a.go"),
                        root.resolve("cmd/b.go"),
                        root.resolve("pkg/a_test.go"),
                        root.resolve("cmd/c.go")),
                new ProjectMapSearchResult("@NewScheduler", ranked, 4, false).paths());
    }

    @Test
    void aDeclarationIsFoundInItsFilesOutlineByNameAndLine() {
        Path file = temp.resolve("types.go").toAbsolutePath();
        ProjectMapStructure.Node spec = node(
                file.resolve("§PodSpec"),
                "type PodSpec struct",
                10,
                List.of(node(file.resolve("§PodSpec/§Volumes"), "Volumes", 12, List.of())));
        ProjectMapStructure.Node status = node(file.resolve("§PodStatus"), "type PodStatus struct", 40, List.of());
        List<ProjectMapStructure.Node> roots = List.of(spec, status);
        assertEquals(List.of(spec), ProjectMapView.searchDeclarationChain(roots, "PodSpec", 10));
        assertEquals(
                List.of(spec, spec.children().getFirst()),
                ProjectMapView.searchDeclarationChain(roots, "Volumes", 13),
                "nested, and the nearest line when the outline counts lines a little differently");
        assertEquals(List.of(status), ProjectMapView.searchDeclarationChain(roots, "Other", 40), "else by its line");
        assertTrue(ProjectMapView.searchDeclarationChain(roots, "Other", 99).isEmpty());
        assertFalse(ProjectMapView.searchLabelNames("type PodSpecList struct", "PodSpec"), "whole identifiers only");
    }

    // --- A4: remote listings are reused for a while -------------------------------------------------

    @Test
    void aRemoteFoldersListingIsReusedForThirtySecondsAndDroppedOnInvalidation() throws IOException {
        try (FileSystem zip = FileSystems.newFileSystem(
                URI.create("jar:" + temp.resolve("remote.zip").toUri()), Map.of("create", "true"))) {
            Path root = Files.createDirectories(zip.getPath("/project"));
            Path folder = Files.createDirectories(root.resolve("src"));
            Files.writeString(folder.resolve("a.txt"), "a");
            Set<Path> expanded = Set.of(root, folder);
            long[] now = {1_000_000};
            try (ListingService listings = new ListingService(0, null, () -> now[0])) {
                listings.setWatched(Set.of()); // nothing on this machine watches a remote folder
                ProjectMapModel.load(new Request(root, expanded, false), listings);
                int first = listings.listedForTest.get();
                assertEquals(2, first);
                ProjectMapModel.load(new Request(root, expanded, false), listings);
                assertEquals(first, listings.listedForTest.get(), "a click soon after lists nothing again");
                now[0] += ListingService.REMOTE_TTL_MILLIS + 1;
                ProjectMapModel.load(new Request(root, expanded, false), listings);
                assertEquals(first + 2, listings.listedForTest.get(), "after 30 s the folders are listed again");
                listings.invalidateAll(); // an in-app change or a focus regain
                ProjectMapModel.load(new Request(root, expanded, false), listings);
                assertEquals(first + 4, listings.listedForTest.get());
            }
        }
    }

    @Test
    void aRemoteRootIsListedAndSearchedToo() throws IOException {
        try (FileSystem zip = FileSystems.newFileSystem(
                URI.create("jar:" + temp.resolve("remote2.zip").toUri()), Map.of("create", "true"))) {
            Path root = Files.createDirectories(zip.getPath("/project"));
            Files.createDirectories(root.resolve("a/b/c/d/e/f/g/h"));
            Files.writeString(root.resolve("a/b/c/d/e/f/g/h/deep.txt"), "x");
            ProjectFileList list = ProjectFileList.build(root, DEFAULT, () -> false);
            assertNotNull(list);
            ProjectMapSearchResult found = ProjectMapSearch.search(root, list.snapshot(), "deep.txt", 300, () -> false);
            assertNotNull(found);
            assertEquals("a/b/c/d/e/f/g/h/deep.txt", found.first().relativePath());
        }
    }

    // --- helpers ------------------------------------------------------------------------------------

    private static ProjectMapStructure.Node node(
            Path path, String label, int line, List<ProjectMapStructure.Node> children) {
        ProjectMapModel.Symbol symbol = new ProjectMapModel.Symbol(
                path.getParent(), label, "type", line, line, line + 5, null, !children.isEmpty());
        return new ProjectMapStructure.Node(path, symbol, children);
    }

    private ProjectMapSearchResult search(Path root, String query) {
        ProjectFileList list = ProjectFileList.build(root, DEFAULT, () -> false);
        assertNotNull(list);
        return search(root, list, query);
    }

    private static ProjectMapSearchResult search(Path root, ProjectFileList list, String query) {
        ProjectMapSearchResult found =
                ProjectMapSearch.search(root, list.snapshot(), query, ProjectMapSearch.MAX_MATCHES, () -> false);
        assertNotNull(found);
        return found;
    }

    private Path root() throws IOException {
        return Files.createDirectories(temp.resolve("project")).toRealPath();
    }

    private static Path write(Path root, String rel) throws IOException {
        Path file = root.resolve(rel);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, rel);
    }

    private static void deleteTree(Path dir) throws IOException {
        try (var walk = Files.walk(dir)) {
            for (Path path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }
}
