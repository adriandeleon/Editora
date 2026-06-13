---
title: Getting Started
description: Install Editora, open your first file, and learn the keyboard-first essentials.
category: Getting started
order: 2
---

Install Editora, open a file, and learn the handful of keys that make it feel
fast.

## Install

Grab a native installer for your platform from the
[releases page](https://github.com/adriandeleon/Editora/releases) (or use the
OS-detected button on the [home page](/)):

- **macOS** — `.dmg` (Intel & Apple Silicon)
- **Windows** — `.msi` (x64)
- **Linux** — `.deb` (x64 & arm64)

Installers bundle their own Java runtime — nothing else to install. They're
currently **unsigned**, so on first launch macOS Gatekeeper (right-click →
*Open*) or Windows SmartScreen will warn you once.

### Or run from source

Requires JDK 25+. The Maven wrapper is bundled.

```bash
git clone https://github.com/adriandeleon/Editora.git
cd Editora
./mvnw javafx:run           # run the app
./mvnw -Pfatjar package     # build a self-contained jar
java -jar target/Editora-*.jar
```

## First steps

Editora is keyboard-first — these get you moving:

| Do this | Key |
| --- | --- |
| Open the command palette (everything is here) | `M-x` |
| Find / open a file by path | `C-x C-f` |
| Save | `C-x C-s` |
| Jump to a symbol in the file | `M-g i` |
| Switch between open files | `C-x b` |
| Toggle a bookmark | `C-c m` |

See the full [keybindings](/keybindings) and the complete
[command list](/commands). Every command in the palette shows its key, so you
can learn shortcuts as you go.

## Make it yours

- **Themes & fonts** — open Settings (the gear, or "Settings" in the palette) →
  Appearance. Six editor themes, five bundled fonts.
- **Projects** — off by default; enable in Settings, then `C-x C-p` opens a
  folder as a workspace.
- **Config & overrides** — preferences and keybindings live in a config folder;
  see [Configuration](/docs/configuration).
