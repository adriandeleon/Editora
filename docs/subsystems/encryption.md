# Encrypted files

Why this design: [ADR 0016](../decisions/0016-encrypted-files.md). This page is the map of the code.

## Packages

| Where | What |
| --- | --- |
| `crypto/Gpg` | The gpg command line: `resolve` (setting, PATH, GPG Suite / Gpg4win / Git for Windows), `keys`, `inspect` (who a message is for, `--list-only --list-packets`), `decrypt`, `encrypt`, `encryptSymmetric`, `clearSign`, `detachSign`, `verify`, `verifyDetached`, `importKeys`, `decryptToFile`. Every run adds `--batch --no-tty --status-fd 2`. |
| `crypto/GpgStatus`, `GpgFailure` | Status lines → facts (`ENC_TO`, signatures, error codes) → one `GpgFailure.Kind` per thing the UI says. |
| `crypto/KeyListing`, `GpgKey` | `--with-colons` → keys: validity, capabilities, secret here or not, subkeys (a message names encryption *subkeys*; `GpgKey.matches` maps them back). |
| `crypto/EncryptedFiles`, `editor/EncryptedNames` | Names (`notes.md.gpg` → inner name `notes.md`, which `LanguageRegistry` and `FileIcons` use) and bytes (`sniff`: armored message, other armor, binary session-key packet). |
| `crypto/PacketInfo`, `GpgIdFiles`, `Pinentry` | Recipients from a packet listing; folder defaults in `.gpg-id` (the `pass` format); which pinentry gpg-agent uses and whether it needs a terminal. |
| `editor/EncryptionState`, `BufferEncryption` | Per buffer: mode, recipients, armor, kept passphrase, signature, lock reason; the locked placeholder and the banner slot. `isEncrypted()` is the privacy guard's question. |
| `ui/EncryptionCoordinator` | Opening (locked loads, decrypting), saving (`sealFor`, `Seal`, `saved`, `beforeSave`, `beforeSaveAs`), the commands, `confirmPlaintextOut`. |
| `ui/EncryptionViews`, `RecipientPicker`, `PassphrasePrompt` | The chip text, locked placeholder, banners and details card; the picker card; the prompt card. |
| `plugin/RetiredPlugins` | Removes `gpg-tools` at startup and keeps it from ever loading again. |

## Open

1. `FileWorkflowCoordinator.prepareLoad` sees an encryption extension → `EncryptionCoordinator.lockedLoad` reads
   the bytes; if they are an encrypted message it returns `PreparedLoad.locked(...)` (no text; `sourceBytes` =
   ciphertext; fingerprint of the ciphertext). The save-time conflict check and external-change detection use
   these and never decrypt.
2. `applyPreparedLoad` hands a locked load to `applyLocked`: placeholder, state, and an `inspect` + `keys` run
   in the background to say who it is for.
3. An interactive open (`MainController.finishAsyncOpen` → `opened`) decrypts at once; a session restore does
   not. A reload of a decrypted buffer (the file changed on disk) decrypts again.
4. `decrypt` → `withSecret` (agent first; Editora's prompt for passphrase-only files, when Settings say so, or on
   `NO_PINENTRY`) → `prepareDecrypted` (the normal charset/BOM/line-ending decode of the plaintext, remembered as
   the file on disk: ciphertext fingerprint, `sourceBytes` null) → `applyDecrypted`.

## Save

`captureSave` asks `sealFor(buffer, target)`: null (plain), a `Seal`, or `Seal.REFUSED` (banner shown). The
seal runs at the top of `writeAsync` (and the administrator save) on the save worker; `SaveRequest.diskBytes()`
is what every later step writes, hashes and backs up. A seal that fails throws `SealFailure` → `saveFailed`
banner; one that produces no bytes fails too. `completeSave` → `saved` clears a pending recipient change,
remembers recent recipients, and turns the buffer into an ordinary file after a confirmed Save As to a plain
name.

## The privacy guard

Each surface checks `buffer.encryption().isEncrypted()` (or, for a closed file, `EncryptedNames.extensionOf`):
`RecoveryCoordinator`/`RecoveryPolicy.Skip.SENSITIVE`, `HistoryCoordinator.record*`, `BookmarkCoordinator` and
`DebugCoordinator` persistence (no line text), `NotesCoordinator`, `LspCoordinator.syncBuffer` (unless
`encryptionLanguageServers`), `AgentCoordinator`, `WindowMcpBridge`, `AiCoordinator`, `EditorBuffer.hasPreview`
and `MarkdownPreview.setDiagrams`, `GitCoordinator`/`GitBlameCoordinator`, `SearchCoordinator.replaceClosedFile`,
`EncodingCommands.reopen`, and `ExportCoordinator`/`ExternalToolCoordinator` through `confirmPlaintextOut`.
Plugins can ask `ActiveEditor.isEncrypted()`. A new feature that copies buffer text anywhere must ask too.

## Tests

`GpgParsingTest` (real gpg 2.4 output), `GpgIntegrationTest` and `EncryptedFilesFxTest` (a real gpg in a
throwaway home: `GpgTestHome`, made under `/tmp` because gpg-agent's socket path is length-limited),
`RetiredPluginsTest`. The FX test stands in for the picker (`EncryptionCoordinator.picker`) and the prompt
(`passphraseAsker`); gpg is never mocked.
