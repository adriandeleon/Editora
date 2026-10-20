package com.editora.maven;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The summary's second pass (Maven review C5/C6/C9/C12/C13/C21): element lines, where a blank version comes
 * from (this file, an imported BOM read from the local repository, a parent on disk — or honestly unknown),
 * profile activation conditions, repositories/reporting plugins/url/scm, and the parse error's position.
 */
class PomSummaryNeighboursTest {

    private static final String QUICKSTART = """
            <project>
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.example</groupId>
              <artifactId>quick</artifactId>
              <version>1.0</version>
              <url>https://example.com/quick</url>
              <scm><url>https://github.com/example/quick</url></scm>
              <properties>
                <junit.version>5.11.0</junit.version>
              </properties>
              <dependencyManagement>
                <dependencies>
                  <dependency>
                    <groupId>org.junit</groupId>
                    <artifactId>junit-bom</artifactId>
                    <version>${junit.version}</version>
                    <type>pom</type>
                    <scope>import</scope>
                  </dependency>
                </dependencies>
              </dependencyManagement>
              <dependencies>
                <dependency>
                  <groupId>org.junit.jupiter</groupId>
                  <artifactId>junit-jupiter-api</artifactId>
                  <scope>test</scope>
                </dependency>
                <dependency>
                  <groupId>org.example</groupId>
                  <artifactId>dangling</artifactId>
                  <version>${nowhere.version}</version>
                </dependency>
              </dependencies>
              <repositories>
                <repository>
                  <id>central</id>
                  <url>https://repo.maven.apache.org/maven2</url>
                </repository>
                <repository>
                  <id>corp</id>
                  <url>https://nexus.corp.example/repository/maven-public</url>
                </repository>
              </repositories>
              <pluginRepositories>
                <pluginRepository>
                  <id>plugins</id>
                  <url>https://plugins.example/maven2</url>
                </pluginRepository>
              </pluginRepositories>
              <reporting>
                <plugins>
                  <plugin>
                    <artifactId>maven-javadoc-plugin</artifactId>
                    <version>3.11.2</version>
                  </plugin>
                </plugins>
              </reporting>
              <profiles>
                <profile>
                  <id>ci</id>
                  <activation>
                    <property><name>env.CI</name></property>
                    <jdk>[25,)</jdk>
                  </activation>
                </profile>
                <profile>
                  <id>mac</id>
                  <activation>
                    <activeByDefault>true</activeByDefault>
                    <os><family>mac</family><arch>aarch64</arch></os>
                    <file><exists>src/main/native</exists></file>
                  </activation>
                </profile>
              </profiles>
            </project>
            """;

    private static final String JUNIT_BOM = """
            <project>
              <groupId>org.junit</groupId>
              <artifactId>junit-bom</artifactId>
              <version>5.11.0</version>
              <packaging>pom</packaging>
              <dependencyManagement>
                <dependencies>
                  <dependency>
                    <groupId>org.junit.jupiter</groupId>
                    <artifactId>junit-jupiter-api</artifactId>
                    <version>${project.version}</version>
                  </dependency>
                </dependencies>
              </dependencyManagement>
            </project>
            """;

    private static final String REACTOR_PARENT = """
            <project>
              <groupId>com.example</groupId>
              <artifactId>reactor-parent</artifactId>
              <version>2.0.0</version>
              <packaging>pom</packaging>
              <properties>
                <slf4j.version>2.0.17</slf4j.version>
              </properties>
              <modules><module>core</module></modules>
              <dependencyManagement>
                <dependencies>
                  <dependency>
                    <groupId>org.slf4j</groupId>
                    <artifactId>slf4j-api</artifactId>
                    <version>${slf4j.version}</version>
                  </dependency>
                </dependencies>
              </dependencyManagement>
              <build>
                <pluginManagement>
                  <plugins>
                    <plugin>
                      <artifactId>maven-compiler-plugin</artifactId>
                      <version>3.14.0</version>
                    </plugin>
                  </plugins>
                </pluginManagement>
              </build>
            </project>
            """;

    private static final String CORE = """
            <project>
              <parent>
                <groupId>com.example</groupId>
                <artifactId>reactor-parent</artifactId>
                <version>2.0.0</version>
              </parent>
              <artifactId>core</artifactId>
              <dependencies>
                <dependency>
                  <groupId>org.slf4j</groupId>
                  <artifactId>slf4j-api</artifactId>
                </dependency>
                <dependency>
                  <groupId>org.example</groupId>
                  <artifactId>elsewhere</artifactId>
                </dependency>
                <dependency>
                  <groupId>org.example</groupId>
                  <artifactId>viaprop</artifactId>
                  <version>${slf4j.version}</version>
                </dependency>
              </dependencies>
              <build>
                <plugins>
                  <plugin>
                    <artifactId>maven-compiler-plugin</artifactId>
                  </plugin>
                </plugins>
              </build>
            </project>
            """;

    @BeforeEach
    void freshCache() {
        PomNeighbours.clearCache();
    }

    private static PomSummary.Dependency dependency(PomSummary s, String artifactId) {
        return s.dependencies().stream()
                .filter(d -> d.artifactId().equals(artifactId))
                .findFirst()
                .orElseThrow();
    }

    // --- one file: honest labels, lines, extras --------------------------------------------------------

    @Test
    void aParentlessPomAttributesABlankVersionToItsBomImportByName() {
        PomSummary s = PomSummary.parse(QUICKSTART);
        PomSummary.Dependency api = dependency(s, "junit-jupiter-api");
        assertEquals("", api.effectiveVersion(), "the BOM was not read: no number to show");
        assertFalse(api.managed());
        assertEquals("junit-bom 5.11.0 (import)", api.managedBy(), "the only possible source, named");
        assertNull(s.parent(), "nothing is inherited here — the old label said so wrongly");
    }

    @Test
    void aDanglingPropertyReferenceStaysUnresolvedAndUnattributed() {
        PomSummary.Dependency d = dependency(PomSummary.parse(QUICKSTART), "dangling");
        assertEquals("${nowhere.version}", d.version());
        assertEquals("", d.effectiveVersion());
        assertEquals("", d.managedBy(), "a written ${…} is not a BOM question");
    }

    @Test
    void elementsRememberTheirLines() {
        PomSummary s = PomSummary.parse(QUICKSTART);
        assertEquals(9, s.properties().get(0).line(), "<junit.version> is on line 9");
        assertEquals(23, dependency(s, "junit-jupiter-api").line());
        assertEquals(13, s.managedDependencies().get(0).line());
        assertEquals(35, s.repositories().get(0).line());
        assertEquals(52, s.reportingPlugins().get(0).line());
        assertEquals(59, s.profiles().get(0).line());
    }

    @Test
    void readsRepositoriesFlaggingTheOnesThatAreNotCentral() {
        PomSummary s = PomSummary.parse(QUICKSTART);
        assertEquals(
                List.of("central", "corp", "plugins"),
                s.repositories().stream().map(PomSummary.Repository::id).toList());
        assertTrue(s.repositories().get(0).central());
        assertFalse(s.repositories().get(1).central());
        assertFalse(s.repositories().get(0).plugin());
        assertTrue(s.repositories().get(2).plugin());
    }

    @Test
    void readsReportingPluginsUrlAndScm() {
        PomSummary s = PomSummary.parse(QUICKSTART);
        assertEquals("maven-javadoc-plugin", s.reportingPlugins().get(0).artifactId());
        assertEquals("3.11.2", s.reportingPlugins().get(0).effectiveVersion());
        assertEquals("https://example.com/quick", s.url());
        assertEquals("https://github.com/example/quick", s.scmUrl());
    }

    @Test
    void describesProfileActivationConditions() {
        PomSummary s = PomSummary.parse(QUICKSTART);
        PomSummary.Profile ci = s.profiles().get(0);
        assertFalse(ci.activeByDefault());
        assertEquals(List.of("jdk [25,)", "property env.CI"), ci.activation().conditions());
        assertEquals("jdk [25,), property env.CI", ci.activation().describe());
        PomSummary.Profile mac = s.profiles().get(1);
        assertTrue(mac.activeByDefault());
        assertEquals(
                List.of("os mac aarch64", "file exists src/main/native"),
                mac.activation().conditions());
        assertFalse(PomSummary.parse("<project><profiles><profile><id>x</id></profile></profiles></project>")
                .profiles()
                .get(0)
                .activation()
                .conditional());
    }

    @Test
    void aParseErrorCarriesItsLineAndColumnWithoutTheDoubledPrefix() {
        PomSummary s = PomSummary.parse("<project>\n  <artifactId>oops\n</project>\n");
        assertFalse(s.ok());
        assertFalse(s.error().startsWith("Malformed pom.xml"), s.error());
        assertTrue(s.error().contains("artifactId"), s.error());
        assertEquals(3, s.errorLine());
        assertTrue(s.errorColumn() > 0);
    }

    // --- beside the file: BOM from the local repository, parent chain on disk -----------------------------

    @Test
    void aBomInTheLocalRepositorySuppliesTheManagedVersionTaggedWithItsName(@TempDir Path tmp) throws Exception {
        Path repo = tmp.resolve("repo");
        Path bomPom = repo.resolve("org/junit/junit-bom/5.11.0/junit-bom-5.11.0.pom");
        Files.createDirectories(bomPom.getParent());
        Files.writeString(bomPom, JUNIT_BOM);
        Path pom = tmp.resolve("quick/pom.xml");
        Files.createDirectories(pom.getParent());
        Files.writeString(pom, QUICKSTART);

        PomSummary.Dependency api = dependency(PomSummary.parse(QUICKSTART, pom, repo), "junit-jupiter-api");
        assertEquals("5.11.0", api.effectiveVersion(), "${project.version} resolved inside the BOM");
        assertTrue(api.managed());
        assertEquals("junit-bom 5.11.0 (import)", api.managedBy());
    }

    @Test
    void theParentOnDiskFillsManagedVersionsPropertiesAndPluginVersions(@TempDir Path tmp) throws Exception {
        Path parentPom = tmp.resolve("reactor/pom.xml");
        Path corePom = tmp.resolve("reactor/core/pom.xml");
        Files.createDirectories(corePom.getParent());
        Files.writeString(parentPom, REACTOR_PARENT);
        Files.writeString(corePom, CORE);

        PomSummary s = PomSummary.parse(CORE, corePom, tmp.resolve("no-repo"));
        assertEquals(List.of("reactor-parent 2.0.0"), s.inheritedFrom());
        PomSummary.Dependency slf4j = dependency(s, "slf4j-api");
        assertEquals("2.0.17", slf4j.effectiveVersion(), "the parent's ${slf4j.version} resolved in the parent");
        assertTrue(slf4j.managed());
        assertEquals("reactor-parent 2.0.0", slf4j.managedBy());
        assertEquals(
                "2.0.17", dependency(s, "viaprop").effectiveVersion(), "a parent property resolves a child reference");
        PomSummary.Plugin compiler = s.plugins().get(0);
        assertEquals("3.14.0", compiler.effectiveVersion());
        assertEquals("reactor-parent 2.0.0", compiler.managedBy());
        // Still honest about what no file supplies: declared parent → "inherited" is the preview's word for it.
        PomSummary.Dependency elsewhere = dependency(s, "elsewhere");
        assertEquals("", elsewhere.effectiveVersion());
        assertEquals("", elsewhere.managedBy());
    }

    @Test
    void theOneFileParseNeverReadsTheDisk(@TempDir Path tmp) throws Exception {
        Path parentPom = tmp.resolve("reactor/pom.xml");
        Files.createDirectories(parentPom.getParent().resolve("core"));
        Files.writeString(parentPom, REACTOR_PARENT);
        PomSummary s = PomSummary.parse(CORE);
        assertTrue(s.inheritedFrom().isEmpty());
        assertEquals("", dependency(s, "slf4j-api").effectiveVersion());
    }

    @Test
    void aRelativePathPointingAtTheWrongArtifactFallsBackToTheRepositoryCopy(@TempDir Path tmp) throws Exception {
        Path other = tmp.resolve("elsewhere/pom.xml");
        Files.createDirectories(other.getParent());
        Files.writeString(other, REACTOR_PARENT.replace("reactor-parent", "someone-else"));
        Path repo = tmp.resolve("repo");
        Path repoParent = repo.resolve("com/example/reactor-parent/2.0.0/reactor-parent-2.0.0.pom");
        Files.createDirectories(repoParent.getParent());
        Files.writeString(repoParent, REACTOR_PARENT);
        Path corePom = tmp.resolve("app/core/pom.xml");
        Files.createDirectories(corePom.getParent());
        String core = CORE.replace(
                "<version>2.0.0</version>\n  </parent>",
                "<version>2.0.0</version>\n    <relativePath>../../elsewhere/pom.xml</relativePath>\n  </parent>");
        Files.writeString(corePom, core);

        PomSummary s = PomSummary.parse(core, corePom, repo);
        assertEquals(List.of("reactor-parent 2.0.0"), s.inheritedFrom(), "the mismatched file was skipped");
        assertEquals("2.0.17", dependency(s, "slf4j-api").effectiveVersion());
    }

    @Test
    void aParentCycleAndAMissingParentBothEndTheChainQuietly(@TempDir Path tmp) throws Exception {
        Path a = tmp.resolve("a/pom.xml");
        Path b = tmp.resolve("a/b/pom.xml");
        Files.createDirectories(b.getParent());
        // a's parent is b (relativePath b/pom.xml), b's parent is a: a cycle.
        Files.writeString(a, """
                <project>
                  <parent><groupId>g</groupId><artifactId>b</artifactId><version>1</version>
                    <relativePath>b/pom.xml</relativePath></parent>
                  <artifactId>a</artifactId>
                </project>
                """);
        Files.writeString(b, """
                <project>
                  <parent><groupId>g</groupId><artifactId>a</artifactId><version>1</version></parent>
                  <artifactId>b</artifactId>
                  <properties><p>1</p></properties>
                </project>
                """);
        PomSummary s = PomSummary.parse(Files.readString(a), a, tmp.resolve("no-repo"));
        assertEquals(List.of("b 1"), s.inheritedFrom(), "b was read once; its parent (a again) was not");

        PomSummary orphan = PomSummary.parse(CORE, tmp.resolve("lonely/pom.xml"), tmp.resolve("no-repo"));
        assertTrue(orphan.ok());
        assertTrue(orphan.inheritedFrom().isEmpty());
    }

    @Test
    void repositoryPathsRefuseUnusableCoordinates(@TempDir Path tmp) {
        assertNull(PomNeighbours.repositoryPom(tmp, "org.x", "a", "${v}"));
        assertNull(PomNeighbours.repositoryPom(tmp, "../x", "a", "1"));
        assertNull(PomNeighbours.repositoryPom(tmp, "org.x", "", "1"));
        assertEquals(tmp.resolve("org/x/a/1/a-1.pom"), PomNeighbours.repositoryPom(tmp, "org.x", "a", "1"));
    }
}
