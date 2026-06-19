# Releasing

## Cutting a release

1. Bump `<version>` in `pom.xml`.
2. Update `CHANGELOG.md` (move `[Unreleased]` into a versioned section).
3. Push a `vX.Y.Z` tag. A `-rcN` suffix (`vX.Y.Z-rcN`) marks a pre-release.

```
git tag vX.Y.Z
git push origin vX.Y.Z
```

The tag triggers [`.github/workflows/release.yml`](../.github/workflows/release.yml). (Manual
dispatch is available for a dry run.)

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
- Bumping the JavaFX version means rebuilding the vendored **Monocle** backend, or the
  `@Tag("fx")` tests stop linking — see [dependencies.md](dependencies.md#monocle--self-built-headless-backend-m2-repo-test-scope-only).
- The AOT cache adds ~60 MB to the installed image (compressed in the DMG/MSI/DEB); it's
  failure-tolerant, so a runner without a usable display still ships, just without the cache —
  see [building-and-packaging.md](building-and-packaging.md#aot-cache-jdk-25-leyden).
