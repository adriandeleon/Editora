package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The owner files' rarer shapes ({@link ProjectMapOwners}): {@code **} at either end or inside a {@code CODEOWNERS}
 * pattern, escapes, GitLab sections and owners that are not handles, and {@code OWNERS} files with flow lists, quoted
 * names, document markers and keys that hold URLs.
 */
class ProjectMapOwnersEdgesTest {

    private static @Nullable List<String> approvers(ProjectMapOwners owners, String rel, boolean directory) {
        ProjectMapOwners.Owners found = owners.owners(rel, directory);
        return found == null ? null : found.approvers();
    }

    @Test
    void doubleStarsCrossFoldersAtTheStartTheEndAndInTheMiddle() {
        ProjectMapOwners owners = ProjectMapOwners.of("""
                **/logs @logs
                /build/** @build
                /src/**/gen @gen
                """, rel -> null);
        assertEquals(List.of("@logs"), approvers(owners, "logs", true));
        assertEquals(List.of("@logs"), approvers(owners, "a/b/logs/x.log", false), "any depth, and what is in it");
        assertEquals(List.of("@build"), approvers(owners, "build/out/x.class", false));
        assertNull(approvers(owners, "rebuild/x", false), "anchored at the root");
        assertEquals(List.of("@gen"), approvers(owners, "src/gen", true), "** may be no folder at all");
        assertEquals(List.of("@gen"), approvers(owners, "src/a/b/gen/X.java", false));
        assertNull(approvers(owners, "src/a/genx", false));
    }

    @Test
    void anEscapedCharacterMatchesItselfAndATrailingBackslashIsLiteral() {
        ProjectMapOwners.Rule star = ProjectMapOwners.rule("/a\\*b", List.of("@x"));
        assertNotNull(star);
        assertTrue(ProjectMapOwners.matches(star, "a*b", false));
        assertFalse(ProjectMapOwners.matches(star, "axb", false), "an escaped * is no wildcard");
        ProjectMapOwners.Rule question = ProjectMapOwners.rule("file?.txt", List.of("@q"));
        assertTrue(ProjectMapOwners.matches(question, "docs/file1.txt", false));
        assertFalse(ProjectMapOwners.matches(question, "file12.txt", false));
        ProjectMapOwners.Rule trailing = ProjectMapOwners.rule("/odd\\", List.of("@o"));
        assertNotNull(trailing);
        assertTrue(ProjectMapOwners.matches(trailing, "odd\\", false));
        assertFalse(ProjectMapOwners.matches(trailing, "", false), "the root itself is never a match");
    }

    @Test
    void aBareSlashIsNoRuleAndNonHandlesAreNotOwners() {
        assertNull(ProjectMapOwners.rule("/", List.of("@x")));
        assertNull(ProjectMapOwners.rule("//", List.of("@x")));
        List<ProjectMapOwners.Rule> rules = ProjectMapOwners.parseCodeowners("""
                # comment
                [Docs section]
                ^[Optional section]
                /
                *.md docs-team @writers  # trailing comment
                """);
        assertEquals(1, rules.size(), "sections, comments and a bare / are skipped");
        assertEquals(List.of("@writers"), rules.getFirst().owners(), "a word without @ is not an owner");
        assertEquals("*.md", rules.getFirst().pattern());
    }

    @Test
    void aFolderOnlyPatternOwnsTheFolderAndWhatIsInItButNotAFileOfThatName() {
        ProjectMapOwners owners = ProjectMapOwners.of("vendor/ @deps\n", rel -> null);
        assertEquals(List.of("@deps"), approvers(owners, "vendor", true));
        assertEquals(List.of("@deps"), approvers(owners, "vendor/x/y.go", false));
        assertNull(approvers(owners, "vendor", false), "a file named vendor is not the folder");
        assertNull(approvers(owners, "", true), "the project is owned only by a pattern that names its top");
    }

    @Test
    void ownersFilesWithFlowListsQuotesMarkersAndUrlsAreRead() {
        ProjectMapOwners.Owners read = ProjectMapOwners.parseOwnersFile("""
                ---
                # See the OWNERS docs at https://go.k8s.io/owners
                options:
                  no_parent_owners: true
                approvers: ["alice", 'bob', , carol]
                reviewers:
                  - "dave"
                  -
                  - 'erin'
                  - dave
                emeritus_approvers:
                  - old
                labels: [area/x]
                homepage: https://example.com/a:b
                "quoted:key": [ignored]
                note "with: a colon in quotes" and no key
                """, "pkg/OWNERS");
        assertNotNull(read);
        assertEquals(List.of("alice", "bob", "carol"), read.approvers());
        assertEquals(List.of("dave", "erin"), read.reviewers(), "blank items dropped, names once");
        assertEquals("pkg/OWNERS", read.source());
    }

    @Test
    void reviewersAloneAreEnoughAndOtherFiltersAreLeftOut() {
        ProjectMapOwners.Owners reviewersOnly =
                ProjectMapOwners.parseOwnersFile("reviewers:\n- \"a\\\\b\"\n", "OWNERS");
        assertNotNull(reviewersOnly);
        assertEquals(List.of(), reviewersOnly.approvers());
        assertEquals(List.of("a\\b"), reviewersOnly.reviewers(), "an escaped backslash in quotes");

        ProjectMapOwners.Owners filtered = ProjectMapOwners.parseOwnersFile("""
                filters:
                  "\\\\.go$":
                    approvers:
                      - go-only
                  ".*":
                    approvers: [any]
                    reviewers:
                      - rev
                    labels:
                      - sig/x
                """, "OWNERS");
        assertNotNull(filtered);
        assertEquals(List.of("any"), filtered.approvers(), "only the \".*\" filter owns every file");
        assertEquals(List.of("rev"), filtered.reviewers());

        assertNull(ProjectMapOwners.parseOwnersFile("labels:\n  - x\nemeritus_approvers: [y]\n", "OWNERS"));
        assertNull(ProjectMapOwners.parseOwnersFile("- stray\n", "OWNERS"), "an item with no key goes nowhere");
    }

    @Test
    void anOwnersFileIsReadOnceAndAFolderWithNoneAsksItsParent() {
        java.util.Map<String, Integer> reads = new java.util.HashMap<>();
        Map<String, String> files = Map.of("OWNERS", "approvers: [root]\n", "a/OWNERS", "labels: [x]\n");
        ProjectMapOwners owners = ProjectMapOwners.of(null, rel -> {
            reads.merge(rel, 1, Integer::sum);
            return files.get(rel);
        });
        assertEquals(List.of("root"), approvers(owners, "a/b/c.go", false));
        assertEquals(List.of("root"), approvers(owners, "a/b/d.go", false));
        assertEquals(List.of("root"), approvers(owners, "a", true));
        assertEquals(1, reads.get("a/b/OWNERS"));
        assertEquals(1, reads.get("a/OWNERS"), "a file naming nobody is remembered as none");
        assertEquals(1, reads.get("OWNERS"));
    }

    @Test
    void aProjectWithNoOwnerFilesOwnsNothingAndAnOversizedFileIsNotRead(@TempDir Path temp) throws Exception {
        assertNull(ProjectMapOwners.load(temp).owners("a/b.go", false));
        Files.writeString(temp.resolve("CODEOWNERS"), "* @big\n" + "#".repeat(600 * 1024) + "\n");
        assertNull(ProjectMapOwners.load(temp).owners("a/b.go", false), "a file over 512 KB is passed over");
        Files.createDirectories(temp.resolve("docs"));
        Files.writeString(temp.resolve("docs/CODEOWNERS"), "* @docs\n");
        assertEquals(List.of("@docs"), approvers(ProjectMapOwners.load(temp), "a/b.go", false));
        assertEquals(
                "docs/CODEOWNERS",
                ProjectMapOwners.load(temp).owners("x", false).source());
    }
}
