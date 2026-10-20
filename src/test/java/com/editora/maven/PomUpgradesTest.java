package com.editora.maven;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Update Versions plan: which coordinates are looked up, what is skipped and why, and the rows the
 * answers become — literal versions, BOM imports, plugins, the parent, and version-only properties.
 */
class PomUpgradesTest {

    /** A Spring-Boot-style module: a parent, a BOM import, property-driven and inherited versions. */
    private static final String POM = """
            <project>
              <parent>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-starter-parent</artifactId>
                <version>3.3.0</version>
                <relativePath/>
              </parent>
              <artifactId>app</artifactId>
              <properties>
                <junit.version>5.10.0</junit.version>
                <surefire.version>3.2.5</surefire.version>
                <guava.version>33.0.0-jre</guava.version>
                <app.name>demo</app.name>
              </properties>
              <dependencyManagement>
                <dependencies>
                  <dependency>
                    <groupId>org.junit</groupId>
                    <artifactId>junit-bom</artifactId>
                    <version>5.10.0</version>
                    <type>pom</type>
                    <scope>import</scope>
                  </dependency>
                </dependencies>
              </dependencyManagement>
              <dependencies>
                <dependency>
                  <groupId>org.junit.jupiter</groupId>
                  <artifactId>junit-jupiter-api</artifactId>
                  <version>${junit.version}</version>
                </dependency>
                <dependency>
                  <groupId>org.junit.jupiter</groupId>
                  <artifactId>junit-jupiter-engine</artifactId>
                  <version>${junit.version}</version>
                </dependency>
                <dependency>
                  <groupId>com.google.guava</groupId>
                  <artifactId>guava</artifactId>
                  <version>${guava.version}</version>
                </dependency>
                <dependency>
                  <groupId>org.springframework.boot</groupId>
                  <artifactId>spring-boot-starter</artifactId>
                </dependency>
                <dependency>
                  <groupId>org.apache.commons</groupId>
                  <artifactId>commons-lang3</artifactId>
                  <version>3.14.0</version>
                </dependency>
                <dependency>
                  <groupId>org.example</groupId>
                  <artifactId>ranged</artifactId>
                  <version>[1.0,2.0)</version>
                </dependency>
              </dependencies>
              <build>
                <plugins>
                  <plugin>
                    <artifactId>maven-surefire-plugin</artifactId>
                    <version>${surefire.version}</version>
                    <configuration>
                      <argLine>-Dsurefire=${surefire.version}</argLine>
                    </configuration>
                  </plugin>
                  <plugin>
                    <artifactId>maven-compiler-plugin</artifactId>
                    <version>3.11.0</version>
                  </plugin>
                  <plugin>
                    <artifactId>maven-jar-plugin</artifactId>
                  </plugin>
                </plugins>
              </build>
            </project>
            """;

    @Test
    void thePlanLooksUpLiteralsBomsPluginsTheParentAndVersionOnlyProperties() {
        PomUpgrades.Plan plan = PomUpgrades.plan(POM);
        assertEquals(
                List.of(
                        "org.junit:junit-bom",
                        "org.junit.jupiter:junit-jupiter-api",
                        "org.junit.jupiter:junit-jupiter-engine",
                        "com.google.guava:guava",
                        "org.apache.commons:commons-lang3",
                        "org.apache.maven.plugins:maven-compiler-plugin",
                        "org.springframework.boot:spring-boot-starter-parent"),
                plan.coordinates());
        assertEquals(java.util.Set.of("org.junit:junit-bom"), plan.boms());
        assertEquals(java.util.Set.of("org.apache.maven.plugins:maven-compiler-plugin"), plan.plugins());
        assertEquals(
                Map.of(
                        "junit.version",
                        List.of("org.junit.jupiter:junit-jupiter-api", "org.junit.jupiter:junit-jupiter-engine"),
                        "guava.version",
                        List.of("com.google.guava:guava")),
                plan.propertyUsers());
        assertEquals("5.10.0", plan.propertyValues().get("junit.version"));
        assertTrue(plan.hasParent());
        assertEquals("3.3.0", plan.parent()[2]);
        // ${surefire.version} is also used in <configuration>, so rewriting the property would change more than
        // a version; the ranged dependency is not a plain version. Both are "skipped", and counted.
        assertEquals(1, plan.skippedPropertyDriven(), "surefire's property is not version-only");
        // spring-boot-starter and maven-jar-plugin have no version of their own.
        assertEquals(2, plan.skippedInherited());
        assertFalse(plan.coordinates().contains("org.example:ranged"), "a range is never looked up");
    }

    @Test
    void rowsAreGroupedFlaggedAndOnlyRealUpgrades() {
        PomUpgrades.Plan plan = PomUpgrades.plan(POM);
        Map<String, String> latest = Map.of(
                "org.junit:junit-bom", "6.1.3",
                "org.junit.jupiter:junit-jupiter-api", "5.13.0",
                "org.junit.jupiter:junit-jupiter-engine",
                        "5.12.0", // the engine lags: the property can only go to 5.12.0
                "com.google.guava:guava", "r09", // the alphabetic trap: never an upgrade
                "org.apache.commons:commons-lang3", "3.14.0", // already current
                "org.apache.maven.plugins:maven-compiler-plugin", "3.14.0",
                "org.springframework.boot:spring-boot-starter-parent", "4.1.1");
        List<PomUpgrades.Row> rows = PomUpgrades.rows(plan, latest);
        assertEquals(
                List.of(
                        new PomUpgrades.Row(PomUpgrades.Kind.BOM, "org.junit:junit-bom", "5.10.0", "6.1.3", true),
                        new PomUpgrades.Row(
                                PomUpgrades.Kind.PLUGIN,
                                "org.apache.maven.plugins:maven-compiler-plugin",
                                "3.11.0",
                                "3.14.0",
                                false),
                        new PomUpgrades.Row(
                                PomUpgrades.Kind.PARENT,
                                "org.springframework.boot:spring-boot-starter-parent",
                                "3.3.0",
                                "4.1.1",
                                true),
                        new PomUpgrades.Row(PomUpgrades.Kind.PROPERTY, "junit.version", "5.10.0", "5.12.0", false)),
                rows);
    }

    @Test
    void aPropertyIsNotOfferedUnlessEveryUserAnswered() {
        PomUpgrades.Plan plan = PomUpgrades.plan(POM);
        List<PomUpgrades.Row> rows = PomUpgrades.rows(plan, Map.of("org.junit.jupiter:junit-jupiter-api", "5.13.0"));
        assertTrue(rows.isEmpty(), "the engine did not answer, so ${junit.version} cannot be moved: " + rows);
    }

    @Test
    void applyWritesEveryKindOfRowAndNothingElse() {
        String out = PomUpgrades.apply(
                POM,
                List.of(
                        new PomUpgrades.Row(PomUpgrades.Kind.BOM, "org.junit:junit-bom", "5.10.0", "6.1.3", true),
                        new PomUpgrades.Row(
                                PomUpgrades.Kind.PLUGIN,
                                "org.apache.maven.plugins:maven-compiler-plugin",
                                "3.11.0",
                                "3.14.0",
                                false),
                        new PomUpgrades.Row(
                                PomUpgrades.Kind.PARENT,
                                "org.springframework.boot:spring-boot-starter-parent",
                                "3.3.0",
                                "4.1.1",
                                true),
                        new PomUpgrades.Row(PomUpgrades.Kind.PROPERTY, "junit.version", "5.10.0", "5.12.0", false)));
        assertTrue(out.contains("<artifactId>junit-bom</artifactId>\n        <version>6.1.3</version>"), out);
        assertTrue(
                out.contains("<artifactId>maven-compiler-plugin</artifactId>\n        <version>3.14.0</version>"), out);
        assertTrue(
                out.contains("<artifactId>spring-boot-starter-parent</artifactId>\n    <version>4.1.1</version>"), out);
        assertTrue(out.contains("<junit.version>5.12.0</junit.version>"), out);
        assertTrue(out.contains("<version>${junit.version}</version>"), "the references stay references");
        assertTrue(out.contains("<surefire.version>3.2.5</surefire.version>"), "untouched");
        assertTrue(out.contains("<version>3.14.0</version>\n    </dependency>"), "commons-lang3 untouched");
        assertEquals(POM, PomUpgrades.apply(POM, List.of()));
    }

    @Test
    void majorMeansTheLeadingNumberChanges() {
        assertTrue(PomUpgrades.isMajor("5.11.0", "6.1.3"));
        assertTrue(PomUpgrades.isMajor("3.3.0", "4.0.1"));
        assertFalse(PomUpgrades.isMajor("3.11.0", "3.14.0"));
        assertFalse(PomUpgrades.isMajor("r08", "33.0.0-jre"), "no leading number on one side: not called major");
    }

    @Test
    void aPomWithNothingToLookUpSaysWhyWhenItCan() {
        PomUpgrades.Plan bare = PomUpgrades.plan("<project><artifactId>bare</artifactId></project>");
        assertFalse(bare.hasLookups());
        assertEquals(0, bare.skipped());
        assertNull(bare.parent());

        PomUpgrades.Plan module = PomUpgrades.plan("""
                <project>
                  <parent><groupId>g</groupId><artifactId>parent</artifactId><version>${revision}</version></parent>
                  <artifactId>core</artifactId>
                  <dependencies>
                    <dependency><groupId>com.google.guava</groupId><artifactId>guava</artifactId></dependency>
                    <dependency><groupId>g</groupId><artifactId>x</artifactId><version>${x.version}</version></dependency>
                  </dependencies>
                </project>
                """);
        assertFalse(module.hasLookups(), "a ${revision} parent is not looked up");
        assertEquals(1, module.skippedInherited());
        assertEquals(1, module.skippedPropertyDriven(), "an undeclared property cannot be offered");
    }
}
