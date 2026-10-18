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
  thread, never on the FX thread. A key still in `settings.json` is written to the keychain and blanked in the file
  only after the keychain gives the same text back; a failure leaves every key in the file.
- When no keychain answers (headless Linux, no Secret Service, `-Deditora.secretStore=none`) keys stay in
  `settings.json`, and Settings → AI Actions says where they are. A keychain write that fails later puts every key
  back into the file for the rest of the session.
- `aiApiKeysInKeychain` (persisted) records that the keys moved, so a session whose keychain does not answer says
  the keys are there rather than gone.
- Tests never reach a real keychain: the surefire run sets `editora.secretStore=none`, the store is injected
  (`SharedConfig.useSecretStore`, called only by the launch), and the one real-keychain test is skipped unless
  `EDITORA_REAL_KEYCHAIN_PROBE=1`.

## Consequences

- Keys in the keychain are never exported or synced; Export's "Include AI API keys" only applies to keys still in
  `settings.json`.
- A Reset to Defaults keeps them (the vault is not touched by it).
- The bindings are thin and untested on CI for macOS and Windows except through the opt-in probe; the Linux
  binding was checked against a private GNOME Keyring on a private D-Bus session.
