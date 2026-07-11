package com.editora.ghactions;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure tests for the GitHub Actions workflow sniff + parse/describe. */
class WorkflowTest {

    private static final String CI = """
            name: CI
            on:
              push:
                branches: [main, dev]
              pull_request:
                branches: [main]
              schedule:
                - cron: '0 2 * * 1-5'
              workflow_dispatch:

            jobs:
              build:
                runs-on: ubuntu-latest
                steps:
                  - uses: actions/checkout@v4
                  - name: Install
                    run: npm ci
                  - run: npm test
              deploy:
                needs: build
                runs-on: ubuntu-latest
                if: github.ref == 'refs/heads/main'
                steps:
                  - uses: actions/deploy@v1
            """;

    @Test
    void detectsWorkflowByContent() {
        assertTrue(GithubActions.looksLikeWorkflow(CI));
        assertFalse(GithubActions.looksLikeWorkflow("name: just-data\nvalue: 3\n"));
        assertFalse(GithubActions.looksLikeWorkflow("# a comment\nkey: value\n"));
        assertFalse(GithubActions.looksLikeWorkflow(""));
    }

    @Test
    void parsesTriggersIncludingCron() {
        Workflow w = Workflow.parse(CI);
        assertTrue(w.ok());
        assertEquals("CI", w.name());
        List<String> t = w.triggers();
        assertTrue(t.contains("push to main, dev"), t.toString());
        assertTrue(t.contains("pull request to main"), t.toString());
        assertTrue(t.contains("manual dispatch"), t.toString());
        // The schedule's cron is decoded via CronExpression.
        assertTrue(
                t.stream().anyMatch(s -> s.startsWith("on a schedule") && s.contains("Monday through Friday")),
                t.toString());
    }

    @Test
    void parsesJobsAndSteps() {
        Workflow w = Workflow.parse(CI);
        assertEquals(2, w.jobs().size());

        Workflow.Job build = w.jobs().get(0);
        assertEquals("build", build.id());
        assertEquals("ubuntu-latest", build.runsOn());
        assertEquals(3, build.steps().size());
        assertEquals("checkout", build.steps().get(0).label());
        assertEquals("Install", build.steps().get(1).label());
        assertEquals("run: npm test", build.steps().get(2).label());

        Workflow.Job deploy = w.jobs().get(1);
        assertEquals(List.of("build"), deploy.needs());
        assertTrue(deploy.ifCond().contains("refs/heads/main"));
    }

    @Test
    void onKeyParsedAsBooleanStillWorks() {
        // Some YAML readers resolve the bare key `on` to boolean true; the parser checks both.
        Workflow w = Workflow.parse("on: [push]\njobs:\n  x:\n    runs-on: ubuntu-latest\n    steps: []\n");
        assertTrue(w.ok());
        assertEquals(List.of("push"), w.triggers());
    }

    @Test
    void shortActionName() {
        assertEquals("checkout", Workflow.shortAction("actions/checkout@v4"));
        assertEquals("setup-node", Workflow.shortAction("actions/setup-node@v4"));
        assertEquals("./local", Workflow.shortAction("./local"));
    }
}
