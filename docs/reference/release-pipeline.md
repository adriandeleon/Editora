# Release pipeline reference

The exhaustive CI and native-packaging behavior. Start with [`release.md`](../release.md) for the
release workflow and use this catalog when changing platform-specific implementation details.

`.github/workflows/release.yml` runs on a `v*` tag (or manual dispatch for a dry run): a 5-way
matrix (linux x64/arm64, macOS x64/arm64, windows x64 — **Windows arm64 is omitted: a hosted
runner now exists (`windows-11-arm`, GA Jan 2026), but OpenJFX 25 publishes no `win-aarch64` native
jar on Maven Central (see [JDK-8314064]), so a native ARM64 build can't link — Windows-on-ARM users
run the x64 installer under x64 emulation. Revisit when JavaFX ships win-aarch64 natives.** macOS x64
uses the `macos-15-intel` runner — the last Intel x86_64 image (good through ~Aug 2027) — since the
old `macos-13` Intel runner was retired Dec 2025;
each on its own GitHub-hosted runner) builds the native
installer via the existing `-Pdist` profile — there is **no cross-building** (jpackage + JavaFX are
host-specific), so each runner builds for itself. Each runner also builds a per-platform runnable
fat jar via `-Pfatjar` (`Editora-<version>-<target>.jar`). **Linux also ships two portable formats built
from the same AOT-trained app-image the `.deb`/`.rpm` are wrapped from (`target/aot-image/Editora`, so no
second `-Pdist` build):** a single-file **`.AppImage`** (`scripts/build-appimage.sh`) and an extract-and-install
**`.tar.gz`** (`scripts/build-tarball.sh`) whose bundled `packaging/linux/tarball-install.sh` installs the
image to `/opt/editora` (root) or `~/.local/editora` (user) with an `editora` command + a `.desktop`
(`StartupWMClass=com.editora.App`), supporting `--system`/`--user`/`--prefix`/`--uninstall`. Both reuse the
jpackage `APP_IMAGE` output (= jlink + the native launcher + `lib/app/editora.aot`, `$APPDIR`-relative so it's
relocatable); both release steps are `continue-on-error` so a hiccup can't sink the installers. The
`.tar.gz`/`.AppImage`/`.rpm` are attached to the GitHub release via `jreleaser.yml`'s file globs. Installers are renamed to
`Editora-<version>-<target>.<ext>` per target (the Stage step preserves the compound `.tar.gz`
extension) — one consistent `Editora-<version>-<target>` prefix
across all artifacts (jpackage's DMG/MSI names omit the version + arch; the
version comes from a `Resolve version` step — the tag minus `v`, else the pom version) and uploaded as
artifacts alongside the fat jar. **macOS pre-1.0 app-version:** jpackage's `--app-version` (which becomes
`CFBundleVersion`/`CFBundleShortVersionString`) rejects a version whose first number is zero/negative, so a
`0.x.y` `pom.xml` version fails jpackage on macOS only (Linux/Windows accept it fine) — the `os-mac` profile
computes a bundle-metadata-only `jpackage.appVersion` via a `maven-antrun-plugin` execution (Ant
`loadresource`/`propertyresource`/`tokenfilter replaceregex`, `initialize` phase, `exportAntProperties`)
that bumps a leading `0.` to `1.` (`0.9.0`→`1.9.0`); `os-windows`/`os-linux` just alias it to
`${project.version}`. Both jpackage invocations — the `jpackage-app-image` execution and
`aot_build.java`'s later DMG-wrap call — read `${jpackage.appVersion}` for their `--app-version` flag
(confirmed empirically: the DMG-wrap bundler enforces the same zero/negative-first-number rule even in
`--app-image <path>` mode, so the placeholder is unavoidable on *both* calls). But the placeholder must
never reach the delivered app: `aot_build.java`'s **`fixMacBundleMetadata`** (which already rewrites
`CFBundleDocumentTypes` for "Open With" — see below) also rewrites `CFBundleVersion`/
`CFBundleShortVersionString` back to the TRUE version (passed as a separate `publicVersion` argument,
`${project.version}`, distinct from the `appVersion` placeholder) right after the app-image build,
*before* the DMG wrap runs — confirmed empirically that jpackage's DMG-wrap does **not** re-touch an
already-correct `Info.plist` (it only uses `--app-version` for its own CLI validation and to name the raw
`.dmg` file, which the `Resolve version`-based rename above replaces anyway), so the delivered `.app`'s
Finder "Get Info" / `mdls` / System Settings, plus the in-app `--version`/About dialog, all show the real
semver — never the placeholder. A final job
hands them to **JReleaser** (`jreleaser.yml`, via `jreleaser/release-action`) which creates the
GitHub release with all installers + fat jars + `checksums.txt` + a changelog. JReleaser only *orchestrates the release* — it does not
build (the `dist` profile is reused as-is) and there is **no `pom.xml`/Maven change**, so the normal
build is unaffected. Installers are currently **unsigned** (signing/notarization is a follow-up).
**Linux `.deb` PATH command + menu/icon registration:** jpackage installs everything under
`/opt/editora/` (launcher at `bin/Editora`, the `.desktop` + the 512×512 `Editora.png` at
`lib/editora-Editora.desktop`/`lib/Editora.png`) and relies on its **own generated `postinst`** to
register the `.desktop`/icon system-wide (via `xdg-desktop-menu`). The **`.deb`** ships a custom **jpackage resource dir** (staged by
**the DEB wrap of `scripts/aot_build.java`** into `target/dist/jpackage-deb-resources` = the
`packaging/linux` files + `branding/editora.png` staged as `Editora.png` — DEB-only; the RPM bundler
uses a `.spec` and ignores these overrides, so `.rpm` users run `/opt/editora/bin/Editora`) carrying
three overrides: **(a)** Debian maintainer scripts `postinst`/`postrm`, **(b)** the menu-entry
template **`Editora.desktop`** (jpackage substitutes the `APPLICATION_*` placeholders in a
resource-dir `<launcher>.desktop` exactly like its bundled `template.desktop`) — ours adds
**`Exec=… %F`** (without it GNOME launches the app *without* the opened file; the path arrives as
argv → `App.fileTargets`), **`MimeType=text/plain;`** (registers Editora as a text-file handler so
GNOME Files offers it under "Open With" and it can be made the default editor via `xdg-mime default
editora-Editora.desktop text/plain`; GIO follows shared-mime-info subclassing, so `text/plain`
covers markdown/python/json/…), **`StartupWMClass=com.editora.App`** (the real `WM_CLASS`, verified
via `wmctrl -lx` — JavaFX derives it from the module main class, *not* the app name; without it the
*running* window can't be matched to the entry), and real `Categories` (jpackage emits `Unknown`) —
and **(c)** the **icon `Editora.png`**: the wrap **regenerates `lib/<name>.png` in the DEB payload
from its own resources**, ignoring `--icon` *and clobbering the app image's already-fixed icon*
(verified empirically: the wrapped `.deb` shipped a byte-identical copy of jpackage's bundled 32×32
`JavaApp.png` despite the pre-wrap overwrite), so the icon **must** be supplied as a resource-dir
override — the pre-wrap `aot_build.java` overwrite of `<imageRoot>/lib/Editora.png` (Linux-only,
guarded) still matters, but only for the deliveries built directly from the app image (APP_IMAGE /
`.tar.gz` / `.AppImage`). **Because the maintainer-script override *replaces* jpackage's generated
scripts, it must reproduce their registration or the launcher is never installed into
`/usr/share/applications` and the app shows the generic Java icon.** So `postinst` (`configure`):
(1) symlinks `/usr/bin/editora` → the launcher (found via the `/opt/*/bin/Editora` glob, since
jpackage lowercases the install dir); (2) **copies the bundled `.desktop` into
`/usr/share/applications/editora-Editora.desktop`** (the template already carries
StartupWMClass/MimeType/`%F`, so the copy takes the verbatim fast path; an awk StartupWMClass
injection remains as a fallback — the menu icon itself comes from the `.desktop`'s already-absolute
`Icon=/opt/editora/lib/Editora.png`); (3) **derives a second menu entry
`/usr/share/applications/editora-Editora-expert.desktop`** ("Editora Expert Mode") from the
just-registered primary via `sed` — `Exec=… --expert --single-window %F`, `StartupWMClass`
**dropped** (both entries launch the same `WM_CLASS` `com.editora.App`; only the primary may claim
it or GNOME binds the running window/dock icon to an arbitrary entry), and a `MimeType` listing the
concrete text types; and (4) **registers Expert Mode as the system-wide default text editor** by
merging a `[Default Applications]` `type=editora-Editora-expert.desktop` line per type into
`/usr/share/applications/mimeapps.list` (the freedesktop mime-apps mechanism GIO honors for system
defaults; idempotent across re-runs, drops a competing default for the same type, preserves
unrelated entries). The type list is the **canonical shared-mime-info names** (verified via
`xdg-mime query filetype` on Debian 13): the `text/*` source/config types plus the `application/*`
text formats (json/yaml/toml/xml/sql/x-shellscript/x-ruby/x-php/x-perl); anything unlisted still
reaches Editora via GIO's `text/plain` **subclass fallback** (a type with its own explicit default
elsewhere doesn't fall back — that's why the explicit list exists). **`text/html` and
`image/svg+xml` are deliberately excluded** (browser / image viewer keep those), and a user's own
`~/.config/mimeapps.list` choice still wins over the system default (deliberate, per Debian
convention — postinst runs as root and must not rewrite per-user config). Then
`update-desktop-database`. Verify a real `.deb`'s icon by
`sha256sum`-ing `/opt/editora/lib/Editora.png` against `branding/editora.png` — they must match.
`postrm` (remove/purge) removes all of it: the symlink, both `.desktop` entries, and the
expert-default lines from `mimeapps.list` (deleting that file only when nothing but section headers
remains, i.e. postinst created it). DEB-only — the RPM bundler ignores the resource-dir maintainer
scripts, and the `.tar.gz`'s `tarball-install.sh` installs its own single `.desktop`. **Device-test on Linux** (install the `.deb`: `which editora`
works + the app shows our icon in the menu and dock; then remove: both are gone) — the macOS dev box and
the `os-linux` profile can't exercise this. *(If a terminal-launched window's dock icon is still generic,
the real `WM_CLASS` differs from `Editora` — check `xprop WM_CLASS` and fix the injected value.)*
Uses the BellSoft **Liberica** JDK 25 in CI for full arch coverage (incl. linux aarch64).
