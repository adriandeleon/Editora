# File templates and New ▸ <type>

Two separate ways to create a file, both owned by `ui/TemplateCoordinator` and decided by pure code in
`template/`:

| Flow | Commands | Decided by |
| --- | --- | --- |
| **New ▸ <type>** (Project tree, palette) | `file.newFileOfType` | `NewFileCatalog` (the table of types), `NewFileContent` (what a typed name means, the Java package of a folder) |
| **New from template** | `template.new`, `template.newInFolder`, `project.newFromTemplate` | `TemplateRegistry` (loading), `TemplateEngine` (syntax, which variables to ask for), `TemplateOutput` (plan, then write) |

## Template JSON

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

- `name` is required and must not be blank. `body` is a string or an array of lines.
- A **multi-file** template has `"files": [{ "path": "...", "body": ... }]` instead of `fileName`/`body`.
  Paths are relative to the folder the user chooses and may contain variables.
- `language` (single-file only) sets the grammar of the created file when the file name alone does not
  select it; multi-file templates type each file by its own name.
- `labels` maps a variable to the label its wizard field shows. Without one, a variable Editora knows
  (`template.var.<name>` in the message catalogs: `className`, `baseName`, `packageName`, `title`, …) gets
  the translated label, and any other identifier is made readable (`issueTitle` → "Issue title").
- The id is the file stem. `index` is reserved (the bundled list is `index.json`).

A file that is not this shape is **skipped and reported** (`TemplateRegistry.problems()`): the picker and
*Template: Reload Templates* name the file, the reason, and the line of a JSON syntax error.

## Syntax

Exactly three forms are special in a body, a file name or a path:

| Form | Meaning |
| --- | --- |
| `${name}` | a variable: a built-in, or one the wizard asks for |
| `${name:default}` | the same, with the value used when nothing supplies one (and the wizard's pre-fill) |
| `${cursor}` | where the caret lands; removed from the text |

`name` is a letter or `_` followed by letters, digits or `_`. **Everything else is literal**: `$name`, `$1`,
`$@`, `${arr[0]}`, `${1:-x}`, `$(date)` and backslashes are written as they are, because a template body is
a file, not a snippet. To write a literal `${name}`, double the dollar: `$${name}`.

Templates deliberately do **not** go through `snippet/SnippetParser`: its `$1` tab stops, `$name`
variables and backslash escapes silently ate shell and awk content.

Built-ins (`TemplateVariableResolver`): `author`, `date`, `year`, `time`, `projectName`, `packageName`,
`packageDeclaration` (a whole `package x.y;` line plus a blank line, or nothing outside a package),
`fileName`, `baseName`, `extension`, and the snippet variables `TM_*` / `CURRENT_*`.

The wizard asks for every variable that is not a built-in, plus the built-ins the context cannot supply
(`TemplateEngine.promptedVariables`): the file-identity names when they appear in the file-name pattern or
anywhere in a multi-file template, `packageName` unless the template creates a single Java file (whose
package comes from its folder), and `projectName` when there is no project.

## Where templates come from

`bundled < plugin < user`, each overriding the previous by id. Nothing is cached except the bundled set:
every window has its own registry over the same folder, so a cache made a change in one window invisible
in the others.

## Applying

`TemplateOutput.plan` works out every target first and writes nothing. A path that is not a relative,
portable path really inside the target folder (`PathContainment.isWithin`, so a symlinked folder does not
lead out) refuses the whole template. Files that already exist are listed in the wizard, which stays open;
pressing Create again creates only the missing ones. `TemplateOutput.write` never overwrites
(`CREATE_NEW`), stops at the first failure and reports what it had created. Both run off the FX thread.

Written files end with a line break and follow the project's `.editorconfig` `end_of_line` /
`insert_final_newline`; a file that starts with `#!` is made executable on POSIX. The file that marks
`${cursor}` is the one opened, through `FileWorkflowCoordinator.openThen` so the caret is placed after the
text has loaded.

A multi-file template is never applied without a folder. *New Project From Template* asks for a name and a
location (default: beside the active project, else the home folder), creates `<location>/<name>/` (refusing
one that exists and is not empty), and opens it as a project; the name is `${projectName}` and the default
for `${packageName}` (`TemplateNames.packageNameFor`).

## New ▸ <type> names

`NewFileContent.decide` returns the path to create or a `Refusal` with its own message. The type's
extension is appended unless the typed name ends in a known one (`PortableFileName.isKnownExtension`).
Refused on every OS: Windows device names, `< > : " | ? *`, control characters, a trailing dot or space,
`~`, a trailing slash; for Java kinds also keywords, a name with another type's extension (`Foo.txt`), and
`package-info.java` outside a package.

`NewFileContent.packageFor(dir, projectRoot)` never looks at or above the project root. Below it a source
root is `src/main/java` or `src/test/java` at any depth, or a plain `src` directly under the root that does
not hold the Maven layout. With no project only the two conventional markers count.
