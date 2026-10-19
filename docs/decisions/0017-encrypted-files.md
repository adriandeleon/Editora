# 0017 — Encrypted files: the gpg CLI, encryption as a property of the file, sensitive buffers

**Status:** Accepted. Replaces the `gpg-tools` registry plugin, which is retired.

## Context

GnuPG support was a registry plugin (`gpg-tools`): a tool window of buttons that ran `gpg` on the buffer's text
and replaced the text with the result. Its model — "encryption is something you do to the text" — made the
dangerous path the easy one: Decrypt put plaintext in the buffer and the next Ctrl+S wrote it to disk; a `.gpg`
file opened as a hex dump; nothing stopped decrypted text from flowing into crash recovery, Local History,
bookmarks, a language server, an agent or an MCP client like any other buffer.

Alternatives considered for the engine: a Java OpenPGP library (Bouncy Castle `bcpg`, PGPainless). Editora
already ships `bcprov` for SSH, so the dependency is close — but the user's keys, trust database, smartcards
and passphrase cache all live in GnuPG, and a library would have to reimplement reading them (or ask the user
to export secret keys into Editora). The same reasoning as [0002](0002-native-cli-git.md) applies: the user's
own tool, configured the way they already use it.

## Decision

- **The `gpg` command line, on this machine only**, bytes in and bytes out (`ProcessRunner.runBytesWithInput`):
  plaintext never touches a temporary file. Decisions are taken from `--status-fd` lines, never from gpg's
  translated messages. A remote (SFTP/WSL/container) encrypted file is read and written through the VFS as
  ciphertext; decryption happens here, where the keys are.
- **Encryption belongs to the file, not to the text.** Opening `notes.md.gpg` (`.gpg`, `.pgp`, `.asc` whose
  bytes are an encrypted message) gives a *locked* load — no text, the ciphertext kept — and the window's
  `EncryptionCoordinator` decrypts it; the inner name decides the language. Saving encrypts again for the same
  recipients and in the same format: the save request carries a `Seal`, sealed on the save worker before the
  conflict check, the staging file or a backup sees any bytes. A save that cannot be encrypted (a recipient's
  key missing, no passphrase, gpg failing) writes nothing and says why in a banner. The rule is by name: a target
  with an encryption extension is written encrypted; Save As to a plain name is confirmed first.
- **Restored tabs stay locked** until the user decrypts them, so a session restore never opens a run of
  passphrase prompts.
- **Passphrases:** gpg-agent's own pinentry by default (Editora never sees the passphrase). Editora's own
  prompt, by loopback (`--pinentry-mode loopback --passphrase-fd 0`, the passphrase as the first line of stdin),
  when the agent has no graphical pinentry, when Settings say so, and always for passphrase-only files — whose
  passphrase the buffer keeps (wiped on close) so a save does not ask again.
- **An encrypted buffer is sensitive.** `BufferEncryption.isEncrypted()` is the one question every surface asks:
  no crash-recovery copy (one taken before it was encrypted is deleted), no Local History (its revisions would be
  its text), no line text in bookmarks/breakpoints, no personal notes, no language server unless Settings allow
  it, no agent / MCP / AI access, no diagram renders (they write their source to temporary files), no Git gutter
  or blame, no closed-file Replace in Files rewrite. Export, print and external tools ask once per tab.
- **The plugin is retired, not kept alongside**: `RetiredPlugins` removes an installed copy at startup (carrying
  over its gpg path and last recipient), and the id is refused by discovery, install and the registry listing.
  Signing (clear-sign, detached, verify) moved into core with it.

## Consequences

- Without GnuPG installed, an encrypted file opens as a locked tab that says how to install it (Doctor has a
  GnuPG row and a passphrase-prompt row).
- An encrypted file has no crash recovery: unsaved edits are lost if Editora crashes. The details card says so;
  autosave narrows the gap.
- Local History records nothing for encrypted names. Storing ciphertext revisions was considered and rejected:
  History is text-based, and restoring a ciphertext revision into an open, decrypted buffer would replace its
  text with armor.
- Mermaid blocks in an encrypted Markdown file show as code in the preview.
- Files larger than 8 MB (`MAX_DECRYPT_BYTES`) are not decrypted into an editor; Decrypt to File writes them.
- The CI Linux lanes run the gpg tests against a real gpg in a throwaway `GNUPGHOME` (an unexpected skip fails
  the build there).
