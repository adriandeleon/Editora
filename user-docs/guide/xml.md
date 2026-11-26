---
title: "XML & pom.xml"
description: "The lemminx XML server, its settings, working without it, and the Maven-aware pom.xml server."
category: Languages
order: 22
---

XML files get code intelligence from lemminx, and a Maven `pom.xml` from lemminx with its Maven extension.

## Language server

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
[Doctor](/docs/doctor)'s **XML schema cache** row shows their
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
[Doctor](/docs/doctor) show which lemminx runs, and Doctor
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
completions list the variable names but never show their values. [Doctor](/docs/doctor) lists it as the
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
[Doctor](/docs/doctor) names the missing jars beside an
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
