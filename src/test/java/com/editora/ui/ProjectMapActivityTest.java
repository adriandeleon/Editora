package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.editora.git.GitRecentChanges;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Map's activity model without a toolkit: heat steps, the most changed folders, and when the history is read. */
class ProjectMapActivityTest {

    private static final Path ROOT = Path.of("/work/k8s");

    private static GitRecentChanges.Stat stat(int commits, long epoch) {
        return new GitRecentChanges.Stat(commits, epoch, "Ada");
    }

    private static GitRecentChanges.Result result(String head) {
        return new GitRecentChanges.Result(
                head,
                false,
                40,
                false,
                Map.of(
                        "pkg/kubelet/kubelet.go",
                        stat(40, 9),
                        "pkg/kubelet/pod.go",
                        stat(3, 5),
                        "README.md",
                        stat(1, 1)),
                Map.of("", stat(40, 9), "pkg", stat(40, 9), "pkg/kubelet", stat(40, 9), "api", stat(2, 1)),
                Map.of(
                        "pkg/kubelet",
                        stat(40, 9),
                        "",
                        stat(1, 1),
                        "api/v1",
                        stat(2, 3),
                        "api/v2",
                        stat(2, 4),
                        "vendor/golang.org/x/net",
                        stat(90, 9),
                        "web/node_modules/react",
                        stat(90, 9)),
                0,
                "");
    }

    @Test
    void stepsAreALogScaleOfTheBusiest() {
        assertEquals(0, ProjectMapActivity.level(0, 40));
        assertEquals(1, ProjectMapActivity.level(1, 1000), "one commit is still warm");
        assertEquals(ProjectMapActivity.LEVELS, ProjectMapActivity.level(40, 40));
        assertTrue(ProjectMapActivity.level(3, 40) < ProjectMapActivity.level(20, 40));
        assertEquals(ProjectMapActivity.LEVELS, ProjectMapActivity.level(50, 40), "never above the top");
        assertEquals(ProjectMapActivity.LEVELS + 1, ProjectMapActivity.HEAT_TINT.length);
    }

    @Test
    void theMostChangedAreTheFoldersWhoseOwnFilesChangedMost() {
        List<ProjectMapActivity.Hot> hot = ProjectMapActivity.mostChanged(result("h"), 6);
        assertEquals(
                List.of("pkg/kubelet", "api/v2", "api/v1"),
                hot.stream().map(ProjectMapActivity.Hot::folder).toList(),
                "by commits, a tie to the more recent; the root's own files and vendored or dependency folders left out");
        assertEquals(1, ProjectMapActivity.mostChanged(result("h"), 1).size());
    }

    @Test
    void theHistoryIsReadWhenWantedOncePerAnswerAndDroppedForAnotherRoot() {
        List<Consumer<GitRecentChanges.Result>> asked = new ArrayList<>();
        int[] changed = {0};
        ProjectMapActivity activity = new ProjectMapActivity(() -> changed[0]++, Runnable::run);
        activity.setSourcesForTest(
                (root, then) -> asked.add(then), root -> ProjectMapOwners.of(null, rel -> null), Runnable::run);
        activity.setRoot(ROOT);
        assertTrue(asked.isEmpty(), "off and no Overview card: nothing is read");
        activity.setHeat(true);
        assertEquals(1, asked.size());
        assertTrue(activity.reading());
        activity.setOverviewWanted(true); // the Overview card opens while the history is read
        assertEquals(1, asked.size(), "one read at a time");
        GitRecentChanges.Result first = result("h1");
        asked.getFirst().accept(first);
        assertEquals(first, activity.changes());
        assertEquals(2, asked.size(), "the request that came during the read is asked after it");
        assertTrue(activity.reading());
        assertEquals(ProjectMapActivity.LEVELS, activity.heatLevel(ROOT.resolve("pkg/kubelet/kubelet.go"), false));
        assertTrue(activity.heatLevel(ROOT.resolve("pkg/kubelet/pod.go"), false) < ProjectMapActivity.LEVELS);
        assertEquals(0, activity.heatLevel(ROOT.resolve("pkg/kubelet/other.go"), false));
        assertEquals(
                ProjectMapActivity.LEVELS, activity.heatLevel(ROOT.resolve("pkg"), true), "the busiest at its depth");
        assertTrue(activity.heatLevel(ROOT.resolve("api"), true) < ProjectMapActivity.LEVELS);
        assertEquals(40, activity.stat(ROOT.resolve("pkg"), true).commits());
        assertEquals(0, activity.heatLevel(Path.of("/elsewhere/a.go"), false));
        asked.get(1).accept(first); // the same HEAD: the service's cached answer
        assertFalse(activity.reading());
        int before = changed[0];
        activity.headMaybeMoved();
        asked.get(2).accept(first);
        assertEquals(before, changed[0], "an unchanged answer repaints nothing");

        activity.setHeat(false);
        assertEquals(0, activity.heatLevel(ROOT.resolve("pkg/kubelet/kubelet.go"), false), "off: no tint");
        assertNull(activity.stat(ROOT.resolve("pkg"), true));

        activity.setHeat(true);
        Consumer<GitRecentChanges.Result> late = asked.getLast();
        activity.setRoot(Path.of("/work/other"));
        late.accept(result("h2"));
        assertEquals(GitRecentChanges.Result.NONE, activity.changes(), "an answer for the old root is dropped");
    }

    @Test
    void theOverviewSummarySaysWhileReadingAndListsOwners() {
        List<Consumer<GitRecentChanges.Result>> asked = new ArrayList<>();
        ProjectMapActivity activity = new ProjectMapActivity(() -> {}, Runnable::run);
        activity.setSourcesForTest(
                (root, then) -> asked.add(then),
                root -> ProjectMapOwners.of("/pkg/ @pkg-team\n* @core\n/api/ @api-team\n", rel -> null),
                Runnable::run);
        activity.setRoot(ROOT);
        activity.setOverviewWanted(true);
        assertTrue(activity.summary().reading());
        assertTrue(activity.summary().hasHistory());
        activity.loadOverviewOwners(List.of("pkg", "api", "docs"));
        List<ProjectMapActivity.Owned> owners = activity.summary().owners();
        assertEquals(
                List.of("", "api"),
                owners.stream().map(ProjectMapActivity.Owned::folder).toList(),
                "pkg/ (the later * line wins over /pkg/) and docs/ are owned as the project is: not repeated");
        assertEquals(List.of("@core"), owners.get(0).owners().approvers());
        assertEquals(List.of("@api-team"), owners.get(1).owners().approvers());
        asked.getFirst().accept(GitRecentChanges.Result.NONE);
        assertFalse(activity.summary().hasHistory(), "not a repository: no history section");
    }

    @Test
    void rowsOwnersAreWorkedOutOnceEach() {
        int[] loads = {0};
        ProjectMapActivity activity = new ProjectMapActivity(() -> {}, Runnable::run);
        activity.setSourcesForTest(
                null,
                root -> {
                    loads[0]++;
                    return ProjectMapOwners.of(
                            null, rel -> rel.equals("pkg/OWNERS") ? "approvers:\n  - alice\n" : null);
                },
                Runnable::run);
        activity.setRoot(ROOT);
        ProjectMapModel.Entry pkg = new ProjectMapModel.Entry(ROOT.resolve("pkg"), ROOT, 1, true);
        ProjectMapModel.Entry file = new ProjectMapModel.Entry(ROOT.resolve("pkg/a.go"), ROOT.resolve("pkg"), 2, false);
        ProjectMapModel.Entry readme = new ProjectMapModel.Entry(ROOT.resolve("README.md"), ROOT, 1, false);
        activity.classify(List.of(pkg, file, readme));
        activity.classify(List.of(pkg, file));
        assertEquals(1, loads[0]);
        assertEquals(List.of("alice"), activity.owners(ROOT.resolve("pkg/a.go")).approvers());
        assertEquals(List.of("alice"), activity.owners(ROOT.resolve("pkg")).approvers());
        assertNull(activity.owners(ROOT.resolve("README.md")));
    }

    // --- Map review 4 -------------------------------------------------------------------------------------

    private static GitRecentChanges.Result folders(
            Map<String, GitRecentChanges.Stat> files,
            Map<String, GitRecentChanges.Stat> folders,
            Map<String, GitRecentChanges.Stat> direct) {
        return new GitRecentChanges.Result("h", false, 100, false, files, folders, direct, 0, "");
    }

    private static ProjectMapActivity answered(GitRecentChanges.Result result, Path root) {
        ProjectMapActivity activity = new ProjectMapActivity(() -> {}, Runnable::run);
        activity.setSourcesForTest(
                (base, then) -> then.accept(result), base -> ProjectMapOwners.of(null, rel -> null), Runnable::run);
        activity.setRoot(root);
        activity.setHeat(true);
        return activity;
    }

    @Test
    void aFoldersHeatIsNeverAboveItsParents() {
        // B5: depth 3 has one folder with one commit, so on its own scale it is the hottest.
        GitRecentChanges.Result result = folders(
                Map.of("x/y/f.go", stat(50, 9), "a/b/c/g.go", stat(1, 1)),
                Map.of(
                        "", stat(51, 9),
                        "x", stat(50, 9),
                        "x/y", stat(50, 9),
                        "a", stat(1, 1),
                        "a/b", stat(1, 1),
                        "a/b/c", stat(1, 1)),
                Map.of("x/y", stat(50, 9), "a/b/c", stat(1, 1)));
        ProjectMapActivity activity = answered(result, ROOT);
        int a = activity.heatLevel(ROOT.resolve("a"), true);
        int ab = activity.heatLevel(ROOT.resolve("a/b"), true);
        int abc = activity.heatLevel(ROOT.resolve("a/b/c"), true);
        assertEquals(1, ab, "one commit against fifty at its depth");
        assertTrue(abc <= ab && ab <= a, "never hotter than the parent: " + a + " " + ab + " " + abc);
        assertEquals(ProjectMapActivity.LEVELS, activity.heatLevel(ROOT.resolve("x/y"), true));
    }

    @Test
    void aSubmoduleFolderRowShowsItsPointersHeat() {
        // B12: git counts vendor/sub as a file (a gitlink); its row is a folder.
        GitRecentChanges.Result result = folders(
                Map.of("vendor/sub", stat(2, 9), "src/a.go", stat(4, 9)),
                Map.of("", stat(5, 9), "vendor", stat(2, 9), "src", stat(4, 9)),
                Map.of("vendor", stat(2, 9), "src", stat(4, 9)));
        ProjectMapActivity activity = answered(result, ROOT);
        assertTrue(activity.heatLevel(ROOT.resolve("vendor/sub"), true) > 0);
        assertTrue(activity.heatLevel(ROOT.resolve("vendor/sub"), true)
                <= activity.heatLevel(ROOT.resolve("vendor"), true));
        assertEquals(2, activity.stat(ROOT.resolve("vendor/sub"), true).commits());
    }

    @Test
    void mostChangedLeavesOutGeneratedVendoredAndVanishedFolders(@org.junit.jupiter.api.io.TempDir Path temp)
            throws Exception {
        // B4: .gitattributes marks gen/ generated and assets/lib/ vendored; B2: "gone" was moved away.
        Path root = temp.toRealPath();
        for (String folder : List.of("gen", "assets/lib", "src", "docs", "mixed")) {
            java.nio.file.Files.createDirectories(root.resolve(folder));
        }
        java.nio.file.Files.writeString(
                root.resolve(".gitattributes"),
                "gen/** linguist-generated\n" + "assets/lib/** linguist-vendored\n*.pb.go linguist-generated\n");
        GitRecentChanges.Result result = folders(
                Map.of(
                        "gen/a.js", stat(31, 9),
                        "assets/lib/x.js", stat(31, 9),
                        "src/Main.java", stat(6, 9),
                        "docs/d.md", stat(4, 9),
                        "gone/z.java", stat(10, 9),
                        "mixed/api.pb.go", stat(8, 9),
                        "mixed/api.go", stat(1, 9)),
                Map.of("", stat(60, 9)),
                Map.of(
                        "gen", stat(31, 9),
                        "assets/lib", stat(31, 9),
                        "gone", stat(10, 9),
                        "mixed", stat(8, 9),
                        "src", stat(6, 9),
                        "docs", stat(4, 9)));
        ProjectMapActivity activity = new ProjectMapActivity(() -> {}, Runnable::run);
        activity.setSourcesForTest(
                (base, then) -> then.accept(result), base -> ProjectMapOwners.of(null, rel -> null), Runnable::run);
        activity.setRoot(root);
        activity.setOverviewWanted(true);
        assertEquals(
                List.of("mixed", "src", "docs"),
                activity.summary().mostChanged().stream()
                        .map(ProjectMapActivity.Hot::folder)
                        .toList(),
                "a folder with one file of its own code stays; generated, vendored and vanished ones go");
    }

    @Test
    void editedOwnerFilesAreReadAgainAndTheRowsFollow() {
        // B9: the owners were kept per root until a project switch.
        Map<String, String> files = new java.util.HashMap<>(Map.of("pkg/OWNERS", "approvers:\n  - alice\n"));
        int[] changed = {0};
        ProjectMapActivity activity = new ProjectMapActivity(() -> changed[0]++, Runnable::run);
        activity.setSourcesForTest(null, root -> ProjectMapOwners.of(null, files::get), Runnable::run);
        activity.setRoot(ROOT);
        ProjectMapModel.Entry file = new ProjectMapModel.Entry(ROOT.resolve("pkg/a.go"), ROOT.resolve("pkg"), 2, false);
        activity.classify(List.of(file));
        activity.loadOverviewOwners(List.of("pkg"));
        assertEquals(List.of("alice"), activity.owners(ROOT.resolve("pkg/a.go")).approvers());

        activity.ownerFilesMayHaveChanged(List.of(ROOT.resolve("pkg")));
        assertEquals(List.of("alice"), activity.owners(ROOT.resolve("pkg/a.go")).approvers(), "nothing changed");
        int before = changed[0];

        files.put("pkg/OWNERS", "approvers:\n  - bob\n");
        activity.ownerFilesMayHaveChanged(List.of(ROOT.resolve("src")));
        assertEquals(List.of("alice"), activity.owners(ROOT.resolve("pkg/a.go")).approvers(), "another folder");
        activity.ownerFilesMayHaveChanged(List.of(ROOT.resolve("pkg")));
        assertEquals(List.of("bob"), activity.owners(ROOT.resolve("pkg/a.go")).approvers());
        assertTrue(changed[0] > before, "the rows and the Overview are told");
        assertEquals(
                List.of("bob"),
                activity.summary().owners().stream()
                        .filter(owned -> owned.folder().equals("pkg"))
                        .findFirst()
                        .orElseThrow()
                        .owners()
                        .approvers());
    }

    @Test
    void turningTheOptionOnSaysWhyNothingIsTinted() {
        // B11: the explanation was only in the Overview card.
        List<String> said = new ArrayList<>();
        List<Consumer<GitRecentChanges.Result>> asked = new ArrayList<>();
        ProjectMapActivity activity = new ProjectMapActivity(() -> {}, Runnable::run);
        activity.setSourcesForTest(
                (root, then) -> asked.add(then), root -> ProjectMapOwners.of(null, rel -> null), Runnable::run);
        activity.setOnExplain(said::add);
        activity.setRoot(ROOT);
        activity.setHeat(true);
        GitRecentChanges.Result shallow =
                new GitRecentChanges.Result("h", true, 0, false, Map.of(), Map.of(), Map.of(), 1_700_000_000L, "");
        asked.getLast().accept(shallow);
        assertEquals(1, said.size());
        assertTrue(said.getFirst()
                .startsWith(tr("project.map.activity.shallowEmpty", "§").substring(0, 20)));
        activity.headMaybeMoved();
        asked.getLast().accept(shallow);
        assertEquals(1, said.size(), "said once per turning on");

        activity.setHeat(false);
        activity.setHeat(true);
        asked.getLast().accept(GitRecentChanges.Result.NONE);
        assertEquals(tr("project.map.activity.noHistory"), said.getLast());

        activity.setHeat(false);
        activity.setHeat(true);
        asked.getLast().accept(result("h3"));
        assertEquals(2, said.size(), "a whole window needs no word");

        assertEquals(
                tr("project.map.activity.failed", "boom"),
                ProjectMapActivity.explain(
                        new GitRecentChanges.Result("h", false, 0, false, Map.of(), Map.of(), Map.of(), 0, "boom")));
        assertEquals(
                tr("project.map.activity.noChanges"),
                ProjectMapActivity.explain(
                        new GitRecentChanges.Result("h", false, 0, false, Map.of(), Map.of(), Map.of(), 0, "")));
        assertEquals(
                tr("project.map.activity.truncated", ProjectMapOverviewCard.count(10_000)),
                ProjectMapActivity.explain(
                        new GitRecentChanges.Result("h", false, 10_000, true, Map.of(), Map.of(), Map.of(), 0, "")));
    }
}
