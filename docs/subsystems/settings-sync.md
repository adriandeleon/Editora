# Settings sync

Settings sync keeps four kinds of user data the same on every computer that connects to one Git
repository of the user's: snippets, abbreviations, templates and the personal spell-check dictionary.
It is off by default. The rationale for the design is in
[ADR 0012](../decisions/0012-settings-sync-git-transport.md).

## What is synced

| Data | File in the config directory | Merge unit |
| --- | --- | --- |
| Snippets | `snippets/<language>.json` | one snippet, by name (a `"disabled": true` mark is an entry too) |
| Abbreviations | `abbreviations.json` | one abbreviation |
| Templates | `templates/<id>.json` | the file |
| Personal dictionary | `dictionary.txt` | one word |

The repository has the same paths, plus `editora-sync.json` (`{"formatVersion": 1}`), `.gitattributes`
(`* -text`) and a `README.md`. Nothing else is ever written to it: `SyncCategory.owns` decides which
paths belong to a category, and a path outside the four is neither read nor pushed. `settings.json`,
keymaps, macros, themes, plugins and session files are not synced. The `sync*` keys in `settings.json`
describe one installation's connection and must stay local if settings are synced later.

## Pieces

- `sync/SyncMerge` — pure three-way merge of one file. Entries added, changed or removed on one side
  carry over; an entry changed differently on both sides keeps this machine's version and is reported.
  When only one side changed a file its text is taken whole, which keeps the comments of a snippet
  file. The dictionary and the abbreviation store are written in a canonical form (sorted words;
  pretty-printed JSON with line feeds), so equal data is equal bytes on every operating system.
- `sync/SyncEngine` — one fetch, merge, push round. Blocking and JavaFX-free.
- `sync/FileSyncTarget` — the config directory's side: reads the files, and writes changes only if
  the files still hold what the engine read. Files it replaces or deletes are first copied to
  `sync/backups/<timestamp>/` (ten sets are kept).
- `git/QuietGit` — the git runner: hooks off, no end-of-line conversion, no commit signing, and for
  automatic runs no prompt of any kind (`GitSafety.autoFetchEnv`).
- `ui/SettingsSync` — the one service of the process (owned by `WindowManager`): triggers, the worker
  thread, reloading the stores of every window, status reporting.

## One round

The clone lives in `<configDir>/sync/repo`. Nothing but the engine reads it.

1. `git fetch`. A failure ends the round with nothing changed.
2. The working tree is reset to the remote branch. Its files are *theirs*; the live files are *mine*;
   the files of `refs/editora/base` are the *base*.
3. Every file of an enabled category is merged. A file that cannot be parsed on either side, or whose
   store was written by a newer Editora, is skipped: neither copy is touched.
4. If the result differs from the live files it is written (on the FX thread, see below).
5. The merged files are written over the working tree, committed and pushed. A push rejected because
   another machine pushed first restarts the round, three times at most.
6. `refs/editora/base` is moved to the pushed commit.

Git never merges. The base moves only at the very end, so a round interrupted anywhere (crash, quit,
failed push) is repeated by the next one: this machine's edits still differ from the base and are sent
again. Pointing the clone at another repository deletes the base, which makes the next round a first
sync.

**First sync.** With no base, both sides look new: everything on both sides is kept, and a same-named
entry that differs keeps this machine's version. Connect runs `SyncEngine.preview` first and, when the
repository already has data, shows how much would arrive before anything changes.

**Guards.** A repository whose `formatVersion` is newer than `SyncEngine.FORMAT_VERSION` is left
alone. A round that would remove more than half of a category that has ten or more entries stops with
`NEEDS_CONFIRMATION`; the Settings page offers "Sync Anyway". This
is what keeps a truncated file on one machine from emptying every other one.

## Triggers and threads

`SettingsSync` runs a round five seconds after startup, thirty seconds after a local change
(`markDirty`), on the interval timer while a window is focused, and for `sync.now`. Only the primary
instance of a config directory syncs. Rounds never overlap; a request during a round runs one more
afterwards.

`markDirty` is called from `WindowManager.broadcastSnippetsChanged` (with a window as origin),
`broadcastUserDictionaryChanged`, `onSharedStoreChanged`, and from the Settings pages that save or
delete a template or a dictionary word. A change made by hand-editing a file is picked up by the next
timer round.

The engine runs on the `settings-sync` thread. Writing the result happens on the FX thread
(`SettingsSync.LiveTarget`), where the stores themselves save, so a store cannot be writing the same
file at that moment; the "still what I read" check then catches an edit made during the round, which
ends it as `LOCAL_BUSY` and schedules another. After the write: snippets are re-read through
`broadcastSnippetsChanged(null)`, the dictionary through `SharedConfig.reloadUserDictionary`,
abbreviations through `SharedConfig.reloadAbbreviations` followed by `broadcastSettingsApplied`
(each buffer holds a copy of the map), and open Settings windows refresh their Snippets and
Templates pages.

Automatic rounds report a failure once per kind, as a status line, and show the "Sync ⚠" status-bar
segment until a round succeeds. They never open a dialog and never prompt for credentials. A round
the user asked for may use their credential helper.

## Changing it

- A new category: add it to `SyncCategory`, give `SyncMerge.parse` its entries and `assemble` its
  merge, a reload in `SettingsSync.reload`, a `markDirty` call where the store saves, and a setting.
- A change to the repository layout that an older build would misread: bump
  `SyncEngine.FORMAT_VERSION`.
- Tests: `SyncMergeTest` (pure), `SyncEngineTest` (two config directories and a bare repository, real
  git), `SettingsSyncFxTest` (a real window against a second machine).
