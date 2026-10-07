package com.editora.github;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.editora.github.PrDraft.CommitMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrDraftTest {

    @Test
    void oneCommitGivesItsSubjectAndBody() {
        PrDraft.Draft draft =
                PrDraft.of("fix/thing", List.of(new CommitMessage("Fix the thing", "Because it was broken.\n")), "");
        assertEquals("Fix the thing", draft.title());
        assertEquals("Because it was broken.", draft.body());
    }

    @Test
    void severalCommitsGiveTheBranchNameAndAListOldestFirst() {
        PrDraft.Draft draft = PrDraft.of(
                "feature/github-ui_polish",
                List.of(
                        new CommitMessage("third", ""),
                        new CommitMessage("second", "x"),
                        new CommitMessage("first", "")),
                "");
        assertEquals("Github ui polish", draft.title());
        assertEquals("- first\n- second\n- third", draft.body());
    }

    @Test
    void noCommitsStillGivesATitle() {
        PrDraft.Draft draft = PrDraft.of("topic", List.of(), null);
        assertEquals("Topic", draft.title());
        assertEquals("", draft.body());
        assertEquals("", PrDraft.of(null, null, null).title());
    }

    @Test
    void theRepositorysTemplateIsTheBodyWhenThereIsOne() {
        PrDraft.Draft draft =
                PrDraft.of("fix/thing", List.of(new CommitMessage("Fix", "body")), "\n## Summary\n\n## Testing\n");
        assertEquals("Fix", draft.title());
        assertEquals("## Summary\n\n## Testing", draft.body());
    }

    @Test
    void theDefaultBranchCannotBeAPullRequestsHead() {
        assertTrue(PrDraft.onDefaultBranch("main", "main"));
        assertFalse(PrDraft.onDefaultBranch("feature", "main"));
        assertFalse(PrDraft.onDefaultBranch("main", ""), "an unknown default branch does not block");
        assertFalse(PrDraft.onDefaultBranch("", ""));
    }

    @Test
    void aBranchWithNoUpstreamOrAheadOfItNeedsAPush() {
        assertTrue(PrDraft.needsPush("", 0));
        assertTrue(PrDraft.needsPush(null, 0));
        assertTrue(PrDraft.needsPush("origin/feature", 2), "unpushed commits would be missing from the pull request");
        assertFalse(PrDraft.needsPush("origin/feature", 0));
    }

    @Test
    void theCommitsAreCountedAgainstTheBaseOnTheBranchsOwnRemote() {
        List<String> remotes = List.of("origin/main", "upstream/main", "origin/feature");
        assertEquals("upstream/main", PrDraft.baseRef("main", remotes, List.of("main"), "upstream/feature"));
        assertEquals("origin/main", PrDraft.baseRef("main", remotes, List.of("main"), ""));
        assertEquals("upstream/dev", PrDraft.baseRef("dev", List.of("upstream/dev"), List.of(), ""));
        assertEquals("dev", PrDraft.baseRef("dev", List.of(), List.of("dev"), ""));
        assertEquals("", PrDraft.baseRef("dev", List.of(), List.of(), ""));
        assertEquals("", PrDraft.baseRef("", remotes, List.of(), ""));
    }

    @Test
    void theBaseChoicesAreTheRemoteBranchesDefaultFirst() {
        assertEquals(
                List.of("main", "feature", "release/1.0"),
                PrDraft.baseChoices(
                        List.of("origin/HEAD", "origin/feature", "origin/main", "upstream/main", "origin/release/1.0"),
                        "main"));
        assertEquals(List.of("a"), PrDraft.baseChoices(List.of("origin/a"), ""));
    }

    @Test
    void theTemplateIsFoundWhereGitHubLooks(@TempDir Path root) throws Exception {
        assertEquals("", PrDraft.template(root));
        assertEquals("", PrDraft.template(null));

        Files.createDirectories(root.resolve("docs"));
        Files.writeString(root.resolve("docs/pull_request_template.md"), "from docs\n");
        assertEquals("from docs", PrDraft.template(root));

        Files.writeString(root.resolve("PULL_REQUEST_TEMPLATE.md"), "from the root\n");
        assertEquals("from the root", PrDraft.template(root), "the root comes before docs/, in any letter case");

        Files.createDirectories(root.resolve(".github"));
        Files.writeString(root.resolve(".github/pull_request_template.md"), "\n## What\n\n## Why\n");
        assertEquals("## What\n\n## Why", PrDraft.template(root), ".github/ comes first");
    }

    @Test
    void anImplausiblyLargeTemplateIsNotReadIntoTheForm(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("pull_request_template.md"), "x".repeat((int) PrDraft.TEMPLATE_MAX_BYTES + 1));
        assertEquals("", PrDraft.template(root));
    }
}
