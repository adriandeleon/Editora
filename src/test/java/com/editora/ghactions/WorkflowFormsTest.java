package com.editora.ghactions;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The trigger, job and step shapes of a workflow file beyond the common one, and the files that are not one. */
class WorkflowFormsTest {

    @Test
    void aFileThatIsNotAMappingIsReportedNotThrown() {
        for (String text : new String[] {null, "", "just a string", "- a\n- b\n", "42"}) {
            Workflow w = Workflow.parse(text);
            assertFalse(w.ok(), String.valueOf(text));
            assertEquals("not a YAML mapping", w.error());
            assertNull(w.name());
            assertEquals(List.of(), w.triggers());
            assertEquals(List.of(), w.jobs());
        }
    }

    @Test
    void brokenYamlGivesTheParsersFirstLine() {
        Workflow w = Workflow.parse("name: [unclosed\njobs:\n  a: {");
        assertFalse(w.ok());
        assertNotNull(w.error());
        assertFalse(w.error().contains("\n"));
        assertFalse(w.error().isBlank());
        assertEquals(List.of(), w.jobs());
    }

    @Test
    void aSingleEventNameIsATrigger() {
        Workflow w = Workflow.parse("name: One\non: pull_request_target\n");
        assertTrue(w.ok());
        assertNull(w.error());
        assertEquals("One", w.name());
        assertEquals(List.of("pull request target"), w.triggers());
        assertEquals(List.of(), w.jobs());
    }

    @Test
    void aWorkflowWithoutTriggersOrJobsIsStillValid() {
        Workflow w = Workflow.parse("name: Empty\non:\njobs: none\n");
        assertTrue(w.ok());
        assertEquals(List.of(), w.triggers());
        assertEquals(List.of(), w.jobs());
        assertEquals(List.of(), Workflow.parse("name: NoOn\n").triggers());
        // A number is not a trigger list.
        assertEquals(List.of(), Workflow.parse("on: 5\n").triggers());
    }

    @Test
    void filtersAreSpelledOutPerEvent() {
        Workflow w = Workflow.parse("""
                on:
                  push:
                    branches: [main, 'release/**']
                    tags: 'v*'
                    paths: [src/**, pom.xml]
                  pull_request:
                    branches: main
                    tags: {}
                    paths: []
                  pull_request_target:
                  workflow_dispatch:
                  workflow_call:
                  release:
                    types: [published, edited]
                  issue_comment:
                """);
        assertEquals(
                List.of(
                        "push to main, release/** of tag v* touching src/**, pom.xml",
                        // A filter that is empty, or not a list of names, adds nothing.
                        "pull request to main",
                        "pull request",
                        "manual dispatch",
                        "called by another workflow",
                        "release (published, edited)",
                        "issue comment"),
                w.triggers());
        assertEquals(List.of("release"), Workflow.parse("on:\n  release:\n").triggers());
        assertEquals(
                List.of("release"),
                Workflow.parse("on:\n  release: published\n").triggers());
    }

    @Test
    void schedulesAreDecodedWhereTheyCanBe() {
        assertEquals(
                List.of("on a schedule (at 02:30, Monday through Friday; not a cron)"),
                Workflow.parse("on:\n  schedule:\n    - cron: '30 2 * * 1-5'\n    - cron: 'not a cron'\n")
                        .triggers());
        assertEquals(
                List.of("on a schedule"), Workflow.parse("on:\n  schedule:\n").triggers());
        assertEquals(
                List.of("on a schedule"),
                Workflow.parse("on:\n  schedule: []\n").triggers());
        assertEquals(
                List.of("on a schedule"),
                Workflow.parse("on:\n  schedule: daily\n").triggers());
        // Entries without a cron key are passed over.
        assertEquals(
                List.of("on a schedule"),
                Workflow.parse("on:\n  schedule:\n    - timezone: UTC\n").triggers());
    }

    @Test
    void jobsCarryRunnerNeedsConditionAndMatrix() {
        Workflow w = Workflow.parse("""
                on: push
                jobs:
                  build:
                    name: Build it
                    runs-on: [self-hosted, linux]
                    strategy:
                      matrix:
                        jdk: [25, 27]
                    steps: not-a-list
                  test:
                    runs-on:
                      group: big
                    needs: build
                    if: github.ref == 'refs/heads/main'
                  deploy:
                    needs: [build, test]
                    strategy: {fail-fast: false}
                  odd:
                    needs: {a: b}
                """);
        assertEquals(4, w.jobs().size());
        Workflow.Job build = w.jobs().get(0);
        assertEquals("build", build.id());
        assertEquals("Build it", build.name());
        assertEquals("self-hosted, linux", build.runsOn());
        assertTrue(build.matrix());
        assertEquals(List.of(), build.needs());
        assertEquals(List.of(), build.steps());
        assertNull(build.ifCond());

        Workflow.Job test = w.jobs().get(1);
        assertNull(test.name());
        assertEquals("{\"group\":\"big\"}", test.runsOn());
        assertEquals(List.of("build"), test.needs());
        assertEquals("github.ref == 'refs/heads/main'", test.ifCond());
        assertFalse(test.matrix());

        Workflow.Job deploy = w.jobs().get(2);
        assertNull(deploy.runsOn());
        assertEquals(List.of("build", "test"), deploy.needs());
        assertFalse(deploy.matrix());
        assertEquals(List.of(), w.jobs().get(3).needs());
    }

    @Test
    void aStepIsLabelledByNameThenActionThenCommand() {
        Workflow w = Workflow.parse("""
                on: push
                jobs:
                  a:
                    steps:
                      - name: Named
                        uses: actions/checkout@v4
                      - uses: actions/setup-java@v4
                      - name: '  '
                        uses: ./.github/actions/local
                      - uses: docker://alpine:3
                      - uses: plainaction
                      - run: |
                          echo first
                          echo second
                      - name: ''
                        uses: ' '
                        run: mvn -B verify
                      - {}
                """);
        List<String> labels =
                w.jobs().get(0).steps().stream().map(Workflow.Step::label).toList();
        assertEquals(
                List.of(
                        "Named",
                        "setup-java",
                        "./.github/actions/local",
                        "docker://alpine:3",
                        "plainaction",
                        "run: echo first",
                        "run: mvn -B verify",
                        "(empty step)"),
                labels);
    }

    @Test
    void aLongCommandIsCutAtEightyCharacters() {
        String cmd = "x".repeat(81);
        String label = new Workflow.Step(null, null, cmd).label();
        assertEquals("run: " + "x".repeat(77) + "…", label);
        assertEquals("run: " + "y".repeat(80), new Workflow.Step(null, "", "y".repeat(80)).label());
        assertEquals("(empty step)", new Workflow.Step(null, null, "  ").label());
        assertEquals("repo", Workflow.shortAction(" owner/repo "));
        assertEquals("sub", Workflow.shortAction("owner/repo/sub@abc123"));
    }
}
