---
title: Editing
description: Multiple cursors, auto-indent, bracket matching, comments, and the small text operations.
category: Editing
order: 1
---

The editor has line numbers, a fold gutter, a minimap, and a current-line
highlight. Most of the editing help is automatic;
the rest is a command away. See the full list on the [Commands](/commands) page.

## Multiple cursors

Edit many places at once, VS Code style.

![Shelf.java with every occurrence of Item selected at once: each occurrence highlighted with its own caret, down the sample list and in the methods below](/screenshots/docs/multiple-cursors.png "Select all occurrences turns every Item in the file into a cursor.")

- **Add a caret at the next occurrence** of the selection
  (`edit.addCaretNextOccurrence`: `Ctrl+D` in the VS Code and Sublime keymaps,
  `⌘D` on macOS; `Alt+J` in IntelliJ, `Ctrl+G` on macOS), or **above** /
  **below** the current line (`edit.addCaretAbove` / `Below`: `Ctrl+Alt+Up` /
  `Down` in the VS Code and Sublime keymaps, `⌥⌘↑` / `⌥⌘↓` on macOS). The
  Emacs and CUA keymaps leave these to the palette.
- **Select all occurrences** (`edit.selectAllOccurrences`: `Ctrl+Shift+L` in the
  VS Code keymap, `Alt+F3` in Sublime, `Ctrl+Cmd+G` on macOS Sublime) turns every
  occurrence of the selection, or of the word under the caret, into a cursor at
  once. Matching is literal and case-sensitive.
- **Skip an occurrence** (`edit.skipOccurrence`: `Ctrl+K Ctrl+D` in the VS Code
  and Sublime keymaps) moves the newest selection on to the next occurrence
  instead of adding one, so you can step past a match you don't want. It wraps
  round to the start and passes over occurrences other cursors already hold.
- **Remove the last added caret** (`edit.undoLastCaret`: `Ctrl+U` in the VS Code
  and Sublime keymaps, `Alt+Shift+J` in IntelliJ) takes back the cursor you added
  most recently, then the one before it. The main cursor is never removed.
- **Split a selection into lines** (`edit.splitSelectionIntoLines`:
  `Ctrl+Shift+L` in the Sublime keymap, and in the Edit menu) turns a selection
  into one selection per line it covers, each holding that line's selected part.
  A selection that ends at the very start of a line leaves that line out.
- **Add carets to line ends** (`edit.addCaretsToLineEnds`: `Shift+Alt+I` in the
  VS Code and CUA keymaps, `Alt+Shift+G` in IntelliJ) puts a bare cursor at the
  end of every selected line, ready to append to all of them.
- From the Find bar, **Alt+Enter** (`find.selectAllMatches`) does the same for
  every match of the current query and closes the bar, so the query's case,
  regex and whole-word toggles decide what gets a cursor.
- **Alt-drag** to make a column or box selection.
- Type, and the edit fans out to every caret. **Esc** collapses back to one.

On macOS the VS Code and Sublime keymaps use `Cmd` where these list `Ctrl`
(`Cmd+D`, `Cmd+K Cmd+D`, `Cmd+U`, `Cmd+Shift+L`). The Emacs keymap leaves all of
these to the palette.

Movement chords fan out too: `C-f`, `C-b`, `C-n`, `C-p`, `C-a`, `C-e`, `M-f` and
`M-b` move every caret, like the arrow keys. Document, paragraph, sentence and
page motions stay on the primary caret. Multiple cursors can be turned off with
**Enable multiple cursors & column selection** (Settings → Editor → Editing),
and they're disabled in Simple UI mode.

## Auto-indent

**Enter** does the right thing per language: it keeps the current line's indent,
adds a level after a block opener (braces, a trailing `:` in Python or YAML,
`do` / `then` in shell, an open tag in XML or HTML), and splits a matching pair
into an indented stanza with the closer dropped below. Typing a closer such as
`)`, `]`, `}`, or a keyword like `end` / `fi` / `done` re-aligns the line to its
opener. Inside an HTML page's `<script>` or `<style>`, Enter follows the brace
rules of the code there: it indents after `{`, and a typed `}` lines up with its
opener. Enter between `<style>` and `</style>` puts the end tag on its own line,
and Tab inside `<pre>` or `<textarea>` keeps the line as written (see
[HTML](/docs/html#comments-and-indentation)).

In brace languages (JavaScript, TypeScript, Java, C, Go and the others), Enter
also follows these shapes:

- **`case` bodies.** After a `case …:` or `default:` label, the body is
  indented. A new label typed at the body's level steps back to the level of
  the label above it.
- **Headers without braces.** The one statement under a brace-less `if (…)`,
  `for (…)`, `while (…)` or `else` is indented, and the line after it goes back
  to the header's level.
- **Open argument lists.** After `foo(a,` the next argument is indented, and
  after the line that closes the call (`b);`) the next line goes back to the
  statement's level.
- **Call chains.** A line that starts with `.` continues the statement above,
  such as `.then(…)` under `return fetch(url)`, and the line after the chain
  ends goes back to the chain's first line.
- **Doc comments.** Enter inside a `/** … */` comment (JSDoc, Javadoc) starts
  the new line with ` * `. Enter right after a new `/**` also adds the closing
  ` */` below.
- **JSX tags.** In JavaScript, JSX and TSX files, Enter after an opening tag
  such as `<div className="x">` indents the next line one level. Enter between
  `<div>` and `</div>` puts the caret on an indented line, with the closing tag
  below at the opening tag's indent. Fragments (`<>` and `</>`) work the same
  way.

**Smart backspace** clears a line's indentation in one press. On a blank,
auto-indented line, a single Backspace jumps back to the end of the previous
line. In Python and YAML, where indentation is syntax, Backspace in the
indentation removes one indent level instead and never joins the line above.
The indent unit (tabs or spaces) is inferred from the file, including its
width: a 2-space file gets 2-space Tab, Enter, and Shift-Tab whatever the tab
size setting says. A file with nothing indented yet, such as a new one, takes
its language's own unit: 2 spaces in JavaScript, TypeScript, JSON and YAML, the
tab size elsewhere. An `.editorconfig` indent rule, or an **Indent style** of
spaces or tabs, still comes first. A tab character is drawn at the configured
tab size (the setting or `.editorconfig`), 4 columns by default.

### Shell scripts

In a shell script (which files count as one is on
[Languages](/docs/languages#shell-scripts)) the indent comes from the script's
structure, not from the shape of one line. Editora reads quotes and `$( … )` that span lines, comments,
here-documents, and the keyword blocks (`if … fi`, `for` / `while` / `until` /
`select … done`, `case … esac`, `{ … }` and `( … )`). A keyword counts only where
the shell treats it as one, at the start of a command, so `echo what to do`
opens nothing.

- A body indents one level past the line its statement starts on: after
  `then`, `do`, `else`, `case … in`, a `{` or `(`, and a case pattern such as
  `start|run)`.
- In a `case` item, `;;` (or `;&`, `;;&`) stays at the level of the item's body,
  the layout shfmt and the Google shell style guide use. The next line goes back
  to the pattern's level.
- `fi`, `else`, `elif`, `done` and `esac` line up with the line that opened
  their block. The opener is found by matching, so a wrapped condition or a
  `$( … )` in between does not throw it off. `done=false` is an assignment, not
  a closer.
- Inside a here-document nothing is re-indented, because the body is data. When
  **Enter** finishes the delimiter line (`EOF`), its indent is removed, since
  bash only ends `<<EOF` at an unindented delimiter (`<<-EOF` keeps tabs). The
  next line goes back to the level of the `cat <<EOF` line.
- **Tab** in a line's leading whitespace follows the same rules.
- **Tab** or **Indent Lines** over a selection shifts its lines but leaves the
  shebang, the text of a here-document and its delimiter where they are (a
  `<<-` here-document moves by tabs only), since moving them would change what
  the script does.

With the language server off, completion in a shell script offers the script's
own function and variable names after the snippets, and only variables after
`$` or `${`. With the [Bash server](/docs/lsp#shell-scripts) on, completion
comes from the server, and Editora's shell snippets stay in the list.

Shell blocks also [fold](/docs/navigation#folding): functions (from the line
with their name), `{ }` groups, `if … fi`, loops, `case … esac` and each case
item, multi-line `( )` and `$( )`, and here-documents. Sticky scroll pins the
same blocks. A double-click takes a hyphenated shell word whole, such as
`--long-flag`, `my-func` or `a-b` in `a-b.sh`.

A script with Windows (CRLF) line endings or a byte-order mark gets a notice
above the editor that converts it. See
[EditorConfig](/docs/editorconfig#shell-scripts).

### Python

In a Python file, Enter follows Python's own structure, so a block typed line
by line comes out nested the way Python reads it:

- After a line that ends in `:` the next line goes one level in, measured from
  the line where the statement starts. The body of a wrapped
  `if (this_is_one_thing and` / `that_is_another_thing):` goes one level past
  the `if`, not past the continuation line.
- After `return`, `pass`, `raise`, `break` or `continue` at the end of a line,
  Enter steps out one level, since nothing more runs in that block.
- Typing the `:` of `else`, `elif …`, `except …`, `finally` or `case …` moves
  the line to the statement it belongs to: the `if`, `for`, `while` or `try` of
  an `else`, the `try` of an `except` or `finally`, the previous `case`. A
  `finally` after an `if`/`else` inside the `try` body still goes to the `try`.
- A clause with nothing above it to line up with stays where you typed it. The
  first `case` under a `match` stays one level inside the `match`, and Enter at
  the end of a `case` line that is already right leaves it there.
- Inside an open bracket that ends its line, the next line hangs one level in.
  When an argument follows the bracket (`result = some_function(arg_one,`), the
  next line lines up with that argument, as PEP 8 shows. After the closing
  bracket the next line goes back to the statement's indent.
- A trailing `\` continues the statement one level in.
- Inside a docstring or another triple-quoted string, Enter keeps the line's own
  indent. Enter between an opening `"""` and its closing `"""` puts the
  caret on an indented line of its own, with the closing quotes below it.
- Quotes inside an f-string's `{ }` are read as code, so an f-string that
  reuses its own quote (`f"{d["key"]}"`) does not throw the indent off.
- **Tab** in a line's leading whitespace indents it to where Enter would have
  put it.
- Backspace in the indentation removes one level.

The indent size is guessed from the lines that start a statement, so the hanging
indents of wrapped calls and signatures do not make a 4-space file look like an
8-space one.

A file that mixes tabs and spaces in a way Python rejects with a `TabError`
shows it on the status bar: the indent segment reads `Tab 4 · mixed indent` in
the warning colour, and its tooltip names the line to fix.

**Narrow to Defun** (`C-x n d`) in a Python file narrows to the innermost `def`
or `class` around the caret, with its decorators, as Emacs's python-mode does.
**Go: Beginning of Defun** and **Go: End of Defun** (`C-M-a`, `C-M-e`) use the
same rule.

Python blocks [fold](/docs/navigation#folding) by indentation, with or without
a language server. A statement that ends in `:` folds over its body, and a
statement that spans lines (a wrapped call, a multi-line list or dict, a
docstring) folds over its own lines. A comment at the block's own level does not
end it. Sticky scroll pins the same blocks.

## Brackets and quotes

Typing `(`, `[`, `{`, `"`, `'`, or `` ` `` inserts the matching closer and keeps
the caret between them. Type the closer when it's already next to the caret and
Editora types over it. Type an opener with a selection and it wraps the
selection. Backspace inside an empty pair deletes both halves. A bracket pairs
only where its closer can sit: before whitespace, a closing bracket, a
separator (`;`, `:`, `.`, `,`, `=`, `>`), a quote, or at the end of the line.
Typing `(` just before `foo` gives `(foo`, so you can type the closer where it
belongs. This holds in every language. Quotes aren't auto-paired next to a word
character, so the apostrophe in `don't` is left alone. Whenever the caret sits
next to a bracket, it and its match are highlighted.

In Python a quote after a string prefix pairs too (`f`, `r`, `b`, `u`, `t`, and
pairs such as `rb` or `fr`), so `f'` gives `f''`. The third quote of `"""` or
`'''` adds the closing triple quote, with the caret between them. A backtick is
not Python syntax, so it is not paired there.

In an HTML start tag, typing `=` after an attribute name inserts the quotes
chosen in **Quotes after a typed or completed =** (Settings → Editor →
Editing); see [attribute quotes](/docs/html#editing-tags).

**Go: Matching Bracket** (`nav.matchingBracket`, `Ctrl+Shift+\` in the VS Code
keymap) jumps the caret to the bracket paired with the one beside it, and press
it again to jump back. **Edit: Select to Bracket** selects everything between a
pair, both brackets included. Matching is text-based, like the highlight, so a
bracket inside a string or comment can pair with the wrong one.

### Bracket-pair colorization

Separately from that highlight, every `()`, `[]` and `{}` is **tinted by its
nesting depth**, so you can see how deep you are without counting. The
highlight shows where *this* bracket closes and the colour shows how deep it is,
and the two combine on the same character.

Brackets inside strings and comments are skipped, because a stray `{` in a
string would otherwise shift the colour of every bracket below it. Six depth
colours cycle, and an unmatched closer is red.

On by default. Turn it off with **Bracket pair colorization** (Settings → Editor
→ Display) or with `view.toggleBracketColors`.

## Expand and shrink selection

**Edit: Expand Selection** grows the selection outward through syntactic levels:
word, then the enclosing brackets or string, the line, the enclosing definition,
the paragraph, the whole document. **Edit: Shrink Selection** retraces the
same steps back. `Shift+Alt+Right` / `Left` in the VS Code, CUA and Sublime
keymaps (`Ctrl+Cmd+Shift+Right` / `Left` on macOS); `Ctrl+W` / `Ctrl+Shift+W` in
the IntelliJ keymap (`Option+Up` / `Down` on macOS); palette-discoverable
everywhere, and unbound in the Emacs keymap.

Where a [language server](/docs/lsp#folding-and-selection) is running, the ladder
comes from the server's own parse, so it respects strings and comments. Without
one it falls back to text-based bracket and string matching, which is the
always-available baseline.

## Comments

`M-;` (Emacs comment-dwim) comments or uncomments using the language's own
syntax. A single line toggles a line comment; a multi-line selection toggles a
block or region comment. It covers `//` and `/* */`, `#`, `<!-- -->`, `--`, and
the rest, preserving indentation, and toggles back off when the text is already
commented. When several lines are commented, every marker goes in one column,
at the indent of the least-indented line, so the markers line up.

**Edit: Toggle Comment** (`edit.toggleComment`, also in the Edit menu and as
**Comment / Uncomment** in the editor's right-click menu) is the same action.
Two kinds of line in a script are left alone, and the status bar says why:

- A `#!` shebang line at the top of a file is not a comment, so it is never
  toggled, in any language: removing its `#` breaks a shell or Python script,
  and `// #!/usr/bin/env node` breaks a Node.js script. A selection that
  includes it toggles only the lines after it. (Rust's `#![…]` is code, and
  toggles as usual.)
- The body and delimiter of a here-document are data. A selection that reaches
  into one without its `cat <<EOF` line toggles nothing, because a `#` there
  would end up in the script's output. Selecting the whole statement comments it
  as one block, and uncommenting restores it.

In an INI file such as `setup.cfg`, `tox.ini` or `.flake8`, the comment
character is the one the file already uses: `#` when most of its comments start
with `#`, otherwise `;`.

In HTML pages, templates and PHP files the syntax follows where the selection
is (markup, `<style>`, `<script>`, template code or a PHP block); see
[HTML comments](/docs/html#comments-and-indentation).

In JavaScript, JSX and TSX files, a line of JSX markup (between an opening and
a closing tag, where `//` would be page text) gets `{/* … */}`, and toggling
again removes it. A multi-line selection of markup is wrapped in one
`{/* … */}`. Lines of code in the same file keep `//`.

## Small operations

| Operation | Command | Default key |
| --- | --- | --- |
| Transpose characters | `edit.transposeChars` | `C-t` |
| Transpose words | `edit.transposeWords` | `M-t` |
| Transpose lines | `edit.transposeLines` | `C-x C-t` |
| Duplicate line | `edit.duplicateLine` | (keymap-specific) |
| Move line up / down | `edit.moveLineUp` / `Down` | (keymap-specific) |
| Insert line below / above | `edit.insertLineBelow` / `Above` | (keymap-specific) |
| Complete statement | `edit.completeStatement` | (keymap-specific) |
| Delete line | `edit.deleteLine` | (keymap-specific) |
| Select line | `edit.selectLine` | (keymap-specific) |
| Indent / outdent lines | `edit.indentLines` / `edit.outdentLines` | (keymap-specific) |
| Select all | `edit.selectAll` | `C-x h` |
| Fill paragraph | `edit.fillParagraph` | `M-q` |
| Set fill column | `edit.setFillColumn` | (palette) |
| Forward / backward subword | `nav.subwordForward` / `Backward` | (palette) |
| Delete subword forward / backward | `edit.deleteSubword…` | (palette) |
| Convert indentation to spaces / tabs | `edit.indentationTo…` | (palette) |
| Tabify / untabify region | `edit.tabify` / `edit.untabify` | (palette) |
| Align regexp | `edit.alignRegexp` | (palette) |
| Copy with syntax highlighting | `edit.copyWithHighlighting` | (palette) |

Fill re-wraps a paragraph to the fill column (70 by default), repeating any
comment or quote prefix on each wrapped line. **Auto Fill mode**
(`view.toggleAutoFill`, off by default) does it as you type, breaking the line
when it passes the fill column. It is prose-only, so it never wraps code, and
each wrapped line keeps the paragraph's indent. In code languages, Fill
Paragraph and Fill Region fill **comments only** (line comments, and block
comments that start a line); they never join code statements.

Move Line Up/Down and Duplicate Line act on **every line of a selection**, not
only the caret's line.

The **line commands** work wherever the caret is on the line, and run at every
cursor as one undo step. They open a collapsed fold before changing it, renumber
an ordered Markdown list they change, and work with multiple cursors turned off
too.

- **Insert Line Below** opens a new line under the current one, indented as
  Enter at the end of the line would be (a level deeper after `{`). It doesn't
  continue a Markdown or Typst list. **Insert Line Above** opens one above, with
  the current line's indentation.
- **Complete Statement** (Code → Complete Statement) is IntelliJ's Complete
  Current Statement. It finishes the statement on the caret's line wherever the
  caret is: it closes an open string and the brackets the statement left open,
  including ones opened on lines above it, and adds what the statement needs.
  - A call or assignment gets its `;`: `println("done` becomes
    `println("done");`.
  - A control header, a type or a method gets a `{ }` block with the caret on
    its empty line: `if (ready` becomes `if (ready) {`, a body line and `}`.
    The same goes for `else`, `try`, `catch (…)`, `class Foo` and `void run(`.
  - An `abstract` method, or one in an interface, gets `;` instead.
  - A line ending in `{` or `{}` opens its body.
  - A statement that is already finished, an annotation or a `case` label
    gets a new line below.

  It works in Java, C, C++, C#, JavaScript, TypeScript, PHP and Kotlin (where it
  adds no `;`). Other languages get Insert Line Below.
- **Delete Line** deletes the line, or every line a selection touches, and the
  caret keeps its column on the line that moves up. It doesn't touch the
  clipboard or the kill ring; Emacs' *Kill Whole Line* (`C-S-backspace`) still
  kills. Delete Line is also in the Edit menu.
- **Select Line** selects the whole line with its line break; run it again to
  add the next line.
- **Indent Lines** / **Outdent Lines** shift the line, or every line of a
  selection, by the file's own indent unit, and the caret stays with its text.

| Line command | VS Code, Sublime | CUA | IntelliJ |
| --- | --- | --- | --- |
| Insert Line Below | `Ctrl+Enter` | `Ctrl+Enter` | `Shift+Enter` |
| Insert Line Above | `Ctrl+Shift+Enter` | `Ctrl+Shift+Enter` | `Ctrl+Alt+Enter` |
| Complete Statement | (palette) | (palette) | `Ctrl+Shift+Enter` |
| Delete Line | `Ctrl+Shift+K` | `Ctrl+Shift+K` | `Ctrl+Y` |
| Select Line | `Ctrl+L` | (palette) | (palette) |
| Indent / Outdent Lines | `Ctrl+]` / `Ctrl+[` | (palette) | (palette) |

On macOS, `Cmd` takes the place of `Ctrl` in the VS Code, Sublime and CUA
keymaps, except Sublime's Delete Line, which stays `Ctrl+Shift+K`. The macOS
IntelliJ keymap uses `Shift+Enter`, `Option+Cmd+Enter`, `Cmd+Shift+Enter` and
`Cmd+Backspace`. CUA
leaves Select Line unbound because `Cmd+L` is Go to Line on macOS. The Emacs
keymap leaves the line commands to the palette.

A **right-click outside the selection** moves the caret to the click and drops
extra carets, so Paste, Run/Debug, the language-server items, spelling
suggestions, and Add Bookmark act on the place you clicked. A right-click inside
a selection leaves it alone.

**Subword navigation** moves and deletes by camelCase and snake_case parts:
stepping through `getUserName` lands on `get`, `User`, `Name`, and acronyms split
correctly (`HTMLParser` gives `HTML` and `Parser`). These have no default key, so
bind them from Settings → Keymaps if you want VS Code's `Ctrl+Alt+←/→`.

**Convert indentation** rewrites the whole file's *leading* whitespace between
tabs and spaces. Alignment inside a line and string contents are left alone, and
each direction reverses the other. **Tabify / untabify** work the other way
round, converting whitespace runs anywhere in the selection (or the buffer) using
the tab width. **Align regexp** pads a selection's lines with spaces so a regular
expression lines up in a column, which is how you align a block of `=`
assignments; it only adds spaces, so re-running it is a no-op.

**Copy with syntax highlighting** puts a colored HTML flavor on the clipboard
alongside the plain text, so code pasted into Slack, an email or a document keeps
its highlighting. It is on by default for ordinary copies (Settings → Editor →
Clipboard, or `view.toggleCopyWithHighlighting`) and uses a light GitHub-style palette, since
pasted code usually lands on a light background. The explicit command ignores
both the setting and the size cap. Plain text is always on the clipboard too, so
pasting into a plain-text field is unchanged.

## Abbreviations

A text-replacement dictionary: define a short abbreviation that expands to longer
text. Expand the word before the caret on demand with `C-x a e`, or turn on
**Abbrev Mode** (`view.toggleAbbrevMode`) to expand automatically when you type a
space or punctuation. Define a new one with `C-x a g`, and manage the whole
dictionary in **Settings → Abbreviations** (`abbrev.manage`).

On that page an expansion can span several lines. Changes apply as you make
them, with no Save button: the expansion once you pause typing, the
abbreviation itself when you press Enter or leave its field. **Remove** asks
before it deletes an abbreviation. An abbreviation that repeats another one,
ignoring case, is refused with a message and the old one is put back, because
one would hide the other.

An abbreviation can contain punctuation as well as letters and digits: `;sig`,
`e.g`, `adl-fn`. When more than one abbreviation ends at the caret, the longest
one wins, so `adl-fn` expands as itself even when `fn` is also defined.

Your typed case is carried onto the expansion, so `btw` gives *by the way*, `Btw`
gives *By the way*, and `BTW` gives *BY THE WAY*; a lowercase abbreviation keeps
any capitals inside its expansion.

With Abbrev Mode on, an abbreviation typed out in full at the end of a line
shows what it will become as muted text after the caret (`btw → by the way`),
the way a snippet trigger does. **Tab** expands it at once, **Esc** hides the
hint, and typing a space or punctuation expands it as usual. The hint is part of
autocomplete, so it doesn't appear while autocomplete is turned off, and a
snippet trigger typed at the same spot keeps Tab for itself.

## The Emacs editing model

Beyond the keymap, Editora implements the concepts an Emacs user reaches for.
See [Emacs heritage](/features/emacs-heritage) for the full tour.

| Feature | Keys |
| --- | --- |
| Kill ring, yank, yank-pop | `C-k` / `M-d` / `M-DEL` …, `C-y`, `M-y` |
| Mark ring, pop mark | `C-SPC`, `C-x C-SPC` |
| Narrowing / widen | `C-x n n` / `d` / `f`, `C-x n w` |
| Rectangles | `C-x r k` / `M-w` / `y` / `d` / `c` / `o` / `t` / `N` |
| Registers | `C-x r s` / `i` / `SPC` / `j` |
| Query-replace, regexp | `M-%`, `C-M-%` |
| Occur | `M-s o` |
| Universal argument | `C-u` |

The **kill ring** holds the last 120 kills, and consecutive kills accumulate into
one entry, so `C-k C-k C-k` then `C-y` restores all three lines. Kills are still
written to the system clipboard, and text copied in another application wins over
a stale ring entry. *Edit: Yank from Kill Ring…*
picks an older entry directly. The ring is per window and lives for the session.

The **mark ring** is per buffer. `C-SPC` records the spot, `C-x C-SPC` pops back
through older marks and around to where you started, and marks follow their text
as you edit above them.

**Narrowing** restricts the buffer itself and is more than a display filter:
search, replace, macros and Select All see only the region. The editor says so
where you are looking: an amber banner above the text names the accessible
lines (*Narrowed to lines 32–36 of 81*) and has a **Widen** button, and the
gutter keeps the file's line numbers while narrowed, so the region's first row
still reads 32, not 1. The status bar's **Ln** and the Structure window's row
numbers count the same way. The amber **Narrowed · Widen** badge in the status
bar shows the state too and widens on click. Saving always writes the whole file.
Language-server support is suspended while narrowed, bookmarks, notes and
breakpoints stop persisting until you widen, and undo history is dropped at the
narrowing boundary (edits made while narrowed undo normally).

The **Structure** tool window (`tool.structure`) narrows by symbol with one
toggle at the end of its sort row: **Narrow to fold region**. A click narrows
the editor to the picked symbol's own block — on an indented method, the method
itself — and turns the mode on; with no row picked, the block around the
caret's line is used. A second click shows the whole file again and ends the
mode. While it is on, a click on another symbol, or Enter on it, narrows to
that one instead of only scrolling there, so you can walk a file one function
at a time. Only an activation narrows: the arrow keys, the filter field and a
right-click select a row and move the caret within the region, nothing more.
The outline stays the whole file's while narrowed, with the file's line
numbers; rows outside the region are muted, and with the mode off, Enter on one
widens and goes there. In Markdown, the toggle narrows a heading to its
section. It is disabled for a file that cannot be narrowed and hidden in Simple
UI mode. From the keyboard, `f` flips it while the outline has focus, and the
command `structure.narrowFold` can be bound in any keymap. The toggle mirrors
the buffer: lit amber while the window narrowed it, unlit with an amber border
while a chord such as `C-x n n` did (a click then narrows to the block
instead). Widening by any means — the badge, the banner, `C-x n w` — ends the
mode. `C-x n d` and `C-x n f` look for their definition and block in the whole
document, as Emacs does, so a narrowed buffer can be narrowed to the
definition around it.

**Rectangles** act on the columns between point and mark, and each command is one
undo step. They are character columns, so a tab counts as one, and the rectangle
is remembered separately from the kill ring, as in Emacs.

**Registers** hold a piece of text or a place to come back to, each under a
one-key name:

| Register command | Command | Emacs key |
| --- | --- | --- |
| Copy the selection into a register (`C-u` cuts it) | `edit.copyToRegister` | `C-x r s` |
| Insert a register's text, caret before it | `edit.insertRegister` | `C-x r i` |
| Save the caret's file and position | `edit.pointToRegister` | `C-x r space` |
| Jump to a saved position | `edit.jumpToRegister` | `C-x r j` |
| List registers | `edit.viewRegisters` | (palette) |

Each command reads the register's name from the next key you type, with a prompt
in the status line; Escape, moving the caret or leaving the editor cancels.
Jumping opens the position's file if it is closed, and a saved position follows
edits to its file while that file is open. An unsaved buffer has no position to
save. *Edit: List Registers* shows them all in a picker, and choosing one inserts
its text or jumps to its position. Registers last for the session, one set per
window. Only the Emacs keymap binds them, but every register command is in the
palette.

**Query-replace** stops on each match and waits: `y` or Space replaces, `n` or
Backspace skips, `!` replaces all the rest, `.` replaces this one and stops, and
`q`, Enter or Escape quits. Regexp mode expands `$1` group references. In the
Emacs keymap `M-%` runs query-replace rather than opening the find bar's replace
mode; the other keymaps are unchanged.

The **`C-u` prefix argument** gives the next command a count: `C-u 5 C-n` moves
down five lines, `C-u 3 C-k` kills three, `C-u 40 -` inserts forty dashes. A bare
`C-u` is 4 and repeating it multiplies by four, digits after it are that number,
and `C-g` cancels a half-typed argument. `C-u C-SPC` is special-cased to
pop-mark. Only the Emacs keymap binds `C-u`.

## String manipulation

Convert the **case style** of the selection or the identifier at the caret
(camelCase, PascalCase, snake_case, SCREAMING_SNAKE_CASE, kebab-case, dot.case),
with a *Cycle Case Style* that steps a token through the styles on repeated
presses (`edit.case.cycle`) and a *Swap Case* (`edit.case.swap`). Whole-line
transforms work on the selection or the whole file: sort ascending/descending
(numeric-aware, case-insensitive), sort by length, reverse, shuffle, remove
duplicate or empty lines, and trim trailing whitespace. Each is a palette
command, or reach them all from one filterable picker, *Edit: String
Manipulation…* (`C-c x`).

Two other sorts sit beside those: *Sort Lines (Case-Sensitive)*
(`edit.sortLinesCaseSensitive`) uses plain character order, so uppercase comes
before lowercase and digits compare one by one, and *Sort Lines, Unique*
(`edit.sortLinesUnique`) sorts in natural order and keeps one of each line.

*Keep Lines Matching…* (`edit.keepLines`) and *Remove Lines Matching…*
(`edit.removeLines`) ask for a regular expression and keep, or remove, the lines
that match it, in the selection's lines or the whole file, like Emacs'
keep-lines and flush-lines.

*Increment Number* (`edit.incrementNumber`, `C-c =`) and *Decrement Number*
(`edit.decrementNumber`, `C-c -`) change the number at each cursor, or the next
one on its line; with a selection, the first number in it. They understand
negatives (`x = -1`, while `a-1` is a subtraction), keep zero padding (`007`
becomes `008`), keep a hexadecimal number's case and width (`0x0f` becomes
`0x10`), and handle numbers of any size. `C-u 10 C-c =` steps by ten. The GUI
keymaps leave them to the palette.

The **encode and decode** commands rewrite the selected text, at every selection
at once, as one undo step. They need a selection. If a decode cannot read any
one of the selections, nothing changes anywhere and the status line says so.

| Encode / decode | Commands | What it does |
| --- | --- | --- |
| Base64 | `edit.encode.base64` / `edit.decode.base64` | The text's UTF-8 bytes as Base64; decoding accepts the standard and URL-safe alphabets and ignores whitespace |
| URL | `edit.encode.url` / `edit.decode.url` | Percent-encoding, with spaces as `%20`; decoding also reads `+` as a space |
| JSON string | `edit.escape.json` / `edit.unescape.json` | Escapes for use inside a JSON string, without adding the quotes |
| HTML | `edit.escape.html` / `edit.unescape.html` | Escapes `&`, `<`, `>`, `"` and `'`; unescaping knows the common named entities and every numeric one, and leaves unknown ones alone |
| Unicode | `edit.escape.unicode` / `edit.unescape.unicode` | Every non-ASCII character as a `\uXXXX` escape, and back |

## Hex viewer

Opening a **binary** file (an executable, archive, `.class`, and so on) shows a
read-only `offset | hex | ASCII` dump instead of garbage text. PDFs and images
are the exception: they open in their own page and image viewers. Binaries
are detected by content, and large files show their first slice with a
truncation note. *View: Open as Hex* (`view.openAsHex`) force-opens any file's
bytes as hex, and *View: Open as Text* switches back.

The dump's right-click menu has **Copy** and **Select All**, and Edit → Copy and
Select All, with their keys, act on it while it has the keyboard. Its tab is a
tab like any other: it has the full tab menu, can be pinned, and **Reopen Closed
Tab** brings it back as hex.

![sample.bin open read-only in the hex viewer: an offset column, sixteen bytes per row in hex, and the printable characters on the right](/screenshots/docs/hex-viewer.png "A binary file opens as an offset, hex and ASCII dump.")

## Read-only and View mode

Toggle a buffer read-only with `C-x C-q` so it can't be edited by accident.
Typing and edit commands are blocked, while highlighting, folding, search, and
copy keep working. A file that isn't writable on disk opens read-only
automatically, and is never replaced silently: Save asks first (once per file),
and auto-save reports that it cannot write. A Word-style **View Mode banner** docks above the editor with an
*Enable Editing* button when the file is writable, and while read-only,
**Space** pages down and **Backspace** pages up like a pager.

Some files are slow or risky to type into, so the first time a tab loads one it
opens in View Mode, and the banner says why:

- **A file of 200,000 lines or more**, or of 16 MB or more. It opens in
  [large-file mode](/docs/troubleshooting#performance), which keeps no undo.
- **A file with a line of a million characters or more** (1,048,576). Such a
  line is too wide to lay out quickly, so the file also opens without undo.
- **Minified or generated code**: a file of at least 100,000 characters whose
  lines average 500 characters or more, such as `dist/bundle.min.js`. It keeps
  undo. Markdown, Typst, plain text, logs, CSV and TSV, Markwhen and diffs never
  count as minified, since their long lines are paragraphs or records.

**Enable Editing** makes the file editable, and reloading it does not put it
back in View Mode. While an editable file has no undo, the status bar says
**Editable, No Undo**; its tooltip says why, and clicking it makes the file
read-only again. See [Files without undo](/docs/undo-history#files-without-undo)
for how bulk edits are kept safe there.

![A text file that is read-only on disk, open with the View Mode banner above it: This file is open for viewing, Space pages down, Backspace pages up, and Read-only on disk (no write permission) at the right; the status bar reads Read-Only](/screenshots/docs/view-mode.png "A file without write permission opens in View Mode, and the banner says why.")

For existing local files, Save writes a staged replacement and only swaps it
into place after the complete new content is ready. If Editora cannot create a
safe staging file, Save fails without touching the existing bytes. A
hard-linked file, or one owned by another user, is written in place instead, so
every name of the file gets the new content and its ownership is kept.

A file whose encoding Editora cannot detect, such as a Shift_JIS or GBK file,
opens without losing a byte, with its encoding in the warning colour; see
[File encodings](/docs/workspace#file-encodings).

## Whitespace and the column ruler

The **Display** card of Settings → Editor toggles whitespace markers, the
rulers, indent guides, the minimap, and line numbers. These render as
lightweight overlays drawn only for the visible lines, so they stay cheap on
large files. The page's other cards are **Editing** (multiple cursors, tag
auto-rename and auto-close, [HTML markup checks](/docs/html#markup-checks),
auto-fill, the project symbol index),
**Clipboard**, **Indentation**, **Saving** (auto save, save as administrator),
**Performance**, **Logs**, **CSV**, **File previews** and **Export & Print**.
While Simple UI mode is on, the Display card says that line numbers, the
minimap and indent guides stay hidden whatever their switches say.

**Rulers** are vertical lines at chosen columns, 80 by default. In Settings →
Editor, **Rulers** takes a list (`80, 120`; blank for none) and **Rulers by
language** takes lists that replace it for a file type (`java: 100; python: 79,
88`; a file type with nothing after its colon has no rulers). The names are the
file types on the Spell Check page's **File types** list (`java`, `python`,
`shell`…), in any letter case; they are stored in lower case. Text that cannot
be read, such as a column past 1000 or a name no file type has, turns the field
red with the reason in its tooltip, and nothing is saved until you fix it. Both
rows are greyed out while **Show rulers** is off. From the palette,
*Editor: Set Rulers…* (`editor.setRulers`) sets the global list and *Editor: Set
Rulers for This Language…* (`editor.setLanguageRulers`) sets the current file's
language, where a blank answer goes back to the global rulers. An
`.editorconfig` can move the first ruler or hide them all; see
[Rulers and max_line_length](/docs/editorconfig#rulers-and-max_line_length).
*View: Toggle Rulers* (`view.toggleColumnRuler`) shows or hides them.

**Indent guides** are faint vertical lines at each indentation level, carried
through blank lines and skipped over folded lines, in a colour that follows the
theme. Each guide stands on the column where its level's text starts, measured
from the text as it is laid out: it follows the text when the line numbers get
wider or are turned off and when the font changes, and it falls on the tab stops
of a tab-indented file and on the spaces of a proportional font. When you scroll
sideways, a guide whose column has passed under the line numbers is hidden. They
are on by default; turn them off in Settings → Editor or with *View: Toggle
Indent Guides* (`view.toggleIndentGuides`). They are hidden in Zen mode, Simple
UI and large-file mode.

### Word wrap

Word wrap is set per kind of file. Markdown and plain text wrap long lines to
the editor width by default, because their lines are paragraphs; code does not,
and scrolls sideways instead. Each has its own row under **Settings → Editor →
Display**:

| Settings row | Applies to | Default |
| --- | --- | --- |
| Wrap long lines in code | Every language except Markdown and plain text | Off |
| Wrap long lines in Markdown and plain text | Markdown and plain text | On |

*View: Toggle Word Wrap* (`view.toggleWordWrap`, no default key) flips the row
that governs the file in front, and the status bar says which one it changed.

A buffer that receives a line of 10,000 characters or more, or a run of 2,000
characters with no space in it (a minified file, a pasted `data:` URI), stays
unwrapped, because wrapping such text is very slow. *View: Toggle Word Wrap*
wraps that buffer anyway.
