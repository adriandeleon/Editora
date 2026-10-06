package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.editora.build.BuildTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two project walks behind a JVM test run: finding the modules' report files (every 750 ms while the run
 * lasts) and listing the test sources to pre-seed the tree. Both used to enumerate the whole project —
 * {@code .git}, {@code node_modules} and all — and filter afterwards.
 */
class TestRunCoordinatorWalkTest {

    private static Path file(Path root, String rel) throws Exception {
        Path f = root.resolve(rel);
        Files.createDirectories(f.getParent());
        return Files.writeString(f, "<testsuite/>");
    }

    private static List<String> rels(Path root, List<Path> files) {
        return files.stream()
                .map(p -> root.relativize(p).toString().replace('\\', '/'))
                .sorted()
                .toList();
    }

    @Test
    void mavenReportFilesAreFoundInEveryModuleButNotInDependencyOrDotTrees(@TempDir Path root) throws Exception {
        file(root, "target/surefire-reports/TEST-com.x.RootTest.xml");
        file(root, "target/surefire-reports/com.x.RootTest.txt"); // not a TEST-*.xml
        file(root, "services/billing/target/surefire-reports/TEST-com.x.BillingTest.xml");
        file(root, "services/billing/target/failsafe-reports/TEST-com.x.BillingIT.xml");
        file(root, "web/node_modules/pkg/target/surefire-reports/TEST-vendored.xml");
        file(root, ".cache/old/target/surefire-reports/TEST-stale.xml");

        assertEquals(
                List.of(
                        "services/billing/target/failsafe-reports/TEST-com.x.BillingIT.xml",
                        "services/billing/target/surefire-reports/TEST-com.x.BillingTest.xml",
                        "target/surefire-reports/TEST-com.x.RootTest.xml"),
                rels(root, TestRunCoordinator.reportFiles(BuildTool.MAVEN, root)));
    }

    @Test
    void gradleReportFilesIncludeEveryTestTaskOfEveryProject(@TempDir Path root) throws Exception {
        file(root, "build/test-results/test/TEST-com.x.RootTest.xml");
        file(root, "app/build/test-results/test/TEST-com.x.AppTest.xml");
        file(root, "app/build/test-results/integrationTest/TEST-com.x.AppIT.xml");

        assertEquals(
                List.of(
                        "app/build/test-results/integrationTest/TEST-com.x.AppIT.xml",
                        "app/build/test-results/test/TEST-com.x.AppTest.xml",
                        "build/test-results/test/TEST-com.x.RootTest.xml"),
                rels(root, TestRunCoordinator.reportFiles(BuildTool.GRADLE, root)));
    }

    /** The depth the walk has always had: a report directory five levels down is found, one deeper is not. */
    @Test
    void theReportDirectoryWalkKeepsItsDepthLimit(@TempDir Path root) throws Exception {
        file(root, "a/b/c/target/surefire-reports/TEST-five.xml");
        file(root, "a/b/c/d/target/surefire-reports/TEST-six.xml");

        assertEquals(
                List.of("a/b/c/target/surefire-reports/TEST-five.xml"),
                rels(root, TestRunCoordinator.reportFiles(BuildTool.MAVEN, root)));
    }

    @Test
    void testSourcesAreListedFromSourceTreesNotFromBuildOutputOrIgnoredTrees(@TempDir Path root) throws Exception {
        file(root, "src/test/java/com/x/FooTest.java");
        file(root, "mod/src/test/java/com/x/build/WrapperTest.java"); // a package named build is still source
        file(root, "src/main/java/com/x/Foo.java");
        file(root, "target/test-classes/test/Copied.java");
        file(root, "mod/build/tmp/test/Generated.java");
        file(root, "node_modules/pkg/test/Vendored.java");
        file(root, "ignored/test/Skipped.java");
        Files.writeString(root.resolve(".gitignore"), "ignored/\n");

        List<Path> sources = TestRunCoordinator.testSourceFiles(root);

        assertNotNull(sources);
        assertEquals(
                List.of("mod/src/test/java/com/x/build/WrapperTest.java", "src/test/java/com/x/FooTest.java"),
                rels(root, sources));
    }

    /** "test" above the project root says nothing about a file inside it (it used to match every .java file). */
    @Test
    void aTestSegmentAboveTheProjectRootDoesNotMakeEverythingATestSource(@TempDir Path base) throws Exception {
        Path root = Files.createDirectories(base.resolve("tests").resolve("project"));
        file(root, "src/main/java/com/x/Foo.java");
        file(root, "src/test/java/com/x/FooTest.java");

        List<Path> sources = TestRunCoordinator.testSourceFiles(root);

        assertNotNull(sources);
        assertEquals(List.of("src/test/java/com/x/FooTest.java"), rels(root, sources));
        assertTrue(root.toString().replace('\\', '/').contains("/tests/"));
    }
}
