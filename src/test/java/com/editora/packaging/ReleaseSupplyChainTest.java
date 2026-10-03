package com.editora.packaging;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the trust boundaries of the CI and release workflows.
 *
 * <p>None of this is exercised by a local build, and a regression is silent: a workflow with a floating
 * action tag, a write-scoped token on a build leg, or an unverified download runs exactly as well as a
 * correct one — until the day it matters. The checks are textual on purpose (the workflows are YAML that no
 * dependency of this project parses), and each message says what the rule protects.
 */
class ReleaseSupplyChainTest {

    private static final Path REPO = Path.of(System.getProperty("user.dir"));
    private static final Path WORKFLOWS = REPO.resolve(Path.of(".github", "workflows"));

    private static String read(Path path) throws IOException {
        return Files.readString(path).replace("\r\n", "\n");
    }

    private static List<Path> workflows() throws IOException {
        try (var files = Files.list(WORKFLOWS)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".yml"))
                    .sorted()
                    .toList();
        }
    }

    /** {@code job id -> the text of that job}, split on the two-space-indented keys under {@code jobs:}. */
    private static Map<String, String> jobs(String workflow) {
        Map<String, String> jobs = new LinkedHashMap<>();
        String body = workflow.substring(workflow.indexOf("\njobs:\n") + "\njobs:\n".length());
        Matcher m = Pattern.compile("(?m)^  ([A-Za-z0-9_-]+):\\s*$").matcher(body);
        List<int[]> starts = new ArrayList<>();
        List<String> names = new ArrayList<>();
        while (m.find()) {
            starts.add(new int[] {m.start(), m.end()});
            names.add(m.group(1));
        }
        for (int i = 0; i < names.size(); i++) {
            int end = i + 1 < names.size() ? starts.get(i + 1)[0] : body.length();
            jobs.put(names.get(i), body.substring(starts.get(i)[1], end));
        }
        return jobs;
    }

    @Test
    void everyActionIsPinnedToAFullCommitShaWithItsVersionInAComment() throws IOException {
        Pattern uses = Pattern.compile("(?m)^\\s*(?:- )?uses:\\s*(.+)$");
        Pattern pinned = Pattern.compile("[\\w.-]+/[\\w./-]+@[0-9a-f]{40}\\s+#\\s*v?\\d\\S*");
        List<String> floating = new ArrayList<>();
        int seen = 0;
        for (Path workflow : workflows()) {
            Matcher m = uses.matcher(read(workflow));
            while (m.find()) {
                seen++;
                if (!pinned.matcher(m.group(1).trim()).matches()) {
                    floating.add(workflow.getFileName() + ": " + m.group(1).trim());
                }
            }
        }
        assertTrue(seen > 10, "found only " + seen + " `uses:` lines — the scan is broken");
        assertTrue(
                floating.isEmpty(),
                "a tag or branch is mutable; pin each action as `owner/repo@<40-hex sha> # vX.Y.Z`: " + floating);
    }

    @Test
    void theWorkflowTokenIsReadOnlyUnlessAJobAsksForMore() throws IOException {
        for (Path workflow : workflows()) {
            String text = read(workflow);
            assertTrue(
                    text.contains("\npermissions:\n  contents: read\n"),
                    workflow.getFileName() + " must default the token to `contents: read`");
        }
        Map<String, String> release = jobs(read(WORKFLOWS.resolve("release.yml")));
        for (var job : release.entrySet()) {
            boolean writes = job.getValue().contains("contents: write");
            boolean mayWrite = job.getKey().equals("release") || job.getKey().equals("bump");
            assertEquals(mayWrite, writes, "release.yml job `" + job.getKey() + "`: unexpected token scope");
        }
    }

    @Test
    void onlyTheJobThatPushesKeepsItsCheckoutCredentials() throws IOException {
        for (Path workflow : workflows()) {
            for (var job : jobs(read(workflow)).entrySet()) {
                String body = job.getValue();
                if (!body.contains("actions/checkout@")) {
                    continue;
                }
                boolean pushes = body.contains("git push");
                assertEquals(
                        !pushes,
                        body.contains("persist-credentials: false"),
                        workflow.getFileName() + " job `" + job.getKey()
                                + "`: a checkout keeps the token in .git/config unless persist-credentials is false,"
                                + " and only a job that pushes needs it");
            }
        }
    }

    @Test
    void everyJobHasATimeout() throws IOException {
        List<String> unbounded = new ArrayList<>();
        for (Path workflow : workflows()) {
            jobs(read(workflow)).forEach((name, body) -> {
                if (!body.contains("\n    timeout-minutes:")) {
                    unbounded.add(workflow.getFileName() + ":" + name);
                }
            });
        }
        assertTrue(unbounded.isEmpty(), "a hung job otherwise holds a runner for six hours: " + unbounded);
    }

    @Test
    void noJobRunsOnTheRetiredMacOs14Image() throws IOException {
        for (Path workflow : workflows()) {
            assertFalse(
                    Pattern.compile("os:\\s*\\[?[^\\n#]*macos-14\\b")
                            .matcher(read(workflow))
                            .find(),
                    workflow.getFileName() + " still schedules a job on macos-14, retired on 2026-11-02");
        }
    }

    @Test
    void theShippedPlatformsEachRunThePureSuiteInCi() throws IOException {
        String ci = read(WORKFLOWS.resolve("ci.yml"));
        String lane = jobs(ci).get("pure-tests");
        assertTrue(lane != null, "ci.yml lost its Windows/macOS test lane");
        assertTrue(lane.contains("windows-latest") && lane.contains("macos-15"), "pure-tests must cover both");
        assertTrue(lane.contains("-DexcludedGroups=fx"), "pure-tests runs the pure suite");
    }

    @Test
    void aReleaseIsGatedOnTheTagMatchingThePomAndOnEveryAssetBeingPresent() throws IOException {
        String release = read(WORKFLOWS.resolve("release.yml"));
        assertTrue(
                release.contains("tags: ['v[0-9]+.[0-9]+.[0-9]+*']"), "only version-shaped tags may start a release");
        Map<String, String> jobs = jobs(release);
        assertTrue(jobs.get("preflight").contains("check-release.py version"), "the tag/pom preflight is gone");
        for (String job : List.of("build", "native-experimental")) {
            assertTrue(jobs.get(job).contains("needs: preflight"), job + " must wait for the preflight");
        }
        String publish = jobs.get("release");
        int check = publish.indexOf("check-release.py assets");
        int jreleaser = publish.indexOf("jreleaser/release-action@");
        assertTrue(check >= 0, "the release job no longer verifies the expected assets");
        assertTrue(check < jreleaser, "the asset check must run BEFORE JReleaser publishes an immutable release");
        assertTrue(
                Pattern.compile("\\n\\s+version: \\d+\\.\\d+\\.\\d+\\n")
                        .matcher(publish)
                        .find(),
                "the JReleaser version the action downloads must be pinned (its default is `latest`)");
    }

    @Test
    void downloadsThatBecomePartOfABuildArePinnedAndVerified() throws IOException {
        String appimage = read(REPO.resolve(Path.of("scripts", "build-appimage.sh")));
        assertFalse(appimage.contains("/continuous/"), "build-appimage.sh downloads from a rolling release");
        assertTrue(appimage.contains("--runtime-file"), "appimagetool would fetch its runtime unverified");
        Matcher hashes = Pattern.compile("_SHA256=\"[0-9a-f]{64}\"").matcher(appimage);
        int count = 0;
        while (hashes.find()) {
            count++;
        }
        assertEquals(4, count, "appimagetool + type2 runtime, for x86_64 and aarch64");

        String wrapper = read(REPO.resolve(Path.of(".mvn", "wrapper", "maven-wrapper.properties")));
        assertTrue(
                Pattern.compile("(?m)^distributionSha256Sum=[0-9a-f]{64}$")
                        .matcher(wrapper)
                        .find(),
                "the Maven wrapper must verify the distribution it downloads");

        String javaDebug = read(REPO.resolve(Path.of("scripts", "install-java-debug.sh")));
        assertFalse(javaDebug.contains("/latest"), "install-java-debug.sh resolves an unpinned release");
        assertTrue(
                Pattern.compile("PINNED_SHA256=\"[0-9a-f]{64}\"")
                        .matcher(javaDebug)
                        .find(),
                "install-java-debug.sh must verify what it downloads (it runs in pull-request CI)");
    }

    @Test
    void dependabotWatchesMavenAndTheActions() throws IOException {
        Path config = REPO.resolve(Path.of(".github", "dependabot.yml"));
        assertTrue(Files.isRegularFile(config), "no Dependabot configuration");
        String text = read(config);
        for (String ecosystem : List.of("package-ecosystem: maven", "package-ecosystem: github-actions")) {
            assertTrue(text.contains(ecosystem), "dependabot.yml is missing `" + ecosystem + "`");
        }
    }
}
