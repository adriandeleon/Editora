Read-Only (View) mode locks a buffer so it can't be edited by accident. Typing
and the edit commands are blocked, while highlighting, folding, search, and copy
keep working. In the Emacs keymap it is `C-x C-q`; the other keymaps leave it
unbound, so run it from the Command Palette there.

Run on an editable buffer, it makes the buffer read-only, and the status line
says **Read-only (View mode)** with the chord that makes it editable again. Run
on a read-only buffer, it makes the buffer editable. Which files you made
read-only is remembered between sessions.

Some files open in View Mode by themselves, and the banner says why:

- a file that isn't writable on disk;
- a file in large-file mode (200,000 lines or more, or 16 MB or more);
- a file with a line of a million characters or more;
- minified or generated code, such as `dist/bundle.min.js`.

This command, or the banner's **Enable Editing**, makes such a file editable. A
large or long-lined file then has no undo, and the status bar says **Editable,
No Undo**. A file too large to load whole opens as a read-only slice and can't
be made editable.

While a buffer is read-only:

- A Word-style **View Mode banner** docks above the editor, with an *Enable
  Editing* button when the file is writable.
- **Space** pages down and **Backspace** pages up, like a pager.
- The status bar shows a **Read-Only** segment you can click to make the buffer
  editable.

See [Editing](/docs/editing#read-only-and-view-mode) for the full picture.
