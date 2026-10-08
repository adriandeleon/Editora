package com.editora.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import com.editora.ui.ProjectMapModel.Entry;
import com.editora.ui.ProjectMapModel.PlaceholderKind;
import com.editora.ui.ProjectMapModel.Request;
import com.editora.ui.ProjectMapModel.Snapshot;
import com.editora.ui.ProjectMapModel.TypeFilter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The Project Map's bounded loading: row limits, what is reported when they bite, and pinned paths. */
class ProjectMapModelLoadingTest {

    @TempDir
    Path root;

    @Test
    void aDirectoryBeyondItsRowLimitEndsInAMoreRowAndReportsItsRealChildCount() throws Exception {
        Path big = folderWithFiles("big", 350);

        Snapshot snapshot = ProjectMapModel.load(new Request(root, Set.of(root, big), false));

        ProjectMapModel.DirectoryFacts facts = snapshot.directories().get(normalize(big));
        assertEquals(350, facts.total());
        assertEquals(ProjectMapModel.DIRECTORY_CHUNK, facts.loaded());
        assertTrue(facts.truncated());
        List<Entry> rows = childrenOf(snapshot, big);
        assertEquals(ProjectMapModel.DIRECTORY_CHUNK + 1, rows.size());
        Entry more = rows.getLast();
        assertTrue(more.isMore());
        assertEquals(50, more.placeholder().remaining());
        assertEquals("+50 more…", more.name());

        ProjectMapModel.Column column = ProjectMapModel.columnsById(snapshot.entries(), Map.of(), Map.of()).stream()
                .filter(candidate -> normalize(big).equals(candidate.parent()))
                .findFirst()
                .orElseThrow();
        assertEquals(350, column.totalEntries());
        assertEquals("300/350", column.countLabel(), "the header must not claim the column is complete");
        assertTrue(column.entries().getLast().isMore(), "the more row closes the column");
    }

    @Test
    void theMoreRowSurvivesAColumnFilterThatHidesEveryLoadedRow() throws Exception {
        Path big = folderWithFiles("big", 310);
        Snapshot snapshot = ProjectMapModel.load(new Request(root, Set.of(root, big), false));
        ProjectMapModel.ColumnId id = new ProjectMapModel.ColumnId(2, big);

        ProjectMapModel.Column filtered =
                ProjectMapModel.columnsById(snapshot.entries(), Map.of(id, "zzzz-no-such-name"), Map.of()).stream()
                        .filter(candidate -> candidate.id().equals(id))
                        .findFirst()
                        .orElseThrow();

        assertEquals(1, filtered.entries().size());
        assertTrue(filtered.entries().getFirst().isMore());
        assertEquals("0/310", filtered.countLabel());
    }

    @Test
    void raisingADirectoryLimitLoadsTheNextChunk() throws Exception {
        Path big = folderWithFiles("big", 350);

        Snapshot snapshot = ProjectMapModel.load(new Request(
                root,
                Set.of(root, big),
                false,
                Map.of(),
                Map.of(normalize(big), 2 * ProjectMapModel.DIRECTORY_CHUNK),
                List.of(),
                null,
                null));

        assertEquals(350, snapshot.directories().get(normalize(big)).loaded());
        assertTrue(childrenOf(snapshot, big).stream().noneMatch(Entry::isPlaceholder));
        assertFalse(snapshot.capped());
    }

    @Test
    void theOverallLimitNeverReportsAFolderAsLoadedWhenNoneOfItsRowsFit() throws Exception {
        // 5 root rows + 4 x 300 = 1,205 real rows wanted; the limit is 1,200 including the root.
        List<Path> folders = new ArrayList<>();
        for (String name : List.of("a", "b", "c", "d")) {
            folders.add(folderWithFiles(name, 300));
        }
        Path last = folderWithFiles("e", 10);
        Set<Path> expanded = new HashSet<>(folders);
        expanded.add(root);
        expanded.add(last);

        Snapshot snapshot = ProjectMapModel.load(new Request(root, expanded, false));

        long realRows = snapshot.entries().stream()
                .filter(entry -> !entry.isPlaceholder())
                .count();
        assertEquals(ProjectMapModel.MAX_VISIBLE_ITEMS, realRows);
        assertTrue(snapshot.capped());
        ProjectMapModel.DirectoryFacts fourth = snapshot.directories().get(normalize(folders.get(3)));
        assertEquals(300, fourth.total());
        assertEquals(294, fourth.loaded());
        assertTrue(childrenOf(snapshot, folders.get(3)).getLast().isMore());
        assertTrue(snapshot.skipped().contains(normalize(last)));
        assertFalse(
                snapshot.loadedDirectories().contains(normalize(last)),
                "a folder with no loaded rows must not be drawn as expanded");
        assertTrue(childrenOf(snapshot, last).isEmpty());
    }

    @Test
    void anEmptyFolderGetsAStubRowAndCountsAsLoaded() throws Exception {
        Path empty = Files.createDirectory(root.resolve("empty"));

        Snapshot snapshot = ProjectMapModel.load(new Request(root, Set.of(root, empty), false));

        assertTrue(snapshot.directories().get(normalize(empty)).empty());
        List<Entry> rows = childrenOf(snapshot, empty);
        assertEquals(1, rows.size());
        assertEquals(PlaceholderKind.EMPTY, rows.getFirst().placeholder().kind());
        assertEquals("Empty folder", rows.getFirst().name());
        assertTrue(ProjectMapModel.loadVisible(root, Set.of(root, empty), false).stream()
                .noneMatch(Entry::isPlaceholder));
    }

    @Test
    void anUnreadableFolderSaysSoInsteadOfLookingEmpty() throws Exception {
        Path locked = folderWithFiles("locked", 2);
        boolean posix = Files.getFileStore(root).supportsFileAttributeView("posix");
        assumeTrue(posix, "needs POSIX permissions");
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"));
        try {
            assumeTrue(!Files.isReadable(locked), "running as a user that ignores permissions");

            Snapshot snapshot = ProjectMapModel.load(new Request(root, Set.of(root, locked), false));

            assertTrue(snapshot.directories().get(normalize(locked)).unreadable());
            List<Entry> rows = childrenOf(snapshot, locked);
            assertEquals(1, rows.size());
            assertEquals(
                    PlaceholderKind.UNREADABLE, rows.getFirst().placeholder().kind());
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwx------"));
        }
    }

    @Test
    void aPinnedPathIsLoadedEvenWhenItSortsBeyondItsDirectoryLimit() throws Exception {
        Path big = folderWithFiles("big", 350);
        Path lastFile = big.resolve(fileName(349));

        Snapshot plain = ProjectMapModel.load(new Request(root, Set.of(root, big), false));
        assertFalse(has(plain, lastFile));

        Snapshot pinned = ProjectMapModel.load(
                new Request(root, Set.of(root, big), false, Map.of(), Map.of(), List.of(lastFile), null, null));
        assertTrue(has(pinned, lastFile));
        assertEquals(1, pinned.pinnedLoaded());
        assertEquals(49, childrenOf(pinned, big).getLast().placeholder().remaining());
    }

    @Test
    void searchMatchesAreLoadedBeforeTheirSiblingsSoTheFirstMatchIsAlwaysPresent() throws Exception {
        // 250 folders with one match each: breadth-first, the old loader showed 94 of them.
        List<Path> matches = new ArrayList<>();
        Set<Path> expanded = new HashSet<>(Set.of(root));
        for (int index = 0; index < 250; index++) {
            Path folder = Files.createDirectory(root.resolve(String.format("f%03d", index)));
            for (int filler = 0; filler < 6; filler++) {
                Files.createFile(folder.resolve("filler" + filler + ".txt"));
            }
            matches.add(Files.createFile(folder.resolve("zz-hit.txt")));
            expanded.add(folder);
        }

        Snapshot snapshot =
                ProjectMapModel.load(new Request(root, expanded, false, Map.of(), Map.of(), matches, null, null));

        assertEquals(250, snapshot.pinnedRequested());
        assertEquals(250, snapshot.pinnedLoaded());
        for (Path match : matches) {
            assertTrue(has(snapshot, match), "every match fits: " + match);
        }
        assertTrue(snapshot.capped(), "the fillers do not all fit, and that is reported");
    }

    @Test
    void matchesThatDoNotFitAreCountedNotSilentlyDropped() throws Exception {
        List<Path> matches = new ArrayList<>();
        Set<Path> expanded = new HashSet<>(Set.of(root));
        for (int index = 0; index < 300; index++) {
            Path top = root.resolve(String.format("d%03d", index));
            Path leaf = Files.createDirectories(top.resolve("x/y/z"));
            matches.add(Files.createFile(leaf.resolve("hit.txt")));
            expanded.addAll(List.of(top, top.resolve("x"), top.resolve("x/y"), leaf));
        }

        Snapshot snapshot =
                ProjectMapModel.load(new Request(root, expanded, false, Map.of(), Map.of(), matches, null, null));

        // Each chain costs five rows; 1,199 rows after the root hold 239 whole chains.
        assertEquals(300, snapshot.pinnedRequested());
        assertEquals(239, snapshot.pinnedLoaded());
        assertTrue(has(snapshot, matches.getFirst()), "matches load in order, so the first is always there");
        assertFalse(has(snapshot, matches.getLast()));
        assertTrue(snapshot.capped());
    }

    @Test
    void hiddenEntriesSpendNoBudgetUnlessTheirColumnAsksForThem() throws Exception {
        Path dotGit = Files.createDirectory(root.resolve(".git"));
        Files.createFile(root.resolve("visible.txt"));

        assertFalse(has(ProjectMapModel.load(new Request(root, Set.of(root), false)), dotGit));

        Snapshot overridden = ProjectMapModel.load(
                new Request(root, Set.of(root), false, Map.of(normalize(root), true), Map.of(), List.of(), null, null));
        assertTrue(has(overridden, dotGit));

        Snapshot switchedOff = ProjectMapModel.load(
                new Request(root, Set.of(root), true, Map.of(normalize(root), false), Map.of(), List.of(), null, null));
        assertFalse(has(switchedOff, dotGit));
    }

    @Test
    void aCancelledLoadStopsBetweenDirectoriesAndReturnsNothing() throws Exception {
        Path a = folderWithFiles("a", 3);
        Path b = folderWithFiles("b", 3);
        AtomicInteger polls = new AtomicInteger();

        Snapshot snapshot = ProjectMapModel.load(new Request(
                root,
                Set.of(root, a, b),
                false,
                Map.of(),
                Map.of(),
                List.of(),
                null,
                () -> polls.incrementAndGet() > 1));

        assertNull(snapshot);
        assertEquals(2, polls.get(), "the root was listed, the next directory was not started");
    }

    @Test
    void namesThatDifferOnlyByCaseHaveAStableOrder() {
        List<String> names = new ArrayList<>(List.of("readme", "README", "Readme", "alpha"));
        names.sort(ProjectPathOrder.directoriesFirst(name -> false, name -> name));
        assertEquals(List.of("alpha", "README", "Readme", "readme"), names);
    }

    @Test
    void expansionIsPrunedToFoldersThatActuallyLoaded() throws Exception {
        Path kept = folderWithFiles("kept", 1);
        Path nested = Files.createDirectory(kept.resolve("nested"));
        Path gone = root.resolve("gone");
        Path file = Files.createFile(root.resolve("now-a-file"));
        Set<Path> expanded = Set.of(
                normalize(root),
                normalize(kept),
                normalize(nested),
                normalize(gone),
                normalize(gone.resolve("child")),
                normalize(file));

        Snapshot snapshot = ProjectMapModel.load(new Request(root, expanded, false));

        assertEquals(
                Set.of(normalize(root), normalize(kept), normalize(nested)),
                ProjectMapModel.pruneExpansion(root, expanded, snapshot));
    }

    @Test
    void anUnreadableParentDoesNotForgetWhatWasOpenBeneathIt() {
        Path parent = normalize(root.resolve("remote"));
        Path child = parent.resolve("child");
        Snapshot snapshot = new Snapshot(
                List.of(new Entry(root, null, 0, true), new Entry(parent, root, 1, true)),
                Map.of(
                        normalize(root),
                        new ProjectMapModel.DirectoryFacts(1, 1, false),
                        parent,
                        new ProjectMapModel.DirectoryFacts(0, 0, true)),
                Set.of(),
                false,
                0,
                0);

        assertEquals(
                Set.of(normalize(root), parent, child),
                ProjectMapModel.pruneExpansion(root, Set.of(normalize(root), parent, child), snapshot));
    }

    @Test
    void remapCarriesPathsUnderARenamedFolder() {
        Path from = root.resolve("old");
        Path to = root.resolve("new");
        assertEquals(normalize(to), ProjectMapModel.remap(from, from, to));
        assertEquals(normalize(to.resolve("a/b.txt")), ProjectMapModel.remap(from.resolve("a/b.txt"), from, to));
        assertEquals(normalize(root.resolve("older")), ProjectMapModel.remap(root.resolve("older"), from, to));
    }

    @Test
    void aMatchInsideACollapsedFolderEmphasizesTheFoldersThatHoldIt() {
        Path src = root.resolve("src");
        Path deep = src.resolve("main/java/App.java");
        List<Entry> entries = List.of(
                new Entry(root, null, 0, true),
                new Entry(src, root, 1, true),
                new Entry(root.resolve("docs"), root, 1, true));

        Set<Path> emphasized = ProjectMapModel.emphasized(entries, Set.of(deep), root);

        assertTrue(emphasized.contains(normalize(src)), "the collapsed folder that holds the match");
        assertTrue(emphasized.contains(normalize(root)));
        assertFalse(emphasized.contains(normalize(root.resolve("docs"))));
        assertFalse(
                ProjectMapModel.emphasized(entries, Set.of(deep)).contains(normalize(src)),
                "without a root only loaded rows are walked");
        assertEquals(
                Set.of(
                        normalize(root),
                        normalize(src),
                        normalize(src.resolve("main")),
                        normalize(src.resolve("main/java"))),
                ProjectMapModel.ancestorsWithin(root, List.of(deep)));
    }

    @Test
    void typeFilterFollowsTheEditorsLanguageRegistry() {
        for (String source : List.of(
                "App.java",
                "query.sql",
                "init.lua",
                "Widget.hpp",
                "api.proto",
                "main.dart",
                "App.vue",
                "x.svelte",
                "run.ps1",
                "lib.rs")) {
            assertEquals(TypeFilter.SOURCE, ProjectMapModel.classify(source), source);
        }
        for (String markup : List.of("README.md", "index.html", "paper.tex", "guide.rst", "style.scss", "icon.svg")) {
            assertEquals(TypeFilter.MARKUP, ProjectMapModel.classify(markup), markup);
        }
        for (String config : List.of(
                "pom.xml",
                "package.json",
                "ci.yml",
                ".env",
                ".gitignore",
                ".editorconfig",
                "Dockerfile",
                "Makefile",
                "CMakeLists.txt",
                "Jenkinsfile",
                "build.gradle.kts",
                "settings.gradle",
                "app.service")) {
            assertEquals(TypeFilter.CONFIG, ProjectMapModel.classify(config), config);
        }
        for (String other : List.of("notes.txt", "photo.png", "data.bin", "LICENSE")) {
            assertEquals(TypeFilter.OTHER, ProjectMapModel.classify(other), other);
        }
        assertFalse(ProjectMapModel.matchesType(new Entry(root.resolve("src"), root, 1, true), TypeFilter.SOURCE));
        assertNotNull(ProjectMapModel.classify(null));
    }

    private Path folderWithFiles(String name, int count) throws IOException {
        Path folder = Files.createDirectory(root.resolve(name));
        for (int index = 0; index < count; index++) {
            Files.createFile(folder.resolve(fileName(index)));
        }
        return folder;
    }

    private static String fileName(int index) {
        return String.format("file%04d.txt", index);
    }

    private static List<Entry> childrenOf(Snapshot snapshot, Path parent) {
        Path normalized = normalize(parent);
        return snapshot.entries().stream()
                .filter(entry -> normalized.equals(entry.parent()))
                .toList();
    }

    private static boolean has(Snapshot snapshot, Path path) {
        Path normalized = normalize(path);
        return snapshot.entries().stream().anyMatch(entry -> entry.path().equals(normalized));
    }

    private static Path normalize(Path path) {
        return ProjectMapModel.normalize(path);
    }
}
