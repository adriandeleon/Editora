---
title: Snippets & templates
description: Expand boilerplate with tab-stop snippets, and scaffold new files from templates.
category: Editing
order: 2
---

Editora has two related ways to stop retyping boilerplate: **snippets** for
inline expansions, and **file templates** for whole new files. The two have
different syntaxes: a snippet body uses the VS Code / TextMate tab-stop syntax,
and a template body is a file with named `${variable}` placeholders.

## Snippets

Type a trigger and press **Tab**, or pick from the **Snippet: Insert…** list
(`C-c i`). Once expanded:

- Placeholders are pre-selected so you can overtype them.
- **Tab** / **Shift-Tab** cycle the fields, and mirrors update live.
- A choice field shows a dropdown.
- `$0` is where the caret lands when you finish.
- Every field is outlined, mirrors are underlined, and the final caret position
  is marked. The status bar shows where you are, as in **Snippet 2/3**.
- **Esc**, a click on that status-bar segment, or **Snippet: Leave Tab Stops**
  (`snippets.endSession`) leaves the fields and keeps the text. Moving the caret
  out of the fields does the same, so Tab indents again.

Bodies use the standard syntax: `$1`, `${1:default}`, mirrors (a repeated
number), `${1|a,b|}` choices, and [variables](#snippet-variables). Snippets ship
for 32 languages, plus a global set that applies to every file. The TypeScript
set also applies to JSX and TSX files.

![The Insert Snippet picker over a Java file: each trigger (main, psvm, cmain, class, interface, record, enum, sealed, package, import, ctor, method) with its description and a preview of its body](/screenshots/docs/snippet-picker.png "Snippet: Insert… lists the snippets for the file's language, each with its trigger and a preview.")

### Expanding with Tab

A trigger can be a word (`fori`), a token with punctuation (`#include`), or up to
three words (`else if`). Tab does not expand a trigger inside a comment or a
string, or in a CSV/TSV file; there it indents as usual. A name typed right
after a `.` is a member, not a trigger, so Tab after `self.property`,
`re.match` or `os.s` never expands a snippet, in any language.

In Markdown and plain text, where triggers like `code`, `link`, `bold` or
`note` are also ordinary words, Tab is careful not to expand one in the middle
of a sentence:

- A snippet that makes a block of its own (a heading, a quote or alert, a list,
  a table, a fenced block, or anything of several lines) expands only when its
  trigger starts the line, after any indentation or `>` quote marks.
- A trigger made only of letters expands only when it is alone on its line.
  One with digits or punctuation (`h2`, `;sig`), or one attached to the
  character before it (`(code`), expands anywhere.

The completion popup still offers the snippet mid-sentence, so you can choose
it there.

A trigger typed out in full goes ahead of other completions. In Markdown and
plain text, no dictionary or AI inline suggestion is shown over it, so Tab
expands the snippet. With a language server, the trigger's snippet is the first
and highlighted row of the completion list, so Tab or Enter takes it.

The bundled `date` and `time` snippets are ordinary words, so Tab alone leaves
them be. They are offered in the completion popup and the **Snippet: Insert…**
list, and in Markdown and plain text as an inline suggestion at the end of a
line (`date → Current date (YYYY-MM-DD)`), which Tab accepts.

### Shell snippets

The bundled shell snippets follow the script they expand in. In a script whose
`#!` line names a POSIX shell without bash's `[[ ]]` (`sh`, `dash`, `ash` or
`posh`), `if`, `elif`, `while` and `until` write their test as `[ condition ]`
instead of `[[ condition ]]`. With no `#!` line a script is treated as bash.

The triggers include `if`, `elif` (also `elseif`), `else`, `for_in`, `for_i`,
`while`, `until`, `case`, `function`, `arith` (also `expr`, for `$(( … ))`),
`echo`, `read` and `break`. The `case` snippet puts each `;;` at the level of
its item's body, the way the editor indents one you type.

Several of these triggers are also shell keywords. So in a shell script,
**Enter** on a completion row that only repeats the word you just typed closes
the popup and starts a new line: typing `else`, pausing, then pressing Enter
gives a new line, not the `else` snippet. **Tab** still expands it, and Enter
still takes a longer row such as `elseif`.

**Settings → Snippets → Expand snippets with Tab** (on by default, also **View:
Toggle Snippet Expansion on Tab**, `view.toggleSnippetTabExpansion`) turns Tab
expansion off altogether. Snippets then come from the completion popup and
**Snippet: Insert…** only.

### Python snippets

The bundled Python snippets cover the usual statements: `def` and `defs` (a
method with `self`), `deft` and `defst` (with a return type), `class`, `classd`
(with a base class) and `classi` (with a docstring and an `__init__`), `if`,
`elif`, `else`, `ifelse`, `ifmain` (the `if __name__ == "__main__":` guard),
`for`, `forr` (over a `range`), `while`, `with`, `try`, `trya`, `tryf` and
`tryef` (with `else` and `finally` blocks), `except`, `exceptas`, `match`,
`case`, `casew` (the `case _:` wildcard), `lambda`, `property`, `import` and
`fromim`. The `except` bodies re-raise with a bare `raise`, which keeps the
original traceback. `#env` writes a `#!/usr/bin/env python3` line, and `#cell`
and `#mark` write `# %%` cell markers.

A few write a piece of a line: `self` writes `self.`, `dunder` a
`__init__`-style name with the name selected, `doc` a docstring whose closing
quotes sit on the next line and `##` a one-line one, and `#ignore` a `# pyright: ignore[]` comment
with the caret in the brackets. No Python trigger is a single character, so
Tab after a lone `s` or `#` indents.

Several of these are named after a keyword (`try`, `if`, `for`). When the
popup lists a snippet that only repeats the word you typed, Enter ends the line
as usual; press Tab to expand the snippet.

### Java snippets

The bundled Java snippets cover:

- **Declarations:** `class`, `interface`, `record` and `enum` named after the
  file, `sealed` (a sealed interface with two record implementations),
  `package` (the package of the folder under `src/main/java` or
  `src/test/java`), `import`, `ctor` (a constructor for the file's class),
  `main` (also `psvm`) and `cmain` (the `void main()` of a compact source
  file).
- **Methods and fields:** `method`, `static_method` and `field` (each with a
  choice of access modifier), `private_method`, `public_method`,
  `protected_method`, `private_static_method`, `public_static_method`,
  `private_field`, `protected_field`, `public_field`, `const` (also `psf`, a
  `static final` constant) and `new`.
- **Output and logging:** `sysout` (also `sout`), `soutv` (a value with its
  name), `souf` (also `printf`), `syserr` (also `serr`), `ioprintln` (also
  `iop`) and `ioreadln` for JDK 25's IO class, `logger` (a `System.Logger`
  field, with no dependency) and `slf4j` (an SLF4J logger field with its
  imports).
- **Statements:** `if`, `ifelse`, `ifnull` (also `ifn`), `ifnotnull` (also
  `inn`), `instanceof` (also `inst`, with a pattern variable), `switch` (with
  arrow cases), `switchexpr` (also `sw`), `fori`, `forr` (counting down),
  `foreach` (also `iter`), `formap` (over a map's entries), `while`,
  `dowhile`, `try_catch`, `try_finally`, `try_resources` (also `twr`), `throw`
  (also `thr`) and `synchronized`.
- **Expressions and threads:** `lambda`, `stream` (a filter, map and toList
  pipeline), `textblock` (also `tb`), `vthread` (starts a virtual thread) and
  `vexecutor` (a virtual-thread-per-task executor, closed when done).
- **JUnit 5:** `test`, `ptest` (a parameterized test), `beforeeach`,
  `aftereach` and `assertthrows`.

**Surround With.** Select whole lines and choose `if`, `ifelse`, `ifnull`,
`ifnotnull`, `fori`, `forr`, `foreach`, `while`, `dowhile`, `try_catch`,
`try_finally`, `try_resources` or `synchronized`, and the lines become the
block's body, re-indented to fit. **Code ▸ Surround With…**
(`snippets.surround`; Ctrl+Alt+T, ⌥⌘T on macOS, in the IntelliJ keymap) lists
only the snippets that wrap a selection; with nothing selected it wraps the
current line, and a selection over several lines wraps the whole lines it
touches. Esc gives back the caret and selection you had. **Snippet: Insert…**
(Ctrl+J, ⌘J on macOS, in the IntelliJ keymap) offers the same snippets among all
the others, and with nothing selected they expand as usual. Any snippet that
uses `$TM_SELECTED_TEXT` wraps a selection the same way: a multi-line variable
value takes the indentation of the line it sits on.

The Java language server offers templates of its own under some of the same
names (`sysout`, `fori`, `try_catch` and others). A server template named like
one of your snippets, bundled or your own, is left out of the completion list,
so the popup shows a single row for the trigger: the snippet that Tab expands.

### HTML snippets

The HTML snippets cover the elements by name (`div`, `ul`, `table`, `form`,
`video` and the rest), plus `div.`, `div#` and `div.#` (and the same for `p`,
`ul` and `ol`) for an element with a class, an id or both.

- `!` and `html5` insert a whole page. The first field is the `lang` value
  (`en`), and the caret ends in the `<body>`. `html5` also links a stylesheet.
- `doctype` inserts `<!DOCTYPE html>`, and `link` a stylesheet link
  (`<link rel="stylesheet" href="">`).
- Obsolete elements (`big`, `command`, `keygen`, `menuitem`) are not offered.

In an HTML page, the snippets offered follow where you are typing: CSS
snippets inside a `<style>`, JavaScript snippets inside a `<script>`, and none
inside a tag, so `col` in a stylesheet offers `color` rather than `<col>`.
Where you are is read back to the `<script>` or `<style>` start tag, up to
100,000 characters back, so a long inline script still counts. HTML templates
(Jinja, Twig, ERB…) and Handlebars files get the HTML snippets. There is no
Emmet; see [what the HTML server does not do](/docs/html#what-it-does-not-do).

### XML snippets

The 25 XML snippets cover:

- **Markup:** `?xml` (the XML declaration), `tag` (an element whose name is
  mirrored in the end tag), `tagc` (self-closing), `taga` (with one attribute),
  `tagb` (its content on its own indented lines), `attr`, `comment`, `cdata` and
  `pi` (a processing instruction).
- **Binding a grammar:** `xsi` (an XSD with no target namespace), `xsins` (an
  XSD with a target namespace), `xml-model` (a RELAX NG or other schema) and
  `doctype` (an external DTD).
- **XSD:** `xsschema`, `xselement`, `xscomplex` (an element with a complex type
  and a sequence), `xsattribute` and `xssimple` (a simple type restricted to an
  enumeration).
- **XSLT:** `xslstylesheet`, `xsltemplate`, `xslvalue`, `xslforeach`, `xslif`,
  `xslchoose` and `xslapply`.

`tag`, `taga`, `tagb`, `comment`, `cdata`, `xscomplex`, `xsltemplate`,
`xslforeach`, `xslif` and `xslchoose` wrap the selection, so **Surround With**
works in XML: select some lines and wrap them in an element, a comment, CDATA or
an XSLT block.

### Maven pom snippets

A `pom.xml` (or any file that
[previews as a pom](/docs/previews#maven-poms)) gets its own snippets on top of
the XML ones: `dep` (a dependency with group, artifact and version), `depm` (a
dependency without a version, managed by a parent or BOM), `dept` (a
test-scoped dependency), `bom` (a BOM import for `<dependencyManagement>`),
`plugin` (a plugin with a `<configuration>` block), `exec` (an `<execution>`
binding a goal to a phase), `profile` (a profile activated by a property),
`prop` (a `<properties>` entry whose name is mirrored in the closing tag),
`module` and `repo`. They live in `maven-pom.json`, so your own go in
`snippets/maven-pom.json`.

### Snippet variables

A variable is written `$NAME` or `${NAME}`, and `${NAME:default}` supplies a
value for when it is empty. A `$name` Editora does not know stays in the text as
typed.

| Variables | Value |
| --- | --- |
| `TM_FILENAME`, `TM_FILENAME_BASE`, `TM_DIRECTORY`, `TM_FILEPATH` | The file's name, name without extension, folder, and full path |
| `RELATIVE_FILEPATH` | The file's path relative to the project |
| `WORKSPACE_NAME`, `WORKSPACE_FOLDER` | The name and folder of the project open in the snippet's window (empty without one, and in an untitled buffer) |
| `TM_SELECTED_TEXT`, `SELECTION` | The selection when the snippet was inserted |
| `TM_CURRENT_LINE`, `TM_CURRENT_WORD` | The line and the word at the caret |
| `TM_LINE_INDEX`, `TM_LINE_NUMBER` | The caret's line, counted from 0 and from 1 |
| `CLIPBOARD` | The clipboard text |
| `CURRENT_YEAR`, `CURRENT_YEAR_SHORT`, `CURRENT_MONTH`, `CURRENT_MONTH_NAME`, `CURRENT_MONTH_NAME_SHORT`, `CURRENT_DATE`, `CURRENT_DAY_NAME`, `CURRENT_DAY_NAME_SHORT` | Parts of today's date |
| `CURRENT_HOUR`, `CURRENT_MINUTE`, `CURRENT_SECOND`, `CURRENT_SECONDS_UNIX`, `CURRENT_TIMEZONE_OFFSET` | Parts of the current time |
| `UUID`, `RANDOM`, `RANDOM_HEX` | A random UUID, six random digits, six random hex digits |
| `LINE_COMMENT`, `BLOCK_COMMENT_START`, `BLOCK_COMMENT_END` | The comment markers of the file's language |

### Your own snippets

User snippets live in your config folder under `snippets/<language>.json` (or
`global.json`). Snippets are merged by name in the order bundled, plugin, user,
so your snippet replaces a bundled or plugin one with the same name. When two
snippets share a trigger, both stay in the completion popup and the picker, and
Tab expands yours before a plugin's or a bundled one. Three commands manage
them:

- **Snippet: Manage…** opens **Settings → Snippets**, where you add, edit, and
  remove them without touching the JSON. The list has a filter and a trigger
  column, and the **Language** picker lists every language Editora knows, so
  you can start snippets for a language that has none yet.
- **Snippet: Edit User Snippets…** opens the file for the current language. It
  reloads when you save.
- **Snippet: Reload Snippets** picks up changes without a restart.

The Snippets page has no Save button. A change applies when you press Enter,
when you leave the field, or a moment after you stop typing. A new **Name**
applies only on Enter or when you leave that field, so a half-typed name never
renames the snippet. **Remove** asks before it deletes one of your snippets. A
trigger cannot contain a space, because Tab could never expand it: the
**Triggers** field turns red and says why, and nothing is saved until you fix
it.

The format is the VS Code snippet JSON, and the reader is lenient about
real-world files. A snippet can have several triggers: an array `prefix` in the
JSON, or a comma-separated list in the **Triggers** field in Settings. A `scope`
field (a comma-separated list of language ids) limits a snippet to those
languages. A file with a mistake is reported with its name, the reason and the
line, and only the bad entry is skipped.

A snippet file Editora writes carries `"schemaVersion": 1`, which says which
format it is in. A file without it loads as before and gets the marker the next
time Editora saves it. A snippet file written by a newer Editora in a newer
format is not loaded: it is left exactly as it is, a copy is kept beside it
(`java.json.v2.bak`, say), saving into it is refused, and
[Settings sync](/docs/settings-sync) does not merge it.

### Changing or disabling a bundled snippet

Bundled snippets appear in **Settings → Snippets** alongside yours. Editing one
saves your own version under the same name, which replaces the bundled snippet
under all its triggers; **Remove** brings the bundled one back. **Disable**
switches a snippet off without deleting anything, and **Enable** restores it. In
the JSON, a disabled bundled snippet is an entry with that snippet's name and
`"disabled": true`.

## File templates

**New File From Template** (`C-c C-n`, also a toolbar button) scaffolds a file,
or a whole set of files, from a reusable template. A picker chooses the
template and shows where each one comes from (bundled, yours, or a plugin), then
a small wizard prompts for any variables it declares that Editora can't fill in
itself.

![The New File From Template picker: Java Class, Java Compact Source, HTML Page, Markdown Document, Python Script, Node.js Script, Shell Script, Zsh Script, JUnit 5 Test and more, each marked Bundled with a description](/screenshots/docs/template-picker.png "New File From Template shows where each template comes from and what it makes.")

Editora ships 37 templates:

- **Code:** a Java class, a Java compact source file, a JUnit 5 test, a Java
  shebang script (an extensionless file the `java` launcher runs directly), a
  Maven `pom.xml` (quickstart-shaped: Java 21, JUnit 5 through its BOM,
  Surefire 3), a Python script, a pytest test module, a Node.js script, shell
  and Zsh scripts, an HTML page (**HTML Page**), and an HTML page with a
  stylesheet it links (**HTML Page + CSS**), both with `<!DOCTYPE html>`,
  `UTF-8` and the viewport meta.
- **XML:** an XML Schema (XSD) with a target namespace and one element, an XSLT
  stylesheet with the identity template, and an XML document bound to a schema
  (`xsi:noNamespaceSchemaLocation`), so the
  [XML server](/docs/lsp#xml) validates and completes it from the start.
- **Files Editora previews or runs:** a Markdown document, a Typst document
  (on its own, or with its page setup in a reusable `template.typ`), a Mermaid
  diagram, a Markwhen timeline, and HTTP requests with `@assert` and `@capture`
  checks plus dev and prod environment files.
- **Writing:** a README, a changelog in the Keep a Changelog format, an
  architecture decision record, a blog post with YAML front matter, and meeting
  notes.
- **Project files:** the MIT license (with this year and your author name) and
  the Apache 2.0 license, `.gitignore` files for Java, Python and Node, an
  `.editorconfig`, GitHub issue and pull request templates, and a multi-stage
  Java Dockerfile, on its own or with a `compose.yaml` and `.dockerignore`.
- **Projects:** a Python project and a Node.js TypeScript project (which installs
  TypeScript 5.9; see [why](/docs/lsp#typescript-and-javascript)). The
  TypeScript project's `npm start` runs `tsx src/index.ts`, and its `npm test`
  runs `node --import tsx --test` on the `src/**/*.test.ts` files; `tsx`,
  `typescript` and `@types/node` are its dev dependencies, and `npm run build` compiles to
  `dist/` with `tsc`.

Every field the wizard shows starts filled in, so pressing **Create** without
changing anything gives a working file.

### Template syntax

A template body is a file, not a snippet. Exactly three forms are special, in a
body, a file name, or a path:

| Form | Meaning |
| --- | --- |
| `${variable}` | A variable: a built-in, or one the wizard asks for |
| `${variable:default}` | The same, with the value used when nothing supplies one. The wizard pre-fills it. |
| `${cursor}` | Where the caret lands when the file opens. It is removed from the text. |

Everything else is written as it is: `$1`, `$HOME`, `$@`, `${arr[0]}`, `$(date)`
and backslashes are literal, so shell and awk content needs no escaping. To
write a literal `${name}`, double the dollar: `$${name}`.

These variables are filled in for you rather than asked for: `${author}`,
`${projectName}`, `${packageName}`, `${packageDeclaration}`, `${fileName}`,
`${baseName}`, `${extension}`, `${date}`, `${year}`, and `${time}`.
`${packageDeclaration}` is a whole `package x.y;` line followed by a blank line,
or nothing when the file is outside a package. The snippet variables that start
with `TM_` or `CURRENT_` work in templates too.

### The wizard

The wizard asks for every variable that is not built in, plus the built-ins it
cannot work out. That includes the template's main name (the class name, the
file's base name, or the package) when the file name depends on it, and the
project name when no project is open. Fields have readable labels: a template
can supply its own, and otherwise a name like `issueTitle` is shown as *Issue
title*.

The wizard has a **Folder** field (with a Browse button). For a single-file
template it is optional: leave it blank to create an unsaved buffer, or pick a
folder to write the file to disk. A multi-file template requires a folder.
Invoking **New → From Template…** from a project folder's right-click menu
pre-fills that folder. The two flows are also the `template.new` and
`template.newInFolder` commands.

Before anything is written, the wizard lists the files that already exist in the
folder. They are kept as they are; pressing **Create** again creates only the
missing ones. A template never overwrites a file. When it is done, the file that
holds `${cursor}` opens with the caret there.

Created files end with a line break and follow the project's
[`.editorconfig`](/docs/editorconfig): its line endings, and its
`indent_style` and `indent_size`. Template bodies are written with four-space
levels, and each level becomes a tab or the configured number of spaces. A
shell script is the exception for line endings: it is always written with LF,
because bash cannot run a script with CRLF line endings.

The **Node.js Script** template writes a `.js` file with a `#!/usr/bin/env node`
shebang and an `async function main(args)`. An error thrown from `main` is
printed and sets a non-zero exit code. It runs from its gutter ▶ (see
[Node.js scripts](/docs/run-debug#nodejs-scripts)). **New ▸ Scripts ▸
Node.js** in the Project tree's right-click menu creates the same kind of file
without the wizard.

A file that starts with `#!`, such as the shell, Zsh and Node.js scripts, is
created executable on Linux and macOS. On a file system without that permission
(Windows, FAT, some network drives) the status bar says so: run the script with
its interpreter (`bash tool.sh`), and mark it executable in Git with
`git update-index --chmod=+x tool.sh`.

### Java packages

A Java template created in a folder gets the package of that folder. Editora
looks for a `src/main/java` or `src/test/java` folder above the file, or a plain
`src` directly under the project root, and reads the package from the path below
it. The search stops at the project root, and folders such as
`src/main/resources` are not source roots. The Java Class template writes the
matching `package` line, or none outside a package.

### Your own templates

Drop template JSON files in your config folder under `templates/`. The file name
without `.json` is the template's id, and templates override each other by id in
the order bundled, plugin, user. The author name used by `${author}` comes from
**Settings → Templates → Author** (it defaults to your OS user).

- **Template: Manage…** opens **Settings → Templates** to add, edit, or remove
  single-file templates, including the ones plugins provide. Editing a bundled
  or plugin template saves a copy in your `templates/` folder that overrides it;
  **Remove** asks first, deletes your copy, and the original comes back. There
  is no Save button: a change applies on Enter, when you leave the field, or a
  moment after you stop typing. The **Id** is the file name, so it applies only
  on Enter or when you leave it, and a blank Id keeps the old one.
- **Template: Edit User Templates…** (`template.editUser`) opens a list: pick
  one of your templates to open as JSON, choose **New template…** for a starter
  file, or choose **Customize** on a bundled or plugin template to copy it to
  your folder. Multi-file templates are edited this way.
- **Template: Reload Templates** re-reads the folder. Saving a template file in
  Editora applies it at once.

A single-file template looks like this:

```json
{
  "name": "Java Class",
  "description": "A public Java class with a main method",
  "language": "java",
  "fileName": "${className:Main}.java",
  "labels": { "className": "Class name" },
  "body": ["${packageDeclaration}public class ${className:Main} {", "    ${cursor}", "}"]
}
```

- `name` is required. `body` is a string or an array of lines.
- `fileName` is the name of the created file and may contain variables.
- `language` sets the grammar of the created file when its name alone does not
  select one.
- `labels` maps a variable to the label its wizard field shows.
- A multi-file template has `"files": [{ "path": "...", "body": "..." }]`
  instead of `fileName` and `body`. Paths are relative to the chosen folder, may
  contain variables, and cannot lead outside that folder.

A file that is not this shape is skipped. The picker and **Template: Reload
Templates** name the file, the reason, and the line of a JSON syntax error.

A template you save carries `"schemaVersion": 1`, which says which format it is
in; a template without it loads as before. A template written by a newer
Editora in a newer format is not loaded: it is left exactly as it is, and a copy
is kept beside it (`<id>.json.v2.bak`, say).

### Starting a project

**New Project From Template** (`project.newFromTemplate`) uses a multi-file
template to start a project. Pick the template, then enter a **Project name**
and a **Location**; the wizard shows the folder it creates. Editora creates
`<location>/<name>`, writes the template's files there, and opens the folder as
a project in its own window on the template's main file. The location defaults
to the folder beside the active project, or your home folder. A folder that
already exists and is not empty is refused. The name you enter is
`${projectName}` and, in a form valid for a package (`my-app` becomes `my_app`),
the default for `${packageName}`.

The **Python Project** template writes a `src` layout: `src/<package>/` with
`__init__.py` and `main.py`, a `tests/test_main.py` that runs with `unittest` or
pytest, a `pyproject.toml` with a console command for `main()` and the pytest
settings that find `src/`, a `.gitignore`, and a README. The README's commands
work on the fresh project: create a virtual environment with
`python3 -m venv .venv`, install the package into it with `pip install -e .`,
then run it with `python -m <package>.main` (or the installed command) and test
it with `python -m unittest discover -s tests` or `pytest`.

The **Node.js TypeScript Project** template writes a module, a `node:test`
test, `package.json`, `tsconfig.json`, a `.gitignore`, and a README. Every other
multi-file template, yours included, appears in the same picker.

## New files by type

**File: New File of Type…** (`file.newFileOfType`, also **New** in the Project
tree's right-click menu) creates an empty or minimal file of a chosen kind
(a Java class, interface, record or enum, a Markdown file, a Python script,
YAML, and so on) in the current folder. It asks only for a name.

- The type's extension is added unless the name already ends in a known one, so
  `release-1.2` as a Markdown file becomes `release-1.2.md`.
- A Java type gets the `package` line of its folder, as described under
  [Java packages](#java-packages).
- A Shell file starts with `#!/usr/bin/env bash` and `set -euo pipefail`, and a
  Zsh file with `#!/usr/bin/env zsh`, `emulate -L zsh` and
  `setopt errexit nounset pipefail` (the same preamble as the Zsh Script
  template). The caret waits after a blank line. Both are created executable,
  and like templates they follow `.editorconfig`.
- A Python file starts like the Python Script template: a
  `#!/usr/bin/env python3` line, a docstring, a `main()` function and an
  `if __name__ == "__main__":` guard, with the caret in `main()`. It is created
  executable too.
- **HTML** starts with an HTML5 page: the doctype, `lang="en"`, the charset
  and viewport metas, and a `<title>` holding the name you typed, with the caret
  in the `<body>`.
- **XML** starts with an XML declaration and a `<root>` element, with the caret
  inside it. **XML Schema** (`.xsd`) starts with an `xs:schema` and one
  element, **XSLT** (`.xsl`) with a stylesheet holding the identity template,
  and **SVG** with an `<svg>` element and its `viewBox`.
- A name that cannot work is refused with the reason: a name Windows reserves
  (`CON`, `NUL`, `COM1`), one of the characters `< > : " | ? *`, a trailing dot
  or space, or a name that starts with `~`. For Java types, a keyword or a name
  with another extension (`Foo.txt`) is refused as well.

## Autocomplete overlap

Snippet prefixes also surface in [autocomplete](/docs/languages#autocomplete):
accepting a snippet from the completion popup starts a full tab-stop session
rather than inserting plain text.
