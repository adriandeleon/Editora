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

1. Set `<version>` in `pom.xml` to the release version (drop the `-SNAPSHOT`).
2. Update `CHANGELOG.md` (move `[Unreleased]` into a versioned section).
3. Push a `vX.Y.Z` tag. A `-rcN` suffix (`vX.Y.Z-rcN`) marks a pre-release.

```
git tag vX.Y.Z
git push origin vX.Y.Z
```

The tag triggers [`.github/workflows/release.yml`](../.github/workflows/release.yml). (Manual
dispatch is available for a dry run.)

**Step 1 is the only manual version edit.** After the release publishes, the workflow's final
`bump` job reopens `master` at the next patch `-SNAPSHOT` for you — see below.

## Reopening master (the post-release bump)

The `bump` job runs after JReleaser succeeds and commits a one-line pom change to `master`:

```
chore: reopen master at 0.9.8-SNAPSHOT after v0.9.7
```

- It is **skipped for pre-releases** (`vX.Y.Z-rcN`) — an rc is cut from the `-SNAPSHOT` line and
  must not advance it — and on `workflow_dispatch`, which is only ever a dry run.
- It is **idempotent**: it bumps only when `master`'s pom still reads exactly the version just
  released. If a human already moved it, the job logs and exits cleanly.
- It pushes with the default `GITHUB_TOKEN`. **If `master` becomes branch-protected, that push
  will fail** (the release itself is already published by then) — swap in a PAT with bypass
  rights, or do step 1's inverse by hand.

### `-SNAPSHOT` and the native installers

jpackage rejects a non-numeric `--app-version`, so the suffix must never reach it. Each OS
profile's antrun step strips it into **`jpackage.publicVersion`**, and `jpackage.appVersion`
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
| linux x64 | ubuntu | |
| linux arm64 | ubuntu arm | |
| macOS x64 | `macos-15-intel` | the last Intel x86_64 image (good through ~Aug 2027). |
| macOS arm64 | macos | |
| windows x64 | windows | |

**Windows arm64 is omitted:** a hosted runner exists, but OpenJFX 25 publishes no `win-aarch64`
native jar on Maven Central ([JDK-8314064]), so a native ARM64 build can't link —
Windows-on-ARM users run the x64 installer under emulation. Revisit when JavaFX ships
`win-aarch64` natives.

Each runner:

- builds the native installer via the existing `-Pdist` profile (DMG/MSI/DEB);
- builds a per-platform runnable fat jar via `-Pfatjar`;
- runs the AOT-cache training step (Linux legs wrap it in `xvfb`; the workflow installs
  `xvfb` + GTK/GL libs there).

Installers and fat jars are renamed to a consistent `Editora-<version>-<target>.<ext>` prefix
(the version comes from a `Resolve version` step — the tag minus `v`, else the pom version) and
uploaded as artifacts.

A final job hands everything to **JReleaser** (`jreleaser.yml`, via `jreleaser/release-action`),
which creates the GitHub release with all installers + fat jars + `checksums.txt` + a changelog.
JReleaser only *orchestrates the release* — it does not build (the `dist` profile is reused
as-is), and there is **no `pom.xml`/Maven change**, so the normal build is unaffected.

CI uses the BellSoft **Liberica** JDK 25 for full arch coverage (incl. linux aarch64).

## Notes

- Installers are currently **unsigned** (signing/notarization is a follow-up).
- A JavaFX bump no longer needs any test-harness work — the headless backend ships inside JavaFX
  26 (the old vendored Monocle rebuild is gone; see
  [dependencies.md](dependencies.md#the-headless-test-backend-no-vendored-dependency)). Do
  re-run the device tests, since the per-OS Prism pipeline matters.
- The AOT cache adds ~60 MB to the installed image (compressed in the DMG/MSI/DEB); it's
  failure-tolerant, so a runner without a usable display still ships, just without the cache —
  see [building-and-packaging.md](building-and-packaging.md#aot-cache-jdk-25-leyden).
