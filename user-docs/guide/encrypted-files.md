---
title: Encrypted files
description: Open, edit and save GnuPG-encrypted .gpg, .pgp and .asc files as the text inside them, choose who can read them, and sign and verify text, with your own gpg.
category: Tools
order: 5
---

Editora opens files encrypted with [GnuPG](https://gnupg.org/) as the text
inside them and saves them encrypted again. Open `notes.md.gpg` and you edit a
Markdown file, with Markdown highlighting and preview; press Save and the file
on disk is encrypted for the same people, in the same format, as before. The
plain text is never written to disk, and Editora keeps it out of the places it
would otherwise copy an open file's text.

Editora uses the `gpg` you have installed, on this machine, with your own
keyring, trust settings, smartcards and passphrase cache. It never asks you to
export a key. If GnuPG isn't installed yet, see
[Install GnuPG](#install-gnupg).

## Open an encrypted file

A file whose name ends in `.gpg`, `.pgp` or `.asc` and whose content is an
encrypted OpenPGP message opens decrypted. The name without the encryption
extension decides the language: `notes.md.gpg` is Markdown, `config.yaml.asc`
is YAML, `secrets.gpg` is plain text. Editora recognises encrypted files by
name, so an encrypted file with some other extension opens as an ordinary
(binary) file.

An `.asc` file that isn't an encrypted message opens as the text it is. When
it holds an OpenPGP public key, a banner offers **Import Key**.

When you open an encrypted file, GnuPG may ask for your passphrase (see
[Passphrases](#passphrases)). Editora decrypts in the background; the tab shows
**Decrypting…** with a **Cancel** button until it is done.

### The lock and the Encrypted status item

An encrypted file's tab has a lock on it, and the status bar shows who the file
is encrypted for:

| Status item | Meaning |
| --- | --- |
| **Encrypted · 2** | Encrypted for two people's public keys. |
| **Encrypted · Passphrase** | Encrypted with a passphrase. |
| **Encrypted · 2 (unsaved)** | You changed the recipients; the change applies when you save. |
| **Locked** | The tab is locked: its text isn't decrypted. |

Click the item, or run **Encryption: Show Encryption Details**
(`crypto.showInfo`), for the details card, **Encrypted with GnuPG**:

- **Readable by**: each person the file is encrypted for, marked **You** for
  your own keys, and **Verified** or **Not verified** according to your GnuPG
  trust settings. A person whose public key isn't in your keyring is marked
  **Key not in keyring**; a passphrase file shows **Anyone with the
  passphrase**.
- **File**, **Format** (**Binary (.gpg)** or **ASCII armor (.asc)**) and
  **Signature**, when the file is signed.
- **Crash recovery**: **Off for encrypted files** (see
  [What Editora keeps out](#what-editora-keeps-out-of-an-encrypted-file)).

The card has **Change Recipients…**, **Remove Encryption…** and **Lock File**
buttons.

### Locked tabs

A tab whose file isn't decrypted is *locked*: it shows what happened and the
next step instead of the file's bytes.

| The tab says | Why | Button |
| --- | --- | --- |
| *You cancelled the passphrase prompt.* | You closed GnuPG's prompt. | **Decrypt** |
| *That passphrase didn't open the file.* | Wrong passphrase. | **Try Again** |
| *None of your keys can open this file.* | The file isn't encrypted for any secret key on this machine. | (none) |
| *GnuPG has no way to ask for your passphrase here.* | gpg-agent has no prompt it can show. | **Decrypt** (Editora asks) |
| *GnuPG isn't installed* | Editora can't find `gpg`. The tab says how to install it on your system. | **Open Doctor**, **Set gpg Path…** |
| *The decrypted file isn't text.* | An encrypted image, archive or other binary. | **Decrypt to File…** |
| *This file is too large to decrypt into the editor.* | Larger than 8 MB. | **Decrypt to File…** |
| *GnuPG couldn't decrypt the file.* | Anything else; **Details** shows GnuPG's own message. | **Try Again** |

A locked tab usually also has **Show Encrypted Text**: an ASCII-armored file shows
its armor in the tab, a binary one opens in a hex view.

**Restored tabs stay locked.** Tabs restored from your last session are locked
until you decrypt them, so startup never asks for
a run of passphrases. Such a tab says *Press Enter to decrypt. Closing the tab
keeps nothing.* Press Enter, or click **Decrypt**.

**Encryption: Lock File** (`crypto.lockFile`), also on the tab's right-click
menu and the details card, puts an open encrypted file away: its text leaves
memory until you decrypt it again. Save the file first; Editora won't lock a
file with unsaved changes.

When an open encrypted file changes on disk, Editora reloads it the usual way
and decrypts the new content.

## Encrypt a file

**Encryption: Encrypt File…** (`crypto.encryptFile`) encrypts the active file.
It is in **Tools ▸ Encryption**, and
**Encrypt…** on a tab's right-click menu and on a file's right-click menu in
the Project tree. It opens the [recipient picker](#the-recipient-picker),
then writes the encrypted file beside the original: `notes.md` becomes
`notes.md.gpg`, or `notes.md.asc` when you choose the **ASCII armor (.asc)**
format. The tab switches to the encrypted file.

The plain original stays on disk until you decide. A banner says so and offers
**Move Original to Trash** or **Keep It**; when Git tracks the original, the
banner adds that Git also has its earlier versions. When
[Local History](/docs/workspace#local-file-history) holds plain copies of the
original, the banner says how many and offers **Delete History**, which asks
before it deletes them.

**Save As** to a name ending in `.gpg` or `.asc` encrypts too: Editora opens
the picker before writing. An untitled tab is encrypted this way.

## The recipient picker

The picker chooses who can open the file. It has two modes, **Public keys**
and **Passphrase**.

**Public keys** lists the keys in your GnuPG keyring: your own keys first, then
the people you encrypted for most recently, then everyone else by name. Type to
search by name, email or key ID, move with ↑ and ↓, select with Space, accept
with Enter, and cancel with Esc. You can choose several people at once; each
can open the file with their own secret key.

![The Encrypt README.md picker in Public keys mode: Ada Lovelace and Sam Rivera ticked and shown as chips, Release Team unticked, a signing-only key greyed at the bottom, a Format menu and an Encrypt for 2 People button](/screenshots/docs/encrypt-recipients.png "The recipient picker with two people chosen; a key that can only sign is listed last.")

- **Your own key is included by default**, so you can open the file again.
  Turn this off in [Settings ▸ Encryption](#settings) (**Always include my
  key**). If you leave your key out, the picker warns *You won't be able to
  open this file yourself.* and its button reads **Encrypt Without My Key**.
- **Keys you haven't verified** are marked **Not verified**. When you choose
  one, the picker shows its full fingerprint and asks you to compare it with
  its owner before sharing secrets.
- **Keys that can't encrypt** are listed last with the reason: **Expired**,
  **Revoked**, **Disabled**, **Invalid**, or **Signing only**.
- **Import Public Key…** adds a key from a file without leaving the picker.

With no keys at all, the picker says so and offers **Use a Passphrase
Instead** and **Import Public Key…**.

**Passphrase** encrypts with a passphrase instead of keys (symmetric
encryption): anyone with the passphrase can open the file. Type it twice.
Editora keeps the passphrase in memory while the file is open, so saving
doesn't ask again, and forgets it when you close the tab.

When you encrypt a file, the picker also offers the **Format**: **Binary
(.gpg)** or **ASCII armor (.asc)**, the text form you can paste into an email.

### Folder defaults: .gpg-id

A folder can name its default recipients in a `.gpg-id` file: one key ID or
fingerprint per line, the format the [`pass`](https://www.passwordstore.org/)
password manager uses. When you encrypt a file that isn't encrypted yet,
the picker starts with the people in the nearest `.gpg-id` at or above the
file's folder (plus your own key). So a password store opened in Editora
encrypts new entries for the same people `pass` would.

For a file on this computer, the picker has a **Use these recipients for new
files in *folder*/** checkbox that writes the folder's `.gpg-id`.

### Change who can read a file

**Encryption: Change Recipients…** (`crypto.changeRecipients`) opens the picker
for the active encrypted file, starting from its current recipients. The change
applies the next time you save (the picker's button reads **Apply on Save**,
and the status item shows *(unsaved)*). Running **Encrypt File…** on a file that
is already encrypted does the same.

## Save an encrypted file

**Saving re-encrypts the file** for its recipients, in its format (binary or
ASCII armor), before anything reaches the disk: Editora's backup and save
safety steps only ever see the encrypted bytes.

**A save that can't be encrypted writes nothing.** The tab shows a banner that
starts with *Not saved.* and says why, for example:

- *The public key of …  isn't in your keyring, so the file can't be encrypted
  for them. Import the key, or change the recipients.*
- *This file opens with a passphrase, and Editora doesn't have it. Choose one
  with Change Recipients.*
- *A recipient's key can't encrypt: it has expired, been revoked, or is for
  signing only.*
- *GnuPG isn't installed, so the file couldn't be encrypted.*

Your edits stay in the tab; fix the cause and save again.

**Save As to a plain name asks first.** Saving an encrypted file's text under a
name without `.gpg`, `.pgp` or `.asc` shows *Save without encryption?*: the new
file is plain text that anyone who can read the folder can read. Click **Save
Without Encryption** to go ahead; the encrypted original stays as it is. Save
As to another `.gpg` or `.asc` name keeps the file encrypted, in the format the
new name says.

**Encryption: Remove Encryption…** (`crypto.removeEncryption`) does the same
on purpose: after asking, it saves the text as the plain file beside it
(`notes.md.gpg` → `notes.md`) and the tab becomes that file.

## Encryption commands

All of these are in **Tools ▸ Encryption** and the command palette. Simple UI
mode has no Tools menu, so there they are in the palette.

| Action | Command |
| --- | --- |
| Encrypt the active file | `crypto.encryptFile` |
| Change who the active file is encrypted for | `crypto.changeRecipients` |
| Save the active encrypted file as plain text | `crypto.removeEncryption` |
| Lock the active encrypted file | `crypto.lockFile` |
| Show the encryption details card | `crypto.showInfo` |
| Encrypt the selected text | `crypto.encryptSelection` |
| Decrypt the armored message at the caret | `crypto.decryptBlock` |
| Write the decrypted content to a file | `crypto.decryptToFile` |
| Clear-sign the active text | `crypto.clearSign` |
| Write a detached signature for the active file | `crypto.signDetached` |
| Verify a signature | `crypto.verifySignature` |
| Import public keys into your keyring | `crypto.importPublicKey` |
| Decrypt the locked file in the active tab (its **Decrypt** or **Try Again** button) | `crypto.decryptLocked` |
| Stop a decryption that is running | `crypto.cancelDecrypt` |
| Move the unencrypted original the tab's banner names to the trash | `crypto.trashOriginal` |
| Set the `gpg` program | `crypto.setGpgPath` |
| Change an encryption setting from the palette | `crypto.changeSetting` |

None has a default key; bind one in [Keymaps](/docs/keymaps/).

### Part of a text

**Encryption: Encrypt Selection…** (`crypto.encryptSelection`) replaces the
selected text with an ASCII-armored message for the people you choose, for
example a secret inside an otherwise plain note or email. If the text changes
while GnuPG works, nothing is replaced.

**Encryption: Decrypt Block at Caret** (`crypto.decryptBlock`) decrypts the
armored message (`-----BEGIN PGP MESSAGE-----` … `-----END PGP MESSAGE-----`)
the caret is in and shows its text in a read-only **Decrypted message** card
with **Copy**, **Replace Block with Text** and **Close** (`crypto.copyPeeked`,
`crypto.replacePeeked`). Replacing in an
encrypted file is safe: saving encrypts the whole file again. Replacing in a
plain file puts the secret in that file as plain text, and the card says so.

**Encryption: Decrypt to File…** (`crypto.decryptToFile`) writes an encrypted
file's decrypted content to a file you choose, for content that isn't text or
is too large to edit. It works on a locked tab too.

### Keys

**Encryption: Import Public Key…** (`crypto.importPublicKey`) adds the public
keys in a file you choose, or in the open `.asc` file, to your GnuPG keyring,
and says how many were new. An open `.asc` public key also offers **Import
Key** in a banner.

### Signatures

Signing uses your secret key, so GnuPG may ask for its passphrase.

- **Encryption: Clear-Sign Text** (`crypto.clearSign`) wraps the active text in
  a clear-text signature (`-----BEGIN PGP SIGNED MESSAGE-----`): the text stays
  readable, and anyone with your public key can check it hasn't changed.
- **Encryption: Sign File (Detached)** (`crypto.signDetached`) writes
  `file.asc` beside the saved file, a separate signature others can check. Save
  the file first, so the signature matches what is on disk.
- **Encryption: Verify Signature** (`crypto.verifySignature`) checks a
  clear-signed text, the active file against the `.asc` or `.sig` beside it,
  or, in an open signature file, the file it signs. The result names the signer
  and says whether the signature is good, whether their key is verified, and
  whether the signature or key has expired or been revoked. A *Bad signature*
  means the text changed after it was signed.

## Passphrases

By default **GnuPG asks for passphrases** with gpg-agent's own prompt (its
*pinentry*), and GnuPG's passphrase cache works as it does in a terminal.
Editora never sees the passphrase.

**Editora asks** in its own prompt, and hands the passphrase straight to GnuPG
without keeping it, when:

- gpg-agent can't show a prompt here, because its pinentry needs a terminal or
  there is none. The prompt says so.
- the file is encrypted with a passphrase only. Editora keeps that passphrase
  in memory while the tab is open, so saving doesn't ask again, and forgets it
  when you close the tab.
- you choose **Editora** as the **Passphrase prompt** in
  [Settings ▸ Encryption](#settings).

To use GnuPG's own dialog instead of a terminal pinentry, install a graphical
pinentry (`pinentry-mac`, `pinentry-gnome3` or `pinentry-qt`) and set
`pinentry-program` in `~/.gnupg/gpg-agent.conf`. Doctor's **Passphrase prompt**
row shows which one gpg-agent uses.

## What Editora keeps out of an encrypted file

While an encrypted file is open, Editora treats its text as sensitive and keeps
it out of everything it would otherwise write or send elsewhere:

- **No crash recovery.** Editora keeps no recovery copy of an encrypted file's
  unsaved edits, so they are lost if Editora crashes. Save often, or turn on
  autosave. (A copy taken before the file was encrypted is deleted.)
- **No Local History.** Nothing is recorded for encrypted files. An `.asc`
  file that is not an encrypted message, such as a public key or a signature,
  is recorded like any other text file.
- **Bookmarks and breakpoints** keep only line numbers, not the line's text.
- **No personal notes**: a note keeps an unencrypted copy of the text it is
  about.
- **No language servers**, unless you turn on **Let language servers read
  encrypted files** in Settings ▸ Encryption. A language server gets the whole
  decrypted text and may keep it in its own caches or logs.
- **No agents, MCP clients or AI actions**: the embedded agent and MCP clients
  can't read the file, and AI actions don't send its text anywhere.
- **No HTML Live Preview**: an encrypted page has no globe, the decrypted text
  never reaches the preview server, and `.gpg`, `.asc` and `.pgp` files are
  never served, since every script a previewed page runs could read them. This
  covers a file you encrypt while the preview runs: after **Encrypt File…**
  the old address serves only the unencrypted file left on disk, if you kept
  it, never your edits (see [HTML](/docs/html#encrypted-files)).
- **No Git change bars or blame**, no diagram previews (they render through
  temporary files; Mermaid blocks in an encrypted Markdown file show as code),
  and Replace in Files doesn't rewrite encrypted files that aren't open.
- **Export, print and external tools ask first**, once per tab: exporting or
  printing writes the text unencrypted, and an external tool gets the decrypted
  text.

Encrypted files on a [remote host](/docs/remote/), in a container or in WSL are
read and written as encrypted bytes; they are decrypted on your computer, where
your keys are.

## Settings

**Settings ▸ Encryption**:

| Setting | What it does |
| --- | --- |
| **gpg path** | Leave empty to find `gpg` on PATH and in the usual install folders (GPG Suite, Gpg4win, Git for Windows). The page shows which `gpg` was found. |
| **Always include my key** | On by default: a file you encrypt for other people is encrypted for you too. |
| **Passphrase prompt** | **GnuPG (gpg-agent)** (default) or **Editora**. Editora always asks for passphrase-only files, and whenever GnuPG can't show its own prompt. |
| **Let language servers read encrypted files** | Off by default. |

## Doctor

**View: Doctor** has an **Encryption** section with two rows:

- **GnuPG**: the `gpg` Editora uses and its version, or *Not installed. Only
  encrypted files need it.*
- **Passphrase prompt**: the pinentry gpg-agent uses. A warning means GnuPG
  can't show its prompt in Editora (a terminal pinentry, or none), so Editora
  asks instead; it says how to install a graphical one. With **Editora**
  chosen in Settings, the row says *Editora asks (Settings)*.

## Install GnuPG

GnuPG is only needed for encrypted files. Install it, then reopen the file (or
click **Decrypt**); if Editora still can't find it, set **gpg path** in
Settings ▸ Encryption.

- **macOS**: `brew install gnupg` with [Homebrew](https://brew.sh/), or install
  [GPG Suite](https://gpgtools.org/), which includes a graphical passphrase
  dialog. Homebrew's GnuPG usually comes with a terminal pinentry, so Editora
  asks for passphrases itself; for GnuPG's own dialog, `brew install
  pinentry-mac` and set it as `pinentry-program` in `~/.gnupg/gpg-agent.conf`.
- **Linux**: install the `gnupg` package: `sudo apt install gnupg` on Debian
  and Ubuntu, `sudo dnf install gnupg2` on Fedora. Most desktops already have
  it, with a graphical pinentry.
- **Windows**: install [Gpg4win](https://www.gpg4win.org/). Editora also finds
  the `gpg` that comes with Git for Windows.

If you don't have a key yet, create one with `gpg --full-generate-key` (or
Kleopatra on Windows, GPG Keychain on macOS), or encrypt with a passphrase
instead.

## The GnuPG plugin

Encryption is part of Editora, not a plugin. The registry's `gpg-tools` plugin
is retired: see [retired plugins](/docs/plugins#publishing).
