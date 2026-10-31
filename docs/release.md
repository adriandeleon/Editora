# Releasing

## Snapshot vs release versions

Between releases `master`'s `pom.xml` carries a **`-SNAPSHOT`** suffix (e.g. `0.9.8-SNAPSHOT`).
Only a release tag is cut from a plain `X.Y.Z` version. That makes any build self-identifying:

- **`AppInfo.VERSION`** (from the pom, via the Maven-filtered `build-info.properties`) shows the
  suffix in `--version`, the About dialog, and the Welcome footer.
- **`AppInfo.isSnapshot()`** drives a **`snapshot` badge** in the toolbar (beside the `--dev`
  badge), so a test build is obvious at a glance without opening About.
- **`AppInfo.releaseVersion()`** is the suffix-stripped form, for anywhere a version must be a
  plain dotted number — the versioned docs URLs, and the jpackage/`Info.plist` metadata (see
  below).

So: a toolbar with no snapshot badge means you are running a real release build.

## Cutting a release

> **Before the next tag: run a dry run.** *Actions → Release → Run workflow* on `master`
> (`workflow_dispatch`). The macOS arm64 legs moved from `macos-14` to `macos-15` (the macOS 14
> images are retired on 2026-11-02, with brownouts from 2026-10-05 —
> [actions/runner-images#13518](https://github.com/actions/runner-images/issues/13518)), and that
> runner has never built a release: AOT training there is sensitive to the runner's virtual GPU and
> `EDITORA_REQUIRE_AOT=1` fails the leg if it regresses. The same run is the first to exercise the
> SHA-pinned actions, the pinned JReleaser and `appimagetool`, the `--license-file` installer wraps
> and the release-asset check. A dry run is green only if **every build leg trained its cache** (the
> job summary lists one row per target) **and** the "Verify the expected release assets" step of the
> `Release` job prints `ok` for all eleven files — in a dry run that step only warns.

1. Set `<version>` in `pom.xml` to the release version (drop the `-SNAPSHOT`).
2. Update `CHANGELOG.md` (move `[Unreleased]` into a versioned section).
3. Push a `vX.Y.Z` tag. A `-rcN` suffix (`vX.Y.Z-rcN`) marks a pre-release.

```
git tag vX.Y.Z
git push origin vX.Y.Z
```

The tag triggers [`.github/workflows/release.yml`](../.github/workflows/release.yml). Only
version-shaped tags do (`v<digits>.<digits>.<digits>` plus an optional suffix). (Manual
dispatch is available for a dry run; it validates JReleaser against the plain upcoming version
because immutable GitHub releases reject `-SNAPSHOT` even in dry-run mode.)

A `preflight` job runs first and **stops the release in seconds if the tag and the pom disagree**:
a plain `vX.Y.Z` tag must equal the pom's `<version>` exactly (so a tag pushed while the pom still
says `X.Y.Z-SNAPSHOT` is refused instead of publishing installers whose app calls itself a
snapshot), and a tag must be `vX.Y.Z` or `vX.Y.Z-rcN`. An `-rc` tag is cut from the `-SNAPSHOT`
line and is checked only for its shape.

The same job then **refuses a commit that CI did not pass**. The build legs run no tests, so it asks
GitHub which checks the `master` ruleset requires (today `Build & Test (JDK 25)` and `Format (JDK 25)`)
and requires each to have succeeded on the tagged commit, `-rc` tags included. When CI is still running
on that commit — a tag pushed right after the merge — it waits for up to 30 minutes, so the build legs
start once CI is green; a failed check stops it at once. **Tag a commit whose CI has passed**, normally
the merge commit on `master`. If a required check failed for a reason unrelated to the code, re-run it
on that commit, then re-run the Release workflow: the newest run of each check decides. A manual dry run
prints the same verdict without stopping.

Last, it **requires a green run of the Full tests** on the tagged commit
([`full-tests.yml`](../.github/workflows/full-tests.yml): the pure suite on Windows and macOS, the FX
suite on macOS, and the probes against real JDT LS, java-debug, TypeScript, js-debug, SSH and Docker).
Pull requests do not wait for these suites, so this is the only place they stop anything from shipping.
A run from a pull request does not count (it tested an older `master`); the daily run or a dispatched
one does, and the newest decides. A fresh merge commit has none yet, so the preflight **starts one on
the tag** and waits for up to an hour; a failed run stops the release at once, and the log prints the
`gh run rerun <id> --failed` command for a failure unrelated to the code (then re-run the Release
workflow's failed jobs). To have it ready before tagging, start it on `master` yourself
(`gh workflow run full-tests.yml --ref master`) or tag the commit the daily run already tested. A dry
run reports the verdict and starts nothing.

**Step 1 is the only manual version edit.** After the release publishes, the workflow's final
`bump` job opens a pull request that reopens `master` at the next patch `-SNAPSHOT` — merge it; see
below.

## Reopening master (the post-release bump)

The `bump` job runs after JReleaser succeeds. `master` requires the `Build & Test (JDK 25)` and
`Format (JDK 25)` checks, so the job cannot push to it (a direct push was refused with GH013 on
v0.21.0). It pushes a one-line pom change to a branch, `chore/reopen-X.Y.Z`, and opens a pull request:

```
chore: reopen master at 0.9.8-SNAPSHOT after v0.9.7
```

**Merge that pull request to finish the release.** Until then `master` still carries the release
version.

- It is **skipped for pre-releases** (`vX.Y.Z-rcN`) — an rc is cut from the `-SNAPSHOT` line and
  must not advance it — and on `workflow_dispatch`, which is only ever a dry run. So a dry run never
  exercises it.
- It is **idempotent**: it bumps only when `master`'s pom still reads exactly the version just
  released, and does nothing when the branch already exists. If a human already moved the pom, the
  job logs and exits cleanly.
- **Its CI is held until approved, and the job approves it.** Opening the pull request queues a
  `pull_request` run of every workflow whose trigger matches (CI, Terminal image for `pom.xml`, Full
  tests), but GitHub holds each one ("action_required") because the Actions bot opened the pull
  request. The pull request counts the held CI run as its CI, so it stays blocked, even though the job
  also dispatches `ci.yml` on the branch and those checks pass on the same commit (v0.22.0, #1223).
  The job therefore approves **every** held `pull_request` run on the commit, polling until CI's held
  run has appeared (about two minutes at most) so a late-queued run is not missed. Approving only the
  first one is not enough: on v0.23.0 (#1296) that was Full tests, and CI and Terminal image stayed
  held. For each approval that is refused, it warns and its log and summary give the command to run:
  `gh api -X POST repos/<owner>/<repo>/actions/runs/<id>/approve`. The dispatched run is kept for the
  case where no `pull_request` run is queued.
- **Opening the pull request needs a repository setting**: *Settings → Actions → General → Allow
  GitHub Actions to create and approve pull requests*. With it off, the branch is still pushed and
  the checks still run; the job warns and its summary links to the page that opens the pull request
  in one click.

### `-SNAPSHOT` and the native installers

jpackage rejects a non-numeric `--app-version`, so the suffix must never reach it. Each OS
profile's build-helper step strips it into **`jpackage.publicVersion`**, and `jpackage.appVersion`
derives from that (macOS additionally bumps a leading `0.` to `1.` — see the comment in
`pom.xml`). `aot_build.java` writes `publicVersion` into the macOS `Info.plist`. A local
`-Pdist` build off `master` therefore produces an installer whose *bundle metadata* reads
`0.9.8` while the *app itself* still reports `0.9.8-SNAPSHOT` — which is the bit that matters
for telling builds apart.

## The pipeline

A **5-way matrix**, each on its own GitHub-hosted runner (no cross-building — jpackage + JavaFX
are host-specific, so each runner builds for itself):

| Target | Runner | Notes |
| --- | --- | --- |
| linux x64 | `ubuntu-latest` | `.deb`, `.rpm`, `.AppImage`, `.tar.gz`, fat jar |
| linux arm64 | `ubuntu-24.04-arm` | `.tar.gz` and fat jar only (no `.deb`/`.rpm`/`.AppImage`) |
| macOS x64 | `macos-15-intel` | `.dmg`. The last Intel x86_64 image (good through ~Aug 2027). |
| macOS arm64 | `macos-15` | `.dmg`. Was `macos-14` until that image's retirement (2026-11-02). |
| windows x64 | `windows-latest` | `.msi`, fat jar |

**Windows arm64 is omitted:** a hosted runner exists, but OpenJFX — still true of the JavaFX 27
that Editora builds against — publishes no `win-aarch64` native jar on Maven Central
([JDK-8314064]), so a native ARM64 build can't link — Windows-on-ARM users run the x64 installer
under emulation. Revisit when JavaFX ships `win-aarch64` natives.

Each runner:

- builds the native installer via the existing `-Pdist` profile (DMG / MSI / DEB+RPM);
- on Linux, builds the `.AppImage` (x64) and the install tarball from the same trained app image;
- builds a per-platform runnable fat jar via `-Pfatjar` (not on macOS);
- runs the AOT-cache training step (Linux legs wrap it in `xvfb`; the workflow installs
  `xvfb` + GTK/GL libs there).

Installers and fat jars are renamed to a consistent `Editora-<version>-<target>.<ext>` prefix
(the version comes from a `Resolve version` step — the tag minus `v`, else the pom version) and
uploaded as artifacts.

A final job hands everything to **JReleaser** (`jreleaser.yml`, via `jreleaser/release-action`),
which creates the GitHub release with all installers + fat jars + `checksums.txt` + a changelog.
JReleaser only *orchestrates the release* — it does not build (the existing `dist` profile is
reused as-is). The experimental `native` profile is opt-in, so the normal build is unaffected.

### The release-asset check

GitHub releases here are **immutable**: once published, an asset can never be added. And several
upstream steps are tolerant by design — JReleaser's globs match zero files without complaint, and
`aot_build.java` lets the `.rpm` succeed alone when the `.deb` wrap fails. So immediately before
JReleaser, the `Release` job runs
[`scripts/release/CheckRelease.java`](../scripts/release/CheckRelease.java) against the downloaded
artifacts and, on a tag, **refuses to publish unless all eleven expected files are present and
non-empty** (the table above; fat jars are named after the pom version, everything else after the
tag). It prints the full list with `ok`/`MISSING` per file. The AppImage and tarball steps are
likewise strict on a tag (a failure fails the linux leg, so *Re-run failed jobs* rebuilds just that
leg) and best-effort only in a dry run. The experimental Native Image archives are reported but never
required. If you add or remove a release artifact, change the `EXPECTED` table in that script, the
Stage step and `jreleaser.yml` together — `test_check_release.py` cross-checks them.

### macOS signing and notarization

Both macOS legs sign the app and DMG with the **Developer ID Application** certificate of team
`8JKSF797L8` (Adrian De Leon Saldivar), have Apple notarize the DMG and staple the ticket to it, so
Gatekeeper opens a downloaded Editora with no warning.

- **Import signing certificate (macOS)** puts the `.p12` into a throw-away keychain and exports
  `EDITORA_MAC_SIGN_IDENTITY` / `EDITORA_MAC_KEYCHAIN`. On a tag, missing secrets fail the leg; a dry
  run without them builds an ad-hoc signed DMG and warns.
- [`scripts/aot_build.java`](../scripts/aot_build.java) (`signMacApp`) signs after every change to the
  bundle (AOT cache, `bin/` strip, `Info.plist` edits) and before the DMG wrap. It first sets
  `<signed>true</signed>` in `Contents/app/.jpackage.xml`: jpackage's DMG wrap otherwise treats the
  image as unsigned and re-signs its copy ad hoc, discarding ours. Then it signs each Mach-O file, then
  `Contents/runtime`, then the app with the hardened runtime and
  [`packaging/macos/Editora.entitlements`](../packaging/macos/Editora.entitlements) (JIT, unsigned
  executable memory, and library validation off for the libraries JNA and pty4j unpack). It then
  signs the DMG. Without `EDITORA_MAC_SIGN_IDENTITY` (any local build) the app stays ad-hoc signed.
- **Notarize DMG (macOS)** runs `notarytool submit --wait` with the App Store Connect API key,
  prints Apple's log, staples, and checks the DMG and the app inside it with `spctl` the way
  Gatekeeper will. A rejection fails the leg; the log names each file Apple objected to.

Secrets (repository secrets on `adriandeleon/Editora`):

| Secret | What it is |
|---|---|
| `MACOS_CERT_P12` | base64 of a `.p12` holding the Developer ID Application certificate, its private key and Apple's Developer ID G2 intermediate |
| `MACOS_CERT_PASSWORD` | the `.p12` password |
| `APPLE_API_KEY_P8` | base64 of the App Store Connect API key (`AuthKey_<id>.p8`, team key, Developer role) |
| `APPLE_API_KEY_ID` | that key's ID |
| `APPLE_API_ISSUER` | the issuer ID shown above the team key list (leave unset for an individual key) |

Making the `.p12` without a Mac: `openssl genrsa` a 2048-bit key and `openssl req` a CSR, upload it at
developer.apple.com → Certificates → **Developer ID Application** (G2 Sub-CA), then convert the
downloaded `.cer` and Apple's `DeveloperIDG2CA.cer` to PEM and run
`openssl pkcs12 -export -legacy -inkey … -in … -certfile … -out developer-id.p12`. `-legacy` matters:
`security import` on the runner rejects OpenSSL 3's default `.p12` encryption.

The certificate expires **2027-11-30** and the Apple Developer Program membership **2027-10-31**.
Renew the membership first, then make a new certificate and replace `MACOS_CERT_P12` and
`MACOS_CERT_PASSWORD`. Apps signed before expiry keep working: the secure timestamp proves when they
were signed.

### Build provenance attestations

Before JReleaser publishes, **Attest build provenance** (`actions/attest-build-provenance`) records a
signed [SLSA](https://slsa.dev/) provenance statement for every file the release attaches: each
DMG, MSI, `.deb`, `.rpm`, AppImage, tarball and fat jar. A statement ties the file's SHA-256 to the
repository, the workflow, the commit and the tag that built it. For the Windows and Linux packages it is
the only signature they carry.

Anyone can check a download with the GitHub CLI:

```bash
gh attestation verify Editora-0.26.1-linux-x64.deb --repo adriandeleon/Editora --source-ref refs/tags/v0.26.1
```

- **Keyless.** Sigstore signs with the job's short-lived OIDC identity, so there is no key or secret
  to store, rotate or leak. The repository is public, so the statements go to Sigstore's public
  transparency log, which shows the file names and digests, both already public on the release.
- **The subject list is `jreleaser.yml`'s `files.globs`.** `ReleaseSupplyChainTest` fails when the
  two lists differ, so a new asset type is either attested or the build says why not.
- **A dry run attests too.** That is how the step is tested before a tag. Its statements name the
  branch, not a tag, so `--source-ref refs/tags/…` keeps a dry-run build from verifying as a
  release.
- `checksums_sha256.txt` (and `RELEASE.md`) are written by JReleaser after the step and are not
  attested themselves; every file the checksums list is.

### Supply chain

- The workflow token is **read-only** by default; only `release` (publishes) and `bump` (pushes the
  version-bump branch) ask for `contents: write`, and only `bump`'s checkout keeps its credentials.
  `bump` also holds `pull-requests: write` and `actions: write`, to open the pull request and
  dispatch CI on its branch. `release` alone holds `id-token: write` and `attestations: write`, for
  the [provenance attestations](#build-provenance-attestations).
- Every action is pinned to a **full commit SHA** with its version in a trailing comment;
  `.github/dependabot.yml` opens the update PRs (weekly, for Maven dependencies too).
  `jreleaser/release-action` has no `v2` *tag* — it was being consumed from a branch — and is pinned
  to that branch's head.
- The JReleaser the action downloads is pinned (`version:` in `release.yml`), as is the Maven the
  wrapper downloads (`distributionSha256Sum` in `.mvn/wrapper/maven-wrapper.properties` — update
  it together with `distributionUrl`).
- `scripts/build-appimage.sh` downloads a **versioned** `appimagetool` and type2 runtime and
  verifies both by SHA-256 before running anything (per architecture; the hashes are in the script).

### Experimental Native Image archives

**Disabled for now** (`if: false` on the job, and it is out of the `release` job's `needs`): in the
v0.20.0 release every target failed, Linux x64 with `native-image` out of heap, so that release has no
native archives. The rest of this section describes the job as it runs when enabled.

A separate, best-effort `native-experimental` matrix builds the opt-in `-Pnative` profile on
Linux x64, macOS x64/arm64, and Windows x64. Each job runs on its own host OS with Oracle
GraalVM for JDK 25; Intel macOS uses the last available JDK 25 update for that host. Native
Image's Java heap is capped at 6 GiB with four build workers, or 5 GiB/two workers on the
7 GiB Apple Silicon runner. The original uncapped Linux experiment peaked at 14 GiB. Linux
uses G1; the JDK 25 macOS/Windows images use Serial GC.
Each job checks `--version` on the extracted archive, then exercises the **actual app** opening a project
and file, editing, undo/redo, saving, project search, Git status and local history. The disposable
project lives outside the checkout so `.editorconfig` cannot change its save oracle. Only after
those checks pass does it upload `Editora-<version>-<target>-native-experimental.tar.gz` (Linux/macOS)
or `.zip` (Windows) for JReleaser to attach. Each archive includes the executable, any adjacent
Native Image shared libraries, a launcher and a limitations README. Run `./run-editora-native [file]`
on Linux/macOS or `run-editora-native.cmd [file]` on Windows; the launcher uses separate settings by
default and `EDITORA_NATIVE_CONFIG_DIR` overrides the location. These are unsigned portable archives,
not installers.
The Windows hosted runner uses StaticFX's headless toolkit for the editor workflow because it has
no interactive desktop; a Windows device trial is still needed to assess rendering and input.
The Apple Silicon hosted runner's virtual Metal GPU aborts during startup, so the experimental
macOS arm64 launcher uses JavaFX software rendering. Its behavior and performance on a physical Mac
still need device testing.

This is an experimental alternative to the regular installers, **not another platform in the
supported release matrix**. The [measured Linux experiment](native-image-staticfx.md) found slower
tokenization and input tails, unqualified peripheral features and an incompatibility with
dynamic Java plugins. macOS and Windows do not yet have comparative editing benchmarks or long-session
qualification; Linux arm64 is not attempted. An experimental job failure is visible in Actions but
does not block the JVM release; that target's archive is omitted when compilation, packaging, or
smoke testing fails. A compiled archive that fails the smoke test remains downloadable from the
workflow as `native-unqualified-<target>` with a `.candidate` suffix; remove that suffix to
extract it for device testing. It is never attached to the release. A manual dispatch also
dry-runs the jobs. Revisit the performance and feature
gate before making any target a default distribution.

CI uses the BellSoft **Liberica** JDK 25 for full arch coverage (incl. linux aarch64).

## Notes

- The macOS DMGs are signed and notarized (see
  [macOS signing and notarization](#macos-signing-and-notarization)). The Windows MSI and the Linux
  packages are not code-signed; every asset carries a
  [build provenance attestation](#build-provenance-attestations).
- A JavaFX bump no longer needs any test-harness work — the headless backend has shipped inside
  JavaFX itself since 26 (the old vendored Monocle rebuild is gone; see
  [dependencies.md](dependencies.md#the-headless-test-backend-no-vendored-dependency)). Do
  re-run the device tests, since the per-OS Prism pipeline matters.
- The AOT cache adds ~72 MB to the installed image (compressed in the DMG/MSI/DEB). On a **release
  tag it is mandatory**: `release.yml` sets `EDITORA_REQUIRE_AOT=1`, so a leg that tries to train and
  ends up with no cache fails, and the `Release` job — which `needs` the matrix — never runs. Nothing
  publishes, rather than one platform shipping quietly slow. Local builds and dry runs leave the
  variable unset and stay failure-tolerant. If it ever fires on a legitimate release, set it back to
  `'0'` for that tag and fix the trainer — don't weaken `aot_build.java`, whose job is to notice. See
  [building-and-packaging.md](building-and-packaging.md#aot-cache-jdk-25-leyden).
