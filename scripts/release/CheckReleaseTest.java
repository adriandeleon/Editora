package release;

import static lib.Assert.assertContains;
import static lib.Assert.assertEquals;
import static lib.Assert.assertMatches;
import static lib.Assert.assertNotContains;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lib.Json;
import lib.Repo;
import lib.TempDir;
import lib.Test;

class CheckReleaseTest implements AutoCloseable {

    private static final List<String> REQUIRED = List.of("Build & Test (JDK 25)", "Format (JDK 25)");
    private static final List<CheckRelease.CheckRun> NONE = List.of();

    private final TempDir scratch = new TempDir();

    @Override
    public void close() {
        scratch.close();
    }

    private Path pom(String version) throws IOException {
        Path path = scratch.resolve("pom.xml");
        Files.writeString(path, "<project>\n  <artifactId>editora</artifactId>\n  <version>" + version + "</version>\n"
                + "  <dependencies><dependency><version>9.9.9</version></dependency></dependencies>\n"
                + "</project>\n");
        return path;
    }

    /** Lays the files out as actions/download-artifact does: one directory per uploaded artifact. */
    private void populate(List<String> names) throws IOException {
        for (String name : names) {
            Path directory = scratch.resolve(name.substring(0, name.lastIndexOf('.')));
            Files.createDirectories(directory);
            Files.writeString(directory.resolve(name), "payload");
        }
    }

    /** The exit code and standard output of the command line. */
    private record Run(int code, String out) {}

    private static Run run(Object... argv) {
        var out = new ByteArrayOutputStream();
        String[] args = java.util.Arrays.stream(argv).map(String::valueOf).toArray(String[]::new);
        int code = CheckRelease.run(args, new PrintStream(out), new PrintStream(new ByteArrayOutputStream()));
        return new Run(code, out.toString());
    }

    // --- the expected assets

    @Test
    void theManifestIsTheDocumentedReleaseSet() {
        assertEquals(List.of(
                        "Editora-1.2.3-linux-arm64.jar",
                        "Editora-1.2.3-linux-arm64.tar.gz",
                        "Editora-1.2.3-linux-x64.AppImage",
                        "Editora-1.2.3-linux-x64.deb",
                        "Editora-1.2.3-linux-x64.jar",
                        "Editora-1.2.3-linux-x64.rpm",
                        "Editora-1.2.3-linux-x64.tar.gz",
                        "Editora-1.2.3-macos-arm64.dmg",
                        "Editora-1.2.3-macos-x64.dmg",
                        "Editora-1.2.3-windows-x64.jar",
                        "Editora-1.2.3-windows-x64.msi"),
                CheckRelease.expectedAssets("1.2.3", "1.2.3").stream().sorted().toList());
    }

    @Test
    void everyTargetOfTheBuildMatrixHasAManifestEntry() throws IOException {
        String workflow = Files.readString(Repo.root().resolve(".github/workflows/release.yml"));
        String build = workflow.split("\n  native-experimental:", 2)[0];
        var targets = Pattern.compile("\\{ target: ([a-z0-9-]+),").matcher(build).results()
                .map(m -> m.group(1))
                .collect(Collectors.toSet());
        assertEquals(CheckRelease.EXPECTED.keySet(), targets, "release.yml build matrix and EXPECTED have drifted");
    }

    @Test
    void everyExpectedExtensionIsAttachedByJreleaser() throws IOException {
        String globs = Files.readString(Repo.root().resolve("jreleaser.yml"));
        for (String name : CheckRelease.expectedAssets("1.2.3", "1.2.3")) {
            String extension = name.endsWith(".tar.gz") ? "tar.gz" : name.substring(name.lastIndexOf('.') + 1);
            if (!globs.contains("." + extension)) {
                throw new AssertionError("jreleaser.yml has no glob that would attach " + name);
            }
        }
    }

    // --- the assets command

    @Test
    void aCompleteMatrixPasses() throws IOException {
        populate(CheckRelease.expectedAssets("1.2.3", "1.2.3"));
        Run run = run("assets", scratch.path(), "1.2.3", "--pom", pom("1.2.3"));
        assertEquals(0, run.code(), run.out());
        assertNotContains(run.out(), "MISSING");
    }

    @Test
    void aFailedDebWrapBlocksTheReleaseAndNamesTheFile() throws IOException {
        var names = new ArrayList<>(CheckRelease.expectedAssets("1.2.3", "1.2.3"));
        names.remove("Editora-1.2.3-linux-x64.deb");
        names.remove("Editora-1.2.3-linux-x64.AppImage");
        populate(names);
        Run run = run("assets", scratch.path(), "1.2.3", "--pom", pom("1.2.3"));
        assertEquals(1, run.code());
        assertContains(run.out(), "::error");
        assertContains(run.out(), "Editora-1.2.3-linux-x64.deb");
        assertContains(run.out(), "Editora-1.2.3-linux-x64.AppImage");
    }

    @Test
    void anEmptyFileCountsAsMissing() throws IOException {
        populate(CheckRelease.expectedAssets("1.2.3", "1.2.3"));
        Files.write(scratch.resolve("Editora-1.2.3-windows-x64/Editora-1.2.3-windows-x64.msi"), new byte[0]);
        assertEquals(List.of("Editora-1.2.3-windows-x64.msi"), CheckRelease.missingAssets(scratch.path(), "1.2.3", "1.2.3"));
    }

    @Test
    void aDryRunReportsButDoesNotFail() throws IOException {
        Run run = run("assets", scratch.path(), "1.2.3-SNAPSHOT", "--pom", pom("1.2.3-SNAPSHOT"), "--mode", "report");
        assertEquals(0, run.code());
        assertContains(run.out(), "::warning");
    }

    @Test
    void theExperimentalArchivesAreNeverRequired() throws IOException {
        populate(CheckRelease.expectedAssets("1.2.3", "1.2.3"));
        Run run = run("assets", scratch.path(), "1.2.3", "--pom", pom("1.2.3"));
        assertEquals(0, run.code());
        assertContains(run.out(), "native-experimental");
    }

    @Test
    void anRcBuiltFromASnapshotPomExpectsJarsNamedAfterThePom() throws IOException {
        // The Stage step names installers after the tag but copies the fat jar under its Maven name.
        var names = CheckRelease.expectedAssets("1.3.0-rc1", "1.3.0-SNAPSHOT");
        lib.Assert.assertContains(names, "Editora-1.3.0-SNAPSHOT-linux-x64.jar", null);
        lib.Assert.assertContains(names, "Editora-1.3.0-rc1-linux-x64.deb", null);
        populate(names);
        Run run = run("assets", scratch.path(), "1.3.0-rc1", "--pom", pom("1.3.0-SNAPSHOT"));
        assertEquals(0, run.code(), run.out());
    }

    // --- the version command

    private List<String> check(String refType, String refName, String version) throws IOException {
        return CheckRelease.checkVersion(refType, refName, pom(version));
    }

    @Test
    void aMatchingTagPasses() throws IOException {
        assertEquals(List.of(), check("tag", "v1.2.3", "1.2.3"));
    }

    @Test
    void aTagCutFromASnapshotPomIsRefused() throws IOException {
        var problems = check("tag", "v1.2.3", "1.2.3-SNAPSHOT");
        assertEquals(1, problems.size());
        assertContains(problems.getFirst(), "-SNAPSHOT");
    }

    @Test
    void aTagForADifferentVersionIsRefused() throws IOException {
        assertEquals(1, check("tag", "v1.2.4", "1.2.3").size());
    }

    @Test
    void anRcTagIsNotComparedWithThePom() throws IOException {
        assertEquals(List.of(), check("tag", "v1.3.0-rc1", "1.2.4-SNAPSHOT"));
    }

    @Test
    void aTagThatIsNotAVersionIsRefused() throws IOException {
        for (String name : List.of("vendor-import", "v1.2", "v1.2.3-beta", "v1.2.3.4", "1.2.3")) {
            assertEquals(1, check("tag", name, "1.2.3").size(), name);
        }
    }

    @Test
    void aBranchDryRunIsNotChecked() throws IOException {
        assertEquals(List.of(), check("branch", "master", "1.2.3-SNAPSHOT"));
    }

    @Test
    void theProjectVersionIsTheFirstOneInThePom() throws IOException {
        assertEquals("4.5.6", CheckRelease.pomVersion(pom("4.5.6")));
        assertMatches("^\\d+\\.\\d+\\.\\d+", CheckRelease.pomVersion(Repo.root().resolve("pom.xml")));
    }

    @Test
    void theCommandExitsNonzeroOnAMismatch() throws IOException {
        Run run = run("version", "tag", "v9.9.9", pom("1.2.3"));
        assertEquals(1, run.code());
        assertContains(run.out(), "::error");
        assertEquals(0, run("version", "tag", "v1.2.3", pom("1.2.3")).code());
    }

    // --- the CI verdict

    private static CheckRelease.CheckRun checkRun(String name) {
        return checkRun(name, "completed", "success", "2026-10-14T01:00:00Z");
    }

    private static CheckRelease.CheckRun checkRun(String name, String status, String conclusion) {
        return checkRun(name, status, conclusion, "2026-10-14T01:00:00Z");
    }

    private static CheckRelease.CheckRun checkRun(String name, String status, String conclusion, String started) {
        return new CheckRelease.CheckRun(name, status, conclusion, started);
    }

    private static String verdict(List<CheckRelease.CheckRun> runs) {
        return CheckRelease.ciVerdict(runs, REQUIRED).verdict();
    }

    @Test
    void everyRequiredCheckPassed() {
        var runs = new ArrayList<>(REQUIRED.stream().map(CheckReleaseTest::checkRun).toList());
        runs.add(checkRun("FX tests", "completed", "failure"));
        var verdict = CheckRelease.ciVerdict(runs, REQUIRED);
        assertEquals("pass", verdict.verdict(), verdict.lines().toString()); // an advisory lane does not decide
    }

    @Test
    void aRequiredCheckThatFailedFails() {
        var verdict = CheckRelease.ciVerdict(
                List.of(checkRun(REQUIRED.get(0), "completed", "failure"), checkRun(REQUIRED.get(1))), REQUIRED);
        assertEquals("fail", verdict.verdict());
        assertContains(verdict.lines().getFirst(), "FAILED");
    }

    @Test
    void runningOrMissingIsPending() {
        assertEquals("pending", verdict(List.of(checkRun(REQUIRED.get(0), "in_progress", null))));
        assertEquals("pending", verdict(List.of()));
    }

    @Test
    void aFailureBeatsACheckStillRunning() {
        assertEquals("fail", verdict(List.of(checkRun(REQUIRED.get(0), "completed", "cancelled"),
                checkRun(REQUIRED.get(1), "queued", null))));
    }

    @Test
    void theNewestRunOfACheckDecides() {
        var oldFail = checkRun(REQUIRED.get(0), "completed", "failure", "2026-10-14T01:00:00Z");
        var rerunOk = checkRun(REQUIRED.get(0), "completed", "success", "2026-10-14T02:00:00Z");
        assertEquals("pass", verdict(List.of(rerunOk, oldFail, checkRun(REQUIRED.get(1)))));
        var rerunFail = checkRun(REQUIRED.get(0), "completed", "failure", "2026-10-14T03:00:00Z");
        assertEquals("fail", verdict(List.of(rerunOk, rerunFail, checkRun(REQUIRED.get(1)))));
    }

    @Test
    void aSkippedRequiredCheckIsNotAPass() {
        assertEquals("fail", verdict(List.of(checkRun(REQUIRED.get(0), "completed", "skipped"), checkRun(REQUIRED.get(1)))));
    }

    // --- polling CI

    /** Answers {@code gh api} for the rules and check-runs endpoints; each poll gets the next state. */
    private static final class FakeGitHub implements Function<List<String>, String> {
        private final List<String> required;
        private final List<List<CheckRelease.CheckRun>> states;
        int polls;

        @SafeVarargs
        FakeGitHub(List<String> required, List<CheckRelease.CheckRun>... states) {
            this.required = required;
            this.states = List.of(states);
        }

        @Override
        public String apply(List<String> args) {
            if (args.get(1).endsWith("/rules/branches/master")) {
                return required.stream().map(name -> name + "\n").collect(Collectors.joining());
            }
            var state = states.get(Math.min(polls, states.size() - 1));
            polls++;
            var out = new StringBuilder();
            for (var run : state) {
                Map<String, Object> json = new LinkedHashMap<>();
                json.put("name", run.name());
                json.put("status", run.status());
                json.put("conclusion", run.conclusion());
                json.put("started_at", run.startedAt());
                out.append(Json.write(json)).append('\n');
            }
            return out.toString();
        }
    }

    private static Run runCheck(FakeGitHub gh, String mode, int wait) {
        double[] now = {0};
        var out = new ByteArrayOutputStream();
        int code = CheckRelease.checkCi("o/r", "abc123", mode, wait, gh, seconds -> now[0] += seconds, () -> now[0],
                new PrintStream(out));
        return new Run(code, out.toString());
    }

    @Test
    void itWaitsForCiStillRunningOnTheCommit() {
        var gh = new FakeGitHub(REQUIRED, NONE, List.of(checkRun(REQUIRED.get(0), "in_progress", null)),
                REQUIRED.stream().map(CheckReleaseTest::checkRun).toList());
        Run run = runCheck(gh, "enforce", 30);
        assertEquals(0, run.code(), run.out());
        assertEquals(3, gh.polls);
    }

    @Test
    void aFailedCheckStopsTheReleaseAtOnce() {
        var gh = new FakeGitHub(REQUIRED,
                List.of(checkRun(REQUIRED.get(0), "completed", "failure"), checkRun(REQUIRED.get(1), "queued", null)));
        Run run = runCheck(gh, "enforce", 30);
        assertEquals(1, run.code());
        assertEquals(1, gh.polls);
        assertContains(run.out(), "::error");
    }

    @Test
    void ciThatNeverFinishesFailsAfterTheWait() {
        var gh = new FakeGitHub(REQUIRED, NONE);
        Run run = runCheck(gh, "enforce", 5);
        assertEquals(1, run.code());
        assertEquals(11, gh.polls); // at 0, 30 s, ... 300 s
        assertContains(run.out(), "has not finished");
    }

    @Test
    void aDryRunOnlyReports() {
        var gh = new FakeGitHub(REQUIRED, List.of(checkRun(REQUIRED.get(0), "completed", "failure")));
        Run run = runCheck(gh, "report", 0);
        assertEquals(0, run.code());
        assertContains(run.out(), "::warning");
    }

    @Test
    void noRequiredChecksIsRefusedRatherThanPassed() {
        Run run = runCheck(new FakeGitHub(List.of(), NONE), "enforce", 30);
        assertEquals(1, run.code());
        assertContains(run.out(), "requires no checks");
    }
}
