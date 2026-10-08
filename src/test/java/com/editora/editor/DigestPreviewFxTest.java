package com.editora.editor;

import javafx.scene.Node;

import com.editora.ghactions.Workflow;
import com.editora.i18n.Messages;
import com.editora.maven.PomSummary;
import com.editora.structured.StructuredParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the pom, OpenAPI and GitHub Actions digests say about a full document, and what they fall back to
 * for the fields a sparse one leaves out.
 */
@Tag("fx")
class DigestPreviewFxTest {

    @BeforeAll
    static void boot() throws Exception {
        EditorFx.boot();
    }

    private static String tr(String key, Object... args) {
        return Messages.tr(key, args);
    }

    private static void assertShows(String text, String expected) {
        assertTrue(text.contains(expected), "expected \"" + expected + "\" in:\n" + text);
    }

    private static final String FULL_POM = """
            <project>
              <modelVersion>4.0.0</modelVersion>
              <parent>
                <groupId>org.example</groupId>
                <artifactId>platform</artifactId>
                <version>7</version>
              </parent>
              <groupId>org.example.orders</groupId>
              <artifactId>orders</artifactId>
              <version>1.4.0</version>
              <packaging>pom</packaging>
              <name>Orders Service</name>
              <description>Takes and ships orders.</description>
              <modules>
                <module>api</module>
                <module>worker</module>
              </modules>
              <properties>
                <jackson.version>2.17.0</jackson.version>
              </properties>
              <dependencyManagement>
                <dependencies>
                  <dependency>
                    <groupId>org.slf4j</groupId>
                    <artifactId>slf4j-api</artifactId>
                    <version>2.0.13</version>
                  </dependency>
                </dependencies>
              </dependencyManagement>
              <dependencies>
                <dependency>
                  <groupId>com.fasterxml.jackson.core</groupId>
                  <artifactId>jackson-databind</artifactId>
                  <version>${jackson.version}</version>
                </dependency>
                <dependency>
                  <groupId>org.slf4j</groupId>
                  <artifactId>slf4j-api</artifactId>
                </dependency>
                <dependency>
                  <groupId>org.junit.jupiter</groupId>
                  <artifactId>junit-jupiter</artifactId>
                  <version>5.11.0</version>
                  <scope>test</scope>
                  <type>pom</type>
                  <classifier>tests</classifier>
                  <optional>true</optional>
                </dependency>
                <dependency>
                  <groupId>org.example</groupId>
                  <artifactId>unversioned</artifactId>
                </dependency>
              </dependencies>
              <build>
                <plugins>
                  <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-surefire-plugin</artifactId>
                    <version>3.2.5</version>
                    <executions>
                      <execution>
                        <goals>
                          <goal>test</goal>
                          <goal>report</goal>
                        </goals>
                      </execution>
                    </executions>
                  </plugin>
                </plugins>
              </build>
              <profiles>
                <profile>
                  <id>release</id>
                  <activation>
                    <activeByDefault>true</activeByDefault>
                  </activation>
                  <properties>
                    <skipTests>true</skipTests>
                  </properties>
                  <dependencies>
                    <dependency>
                      <groupId>org.example</groupId>
                      <artifactId>signing</artifactId>
                      <version>1.0</version>
                    </dependency>
                  </dependencies>
                </profile>
                <profile>
                  <id>empty</id>
                </profile>
                <profile>
                </profile>
              </profiles>
            </project>
            """;

    @Test
    void aFullPomListsItsCoordinatesModulesDependenciesPluginsAndProfiles() throws Exception {
        EditorFx.onFx(() -> {
            PomSummary summary = PomSummary.parse(FULL_POM);
            Node node = PomPreview.build(summary);
            String text = EditorFx.textOf(node);
            assertShows(text, "orders");
            assertShows(text, "Orders Service");
            assertShows(text, "Takes and ships orders.");
            assertShows(text, tr("pom.parent", "org.example:platform:7"));
            assertShows(text, "org.example.orders:orders:1.4.0 · pom");
            assertShows(text, tr("pom.modules", 2));
            assertShows(text, "worker");
            assertShows(text, "jackson.version");
            assertShows(text, "jackson-databind");
            assertShows(text, "2.17.0");
            assertShows(text, "${jackson.version}");
            assertShows(text, tr("pom.managed"));
            assertShows(text, tr("pom.inherited"));
            assertShows(text, tr("pom.optional"));
            assertShows(text, "test");
            assertShows(text, "tests");
            assertShows(text, tr("pom.goals", "test, report"));
            assertShows(text, tr("pom.profileActive", "release"));
            assertShows(text, tr("pom.profile", "empty"));
            assertShows(text, tr("pom.profile", tr("pom.untitled")));
            assertShows(text, tr("pom.nothingDeclared"));
            assertShows(text, "signing");
            assertFalse(summary.isEmpty());
        });
    }

    @Test
    void aSparsePomFallsBackToPlaceholders() throws Exception {
        EditorFx.onFx(() -> {
            String bare = EditorFx.textOf(PomPreview.build(PomSummary.parse("<project/>")));
            assertShows(bare, tr("pom.untitled"));
            assertFalse(bare.contains(tr("pom.modules", 0)), "no empty sections");

            Node named = PomPreview.build(PomSummary.parse(
                    "<project><artifactId>tool</artifactId><name>Tool</name><packaging>jar</packaging></project>"));
            assertShows(EditorFx.textOf(named), "tool");
            assertNull(
                    EditorFx.styled(named, "pom-name-line"),
                    "a name that only repeats the artifactId is not printed a second time");
            assertNull(EditorFx.styled(named, "pom-parent"));
            Node full = PomPreview.build(PomSummary.parse(FULL_POM));
            assertNotNull(EditorFx.styled(full, "pom-name-line"));
            assertNotNull(EditorFx.styled(full, "pom-parent"));
        });
    }

    private static final String FULL_API = """
            {
              "openapi": "3.0.3",
              "info": {"title": "Orders API", "version": "2.1", "description": "Everything about orders."},
              "servers": [{"url": "https://api.example.org/v2"}],
              "paths": {
                "/orders/{id}": {
                  "get": {
                    "summary": "Fetch one order",
                    "deprecated": true,
                    "parameters": [
                      {"name": "id", "in": "path", "required": true, "schema": {"type": "string"}},
                      {"name": "expand", "in": "query", "schema": {"type": "boolean"}}
                    ],
                    "responses": {
                      "200": {"description": "The order"},
                      "404": {}
                    }
                  },
                  "delete": {"responses": {}}
                }
              },
              "components": {
                "schemas": {
                  "Order": {
                    "type": "object",
                    "required": ["id"],
                    "properties": {"id": {"type": "string"}, "note": {"type": "string"}}
                  }
                }
              }
            }
            """;

    @Test
    void anOpenApiDocumentIsShownAsEndpointsParametersResponsesAndSchemas() throws Exception {
        EditorFx.onFx(() -> {
            StructuredParser.Parsed parsed =
                    StructuredParser.parse(FULL_API, StructuredParser.Format.forLanguage("json"));
            assertTrue(parsed.isOpenApi(), String.valueOf(parsed.error()));
            String text = EditorFx.textOf(OpenApiDoc.build(parsed.openApi()));
            assertShows(text, "Orders API  2.1");
            assertShows(text, "Everything about orders.");
            assertShows(text, tr("openapi.servers"));
            assertShows(text, "https://api.example.org/v2");
            assertShows(text, "/orders/{id}");
            assertShows(text, "Fetch one order");
            assertShows(text, tr("openapi.deprecated"));
            assertShows(text, tr("openapi.parameters"));
            assertShows(text, "id (path) : string  " + tr("openapi.required"));
            assertShows(text, "expand (query) : boolean\n");
            assertShows(text, "200 — The order");
            assertShows(text, "404\n");
            assertShows(text, tr("openapi.schemas"));
            assertShows(text, "Order  : object");
            assertShows(text, "id : string  " + tr("openapi.required"));
            assertShows(text, "note : string\n");
        });
    }

    @Test
    void anOpenApiDocumentWithNoInfoStillGetsATitle() throws Exception {
        EditorFx.onFx(() -> {
            StructuredParser.Parsed parsed = StructuredParser.parse(
                    "{\"openapi\": \"3.1.0\", \"paths\": {}}", StructuredParser.Format.forLanguage("json"));
            assertTrue(parsed.isOpenApi());
            String text = EditorFx.textOf(OpenApiDoc.build(parsed.openApi()));
            assertEquals(tr("openapi.untitled"), text.lines().findFirst().orElseThrow());
            assertFalse(text.contains(tr("openapi.servers")));
            assertFalse(text.contains(tr("openapi.schemas")));
        });
    }

    @Test
    void aWorkflowShowsItsTriggersAndEachJobsRunnerNeedsMatrixAndCondition() throws Exception {
        EditorFx.onFx(() -> {
            Workflow workflow = Workflow.parse("""
                    name: Release
                    on: [push, workflow_dispatch]
                    jobs:
                      build:
                        name: Build and test
                        runs-on: ubuntu-latest
                        strategy:
                          matrix:
                            java: [21, 25]
                        steps:
                          - uses: actions/checkout@v4
                          - name: Verify
                            run: ./mvnw verify
                      publish:
                        needs: [build]
                        if: github.ref == 'refs/heads/main'
                        runs-on: macos-15
                        steps:
                          - run: ./publish.sh
                      reusable:
                        needs: build
                        uses: ./.github/workflows/other.yml
                    """);
            assertTrue(workflow.ok(), String.valueOf(workflow.error()));
            String text = EditorFx.textOf(GithubActionsPreview.build(workflow));
            assertShows(text, "Release");
            assertShows(text, tr("ghactions.triggeredBy", "push, workflow dispatch"));
            assertShows(text, tr("ghactions.jobs", 3));
            assertShows(text, "Build and test");
            assertShows(text, tr("ghactions.runsOn", "ubuntu-latest") + " · " + tr("ghactions.matrix"));
            assertShows(text, "2. Verify");
            assertShows(text, "publish");
            assertShows(text, tr("ghactions.runsOn", "macos-15") + " · " + tr("ghactions.needs", "build"));
            assertShows(text, tr("ghactions.onlyIf", "github.ref == 'refs/heads/main'"));
            assertShows(text, "reusable");
            assertShows(text, tr("ghactions.needs", "build") + "\n");
        });
    }

    @Test
    void aWorkflowThatDoesNotParseOrHasNoTriggersSaysSo() throws Exception {
        EditorFx.onFx(() -> {
            Workflow broken = Workflow.parse("name: [unclosed\njobs: {");
            assertFalse(broken.ok());
            Node node = GithubActionsPreview.build(broken);
            assertNotNull(EditorFx.styled(node, "ghactions-error"));
            String text = EditorFx.textOf(node);
            assertShows(text, tr("ghactions.untitled"));
            assertShows(text, tr("ghactions.invalid", broken.error()));

            Workflow bare = Workflow.parse("jobs:\n  lint:\n    steps: []\n");
            assertTrue(bare.ok(), String.valueOf(bare.error()));
            String bareText = EditorFx.textOf(GithubActionsPreview.build(bare));
            assertShows(bareText, tr("ghactions.untitled"));
            assertShows(bareText, tr("ghactions.triggeredBy", tr("ghactions.noTriggers")));
            assertShows(bareText, "lint");
            assertFalse(bareText.contains(tr("ghactions.runsOn", "")), "a job with no runner has no meta line");
        });
    }
}
