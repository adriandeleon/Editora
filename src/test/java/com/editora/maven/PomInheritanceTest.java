package com.editora.maven;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The on-disk parent chain (review B6): a module's model carries its parent's profiles and plugins, the
 * walk never leaves the project, a parent that is not the one named stops it, and {@code ${property}}
 * plugin versions resolve through the merged properties (B26) so the goal-descriptor lookup reads the
 * version the build runs.
 */
class PomInheritanceTest {

    private static final String PARENT = """
            <project>
              <groupId>com.example</groupId>
              <artifactId>multi</artifactId>
              <version>1.0-SNAPSHOT</version>
              <packaging>pom</packaging>
              <properties>
                <surefire.version>3.2.5</surefire.version>
                <exec.version>${exec.base}.1</exec.version>
                <exec.base>3.5</exec.base>
              </properties>
              <build>
                <pluginManagement><plugins><plugin>
                  <artifactId>maven-enforcer-plugin</artifactId><version>3.5.0</version>
                </plugin></plugins></pluginManagement>
                <plugins>
                  <plugin><groupId>org.codehaus.mojo</groupId><artifactId>exec-maven-plugin</artifactId>
                    <version>${exec.version}</version></plugin>
                  <plugin><artifactId>maven-surefire-plugin</artifactId><version>${surefire.version}</version>
                    <executions><execution><id>unit</id><phase>test</phase><goals><goal>test</goal></goals></execution>
                    </executions></plugin>
                </plugins>
              </build>
              <profiles>
                <profile><id>dev</id><activation><activeByDefault>true</activeByDefault></activation></profile>
                <profile><id>release</id><build><plugins><plugin><artifactId>maven-source-plugin</artifactId>
                  <executions><execution><id>attach</id><goals><goal>jar-no-fork</goal></goals></execution></executions>
                </plugin></plugins></build></profile>
              </profiles>
            </project>
            """;

    private static final String CHILD = """
            <project>
              <parent><groupId>com.example</groupId><artifactId>multi</artifactId><version>1.0-SNAPSHOT</version></parent>
              <artifactId>app</artifactId>
              <properties><surefire.version>3.5.0</surefire.version></properties>
              <build><plugins>
                <plugin><artifactId>maven-surefire-plugin</artifactId>
                  <executions><execution><id>its</id><phase>verify</phase><goals><goal>test</goal></goals></execution>
                  </executions></plugin>
              </plugins></build>
              <profiles>
                <profile><id>release</id><build><plugins><plugin><artifactId>maven-javadoc-plugin</artifactId>
                </plugin></plugins></build></profile>
                <profile><id>ci</id><activation><property><name>env.CI</name></property></activation></profile>
              </profiles>
            </project>
            """;

    private static Path multi(Path dir) throws Exception {
        Files.writeString(dir.resolve("pom.xml"), PARENT);
        Path app = Files.createDirectories(dir.resolve("app"));
        Files.writeString(app.resolve("pom.xml"), CHILD);
        return app.resolve("pom.xml");
    }

    private static List<String> keys(List<PomModel.Plugin> plugins) {
        return plugins.stream().map(PomModel.Plugin::key).toList();
    }

    @Test
    void aModuleCarriesItsParentsProfilesAndPlugins(@TempDir Path dir) throws Exception {
        PomModel model = PomInheritance.effective(multi(dir), dir);

        assertEquals("app", model.artifactId());
        assertEquals("1.0-SNAPSHOT", model.version(), "a blank version falls back to the parent's");
        assertEquals(List.of("multi"), model.inheritedFrom());
        assertEquals(
                List.of("org.codehaus.mojo:exec-maven-plugin", "org.apache.maven.plugins:maven-surefire-plugin"),
                keys(model.plugins()),
                "parent first, the child's redeclaration merged into the parent's slot");
        assertEquals(
                List.of("dev", "release", "ci"),
                model.profiles().stream().map(PomModel.Profile::id).toList(),
                "parent profiles first; a same-id profile is one toggle");
        PomModel.Profile release = model.profiles().get(1);
        assertEquals(
                List.of(
                        "org.apache.maven.plugins:maven-source-plugin",
                        "org.apache.maven.plugins:maven-javadoc-plugin"),
                keys(release.plugins()),
                "both pom's plugins of the same profile");
        assertEquals(List.of("org.apache.maven.plugins:maven-enforcer-plugin"), keys(model.managedPlugins()));
    }

    @Test
    void executionsMergeByIdAndTheChildsPropertyWins(@TempDir Path dir) throws Exception {
        PomModel model = PomInheritance.effective(multi(dir), dir);
        PomModel.Plugin surefire = model.plugins().get(1);
        assertEquals(
                List.of("unit", "its"),
                surefire.executions().stream().map(PomModel.Execution::id).toList(),
                "the parent's execution is kept beside the child's");
        assertEquals("3.5.0", surefire.version(), "${surefire.version} resolved; the child's value wins");
        assertEquals("3.5.1", model.plugins().get(0).version(), "a property referencing a property resolves too");
    }

    @Test
    void theWalkStopsAtTheProjectBoundary(@TempDir Path dir) throws Exception {
        Path app = multi(dir).getParent();
        PomModel bounded = PomInheritance.effective(app.resolve("pom.xml"), app);
        assertEquals(List.of(), bounded.inheritedFrom(), "the parent sits above the open project: not read");
        assertEquals(List.of("org.apache.maven.plugins:maven-surefire-plugin"), keys(bounded.plugins()));
        assertEquals("", bounded.plugins().get(0).version(), "the child declares no version of its own");
    }

    @Test
    void theChildsOwnPropertiesResolveWithoutAParent(@TempDir Path dir) throws Exception {
        Path app = Files.createDirectories(dir.resolve("app"));
        Files.writeString(
                app.resolve("pom.xml"),
                CHILD.replace(
                        "<version>1.0-SNAPSHOT</version></parent>",
                        "<version>1.0-SNAPSHOT</version><relativePath/></parent>"));
        PomModel model = PomInheritance.effective(app.resolve("pom.xml"), dir);
        assertEquals(List.of(), model.inheritedFrom(), "an empty <relativePath/> means repository-only");
        assertEquals("", model.plugins().get(0).version(), "the parent's ${surefire.version} was never seen");
    }

    @Test
    void aParentThatIsNotTheOneNamedIsNotMerged(@TempDir Path dir) throws Exception {
        Files.writeString(
                dir.resolve("pom.xml"),
                PARENT.replace("<artifactId>multi</artifactId>", "<artifactId>other</artifactId>"));
        Path app = Files.createDirectories(dir.resolve("app"));
        Files.writeString(app.resolve("pom.xml"), CHILD);
        PomModel model = PomInheritance.effective(app.resolve("pom.xml"), dir);
        assertEquals(List.of(), model.inheritedFrom(), "Maven would resolve `multi` from a repository");
    }

    @Test
    void relativePathPointsAtAFileOrAFolder(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("pom.xml"), PARENT);
        Path parents = Files.createDirectories(dir.resolve("parents"));
        Files.writeString(parents.resolve("pom.xml"), PARENT);
        Path app = Files.createDirectories(dir.resolve("modules").resolve("app"));
        Files.writeString(
                app.resolve("pom.xml"),
                CHILD.replace("</parent>", "<relativePath>../../parents</relativePath></parent>"));
        assertEquals(
                parents.resolve("pom.xml").toAbsolutePath().normalize(),
                PomInheritance.parentPomPath(
                        app.resolve("pom.xml"),
                        PomParser.parseFile(app.resolve("pom.xml")).parent()));
        assertEquals(
                List.of("multi"),
                PomInheritance.effective(app.resolve("pom.xml"), dir).inheritedFrom());

        Files.writeString(
                app.resolve("pom.xml"),
                CHILD.replace("</parent>", "<relativePath>../../parents/pom.xml</relativePath></parent>"));
        assertEquals(
                List.of("multi"),
                PomInheritance.effective(app.resolve("pom.xml"), dir).inheritedFrom());
    }

    @Test
    void aCycleAndTheDepthCapEndTheWalk(@TempDir Path dir) throws Exception {
        // a → b → a: the second visit of a stops the chain.
        Path a = Files.createDirectories(dir.resolve("a"));
        Path b = Files.createDirectories(dir.resolve("b"));
        Files.writeString(a.resolve("pom.xml"), """
                <project><parent><groupId>g</groupId><artifactId>b</artifactId><version>1</version>
                <relativePath>../b/pom.xml</relativePath></parent><artifactId>a</artifactId></project>
                """);
        Files.writeString(b.resolve("pom.xml"), """
                <project><parent><groupId>g</groupId><artifactId>a</artifactId><version>1</version>
                <relativePath>../a/pom.xml</relativePath></parent><artifactId>b</artifactId></project>
                """);
        assertEquals(
                List.of("b"),
                PomInheritance.effective(a.resolve("pom.xml"), dir).inheritedFrom());

        // A chain longer than the cap: only MAX_DEPTH parents are merged.
        Path prev = dir;
        for (int i = 0; i < PomInheritance.MAX_DEPTH + 3; i++) {
            Path level = Files.createDirectories(prev.resolve("m" + i));
            String parent = i == 0
                    ? ""
                    : "<parent><groupId>g</groupId><artifactId>m" + (i - 1)
                            + "</artifactId><version>1</version></parent>";
            Files.writeString(
                    level.resolve("pom.xml"),
                    "<project>" + parent + "<groupId>g</groupId><artifactId>m" + i
                            + "</artifactId><version>1</version></project>");
            prev = level;
        }
        PomModel deep = PomInheritance.effective(prev.resolve("pom.xml"), dir);
        assertEquals(PomInheritance.MAX_DEPTH, deep.inheritedFrom().size());
    }

    @Test
    void resolveLeavesAnUnknownReferenceAsWritten() {
        PomModel model = new PomModel(
                "g", "a", "2.0", "jar", List.of(), List.of(), null, Map.of("x", "1"), List.of(), List.of());
        assertEquals("1", PomInheritance.resolve(model, "${x}"));
        assertEquals("2.0", PomInheritance.resolve(model, "${project.version}"));
        assertEquals("${nope}", PomInheritance.resolve(model, "${nope}"));
        assertEquals("1-${nope}", PomInheritance.resolve(model, "1-${nope}"), "partially unknown: unchanged");
        assertEquals("", PomInheritance.resolve(model, null));
    }

    @Test
    void aMissingParentFileEndsTheChainQuietly(@TempDir Path dir) throws Exception {
        Path app = Files.createDirectories(dir.resolve("app"));
        Files.writeString(app.resolve("pom.xml"), CHILD);
        PomModel model = PomInheritance.effective(app.resolve("pom.xml"), dir);
        assertEquals(List.of(), model.inheritedFrom());
        assertEquals("multi", model.parent().artifactId(), "the <parent> element itself is kept");
        assertEquals(2, model.profiles().size());
    }
}
