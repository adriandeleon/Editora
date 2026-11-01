package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.editora.search.ProjectFileList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pure parts of the Project Map review 3 layout fixes: elision, ignored rows and the project's facts. */
class ProjectMapLayoutReview3Test {

    @TempDir
    Path temp;

    // --- B10: elide what siblings share ---------------------------------------------------------------------

    @Test
    void twinsThatShareTheirStartAndEndAreCutApart() {
        String shorter = "AbstractRefreshableApplicationContext.java";
        String longer = "AbstractRefreshableConfigApplicationContext.java";
        Map<String, int[]> shared = ProjectMapElision.sharedAffixes(List.of(shorter, longer, "Other.java"));
        assertArrayEquals(new int[] {19, 23}, shared.get(longer), "AbstractRefreshable / ApplicationContext.java");
        for (int kept = 12; kept < shorter.length(); kept++) {
            String a = ProjectMapElision.distinctCut(shorter, shared.get(shorter)[0], shared.get(shorter)[1], kept);
            String b = ProjectMapElision.distinctCut(longer, shared.get(longer)[0], shared.get(longer)[1], kept);
            assertNotNull(b, "the distinct part fits at " + kept);
            assertTrue(b.contains("Config"), b);
            assertTrue(b.endsWith(".java"), "the extension stays: " + b);
            if (a != null) {
                assertNotEquals(a, b, "the twins are drawn apart at " + kept);
            }
            // The plain middle cut drew them alike.
            String plainA = middle(shorter, kept);
            String plainB = middle(longer, kept);
            if (kept <= 18) {
                assertEquals(plainA, plainB, "the bug: a middle cut keeps only what they share at " + kept);
            }
        }
        assertEquals(longer, ProjectMapElision.distinctCut(longer, 19, 23, longer.length()), "no cut when it fits");
    }

    @Test
    void aNameSharingNothingOrTooLongInItsOwnPartIsLeftToTheMiddleCut() {
        assertNull(ProjectMapElision.distinctCut("UniqueName.java", 0, 0, 6));
        Map<String, int[]> shared = ProjectMapElision.sharedAffixes(List.of(
                "MockitoSpyBeanWithGenericsOnTestFieldForExistingGenericBeanIntegrationTests.java",
                "MockitoSpyBeanAndContextCustomizerIntegrationTests.java"));
        int[] affixes = shared.get("MockitoSpyBeanAndContextCustomizerIntegrationTests.java");
        assertArrayEquals(new int[] {14, 21}, affixes, "MockitoSpyBean / IntegrationTests.java");
        String cut = ProjectMapElision.distinctCut(
                "MockitoSpyBeanAndContextCustomizerIntegrationTests.java", affixes[0], affixes[1], 26);
        assertNotNull(cut);
        assertTrue(cut.contains("AndContextCustomizer"), cut);
        assertNull(
                ProjectMapElision.distinctCut(
                        "MockitoSpyBeanAndContextCustomizerIntegrationTests.java", affixes[0], affixes[1], 10),
                "its own part does not fit: the caller cuts the middle");
    }

    // --- A11: ignored, dependency, vendored and generated rows ----------------------------------------------

    @Test
    void ignoredDependencyVendoredAndGeneratedRowsSayWhyAndTheirContentsInheritIt() {
        Path root = temp.resolve("p");
        String attributes = String.join(
                "\n",
                "# k8s marks its generated code",
                "zz_generated.*.go linguist-generated=true",
                "pkg/generated/** linguist-generated",
                "pkg/generated/keep.go -linguist-generated",
                "docs/vendored/** linguist-vendored",
                "*.png binary");
        ProjectMapIgnored rules = ProjectMapIgnored.of(root, "build/\n*.log\n", attributes);
        assertEquals(ProjectMapIgnored.Reason.IGNORED, rules.classify(root.resolve("build"), true));
        assertEquals(
                ProjectMapIgnored.Reason.IGNORED,
                rules.classify(root.resolve("build/gen/output_report.txt"), false),
                "a file in an ignored folder is ignored with it (E7's output_report.txt)");
        assertEquals(ProjectMapIgnored.Reason.IGNORED, rules.classify(root.resolve("src/debug.log"), false));
        assertEquals(ProjectMapIgnored.Reason.DEPENDENCY, rules.classify(root.resolve("web/node_modules"), true));
        assertEquals(
                ProjectMapIgnored.Reason.DEPENDENCY,
                rules.classify(root.resolve("web/node_modules/react/x.js"), false));
        assertEquals(ProjectMapIgnored.Reason.VENDOR, rules.classify(root.resolve("vendor"), true));
        assertEquals(ProjectMapIgnored.Reason.VENDOR, rules.classify(root.resolve("third_party/lib/a.c"), false));
        assertEquals(
                ProjectMapIgnored.Reason.GENERATED,
                rules.classify(root.resolve("pkg/apis/zz_generated.deepcopy.go"), false));
        assertEquals(ProjectMapIgnored.Reason.GENERATED, rules.classify(root.resolve("pkg/generated/x.go"), false));
        assertNull(rules.classify(root.resolve("pkg/generated/keep.go"), false), "a later line unsets it");
        assertEquals(ProjectMapIgnored.Reason.VENDORED, rules.classify(root.resolve("docs/vendored/a.js"), false));
        assertNull(rules.classify(root.resolve("src/Main.java"), false), "the project's own code");
        assertNull(rules.classify(root.resolve("src"), true));
    }

    @Test
    void aNestedGitignoreIsReadForItsOwnFolder() throws Exception {
        Path root = Files.createDirectories(temp.resolve("mono"));
        Files.writeString(root.resolve(".gitignore"), "*.log\n");
        Path web = Files.createDirectories(root.resolve("packages/web"));
        Files.writeString(web.resolve(".gitignore"), "dist/\n");
        ProjectMapIgnored rules = ProjectMapIgnored.load(root);
        assertEquals(ProjectMapIgnored.Reason.IGNORED, rules.classify(web.resolve("dist"), true));
        assertEquals(ProjectMapIgnored.Reason.IGNORED, rules.classify(web.resolve("dist/bundle.js"), false));
        assertNull(rules.classify(root.resolve("packages/api/dist"), true), "packages/web's rule speaks for it only");
    }

    // --- B4: the project's facts ----------------------------------------------------------------------------

    @Test
    void theFactsNameTheReadmeBuildsModulesLanguagesEntryPointsTestsAndDocs() {
        List<String> files = List.of(
                "README.md",
                "go.mod",
                "Makefile",
                "cmd/kube-scheduler/main.go",
                "cmd/kubelet/main.go",
                "pkg/scheduler/scheduler.go",
                "pkg/scheduler/scheduler_test.go",
                "pkg/kubelet/kubelet.go",
                "staging/src/k8s.io/api/go.mod",
                "staging/src/k8s.io/api/core/v1/types.go",
                "vendor/github.com/x/go.mod",
                "vendor/github.com/x/x.go",
                "web/package.json",
                "web/src/index.ts",
                "app/src/main/java/demo/DemoApplication.java",
                "app/src/test/java/demo/DemoApplicationTests.java",
                "app/pom.xml",
                "test/e2e/e2e.go",
                "docs/guide.md");
        List<String> folders = List.of(
                "app",
                "app/src",
                "app/src/main",
                "app/src/main/java",
                "app/src/main/java/demo",
                "app/src/test",
                "app/src/test/java",
                "app/src/test/java/demo",
                "cmd",
                "cmd/kube-scheduler",
                "cmd/kubelet",
                "docs",
                "pkg",
                "pkg/kubelet",
                "pkg/scheduler",
                "staging",
                "staging/src",
                "staging/src/k8s.io",
                "staging/src/k8s.io/api",
                "staging/src/k8s.io/api/core",
                "staging/src/k8s.io/api/core/v1",
                "test",
                "test/e2e",
                "vendor",
                "vendor/github.com",
                "vendor/github.com/x",
                "web",
                "web/src");
        Map<String, String> texts = Map.of(
                "README.md",
                "[![Build](https://x/badge.svg)](https://x)\n\n# Kubernetes\n\n<img src=\"logo.png\">\n"
                        + "Production-Grade **Container** Scheduling and [Management](https://k8s.io).\n",
                "web/package.json",
                "{\"name\": \"web\", \"main\": \"./src/index.ts\", \"bin\": {\"kweb\": \"bin/kweb.js\"}}");
        ProjectMapOverview.Facts facts =
                ProjectMapOverview.analyse(new ProjectFileList.Snapshot(files, folders, false), texts::get);

        assertEquals("README.md", facts.readme());
        assertEquals("Kubernetes\nProduction-Grade Container Scheduling and Management.", facts.readmeExcerpt());
        assertEquals(
                "Title\nOne paragraph, wrapped over two lines with a reference link.\nSecond.",
                ProjectMapOverview.excerpt(
                        "# Title\n\nOne paragraph, wrapped\nover two lines with a [reference link][1].\n\n"
                                + "Second.\n\nThird.\n\n[1]: https://example.com\n"));
        List<String> systems =
                facts.builds().stream().map(ProjectMapOverview.Build::system).toList();
        assertEquals(List.of("Go modules", "Maven", "npm"), systems);
        assertEquals(2, facts.builds().getFirst().folders(), "the root and staging/…/api; vendor's go.mod is not one");
        assertEquals(
                List.of("", "app", "web", "staging/src/k8s.io/api"),
                facts.modules().stream().map(ProjectMapOverview.Module::folder).toList());
        assertEquals("Go", facts.languages().getFirst().name());
        assertTrue(
                facts.entryPoints()
                        .containsAll(List.of(
                                "cmd/kubelet/",
                                "cmd/kube-scheduler/",
                                "web/src/index.ts",
                                "web/bin/kweb.js",
                                "app/src/main/java/demo/DemoApplication.java")),
                facts.entryPoints().toString());
        assertTrue(
                facts.entryPoints().stream().noneMatch(path -> path.contains("Tests")),
                "a test class is not an entry point");
        assertFalse(facts.entryPoints().contains("cmd/kubelet/main.go"), "a Go command is listed as its folder");
        assertTrue(
                ProjectMapOverview.ownGradleFile("spring-core", "spring-core.gradle"),
                "Spring names its modules' files");
        assertFalse(ProjectMapOverview.ownGradleFile("", "spring-core.gradle"));
        assertEquals(
                "Spring Framework",
                ProjectMapOverview.excerpt("# <img src=\"logo.png\" width=\"80\"> Spring Framework"));
        assertEquals(List.of("test", "app/src/test"), facts.testFolders());
        assertEquals(List.of("docs"), facts.docFolders());
        assertArrayEquals(new int[] {2, 0, 2}, facts.folderCounts().get("cmd"), "two folders, no files, two in all");
        assertEquals(files.size(), facts.folderCounts().get("")[2]);
        assertEquals(
                "pkg",
                facts.topFolders().stream()
                        .filter(folder -> folder.name().equals("pkg"))
                        .findFirst()
                        .orElseThrow()
                        .name());
    }

    /** The Map's plain middle cut (MapSurface.middleCut, without surrogates): what drew the twins alike. */
    private static String middle(String text, int kept) {
        int tail = (kept + 1) / 2;
        return text.substring(0, kept - tail) + "…" + text.substring(text.length() - tail);
    }
}
