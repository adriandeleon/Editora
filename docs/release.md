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
dispatch is available for a dry run; it validates JReleaser against the plain upcoming version
because immutable GitHub releases reject `-SNAPSHOT` even in dry-run mode.)

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
JReleaser only *orchestrates the release* — it does not build (the existing `dist` profile is
reused as-is). The experimental `native` profile is opt-in, so the normal build is unaffected.

### Experimental Native Image archives

A separate, best-effort `native-experimental` matrix builds the opt-in `-Pnative` profile on
Linux x64, macOS x64/arm64, and Windows x64. Each job runs on its own host OS with Oracle
GraalVM for JDK 25; Intel macOS uses the last available JDK 25 update for that host. Native
Image's Java heap is capped at 6 GiB with four build workers, or 4 GiB/two workers on the
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

- Installers are currently **unsigned** (signing/notarization is a follow-up).
- A JavaFX bump no longer needs any test-harness work — the headless backend ships inside JavaFX
  26 (the old vendored Monocle rebuild is gone; see
  [dependencies.md](dependencies.md#the-headless-test-backend-no-vendored-dependency)). Do
  re-run the device tests, since the per-OS Prism pipeline matters.
- The AOT cache adds ~72 MB to the installed image (compressed in the DMG/MSI/DEB). On a **release
  tag it is mandatory**: `release.yml` sets `EDITORA_REQUIRE_AOT=1`, so a leg that tries to train and
  ends up with no cache fails, and the `Release` job — which `needs` the matrix — never runs. Nothing
  publishes, rather than one platform shipping quietly slow. Local builds and dry runs leave the
  variable unset and stay failure-tolerant. If it ever fires on a legitimate release, set it back to
  `'0'` for that tag and fix the trainer — don't weaken `aot_build.java`, whose job is to notice. See
  [building-and-packaging.md](building-and-packaging.md#aot-cache-jdk-25-leyden).
