package com.editora.maven;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PomSummaryTest {

    private static final String FULL = """
            <project>
              <parent>
                <groupId>com.example</groupId>
                <artifactId>parent</artifactId>
                <version>2.1.0</version>
              </parent>
              <artifactId>demo</artifactId>
              <name>Demo App</name>
              <description>A demo
                  application.</description>
              <properties>
                <java.version>25</java.version>
                <junit.version>5.10.2</junit.version>
              </properties>
              <dependencyManagement>
                <dependencies>
                  <dependency>
                    <groupId>org.slf4j</groupId>
                    <artifactId>slf4j-api</artifactId>
                    <version>2.0.17</version>
                  </dependency>
                </dependencies>
              </dependencyManagement>
              <dependencies>
                <dependency>
                  <groupId>org.junit.jupiter</groupId>
                  <artifactId>junit-jupiter</artifactId>
                  <version>${junit.version}</version>
                  <scope>test</scope>
                </dependency>
                <dependency>
                  <groupId>org.slf4j</groupId>
                  <artifactId>slf4j-api</artifactId>
                </dependency>
                <dependency>
                  <groupId>com.example</groupId>
                  <artifactId>sibling</artifactId>
                  <optional>true</optional>
                </dependency>
              </dependencies>
              <build>
                <plugins>
                  <plugin>
                    <artifactId>maven-surefire-plugin</artifactId>
                    <version>3.2.5</version>
                    <executions>
                      <execution><goals><goal>test</goal></goals></execution>
                      <execution><goals><goal>test</goal><goal>verify</goal></goals></execution>
                    </executions>
                  </plugin>
                </plugins>
              </build>
              <profiles>
                <profile>
                  <id>dist</id>
                  <activation><activeByDefault>true</activeByDefault></activation>
                  <dependencies>
                    <dependency>
                      <groupId>org.example</groupId>
                      <artifactId>extra</artifactId>
                      <version>1.2.3</version>
                    </dependency>
                  </dependencies>
                </profile>
              </profiles>
            </project>
            """;

    @Test
    void readsCoordinatesInheritingGroupIdAndVersionFromTheParent() {
        PomSummary s = PomSummary.parse(FULL);
        assertTrue(s.ok());
        assertEquals("com.example", s.coordinates().groupId());
        assertEquals("demo", s.coordinates().artifactId());
        assertEquals("2.1.0", s.coordinates().version());
        assertEquals("jar", s.packaging()); // Maven's default when <packaging> is absent
        assertNotNull(s.parent());
        assertEquals("parent", s.parent().artifactId());
    }

    @Test
    void collapsesAWrappedDescriptionToOneLine() {
        assertEquals("A demo application.", PomSummary.parse(FULL).description());
    }

    @Test
    void keepsPropertiesInDocumentOrder() {
        List<PomSummary.Property> props = PomSummary.parse(FULL).properties();
        assertEquals(
                List.of("java.version", "junit.version"),
                props.stream().map(PomSummary.Property::name).toList());
        assertEquals("5.10.2", props.get(1).value());
    }

    @Test
    void resolvesAPropertyVersionWhileKeepingTheReferenceAsWritten() {
        PomSummary.Dependency junit = dependency(PomSummary.parse(FULL), "junit-jupiter");
        assertEquals("${junit.version}", junit.version());
        assertEquals("5.10.2", junit.effectiveVersion());
        assertFalse(junit.managed());
        assertEquals("test", junit.scope());
    }

    @Test
    void fillsABlankVersionFromDependencyManagementAndFlagsItManaged() {
        PomSummary.Dependency slf4j = dependency(PomSummary.parse(FULL), "slf4j-api");
        assertEquals("", slf4j.version());
        assertEquals("2.0.17", slf4j.effectiveVersion());
        assertTrue(slf4j.managed());
    }

    @Test
    void leavesAnUnresolvableVersionEmptyRatherThanGuessing() {
        // Nothing in this file supplies it — it would come from the parent pom, which is not read here.
        PomSummary.Dependency sibling = dependency(PomSummary.parse(FULL), "sibling");
        assertEquals("", sibling.effectiveVersion());
        assertFalse(sibling.managed());
        assertTrue(sibling.optional());
    }

    @Test
    void appliesMavensDefaultPluginGroupAndDeduplicatesGoals() {
        PomSummary.Plugin plugin = PomSummary.parse(FULL).plugins().get(0);
        assertEquals("org.apache.maven.plugins", plugin.groupId());
        assertEquals("3.2.5", plugin.effectiveVersion());
        assertEquals(List.of("test", "verify"), plugin.goals());
    }

    @Test
    void readsAProfilesOwnDependenciesWithoutMergingThemIntoTheTopLevel() {
        PomSummary s = PomSummary.parse(FULL);
        assertEquals(3, s.dependencies().size()); // the profile's is not one of them
        PomSummary.Profile dist = s.profiles().get(0);
        assertEquals("dist", dist.id());
        assertTrue(dist.activeByDefault());
        assertEquals("extra", dist.dependencies().get(0).artifactId());
        assertEquals(4, s.totalDependencies());
    }

    @Test
    void resolvesTheProjectVersionBuiltIn() {
        PomSummary s = PomSummary.parse("""
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>demo</artifactId>
                  <version>9.9.9</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.example</groupId>
                      <artifactId>core</artifactId>
                      <version>${project.version}</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
        assertEquals("9.9.9", dependency(s, "core").effectiveVersion());
    }

    @Test
    void resolvesAPropertyDefinedInTermsOfAnother() {
        assertEquals("1.2.3", PomSummary.resolve("${a}", Map.of("a", "${b}", "b", "1.2.3")));
    }

    @Test
    void leavesAnUnknownPropertyAsWritten() {
        assertEquals("${nope}", PomSummary.resolve("${nope}", Map.of("a", "1")));
        assertEquals("1-${nope}", PomSummary.resolve("${a}-${nope}", Map.of("a", "1")));
    }

    @Test
    void terminatesOnAPropertyCycle() {
        assertEquals("${a}", PomSummary.resolve("${a}", Map.of("a", "${b}", "b", "${a}")));
    }

    @Test
    void aVersionThatStaysAPlaceholderIsReportedAsUnresolved() {
        PomSummary s = PomSummary.parse("""
                <project>
                  <artifactId>demo</artifactId>
                  <dependencies>
                    <dependency>
                      <groupId>g</groupId><artifactId>a</artifactId><version>${missing.version}</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
        PomSummary.Dependency d = dependency(s, "a");
        assertEquals("${missing.version}", d.version()); // shown as written…
        assertEquals("", d.effectiveVersion()); // …but never claimed as a real version
    }

    @Test
    void reportsAMalformedDocumentInsteadOfThrowing() {
        PomSummary s = PomSummary.parse("<project><artifactId>oops</project>");
        assertFalse(s.ok());
        assertNotNull(s.error());
        assertTrue(s.dependencies().isEmpty());
    }

    @Test
    void reportsANonPomXmlDocument() {
        PomSummary s = PomSummary.parse("<settings><localRepository>/tmp</localRepository></settings>");
        assertFalse(s.ok());
        assertTrue(s.error().contains("project"));
    }

    @Test
    void reportsAnEmptyDocument() {
        assertFalse(PomSummary.parse("  ").ok());
        assertFalse(PomSummary.parse(null).ok());
    }

    @Test
    void refusesADoctypeLikeThePomParser() {
        // XXE hardening: a DOCTYPE is rejected outright rather than expanded.
        PomSummary s = PomSummary.parse("""
                <!DOCTYPE project [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <project><artifactId>demo</artifactId></project>
                """);
        assertFalse(s.ok());
    }

    @Test
    void aMinimalPomHasNothingToList() {
        PomSummary s = PomSummary.parse("<project><artifactId>demo</artifactId></project>");
        assertTrue(s.ok());
        assertTrue(s.isEmpty());
        assertNull(s.parent());
        assertEquals(0, s.totalDependencies());
    }

    @Test
    void readsAnAggregatorsModules() {
        PomSummary s = PomSummary.parse("""
                <project>
                  <artifactId>root</artifactId>
                  <packaging>pom</packaging>
                  <modules><module>core</module><module>ui</module></modules>
                </project>
                """);
        assertEquals(List.of("core", "ui"), s.modules());
        assertEquals("pom", s.packaging());
        assertFalse(s.isEmpty());
    }

    @Test
    void skipsADependencyWithNoArtifactIdRatherThanFailingThePreview() {
        PomSummary s = PomSummary.parse("""
                <project>
                  <artifactId>demo</artifactId>
                  <dependencies>
                    <dependency><groupId>g</groupId></dependency>
                    <dependency><groupId>g</groupId><artifactId>good</artifactId><version>1</version></dependency>
                  </dependencies>
                </project>
                """);
        assertTrue(s.ok());
        assertEquals(1, s.dependencies().size());
        assertEquals("good", s.dependencies().get(0).artifactId());
    }

    private static PomSummary.Dependency dependency(PomSummary s, String artifactId) {
        return s.dependencies().stream()
                .filter(d -> d.artifactId().equals(artifactId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no dependency " + artifactId));
    }
}
