---
title: Smart file previews
description: Rendered and plain-English previews of data and config files, from JSON, YAML and TOML trees and OpenAPI docs to systemd, ssh and crontab files.
category: Editing
order: 8
---

Many file types get the same 3-mode preview (Editor / Split / Preview) as
Markdown, turning raw data and config into something readable. Each is **on by
default**, and each preview type has its own switch in the **File previews**
card of **Settings → Editor**.

Whatever the file, the same two commands switch the view: **`view.togglePreview`**
flips between the editor and the full preview, and **`view.toggleSplitPreview`**
flips between the editor and a split editor-plus-preview. They act on whatever
the active file previews as, so there's nothing per-type to remember. A floating
3-mode toggle also appears at the top right of any previewable file.

## Structured data and API docs

- `.json`, `.yaml`, and `.toml` render a collapsible, type-colored **data tree**.
- JSON Lines (`.jsonl`, `.ndjson`, `.jsonlines`) renders its records as a tree
  keyed by line number; a line that is not one JSON value shows a link to it
  instead.
- `.xml` (xsd, xsl, fxml, wsdl, rss, and so on) renders a faithful **DOM
  tree** of tags, attributes, and text. A `pom.xml` gets [its own
  rendering](#maven-poms) instead. When an XML file cannot be rendered, the
  preview shows the parser's message with a **Line N, column M** link that
  jumps to the spot (a preview-only view switches to Split to show it). A DTD
  (`.dtd`, `.ent`, `.mod`) is not a document, so it has no preview; its
  Structure lists its declarations instead (see
  [Without the XML server](/docs/xml#without-the-xml-server)).
- A JSON or YAML file recognized as an **OpenAPI 3 / Swagger 2** spec instead
  renders as browsable **API docs**: endpoints with colored method badges,
  params, responses, and schemas. Toggle between the tree and the docs with
  `structured.toggleView`.

![petstore.yaml in Split view: the YAML on the left and, on the right, API docs titled Pet Store 1.0.0 with its server and GET and POST endpoint cards listing parameters and responses](/screenshots/docs/openapi-preview.png "An OpenAPI spec previews as API docs beside its source.")

## Maven poms

A `pom.xml` previews as a **summary** rather than as the generic XML tree, which
spreads every dependency over four nested rows. You get coordinates, parent,
URL and SCM, then modules, dependencies, managed dependencies, plugins, managed
plugins, reporting plugins, repositories, properties, and each profile's own
set. The artifact name and its version are in aligned columns, so the versions
can be read down a column, and a name is never broken inside a word in a narrow
split. A repository row is tagged *plugin repository* where it is one, and
*not Maven Central* where its URL is somewhere else. A profile's row says when
Maven activates it (*activates when: jdk [17,), property env.CI*) beside its
*active by default* mark.

The summary resolves the indirections that otherwise send you back up the file:

- A **`${property}` version is resolved**, with the reference kept beside it
  (`5.10.2  ${junit.version}`), including properties defined in terms of other
  properties and the `project.*` built-ins.
- A **blank version is filled in** from the file's own `<dependencyManagement>`
  or `<pluginManagement>` and tagged *managed* (`2.0.17  managed`).
- For a local file, the **parent chain beside it** is read too, through
  `<relativePath>` (Maven's default `../pom.xml`) or, when that misses, the
  parent's pom in your local repository, and so are the **BOMs** the file
  imports, looked up in the local repository. A version they manage shows its
  number with its source: *from reactor-parent 2.0.0*, *from junit-bom 5.11.0
  (import)*. The parent line names the files it read.

Where the files cannot answer, the summary says so: a blank version under a
declared `<parent>` that was not found reads *inherited*, a dangling `${…}`
reads *unresolved*, and a version a BOM probably manages but which the summary
cannot pin reads *probably from* that BOM. A remote (SFTP) file keeps the
one-file reading. The summary is still not an effective pom: profiles, BOMs
imported by a parent and `<relativePath>` rewrites through settings are not
followed.

The summary is a place to work from, not only to read. A row takes you to its
element in the editor, a section title collapses its rows (**Properties**
starts collapsed on a pom with more than twelve), and a **Filter rows…** field
at the top narrows every section to the rows that mention the text. A pom that
does not parse names the line and column of the error, and the message is a
link to it.

Any file named `pom.xml` or `*.pom` qualifies, plus anything that sniffs as a
pom, so a `pom-template.xml` or an `effective-pom.xml` gets the same view.
**`pom.toggleView`**, or **Show as XML tree** in the preview's right-click menu,
switches to the standard XML rendering and back. Settings → Editor → File
previews turns the summary off entirely.

Poms also carry a [Maven submenu](/docs/build-tools#the-maven-submenu) on their
right-click menu.

## Config files, decoded to plain English

![deploy.crontab in Split view: each schedule decoded on the right, such as Every 15 minutes and At 02:30, Monday through Friday, with its next run times, and a bad line flagged with minute 99 out of range](/screenshots/docs/crontab-preview.png "A crontab decoded: each schedule in plain English with its next runs, and a malformed line flagged.")

- **crontab** (`crontab`, `*.cron`, `cron.d/*`): each schedule in plain English
  (`30 2 * * 1-5` becomes "At 02:30, Monday through Friday"), the next fire times,
  and the `@reboot` / `@daily` macros. Malformed lines are flagged with the field
  error.
- **fstab**: each mount line decoded, the device spec (UUID / LABEL / path / CIFS
  / NFS), mount point, filesystem, and the options.
- **systemd** units (`.service` / `.timer` / `.socket` / …): each directive
  glossed, with a `.timer`'s `OnCalendar=` decoded to English plus its next
  triggers.
- **SSH config**: a one-line connection summary per `Host` block plus option
  glosses.
- **Dockerfile**: a per-build-stage digest (base image, ports, workdir, user,
  entrypoint, health check).
- **GitHub Actions** workflows (detected by content): a plain-English digest of
  the triggers (with `schedule:` crons decoded) and each job's runner and steps.

## Markwhen timelines

A [Markwhen](https://markwhen.com) file (`.mw` or `.markwhen`) previews as a
**timeline**. Each line is an event: a date or a range, a colon, then the
text, as in `2026-02 / 2026-04: Design and prototype #Work`. A `#` heading
starts a group, a `#Tag: color` line in the header colours the events with that
tag, and `title:` names the timeline. A line the preview cannot read is skipped, so a
half-typed file still shows the rest.

- **Toggle Timeline / Calendar View** (`markwhen.toggleView`, also on the
  preview's right-click menu) switches to a month **calendar**. Editora
  remembers the view for each file. Events that span more than ten years are
  too wide for the calendar, which then says to use the timeline.
- **Export Timeline to JSON…** (`markwhen.exportJson`, also on the right-click
  menu) saves the parsed timeline: its title, tag colours, groups, and each
  event's label, tags, line and dates.
- The **Markwhen Timeline** [file template](/docs/snippets-templates#file-templates)
  starts a year with groups, ranges and coloured tags.

## Viewers

- `.pdf` files open in a read-only **page viewer** (rasterized via PDFBox). Its
  top bar turns pages and zooms: −, +, **Fit** and **1:1**. The mouse
  wheel zooms around the pointer, Ctrl/Cmd+wheel zooms faster, and a touchpad
  pinch zooms too; Shift+wheel pans across, Alt+wheel pans down, a sideways
  swipe pans, and dragging pans, as on the [Project Map](/docs/project-map#panning-and-zooming).
- Images (`.png`, `.jpg`, `.gif` and `.bmp`) open in a read-only
  **image viewer** with the same −, +, **Fit** and **1:1** buttons; Ctrl+wheel zooms.
- `.svg` files stay editable XML but gain a live **vector** preview: it is drawn
  through JSVG's JavaFX canvas and redrawn at whatever size it is shown at, so
  it stays sharp at any zoom rather than going soft past 200% the way a scaled
  bitmap does. Transparent files get a checkerboard behind them, so a white icon
  on a transparent background is visible instead of looking like an empty pane.
  An SVG that cannot be drawn shows the parser's message and a **Line N, column
  M** link to the error, like the XML preview.
  Markdown badges and every export path (PDF, print, Word, ODF) still rasterize.
- Binary files open as a read-only [hex dump](/docs/editing#hex-viewer).

With a PDF or an image in the active tab, **View: Zoom In Text**, **Zoom Out
Text** and **Reset Text Zoom** (`view.textZoomIn`, `view.textZoomOut`,
`view.textZoomReset`) and their keys zoom it, as its −, + and 1:1 buttons do.

Every preview can **export to PDF** from its right-click menu, and *File:
Print Rendered Preview…* (`preview.print`) and *File: Export Preview to
PDF…* (`preview.exportPdf`) act on whatever the active file previews as. Tree
and summary previews break pages between rows, an OpenAPI file outputs its
documentation view when that is the one showing, and a file that does not parse
has nothing to export until the syntax error is fixed. A very long tree stops at
4,000 rows and says so. Image tabs print and export with *File: Print…* and
*File: Export to PDF…*. See [Print & PDF export](/docs/print-pdf).
