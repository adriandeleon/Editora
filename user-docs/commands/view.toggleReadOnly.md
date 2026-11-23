Read-Only (View) mode locks a buffer so it can't be edited by accident. Typing
and the edit commands are blocked, while highlighting, folding, search, and copy
keep working, so it's meant for reading rather than the huge-file safety limit.

A file that isn't writable on disk opens in this mode automatically, and the
per-file state is remembered between sessions.

While a buffer is read-only:

- A Word-style **View Mode banner** docks above the editor, with an *Enable
  Editing* button when the file is writable.
- **Space** pages down and **Backspace** pages up, like a pager.
- The status bar shows a **Read-Only** segment you can click to flip back to
  **Editable**.

See [Editing](/docs/editing#read-only-and-view-mode) for the full picture.
