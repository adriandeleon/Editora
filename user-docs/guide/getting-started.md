---
title: Getting Started
description: Install Editora, open your first file, and learn the keyboard-first essentials.
category: Getting started
order: 2
---

Install Editora, open a folder or a file, and learn the handful of keys that
make it feel fast.

## Install

Every release has native packages that bundle their own Java runtime, so
there's nothing else to install. Get them from the
[releases page](https://github.com/adriandeleon/Editora/releases/latest) (or the
OS-detected button on the [home page](/)), or let a package manager fetch and
update Editora for you.

| Your system | Best choice | Also works |
| --- | --- | --- |
| Mac with Apple Silicon | [Homebrew](#package-managers), or `Editora-<version>-macos-arm64.dmg` | |
| Intel Mac | `Editora-<version>-macos-x64.dmg` | |
| Windows x64 | `Editora-<version>-windows-x64.msi` | The portable jar |
| Debian, Ubuntu, Mint (x64) | The [apt repository](#package-managers) | The `.deb`, the `.AppImage`, the `.tar.gz` |
| Fedora, RHEL, openSUSE (x64) | `Editora-<version>-linux-x64.rpm` | The `.AppImage`, the `.tar.gz` |
| Any other Linux (x64) | The `.AppImage` or the `.tar.gz` | The portable jar |
| Linux arm64 | `Editora-<version>-linux-arm64.tar.gz` | The portable jar |

If you already have JDK 25, there is also a portable jar for Linux x64, Linux
arm64 and Windows x64 (`Editora-<version>-linux-x64.jar` and so on), run with
`java -jar`; see the [command-line reference](/docs/cli). Each jar is built for
its platform, so take the one that matches your system.

### macOS

Download the `.dmg` for your Mac (`macos-arm64` for Apple Silicon, `macos-x64`
for Intel), open it, and drag Editora to Applications.

### Windows

Run the `.msi` (x64). It installs Editora for every user of the computer, so
Windows asks for administrator rights. The installer lets you choose the folder
and adds a Start Menu group and a desktop shortcut.

### Linux

Pick the format that suits your system. `.deb`, `.rpm`, and `.AppImage` are x64
only; the `.tar.gz` comes for x64 and arm64. On Debian and Ubuntu, the
[apt repository](#package-managers) installs the same `.deb` and keeps it up to
date.

```bash
# Debian / Ubuntu: adds an `editora` command and a menu entry
sudo apt install ./Editora-<version>-linux-x64.deb

# Fedora / RHEL / openSUSE: adds a menu entry; the launcher is /opt/editora/bin/Editora
sudo dnf install ./Editora-<version>-linux-x64.rpm

# AppImage: no install step
chmod +x Editora-<version>-linux-x64.AppImage
./Editora-<version>-linux-x64.AppImage

# Tarball (x64 or arm64): adds an `editora` command and a menu entry
tar xzf Editora-<version>-linux-x64.tar.gz && cd editora-x64
./install.sh          # per-user  -> ~/.local/editora  (+ ~/.local/bin/editora)
sudo ./install.sh     # system    -> /opt/editora       (+ /usr/local/bin/editora)
./install.sh --uninstall   # remove it again
```

The tarball also runs in place without installing: `./Editora/bin/Editora`
from the extracted folder. For arm64, use `linux-arm64` in the file name and
`cd editora-arm64`.

The `.deb` also adds a second menu entry, *Editora Expert Mode*, which opens a
file in one window with minimal chrome (see
[focus modes](/docs/workspace#focus-modes)), and makes it the system-wide
default for text and source files. Your own per-user choice still wins, and the
previous defaults are put back when you uninstall the package. See the
[command-line reference](/docs/cli) for where the launcher lives in each
package.

### Package managers

A package manager downloads Editora, checks the download for you and installs
new releases with your other updates. Editora's own update check only tells you
a newer version exists, so the package manager does the updating.

**Homebrew (Mac with Apple Silicon).** Editora is a cask in its own tap. Intel
Macs use the `macos-x64` `.dmg` above.

```bash
brew install --cask adriandeleon/tap/editora   # install
brew upgrade --cask editora                    # update to the latest release
brew uninstall --cask editora                  # remove it
```

The cask installs the same signed and notarized app as the `.dmg` into
Applications.

**apt (Debian, Ubuntu and their derivatives, x64).** Editora is in
[Vitrina](https://vitrina-project.dev), the apt repository for Editora and its
sibling apps, signed with the same release key as the downloads (see
[Checking a download](#checking-a-download)). Add the repository once:

```bash
sudo install -d -m 0755 /etc/apt/keyrings
curl -fsSL https://vitrina-project.dev/vitrina.asc | sudo tee /etc/apt/keyrings/vitrina.asc > /dev/null
sudo tee /etc/apt/sources.list.d/vitrina.sources > /dev/null <<'EOF'
Types: deb
URIs: https://vitrina-project.dev/apt
Suites: stable
Components: main
Architectures: amd64 arm64
Signed-By: /etc/apt/keyrings/vitrina.asc
EOF
sudo apt update
```

Then:

```bash
sudo apt install editora      # install
sudo apt upgrade              # update it along with everything else
sudo apt remove editora       # remove it
```

It installs the same `.deb` as the download, so everything said about the `.deb`
above holds. Vitrina carries the latest release only, and picks a new one up
within a day of it being published. A `.deb` you installed by hand earlier is
upgraded in place by `apt upgrade` once the repository is added.

A winget package for Windows is on its way but not available yet; until it is,
use the `.msi`.

### Check that it runs

Ask the installed launcher for its version. It prints the version and exits
without opening a window:

| Package | Command |
| --- | --- |
| `.deb`, apt, `.tar.gz` | `editora --version` |
| `.rpm` | `/opt/editora/bin/Editora --version` |
| `.AppImage` | `./Editora-<version>-linux-x64.AppImage --version` |
| macOS (`.dmg` or Homebrew) | `/Applications/Editora.app/Contents/MacOS/Editora --version` |
| Windows `.msi` | `editora-cli --version` (in a new terminal, so the updated `PATH` applies) |
| Portable jar | `java -jar Editora-<version>-<platform>.jar --version` |

Then start Editora from your menu, Launchpad or Start Menu.
**`View: Doctor`** (in the Tools menu) lists the tools it found on your system;
see [Doctor](/docs/doctor).

### First launch

The macOS app is signed with a Developer ID certificate and notarized by Apple.
The first time you open it, macOS asks once whether to open an app downloaded
from the Internet; click **Open**.

The Windows installer is **unsigned**, so SmartScreen stops the `.msi` until you
allow it, and the administrator prompt that follows names the publisher as
*Unknown*. The steps are in
[Troubleshooting](/docs/troubleshooting#a-launcher-is-blocked-on-first-run).

### Checking a download

Each release lists the SHA-256 of every file in `checksums_sha256.txt`.
Download it into the same folder as the file, then:

```bash
# Linux
sha256sum --ignore-missing -c checksums_sha256.txt

# macOS
grep macos-arm64.dmg checksums_sha256.txt | shasum -a 256 -c   # macos-x64.dmg on Intel
```

```powershell
# Windows (PowerShell): prints True when the file matches
$f = "Editora-<version>-windows-x64.msi"
(Get-FileHash $f).Hash -eq ((Select-String $f checksums_sha256.txt).Line -split '\s+')[0]
```

The Linux downloads (`.deb`, `.rpm`, `.AppImage` and `.tar.gz`) each come with
an OpenPGP signature beside them (`.asc`), made with the release key attached
to the release as `editora-signing-key.asc` (fingerprint
`8486 A7AF 4FC8 FEE9 3D83 CDB4 EA5F A138 C2C0 9C4A`):

```bash
gpg --import editora-signing-key.asc
gpg --verify Editora-<version>-linux-x64.deb.asc Editora-<version>-linux-x64.deb
```

The `.rpm` and `.deb` are also signed inside, for `rpm -K`, dnf and
`debsig-verify`.

Every release file also has a build provenance attestation: a signed record
that the file was built by the Editora repository's release workflow from the
tagged commit. For the Windows `.msi` it is the only signature. With the
[GitHub CLI](https://cli.github.com/), on any system:

```bash
gh attestation verify Editora-<version>-windows-x64.msi \
  --repo adriandeleon/Editora --source-ref refs/tags/v<version>
```

Use the name of the file you downloaded; the command is the same for the
`.dmg`, the Linux packages and the jars.

### Updating and removing

Editora checks for a newer release at most once a day, and the status bar then
shows **Update: X.Y.Z**; click it to open the download page. Editora does not
update itself: download the new package and install it the same way.

| Package | Update | Remove |
| --- | --- | --- |
| macOS `.dmg` | Drag the new Editora to Applications and replace the old one | Move Editora from Applications to the Trash |
| Homebrew cask | `brew upgrade --cask editora` | `brew uninstall --cask editora` (`~/.editora/` stays) |
| Windows `.msi` | Run the new `.msi`; it replaces the installed version | In Windows Settings, **Apps → Installed apps**, then Editora's **Uninstall** |
| `.deb` | `sudo apt install ./Editora-<version>-linux-x64.deb` | `sudo apt remove editora` |
| apt repository | `sudo apt upgrade` | `sudo apt remove editora` |
| `.rpm` | `sudo dnf install ./Editora-<version>-linux-x64.rpm` | `sudo dnf remove editora` |
| `.AppImage` | Replace the file with the new one | Delete the file |
| `.tar.gz` | Extract the new tarball and run its `./install.sh` (with `sudo` for a system install) | `./install.sh --uninstall` (with `sudo` for a system install) |
| Portable jar | Replace the jar | Delete the jar |

Removing Editora leaves your settings in the
[config folder](/docs/configuration) (`~/.editora/`); delete that folder too to
remove them.

### Or run from source

Requires JDK 25+. The Maven wrapper is bundled (use `mvnw.cmd` on Windows).

```bash
git clone https://github.com/adriandeleon/Editora.git
cd Editora
./mvnw javafx:run           # run the app
./mvnw -Pfatjar package     # build a self-contained jar
java -jar target/Editora-*.jar
```

## Open a folder or a file

A fresh install opens on the **Welcome** page. At the top it asks **Which keys
do your fingers know?**: click Emacs, CUA, Sublime Text, VS Code or IntelliJ
IDEA and every shortcut in Editora follows, or **Keep Emacs**. It asks once;
[Choose your keymap](#choose-your-keymap) below changes it later. Under it,
**Check for new versions?** asks whether Editora may ask GitHub once a day for a
newer release (**Check Daily** or **Don't Check**); nothing is sent until you
answer, and **Settings → Advanced** changes it later. The buttons under those
are the quickest way in:

- **Open Folder…** opens a folder as a project, with its file tree in the
  Project tool window. A click opens your system's folder dialog; its shortcut
  opens Editora's own folder prompt, where you type the path. This is the usual way to work on a codebase; each project
  gets its own window and remembers its open files.
- **Open File…** opens a single file.
- **New File** starts an empty buffer, and **Clone Git Repository…** fetches a
  repository and opens it.

Every one of these is also a command in the palette. Reopen the page any time
with **Help → Welcome Page** or *View: Welcome Page* in the palette.

![The Welcome tab with the Editora logo, a Start list (New File, Open File, Open Folder as a Project, Clone Git Repository, Command Palette) with shortcuts, and Recent Projects and Recent Files lists](/screenshots/docs/welcome.png "The Welcome page: open a folder or a file, and change the keymap or theme from the same page.")

The page also shows the keymap and theme in use; click either to change it.
Editora starts with the Emacs keymap, so shortcuts read like `C-x C-f`
(Ctrl+X, then Ctrl+F) and `M-x` (Alt+X). Pick the VS Code, IntelliJ, Sublime
or CUA keymap there if you prefer, and every shortcut shown switches to your
platform's notation. See [Keymaps](/docs/keymaps).

Starting Editora again while it is running does not open a second editor: the
launch goes to the one already running. And if the editor ever stops without
closing normally, the next launch offers your unsaved edits back; see
[crash recovery](/docs/undo-history#crash-recovery).

## Choose your keymap

Editora ships five keymaps: **Emacs** (the default), **CUA**, **Sublime Text**,
**VS Code**, and **IntelliJ IDEA**. The Welcome page asks which one on first
launch. To change it later: open Settings (the gear in the toolbar,
or "Settings" in the palette), go to **Keymaps**, and pick one. The change is
live, and every shortcut shown in menus, tooltips, and the palette follows it.
See [Keymaps & keybindings](/docs/keymaps).

### Reading the key notation

These docs write the Emacs keymap's chords the Emacs way:

- `C-` is **Ctrl**, `M-` is **Alt** (**Option** on macOS), and `S-` is
  **Shift**. So `M-x` is Alt+X and `C-S-f` is Ctrl+Shift+F.
- A space separates the steps of a sequence: `C-x C-f` is Ctrl+X, then Ctrl+F.
- `C-g` cancels a half-typed sequence.

## First steps

Editora is keyboard-first. These get you moving:

| Do this | Emacs keymap | VS Code keymap |
| --- | --- | --- |
| Open the command palette (everything is here) | `M-x` | `Ctrl+Shift+P` or `F1` |
| Open a file (by path; in VS Code, by name in the project) | `C-x C-f` | `Ctrl+P` |
| Save | `C-x C-s` | `Ctrl+S` |
| Jump to a symbol in the file | `M-g i` | `Ctrl+Shift+O` |
| Switch between open files | `C-x b` | (palette) |
| Find in the current file | `C-s` | `Ctrl+F` |
| Search Everywhere | `M-S-x` | `Ctrl+Shift+E` |
| Toggle a bookmark | `C-c m` | (palette) |
| Open Settings | (palette) | `Ctrl+,` |

On macOS the VS Code keymap uses `Cmd` where the table shows `Ctrl`. The CUA,
Sublime Text, and IntelliJ IDEA keymaps differ in places; the
[Keybindings](/keybindings) page has a tab for each.

Don't memorize these. Open the palette, type a few letters of what you want,
and run it. Each entry shows its key, so you pick up shortcuts as you go. The
full list lives on the [Commands](/commands) and [Keybindings](/keybindings)
pages.

![The command palette over a Java file with fold typed: View: Fold, Fold All, Toggle Fold and Fold Level 1 to 5, each with its chord, the highlighted row's description below and a hint line naming the keys](/screenshots/docs/command-palette.png "Type a few letters in the palette and the matching commands come up with their keys.")

If you don't yet know *which* picker holds the command, file or symbol you
want, run **Search Everywhere** and type its name. See
[Navigation & search](/docs/navigation#search-everywhere).

If you'd rather browse than recall, there is also a **menu bar** (File / Edit /
Find / View / Navigate / Code / Run / Git / Tools / Window / Help) built over
the same commands, with each item showing its current keybinding. Its **Help**
menu links to the documentation for the version you are running, and has
**Keyboard Shortcuts** (every command's keys, searchable and changeable) and
**Welcome Page**. F10 opens the menu bar in the Emacs and IntelliJ IDEA keymaps
and Ctrl+Shift+F10 in the others; see
[Menus and buttons from the keyboard](/docs/keymaps#menus-and-buttons-from-the-keyboard).

A command that can't run right now stays listed and dimmed rather than
disappearing, whether its feature is switched off or there is nothing for it to
act on. Hover a dimmed row and it tells you which, and names the command that
would fix it. The same holds in the menu bar, the right-click menus and the
drop-downs: hovering a grayed row shows the reason, a screen reader hears it
with the row, and choosing the row says it in the status bar. (On macOS the menu
bar is the system's, and a row there is only disabled.)

## Make it yours

- **Themes & fonts**: open Settings and go to Appearance. Editora Light and Dark
  are the default pair. **System** follows your system's light or dark
  setting, a High Contrast pair reads at 7:1 or better, and more themes and
  five bundled fonts are in the pickers. See [Themes & fonts](/docs/themes-fonts).
- **Keymap**: switch keymaps live in **Settings → Keymaps**, or rebind
  individual commands. See [Keymaps & keybindings](/docs/keymaps).
- **Projects**: on by default. *Project: Open Folder…* (`C-x C-p` in the Emacs
  keymap) opens a folder as a workspace, each in its own window, and **New
  Project From Template** scaffolds one from scratch. See
  [Projects, windows & files](/docs/workspace).

## Turning on the bigger features

**Off until you turn them on** are the features that run code or reach the
network. Enable the ones you want in Settings:

- **[Plugins](/docs/plugins)**.
- **[AI assistance](/docs/ai)** and the **[MCP server](/docs/mcp)**.
- **[Settings sync](/docs/settings-sync)**, to share snippets, abbreviations,
  templates, your dictionary, and the shortcuts you changed between computers
  (which keymap you use is not synced).

**On, but dormant until their tool is installed** is the larger group:
**[Git](/docs/git)**, **[GitHub](/docs/github)**,
**[language servers](/docs/lsp)**, **[debugging](/docs/debugging)**,
**[Mermaid](/docs/markdown#mermaid-diagrams)**,
**[diagrams](/docs/diagrams)**, and **[Typst](/docs/typst)** are enabled by
default, and their buttons and panels appear once Editora finds the command they
need (`git`, `gh`, a language server, a debug adapter, `mmdc`, and so on). A file
whose language server is missing shows a banner that offers to install it. Run
*View: Doctor* to see what was found; see
[Doctor](/docs/doctor).

In a folder you have not [trusted](/docs/workspace#trusted-folders), a language
server that would have to run the project's build to load it waits: Java starts
without its Maven or Gradle build, and the Kotlin, Scala, Rust, Ruby, C# and
Clojure servers start once you click **Trust Folder**.

Projects, the **[HTTP client](/docs/http-client)**,
**[HTML preview](/docs/html#live-preview)**, and
**[Personal notes](/docs/bookmarks-notes)** are on from the start and need
nothing else.
