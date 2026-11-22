# 0015 — AI API keys in the operating system's keychain

**Status:** Accepted

## Context

The AI providers' API keys (Anthropic, OpenAI-compatible, LM Studio / Bionic) were kept in `settings.json` as plain
text. The file is owner-only, but it is the file people back up, sync with dotfiles and attach to bug reports, and a
key is a billable credential. Export already leaves the keys out by default. The editor ships JNA for the terminal.

## Decision

- Keys live in the operating system's keychain, reached through JNA with no new dependency
  (`com.editora.secrets`): the macOS Keychain (Security framework generic passwords), the Windows Credential
  Manager (`CredReadW`/`CredWriteW`/`CredDeleteW`, generic credentials kept for the local machine) and the
  freedesktop Secret Service through libsecret (GNOME Keyring, KWallet). Service `Editora`, one account per
  provider (`ai-api-key.<provider>`).
- `config.ApiKeyVault` sits between `Settings` and the store. It is opened once at launch on the config-prefetch
  thread, never on the FX thread. A key still in `settings.json` is written to the keychain; the settings fields
  are read from the file but never written to it (`JsonProperty.Access.WRITE_ONLY`), so the next save drops them,
  and the launch blanks them in the file's older copies beside it. A key saved later is held in memory at once and
  written to the store on the vault's own thread.
- When no keychain answers (headless Linux, no Secret Service, `-Deditora.secretStore=none`) keys go to
  `secrets.EncryptedFileStore` (adl's decision, 2026-11-21, for SET-13): `~/.editora/keys/secrets.enc`, AES-256-GCM
  with a fresh nonce per write, under a random 256-bit key Editora generates in `keys/secrets.key`, the way Pauta
  lets an app keep a key it generates (Torrenta's `keys/<name>.key`). The folder is `rwx------` and both files
  `rw-------` where the file system has POSIX modes. A keychain write that fails later sends every key to the file
  for the rest of the session; keys in the file move into the keychain once it answers. If the file cannot be
  written either, the keys are held for the session only, and Settings and standard error say so. A file that does
  not decrypt is reported and read as no keys, and moved aside (`*.unreadable`) before a new one is written.
- `aiApiKeysInKeychain` (persisted) records that the keys moved, so a session whose keychain does not answer says
  the keys are there rather than gone.
- Tests never reach a real keychain: the surefire run sets `editora.secretStore=none`, the store is injected
  (`SharedConfig.useSecretStore`, called only by the launch), and the one real-keychain test is skipped unless
  `EDITORA_REAL_KEYCHAIN_PROBE=1`.

## Consequences

- The keys are never exported or synced: no settings file holds them, Export leaves `keys/` out and blanks any
  `aiApiKey…` value an older file still has, and Import never writes one into `settings.json`.
- A Reset to Defaults keeps them (the vault is not touched by it).
- The encrypted file protects the keys from being copied about with the settings, not from a program running as
  the same user, which can read the key file beside it; that is what a keychain adds. Pauta 6.2.0's SET-13 asks for
  memory-only keys where no keychain answers, so the encrypted file stays an exception in `pauta.json`.
- The bindings are thin and untested on CI for macOS and Windows except through the opt-in probe; the Linux
  binding was checked against a private GNOME Keyring on a private D-Bus session.
