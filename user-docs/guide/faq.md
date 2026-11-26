---
title: FAQ
description: Licensing, telemetry, platforms, signing, which features are on by default, external tools, startup speed, and customization.
category: Help
order: 1
---

## Is Editora free and open source?

Yes, it's free and released under the MIT License. Source is on
[GitHub](https://github.com/adriandeleon/Editora).

## Does it phone home or collect telemetry?

There are no accounts, no telemetry, and no analytics. Nothing about you or your
code is collected or sent anywhere as a matter of course.

There are two exceptions:

- **The update check.** Once a day at startup Editora asks GitHub whether a
  newer release exists. It sends nothing about you or your files, and it's a
  checkbox in **Settings → Advanced → Updates** if you'd rather it didn't. **About
  Editora** shows the answer under the version ("You are up to date." or the
  release available), when the last check answered, and a **Check now** link
  that asks again.
- **The AI features**, which are off by default. If you turn them on, the text
  you act on is sent to whichever endpoint you configure (a local model, or a
  provider you supply a key for). That is how the feature works, and it is the
  one part of Editora that transmits your code, so it stays opt-in.

[Settings sync](/docs/settings-sync), also off by default, sends your snippets,
abbreviations, templates, dictionary, and the shortcuts you changed to a Git
repository that you name and own, and nowhere else.

Plugins you install run with full access and can do their own thing (see below).

## Which platforms are supported?

macOS (Intel & Apple Silicon), Windows (x64), and Linux (x64 & arm64).
Windows-on-ARM runs the x64 installer under emulation.

## Do I need Java installed?

Not for the regular platform installers; they bundle their own Java runtime.
The portable fat jar does need JDK 25 (`java -jar`).

Running your own Java code needs a JDK, but not necessarily JDK 25. A
single-file program whose `main` is `public` runs on JDK 11 or newer; a compact
source file (a top-level `void main`) or a `main` that is not `public` needs
JDK 25. A project can name its own JDK (in `.jkit.toml`, `.sdkmanrc` and the
like); otherwise Editora uses the Default JDK in **Settings → Build Tools**,
else the `java` on your `PATH`. See
[Running programs](/docs/run-debug#running-files) and
[The project's JDK](/docs/build-tools#the-projects-jdk-maven-gradle-sbt-and-graalvm).

## The installer says it's from an unidentified developer.

That is the Windows installer, which is unsigned. SmartScreen stops the `.msi`
until you allow it, and the administrator prompt that follows (it installs for
every user) names the publisher as *Unknown*. The steps are in
[Troubleshooting](/docs/troubleshooting#a-launcher-is-blocked-on-first-run).

The macOS app is signed and notarized by Apple: macOS asks once whether to open
an app downloaded from the Internet, and nothing more. The Linux downloads
carry OpenPGP signatures, and every release file has a build provenance
attestation: [Checking a download](/docs/getting-started#checking-a-download).

## Which features are off by default?

Fewer than you might expect: only the few that run code or reach the network,
such as plugins and the AI features. Everything else is on. The features that
drive an external tool, such as Git or a language server, stay out of sight
until Editora finds that tool, so a fresh install is uncluttered and installing
the tool later makes the feature appear on its own.

[Turning on the bigger features](/docs/getting-started#turning-on-the-bigger-features)
lists both groups, with what each feature needs. Projects are on from the start
too; **Settings → Workspace** turns them off.

## What happens to unsaved changes if Editora crashes?

While a tab has unsaved changes, Editora keeps a recovery copy of its text in
the config folder, untitled buffers included. If the editor does not close
normally (a crash, a kill, a logout, a power cut), the next launch lists those
copies and offers to restore them as unsaved tabs. Restoring never writes to
your files. See [crash recovery](/docs/undo-history#crash-recovery).

Saved versions are a separate matter: every save that changes the file is
recorded in [Local History](/docs/workspace#local-file-history).

## Can I use the same snippets and templates on several computers?

Yes, with [Settings sync](/docs/settings-sync) (Beta, off by default). It keeps
snippets, abbreviations, templates, your personal dictionary, and the
shortcuts you changed (in every keymap) the same on every computer through a
private Git repository you own. Which keymap you use is not synced, and neither
are your other preferences, macros, and themes; to move those, copy the
[config folder](/docs/configuration) or use **Export Configuration…**.

## How do I add a language, snippet, or dictionary word?

Languages: the grammars ship with Editora, and there is no way to add one of
your own. A file whose name Editora doesn't know opens as plain text; click the
language in the status bar, or run **Buffer: Set Language…**
(`buffer.setLanguage`), to read it as one of the
[supported languages](/docs/languages#supported-languages). To ask for a
language, [open an issue](https://github.com/adriandeleon/Editora/issues).

Snippets: drop a `snippets/<language>.json` in your config folder (they override
the bundled ones). Spell-check: "Add to Dictionary" saves to `dictionary.txt`,
and you pick a language per file with "Spell Check: Set Language…". See
[Configuration](/docs/configuration) and
[Highlighting, completion & spelling](/docs/languages).

## Do I need to install anything else?

No, for editing, previews, and everything that works on plain text. Yes, for
the features that drive an external tool, and by design: Editora shells out to
the `git`, `ripgrep`, language servers and diagram tools already on your
machine instead of bundling its own copies, so you get your versions and your
configuration.

Nothing breaks when a tool is missing. The feature stays hidden until it
appears. Run **Doctor** (`view.doctor`) to see what was found, what's only
half-configured, and what's missing, with an install button on the ones Editora
can fetch for you. See [Doctor](/features/doctor).

## Is this an Emacs clone? Do I need to know Emacs?

No, and no. Emacs is the default keymap because the editing model is inherited
from Emacs: a kill ring, a mark ring, `C-x r` rectangles, narrowing,
query-replace, `C-u` prefix arguments. If that's your muscle memory, most of it
is already here, and [Emacs heritage](/features/emacs-heritage) lists what
carried over and what deliberately didn't.

If it isn't, pick CUA, Sublime Text, VS Code or IntelliJ IDEA in
**Settings → Keymaps** and you'll never meet a `C-x` chord. There's no Elisp
and no modal editing, and every command is discoverable by name in the palette
rather than something you have to know in advance.

## How does it compare to VS Code or IntelliJ?

There's a page for that: [how Editora compares](/compare). It covers startup
measurements taken on one machine with one method, where each editor is
stronger, and a section on when Editora is probably the wrong choice for you.

## Can I rebind keys or use a different keymap?

Yes. In **Settings → Keymaps** you can switch between Emacs (default), CUA,
Sublime Text, VS Code, and IntelliJ IDEA, and rebind any command with the
built-in editor. Changes apply live and are saved in `settings.json`. See
[Keymaps & keybindings](/docs/keymaps).

## Are plugins safe?

Plugins are off by default and run with full access (no sandbox), like a VS Code
or IntelliJ extension. The registry adds defenses (a capability disclosure
before you enable one, sha-256 verification of downloads, and an Ed25519-signed
index), but you should still only install plugins you trust. See
[Plugins](/docs/plugins).

## Where are my settings and how do I reset them?

In a config folder (`~/.editora/` by default). Its live path is in **About
Editora**. You can export it or reset to defaults from **Settings → Advanced**,
and run a throwaway config with `--dev` or `--config-dir`. A reset first saves
the previous preferences as `settings.json.before-reset-<date>.bak` in that
folder. See [Configuration](/docs/configuration).

## Is it slow to start? It's a Java app.

That's the usual assumption, so here are measurements, taken on Editora 0.22.0
in October 2026. On one 14-inch MacBook Pro with an Apple M5, opening a single
file, Editora had its window up in
about 0.52 seconds, against 0.59 for VS Code and 2.2 for IntelliJ IDEA. Opening
a real project folder, it took about 0.58 seconds against 0.66 for VS Code and
3.2 for IntelliJ. A lead of under a tenth of a second over VS Code is real but
not one you will feel, and these are warm relaunches timed to the window
appearing, not cold boots or indexing. The machine, the method, the caveats,
and the run-to-run spread are all on [how Editora compares](/compare).

The native builds bake in an AOT class cache, which is worth roughly a third of
the startup time on its own. If startup or editing ever feels slow, see
[Troubleshooting](/docs/troubleshooting#performance).

## Why JavaFX?

It gives one native-feeling, cross-platform codebase with a fast text surface
(RichTextFX), and lets Editora ship a single app per platform without a browser
engine.

Didn't find your answer?
[Open an issue](https://github.com/adriandeleon/Editora/issues).
