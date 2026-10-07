# Crash recovery of unsaved edits

Editora keeps a copy of every unsaved buffer's text on disk while it runs, and offers the copies back on the
next launch when the previous run ended without its windows being closed (a crash, `kill`, an OOM kill, a
desktop logout, power loss). It is the only place unsaved text is written: the session file holds paths
only, and Local History records saves.

Code: [`com.editora.recovery`](../../src/main/java/com/editora/recovery) (pure, no JavaFX) and
[`ui/RecoveryCoordinator`](../../src/main/java/com/editora/ui/RecoveryCoordinator.java) +
[`ui/RecoveryOfferWindow`](../../src/main/java/com/editora/ui/RecoveryOfferWindow.java).

## On disk

```
<configDir>/recovery/
  <session>/            one per Editora process; owner-only permissions where the platform has them
    session.lock        held exclusively by that process for its whole life
    <buffer-uuid>.rec   one per unsaved buffer
```

A record (`RecoveryCodec`) is a magic line, one line of JSON metadata, then the text:

- the file's storable path (`Vfs.toStorableString`, so a remote file keeps its `sftp://` URI), or none for an
  untitled buffer, plus the tab title and an untitled buffer's suggested name;
- what a save would have written with: charset label, whether a byte-order mark is written, line ending;
- the disk state the edits were based on — the buffer's `DiskSnapshot` (modified time, size, SHA-256);
- when the copy was taken, the caret offset and the window key;
- the text with `\n` line ends, as UTF-8 — or as raw UTF-16 units when it holds an unpaired surrogate, which
  UTF-8 cannot carry — with its byte length and CRC-32C in the metadata.

A record is written to `<id>.rec.tmp`, forced to the device, renamed over `<id>.rec`, and the directory is
forced where the platform allows. A `.rec` file is therefore a whole record or the whole previous one. One
that still does not read back whole (length or checksum mismatch, unknown shape) is **never offered and
never deleted**: it is logged, counted in a status-bar error at start-up, and left where it is.

Nothing is created until a buffer is unsaved: no directory, no thread, no shutdown hook.

## Whose records are whose

`session.lock` is an OS file lock, released by the operating system when its process ends however it ends —
the same reasoning as [`InstanceLock`](../../src/main/java/com/editora/config/InstanceLock.java). "The lock
can be taken" is the only sign used that a session is dead; no process id is stored.

- A process writes and deletes only inside its own session directory.
- At start-up `RecoveryStore.claimOrphans` tries the lock of every other session. Held (or, in tests, held
  through another channel of the same JVM): the session is alive and is not listed, restored or deleted.
  Taken: the session is dead, and this process **keeps** the lock, so a third process started at the same
  time does not offer the same records.
- A file system that refuses locks cannot prove a session dead; such sessions are treated as alive, and
  their records stay on disk without being offered.

## When a copy is taken

Never per keystroke, and the buffers are not subscribed to. While a window has an unsaved buffer,
`RecoveryService` posts `RecoveryCoordinator.tick` to the FX thread about once a second (from its own
thread, so no animation timer keeps the pulse loop alive). A tick reads, per unsaved buffer, the document
version and compares it with the one last copied. When a copy is due (`RecoveryPolicy.due`) it takes
`EditorBuffer.getContent()` — the buffer's cached document snapshot when another consumer already built it —
and hands the string to the service. Encoding, the checksum, the write and the fsync run on the service's
one worker thread, and writes are coalesced per buffer: only the newest text queued is written.

| Document length | Copied |
| --- | --- |
| up to 1M chars | at the first tick after the text stops changing (a pause of 1–2 s), and at least every 10 s while typing continues |
| above 1M chars | as above, but no more often than every 2 s per 1M chars, and at least every twice that |
| above 16M chars (`RecoveryPolicy.MAX_CHARS`) | not at all: the status bar says so once, and the last copy taken below the limit is kept |

A newly unsaved buffer is copied at the first tick. Not copied at all: a clean buffer, a loading shell, a
truncated huge-file load, a trimmed or followed log (`RecoveryPolicy.skip`) — the same buffers the save path
refuses — and tabs that are not editor buffers (image, hex, PDF, diff and merge views).

When the process is told to stop without the windows being closed (`SIGTERM`, a logout), the shutdown hook
asks the FX thread for one last copy of whatever changed, waits briefly, and lets the worker finish.

## When a copy goes away

- The buffer becomes clean: saved, or edited back to its saved text (`dirtyProperty` listener).
- Its tab is closed (`MainController`'s tab listener — after the close prompt was answered).
- Its window is closed and the user answered for every unsaved buffer: `MainController.persistSessionForClose`,
  which both the window close request and an approved quit reach. A normal quit thus leaves no record, and
  the shutdown hook removes the empty session directory.
- The setting is turned off (see below).

`MainController.disposeWindow` on its own (`RecoveryCoordinator.dispose`) stops copying and deletes nothing:
a window torn down without the user having chosen keeps its copies for the next launch.

## The offer

The first editor window of a launch owns the offer (`RecoveryService.claimOffer`, from
`MainController.startup`; the standalone `--diff-ui` window never offers, and leaves the records for the next
ordinary launch). If dead sessions left records,
a modeless `RecoveryOfferWindow` lists them — name, location, when the copy was taken, and a warning when the
file changed on disk since (`RecoveryStore.diskState`: size, then the recorded SHA-256 when there is one — so a touched
but identical file is not flagged — else the modified time), is gone, or is remote. Restore and Discard apply to the selection or to all.

- **Enter** is Restore All; **Escape** and the window's close button are Decide Later, which changes nothing
  — the records are offered again by `file.recoverUnsavedEdits` and by the next launch.
- **Discard** deletes only after a confirmation whose default is to keep.

**Restoring never writes the user's file.** `RecoveryCoordinator.restoreInto`:

- file not open: a new buffer bound to the path, with the recorded charset, BOM choice and line ending, and
  with the **recorded** disk snapshot rather than today's — so a file that changed in the meantime is caught
  by the ordinary external-change check and by the save path, as if the editor had never stopped. The buffer
  is `markUnsaved()`.
- file already open (typically reopened by the session restore): once its load has landed, the recovered
  text replaces the document as one undoable edit. If that buffer has unsaved changes of its own, or cannot
  take text, the recovered text opens in a separate untitled buffer instead.
- remote file whose connection is not open, or a path this machine cannot express: an untitled buffer named
  after the file.

The restored buffer immediately gets a record in the current session, and the old record is deleted only
once that one is on disk (`RecoveryService.discardOnceSaved`), so a second crash at any point loses nothing.
Records are restored into the window that shows the offer, whichever window they came from.

## Setting and commands

- `Settings.crashRecovery` (default on; schema v112, additive). Settings → Workspace, and
  `view.toggleCrashRecovery`. Off: each window drops its copies on its next tick and takes none. Leftovers
  from an earlier run are still offered; restoring one while recovery is off deletes its record.
- `file.recoverUnsavedEdits` reopens the offer.

## Tests

`RecoveryCodecTest`, `RecoveryStoreTest`, `RecoveryServiceTest`, `RecoveryPolicyTest` (pure) and
`CrashRecoveryFxTest`, which stands in for a dead process with `RecoveryService.abandon()` — locks dropped,
nothing cleaned up — followed by a second window fixture on the same config directory.
