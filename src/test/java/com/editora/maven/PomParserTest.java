package com.editora.maven;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PomParserTest {

    private static final String MINIMAL = """
            <project>
              <groupId>com.example</groupId>
              <artifactId>demo</artifactId>
              <version>1.0.0</version>
              <packaging>jar</packaging>
            </project>
            """;

    @Test
    void parsesGroupArtifactVersionPackaging() throws Exception {
        PomModel model = PomParser.parse(MINIMAL);
        assertEquals("com.example", model.groupId());
        assertEquals("demo", model.artifactId());
        assertEquals("1.0.0", model.version());
        assertEquals("jar", model.packaging());
    }

    @Test
    void defaultsPackagingToJarWhenAbsent() throws Exception {
        String xml = """
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>demo</artifactId>
                  <version>1.0.0</version>
                </project>
                """;
        assertEquals("jar", PomParser.parse(xml).packaging());
    }

    @Test
    void fallsBackToParentGroupIdAndVersion() throws Exception {
        String xml = """
                <project>
                  <parent>
                    <groupId>com.example</groupId>
                    <artifactId>parent</artifactId>
                    <version>2.0.0</version>
                  </parent>
                  <artifactId>child</artifactId>
                </project>
                """;
        PomModel model = PomParser.parse(xml);
        assertEquals("com.example", model.groupId());
        assertEquals("2.0.0", model.version());
    }

    @Test
    void malformedXmlThrows() {
        assertThrows(PomParseException.class, () -> PomParser.parse("<project><groupId>oops"));
    }

    @Test
    void missingArtifactIdThrows() {
        String xml = """
                <project>
                  <groupId>com.example</groupId>
                  <version>1.0.0</version>
                </project>
                """;
        assertThrows(PomParseException.class, () -> PomParser.parse(xml));
    }

    @Test
    void emptyProjectHasEmptyListsNotNull() throws Exception {
        PomModel model = PomParser.parse(MINIMAL);
        assertTrue(model.plugins().isEmpty());
        assertTrue(model.profiles().isEmpty());
    }

    @Test
    void parsesExecutionsWithIdPhaseAndMultipleGoals() throws Exception {
        String xml = """
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>demo</artifactId>
                  <version>1.0.0</version>
                  <build>
                    <plugins>
                      <plugin>
                        <groupId>org.apache.maven.plugins</groupId>
                        <artifactId>maven-compiler-plugin</artifactId>
                        <version>3.13.0</version>
                        <executions>
                          <execution>
                            <id>default-compile</id>
                            <phase>compile</phase>
                            <goals>
                              <goal>compile</goal>
                              <goal>testCompile</goal>
                            </goals>
                          </execution>
                        </executions>
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """;
        PomModel model = PomParser.parse(xml);
        assertEquals(1, model.plugins().size());
        PomModel.Plugin plugin = model.plugins().get(0);
        assertEquals("maven-compiler-plugin", plugin.artifactId());
        assertEquals(1, plugin.executions().size());
        PomModel.Execution exec = plugin.executions().get(0);
        assertEquals("default-compile", exec.id());
        assertEquals("compile", exec.phase());
        assertEquals(List.of("compile", "testCompile"), exec.goals());
    }

    @Test
    void defaultsExecutionIdWhenAbsent() throws Exception {
        String xml = """
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>demo</artifactId>
                  <version>1.0.0</version>
                  <build>
                    <plugins>
                      <plugin>
                        <artifactId>spotless-maven-plugin</artifactId>
                        <executions>
                          <execution>
                            <goals><goal>check</goal></goals>
                          </execution>
                        </executions>
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """;
        PomModel model = PomParser.parse(xml);
        assertEquals("default", model.plugins().get(0).executions().get(0).id());
        assertEquals("org.apache.maven.plugins", model.plugins().get(0).groupId());
    }

    @Test
    void parsesProfileActiveByDefault() throws Exception {
        String xml = """
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>demo</artifactId>
                  <version>1.0.0</version>
                  <profiles>
                    <profile>
                      <id>dev</id>
                      <activation><activeByDefault>true</activeByDefault></activation>
                    </profile>
                    <profile>
                      <id>prod</id>
                    </profile>
                  </profiles>
                </project>
                """;
        PomModel model = PomParser.parse(xml);
        assertEquals(2, model.profiles().size());
        assertTrue(model.profiles().get(0).activeByDefault());
        assertFalse(model.profiles().get(1).activeByDefault());
    }

    @Test
    void parsesProfileScopedPlugins() throws Exception {
        String xml = """
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>demo</artifactId>
                  <version>1.0.0</version>
                  <profiles>
                    <profile>
                      <id>dist</id>
                      <build>
                        <plugins>
                          <plugin>
                            <groupId>org.codehaus.mojo</groupId>
                            <artifactId>exec-maven-plugin</artifactId>
                            <executions>
                              <execution>
                                <id>run-it</id>
                                <phase>package</phase>
                                <goals><goal>exec</goal></goals>
                              </execution>
                            </executions>
                          </plugin>
                        </plugins>
                      </build>
                    </profile>
                  </profiles>
                </project>
                """;
        PomModel model = PomParser.parse(xml);
        assertTrue(model.plugins().isEmpty()); // not merged into the top-level list
        PomModel.Profile dist = model.profiles().get(0);
        assertEquals(1, dist.plugins().size());
        assertEquals("exec-maven-plugin", dist.plugins().get(0).artifactId());
        assertEquals("exec", dist.plugins().get(0).executions().get(0).goals().get(0));
    }

    @Test
    void xxeDoctypeIsRejectedNotExpanded() {
        String xml = """
                <?xml version="1.0"?>
                <!DOCTYPE project [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <project>
                  <groupId>&xxe;</groupId>
                  <artifactId>demo</artifactId>
                  <version>1.0.0</version>
                </project>
                """;
        // DOCTYPE is disallowed outright, so this must fail to parse rather than expand the entity into
        // any field — the error message must never contain expanded /etc/passwd content.
        PomParseException ex = assertThrows(PomParseException.class, () -> PomParser.parse(xml));
        assertFalse(ex.getMessage() != null && ex.getMessage().contains("root:"));
    }

    @Test
    void parsesThisRepositorysOwnPomFile() throws Exception {
        Path pom = Path.of(System.getProperty("user.dir"), "pom.xml");
        PomModel model = PomParser.parseFile(pom);
        assertEquals("editora", model.artifactId());

        Set<String> topLevelPrefixes = model.plugins().stream()
                .map(p -> MavenPluginPrefix.derive(p.groupId(), p.artifactId()))
                .collect(Collectors.toSet());
        assertTrue(topLevelPrefixes.containsAll(Set.of("compiler", "surefire", "jacoco", "spotless", "moditect")));

        Set<String> profileIds =
                model.profiles().stream().map(PomModel.Profile::id).collect(Collectors.toSet());
        assertTrue(profileIds.containsAll(Set.of("apidocs", "os-mac", "os-windows", "os-linux", "dist", "fatjar")));
        assertTrue(model.profiles().stream().noneMatch(PomModel.Profile::activeByDefault));

        // Profile-scoped plugins are parsed under their own profile, not merged into the top level.
        PomModel.Profile dist = model.profiles().stream()
                .filter(p -> "dist".equals(p.id()))
                .findFirst()
                .orElseThrow();
        Set<String> distPrefixes = dist.plugins().stream()
                .map(p -> MavenPluginPrefix.derive(p.groupId(), p.artifactId()))
                .collect(Collectors.toSet());
        assertTrue(distPrefixes.contains("exec"));
    }
}
