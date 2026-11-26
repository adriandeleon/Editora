---
title: Settings sync
description: Keep snippets, abbreviations, templates, your personal dictionary, and your shortcuts the same on every computer, through a private Git repository you own.
category: Customization
order: 2
beta: true
---

Settings sync keeps five kinds of personal data the same on every computer you
use Editora on. The data travels through a **Git repository you own**; there is
no Editora account and no Editora server. It is **off by default**.

## What is synced

| Data | File in the [config folder](/docs/configuration) |
| --- | --- |
| Snippets | `snippets/<language>.json` |
| Abbreviations | `abbreviations.json` |
| Templates | `templates/<id>.json` |
| Personal dictionary | `dictionary.txt` |
| Shortcuts | part of `settings.json`; `keybindings.json` in the repository |

Each of the five has its own switch under **What to sync**, so you can leave
one out.

Nothing else is synced. Your other preferences in `settings.json` (including
which keymap you use), macros, themes, plugins, and session state stay on the
computer they were made on. The **What to sync** card says so too.

### Shortcuts

**Shortcuts** syncs the key bindings you changed on the
[Keymaps page](/docs/keymaps#rebinding-commands): every rebind, every shortcut
you removed, and every reset. It covers the changes you made in **every
keymap**: Editora keeps one set per keymap for every system, so a chord you
change on a Mac is changed on Linux and Windows too (Ctrl on one is Cmd on the
other in the keymaps with a macOS form). A `keybindings.json` from this version
is not read by an older Editora.

Which keymap a computer uses is its own choice and is not synced. A computer
that uses another keymap keeps your changes to the Emacs keymap, say, and uses
them when you switch it to Emacs.

A shortcut for a macro, an external tool, or a run configuration is synced
like any other, but the macro, tool, or run configuration itself is not. On a
computer that does not have it, the shortcut does nothing.

## Setting it up

You need `git` installed and a Git repository that is either empty or already
used for sync by another of your computers. Use a **private** repository:
snippets and templates can contain private text.

1. Open **Settings → Sync**, or run **Settings Sync: Set Up…** (`sync.setup`).
2. Paste the repository's URL into **Repository**, for example
   `git@github.com:you/editora-sync.git`, and check the **Branch**.
   When the [GitHub CLI](/docs/github) is installed and signed in, **Create on
   GitHub…** asks for a name, creates a private repository, and fills the URL
   in.
3. Click **Connect**.

![The Sync page of the Settings window: a Repository URL field, a Branch field with a Connect button, a Status row reading Not connected with Sync Now and Open Sync Folder buttons, and the first What to sync switches for snippets, abbreviations, templates and the personal dictionary](/screenshots/docs/settings-sync.png "Settings → Sync before connecting.")

The **Repository** field does not take a URL with a password or token in it
(`https://me:TOKEN@host/…`, or a token used as the user name): the field turns
red, says why, and nothing is saved. Such a URL would sit in plain text in your
settings. Sign in through a Git credential helper instead (for GitHub,
`gh auth setup-git`), or use an SSH URL. A **Branch** name that Git would
refuse, such as `a..b`, turns that field red the same way and is not saved.

Connect looks at the repository before anything changes. If it already holds
data, a dialog states how many entries would arrive on this computer, how many
this computer would send, and how many differ on both. Nothing is removed on
either side by connecting. While it looks, **Cancel** stops waiting; nothing
changes on either side.

If Connect cannot go ahead (the repository or branch is missing, the repository
cannot be reached, or GitHub reports an error), the **Status** row says why. The
reason stays there until you edit the repository, try again, or disconnect.

Repeat the same steps on each other computer, with the same URL.
**Disconnect** stops syncing on this computer and leaves the repository and
your local files as they are.

## When it syncs

With **Sync automatically** on, Editora syncs:

- a few seconds after startup,
- about half a minute after you change a snippet, an abbreviation, a template,
  the dictionary, or a shortcut (a rebind, a reset, or a keymap switch), and
- at the interval set on the page (15 minutes by default), while an Editora
  window has the focus.

**Settings Sync: Sync Now** (`sync.now`) and the **Sync Now** button run one on
demand. A file you edit by hand in the config folder is picked up by the next
timed sync.

While a sync runs, **Cancel** on the Sync page stops it, up to the moment it
starts writing the merged files on this computer. A cancelled sync changes
nothing here, and the next one starts afresh. Once the files are being
written it is too late: the page says so and the sync finishes.

The **Status** row starts with a pill:

- green **Connected**, with when it last synced (*synced 5 min ago*, or *not
  synced yet*);
- amber **Not connected**: sync is not set up or is turned off, another
  Editora on the same config folder does the syncing, or the repository could
  not be reached and the next sync retries by itself;
- red **Sync failing**: it needs you, for example because the sign-in was
  refused, the repository is not there, `git` is missing, or a large removal
  waits for **Sync Anyway**.

While a sync runs, the pill adds *syncing…*. The line under it gives the
reason a sync failed, or what the last one did, for example "3 received,
1 sent". Changes that arrive are applied to every open window without a
restart.

## How changes merge

Changes merge **entry by entry**, not file by file: one snippet, one
abbreviation, one dictionary word, one shortcut. Two computers that each added a
snippet to the same language both end up with both, and two computers that each
rebound a different command both keep both rebinds. A template is merged as a
whole file.

When the **same entry** was changed on two computers, the computer that syncs
keeps its own version. The other version is not lost: it stays in the
repository's Git history. The status row names the entries this happened to.

A file that cannot be read on either side, or that was written by a newer
Editora, is left alone on both sides and named in the status row.

A sync that would remove more than half of a category that holds ten or more
entries stops and asks first: the Sync page shows **Sync Anyway**. This keeps a
damaged file on one computer from emptying the others.

## Backups

Before a sync replaces or deletes a file in your config folder, it copies that
file to `sync/backups/<timestamp>/` in the config folder. Shortcuts it replaces
are saved there as `keybindings.json`. The last ten sets are
kept. **Open Sync Folder** on the Sync page opens the folder.

## Credentials and failures

Editora signs in to the repository with **your own Git credentials or SSH
key**, the same ones `git` uses in a terminal, and stores none of its own.

When the saved repository URL has a password or token in it, for example one
written into `settings.json` by hand, the Sync page shows a warning above the
repository with two buttons. **Remove from URL** saves the URL without the
secret; the next sync signs in with your credential helper or SSH key, so set
one up first. **Keep for now** hides the warning until Editora starts again.
**Export Configuration…** always leaves such a secret out of the archive.

With more than one account in the [GitHub CLI](/docs/github) (a personal and a
work account, switched with `gh auth switch`), sync does not depend on which
one is active. It finds out which of your `gh` accounts can read the
repository, remembers that account's name on this computer, and signs in as it
from then on; the **Status** row on Settings → Sync names the account. This
applies to an `https://` repository on a host `gh` is signed in to. An SSH URL
or another credential helper signs in the way your `git` does. Either way Git
asks `gh` for the token, and Editora never sees or stores it.

A Git configuration that rewrites the repository URL (an insteadOf rule, for
example one that turns GitHub HTTPS URLs into SSH ones) does not affect sync:
the repository is recognised by the URL you entered.

An automatic sync never asks for a password or a passphrase and never opens a
dialog. If it fails, the reason appears once in the status bar and a
**Sync ⚠** segment stays there until a sync succeeds; clicking it opens
Settings → Sync. A sync you start yourself with Sync Now may use your Git
credential helper.

If a sync cannot reach the repository, nothing changes locally and the next
sync tries again. A sync interrupted part-way is repeated in full by the next
one.

When two Editora processes share one config folder (see
[`--new-instance`](/docs/cli#one-editor-not-two)), only the first one syncs.

## Commands

| Action | Command | Default key |
| --- | --- | --- |
| Open Settings on the Sync page | `sync.setup` | (palette) |
| Sync once | `sync.now` | (palette) |
| Turn automatic syncing on or off | `sync.toggleAuto` | (palette) |

## Related

- [Snippets & templates](/docs/snippets-templates) and
  [Configuration](/docs/configuration) describe the files that are synced.
- [Keymaps & keybindings](/docs/keymaps) describes rebinding a command.
- **Export Configuration…** makes a one-off archive of the whole config
  folder; see [Export and reset](/docs/configuration#export-and-reset).
