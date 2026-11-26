package com.editora.packaging;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Tag;
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
@Tag("guard")
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
        String full = read(WORKFLOWS.resolve("full-tests.yml"));
        String lane = jobs(full).get("pure-tests");
        assertTrue(lane != null, "full-tests.yml lost its Windows/macOS test lane");
        assertTrue(lane.contains("windows-latest") && lane.contains("macos-15"), "pure-tests must cover both");
        assertTrue(lane.contains("-DexcludedGroups=fx"), "pure-tests runs the pure suite");
    }

    @Test
    void windowsRunsTheWindowsFxSliceAndEveryFullTestsJobSharesOneCondition() throws IOException {
        Map<String, String> jobs = jobs(read(WORKFLOWS.resolve("full-tests.yml")));
        String lane = jobs.get("windows-fx-tests");
        assertTrue(lane != null, "full-tests.yml lost its Windows FX lane");
        assertTrue(lane.contains("runs-on: windows-latest"), "windows-fx-tests must run on Windows");
        assertTrue(lane.contains("-Dgroups=windows-fx"), "windows-fx-tests runs the classes tagged windows-fx");
        Pattern condition = Pattern.compile("(?m)^    if: (.+)$");
        Set<String> conditions = new HashSet<>();
        for (Map.Entry<String, String> job : jobs.entrySet()) {
            Matcher m = condition.matcher(job.getValue());
            assertTrue(m.find(), job.getKey() + " has no job-level if: (it would run on every pull request)");
            conditions.add(m.group(1));
        }
        assertEquals(1, conditions.size(), "full-tests.yml's jobs must share one if: " + conditions);
    }

    @Test
    void aReleaseIsGatedOnTheTagMatchingThePomAndOnEveryAssetBeingPresent() throws IOException {
        String release = read(WORKFLOWS.resolve("release.yml"));
        assertTrue(
                release.contains("tags: ['v[0-9]+.[0-9]+.[0-9]+*']"), "only version-shaped tags may start a release");
        Map<String, String> jobs = jobs(release);
        assertTrue(jobs.get("preflight").contains("CheckRelease.java version"), "the tag/pom preflight is gone");
        assertTrue(
                jobs.get("preflight").contains("CheckRelease.java ci")
                        && jobs.get("preflight").contains("--mode enforce"),
                "the preflight no longer refuses a commit that CI did not pass");
        assertTrue(
                Pattern.compile("CheckRelease\\.java full [^\\n]*--mode enforce")
                        .matcher(jobs.get("preflight"))
                        .find(),
                "the preflight no longer refuses a commit without a green Full tests run (the Windows and macOS"
                        + " suites run nowhere else before a release)");
        for (String job : List.of("build", "native-experimental", "docs-site")) {
            assertTrue(
                    jobs.get(job).contains("needs: preflight") || jobs.get(job).contains("needs: [preflight,"),
                    job + " must wait for the preflight");
        }
        String publish = jobs.get("release");
        int check = publish.indexOf("CheckRelease.java assets");
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
    void theUserGuideIsRenderedWithTheWebsiteBranchThatTracksThisRepo() throws IOException {
        // 0.29.0's dry run: editora-website's master still listed screenshots this repo's user-docs/ had renamed,
        // so its build refused the guide. website-next is kept in step with Editora master; master is not.
        String docs = jobs(read(WORKFLOWS.resolve("release.yml"))).get("docs-site");
        assertTrue(
                docs.contains("repository: adriandeleon/editora-website"), "docs-site no longer checks out the site");
        assertTrue(
                docs.contains("ref: website-next"),
                "docs-site must render with editora-website's website-next: its master documents the release"
                        + " already out and can lag user-docs/");
    }

    @Test
    void theMacOsDmgIsSignedBeforeTheBuildAndNotarizedAfterIt() throws IOException {
        String build = jobs(read(WORKFLOWS.resolve("release.yml"))).get("build");
        int importCert = build.indexOf("- name: Import signing certificate (macOS)");
        int dist = build.indexOf("- name: Build native installer (-Pdist)");
        int notarize = build.indexOf("- name: Notarize DMG (macOS)");
        int stage = build.indexOf("- name: Stage artifacts");
        assertTrue(importCert >= 0 && notarize >= 0, "the macOS legs no longer sign and notarize the DMG");
        assertTrue(
                importCert < dist && dist < notarize && notarize < stage,
                "the keychain must exist before -Pdist signs the app, and the DMG must be notarized before it is"
                        + " staged for the release");
        String importStep = build.substring(importCert, dist);
        assertTrue(
                importStep.contains("GITHUB_REF_TYPE\" = \"tag\"") && importStep.contains("exit 1"),
                "a tag without the signing secrets must fail, not publish an unsigned DMG");
        assertTrue(
                importStep.contains("EDITORA_MAC_SIGN_IDENTITY="), "aot_build.java signs only when handed an identity");
        String notarizeStep = build.substring(notarize, stage);
        assertTrue(
                notarizeStep.contains("stapler staple") && notarizeStep.contains("spctl --assess"),
                "notarization must staple the ticket and check the result the way Gatekeeper will");
        assertTrue(
                build.contains("if: always() && steps.macsign.outputs.enabled == 'true'")
                        && build.contains("security delete-keychain"),
                "the keychain holding the private key must be removed even when the leg fails");
    }

    @Test
    void theLinuxPackagesAreSignedOffTheBuildRunnersBeforeTheReleaseSeesThem() throws IOException {
        Map<String, String> jobs = jobs(read(WORKFLOWS.resolve("release.yml")));
        String sign = jobs.get("sign-linux");
        assertTrue(sign != null, "the release no longer signs the Linux packages");
        assertTrue(sign.contains("needs: build"), "sign-linux signs what the build legs uploaded");
        assertFalse(
                jobs.get("build").contains("LINUX_GPG_KEY"),
                "the key must never reach a build leg, which runs whatever Maven resolves");
        assertTrue(
                sign.contains("GITHUB_REF_TYPE\" = \"tag\"") && sign.contains("exit 1"),
                "a tag without the signing key must fail, not publish unsigned packages");
        assertTrue(
                sign.contains("EDITORA_GPG_EXPECTED_KEY=packaging/linux/signing-key.asc"),
                "a tag must be signed by the committed key and no other");
        assertTrue(
                sign.indexOf("sign-linux-packages.sh") < sign.indexOf("check-linux-signatures.sh"),
                "what was signed is checked against the public key alone before it is uploaded");
        for (String target : List.of("linux-x64", "linux-arm64")) {
            assertTrue(
                    sign.contains("name: Editora-${{ steps.ver.outputs.version }}-" + target + "\n")
                            && sign.contains("overwrite: true"),
                    "the signed " + target + " files must replace the build leg's unsigned ones");
        }
        for (String script : List.of("sign-linux-packages.sh", "check-linux-signatures.sh")) {
            String text = read(REPO.resolve(Path.of("scripts", "release", script)));
            assertTrue(
                    text.contains("\"$dir\"/*.tar.gz \"$dir\"/*.jar; do"),
                    script + ": every Linux download, the fat jar included, has a detached signature (REL-16)");
        }
        String publish = jobs.get("release");
        assertTrue(
                publish.contains("needs: [build, sign-linux]")
                        && publish.contains("needs.sign-linux.result == 'success'"),
                "the release must wait for the signed files, and not run without them");
    }

    /** The text of each {@code - uses: actions/setup-java@} step: up to the job's next step. */
    private static List<String> setupJavaSteps(String job) {
        List<String> steps = new ArrayList<>();
        for (String step : job.split("\n      - ")) {
            if (step.contains("uses: actions/setup-java@")) {
                steps.add(step);
            }
        }
        return steps;
    }

    @Test
    void everySetupJavaStepUsesTheMavenCacheAndOnlyAJobThatRunsMavenSavesIt() throws IOException {
        List<String> uncached = new ArrayList<>();
        List<String> saving = new ArrayList<>();
        int seen = 0;
        for (Path workflow : workflows()) {
            for (var job : jobs(read(workflow)).entrySet()) {
                boolean runsMaven = job.getValue().contains("./mvnw ");
                for (String step : setupJavaSteps(job.getValue())) {
                    seen++;
                    String where = workflow.getFileName() + ":" + job.getKey();
                    if (!step.contains("\n          cache: maven\n")) {
                        uncached.add(where);
                    }
                    // A job with no Maven would save an empty ~/.m2 under the key the building jobs share.
                    if (!runsMaven && !step.contains("\n          cache-read-only: true")) {
                        saving.add(where);
                    }
                }
            }
        }
        assertTrue(seen > 10, "found only " + seen + " setup-java steps — the scan is broken");
        assertTrue(uncached.isEmpty(), "every setup-java step caches Maven's repository (REL-25): " + uncached);
        assertTrue(saving.isEmpty(), "a job that runs no Maven only reads the cache (cache-read-only): " + saving);
    }

    @Test
    void theReleaseNotesAreTheVersionsChangelogSectionNotTheCommits() throws IOException {
        String jreleaser = read(REPO.resolve("jreleaser.yml"));
        assertTrue(
                jreleaser.contains("\n    changelog:\n      external: out/release-notes.md\n"),
                "JReleaser must publish the notes ReleaseNotes.java writes (REL-04, DOCS-05)");
        assertFalse(
                jreleaser.contains("commitShortHash") || jreleaser.contains("commitTitle"),
                "the notes are written for the user, not a list of commits (DOCS-05)");
        for (Path workflow : workflows()) {
            assertFalse(
                    read(workflow).contains("generate_release_notes"),
                    workflow.getFileName() + " asks GitHub to generate the notes from the history (DOCS-05)");
        }
        Map<String, String> jobs = jobs(read(WORKFLOWS.resolve("release.yml")));
        assertTrue(
                jobs.get("preflight").contains("scripts/release/ReleaseNotes.java"),
                "the preflight must refuse a tag whose CHANGELOG.md has no section for it");
        String publish = jobs.get("release");
        int notes = publish.indexOf("scripts/release/ReleaseNotes.java");
        int jreleaserStep = publish.indexOf("jreleaser/release-action@");
        assertTrue(notes >= 0 && notes < jreleaserStep, "the release job writes the notes before JReleaser runs");
        assertTrue(publish.contains("out/release-notes.md"), "and where jreleaser.yml reads them");
    }

    @Test
    void theReleaseOpensTheWingetPullRequestThatJReleaserOnlyPushesABranchFor() throws IOException {
        // JReleaser's winget packager pushes the manifests to a branch of the fork and stops there: 0.29.0's
        // pull request on microsoft/winget-pkgs had to be opened by hand. The release job must open it.
        String jreleaser = read(REPO.resolve("jreleaser.yml"));
        assertTrue(
                jreleaser.contains("        branchPush: 'editora-{{projectVersion}}'\n"),
                "the step below looks for the fork branch by this name");
        String publish = jobs(read(WORKFLOWS.resolve("release.yml"))).get("release");
        int jreleaserStep = publish.indexOf("jreleaser/release-action@");
        int prStep = publish.indexOf("- name: Open the winget pull request\n");
        assertTrue(prStep > jreleaserStep && jreleaserStep >= 0, "the pull request is opened after JReleaser pushes");
        String step = publish.substring(prStep, publish.indexOf("\n      - name: ", prStep + 1));
        assertTrue(
                step.contains("gh pr create -R \"$upstream\"") && step.contains("upstream=microsoft/winget-pkgs"),
                "the step opens the pull request on microsoft/winget-pkgs");
        assertTrue(step.contains("branch=\"editora-$VERSION\""), "from the branch JReleaser pushed");
        assertTrue(
                step.contains("GH_TOKEN: ${{ secrets.PACKAGES_TOKEN }}"),
                "the workflow's own token cannot open a pull request on another repository (REL-14)");
        assertTrue(
                step.contains("continue-on-error: true"),
                "like the packager, a failed submission must never fail a published release");
        assertTrue(
                step.contains("!contains(github.ref_name, '-rc')"),
                "an rc pushes no manifest (`active: RELEASE`), so it opens no pull request");
        assertTrue(
                step.contains("--state open") && step.contains("already open"),
                "a rerun must not open a second pull request for the same version");
    }

    @Test
    void everyBuildLegStartsThePackagedAppBeforeItIsUploaded() throws IOException {
        String build = jobs(read(WORKFLOWS.resolve("release.yml"))).get("build");
        int tarball = build.indexOf("- name: Build install tarball (Linux)");
        int unix = build.indexOf("- name: Smoke-test the packaged app (Linux, macOS)");
        int windows = build.indexOf("- name: Smoke-test the packaged app (Windows)");
        int upload = build.indexOf("- name: Upload installer");
        assertTrue(unix >= 0 && windows >= 0, "every OS's leg must launch the app it packaged (REL-29)");
        assertTrue(
                tarball < unix && tarball < windows && unix < upload && windows < upload,
                "the smoke test runs after the last package is built from the image, and before the upload");
        String unixStep = build.substring(unix, windows);
        String windowsStep = build.substring(windows, build.indexOf("\n      - ", windows + 1));
        assertTrue(unixStep.contains("if: runner.os != 'Windows'"), "the Linux and macOS legs: " + unixStep);
        assertTrue(windowsStep.contains("if: runner.os == 'Windows'"), "the Windows leg: " + windowsStep);
        for (String step : List.of(unixStep, windowsStep)) {
            assertTrue(step.contains("--version") && step.contains("--check-terminal"), step);
            assertFalse(step.contains("continue-on-error"), "a leg whose app does not start must fail");
        }
        assertTrue(windowsStep.contains("editora-cli.exe"), "the Windows console launcher starts too");
    }

    @Test
    void everyReleaseAssetGetsAProvenanceAttestationBeforeItIsPublished() throws IOException {
        Map<String, String> jobs = jobs(read(WORKFLOWS.resolve("release.yml")));
        for (var job : jobs.entrySet()) {
            boolean mints = job.getValue().contains("id-token: write");
            assertEquals(
                    job.getKey().equals("release"),
                    mints,
                    "release.yml job `" + job.getKey() + "`: only the release job may mint an OIDC token");
        }
        String publish = jobs.get("release");
        assertTrue(publish.contains("attestations: write"), "the release job cannot store its attestations");
        int check = publish.indexOf("CheckRelease.java assets");
        int attest = publish.indexOf("actions/attest-build-provenance@");
        int jreleaser = publish.indexOf("jreleaser/release-action@");
        assertTrue(attest >= 0, "the release assets are no longer attested");
        assertTrue(
                check < attest && attest < jreleaser,
                "attest after the asset check and before JReleaser publishes the immutable release");

        List<String> attested = new ArrayList<>();
        Matcher subjects =
                Pattern.compile("subject-path: \\|\\n((?: {12}\\S.*\\n)+)").matcher(publish.substring(attest));
        assertTrue(subjects.find(), "the attestation step lost its `subject-path:` list");
        subjects.group(1).lines().map(String::trim).forEach(attested::add);
        List<String> released = new ArrayList<>();
        String config = read(REPO.resolve("jreleaser.yml"));
        Matcher globs = Pattern.compile("(?m)^    - pattern: '(.+)'$").matcher(config);
        while (globs.find()) {
            released.add(globs.group(1));
        }
        assertFalse(released.isEmpty(), "found no `files.globs` in jreleaser.yml — the scan is broken");
        // A distribution's file (the Homebrew and winget packagers) is attached to the release too; its path
        // names the version as a template, which the attestation's glob writes as `*`.
        int globbed = released.size();
        Matcher packaged = Pattern.compile("(?m)^      - path: '(.+)'$").matcher(config);
        while (packaged.find()) {
            released.add(packaged.group(1).replace("{{Env.EDITORA_BUILD_VERSION}}", "*"));
        }
        assertEquals(
                globbed + 2,
                released.size(),
                "expected the Apple Silicon DMG and the MSI as jreleaser.yml's distribution artifacts");
        assertEquals(released, attested, "every file JReleaser attaches must also be attested, and no other");
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
