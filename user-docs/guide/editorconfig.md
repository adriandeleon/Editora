---
title: EditorConfig
description: Editora honors a project's .editorconfig for indent, line endings, charset, and on-save fixups.
category: Editing
order: 4
---

Editora reads a project's [`.editorconfig`](https://editorconfig.org) so your
files follow the project's conventions without per-file fiddling. It's **on by
default**; toggle it in **Settings → Editor → Indentation** or with *View:
Toggle EditorConfig Support*.

## How resolution works

When you open a file, Editora finds the nearest `.editorconfig` by walking up the
directory tree until it hits a file with `root = true`. Closer directories win,
so a nested config overrides a parent.

## Supported keys

| Key | Effect |
| --- | --- |
| `indent_style`, `indent_size`, `tab_width` | Tab and Enter indentation, and the indentation of [new files from templates](/docs/snippets-templates#file-templates) |
| `end_of_line` | LF / CRLF / CR, round-tripped on save |
| `charset` | utf-8, utf-8-bom, latin1, utf-16le/be, round-tripped on read and save |
| `max_line_length` | Sets the first ruler; `off` hides them all |
| `trim_trailing_whitespace` | Trim on save |
| `insert_final_newline` | Ensure a trailing newline on save |

The on-save fixups (trim, final newline) and the encoding round-trip apply when
the file is written, so what's on disk matches the config.

When a `charset` rule makes a save write the file in a different encoding, or
adds or removes its byte-order mark, the save message in the status bar says
so: it names the old encoding, the new one, and the `charset` rule that caused
the change. It also says when `insert_final_newline = false` left trailing line
breaks out.

An encoding you pick yourself with *Buffer: Reopen with Encoding…* or *Buffer:
Save with Encoding…* wins over `charset`: Editora reads and writes that file in
your choice, and remembers it for the file. See
[File encodings](/docs/workspace#file-encodings).

### Rulers and max_line_length

A `max_line_length` replaces only the **first**
[ruler](/docs/editing#whitespace-and-the-column-ruler), so the others stay where
you put them (with no rulers set, it adds one), and `max_line_length = off`
hides them all for files the rule covers.

![A notes.txt file in a folder whose .editorconfig sets max_line_length = 40 and end_of_line = crlf for text files: the ruler stands at column 40 and the status bar reads EditorConfig and CRLF](/screenshots/docs/editorconfig.png "A max_line_length of 40 moves the first ruler to column 40; the status bar shows the file follows an .editorconfig and its CRLF endings.")

## Files with mixed line endings

A file that uses more than one kind of line ending (a CRLF file with a few bare
LF lines, say) is shown in the status bar as **Mixed**, with its dominant
ending in parentheses: "Mixed (LF)". This applies with or without an
`.editorconfig`.

- **Saving it without editing it writes the file back byte for byte.** Nothing
  is normalised.
- **Saving an edit** writes the dominant ending on every line. The status bar
  says so, and the bytes the file had before are first copied to
  `line-ending-originals/` in the [config folder](/docs/configuration), which
  keeps the last 30.
- If the file also contains **binary data**, rewriting its line breaks would
  change that data, so the save asks first (**Rewrite and Save**).

## Shell scripts

Bash cannot run a script with Windows (CRLF) line endings, and a UTF-8
byte-order mark (BOM) at the start of a script hides its `#!` line and becomes
part of the first command. So shell scripts get two special rules:

- **A shell script keeps LF and no BOM.** In a shell buffer, an
  `end_of_line = crlf` (or `cr`) rule and a `charset = utf-8-bom` rule are not
  applied, on open or on save. Every other rule still applies. New shell files
  from [templates and File: New File of Type…](/docs/snippets-templates) are
  written with LF too.
- **A notice offers to fix a script that has them.** When a shell script opens
  with CRLF line endings or a BOM, a bar above the editor says so, with
  **Convert to LF** (or **Remove BOM**) and **Keep as is**. The action converts
  the buffer to LF without a BOM, and the next save writes it. The notice is
  decided again each time the file loads, so it disappears once the file is
  fixed.

Other files with CRLF line endings are not affected. To convert any file by
hand, use **Buffer: Convert Line Endings (LF/CRLF)…**
(`buffer.convertLineEndings`).

## Without an .editorconfig

The indent unit is normally inferred per file. You can force it with a global
**Indent style** preference (Detect / Spaces / Tabs) in **Settings → Editor →
Indentation**, or
the *Editor: Set Indent Style…* command. With an `.editorconfig` present, its
`indent_style` takes precedence over the global preference.

The *EditorConfig: Open Active File* command (`editorConfig.openActive`, also the
status-bar EditorConfig segment) opens the `.editorconfig` governing the current
file.
