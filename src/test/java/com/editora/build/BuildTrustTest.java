package com.editora.build;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The trust prompt's content (review B10): it names what a build in the folder would run. */
class BuildTrustTest {

    @Test
    void onlyMavenAndGradleGateRuns() {
        assertTrue(BuildTrust.gates(BuildTool.MAVEN));
        assertTrue(BuildTrust.gates(BuildTool.GRADLE));
        assertFalse(BuildTrust.gates(BuildTool.NPM));
        assertFalse(BuildTrust.gates(BuildTool.CARGO));
    }

    @Test
    void aMavenFolderListsTheWrapperThePluginsTheMvnFilesAndTheOverride(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("pom.xml"), """
                <project><artifactId>demo</artifactId><build><plugins>
                  <plugin><groupId>org.openjfx</groupId><artifactId>javafx-maven-plugin</artifactId><version>0.0.8</version></plugin>
                  <plugin><artifactId>maven-surefire-plugin</artifactId></plugin>
                </plugins></build>
                <profiles><profile><id>p</id><build><plugins>
                  <plugin><groupId>org.codehaus.mojo</groupId><artifactId>exec-maven-plugin</artifactId></plugin>
                </plugins></build></profile></profiles></project>
                """);
        Files.createDirectories(root.resolve(".mvn"));
        Files.writeString(root.resolve(".mvn").resolve("extensions.xml"), "<extensions/>");
        Files.writeString(root.resolve(".mvn").resolve("maven.config"), "-s evil.xml");
        Path wrapper = root.resolve("mvnw");

        BuildTrust.Summary s = BuildTrust.describe(BuildTool.MAVEN, root, wrapper, "/opt/mvn/bin/mvn");

        assertEquals(root, s.root());
        assertEquals(wrapper, s.wrapper());
        assertEquals(
                java.util.List.of(
                        tr("trust.item.wrapper", "mvnw"),
                        tr(
                                "trust.item.plugins",
                                "org.openjfx:javafx-maven-plugin:0.0.8, org.apache.maven.plugins:maven-surefire-plugin, "
                                        + "org.codehaus.mojo:exec-maven-plugin"),
                        tr("trust.item.mvnFiles", ".mvn/extensions.xml, .mvn/maven.config"),
                        tr("trust.item.projectOverride", "/opt/mvn/bin/mvn", "mvn")),
                s.items());
    }

    @Test
    void manyPluginsAreCappedAndABrokenPomIsSaidSo(@TempDir Path root) throws Exception {
        StringBuilder plugins = new StringBuilder();
        for (int i = 0; i < BuildTrust.MAX_LISTED_PLUGINS + 3; i++) {
            plugins.append("<plugin><artifactId>p").append(i).append("</artifactId></plugin>");
        }
        Files.writeString(
                root.resolve("pom.xml"),
                "<project><artifactId>demo</artifactId><build><plugins>" + plugins + "</plugins></build></project>");
        BuildTrust.Summary capped = BuildTrust.describe(BuildTool.MAVEN, root, null, "");
        java.util.List<String> first = new java.util.ArrayList<>();
        for (int i = 0; i < BuildTrust.MAX_LISTED_PLUGINS; i++) {
            first.add("org.apache.maven.plugins:p" + i);
        }
        assertEquals(
                java.util.List.of(tr("trust.item.pluginsMore", String.join(", ", first), 3)),
                capped.items(),
                "the first few, then how many more");

        Files.writeString(root.resolve("pom.xml"), "<project><artifactId>x");
        assertEquals(
                java.util.List.of(tr("trust.item.pomUnreadable")),
                BuildTrust.describe(BuildTool.MAVEN, root, null, null).items());
    }

    @Test
    void aPomWithNoPluginsStillNamesTheBuildFile(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("pom.xml"), "<project><artifactId>demo</artifactId></project>");
        assertEquals(
                java.util.List.of(tr("trust.item.buildFile", "pom.xml")),
                BuildTrust.describe(BuildTool.MAVEN, root, null, "").items());
    }

    @Test
    void aGradleFolderListsItsScripts(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("build.gradle.kts"), "");
        Files.writeString(root.resolve("settings.gradle.kts"), "");
        Files.createDirectories(root.resolve("buildSrc"));
        BuildTrust.Summary s = BuildTrust.describe(BuildTool.GRADLE, root, root.resolve("gradlew"), "");
        assertEquals(
                java.util.List.of(
                        tr("trust.item.wrapper", "gradlew"),
                        tr("trust.item.gradleScripts", "build.gradle.kts, settings.gradle.kts, buildSrc/")),
                s.items());
    }
}
