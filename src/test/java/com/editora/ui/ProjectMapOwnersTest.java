package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.editora.i18n.Messages;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** CODEOWNERS and Kubernetes OWNERS: GitHub's matching rules, the nearest OWNERS file, and the YAML they hold. */
class ProjectMapOwnersTest {

    @BeforeAll
    static void english() {
        Messages.init("en");
    }

    /** A Spring-like CODEOWNERS (Spring has none): a default, modules, a docs folder's own files, an unowned path. */
    static final String SPRING_CODEOWNERS = """
            # Default owners of everything
            *                                   @spring-projects/spring-framework-core
            /spring-webmvc/                     @spring-projects/web @rstoyanchev
            /spring-web/**/reactive/            @spring-projects/web-reactive
            *.kt                                @spring-projects/kotlin   # Kotlin sources, wherever they are
            framework-docs/*                    @spring-projects/docs
            /spring-core/src/main/java/org/springframework/core/io/ @jhoeller
            /buildSrc/generated/
            [GitLab style section]
            """;

    private static ProjectMapOwners codeowners() {
        return ProjectMapOwners.of(SPRING_CODEOWNERS, rel -> null);
    }

    private static List<String> owners(ProjectMapOwners owners, String rel, boolean directory) {
        ProjectMapOwners.Owners found = owners.owners(rel, directory);
        return found == null ? List.of() : found.approvers();
    }

    @Test
    void theLastMatchingCodeownersLineWins() {
        ProjectMapOwners owners = codeowners();
        assertEquals(List.of("@spring-projects/spring-framework-core"), owners(owners, "build.gradle", false));
        assertEquals(
                List.of("@spring-projects/web", "@rstoyanchev"),
                owners(owners, "spring-webmvc/src/main/java/A.java", false),
                "a folder pattern owns everything in it");
        assertEquals(List.of("@spring-projects/web", "@rstoyanchev"), owners(owners, "spring-webmvc", true));
        assertEquals(
                List.of("@spring-projects/kotlin"),
                owners(owners, "spring-webmvc/src/main/kotlin/A.kt", false),
                "a later *.kt line wins over the folder's");
        assertEquals(
                List.of("@spring-projects/web-reactive"),
                owners(owners, "spring-web/src/main/java/org/springframework/http/server/reactive/X.java", false),
                "** crosses folders");
        assertEquals(
                List.of("@jhoeller"),
                owners(owners, "spring-core/src/main/java/org/springframework/core/io/R.java", false));
        assertEquals(
                List.of("@spring-projects/spring-framework-core"),
                owners(owners, "spring-core/src/main/java/org/springframework/core/Other.java", false));
        assertEquals(".github/CODEOWNERS", owners.owners("a.txt", false).source());
    }

    @Test
    void aSlashStarPatternOwnsTheFoldersOwnFilesOnly() {
        ProjectMapOwners owners = codeowners();
        assertEquals(List.of("@spring-projects/docs"), owners(owners, "framework-docs/antora.yml", false));
        assertEquals(
                List.of("@spring-projects/spring-framework-core"),
                owners(owners, "framework-docs/modules/ROOT/nav.adoc", false),
                "docs/* does not reach a subfolder's files (GitHub's rule)");
    }

    @Test
    void aLineWithoutOwnersLeavesThePathUnowned() {
        ProjectMapOwners owners = codeowners();
        assertNull(owners.owners("buildSrc/generated/Gen.java", false));
        assertNull(owners.owners("buildSrc/generated", true));
        assertEquals(List.of("@spring-projects/spring-framework-core"), owners(owners, "buildSrc/build.gradle", false));
    }

    @Test
    void patternsAnchorAndMatchAsGitHubSays() {
        ProjectMapOwners.Rule anywhere = ProjectMapOwners.rule("apps/", List.of("@a"));
        assertTrue(ProjectMapOwners.matches(anywhere, "x/apps/y.js", false), "unanchored: at any depth");
        assertFalse(ProjectMapOwners.matches(anywhere, "x/apps", false), "a trailing slash: folders only");
        ProjectMapOwners.Rule anchored = ProjectMapOwners.rule("/apps/", List.of("@a"));
        assertFalse(ProjectMapOwners.matches(anchored, "x/apps/y.js", false));
        assertTrue(ProjectMapOwners.matches(anchored, "apps/y/z.js", false));
        ProjectMapOwners.Rule inner = ProjectMapOwners.rule("docs/getting-started.md", List.of("@a"));
        assertFalse(ProjectMapOwners.matches(inner, "x/docs/getting-started.md", false), "an inner slash anchors");
        ProjectMapOwners.Rule logs = ProjectMapOwners.rule("**/logs", List.of("@a"));
        assertTrue(ProjectMapOwners.matches(logs, "build/logs/a.log", false));
        assertTrue(ProjectMapOwners.matches(logs, "logs/a.log", false));
        ProjectMapOwners.Rule question = ProjectMapOwners.rule("v?.txt", List.of("@a"));
        assertTrue(ProjectMapOwners.matches(question, "v1.txt", false));
        assertFalse(ProjectMapOwners.matches(question, "v10.txt", false));
        assertNull(ProjectMapOwners.rule("/", List.of("@a")));
        assertFalse(ProjectMapOwners.matches(anywhere, "", true), "the root itself takes no line");
    }

    @Test
    void theNearestOwnersFileThatNamesSomeoneWins() {
        Map<String, String> files = new HashMap<>();
        files.put("OWNERS", K8S_ROOT_OWNERS);
        files.put("pkg/kubelet/OWNERS", K8S_KUBELET_OWNERS);
        files.put(
                "pkg/kubelet/cm/OWNERS",
                "# only labels here\nlabels:\n  - area/kubelet\nemeritus_approvers:\n  - old\n");
        List<String> reads = new ArrayList<>();
        ProjectMapOwners owners = ProjectMapOwners.of(null, rel -> {
            reads.add(rel);
            return files.get(rel);
        });
        ProjectMapOwners.Owners cm = owners.owners("pkg/kubelet/cm/container_manager.go", false);
        assertEquals(List.of("sig-node-approvers"), cm.approvers(), "cm/OWNERS names nobody: kubelet's wins");
        assertEquals(List.of("sig-node-reviewers"), cm.reviewers());
        assertEquals("pkg/kubelet/OWNERS", cm.source());
        ProjectMapOwners.Owners api = owners.owners("pkg/api", true);
        assertEquals(List.of("dep-approvers", "sig-architecture-approvers"), api.approvers());
        assertEquals("OWNERS", api.source());
        int before = reads.size();
        owners.owners("pkg/kubelet/cm/other.go", false);
        assertEquals(before, reads.size(), "each folder's OWNERS is read once");
        assertNull(ProjectMapOwners.of(null, rel -> null).owners("a/b.go", false));
    }

    @Test
    void codeownersComesBeforeOwnersFiles() {
        ProjectMapOwners owners =
                ProjectMapOwners.of("/docs/ @docs-team\n", rel -> rel.equals("OWNERS") ? K8S_KUBELET_OWNERS : null);
        assertEquals(List.of("@docs-team"), owners(owners, "docs/a.md", false));
        assertEquals(List.of("sig-node-approvers"), owners(owners, "src/a.go", false), "no line: the OWNERS file");
    }

    @Test
    void anOwnersFileIsReadForItsApproversAndReviewers() {
        ProjectMapOwners.Owners root = ProjectMapOwners.parseOwnersFile(K8S_ROOT_OWNERS, "OWNERS");
        assertEquals(List.of("dep-approvers", "sig-architecture-approvers"), root.approvers(), "the \".*\" filter");
        assertEquals(List.of("dep-reviewers", "sig-architecture-reviewers"), root.reviewers());
        ProjectMapOwners.Owners flow =
                ProjectMapOwners.parseOwnersFile("approvers: [alice, 'bob']\nreviewers: []\n", "x/OWNERS");
        assertEquals(List.of("alice", "bob"), flow.approvers());
        assertNull(ProjectMapOwners.parseOwnersFile("options:\n  no_parent_owners: true\n", "OWNERS"));
    }

    @Test
    void theRealKubernetesOwnersFilesAreRead(@TempDir Path temp) throws Exception {
        Path root = Files.createDirectories(temp.resolve("k8s"));
        Files.writeString(root.resolve("OWNERS"), K8S_ROOT_OWNERS);
        Path kubelet = Files.createDirectories(root.resolve("pkg/kubelet/cm"));
        Files.writeString(root.resolve("pkg/kubelet/OWNERS"), K8S_KUBELET_OWNERS);
        Files.writeString(kubelet.resolve("cm.go"), "package cm\n");
        ProjectMapOwners owners = ProjectMapOwners.load(root);
        assertEquals(
                "pkg/kubelet/OWNERS",
                owners.owners("pkg/kubelet/cm/cm.go", false).source());
        Files.createDirectories(root.resolve(".github"));
        Files.writeString(root.resolve(".github/CODEOWNERS"), "* @everyone\n");
        Files.writeString(root.resolve("CODEOWNERS"), "* @not-read\n");
        assertEquals(List.of("@everyone"), owners(ProjectMapOwners.load(root), "pkg/a.go", false), ".github/ first");
    }

    @Test
    void ownerLinesNameTheSourceAndCutLongLists() {
        ProjectMapOwners.Owners many =
                new ProjectMapOwners.Owners(List.of("a", "b", "c", "d", "e", "f"), List.of("r"), "pkg/OWNERS");
        List<String> lines = ProjectMapActivity.ownerLines(many);
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).contains("a, b, c, d +2"), lines.get(0));
        assertTrue(lines.get(0).contains("pkg/OWNERS"), lines.get(0));
        assertTrue(lines.get(1).contains("r"));
        ProjectMapOwners.Owners same = new ProjectMapOwners.Owners(List.of("a"), List.of("a"), "OWNERS");
        assertEquals(1, ProjectMapActivity.ownerLines(same).size(), "reviewers equal to the approvers say nothing");
    }

    /** The shape of kubernetes/kubernetes' root OWNERS: everything under filters, one per file pattern. */
    static final String K8S_ROOT_OWNERS = """
            # See the OWNERS docs at https://go.k8s.io/owners

            filters:
              ".*":
                reviewers:
                  - dep-reviewers
                  - sig-architecture-reviewers
                approvers:
                  - dep-approvers # for go.mod/go.sum
                  - sig-architecture-approvers # for OWNERS_ALIASES and other root files
                emeritus_approvers:
                  - lavalamp
              # go.{mod,sum} files relate to go dependencies
              "go\\\\.(mod|sum|work|work\\\\.sum)$":
                approvers:
                  - dep-approvers
                labels:
                  - area/dependency
            """;

    /** kubernetes/kubernetes' pkg/kubelet/OWNERS. */
    static final String K8S_KUBELET_OWNERS = """
            # See the OWNERS docs at https://go.k8s.io/owners

            approvers:
              - sig-node-approvers # https://github.com/kubernetes/kubernetes/blob/master/OWNERS_ALIASES#LC220
            emeritus_approvers:
              - dashpole
            reviewers:
              - sig-node-reviewers
            labels:
              - area/kubelet
              - sig/node
            """;

    // --- Map review 4 -------------------------------------------------------------------------------------

    @Test
    void aReviewersOnlyOwnersFileNamesReviewersAndTheApproversComeFromAbove() {
        // B3: k8s pkg/apis/abac/OWNERS has only reviewers; approval bubbles up to pkg/apis/OWNERS.
        Map<String, String> files = Map.of(
                "pkg/apis/OWNERS", "approvers:\n  - api-approvers\nreviewers:\n  - api-reviewers\n",
                "pkg/apis/abac/OWNERS", "reviewers:\n  - sig-auth-authorizers-approvers\n  - sig-auth-reviewers\n",
                "lone/OWNERS", "reviewers:\n  - only-reviewers\n");
        ProjectMapOwners owners = ProjectMapOwners.of(null, files::get);
        ProjectMapOwners.Owners abac = owners.owners("pkg/apis/abac/types.go", false);
        assertEquals(List.of("api-approvers"), abac.approvers(), "the nearest approvers, from above");
        assertEquals("pkg/apis/OWNERS", abac.source());
        assertEquals(List.of("sig-auth-authorizers-approvers", "sig-auth-reviewers"), abac.reviewers());
        assertEquals("pkg/apis/abac/OWNERS", abac.reviewersSource());
        assertEquals(
                List.of(
                        Messages.tr("project.map.activity.owners", "api-approvers", "pkg/apis/OWNERS"),
                        Messages.tr(
                                "project.map.activity.reviewersFrom",
                                "sig-auth-authorizers-approvers, sig-auth-reviewers",
                                "pkg/apis/abac/OWNERS")),
                ProjectMapActivity.ownerLines(abac));

        ProjectMapOwners.Owners lone = owners.owners("lone/x.go", false);
        assertTrue(lone.approvers().isEmpty(), "no approvers anywhere above");
        assertEquals(
                List.of(Messages.tr("project.map.activity.reviewersFrom", "only-reviewers", "lone/OWNERS")),
                ProjectMapActivity.ownerLines(lone),
                "reviewers are never shown as owners");

        ProjectMapOwners.Owners apis = owners.owners("pkg/apis/x.go", false);
        assertEquals(
                List.of(
                        Messages.tr("project.map.activity.owners", "api-approvers", "pkg/apis/OWNERS"),
                        Messages.tr("project.map.activity.reviewers", "api-reviewers")),
                ProjectMapActivity.ownerLines(apis),
                "one file with both: as before");
    }

    @Test
    void gitLabSectionsGiveTheirDefaultOwnersAndCombine() {
        // B6: a section's default owners go to its owner-less lines; each section adds its owners.
        String text = """
                * @everyone
                [Frontend] @frontend-team
                /app/frontend/
                *.vue @vue-team

                ^[Docs][2] @docs-team
                /docs/
                /docs/internal/ @internal

                [frontend]
                /app/legacy/
                """;
        ProjectMapOwners owners = ProjectMapOwners.of(text, rel -> null);
        assertEquals(
                List.of("@everyone", "@frontend-team"),
                owners.owners("app/frontend/main.js", false).approvers(),
                "the default section and Frontend's default");
        assertEquals(
                List.of("@everyone", "@vue-team"),
                owners.owners("app/frontend/App.vue", false).approvers());
        assertEquals(
                List.of("@everyone", "@docs-team"),
                owners.owners("docs/x.md", false).approvers());
        assertEquals(
                List.of("@everyone", "@internal"),
                owners.owners("docs/internal/y.md", false).approvers());
        assertEquals(
                List.of("@everyone", "@frontend-team"),
                owners.owners("app/legacy/z.js", false).approvers(),
                "sections of the same name are one, with its defaults");
        assertEquals(List.of("@everyone"), owners.owners("README.md", false).approvers());
    }

    @Test
    void theGitLabPlaceIsReadAndOwnerFileEditsAreNoticed(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve(".gitlab"));
        Files.writeString(root.resolve(".gitlab/CODEOWNERS"), "[Frontend] @frontend-team\n/app/frontend/\n");
        Files.createDirectories(root.resolve("pkg/sub"));
        Files.writeString(root.resolve("pkg/OWNERS"), "approvers:\n  - alice\n");
        ProjectMapOwners owners = ProjectMapOwners.load(root);
        assertEquals(
                List.of("@frontend-team"),
                owners.owners("app/frontend/App.vue", false).approvers());
        assertEquals(
                ".gitlab/CODEOWNERS",
                owners.owners("app/frontend/App.vue", false).source());
        assertEquals(List.of("alice"), owners.owners("pkg/sub/a.go", false).approvers());

        // B9: only a folder whose owner file was read (or looked for) is checked, and only a real change counts.
        assertFalse(owners.changedIn(List.of("pkg", "pkg/sub", ".gitlab")), "nothing changed");
        Files.writeString(root.resolve("pkg/OWNERS"), "approvers:\n  - bob\n");
        assertFalse(owners.changedIn(List.of("docs")), "not a folder these rules read");
        assertTrue(owners.changedIn(List.of("pkg")), "an edited OWNERS");
        Files.writeString(root.resolve("pkg/sub/OWNERS"), "approvers:\n  - cy\n");
        assertTrue(owners.changedIn(List.of("pkg/sub")), "an OWNERS where none was");
        Files.createDirectories(root.resolve(".github"));
        Files.writeString(root.resolve(".github/CODEOWNERS"), "* @gh\n");
        assertTrue(owners.changedIn(List.of(".github")), "a CODEOWNERS that now comes first");
    }
}
