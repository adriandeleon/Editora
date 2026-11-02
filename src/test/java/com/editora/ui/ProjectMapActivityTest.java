package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.editora.git.GitRecentChanges;
import org.junit.jupiter.api.Test;

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
}
