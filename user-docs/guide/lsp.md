---
title: Language servers (LSP)
description: Go to definition, code actions, Java refactorings, rename, signature help, inlay hints, code lenses, hierarchy, diagnostics, and completion from 26 language servers.
category: Code intelligence
order: 2
---

Editora speaks the **Language Server Protocol**, so with a server installed you
get real language smarts. LSP is **on by default**: each server starts once it
is installed, and a file whose server is missing offers to install it. In a
folder you have not trusted, servers that would run the project's build wait
(see [Servers that load a project's build](#servers-that-load-a-projects-build)).
The master switch is **Enable LSP (language servers)** in **Settings → LSP**.

## Navigation

| Feature | Command | Default key |
| --- | --- | --- |
| Go to definition | `lsp.gotoDefinition` | `M-.` |
| Peek definition | `lsp.peekDefinition` | (palette) |
| Go to definition in a split | `lsp.gotoDefinitionInSplit` | (palette) |
| Go to implementation | `lsp.gotoImplementation` | `C-c l i` |
| Go to type definition | `lsp.gotoTypeDefinition` | `C-c l d` |
| Go to declaration | `lsp.gotoDeclaration` | (palette) |
| Find references | `lsp.findReferences` | `M-S-/` |
| Go to Symbol in Workspace | `lsp.gotoSymbol` | `M-g s` |
| Call hierarchy | `lsp.callHierarchy` | `C-c l c` |
| Type hierarchy | `lsp.typeHierarchy` | `C-c l h` |
| Next / previous problem | `lsp.nextProblem`, `lsp.previousProblem` | `M-g M-n`, `M-g M-p` |
| Show problem at caret | `lsp.problemAtCaret` | `C-c l e` |

![Shelf.java with the caret on discountFor and the References tool window below: 2 references to discountFor in 2 files, one in Shelf.java at 48:41 with its line, and one in ShelfTest.java](/screenshots/docs/lsp-references.png "Find references in a Java project, grouped by file.")

In the References and Hierarchy tool windows a row's right-click menu (or the
Menu key, Shift+F10) has Open and Copy, and the Copy key copies the selected row;
the commands are References: Open Reference / Copy Line
(`references.openSelected`, `references.copySelected`) and Hierarchy: Open
Declaration / Copy Name (`hierarchy.openSelected`, `hierarchy.copySelected`).

The goto, references and hover commands are also in the editor right-click menu
while a server is active, under a single **LSP** submenu, and you can
**Ctrl/Cmd-click** a symbol to jump to its definition. **Go to Implementation**
and **Go to Type Definition** join that menu only when the server
supports them, so it never offers a dead entry. **Go to Super Method** (Navigate
menu, and this submenu for Java) jumps from anywhere in a method, its `@Override`
and Javadoc included, to the method it overrides or implements. That includes an
anonymous class's method, wherever the class is written: in a method, a field, an
initializer or an enum constant. The type hierarchy fills in what the server
misses, so an interface's default method and a method two interfaces declare both
have somewhere to go; of two where one overrides the other, the nearer one is
offered. A static or private method, or another overload, has no super method. In
a lambda it goes to the functional interface's method, in a constructor to the
super constructor it calls (through `this(...)` too), and from a class to its
supertypes, the superclass first and each named by its type (never
`java.lang.Object`, as in IntelliJ). On an anonymous class itself (its header,
from the type's name to its `{`, or a field in it) it opens the type the class
instantiates, also for a nested type written with `<>`, such as
`new Outer.Mid.Sink<>() {…}` or an imported `Map.Entry`. A comment right above a
method, Javadoc or `//`, counts as the method's. Several targets open a picker,
and it works the same in a library source tab.

The submenu does not change which actions a server contributes. A
fully-featured server offers up to eight. Grouping them keeps cut, copy, paste
and the spelling suggestions near the top of the menu, and labels entries such
as "Go to Definition" as coming from the language server.

When an implementation lives in the JDK or a dependency, Go to Implementation
lists every implementation in a picker, each with its package and a nested
class by its own name (`AbstractList.SubList`), and opens the one you choose as
read-only source.

**Find references** and Go to Implementation list multiple results in a browsable
**References** tool window (`tool.references`), grouped by file with a line and
preview; a single result jumps straight there. When the file has an open tab,
the preview is the reference's line with the editor's syntax highlighting, in
the active editor color theme; for a file with no tab the line is read from disk
in the background and filled in a moment later. Each file's header names its
folder, so two `Main.java` in different packages can be told apart, the summary
names what was searched ("5 references to area in 4 files"), and a reference
that writes the symbol (an assignment, `+=`, `++`) is marked **(write)**. The
declaration itself is not listed, so Find References on an unused symbol says
there are none. A request that fails or times out says so instead of "No
references".

**Go to Symbol in Workspace** opens a live search over every symbol in the
project, methods included, with JDK and library types after the project's own,
labelled with their module or jar. With a Java server running,
[Search Everywhere](/docs/navigation#search-everywhere) lists those library
classes too, in the same order. Where no server is running, Editora's own
[project symbol index](/docs/code-navigation#go-to-symbol-in-project) answers the
same question.

**Peek Definition** shows the definition over the editor instead of taking you
to it, leaving your place, scroll position and tab count where they were; Enter
commits to the real jump. **Go to Definition in a Split** puts the definition in
a new editor group beside the code that referenced it. Both are covered in
[Code navigation](/docs/code-navigation).

**Definitions inside libraries work too.** A Java definition that lives in the
JDK or a dependency is fetched from the server and opened in a read-only,
highlighted tab on the declaration, not the start of its Javadoc. Inside that
tab the language server keeps working: Go to Definition, hover, Find References
(which finds your project's calls), Go to Implementation, Type Definition and
Declaration, Peek Definition and both hierarchies, onward into another library
class or back into your own code. Peek Definition on a library member shows its
source in the peek window rather than opening a tab.

**Call and type hierarchy** open in a **Hierarchy** tool window. Call hierarchy
shows who calls the method under the caret, or what it calls (flip with the
Callers / Callees toggle); a member called more than once is one row with its
count, as in `append(String) ×2`. Type hierarchy shows a type's supertypes and
subtypes, and on an interface it opens on the subtypes, its implementations.
Each level is fetched from the server as you expand it, and Enter or a
double-click jumps to any entry, a JDK or library one included.

**Next Problem** and **Previous Problem** move the caret to the file's next or
previous error, wrapping at the end (its warnings when it has no errors), and say
what it is; **Show Problem at Caret** says the problem under the caret, or the
worst one on its line.

## Reading the code

| Feature | Command | Default key |
| --- | --- | --- |
| Hover docs | `lsp.hover` | `C-c h` |
| Signature help | `lsp.signatureHelp` | `C-c l s` (also automatic) |
| Toggle inlay hints | `view.toggleInlayHints` | (palette) |
| Toggle code lenses | `view.toggleCodeLens` | (palette) |

**Hover docs** open in a popup at the caret. Long documentation, such as the
man page bash-language-server shows for a command like `ls`, scrolls inside a
popup no taller than part of the window. **Page Down** and **Page Up** scroll it
from the keyboard (and `C-v` / `M-v` in the Emacs keymap); Escape closes it. A
man page gets its full 80 columns.

![A hover popup over discountFor in Shelf.java showing the signature Discount com.example.shelf.Pricing.discountFor(Item item), its Javadoc and its parameter](/screenshots/docs/lsp-hover.png "Hover docs: the signature and Javadoc at the caret.")

**Signature help** pops the server's overload list when you type `(` or `,` in a
call: the active signature with the current parameter highlighted, an *n/m*
overload counter, and the signature's documentation (for Java, the method's
Javadoc, led by the current parameter's own `@param` text). It follows your typing as
arguments are entered, stays open across multiline calls, and lets you move
between overloads with `lsp.nextSignature` and `lsp.previousSignature`. It
closes when the caret leaves the call, on Escape, or on scroll. Finished
zero-argument calls and method references do not open an irrelevant signature
popup.

**Inlay hints** are the server's parameter-name and inferred-type annotations,
drawn in grey italics inline, at the position they describe. They are **off by
default**; turn them on in the **Language server features** card of Settings →
Code Completion or with `Toggle Inlay Hints`. A hint keeps the space the server
asks for, so a lambda's type reads `String s ->`. By default a parameter-name hint
appears only where the argument is a literal; **LSP: Set Inlay Hint Filter**
(`lsp.setInlayHintMode`) switches between that and hints on all arguments. Type hints are never filtered; in Java
they show the inferred type of a `var` and of a lambda's parameters. Arguments
named only by number (`List.of(e1: 1, e2: 2)`) get no hint. They cost nothing
while off. Not every server gives them; Pyright, for one, does not. Turning inlay
hints on in such a file says so in the status bar (*Inlay hints are on, but
Pyright gives none for this file.*), and **Set Inlay Hint Filter** says there is
nothing to filter instead of opening its list. Turning on **View: Toggle
Semantic Highlighting** (`view.toggleSemanticHighlight`) gives the same notice
when the file's server sends no semantic tokens, and the file keeps its grammar
colours.

**Code lenses** are the server's reference and implementation counts, drawn
after a declaration (`3 references`, `2 implementations`). A lens lights up
under the pointer, and a click opens what it counts. A references lens always
opens the **References** tool window with its first row selected, also for a
single reference and for `0 references`, which shows the empty list. An
implementations lens runs Go to Implementation. When a declaration has more
than one lens, a short list asks which one. They are **off by default**,
because each count is a search on the server; turn them on in Settings → Code
Completion ("Code lenses") or with `view.toggleCodeLens` (**View: Toggle Code
Lenses**). Turning them on in a file whose server gives none, such as a Python
file under Pyright, says so in the status bar.

**Occurrence highlighting** needs no command: rest the caret on a symbol and
every occurrence in the file gets a subtle wash, with writes (assignments) shaded
warmer than reads. It is semantic rather than textual, so a local `x` does not
light up an unrelated field `x`, and it clears the moment the caret moves.

**Diagnostics** appear as inline squiggles, in the **Problems** tool window
(`M-8`), and as marks on the minimap and scrollbar. A hint (ShellCheck's
"style" level, for example) is drawn as a faint dotted line rather than a full
squiggle, code the server marks as unused, such as a variable nothing reads,
is drawn faded, and a call the server marks as deprecated is struck through.
Those unused and deprecated marks are not listed in the Problems window or
counted by the problem counter.

![Shelf.java with a red squiggle under a type error on line 44, a red marker in the gutter, the problem counter at the top right of the editor reading 1, and the Problems tool window below: 1 problem in 1 file, grouped under Java and Shelf.java](/screenshots/docs/lsp-problems.png "Diagnostics: a squiggle in the editor, a counter at the top right, and the Problems window with the list.")

The **problem counter** at the top right of the editor shows the file's errors
and warnings, and a muted count of notes (information and hints, such as
ShellCheck's quoting advice). The green check means the server found no
problems at all. Until the server has
checked the file it says **Analyzing…** (for Java, until the project has loaded,
and again after a restart), and **Not checked** when the file's project did not
import. Its arrows go to the previous and next problem in that
file, notes included, and clicking the counts shows or hides Problems. While
the Problems window is open, its arrows and **Next Problem** / **Previous
Problem** select the problem they reach there too, and the focus stays in the
editor. It hides when the code
pane is narrower than 520 pixels. When the caret rests on a line where the
server can **fix an error**, a red **lightbulb** with a `!` appears in the
gutter; an amber one marks other quick actions, a warning's fixes included.
Click it for the list **Code Actions** (`C-c l a` in Emacs, `Ctrl+.` in the
other keymaps, also `Alt+Enter` in IntelliJ) shows, for its own line.
Refactorings, source actions and Add Javadoc, which a server offers almost
everywhere, light no bulb, and the bulb goes as soon as you edit its line. In a
split it shows only in the view you are typing in; a tab you come back to keeps
it; and its colours come from the editor theme (in Summer Dark, Navy Dark, Fall
Dark and Blacky, whose accent is the bookmark's yellow, the quick-action bulb takes
the text colour). Both
can be turned off in Settings → Code Completion → Language server features.
**Completion** is merged into
the [autocomplete](/docs/languages#autocomplete) popup, which keeps every row the
server sends (`os.` in Python lists all of the module's members). When the
server has not answered after 300 ms, as Pyright may not on a very large file,
the popup shows the rows Editora has without it, with **Waiting for Pyright…**
(or the name of the file's server) under them, and the server's answer replaces
them when it comes. The **Structure**
tool window builds its outline from the server's document symbols, with real
kinds, per-kind icons and method signatures. A server that lists its symbols
flat, as bash-language-server does, has them nested under the function that
contains them, and a variable assigned several times in one scope is listed
once. In a `pom.xml` the outline names what the XML server lists only by
element: a `dependency`, `plugin`, `exclusion` or `extension` by its
`groupId:artifactId`, and a `profile`, `execution`, `repository` or
`pluginRepository` by its `id`, so five "dependency" rows become a list you can
pick from.

Each row in the Problems window ends with the rule's code when the server gives
one, for example `[SC2086]` (but not jdtls's numeric problem ids, which link
nowhere). Right-click a row for **Open Rule Documentation**,
which opens the rule's page (ShellCheck links every code to its wiki), and
**Copy Code**, which puts the code on the clipboard.

Java completion reacts immediately after member access and begins identifier
completion after a short pause. Its documentation popup shows the item's Javadoc;
the **Excluded types** in the Language server features card (vscode-java's list,
`sun.*`, `com.sun.*`, `jdk.*` and the like, plus `java.awt.*` by default) are not
offered, imported or listed by Go to Symbol and Search Everywhere; JUnit and Mockito
statics such as `assertEquals` and `mock` are offered without an import, which
is then added; and in an argument or assignment it also offers a call chain that
gives the expected type, such as `Collectors.toList()` inside `collect(`. The
list matches what you type against the name, not the parameter list. Enter and Tab accept the selected item without
also inserting a newline or indentation; complete result lists filter locally,
and obsolete requests are cancelled as you continue typing. Overloads remain
distinct, commit characters and server ranges are honored, and method
completion reuses parentheses already in the file before requesting signature
help. Auto-imports can safely follow continued typing and remain grouped with
the completion for undo and redo; an answer that can no longer be rebased is
dropped instead of touching newer text.

**Smart Type completion** (Edit → Smart Type Completion; Ctrl+Shift+Space in the
IntelliJ keymaps) opens the list with only what fits the type the code expects
at the caret, and the status bar names that type. Editora reads the type from:

- a declaration (`String s = `);
- an assignment, using the type the name was declared with;
- `return`, using the method's return type;
- a condition (`if (`, `while (`, after `&&`, `||` or `!`), which expects
  `boolean`;
- a call's argument, using the parameter that signature help shows, in every
  overload the call could be: `System.out.println(` offers an `int` as well as a
  `char[]`, and the status bar names the other types.

A generic method's type variable takes the type the line declares, matched
against the method's return type: `List<String> l = List.of(` expects a `String`,
and `String[] a = names.toArray(` a `String[]`. Inside another call's argument
(`names.contains(requireNonNull(`) it is left open.

The list keeps the fields, variables and methods of a fitting type, `null` for
an object, and `true` and `false` for a boolean. After `new`, it keeps the
classes that implement the type (`ArrayList` for a `List`). A class of your own
that implements or extends the expected type stays too, because jdtls ranks it
as a match. Where the code expects no particular type, the full list opens and
the status bar says so.

The Problems window has an **Open files / Whole project** selector
(`lsp.toggleProjectProblems`), plus a **Build Project** command
(`lsp.buildWorkspace`) that recompiles the Java project and fills it; it says
when the build found errors. With **Open files** selected, problems come only
from files you have open, so a compile error in a file you haven't touched is
not listed. Open files is the default, so the window lists more only when you ask
for it. With **Whole project** selected, saving a Java file runs a quiet
incremental build, so an error in a closed file clears once its cause is fixed.
With Open files, the same build runs when another file of the project is open, in
this window or another one on the same project, so
a file calling a method you just renamed elsewhere shows the error at once.

For Python, **Whole project** has Pyright check every Python file of the
project, opened or not, which can take a while on a large one; **Open files**
has it check only the open ones. **Build Project** on a Python file switches
Problems to Whole project and says so in the status bar.

Problems of the project rather than of a file, such as a JDK version the build
expects and the machine does not have, are reported after a Maven or Gradle
project is imported: said in the status line, and listed in Problems in either
scope.

## Changing the code

| Feature | Command | Default key |
| --- | --- | --- |
| Code actions / quick fixes | `lsp.codeActions` | `C-c l a` |
| Generate (Java) | `lsp.generate` | `C-c l g` |
| Refactor This (Java) | `lsp.refactorThis` | `C-c l t` |
| Undo Last Refactoring | `lsp.undoRefactoring` | `C-c l u` |
| Rename symbol | `lsp.rename` | `C-c l r` |
| Format Document (or the selection) | `lsp.formatDocument` | `C-c l f` |
| Organize imports | `lsp.organizeImports` | `C-c l o` |
| Clean Up File (Java) | `lsp.cleanUp` | (palette) |
| Smart Type completion (Java) | `lsp.smartTypeCompletion` | (palette) |
| Copy fully qualified name | `lsp.copyQualifiedName` | (palette) |
| Reload project configuration | `lsp.reloadProject` | (palette) |
| Re-indent as you type | `view.toggleOnTypeFormatting` | (palette) |
| Add imports for pasted Java | `view.togglePasteImports` | (palette) |
| Smart semicolon placement | `view.toggleSmartSemicolon` | (palette) |

**Code actions** ask the server what it can do at the caret or selection: quick
fixes for the diagnostics there, assists, and extract and inline refactorings.
The list **opens at the caret**, where you are looking, with the fixes first,
then assists, refactorings and source actions, each row labelled **Fix**,
**Assist**, **Refactor** or **Generate** in words, and a title the server offers twice listed
once. The fixes for a line's problem are offered from anywhere on that line, not
only on the squiggle. Typing narrows the list to titles containing what you type
(Backspace widens it again) instead of editing the code behind it; arrow keys or
`C-n` / `C-p` move, Enter applies, Escape or `C-g` dismisses. Every action shape is supported, including a server-side command whose
edits
arrive back over `workspace/applyEdit`. An edit to the open file is one undo
step; one that changes other files is applied and saved as one
[refactoring](#java-refactorings-that-ask-a-question), undone together. `Ctrl-.` / `Cmd-.` is bound in the VS Code, Sublime and IntelliJ keymaps;
the command is in the palette and the right-click menu in every keymap.

**Organize Imports** works the way the file's server does it: jdtls's own
request, Pyright's organize-imports command, or the server's organize-imports
code action. If the server has none of these, the status bar says so. On a
Python file **Format Document** uses Ruff or Black; see
[Formatting Python](#formatting-python). Pyright offers no quick fixes of its
own, so on a Python file Editora adds **Import**, **Remove unused import** and
**Ignore on this line**; see [Python quick fixes](#python-quick-fixes).

A multi-file edit is applied **completely or not at all**. An action that would
delete a folder or replace an existing file asks first and names the paths, and
whatever an edit deletes or replaces is copied to
[local file history](/docs/workspace#local-file-history) beforehand. If Editora
is interrupted in the middle of an edit that moves or deletes files, the next
time the project is opened an **Interrupted Refactoring** dialog offers to put
the files back.

![Shelf.java with the caret on a line whose type is wrong and the code actions list open at the caret: two Fix rows, Change type of label to double and Change type of total to String, then an Assist row, Add final modifier for label, each labelled in words](/screenshots/docs/lsp-code-actions.png "Code actions open at the caret: fixes first, then assists and refactorings.")

**Java code generation** has its own list, **Generate…** (`lsp.generate`,
Alt+Insert in the IntelliJ keymap): **Generate toString()**, **Generate
hashCode() and equals()**, **Generate Constructors**, **Generate Getters and
Setters**, **Generate Delegate Methods** and **Override/Implement Methods**
(`lsp.overrideMethods`, Ctrl+O in the IntelliJ keymap). For Java they are not
repeated in the quick-fix list. Each opens a checkbox list so you choose which
fields or methods to include, with Space to toggle and Enter to generate;
hashCode() and equals() starts with every field ticked, and Enter with nothing
ticked says so. Delegate Methods asks for the field to delegate to first, then
lists that field's methods. The server offers these only when the editor
declares that it can drive the picker, and Editora does.

![The Generate… list over Shelf.java: Generate Getters, Generate Constructors…, Generate hashCode() and equals()…, Generate toString()…, Override/Implement Methods… and Generate Delegate Methods…, each labelled Generate](/screenshots/docs/lsp-generate.png "Generate… on a Java class.")

**Refactorings have commands of their own**, in the **Code ▸ Refactor** submenu
and the palette: **Refactor This…** (`lsp.refactorThis`) lists the ones at the
caret, and **Extract Variable**, **Extract Constant**, **Extract Field**,
**Extract Method**, **Introduce Parameter**, **Inline**, **Move…** and **Change
Signature…** (`lsp.extractVariable`, `lsp.extractConstant`, `lsp.extractField`,
`lsp.extractMethod`, `lsp.introduceParameter`, `lsp.inline`, `lsp.move`,
`lsp.changeSignature`) each run the one jdtls offers there.

The extract refactorings work **at a bare caret**: with nothing selected Editora
asks the server which expressions are there and lets you pick one when there are
several. Extract to Field and Convert Local to Field ask where to initialize the
field when there is a choice. After an extraction, **Rename opens on the name the
server picked** (`d`, `getTotal`), so you can name it at once.

### Java refactorings that ask a question

Three refactorings in the Code Actions menu need more than the caret position,
so each one asks before it runs. They need the Java language server.

**Move** depends on what the caret is on:

| On | Moves it to |
| --- | --- |
| A class | another package, chosen from a list |
| A nested class | its own new file, or another class |
| A static member | another class |
| An instance method | one of its parameters or fields |

Moving a class lists the packages of its own source folders (a class in main
code: the main packages only), and ends with **New package…**: type a package that does not exist yet and its folder
is created under the current source folder. A Move Instance Method that jdtls
finds problems with shows them first and moves only if you continue.

**Extract Interface** shows the class's methods as a checkbox list, then asks
for the interface's name and its package.

**Change Signature** presents the method's signature as one line of text to
edit:

```java
public String greet(Helper helper, int n)
```

In that line you can rename the method, change its visibility or return type,
and reorder, rename, retype or remove parameters. To **add** a parameter, write
it as `type name = value`; the value is what existing callers pass for it:

```java
public String greet(Helper helper, int n, boolean loud = false)
```

A line that cannot be read as a method signature is rejected and nothing
changes. When Change Signature or Move changes other files, they are listed
first, ticked, the way a [rename](#rename-preview) is, and a file you untick is
left alone.

**A refactoring is one change across files.** Every file a refactoring renames,
moves or creates is saved, so the project compiles straight away, and a file it
opened only to edit is saved and its tab closed. Renaming a `.java` file in the
Project tree, or dragging it (or a package folder) into another package, is such a
refactoring too: the class is renamed or its package line changed with it, and
the files that use it are updated; **Undo Move** moves it back the same way.
Dragging several files is one refactoring: **Undo Move** and **Undo Last
Refactoring** each take all of them back, and either one ends the other. Dragging
only files jdtls has nothing to change for, such as a text file, is no
refactoring, so the one before stays undoable. One drag runs at a time: a drop
while one is under way says to drop again when it is done, and Undo Move says to
undo it when it is done. While jdtls is still loading, or
does not answer, Editora asks once before it moves Java files without updating
their package lines. **Undo** in one of those files
offers to undo the refactoring in all of them, and **Undo Last
Refactoring** (`lsp.undoRefactoring`) does that from anywhere, moving renamed
files back and removing created ones. A rename onto a name that is already taken
is reported after it lands, with the way back.

Three commands sit outside the menu: **Organize Imports** sorts and prunes a
file's imports directly (in Java, JavaScript and TypeScript), **Copy Fully Qualified Name** puts the full name of the
symbol at the caret on the clipboard, and **Reload Project Configuration** makes
the server re-read `pom.xml` or `build.gradle`, also from the `pom.xml` tab
itself. A build file you save is picked up on its own, so Reload is rarely
needed. In Java, Organize Imports asks which class an ambiguous name such as
`Date` or `List` means, and so does the **Add all missing imports** quick fix.
The question waits for you: it does not time out while it is open, and goes
away when the server stops (a restart). A second Organize Imports while it is
open says to answer it first, and a save does not organize the file under it.
If the file changes while the question is open, the answer no longer applies and
the status says to run Organize Imports again.
After a failed Gradle import, saving the fixed build file imports the project
again, as **Reload Project Configuration** does from any of its tabs.

**Rename symbol** renames across the whole workspace. The prompt comes pre-filled
with the current name, and the server validates the spot first, so you are told
up front when something cannot be renamed. Renaming a public Java class also
**moves its `.java` file**, and the tab, session state and language server all
follow to the new path. Renaming a `.java` file or a package folder in the
**Project** tree renames the class or package too, so references, `package`
lines and imports follow. `F2` is bound in the VS Code and Sublime keymaps, and
`Shift+F6` in the IntelliJ keymap.

![The Rename Symbol prompt over Shelf.java: a New name field pre-filled with discountFor, the symbol under the caret, with Cancel and OK buttons](/screenshots/docs/lsp-rename.png "Rename symbol: the prompt comes pre-filled, and the server has already checked the spot.")

<a id="rename-preview"></a>A rename that reaches **beyond the file you're looking at** shows you what it
will do first: every affected file, its change count, and where it moves to, with
a tick beside each one so you can leave a file out. The preview also lists any
file or folder the rename **would delete** and any existing file it **would
replace**, each marked as such. A rename confined to the current file applies
straight away, because the result is on screen and one undo away.

**Format Document** reformats the whole file through the server when it advertises
formatting (undoable, palette or right-click menu), including `.json`, `.css` and
`.html` (for HTML, see [Formatting](/docs/html#formatting) on the HTML page:
`<pre>` and `<textarea>` are kept as written, and without the server Editora
formats it itself). With a selection it formats only the selected lines. It tells the server the file's own indentation: the `indent_style` and
`indent_size` from [EditorConfig](/docs/editorconfig) when there are any, else
what the file already uses, not the global tab size. A two-space script stays
two-space. It also passes the file's `trim_trailing_whitespace` and
`insert_final_newline` (or the project's save rules), so the formatted file
already looks the way saving writes it; the XML server adds the final newline
only when asked. In a language whose server supports range formatting, **Tab** also snaps
the current line's indentation to the server's convention.

### Java-only assists

**Pasted code imports itself.** Paste a snippet into a Java file and the server
is asked which imports the pasted text needs; they are added for you, and only
for names the pasted text uses. Only the
language server can do this, because it needs the type resolver. An answer that
lost a race with your typing is dropped rather than applied to text it wasn't
computed for. In the Emacs keymap, a yank (`C-y`) and each `M-y` get the imports
their text needs, each kill ring entry with the file it was killed from, and `M-y`
keeps working after those imports land.

**Pasting into a string escapes the text.** Pasted between the quotes of a Java
string, `C:\temp "x"` becomes `C:\\temp \"x\"`, and pasted lines become
concatenated strings, each ending in `\n`. Text copied from inside a string
literal pastes as it is, already escaped. In a text block only backslashes (and a
`"""`) are escaped, and in a char literal its backslash and quote. One Undo takes
the paste back.

**Smart semicolon placement.** Typing `;` part-way through an expression puts it
at the end of the statement: `compute(1, 2|)` becomes `compute(1, 2);|`. The
semicolon is never held back waiting for the server, since that would make a
keystroke wait on a round trip. It lands where you typed it and moves afterwards
if the server disagrees, as a single undo step. If you keep typing (the very
common `;` then Enter), the document has moved past the answer, so the
correction is skipped rather than applied late.

Both need the Java language server running. Pasted-code imports are **on by
default**; smart semicolon placement is **off by default**. Switch either in
Settings → Code Completion, or with `view.togglePasteImports` and
`view.toggleSmartSemicolon`.

**Re-indent as you type** does the same on `;`, `}` and Enter. It is indentation
only, never a reformat of the line under you, and the local auto-indent still
acts first, so nothing feels slower and the server only corrects the cases the
heuristic gets wrong. Off by default: turn it on in Settings → Code Completion or
with `view.toggleOnTypeFormatting`.

**A Javadoc for the declaration below.** Typing `/**` above a method or class
closes the comment and offers the server's **Javadoc comment** template, with
`@param`, `@return` and `@throws` for that declaration. Pressing Enter instead
splits the comment as usual.

**Organize imports and format on save.** **Organize imports on save (Java)** and
**Format on save (Java)** in Settings → Code Completion → Language server
features tidy a Java file after you save it, and save it again when that changed
it, as IntelliJ's Actions on Save do. Both are off by default. Autosave never
runs them, since organizing imports while you type would remove one you have
just added, and an ambiguous import is left out rather than asked about. While
Organize Imports is asking you which class to import, a save runs none of them,
so your answer still applies.

**Clean-ups.** **Code → Clean Up File** applies the jdtls clean-ups you tick
under **Java clean-ups → Choose…** on the same Settings page: add `@Override`
and `@Deprecated`, remove redundant modifiers and `super()` calls, qualify
members, add `final`, use pattern-matching `instanceof`, switch expressions,
lambdas, try-with-resources and text blocks, and more (19 in all). The first
four are on by default; the others rewrite code, so they are yours to pick.
**Clean up on save (Java)** runs them after an explicit save, before Organize
Imports and Format.

### The Java server and your project

- **Untrusted folders.** In a folder you have not trusted, jdtls starts
  without the project's Maven or Gradle build, and the file says so with
  **Trust Folder**; see
  [Servers that load a project's build](#servers-that-load-a-projects-build).
- **Which jdtls.** Install fetches jdtls **1.63.0**. [Doctor](/docs/troubleshooting#doctor)
  names the installed version and warns when it is older, with an
  [Update…](/docs/troubleshooting#update-for-servers-older-than-editoras)
  button that installs 1.63.0 and restarts the server on it.
- **Which JDK.** jdtls itself needs a recent Java (25 for jdtls 1.63, read from
  the installed jdtls), and is given one Editora finds even when `JAVA_HOME` is
  older; when there is none, it says so, and Doctor's jdtls row names the Java
  it runs on and its version. Of two JDKs of one version, a project gets the one
  with its sources, so JDK parameter names and docs show; when no JDK of that
  version has them, Doctor says which package adds them, reading the JDK the build
  compiles with (the compiler plugin's `<release>`, a parent pom, a Gradle
  toolchain or version catalog). The
  default JDK for your projects, which Gradle also runs on, is Settings → Build
  Tools → Default JDK, else the JDK of the `java` on your `PATH`, else
  `JAVA_HOME`, else the newest JDK installed. It is the JDK a Java file without a
  build tool runs on, so jdtls builds a loose file or a plain folder for the
  Java that runs it, and a JDK class opened while debugging shows that JDK's
  source. A changed Default JDK reaches a running jdtls at once; only the Java
  jdtls itself runs on waits for **Restart Language Servers**. The Gradle daemons
  jdtls starts for its imports stop after 10 idle minutes.
- **Library sources.** A Maven library class you open (with Go to Definition,
  say) gets its sources when a repository has them: the tab shows the real code,
  with its parameter names and Javadoc. A class that has none is shown
  decompiled, and its tab says so with two buttons: **Download Sources** looks in
  your local repository and on Maven Central (whose search it can ask by the
  jar's checksum), and **Choose Sources…** attaches a `-sources.jar` or
  zip from disk, for a `lib/` jar or an artifact only installed locally. The
  palette has both as **LSP: Download Library Sources**
  (`lsp.downloadLibrarySources`) and **LSP: Choose Library Sources…**
  (`lsp.attachLibrarySources`). A Gradle library's sources come from its build,
  and a JDK class without sources is a JDK with no `src.zip` (see Which JDK
  above). Once a library has its sources, every open tab of it shows them at
  once. To fetch every dependency's sources up front, turn on Settings → LSP →
  **Download library sources when a Java project is imported** (off by default;
  Maven only), or run **View: Toggle Downloading Library Sources at Import**
  (`view.toggleJavaDownloadSources`). It applies at the next import; **LSP: Clean Java
  Workspace and Restart** applies it now.
- **A failed import says why.** When jdtls cannot import the project, the status
  bar names the reason, each of its open files says **Not checked**, and Problems
  lists the build file's own errors. That covers a Gradle build that does not run
  (a settings file that `include`s a folder that does not exist, say), a pom that
  does not parse, and a pom Maven cannot build a model of: a dependency without a
  `<version>`, a parent it cannot resolve. A failure that belongs to one module or
  folder is named with it, as in **jdtls (app): the project did not import**, and
  the rest of the project stays checked. A module with
  `<packaging>pom</packaging>` that holds Java sources says Maven compiles none of
  them. Fix the build file and save it, or run **Reload Project Configuration**:
  the project imports again, after a pom's model error with a cleaned workspace,
  and a fix that arrives by `git pull` or another editor does the same. A project
  that imports with build problems (a Maven dependency or a Gradle one that cannot
  be found) is loaded and checked, and Problems lists them. A file the build
  leaves out on purpose (a nested sample project, `buildSrc`, an excluded source)
  does not make a healthy project look broken.
- **Your project folder stays clean.** jdtls keeps Eclipse's `.project`,
  `.classpath` and `.settings/` in its own workspace, not in your project.
- **Build files sync on save.** A change to `pom.xml` or `build.gradle` reaches
  the classpath when you save it, also a module's in a folder you never expanded,
  and so does one made outside Editora (a `git pull`, a branch switch).
- **One server per build.** Files of the modules of one Maven reactor or Gradle
  build share a server even without a project open. A plain folder of `.java`
  files with packages, and no build file, gets the right source folder as soon as
  a file opens. Java files keep their server up to 50,000 lines.
- **The project's own code style.** IntelliJ's import settings in the project's
  `.editorconfig` (`ij_java_imports_layout`,
  `ij_java_class_count_to_use_import_on_demand`,
  `ij_java_names_count_to_use_import_on_demand`) set the import order and when
  imports become `*`. The Eclipse formatter profile your build formats with
  (Spotless's `<eclipse><file>` or `eclipse().configFile(…)`, else
  `eclipse-formatter.xml` at the project root) is what Format Document uses.
  Editing the profile, or removing it, applies without restarting the server,
  also for a profile kept in Eclipse's `.settings` or a `config` folder, and a
  profile removed while Editora was closed no longer formats the old way. A
  project that already has an Eclipse `.settings` folder gets jdtls's compiler
  settings written into it (a committed `org.eclipse.jdt.core.prefs` changes);
  a project without one gets none.
- **Lombok.** For a project whose build declares Lombok, jdtls runs with it as
  its agent, using the newest Lombok jar already in your local Maven or Gradle
  cache, so the members Lombok generates are seen. When none has been downloaded
  yet, Editora offers to download Lombok from Maven Central and restarts the
  server with it.
- **Null analysis.** In a project with JSpecify, `javax.annotation`, Spring or
  Eclipse null annotations, jdtls checks `@Nullable` and `@NonNull` use.
- **A fresh start.** **LSP: Clean Java Workspace and Restart**
  (`lsp.cleanJavaWorkspace`) deletes the project's jdtls index and the
  classpath it worked out, then imports the project again: the cure for a stale
  or corrupt index.

## Folding and selection

Where a server is running, **code folding comes from the server's own
understanding of the file** instead of brace and indent scanning: an import block
folds as one region, javadoc and block comments fold, and multi-line expressions
nest correctly. [Expand and shrink
selection](/docs/editing#expand-and-shrink-selection) uses the same source, so it
respects strings and comments rather than guessing at brackets.

Both fall back to the built-in behaviour whenever LSP is off, the file is on a
[remote host](/docs/remote) that may not run commands, or the server offers neither, so a file is never less foldable with a server
than without one.

## While the server works

Servers that report long-running work through the LSP progress channel (jdtls
importing and indexing a project, gopls loading packages, rust-analyzer's first
check) drive the status-bar loading bar and show what they are doing, with a
title and percentage, in the echo area. Each message starts with the name of the
server it comes from, for example `bash-language-server: …`.

Startup ends with a line that says it is done, such as
`jdtls: Loaded and ready`. It appears once the server is ready and has had no work outstanding for
a moment; for jdtls that is after the project import, not at its own earlier
"Ready". After that, background work (diagnostics after an edit, a search) is
shown only when it is still running after that moment, and work that was shown
ends with another ready line, so the last line never reads as a job left
unfinished. Work that a ready server began but never reported as done counts as
over once the server has been silent for 30 seconds, so the ready line, and the
end of the loading bar, always come. A line that repeats the one just shown is
skipped.

External file changes are forwarded to the running servers, so a server's
project model stays current after a `git checkout`, a branch switch, a CLI build
or an edit in an external editor. In a window without a project, and for a
server whose folder is outside the project, Editora watches the server's folder
itself (hidden folders, `node_modules`, `__pycache__` and virtual environments
aside), so a module created by `git` or a terminal resolves without a restart.
A server that dies mid-session (a crash, an
OOM-kill, a failed handshake) is announced in the status bar, has its stale
diagnostics cleared, and is restarted for the affected files, with a cap so a
server that keeps crashing is not relaunched forever.

A server that *answers* a request with an internal error is not mistaken for
one with nothing to say. The error is logged with the server's own detail, and
the first failure of each request kind is announced in the status bar, such as
`lemminx-maven: internal error on hover — see the log`. Hover then says
*Hover failed: …* with the reason instead of "No documentation at the caret".
Three failures in a row with no good answer between them stop the server: the
status bar says so, Doctor shows the server as a warning with the reason, and
*LSP: Restart Language Servers* (`lsp.restartServers`) tries it again.

## Servers are auto-detected, never bundled

Editora doesn't ship language servers. It looks for each one on your `PATH` (a
Settings field can override the command per server) and uses it if present.
Twenty-six servers are supported:

- **Java** (`jdtls`), **TypeScript / JavaScript** (`typescript-language-server`),
  **Python** (`pyright-langserver`), **Go** (`gopls`), **Rust**
  (`rust-analyzer`), **C / C++** (`clangd`), **C#** (`csharp-ls`)
- **Ruby** (`ruby-lsp`), **PHP** (`phpactor`), **Kotlin**
  (JetBrains' Kotlin LSP, `kotlin-lsp`), **Groovy** (`gls`, from gvy), **Clojure** (`clojure-lsp`),
  **Scala** (Metals), **Lua** (`lua-language-server`)
- **Astro** (`astro-ls`), **HTML** (`vscode-html-language-server`), **CSS**
  (`vscode-css-language-server`), **JSON** (`vscode-json-language-server`),
  **YAML**, **XML** (`lemminx`), **Bash** (`bash-language-server`),
  **Dockerfile**, **SQL** (`sqls`), **Terraform** (`terraform-ls`), **TOML**
  (`taplo`), **Typst** (`tinymist`)

The HTML, CSS and JSON servers come from one npm package,
**vscode-langservers-extracted** (the servers taken out of VS Code), so one
install gives all three; see [HTML, CSS and JSON](#html-css-and-json).

There is also a **Maven-aware `pom.xml` server** (JVM lemminx plus the
lemminx-maven extension), shown as **lemminx-maven** in Settings, Doctor and
the status bar where the XML server is **lemminx**. A `pom.xml` in a trusted
folder gets dependency, plugin and GAV completion from it while every other XML
file keeps the fast native lemminx; see
[pom.xml and the Maven server](#pomxml-and-the-maven-server).

Each server has a card on the **Settings → LSP** page: its enable switch, its
command field with **Browse…**, and a **Detected** line such as *jdtls: found
(/usr/bin/jdtls)* or *rust-analyzer: not found*, with an **Install…** button
while it is missing. A command left blank uses the program on your `PATH`. While
**Enable LSP (language servers)** is off, the cards are greyed and a note says to
turn it on. The switches for inlay hints, code lenses and the other server
features are in the **Language server features** card on Settings → Code
Completion; the LSP page links there.
[Doctor](/docs/troubleshooting#doctor) reports the same detection for every
enabled server in one place.

### Per-project overrides

A project can commit a `.editora/settings.json` naming **which server to run for
a language and whether to run it**, which overrides your global preference for
anyone who opens that project. It can also set the
[Bash server's options](#bash-server-options) and the
[HTML server's](/docs/html#settings--lsp--html). This covers one repository needing a
JDK 17 server and another a JDK 25 one, where nobody has to remember to flip a
setting when switching between them. The same file can also set indentation,
rulers, build commands and debugger programs; see
[projects](/docs/workspace#settings-a-project-can-commit) and the full list in
[Configuration](/docs/configuration#inside-a-project).

Because those commands are programs that run on your machine, they apply only
to a **trusted folder**. In an untrusted folder the project's server commands
(and any attempt to re-enable a server you switched off) are ignored until you
run *Project: Trust This Project's Settings* (`lsp.trustProjectSettings`).

Settings → LSP shows your own commands. When the project in the window
overrides a server, that server's card says so: which command the project runs,
which one it asks to run until you trust it, or that it turns the server on or
off.

A remote project's `.editora/settings.json` is not read; set a server's command
for a host in Settings → Remote instead (see
[Language servers on a remote host](#language-servers-on-a-remote-host)).

### Servers that load a project's build

Some servers run the project's own code just to load it: a Gradle build's
scripts, Maven build extensions, Rust build scripts and proc macros, a
`Gemfile`, MSBuild projects, Leiningen. In a folder you have not
[trusted](/docs/workspace#trusted-folders), Editora holds that back:

| Server | In an untrusted folder |
| --- | --- |
| Java (jdtls) | Starts without the Maven or Gradle build. Syntax checks, the JDK's types and navigation within the file still work. |
| Kotlin | Waits when the folder has a `pom.xml`, `build.gradle(.kts)` or `settings.gradle(.kts)`. |
| Scala (Metals) | Waits when the folder has a `build.sbt`, `build.sc`, `build.mill` or `project.scala`. |
| Rust (rust-analyzer) | Waits when the folder, or a folder one level down, has a `Cargo.toml`. |
| Ruby (Ruby LSP) | Waits when the folder has a `Gemfile` or `gems.rb`. |
| C# (csharp-ls) | Waits when the folder, or a folder up to two levels down, has a solution, a project file or `Directory.Build.props`/`.targets`. |
| Clojure (clojure-lsp) | Waits when the folder has a `project.clj`, `build.boot` or `shadow-cljs.edn`. A `deps.edn` or `bb.edn` is read as data and does not hold it back. |

A file of one of these languages says so, with **Trust Folder** and **Not
now**. Trusting the folder (the button, or **Workspace: Trust This Folder**)
restarts the server with the build; removing the trust in Settings → Advanced →
Trusted folders stops it again. A loose file with no build next to it starts its
server as usual. Other servers, Pyright among them, are not held back.

## One-click install

If a server isn't installed, Editora can fetch it for you. **Every server** is
covered through one of these channels, picked per server:

- **npm** packages (Astro, Bash, YAML, Dockerfile, TOML, Python/Pyright),
  needing Node.
- **Pinned npm packages** for TypeScript / JavaScript, typescript-language-server
  5.3.0 and TypeScript 5.9.3, installed with npm into Editora's own folder
  rather than globally (see [TypeScript and JavaScript](#typescript-and-javascript)).
  They need Node.
- A **pinned npm package** for HTML, CSS and JSON, vscode-langservers-extracted
  4.10.0, installed with npm into Editora's own folder rather than globally
  (see [HTML, CSS and JSON](#html-css-and-json)). It needs Node.
- **Toolchain** installs that use the language's own package manager (Go, Ruby,
  C#, Rust, PHP, and SQL, whose `sqls` installs with `go install`), which run
  only when that toolchain is already on your `PATH`.
- **Binary** releases downloaded per OS/architecture (clangd, terraform-ls,
  Lua, and tinymist for Typst), extracted into your config folder.
- A **pinned native binary** for lemminx, the XML server, from one vscode-xml
  release, with both the zip and the program checked against the release's
  SHA-256 (see [Installing lemminx](#installing-lemminx)).
- A **pinned release** for the Java server, Eclipse JDT LS (the version is
  under [The Java server and your project](#the-java-server-and-your-project)),
  unpacked into your config folder. A server installed earlier keeps its version until
  you install it again.
- A **pinned distribution** for the Kotlin server, checked against its published
  checksum. It is about 370 MB to download and about 1.2 GB once installed,
  because it brings its own Java runtime, so Editora asks before it starts.
- A **JVM zip with its dependencies** for the Maven-aware `pom.xml` server, which
  needs a JDK, plus three jars from Maven Central that the zip leaves out, each
  checked against its published checksum. The server is started and asked one
  request before the install is kept.
- A **single jar** for the Groovy server (`gls`, from gvy), pinned to one release and checked
  against its published checksum. It runs on the JDK chosen for builds and runs
  (Java 17 or later).
- A **pinned native binary** for clojure-lsp, one per OS and architecture, each
  checked against its SHA-256 before it is unpacked. It needs no JVM.
- **Coursier** for Metals, the Scala server, which is published only as Maven
  artifacts: Editora downloads coursier's launcher (pinned, and checked against
  its published checksum), which fetches Metals' jars into your config folder.
  It runs on the JDK chosen for builds and runs (Java 17 or later).

There are three entry points: an **Install…** button per server in **Settings → LSP**, an
in-editor **banner** when you open a file whose server is missing (turn the nudge
off with `view.toggleInstallPrompts`; its **Not now** lasts for every file of
that kind in the window), and the **Install: Language Server…**
picker (`install.languageServer`). After installing, the server is auto-detected
and activates without a restart. Editora never installs the underlying runtimes
(Node, Python, Go, …); if one is missing it tells you which to install first.

The Bash server lints with ShellCheck and formats with shfmt, which it does not
install itself. Editora installs those two separately; see
[ShellCheck and shfmt](#shellcheck-and-shfmt).

Installing over an existing version is safe to retry. A downloaded server is
unpacked beside the installed one and replaces it only once it is complete and
is the expected tool, so a failed or interrupted install leaves the working
version in place.

For Astro, startup also locates the TypeScript SDK required by `astro-ls`,
whether it is hoisted in the workspace or Editora's own TypeScript 5.9.3, the
same copy the [TypeScript / JavaScript server](#typescript-and-javascript)
uses. A TypeScript SDK inside the project is loaded only from a trusted
folder; otherwise Editora uses its own or the one beside the server, or does
not start the server until you trust the folder. The trust prompt lists the SDK when the folder has an
`astro.config.*` or an `.astro` file is open.

## Groovy

The Groovy server is `gls`, from the gvy project. Install it from its Settings → LSP card, the banner
on a `.groovy` file, or **Install: Language Server…**. It has no command until
it is installed. The installer sets its command to `java -jar` with the
downloaded jar, and Editora runs that with the JDK chosen for Maven, Gradle and
Run, not the first `java` on your `PATH`.

It reports Groovy compiler errors, and offers completion, hover, go to
definition, references, rename, the outline and formatting. CodeNarc's style
rules are off: they flag nearly every line of ordinary Groovy.

A `Jenkinsfile` at a project's root makes [Doctor](/docs/troubleshooting#doctor)
expect the Groovy server, as an open `.groovy` file does. A `build.gradle` alone
does not, because most Gradle projects with a Groovy build script are Java
projects.

## Kotlin

The Kotlin server is JetBrains' Kotlin LSP. Install it from its Settings → LSP
card, the banner on a `.kt` file, or **Install: Language Server…**; Editora says
how large the download is and waits for your answer. The installer sets the
server's command to the downloaded launcher. Left blank, the command is
`kotlin-lsp --stdio`, the name Homebrew's `kotlin-lsp` gives it. A command you
set yourself is kept, so fwcd's `kotlin-language-server` still works there.

The server imports the project's Gradle or Maven build, so its root is the
folder with `settings.gradle.kts` (or `settings.gradle`), else the nearest
`build.gradle.kts`, `build.gradle` or `pom.xml`: every module of a multi-module
build shares one server. In a folder you have not trusted, the server waits
until you trust it, since the import runs the build (see
[Servers that load a project's build](#servers-that-load-a-projects-build)).
Once the import ends (a few seconds for a small
project, longer the first time Gradle fetches its plugins) it reports
compiler errors and warnings and offers completion, hover, go to definition,
references, rename, the outline, formatting and code actions.

The server is an alpha. It checks the `.kt` files in the build's source
folders; a `.kts` script, `build.gradle.kts`, and a `.kt` file outside a build
get no diagnostics or hover from it. A folder without a build file is not
imported at all.

## Clojure

The Clojure server is clojure-lsp. Editora uses the `clojure-lsp` on your `PATH`,
or installs its own copy from the server's Settings → LSP card, the banner on a
Clojure file, or **Install: Language Server…**. The install is the native build
for your platform (Linux and macOS on x64 and arm64, Windows on x64), pinned to
one release and checked against its SHA-256.

It serves `.clj`, `.cljs`, `.cljc` and `.edn` files with clj-kondo's diagnostics,
completion, hover, go to definition, references, rename, code actions, the
outline and formatting. To resolve the project's dependencies it asks the
project's own tool for the classpath (`clojure -Spath`, `lein classpath`,
`bb print-deps` or `npx shadow-cljs classpath`), which it finds on the same
`PATH` Editora gives every language server. Without that tool it still works on
the project's own files. In a folder you have not trusted, a `project.clj`,
`build.boot` or `shadow-cljs.edn` makes the server wait until you trust it
(see [Servers that load a project's build](#servers-that-load-a-projects-build)).

A server runs per project root: the folder with a `deps.edn`, `project.clj`,
`bb.edn`, `shadow-cljs.edn` or `build.boot`, else the repository. Any of those
files at a project's root also makes [Doctor](/docs/troubleshooting#doctor)
expect the Clojure server.

## Scala

The Scala server is [Metals](https://scalameta.org/metals/) 1.6.9. Install it
from its Settings → LSP card, the banner on a `.scala` file, or **Install:
Language Server…**. It has no command until it is installed: the installer
sets it to `java` with Metals' JVM options and the fetched jars, and Editora
runs that with the JDK chosen for Maven, Gradle and Run, not the first `java`
on your `PATH`.

Metals works on the build it finds at the workspace root: an sbt build
(`build.sbt`), a Mill build (`build.mill`, or a Mill 0.x `build.sc`), a Scala
CLI project (`project.scala`), a build already exported over BSP (`.bsp/`), or a
Gradle or Maven build. The first time it sees one it asks whether to **import
the build**; the question appears as a dialog, and the editor stays usable while
it is open. An import runs the build tool and compiles the project, which takes
a minute or more the first time. Metals shows its progress in the status bar.
In a folder you have not trusted, Metals waits until you trust it (see
[Servers that load a project's build](#servers-that-load-a-projects-build)).

It reports compiler errors, and offers completion, hover, go to definition,
references, rename, the outline and formatting (with a `.scalafmt.conf`). When
Metals asks for a value or a choice of its own, a text field or a picker opens.

A `build.sbt`, `build.mill`, `build.sc` or `project.scala` at a project's root
makes [Doctor](/docs/troubleshooting#doctor) expect Metals.

Any server's question with buttons gets the same dialog.

## Workspace roots and lifetime

A server runs once per workspace root. The root is the active project, else the
nearest build marker (`pom.xml`, `package.json`, `go.mod`, `Cargo.toml`, and so
on), else the file's directory. Files of the same language in one project share a
single server. Python looks for its own markers first, and a Python sub-project
inside a project gets a server of its own; see
[Python sub-projects](#python-sub-projects).
An HTML page with no project, and no `package.json` or `.git` above it (one in
your home folder or above it does not count), is rooted at its site root, by
the one rule HTML Live Preview uses too: the nearest folder with an
`index.html`, then on up while the folder above holds one too, never into your
home folder, Downloads, Desktop, Documents or a temporary folder. So the whole
static site shares one server, its `/css/…` links resolve, and Ctrl-click and
the browser agree. A page with a `package.json` or `.git` above it is rooted
there instead, while the preview still serves its site root, so the two agree
only when that folder is the site root (see
[What is served](/docs/html#what-is-served)). Go to Definition on a `/…` link
and the markup checks use the server's root for a page outside the project
even when no server is running.

A file directly in your home folder, the temporary-files folder or at the top of
a drive (`~/.bashrc`, `/tmp/x.sh`) has that whole folder as its root. Editora
tells the Bash server not to scan such a folder for other scripts, so opening
one loose file does not walk your whole home folder. The file itself still gets
diagnostics, completion and navigation.

Servers start **per tab, as you visit it**, rather than all at once while a
session restores, and they **shut down when their last file closes**, after a
three-minute grace period so reopening a file keeps the server warm.

**Two windows on one Java project share one Java server.** The second window
joins the jdtls the first one runs, so the project isn't imported twice and the
second window costs no extra memory (a jdtls takes about 1.5 GB). Both windows
show its diagnostics; a window that opens while jdtls is still importing the
project waits for it to finish before it says the project is ready. A question
the server asks, such as which class to import,
goes to the window you are working in. Closing one window leaves the server to
the other, and it stops when the last window closes. **Restart Language
Servers** in either window restarts it for both, and the other window reopens
its files on the new server. It restarts only the current project's jdtls: a
second project open in the same window keeps its server and its problems. Projects on a remote host, in a container or in
WSL, and other language servers, still get one server per window.

jdtls keeps an index for every Java folder you have opened, under
`jdtls-workspaces` in your config folder: about 40 MB each, and often far more.
**LSP: Clear Java Index Caches** (`lsp.clearJavaIndexCaches`) deletes every
index that no running server is using and reports the space it freed; the ones
in use are kept and counted. jdtls rebuilds a project's index the next time the
project opens. It works with LSP turned off too.

A Java file without a `.java` name (an extension-less `java --source 25`
shebang script, or a file set to Java in the status bar) is shown to jdtls as a
temporary `.java` copy, since jdtls works only on files named `.java`. It gets
completion, hover, the outline and syntax errors, and an import a completion
adds goes below the `#!` line. The copy sits outside any project, so type
errors are not reported. A script in a remote folder is not given a copy.

## TypeScript and JavaScript

JavaScript and TypeScript files use **typescript-language-server**, which runs
TypeScript's own `tsserver`. **Install…** on its card (or *Install: TypeScript
/ JavaScript (Node.js) Language Support*, `install.jsSupport`) installs
typescript-language-server **5.3.0** and TypeScript **5.9.3** with npm into
`plugins/lsp/typescript` under Editora's config folder. Nothing is installed
globally, so your own `tsc` stays as it is. With the server's command left
blank, Editora runs that copy. The server runs on the same Node.js as
[Run](/docs/run-debug#nodejs-scripts).

**Why TypeScript 5.9.** TypeScript 6 leaves out the installed `@types`
packages, such as `@types/node`, unless a tsconfig lists them. Node.js code
then shows false errors such as *Cannot find name 'process'*, and plain
JavaScript gets no types for Node's APIs. TypeScript 7 is a new compiler
written in Go and has no `tsserver`, so typescript-language-server cannot run
on it. TypeScript 5.9 has neither problem.

**Which TypeScript runs**, in this order:

1. The folder's own `node_modules/typescript`, in a
   [trusted folder](/docs/workspace#trusted-folders).
2. Editora's own TypeScript 5.9.3, when it is installed, even for a server
   you installed yourself or a command you set.
3. The TypeScript installed beside the server.

The card's **TypeScript** line shows the one in use, such as *Editora's own,
…*. While a JavaScript or TypeScript file is active, the status bar's LSP
segment names it too, for example **LSP: TypeScript 5.9.3**.

When the only TypeScript found is 6 or newer, the card's TypeScript line and
[Doctor](/docs/troubleshooting#typescript-features-are-off-or-show-false-errors)
warn about it: *TypeScript 6.0.3: leaves out @types packages*, or *TypeScript
7.0.2 has no tsserver*. With no TypeScript that has a `tsserver` (only a
TypeScript 7, or none at all), Editora does not start the server. The status
bar says so, the fix first: *Install TypeScript 5.9 (Settings → LSP): TypeScript
7.0.2 has no tsserver*. The file's install banner offers **Install**, which adds
Editora's own TypeScript 5.9.3 and typescript-language-server 5.3.0 to its own
folder with npm; your own TypeScript is not changed. The banner's **Not now**
and the install-prompts switch work as they do for a missing server. Editora
does not try again each time you open another tab.

**A folder's own TypeScript runs only once you trust the folder.** Many projects
have their own `node_modules/typescript`. That is code from the folder, so
Editora uses it only in a trusted folder, and the trust prompt asks about it
(see [the prompt](#a-folders-own-typescript-and-the-trust-prompt)). Until then,
Editora's own TypeScript is used, else the one beside the server. When the
folder's own TypeScript has another major version than the one running, the
status bar says so once, when the server is ready: *This folder has TypeScript
5.4.5, but Editora runs 5.9.3 here until the folder is trusted*, since results
can differ from the folder's own `tsc`. When the folder's own copy is the only
one, the server waits until you run *Project: Trust This Project's Settings*
(`lsp.trustProjectSettings`). Without a project, the status bar says that only
a project can be trusted, and points to **Install…**. On a remote host, the
server still picks its TypeScript itself.

**Type definitions are not downloaded.** Left to itself, the server downloads
`@types` packages from the npm registry for the libraries your JavaScript uses.
Editora turns that off. To turn it on, use **Download type definitions
automatically** on the TypeScript / JavaScript card in **Settings → LSP**. When
a folder has no Node.js types, the status bar suggests
`npm install --save-dev @types/node`, so hover and completion know Node's APIs.
The hint replaces the server's ready line and stays in the status bar.

**What it adds:**

- **Organize Imports** (`lsp.organizeImports`) sorts the imports and removes
  unused ones. On a line with a problem, the code-action menu also offers
  **Fix all**, **Add all missing imports** and **Remove unused**, and quick
  fixes follow the file's own indentation.
- **Imports follow moved files.** Moving or renaming a `.ts` or `.js` file in
  the Project tree updates the imports that name it, in open and closed files,
  and saves them. A renamed folder does the same, unless a Java server serves
  it.
- **Completion details**: the documentation pane beside a completion shows
  the signature, such as the method's parameters or where an auto-import
  comes from.
- **Inlay hints** for parameter names, return types and enum values, when
  [inlay hints](#reading-the-code) are on.
- **Code lenses**: a references count on each function, when code lenses are
  on.

A `tsserver` that dies restarts the server, within the
[crash cap](#while-the-server-works), and the status bar says it stopped
unexpectedly. JavaScript and TypeScript files keep their server up to
**50,000 lines**, even when the **Turn the language server off above** limit is set lower.

**Workspace roots.** Without a project open, a file's server root is the
nearest folder with a `tsconfig.json` or `jsconfig.json`, then one with a
`package.json`, then the Git repository. A monorepo still gets **one server**:
the root widens to the outermost folder above that is a workspace's root, one
with a `package.json` that lists `"workspaces"`, a `pnpm-workspace.yaml`, or a
`tsconfig.json` with `"references"`. It never widens to your home folder or
above, and never outside an open project. Scripts with none of these above
them share one server, wherever they are, instead of starting one each.

### A folder's own TypeScript and the trust prompt

When a folder brings nothing to trust but its own TypeScript, the trust prompt
asks about that alone: **Run the folder *name*'s own TypeScript?** It says that,
once trusted, the JavaScript and TypeScript features run that copy instead of
Editora's, and that its code runs with your privileges. Each copy is listed on
one line, for example *TypeScript 5.9.3 in node_modules/typescript, run by the
TypeScript / JavaScript server*. When the folder also has an
`.editora/settings.json` with settings to trust, the prompt is the usual
**Use the settings of the folder *name*?**, with the TypeScript lines in its
list.

The Astro server loads a TypeScript too. Its line (*… loaded by the Astro
server*) appears only when the folder has an `astro.config.*` or an `.astro`
file is open. Trust is remembered for the folder and the folders inside it;
revoke it in Settings → Advanced, under Trusted folders.

### ESLint

A project's own **ESLint** checks JavaScript and TypeScript files (`.js`,
`.jsx`, `.mjs`, `.cjs`, `.ts`, `.tsx`, `.mts` and `.cts`) when the project has an ESLint
configuration (`eslint.config.*`, an `.eslintrc*` file, or an `eslintConfig`
key in `package.json`) and ESLint installed in `node_modules`. It checks a
file when its tab is shown, 0.8 s after you stop typing, and on save. Its
problems join the TypeScript server's in the squiggles, the problem counter and
the Problems window, with **eslint** as their source. ESLint runs from its
configuration's folder, on the project's Node.js, and only from the project's
`node_modules`: a global or `PATH` copy is never used.

- **Code Actions** lists each problem's fixes and suggestions, such as *Fix
  this no-var problem*, and *Fix all auto-fixable ESLint problems*. They show
  while the TypeScript server runs; the lightbulb does not light for a line
  with only ESLint problems.
- **ESLint: Fix All Problems** (`eslint.fixAll`) applies every automatic fix
  to the active file. The status bar says how many problems are left. A fix
  from a check older than your last edit is refused with *The file changed
  since ESLint checked it*; try again a moment later.

ESLint loads the project's configuration and plugins, which are code, so it
runs only in a [trusted folder](/docs/workspace#trusted-folders). In an
untrusted one, the first JavaScript or TypeScript file of the project says so
once per window, with **Trust Folder** and **Not now**. A project with a
configuration but no ESLint installed stays quiet; Doctor's **ESLint** row
says *configuration found, but ESLint is not installed*.

ESLint's problems are shown only while **Enable LSP (language servers)** is on.
**ESLint: Fix All Problems**, fixes on save and Prettier or Biome work with it
off. To stop ESLint, turn off **Check with ESLint** (see
[the settings](#eslint-and-formatting-settings)).

### Formatting JavaScript and TypeScript

**Format Document** (`lsp.formatDocument`) on a JavaScript or TypeScript file
uses the **Formatter** setting:

- **Automatic** (the default): the project's **Prettier** when the project uses
  it (a Prettier configuration file, a `prettier` key in `package.json`, or
  Prettier among its dependencies) and has it installed; else its **Biome**
  when it has a `biome.json` or `biome.jsonc` and Biome installed; else the
  TypeScript server.
- **Prettier** or **Biome**: that tool, when it is installed in the project,
  whatever the configuration says.
- **TypeScript server**: always the server.

Prettier and Biome run from the project's `node_modules` on the project's
Node.js, and only in a trusted folder. With **Automatic**, an untrusted folder
or a missing tool leaves the file to the server. With **Prettier** or
**Biome**, a tool that cannot run shows why in the status bar and leaves the
text as it is. The result is one undoable edit, and the status bar names the
tool, for example *Formatted with Prettier*. When the tool fails, the status
bar shows its first error line and the text stays as it was.

With a selection, Format Document gives Prettier the selection's range. Biome,
or no tool, leaves the range to the server.

### Actions on save

Three switches tidy a JavaScript or TypeScript file after you save it, in this
order: **Organize imports on save**, **Apply ESLint fixes on save** and
**Format on save**. They run after an explicit save, never after autosave.
When they change the file, Editora saves it again, and one undo takes all
their changes back. A step is skipped when you type in the file while it runs.
Organize imports needs the TypeScript server. All three are off by default.

### ESLint and formatting settings

The **ESLint and formatting** group on the TypeScript / JavaScript card in
**Settings → LSP** holds:

| Setting | Values | Default |
|---|---|---|
| **Check with ESLint** | On · Off | On |
| **Formatter** | Automatic · Prettier · Biome · TypeScript server | Automatic |
| **Organize imports on save** | On · Off | Off |
| **Apply ESLint fixes on save** | On · Off | Off |
| **Format on save** | On · Off | Off |

They apply to every window at once.

## HTML, CSS and JSON

HTML, CSS and JSON files use **vscode-html-language-server**,
**vscode-css-language-server** and **vscode-json-language-server**, the servers
taken out of VS Code and published together in the npm package
**vscode-langservers-extracted**. They are Node.js programs, so they need
**Node.js**. The first HTML or CSS file a window opens asks **Turn on HTML code
intelligence?** (or CSS), with **Turn on** and **Not now**, like the
[Python offer](#python).

**Install** on the banner, **Install…** on any of the three cards in Settings →
LSP, Doctor's **Install…** or **Install: Language Server…** installs
vscode-langservers-extracted **4.10.0**, the version Editora is tested with,
with npm into `plugins/lsp/web` under Editora's config folder rather than
globally. It also links the TypeScript libraries the HTML server needs for the
DOM and standard library in `<script>`. With the three commands left blank,
Editora runs that copy first, then one on your `PATH`.
[Doctor](/docs/troubleshooting#doctor) checks the installed version against
4.10.0, and warns, with **Update**, about a copy that cannot load the
TypeScript libraries; the first HTML page a window opens offers the same
**Update** on a banner.

The three servers run on the Node.js chosen on the **Node.js** card under
**Settings → Build Tools**, else `node` on your `PATH`. A project's `.nvmrc` or
`.node-version` does not change them; it picks only the TypeScript server's
Node.js. When a Node.js is chosen there, Install uses the npm beside it.

**JSON with comments.** These files allow comments and trailing commas:
`tsconfig.json` and `jsconfig.json` (and their `tsconfig.*.json` variants),
`.eslintrc`, `.eslintrc.json`, `.babelrc`, `.babelrc.json`, `.swcrc`,
`deno.json`, `bun.lock`, any `.json` file in a `.vscode` folder, and `*.jsonc`
and `*.code-workspace` files. Other `.json` files are strict JSON.

**package.json and tsconfig.json** are checked and completed against schemas
bundled with Editora, so they work offline. The JSON server flags a misspelt
compiler option such as `"strictt"`, a wrong value such as `"type": "modul"`,
or `"scripts"` that is not an object, and completes and explains the keys and
values inside `compilerOptions`. The tsconfig schema covers `tsconfig.json`,
`tsconfig.*.json`, `tsconfig-*.json`, `jsconfig.json` and `jsconfig.*.json`.
Unknown top-level keys in `package.json` are allowed, since tools add their own.

What the HTML server adds, its settings on the HTML card (wrap column,
attribute wrapping, content kept as written, checking embedded scripts and
styles), the `html` object a project can commit, and what
it does not do (markup validation, Emmet) are on the
[HTML](/docs/html#the-html-language-server) page. The quotes an attribute gets
after its `=` are chosen in Settings → Editor, not on the card (see
[Attribute quotes](/docs/html#editing-tags)). The HTML and CSS servers also
draw a small swatch before each colour.

## Shell scripts

Shell scripts use **bash-language-server** (`bash-language-server start`).
It serves `.sh`, `.bash`, `.zsh`, `.ksh`, `.mksh`, `.command` and `.bats`
files, shell dotfiles such as `.bashrc` and `.profile`, files whose shebang
names a shell, and any file with a `# shellcheck shell=bash` (or `sh`, `dash`,
`ksh`) line near the top, such as a sourced `.inc` file. A `PKGBUILD` is a
shell script too; its `pkgname`, `pkgver` and other makepkg variables are not
reported as unused (SC2034) or unset (SC2154).

If language servers are off (Settings → LSP), the first shell script a window
opens asks **Turn on shell script code intelligence?** with **Turn on** and
**Not now**, once per window, like the [Python offer](#python).

zsh startup files (`.zshrc`, `.zshenv`, `.zprofile`, `.zlogin`, `.zlogout`)
are not opened on the Bash server. ShellCheck has no zsh mode and would report
errors in valid zsh, so these files get highlighting and folding but no
language server. A zsh script (a `.zsh` file, a zsh shebang, or a
`# shellcheck shell=zsh` line) stays on the server for completion, hover and
symbols, but nothing lints it: ShellCheck's "only supports sh/bash" error is
hidden, and the problem counter says **Not checked: ShellCheck has no zsh
mode**.

Scripts outside any Git repository (in `~/bin`, `/etc` or a loose folder)
share one Bash server per window, which reads no other scripts in the
background.

### Installing the Bash server

**Install** on the banner, in Settings → LSP or in Doctor installs
bash-language-server **5.8.1** with npm, the version Editora is tested with,
together with ShellCheck and shfmt when they are missing. It needs **Node.js
20 or newer**; with an older Node.js the install stops and says which version
it found. Doctor's **Bash / Shell** row shows the installed version and warns,
with **Update**, when it is older than 5.7. A Bash command you set in Settings
that is not found gets a banner that opens Settings rather than an install.

bash-language-server is a Node.js program. When Editora starts where `node` is
not on the `PATH` (from a desktop launcher, without nvm's setup), the server
cannot start: Doctor says the server needs Node.js, and its **Restart Language
Servers** button tries again once that is fixed.

### ShellCheck and shfmt

The Bash server finds problems with **ShellCheck** and formats with **shfmt**.
Without ShellCheck a script gets no diagnostics; without shfmt **Format
Document** changes nothing. Editora shows whether each one is there:

- The **Bash / Shell** card in **Settings → LSP** has a command field and a
  Detected line for each, for example *ShellCheck: found (/usr/bin/shellcheck)*
  or *shfmt: not found*.
- [Doctor](/docs/troubleshooting#doctor) has a **ShellCheck** and an **shfmt**
  row under the Bash / Shell server.
- Opening a script when the server is installed but ShellCheck or shfmt is not
  shows a banner that says what is lost (*ShellCheck and shfmt aren't
  installed — shell scripts get no problems or formatting*) with **Install**
  and **Not now**. Not now holds for the window. Format Document without shfmt
  says that shfmt was not found.

To install them, use the **Install…** button beside either line in Settings,
the Install… button in Doctor, or **Install: ShellCheck and shfmt**
(`install.shellTools`) in the palette, which installs whichever is missing.
Editora downloads the official release for your system (ShellCheck 0.11.0 and
shfmt 3.14.1, for Linux x64 and arm64, macOS Intel and Apple silicon, and
Windows x64). It checks each download against a SHA-256 hash built into
Editora and refuses one that does not match, so nothing is installed from a
changed or damaged file. The programs go into `plugins/tools` in your config
folder.

The **ShellCheck command** and **shfmt command** fields on the Bash card choose
which copy the server runs. Left empty, they use the copy Editora installed,
else the program of that name on your `PATH`. A path you enter always wins.
On a [remote host](#language-servers-on-a-remote-host), in a container and in
WSL, ShellCheck and shfmt are the ones that side's `PATH` finds.

### Bash server options

The Bash / Shell card also holds the server's own settings:

| Setting | What it does |
| --- | --- |
| Lint with ShellCheck | On by default. Off: scripts get no ShellCheck problems and Editora stops saying ShellCheck is missing; completion, hover and navigation keep working |
| Format with shfmt | On by default. Off: Format Document leaves scripts as they are and Editora stops saying shfmt is missing |
| ShellCheck arguments | Added to every ShellCheck run, for example `--severity=warning` to hide notes and style hints or `--exclude=SC2034`. Quote an argument that contains spaces. Sourced files are already followed, so `-x` is not needed |
| Explain source lines the server cannot follow | On by default. A `source` line whose path is computed, such as `source "$(dirname "$0")/lib.sh"` or `source "${BASH_SOURCE%/*}/lib.sh"`, gets a hint. Add `# shellcheck source=lib.sh` above it, and Go to Definition, hover and completion reach that file |
| shfmt dialect | The shell syntax Format Document reads: Automatic (from the shebang), Bash, POSIX sh, mksh or Bats |
| Indent case branches, Put && and \|\| at the start of a line, Put a function's opening brace on its own line, Space after redirections, Simplify code when formatting, Keep column alignment | shfmt's style switches, all off by default |
| Ignore .editorconfig shfmt options | If an `.editorconfig` sets **any** shfmt property (such as `switch_case_indent`), all the options above, the dialect included, are ignored for that file unless this is on |
| Scripts read in the background | How many workspace scripts the server reads for Go to Symbol and cross-file completion (500 by default; 0 turns it off) |
| Symbols from every workspace script | Complete, find and rename functions and variables from all scripts, not only from sourced ones |
| explainshell server | The address of an [explainshell](https://github.com/idank/explainshell) server, for hover help on a command's options. Hover sends it the **whole command line** under the caret, including any password or token in it. Use `https://`; plain `http://` is accepted only for this machine (`localhost`). A hover that waits more than 5 seconds gives up with *explainshell did not answer*. Empty (the default) turns it off |

A project can set the same options for everyone who opens it, with a `bashIde`
object in its `.editora/settings.json` (see
[Per-project overrides](#per-project-overrides)). It uses the server's own key
names, the same ones VS Code's Bash IDE extension uses:

```json
{
  "bashIde": {
    "shellcheckArguments": ["--severity=warning"],
    "backgroundAnalysisMaxFiles": 2000,
    "globPattern": "**/*@(.sh|.bash|.inc)",
    "shfmt": { "caseIndent": true, "languageDialect": "bash" }
  }
}
```

The keys are `shellcheckPath`, `shellcheckArguments` (a string or a list),
`explainshellEndpoint`, `backgroundAnalysisMaxFiles`,
`includeAllWorkspaceSymbols`, `enableSourceErrorDiagnostics`, `globPattern`,
`logLevel`, `shellcheckExternalSources`, `backgroundAnalysisIgnore`, and a
`shfmt` object with `path`, `languageDialect`, `simplifyCode`,
`binaryNextLine`, `caseIndent`, `funcNextLine`, `spaceRedirects`,
`keepPadding`, `ignoreEditorconfig` and `additionalArguments`.
`shellcheckExternalSources` and `backgroundAnalysisIgnore` need
bash-language-server 5.7 or newer. A key the project leaves out keeps your own
setting, and a value of the wrong type is ignored. `"shellcheckPath": ""` or
`"shfmt": {"path": ""}` turns that tool off for the project.

A project's `bashIde` applies only to the scripts in that project. A script
from another folder opened in the same window keeps your own settings. The
ShellCheck path, the shfmt path, the explainshell server and any key Editora
does not know name a program to run or a place to send your code, so they
apply only in a trusted folder, like a project's server commands; the other
keys always apply. When a project overrides options, the Bash / Shell card
says which.

### Formatting, renaming and finding references in scripts

- **Format Document** says why shfmt could not format a script, with the line:
  *Could not format: line 5: `for` statement must end with `done`*. A
  `#!/bin/sh` script that uses arrays or `[[ ]]` is read as POSIX sh, and the
  message suggests changing the shebang to bash or setting the shfmt dialect.
  The server cannot format a selection, so Format Selection formats the whole
  file and says so.
- **Rename** works on your functions and variables, across the files that
  source each other. On a command such as `echo`, `cd` or `ls` it is refused:
  *echo is a command, not a function or variable defined in your scripts*. The
  new name must be a valid shell name; a function name may also contain `-`,
  `.` and `:`.
- **Find References** on a call lists every call, the one you are on included,
  and leaves out the definition.
- **Quick fixes** say what they do: *Fix SC2006: Use $(...) notation instead of
  legacy backticks*. *Disable SC2086 for this command* adds a
  `# shellcheck disable=SC2086` line above the command (bash-language-server 5.8
  also offers it for the whole file); these suppressions are listed under
  **Suppress**, after the fixes.
- **Completion** lists keywords and snippets first, then your script's
  functions and variables, then builtins and commands, each labelled. After `$`
  or `${` it offers only variables.
- A library you `source` that changes on disk (a `git pull`, another editor) is
  read again, so Go to Definition and completion follow it without a restart.

Positions on a line with emoji or other characters outside the Basic
Multilingual Plane are correct: Editora converts ShellCheck's columns.

bash-language-server cannot follow `source` into a folder whose path contains
a space or another character that needs escaping in a URL (`My Scripts`,
`Proyectos 2026`). Cross-file Go to Definition, hover and completion do not
work for scripts there; diagnostics and everything within one file do.

### Where ShellCheck finds .shellcheckrc

ShellCheck looks for a `.shellcheckrc` file in the server's workspace root (the
project or repository folder) and the folders above it. Scripts outside any
repository share one server whose root is in Editora's config folder, so a
`.shellcheckrc` beside such a script is not read; use `# shellcheck` directives
in the script, or a `~/.shellcheckrc`. A `.shellcheckrc` in a subfolder of the project is not
read, because the server hands ShellCheck the script's text rather than its
path. Put shared settings in the root
`.shellcheckrc`, and use `# shellcheck disable=SC2086` (or other
`# shellcheck` directives) in the script itself for anything that applies to
one folder or file.

## Python

Python files use **Pyright** (`pyright-langserver --stdio`). If language
servers are off (Settings → LSP), the first Python file a window opens asks
**Turn on Python code intelligence?** with **Turn on** and **Not now**. The offer comes
once per window and follows the same switch as the install banners
(`view.toggleInstallPrompts`). It is not shown while **Enable Python (Pyright)**
is off in Settings → LSP.

### The Python interpreter

Pyright analyses your code with a Python interpreter, and it can only resolve
the packages installed in that interpreter. Editora picks one interpreter per
file and uses it for Run, Debug, the tests, Pyright and Format Document alike.
The first match wins:

1. the **Interpreter** field of a [run configuration](/docs/run-debug), for that
   configuration;
2. the interpreter picked for this project (see below);
3. the environment Editora was started from (`$VIRTUAL_ENV`, then
   `$CONDA_PREFIX`), so activating a virtual environment before starting
   Editora works;
4. a virtual environment in the project: a `.venv`, `venv`, `env` or `.env`
   folder with a `pyvenv.cfg`, looked for from the file's folder up to the
   project root. Pyright and the status-bar item look from the folder Pyright
   is rooted at, so a [Python sub-project](#python-sub-projects) finds its own;
5. **Settings → LSP → Python → Python interpreter (fallback for Run, Debug and
   code intelligence)**, which may be a path or a bare name such as python3.12
   that is looked up on `PATH`;
6. the system Python on `PATH` (`python3`, then `python`; on Windows `py -3`,
   then `python`, never the Microsoft Store stub).

A status-bar item on Python files shows the result and why, for example
`Python 3.13.5 (.venv)`, `Python 3.12.4 (PATH)` or `Python 3.13.5 (picked)`.
It is the interpreter Pyright analyses with. Hover it for the full command.
Click it, press Enter on it after reaching it with Tab, or run **Python: Select
Interpreter** (`python.selectInterpreter`), to choose another one: **Detect
automatically** (the order above), the one in use, the project's environments,
every Python on `PATH`, or **Browse…** for any file. An environment's row names
its folder, for example `Python 3.13.5 — .venv` or
`Python 3.13.5 — services/api/venv`, with a long path shortened in the middle, and typing
filters the rows by path as well as by name. An interpreter found twice on
`PATH` is listed once. **Browse…** checks that the file answers `--version` as
Python does and changes nothing when it does not. The pick keeps the whole
command, arguments included. It is kept per project; inside the project folder
it is stored as a relative path. In a window without a project it applies to
every loose Python file in that window.

The **Python** card on Settings → LSP holds the fallback interpreter, a
**Python: Select Interpreter** row whose **Choose…** button opens the same
picker for this window's project, and **Format on save (Ruff/Black)**. The card
can be edited with language servers and debugging off.

Pyright is told the interpreter, so the packages in a project's `.venv` resolve
with no `pyrightconfig.json`. A changed pick reaches the running server at once.
So does a virtual environment created while the project is open, as soon as its
`pyvenv.cfg` is written, and a package installed into it with `pip install`:
its imports resolve within seconds, with no restart.
A `pyrightconfig.json` or a `[tool.pyright]` section with `venvPath` and `venv`
still wins inside Pyright, and settings such as `pythonVersion` or
`typeCheckingMode` belong there too.
[Doctor](/docs/troubleshooting#doctor) shows the same interpreter in its
**Python interpreter** row, whose **Settings…** opens the Python card.

### Python sub-projects

The Python server's root is the nearest folder with a `pyproject.toml`,
`setup.py`, `setup.cfg` or `pyrightconfig.json`, else the repository (`.git`),
else the nearest `requirements.txt` or `Pipfile`. A `docs/requirements.txt`
therefore does not split the documentation off from the package it documents.

In a project window the project root is the root, except for a Python
sub-project: a folder inside the project with its own `pyproject.toml`,
`setup.py`, `setup.cfg` or `pyrightconfig.json`, such as a `backend/` beside a
`frontend/`. It gets a Pyright of its own, with its own `.venv`, its own `src/`
and its own `[tool.pyright]` settings, and the status-bar item on its files
names that `.venv`. A member of a uv workspace (under a `pyproject.toml` with
`[tool.uv.workspace]`) shares the workspace's server and its one `.venv`.
Without a project, a uv workspace member still finds the workspace's `.venv`.
Remote projects keep one server at the project root.

### What Pyright adds

- **Unused names are faded** (an import or a variable nothing reads) and
  **calls to a deprecated function are struck through**. These marks are drawn
  in the editor but not listed in the Problems window or counted by the problem
  counter, where they would bury the real errors.
- **Organize Imports** (`lsp.organizeImports`) sorts and groups the imports
  with Pyright's own command. It leaves unused ones in place; they are the
  faded ones.
- **Find References** from a call lists that call with the others, and on a
  function's only call it stays there rather than jumping to the definition.
- **Go to Definition** into the standard library, site-packages (the project's
  own `.venv` included) or Pyright's bundled stubs opens that file in View
  mode, on the project's server, and without the library's own type errors.
  The status bar says **Read-Only**, and **Enable Editing** in the View mode
  bar makes it editable.
- An **untitled** buffer set to Python is analysed too. In a project window it
  sees the project's modules. Once you save it, its file takes over.
- A Python file keeps its server up to **50,000 lines**, even when the
  **Turn the language server off above** limit (50,000 lines by default) is set
  lower. Pyright can take several seconds to answer on a
  file that large; completion says **Waiting for Pyright…** meanwhile.

Pyright gives no inlay hints, semantic highlighting or code lenses. Turning one
of them on in a Python file says so in the status bar. basedpyright, set as the
Python server below, gives inlay hints and semantic highlighting.

To use another server, set the Python command in **Settings → LSP**, for example
`basedpyright-langserver --stdio`. If that command is not found, the banner
names it and offers **Settings…**, rather than installing Pyright.

### Python quick fixes

Pyright answers a request for code actions with nothing, so Editora adds its
own to **Code Actions** and the lightbulb on a Python problem:

- **Import Path from pathlib** (or **Import os**) on a name that is not
  defined. Pyright's own import suggestions come first; when it has none, a
  built-in list of common standard-library names and modules supplies the
  import. The new line goes after the file's imports, or into its existing
  one-line `from pathlib import …`. A module of your project that nothing in
  the file reaches yet is not offered.
- **Remove unused import sys** on a faded import, one name at a time.
- **Ignore reportAssignmentType on this line**, listed under **Suppress**, on
  an error, warning or information that names its Pyright rule. It adds
  `# pyright: ignore[reportAssignmentType]` at the end of the line, or adds the
  rule to the line's existing ignore comment. It is not offered on a line that
  ends inside a string or with a backslash.

### Formatting Python

Pyright does not format. **LSP: Format Document** (`lsp.formatDocument`) on a
Python file runs **Ruff** (`ruff format`) or, failing that, **Black**, giving
them the editor's text with the file's name so they read the project's
`pyproject.toml`. Each is looked for first in the interpreter's environment
(the project's `.venv`), then on `PATH`, in this order: the environment's Ruff,
the environment's Black, Ruff on `PATH`, Black on `PATH`. The result is one
undoable edit, and the status bar names the tool, for example
`Formatted with ruff`. It works with language servers off too.

If neither is installed, the status bar says so; install one into the project's
environment (`pip install ruff`). A Python server that formats by itself, such
as `ruff server`, keeps the job. Doctor's **Python formatter** row shows which
tool Format Document will use. A missing formatter is optional there, and a
warning only while Format on save is on.

The text is left as it was, and the status bar says why, when:

- the formatter prints nothing for a file that is not blank, which usually means
  the file matches its `force-exclude` setting;
- the result is larger than 10 MB;
- the buffer is narrowed (*Widen the buffer to format it*) or read-only;
- the file is on another machine (*Ruff and Black cannot format a file on
  another machine yet*). A Python server that formats by itself still formats
  remote files.

A formatter that writes CRLF line endings adds no blank lines, and the file is
saved with its own line endings. A format that finishes after you switch tabs
still lands in its own file.

**Format on save (Ruff/Black)** on the Python card of Settings → LSP formats a
Python file with Ruff or Black after you save it, then saves it again. It is
off by default, runs after an explicit save only (never after auto-save), and
uses Ruff or Black even when a Python server that formats is running. With no
formatter found, a save says so in the status bar.

## XML

XML files use **lemminx**, Red Hat's XML language server. Editora installs a
fixed release of it (see [Installing lemminx](#installing-lemminx)), and a
`pom.xml` in a trusted folder gets the
[Maven-aware server](#pomxml-and-the-maven-server) instead. If language
servers are off (Settings → LSP), the first XML file a window opens asks
whether to turn them on, once per window.

Besides `.xml`, `.xsd`, `.xsl` and the other usual extensions, these XML
dialects open as XML, with highlighting, folding, comments and the language
server: DTDs and their entity and module files (`.dtd`, `.ent`, `.mod`),
RELAX NG (`.rng`), XSL-FO (`.fo`), XSpec, Schematron (`.sch`), MSBuild and
NuGet files (`.csproj`, `.vbproj`, `.fsproj`, `.props`, `.targets`, `.nuspec`),
`.resx`, XAML, XLIFF (`.xlf`, `.xliff`), property lists (`.plist`), XUL, KML,
GPX, JSP tag libraries (`.tld`), Java Web Start (`.jnlp`), XMI and DITA
(`.dita`, `.ditamap`). A file whose name gives no language and that starts with
`<?xml` (an `AndroidManifest`, say) is XML too; see
[When the file name says nothing](/docs/languages#when-the-file-name-says-nothing).

Five of those extensions belong to other formats too, so a `.mod`, `.sch`,
`.props`, `.targets` or `.fo` file opens as XML only when its text starts with
`<` (after any blank lines or byte-order mark). A Modula-2 module or a KiCad
schematic opens as plain text and never reaches the XML server. Typing a `<` at
the top of such a file makes it XML again; a language you picked yourself or a
shebang is kept.

The project's XML files share one server, and every XML file outside the
project shares **one more per window**, whatever folder or Maven module it is
in, rather than one server per folder; the status line calls that one's
folder *files outside the project*. An XML file keeps the server up
to **100,000 lines**, even above the **Turn the language server off above**
limit (50,000 lines by default); from 200,000 lines (or 16 MB) every file opens
in large-file mode, without its language server (see
[Performance](/docs/troubleshooting#performance)).

### What the XML server adds

- **Validation** against the file's XSD, DTD or RELAX NG grammar. A file that
  names no grammar gets no "No grammar constraints" problem; see
  [Files without a grammar](#xml-settings).
- **Completion** of the schema's elements, attributes and enumeration values.
  They come first after `<` and inside an element; *Insert Comment* and *Insert
  CDATA* are listed last.
- **Go to Definition** and Ctrl/Cmd-click on a `schemaLocation`, a DOCTYPE
  system id or an `xml-model` href opens that schema or DTD, and on the `href`
  of an `xsl:include` or `xsl:import` (or an XInclude `xi:include`) it opens
  that file, resolved against the document's folder. A percent-encoded href
  (`sub%20dir/lib.xsl`), a `file:` URI and an XInclude in the default namespace
  work too, as do `file://localhost/…` and an href with a `?query`. On a
  `schemaLocation` inside an XSD (`xs:include`, `xs:import`) it opens the
  included schema. When the file a link names is missing (an include, a schema
  location, a DOCTYPE), the status line says *lib.xsl does not exist*. On an
  element or attribute bound to an XSD, it goes to the declaration in the XSD.
  A `pom.xml` on the Maven server gets the same: F12 on its schema location or
  on an element the Maven extension does not handle goes to the POM schema.
- **Show Documentation** and **Show Problem at Caret** show the whole problem
  at the caret above any documentation, including lemminx's list of the
  elements or values allowed there.
- **Format Document** formats through the server, with VS Code's XML
  defaults under the [settings below](#xml-settings): up to two
  blank lines in a row are kept, a start tag written over several lines (a
  pom's `<project>` header, say) keeps its line breaks with its continuation
  lines indented by two, and comments and CDATA are left as written. Runs of
  spaces and line breaks inside an element's text are joined, unless the
  element is under **Keep text as written in** or has
  `xml:space="preserve"`.
- **Tab** re-indents the current line the way the server would format it,
  also with the caret inside an element's start tag. When the server gives no
  answer, Tab indents as it does without a server.
- **Quick fixes.** On a file without a grammar, lemminx's *Generate … and
  bind* fixes (XSD, DTD or RelaxNG) write the grammar file next to the document
  and bind the document to it with `xsi:noNamespaceSchemaLocation`, a DOCTYPE
  or `xml-model`. These fixes are offered only while
  [Files without a grammar](#xml-settings) reports something; **XML: Generate
  Grammar…** offers them whatever it is set to. *Bind to existing
  grammar/schema* does what **XML: Bind to Grammar…** does. A fix that cannot be
  applied closes any tab it opened. Generated grammars and fixes are indented
  like the file, as it is indented now rather than when it was opened.
  *Minify XML* is not offered as the fix for a problem; it is under Generate,
  and in Code Actions on a line without a problem.
- **Rename** says it cannot rename where the server declines, rather than
  opening a dialog that does nothing.
- **Code lenses** on XSD and DTD declarations count their references, when
  Code Lens is on. They are asked for again when the server says they changed,
  for example after you change the XML catalogs.

Four palette commands go with it:

| Command | What it does |
| --- | --- |
| **XML: Revalidate File** | Validates the file again, after its schema or DTD changed on disk. Works on a `pom.xml` on the Maven server too. |
| **XML: Revalidate All Open Files** | Forgets the cached grammars and validates every open XML file again. |
| **XML: Bind to Grammar…** | Picks an XSD, DTD or RELAX NG file, then binds the document to it with a schema location (`xsi:noNamespaceSchemaLocation` or `xsi:schemaLocation`), a DOCTYPE, or an `<?xml-model?>` instruction. Local files only. A Schematron `xml-model` holds rules, not a grammar, so it does not count as a binding. |
| **XML: Generate Grammar…** | Asks the server for a grammar made from the file (an XSD, a DTD or RELAX NG, bound by a schema location, a DOCTYPE or `xml-model`), lets you pick one, then writes it next to the file and binds the file to it. Local files with a root element only. An XSD can be generated in any folder, trusted or not: Editora ships the W3C schema lemminx needs for it. A file that already has a DOCTYPE is not offered a second one. |

Both binding commands leave a file that is already bound alone: the status
line says what it is bound to and by what (an external DOCTYPE, an
`xml-model`, or a schema location on the root), so remove that binding first.
A DOCTYPE that holds only an internal subset is not a binding; a DTD is then
bound with `xml-model` rather than a second DOCTYPE. For a document whose root
element is in a namespace, the two XSD choices are left out: the schema lemminx
generates has no namespace and would fail the document at once.

A schema, DTD or RELAX NG file changed outside Editora (edited elsewhere,
regenerated by a build, or changed by a pull or checkout) revalidates the open
XML files that use it, also when its folder is collapsed in the Project tree,
when the schema itself is open in a background tab, and after you reload it.

### XML settings

The **XML** card in **Settings → LSP** holds the server's settings, under
three headings: **Validation**, **Formatting (XML server)** and **Structure**.
A settings search that matches a row shows its heading too. Editora sends them
when the server starts (the Maven `pom.xml` server gets them as well), and a
change reaches the running server without a restart.

| Setting | Choices | Default |
| --- | --- | --- |
| **Download schemas** | Never · Only in trusted folders · Always | Only in trusted folders |
| **Files without a grammar** | Nothing · Hint · Warning | Nothing |
| **XML catalogs** | OASIS catalog files, separated by semicolons | none |
| **Schema associations** | `pattern=schema` entries, separated by semicolons | none |
| **Wrap long text when formatting** | on · off | off |
| **Keep text as written in** | element names, separated by semicolons | `xsl:text`, `xsl:comment`, `xsl:processing-instruction`, `literallayout`, `programlisting`, `screen`, `synopsis`, `pre`, `xd:pre` |
| **Attributes when formatting** | Keep as written · One per line · One per line, aligned with the first | Keep as written |
| **Empty elements when formatting** | Keep as written · Collapse to `<a/>` · Expand to `<a></a>` | Keep as written |
| **Space before />** | on (`<a />`) · off (`<a/>`) | on |
| **Structure element limit** | a number | 5,000 |

- **Download schemas** decides whether lemminx may fetch the XSDs, DTDs and
  RELAX NG grammars a file names (`xsi:schemaLocation`, DOCTYPE, `xml-model`)
  from the internet, plain http included. With *Only in trusted folders*, a
  file in a [trusted folder](/docs/workspace#trusted-folders) gets its
  schemas, and trusting a folder turns downloads on for it. Files outside the
  open project are never in a trusted folder. When a schema was not downloaded
  because the file's folder is not trusted, the file says so with a
  **Trust Folder** button, and the status line names **Workspace: Trust This
  Folder** and this setting. Trusting turns the download on without a restart.
  The status line names only what would help: the setting when it is *Never*,
  and opening the folder as a project for a file outside it.
  External entities are never resolved.
- **Files without a grammar**: a hint is listed in the Problems window as well
  as a warning.
- **XML catalogs** map schema and DTD addresses to local copies, so they work
  without downloads. A relative path is looked for in each project's root
  folder.
- **Schema associations** bind files to a grammar by name, for example
  `*.myconf.xml=schemas/myconf.xsd`. A pattern without a folder matches the
  file name in any folder, and a relative schema path is under the project's
  root folder. So a relative path applies to files in the open project only;
  files outside it get the associations whose schema is an absolute path or a
  URL. The same goes for a relative catalog path.
- **Wrap long text** wraps text and long tags at the editor's fill column
  (the column Fill Paragraph wraps at). Off, nothing is wrapped, but runs of
  spaces and line breaks in an element's text are still joined. Indentation
  always follows the file's own.
- **Keep text as written in** names the elements whose text the server never
  reflows: by default XSLT's `xsl:text` and its siblings, and DocBook and HTML
  verbatim elements such as `pre` and `programlisting`. An element with
  `xml:space="preserve"` is always kept as written. An empty list reflows
  every element's text.
- The **Attributes**, **Empty elements** and **Space before />** settings,
  like Wrap, apply through the server only.
- **Structure element limit** caps how many elements Structure and folding list
  for one file, with or without the server. When a file reaches it, the status
  line names this setting. Reopen the file for a new limit to apply.

The schemas lemminx downloads are kept in `cache/lemminx` under Editora's
config folder, not in `~/.lemminx`, and settings export leaves them out.
[Doctor](/docs/troubleshooting#doctor)'s **XML schema cache** row shows their
size and folder, with **Clear**.

### Without the XML server

- **Format Document** still works on XML: a built-in formatter puts one element
  per line, in the file's own indentation. Text, CDATA and
  `xml:space="preserve"` content are kept exactly as written, up to two blank
  lines in a row are kept, a comment on the line of what it follows stays
  there, and the caret stays on the character it was on. A file whose tags do
  not nest is left alone, with a "not well-formed" message. It formats in the
  background, so a large file does not freeze the window. If you switch tabs
  meanwhile, the result still goes into that file and the status line names
  it; if you edit the file meanwhile, nothing is changed and the status line
  says to run it again. Format Document on the same file while it formats says
  so; another tab can format at once. In large-file mode it asks first
  (*Format big.xml?*) before a format that would more than double the line
  count. This formatter ignores the formatting settings above,
  which apply through lemminx only.
- **Structure** lists every element, leaves included, named by its qualified
  name and its `name`, `id`, `match` or `ref` attribute, for example
  `xs:element name="note"` or `xsl:template match="/"`. With the server, the
  outline shows the document only, without lemminx's *Grammars* rows.
- **A DTD** (`.dtd`, `.ent`, `.mod`) lists its declarations in Structure: each
  element with its attributes, then its entities (`&name;` and `%name;`) and
  notations. Comments and `IGNORE` sections are skipped. With the server,
  lemminx's own outline is used. A DTD has no XML tree preview.
- **Auto-close HTML/XML tags**: typing the close tag by habit after the one
  Editora inserted (`x</from>`) steps over it instead of writing a second one,
  but only when it is the end tag of the element you are closing. Typing
  `</b>` inside `<p>Hello <b>world|</p>` writes `</b>` before the `</p>`, and a
  typo such as `</itme>` becomes a tag of its own instead of renaming the end
  tag after it. While it steps over the end tag, every key acts on what you
  typed, not on the end tag: Backspace, Ctrl/Shift/Alt+Backspace, Delete,
  Enter, Tab, a paste and the Emacs kills. A space before `>` is fine, and
  moving the caret ends the step-over. The same holds in HTML, where tag names
  match in any case. With auto-rename on, the Emacs word kills (M-DEL, M-d) on
  a tag name keep the caret in that tag, so retyping a closing tag's name
  renames its own element.

### Installing lemminx

**Install…** on the XML card, the banner on an XML file, or **Install: Language
Server…** installs **lemminx 0.31.2**, the native build from Red Hat's
vscode-xml 0.29.3 release on GitHub (about 17 MB). Both the download and the
program inside it are checked against the SHA-256 the release publishes. The
installed server is asked one request before it is kept. Settings and
[Doctor](/docs/troubleshooting#doctor) show which lemminx runs, and Doctor
offers **Update…** when a later Editora moves to a newer one.

The install leaves the XML command in Settings blank, and a blank command runs
Editora's own copy before anything on your `PATH`. **Reset to default**, or
settings imported from another computer, therefore keep it connected.

The banner is not offered while the XML server is switched off. When the XML
command you set points at a program that is missing, it opens Settings rather
than installing over it.

lemminx's native build needs **glibc 2.34** or later on Linux. On an older
system Install says so and installs nothing; if the
[Maven `pom.xml` server](#pomxml-and-the-maven-server) is
installed, XML files run its Java lemminx instead, shown as *Java lemminx
0.30.1 (fallback)* in Settings and Doctor. That fallback is plain XML: it never
loads the Maven extension, so a pom in an untrusted folder stays schema-only,
and it validates and completes RELAX NG too. Editora removes the signature
files from the server's jars when it installs them (and fixes an earlier
install the next time it starts), which the RELAX NG support needs. An XML
command of your own that would load lemminx-maven is not started: the status
bar says why, Settings → LSP shows it as a problem and Doctor as an error.
Updating the Maven server also stops XML while XML runs on its Java lemminx. A server that exits as soon
as it starts shows its own error message in the status line, Settings and
Doctor, rather than *found* and *OK*.

### pom.xml and the Maven server

A `pom.xml` gets the **Maven-aware server**: JVM lemminx plus the
lemminx-maven extension, shown as **lemminx-maven** in Settings, Doctor and the
status bar. It adds dependency, plugin and GAV completion, completion of
`<scope>`, `<packaging>`, phases, goals and plugin `<configuration>`
parameters, hover with a dependency's managed version and where it is managed,
Go to Definition on parents, properties, modules and plugins, Maven's own
checks (an artifact that cannot be resolved, a missing or duplicated managed
version, a missing module), and refactorings such as *Extract as new property*.
Every other XML file keeps the fast native lemminx, and the
[XML settings](#xml-settings) apply to both.

It runs on the **Default JDK** chosen under Settings → Build Tools (else the `java` on your `PATH`), for Install… as well as for the
server itself. Its root is the outermost pom of a contiguous chain, so a
multi-module reactor is served by one JVM: in the open project, opened without a
project, and for a Maven project beside the open one. The status bar says
*Loaded and ready* only once the server reports its own Maven initialization
done, since it answers the handshake long before it can answer a request. Its
diagnostics are named after the server, the schema's "resource is downloading"
notice is shown as information rather than an error, and `${…}` property items
are left out of an artifact completion unless you typed `$`.

**Folder trust.** To build a pom's Maven model, lemminx-maven fetches the
parents, BOMs, plugins and extensions the pom names, from the repositories the
pom names, and loads plugin code into its JVM. So it only serves a pom in a
[trusted folder](/docs/workspace#trusted-folders): the folder it would run on
(the reactor's top) must be trusted. Any other `pom.xml` is edited with the
plain XML server, which validates and completes it against the bundled POM
schema, and its tab says *Maven-aware editing is off until you trust this
folder* with **Trust Folder** and **Not now**. **Trust Folder**, or
**Workspace: Trust This Folder** from the palette, asks first (Cancel is the
default) and then trusts the open project when the file is in it, else the
pom's own reactor. For any other file outside the project it trusts nothing:
open that file's folder as a project first. In every window, the folder's open
poms, background tabs included, then move to the Maven server at once. Revoking
that trust (Settings → Advanced → Trusted folders) stops the Maven server on
the folder and moves its poms back to the XML server, with their diagnostics.
While the Maven server is not installed, a pom in an untrusted folder instead
offers to install it, saying it runs only in folders you trust.

**Bundled POM schemas.** Editora ships the Maven POM 4.0.0 and 4.1.0 schemas
and hands both XML servers on this machine a catalog for them, ahead of your
own catalogs. A pom naming `maven-4.0.0.xsd` (over http or https, or the older
`maven-v4_0_0.xsd`) therefore validates and completes with schema downloads
off, in an untrusted folder or outside any project. The copies live in `cache/schemas` under
Editora's config folder; Doctor's **Clear** on the XML schema cache leaves them.

**Completion** lists the artifacts in your local repository (`~/.m2`). **Search
Maven Central when completing pom.xml coordinates**, on the server's card under
Settings → LSP and off by default, also asks search.maven.org as you type, so
what you type is sent to it; nothing is downloaded up front. Changing the
setting restarts the server. Editora tells the server which local repository
to use, expanding `${env.…}` and system properties in `<localRepository>` as
Maven does. lemminx-maven keeps what it resolves in `.lemminx-maven` beside
that repository: `~/.m2/.lemminx-maven` by default, or next to the folder
`<localRepository>` names in `~/.m2/settings.xml`. `${env.*}` property
completions list the variable names but never show their values. [Doctor](/docs/troubleshooting#doctor) lists it as the
**lemminx-maven artifact store** with **Clear**.

**Installing it.** While the Maven server is not installed, a `pom.xml` in a
trusted folder offers it in a banner that says what it adds, with **Install**
and **Not now**. Install downloads lemminx-maven 0.12.0 and the three libraries
its bundle leaves out (Guava, Commons Lang and jsoup), about 16 MB in all, from
the Eclipse repository and Maven Central, each pinned by version and checksum,
into one folder. It then starts the server and asks it one hover request before
keeping the install. A server that cannot answer is refused with the reason,
and the previous install stays in place. When the command you set for the
server points at a program that is missing, the banner offers **Settings…**
instead. The install leaves the command in Settings blank, and a blank command
runs Editora's own copy, so **Reset to default** keeps it.

A folder that lacks any of the jars, or whose jsoup is not a 1.14 release, does
not count as installed: `pom.xml` falls back to the XML server, the Settings →
LSP card reads *lemminx-maven: incomplete — Install… repairs it*, and
[Doctor](/docs/troubleshooting#doctor) names the missing jars beside an
**Install…** button. Click it once to repair the folder. Install puts in jsoup
1.14.3, the line the server's documentation renderer needs; with a newer jsoup,
every hover on a schema-documented element fails.

**While you edit a pom:**

- **Structure** names a `dependency`, `plugin`, `exclusion` or `extension` by
  its `groupId:artifactId`, and a `profile`, `execution`, `repository` or
  `pluginRepository` by its `id`, on the Maven server as on the XML server.
- **Hover** on a plugin or dependency links to the pom it is managed in;
  following the link opens that pom at the managing line. Any `file:` link
  ending in `#L28` or `#L28,22` opens the file at that line and column, unless
  a Markdown heading answers to the same name.
- ***Extract as new property in parent*** works with the parent pom open or
  closed. If the parent has unsaved changes, or changed on disk since the
  request, the edit is refused with a reason; save it and try again.
- A **malformed pom** (one that is not well-formed XML on disk) would stop
  lemminx-maven's dependency completion everywhere, offering only *Insert
  Comment* and *Insert CDATA*. Editora takes such a pom out of the Maven
  server's workspace instead, so groupId, artifactId and version completion
  keeps working, and the status bar names it: *Left out broken/pom.xml (not
  well-formed XML, line 11)…*. It looks at the files the Maven server reads:
  `pom*.xml`, `*pom.xml` and `*.pom` up to ten folders deep, `node_modules` and
  `build` included, but not hidden folders or `target`. Editora acts only when
  the Maven server itself reports the failure, so an honest empty answer
  changes nothing.
- lemminx-maven also fails on some poms that are well-formed XML: a tag given
  twice or misspelled, or a DOCTYPE that declares entities, under a parent it
  cannot resolve offline. Then the status bar says once that Maven completion
  is off and names the likely pom and line, for example *…probably
  dup/pom.xml (`<dependencies>` appears twice, line 13)*; the Application Log has
  the server's own message.
- Once that pom is fixed (saved in Editora, or fixed by a checkout or another
  editor, in any folder), Editora restarts the Maven server for that project
  only, and its open poms move to the new server at once. Each project's Maven
  server reports *Loaded and ready* on its own.
- **XML: Revalidate File** validates the pom again.

## Language servers on a remote host

A file in a [remote folder](/docs/remote) is served by a language server running
on its host, once that host
[may run commands](/docs/remote#allowing-a-host-to-run-commands): completion,
hover, diagnostics, go to definition, rename and the rest work as for a local
file. The server is the one the host's `PATH` finds. To use another, add a
`server = command` line for that host under **Language servers** in Settings →
Remote, for example `python = basedpyright-langserver --stdio`. A command set in
Settings → LSP is used on the host only when it is a bare program name rather
than a path on this machine.

- All files on the project's host share the project's server, including a file
  outside the project folder, such as a library source reached by Go to
  Definition.
- Renames and other cross-file edits create, move and delete files on the host.
- **`pom.xml`** uses the host's plain XML server, unless a `maven-pom = command`
  line for the host, or a bare program name set in Settings → LSP, names a
  Maven server there. Editora's own copy, which runs from a folder on this
  machine, is never sent to the host. The same holds in a container or WSL.
- **Python**: Pyright on the host is told the project's virtual environment as
  the host names it, found there the way [Run](/docs/run-debug) finds it, else
  the host's own `python` tool. The status-bar item names it, for example
  `python (.venv)`, without running it on this machine. If the connection
  drops, the host's own `python3` is used.
- **Java** works too: jdtls runs on the host with the host's JDKs and keeps its
  index in `~/.cache/editora` there.
  *LSP: Clear Java Index Caches* clears only the indexes on this machine.
- Files changed on the host, by `git pull` in a terminal for example, reach the
  server when the Editora window comes back to the front, and every minute while
  it stays there. Each check lists up to 300 folders and 5,000 files, and skips
  virtual environments.
- Losing the connection stops the host's servers without reporting a crash, and
  reconnecting starts them again.
- The Astro server does not run on a host yet.

## Language servers in a container or WSL

A project whose tools [run in a container](/docs/remote#tools-in-a-container)
or, on Windows, [in a WSL distribution](/docs/remote#tools-in-wsl-windows) has
its language servers run there, each the one the container's or distribution's
`PATH` finds, and told about the files by the names they have inside. A command
set in Settings → LSP is used there only when it is a bare program name.

- When the project's tools move into a container or back out, a server started
  on the other side is stopped and its files reopen on the right one.
- **Java**: jdtls runs inside with the JDKs found there, and keeps its index in
  `~/.cache/editora` inside, so it is kept across restarts of Editora for as long
  as the container lasts. *LSP: Clear Java Index Caches* clears only the indexes
  on this machine.
- **Python**: Pyright inside is told the project's virtual environment by the
  name it has inside, as on a remote host.
- Files changed inside reach the server the same way as on a remote host: when
  the window comes back to the front, and every minute while it stays there.
- The Astro server does not run in a container or distribution yet.

## Finding the server's binary

A GUI-launched app inherits a stripped `PATH` that often misses Homebrew, npm,
and version-manager directories. Editora rebuilds the `PATH` from your login
shell plus the usual install locations before launching a server, which recovers
things like nvm's per-version `node` bin. If a server still isn't found, set its
absolute path in Settings. See [Troubleshooting](/docs/troubleshooting#external-tools-arent-found)
for details.

## Notes and limits

A file reaches its server once it has loaded, with its whole text in the one
open message. The server never sees it empty first, so an XML file shows no
passing "Premature end of file" error while it opens. The indentation the server is
told, which quick fixes and generated files follow, is read from the file and
kept up to date as you edit. Document sync is **incremental** for servers that
accept it (most of them), with a periodic full resync as a divergence safety net, and semantic highlighting
transfers only what changed on servers that support token deltas. Diagnostics for
files that aren't open are dropped by default, to keep the Problems window
focused on what you're editing, and the whole-project selector above lifts that
when you want it. Java can [organize imports and format on
save](#java-only-assists), JavaScript and TypeScript have their own
[actions on save](#actions-on-save), and Python can
[format on save](#formatting-python) with Ruff or Black; other languages cannot
yet.

The protocol library is **LSP 3.18 / DAP 1.70**. Two fields it widened affect
Editora. A diagnostic's message may be markup rather than plain text;
the markup's text is used, so a squiggle always carries its explanation. A
document edit may be a **snippet** edit carrying `${1:name}` placeholders.
Editora **refuses** one rather than applying it, since inserting it as plain
text would write the placeholder markup into your file. That is the same
all-or-nothing rule that already covers file creates and deletes, and in
practice no server sends one, because Editora does not advertise the
capability.
