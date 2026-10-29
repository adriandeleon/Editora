# Project Map canvas navigator

The Project Map is the spatial file navigator of the Project tool window. It complements the
traditional tree; it does not replace the tree or introduce a second file-management model. The Map
always opens in a window of its own (`ProjectMapWindow`), never inside the Project tool window, which
keeps showing the tree: a canvas of columns wants the room of a document, not of a side panel. Both
share the active project root, filesystem watcher, file-opening callback, editor and Git state,
ordering, icons, and context menu actions; each has its own search field.

The focus-traversable **Map** button beside the Project search field opens the window, or brings it
forward when it is open already; the `project.toggleMapView` command (no default key) does the same from
the palette — even with the Project tool window closed — and closes the window when it is the one in use.
The window is *not* owned by the editor window: an owned stage stays above its owner for good, so a file
opened from a large Map would land behind it. Unowned, the two stack like any other windows, and the Map
window closes itself when its editor window hides or the project closes. Whether it was open, and where,
is remembered per workspace, and a workspace that last had it open reopens it (without taking focus from
the editor). The Map lists nothing until its window is first opened, and reloads every time it opens, so a
window that never opens the Map costs no listing and no loader thread.

Work started in the Map window that happens in the editor window brings the editor window forward:
opening a file (a row, Enter, a preview card's Open), and every context-menu action except Rename, New
Folder, Undo Move, Delete and Reveal, which stay over the Map — the name prompts in the Map window's own
overlay host, the confirmations owned by its stage. Escape with nothing left to dismiss returns to the
editor, in the editor window. The window copies the editor window's stylesheets and icons, and gets the
editor window's key dispatcher, as a floating tool window does.

The implementation is deliberately hybrid. Native JavaFX controls handle text entry, checkboxes,
buttons, focus, accessibility, and popups. A focusable `Canvas` draws the hierarchy, connectors,
selection state, and overview and performs explicit hit-testing. A separate native overlay provides
the floating code preview.

## User model

The map presents branch-aware Miller columns through the project:

1. The first column contains the project root.
2. Expanding a directory shows its children in a new column at the next depth.
3. Expanding a sibling retains the existing branch and opens another independent column.
4. Closing a column or collapsing its parent removes only that branch and its descendant columns.

Every non-root column has one meaningful parent, but several parents may own independent columns at
the same depth. This keeps parallel work visible without merging unrelated subtrees into one dense
column.

The default flow is left to right (`ProjectMapView.DEFAULT_FLOW`), the direction the breadcrumb and
the Tree read in. The flow selector also supports right to left, top to bottom, and bottom to top. It
changes column placement, connector direction, and the meaning of the arrow keys together. Changing
flow clears manual column offsets and locks, then auto-fits the new layout. The last selected flow is
stored in workspace state and restored when the editor is reopened. Right-to-left was the default
before workspace schema v13 and was written into every file, so the v26 step resets a stored
right-to-left to left-to-right once (`ConfigMigrations.resetRightToLeftMapFlow`).

## Interaction reference

### Pointer

| Gesture | Result |
| --- | --- |
| Single-click a folder | Select and expand it; if it is already expanded, select it and bring its column into view |
| Click a folder's chevron (the row's trailing edge) | Expand or collapse it; collapsing closes its descendant columns |
| Single-click a file | Open it in a normal editor tab |
| Click a file's preview icon | Open or focus that file's floating read-only preview on the canvas |
| Click a file's Structure icon (just before the eye) | Open or close the file's structure column |
| Click a declaration | Open or focus a floating preview of only its lines |
| Click a declaration's chevron | Open or close the column of the declarations it holds |
| Right-click a node | Select it and open the same context menu as the Project tree, including first-line bookmark and Personal Note actions |
| Drag empty canvas space | Pan the map |
| Middle-button drag | Pan the map, wherever the drag starts (including on a row) |
| Mouse wheel | Zoom around the pointer position; scroll inertia does not zoom |
| Shortcut + mouse wheel | Zoom faster around the pointer position |
| Horizontal scroll (touchpad swipe, tilt wheel) | Pan horizontally |
| Pinch | Zoom around the gesture |
| Shift + mouse wheel | Pan horizontally |
| Alt + mouse wheel | Pan vertically |
| Drag a column header | Move that column independently unless it is locked; it stops at its parent column |
| Click a column lock | Prevent or allow accidental header dragging |
| Click a column close button | Close that branch and all of its descendant columns |
| Options (⋯) menu → **Print…** | Open the standard print preview for the complete map layout |
| Options (⋯) menu → **PDF…** | Export the complete map layout using the configured PDF page size |
| Click **?** | Show the mouse and keyboard model in a popover |
| Click the overview | Recenter the canvas around that content position |

Preview and note cards sit on the canvas: a pan or zoom carries each card's corner with the map under it
and draws the card at the map's zoom from that corner (`MapSurface.moveCardsWithViewport`, run after every
paint, sets each card's `setMapScale`), so cards shrink and grow with the columns. A card's own width and
height are unzoomed; placement, overlap, connectors and size limits work on the card as drawn
(`ProjectMapView.cardScale`), and the resize grip divides the pointer's movement by the zoom. Each title bar
moves its card — like a column header, anywhere, including out of view, where panning finds it again — each
lower corner resizes it up to the panel's size at the current zoom, and each editor scrolls independently.
A resized panel bounds a card's size but never moves it; a zoom never resizes a card, so one zoomed in may
outgrow the panel as a column does. The map area (`canvasHost`) clips its children, so whatever part of a card
lies outside it is neither drawn nor clickable — never over the filter bar or breadcrumbs above. The card's own − / + buttons scale its text on top of the map's zoom. Opening another file
keeps existing cards visible; clicking an already-previewed file focuses its existing card, and each
close button removes only that card. An accent connector runs from every preview to its source file row
and follows map pan/zoom plus card movement and resizing.

Code previews and note cards are placed by one path (`ProjectMapView.previewPlacement` over
`MapSurface.previewPlacement`). A card opens beside its row's column — on the side the flow leaves free,
else on the other — when at least the card's minimum size fits there; otherwise it lies over the map
against the panel edge, which is the normal case in a tool-window-wide panel. It is then shifted or
cascaded clear of the cards already open. **Placement never pans the map**, and a card is never smaller
than its minimum (340 × 220 for code, 300 × 180 for notes) unless the panel itself is. Text that arrives
after a card was placed only widens it where it stands, by at most 120 columns and never past the panel
(a card already partly out of view is never narrowed);
a card the user moved keeps its position and one the user resized keeps its size.

Up to eight cards remain open, code and note cards together; a ninth replaces the least recently used
one. Pressing a card, moving keyboard focus into it and scrolling it all count as use. A folder's cards
close when the folder stops being expanded, however it was collapsed, and all cards close when the root
changes. Switching to the Tree and back keeps them where they were.

A preview uses current unsaved text when the file is already open; otherwise it reads the file off
the JavaFX application thread, decoded as the editor would open it (BOM, then the `.editorconfig`
charset, then UTF-8 with a single-byte fallback instead of U+FFFD) and with `\n` line endings. It is
visibly marked read-only, has independent text/image zoom controls, and renders common bitmap image
formats as well as syntax-highlighted text. Its context menu can copy selected code, select all, add a
bookmark at the clicked line, or add a Personal Note for the selected or clicked lines. The **Open**
action promotes the previewed file to a normal editor tab.

An open preview is kept current without being moved or re-scrolled: a card that mirrors an open buffer
looks for edits once a second while the Map is on screen and replaces only the range that changed; any
other card re-reads its file when the Project watcher reports a change and the file's size or
modification time differs. A card whose file was deleted or renamed keeps its last text, says the file
no longer exists, and disables **Open**.

`Escape` in a focused card closes it and returns focus to the map; `Tab` leaves the preview's text.
Global chords still act on the active editor tab while a card has focus (the app-wide key policy), so a
focused card has an accent border and its text area's accessible name states the file and that it is
read-only. Cards cannot be moved or resized from the keyboard.

Context menus use JavaFX auto-hide plus a next-pulse owner-scene press filter. This mirrors the
other Project menus and ensures a click elsewhere closes the menu even on platforms where the
native popup grab misses the press.

Files and folders with one or more bookmarks or Personal Notes show compact, independently colored
indicators in both the Tree and Map. Personal Notes indicators are interactive: they open a separate editable note card attached to the same
file or folder row by a connector. A note card and the code preview of the same file open and close
independently, under the shared limit above. The options
menu's default-off “Hide all open Personal Notes” item temporarily hides those cards without closing them.
A note card saves an edit on focus loss, Shortcut+Enter or close, comparing against the body the store
last held. It follows the store: when notes change elsewhere it adopts the new bodies (an edit in
progress is kept), and it closes when its notes are deleted. A blanked note is not a deletion — its text
is restored and the status bar says so.
A note card is styled from the theme's warning (amber) tokens, so it is a pale sheet on light themes and a
dark amber one on dark themes.

A code or declaration card carries its own Personal Notes. Selecting text in it brings up a floating bar over
the selection — the Markdown buffer's (`MarkdownFormatBar.placeY`/`trackMouse`), with one **Add Personal Note**
button — that is gone mid-drag, for a blank selection, with Personal Notes off, on focus loss and on a first
Escape (a second closes the card). A **Notes** button before **Open** counts the notes on what the card shows —
its file's, without folder notes, or those starting on a declaration's lines (`ProjectMapStickyNotes.notesFor`,
at most 24) — is disabled at none and absent with Personal Notes off. It sticks them on the card as **sticky
notes** (`ProjectMapStickyNotes`): one small editable amber sheet per note, headed by its line, each tied by a
note-coloured connector from a dot on the card's edge at its line (the text's top or bottom when scrolled past)
to the sheet. The sheets stand outside the card's right edge, else its left, else on the card against its right
edge (`side`), each as near its line as the sheets above allow, a further column when they run past the card's
foot (`stack`). They hold their place relative to the card's edge as it moves, resizes and zooms; one the user
drags keeps its new spot until the card closes. They save like a note card (focus loss, Shortcut+Enter, a blanked
note restored), follow the store, follow "Hide all open Personal Notes", rise with their card, and close with it;
they are not cards and take nothing from the eight-card limit. A note added from a card — its bar or its menu —
brings the card's notes up once the store has it.
Marker state is read from an open buffer when available and otherwise from
the active project's persisted stores, so adding an annotation refreshes both views without opening
the target file.

### Structure columns and declaration cards

A file whose language the Structure tool window can outline has a **Structure icon** — that window's glyph
of stepped lines — before its eye. `StructureOutline.supports` decides by the name alone (a language with a
bundled grammar, or Markdown, Typst, `.http` or Clojure; never an encrypted file), so a row costs no read.
The icon opens a **structure column**: the file's declarations, as the Structure window shows them, in the
order they are written. A declaration that holds others (a class's members, a heading's subsections) ends in
a chevron and opens a further column, as a folder does; a file whose outline is one top-level declaration
with members (most Java files) opens that declaration's column too. The icon sits in a filled disc while its
column is open. A structure column has the usual title (`App.java · Structure`, or the declaration's
label), count, filter, lock and close button, but no Show hidden checkbox. While its file is being outlined,
when it cannot be read and when it has no declarations it holds one explanatory row; past 1,000 rows it ends
in one that says how many were left out.

**Clicking a declaration opens a declaration card**: the ordinary preview card showing only the declaration's
lines — its block, from its declaration (or its fold region's first line, when that is above it) to the
region's last line, extended upwards over the doc comment, annotations, decorators and Rust attributes
directly above it (`ProjectMapStructure.range` / `leadingDocStart`). A heading runs to the line before the
next heading at its level; a declaration with no block of its own is its own line. The card reads and
highlights the whole file and shows that slice, so a line inside a comment or a string is coloured as in the
editor; its gutter keeps the file's numbers in the narrowed amber, its title reads `App.java › add()`, its
status line says `Lines 9–19 of 29`, and it opens no taller than its lines need (but no smaller than the card
minimum). **Open** opens the file at the declaration; **Open Narrowed** (the card's and the row's menu) opens
it narrowed to the declaration's fold block through `EditingCoordinator.narrowToFoldRegionAt`, as the Structure
window's Narrow to fold region does. Bookmarks and Personal Notes added from a declaration card land on the
file's own lines. Declaration cards are keyed by the declaration's row, so several of one file can be open,
under the shared eight-card limit.

A declaration row's path is made up: its file followed by one segment per declaration down to it
(`App.java/…sApp~0/…sadd~0`), each the declaration's name up to its parameter list, made file-name safe, and
its place among same-named siblings. No such path can exist on disk — a file has no children — and because
the segment is a name rather than a line, an open column, the selection and an open card survive an edit that
moves the declaration. `ProjectMapModel.Entry.symbol` rows are neither files nor folders (`isSymbol()`,
`isFile()`): the Project context menu, F2, Delete, the type filter, the open/unsaved/Git/marker states and the
hidden-files filter never apply to them; their emphasis under a filter is their file's. Closing a structure
column, collapsing the folder above its file or deleting the file closes the columns below it and their cards.

`ProjectMapStructure` holds which files and declarations have a column open and the outline of each. Outlines
are built on one daemon `project-map-outline` thread from the open buffer's text when the file is open —
unsaved edits included, and the language server's document symbols when the Structure window has them for
that buffer (`StructurePanel.serverSymbolsFor`) — or from the file read and decoded as a preview card reads
it (`ProjectMapPreview.readText`, under the same 1,000,000-byte cap). Each result is checked against the newest
request for its file. They are brought up to date with the cards: an open buffer is compared by its text
(once a second while one is on show), a closed file by its size and time on disk after every listing and
watcher notification. When an outline changes, every declaration card is moved to its declaration's new lines
(`setRange`); a card whose declaration is gone keeps its last lines and says "No longer in this file". No
language server is started for a closed file, the Map's global search still matches file names only, and
nothing about the columns is remembered across restarts.

### Keyboard

| Key | Result |
| --- | --- |
| Arrow along the flow | Select the first child, or expand the selected directory, file structure or declaration |
| Arrow against the flow | Select the parent |
| Perpendicular arrows | Move among siblings |
| `Ctrl-N` / `Ctrl-P` | Select the next or previous sibling |
| `Page Down` / `Page Up` | Move ten siblings forward or backward, stopping at the last or first row |
| `Enter` or `Space` | Open the selected file or declaration card, or expand or collapse the selected folder |
| `F2` | Rename the selected file or folder (not the project root), as in the Project tree |
| `Delete` | Delete the selected file through the Project tree's confirmed delete |
| Menu key / `Shift-F10` | Open the selected node's context menu at its row |
| `Backspace` | Select the parent |
| `Home` | Select the project root |
| `Alt-Left` / `Alt-Right` | Move backward or forward through map selection history |
| `/` | Focus and select the selected column's filter text |
| Shortcut + `0` | Fit all visible columns |
| `Escape` | Close the open preview and note cards; otherwise clear the Map's search query; otherwise return focus to the editor (in the editor window) |

Single sibling steps (perpendicular arrows, `Ctrl-N` / `Ctrl-P`) wrap around the column; page moves do
not. `/` is matched by the character typed rather than the key, so it works where the slash is a
shifted key and on the numeric keypad. When the selected column's filter is hidden by a low zoom the map
zooms in just far enough and scrolls it into view first; the project column has no filter and reports
that in the status bar.

Text fields own their keystrokes. In particular, `Backspace`, arrows, `Home`, `F2`, and `Delete` edit a
focused column filter rather than triggering map navigation. Pressing Enter in a column filter returns
focus to the map and selects that column's first remaining row unless the selection is already one of
them. Activating a selection whose row a column filter has hidden does nothing. If a focused column
control is hidden (zooming out) or removed (closing its column), focus returns to the map surface.

The scene-level `KeyDispatcher` sees every key before the map. While the surface itself is the focus
owner it declares `Alt-Left`, `Alt-Right`, Shortcut + `0`, `Ctrl-N`, `Ctrl-P`, `F2`, and `Delete` through
`KeyDispatcher.CLAIMED_KEYS`, so they reach it in every bundled keymap instead of running the keymap's
command for the same chord (text-zoom reset, New File, Print, Find File, symbol rename) or being
swallowed as an unbound `Alt` chord. The surface always consumes them; the dispatcher consumes a
claimed `Alt` chord that comes back unconsumed, so none reaches the native menu. The shared Project
search field claims `Ctrl-N` / `Ctrl-P` the same way.

Selection history holds at most 100 entries. A run of sibling moves is one entry, so Back returns to
where the run started rather than retracing each row. Closing a column moves the selection to that
column's folder only when the selection was inside the closed branch.

The bottom-left zoom bar is icon buttons (each with a tooltip and accessible name) around the current
percentage: zoom out, zoom in, Fit, Center selection, and Reset. Manual zoom runs from 40% to 225%.

- The **first view** of a project opens at 100% with the root column and the start of the first column in
  view (a small project that fits is centred instead). It steps down, never below 85%, only when that is
  what shows both columns.
- **Fit** (the button or Shortcut + `0`) scales and centres the whole map when that is possible at 40% or
  more. When the floor is hit it anchors on the selected path instead: the selected row, its column
  header, and as many of the columns leading to it as the view holds.
- **Auto-fit** (changing flow, or opening a folder with Keep current zoom off) does the same with 85% as
  its floor, so the map never shrinks to an unreadable thumbnail by itself.
- **Reset** restores 100%, clears manual column positions and locks, and returns to the first view.

Reveal, Fit and the first view reserve the strip the zoom bar covers, and a revealed row is also lifted
clear of the overview.

The options (⋯) menu in the navigation row holds the default-on **Keep current zoom** and **Focus new
column** options, remembered per workspace (`isKeepZoom()` / `isFocusNewColumn()` and their setters on the view). Opening a
folder therefore preserves the user's scale and brings the new column in from its start: the whole card
when it fits beside the row that opened it, otherwise its header and first rows together with that row.
Either effect can be disabled independently; disabling zoom preservation restores auto-fit on expansion.

The filter row holds only filters and wraps instead of truncating; the selectors show their value alone
("Source", "Left → Right") and carry the "Type:" / "Flow:" form as tooltip and accessible name. Breadcrumbs
that do not fit collapse their middle into one "…" (whose tooltip lists the hidden folders) and keep the
root and the last two crumbs.

## Filters, ordering, and state

The search field at the top of the Map window is the map's global fuzzy name query (the Project tool
window's own field filters the tree only). It combines with:

- status chips for files that are open, modified, Git-changed, bookmarked, or have Personal Notes;
- a type selector for source, markup, configuration, other files, or all files;
- a fuzzy free-text filter in every non-root column;
- a per-column **Show hidden** checkbox.

Hidden (dot) entries follow the Project "show hidden files" setting: it is each column's default, it is
what the loader lists (so hidden entries spend no row budget while it is off), and changing it puts every
column back on the setting. Ticking one column's checkbox overrides the setting for that folder only and
lists the folder again.

The status chips are alternatives to one another: selecting Open and Bookmarks matches either state.
The type and text criteria constrain that working set. Matches and their ancestors remain prominent while
unrelated nodes fade, preserving spatial context. A match does not have to be a loaded row: the Open,
Modified, Bookmarks and Personal Notes chips also light the collapsed folders that hold a matching file,
as the Git chip does. Those ancestor sets come from the open tabs and the marker stores' keys
(`ProjectPanel.setOpenFiles`, `MarkerActions.markedPaths`) by path arithmetic; nothing is read from disk
on the FX thread. Folders match the Bookmarks and Personal Notes chips in their own right.

**Hide unmatched** (a checkbox in the filter row, remembered per workspace as `projectMapHideUnmatched`,
workspace schema v25) takes the rows that would fade off the map instead. It keeps exactly the emphasized
set — the matches and the folders that lead to them — by handing it to `ProjectMapModel.columnsById` as a
keep set, so a folder taken out takes its open columns with it, as a column filter does. Placeholder rows
("+N more", "Empty folder") stay. Each column counts what it lost (`Column.narrowed`): its header reads
"shown/total", an italic "N hidden" footer sits under its rows (or on its empty row when none are left), and
an amber "N rows hidden" badge beside the checkbox totals them. With no filter active it changes nothing.
Column widths are still measured from every loaded row, so switching it does not resize the cards.

The type selector asks `ProjectMapModel.classify`, which maps `LanguageRegistry.forFileName` — the
registry behind the file icons and the editor's language detection — to a bucket: program languages are
Source, document and style languages Markup, every other recognized language (data formats, unit files,
the name-determined configuration files) Config. A short extension table covers common types the registry
has no language for, and a few build files (`pom.xml`, `*.gradle`, `CMakeLists.txt`) are pinned to Config.

A global filename query uses the Project tree's bounded, off-thread search and temporarily opens every
ancestor column needed to reveal its matches. The matches are loaded before anything else, in rank order,
so the first match is always present and is selected; when they do not all fit the row limit the status
bar says "showing N of M matches". The search never writes to the manual expansion set. Folders opened or
closed by hand while a query is active (chevron, column ×, a reveal) are kept in the search's own view, so
closing a search-opened column really closes it, and clearing the query restores exactly the manually
expanded branches. Re-running the same query after an in-app file change reloads the map and leaves the
selection alone. A column filter removes unmatched rows and their now-unreachable descendants so the
remaining geometry is still a valid hierarchy.

When a query or chip is active and no loaded row matches, a "No files match" message (with the query)
appears over the canvas once that state has lasted 300 ms — a global query first fades what is loaded and
only then opens the folders that hold its matches. The viewport is not moved. A column whose own filter
matches nothing keeps one row of space for a "No matches" line.

Rows use `ProjectPathOrder`: directories first, then case-insensitive names with a deterministic
case-sensitive tie-break. This is the same ordering contract as the traditional Project explorer.

Canvas nodes reuse `FileIcons.forProjectItem`, rasterized and cached per file kind and status (plus an
on-accent variant, style class `project-map-icon-on-accent`, for the focused selection). A file already
open in an editor tab has an accent rail and an accent-coloured label. Unsaved changes are a filled dot
and a Git state is the status letter the Project tree uses, so the two differ in shape as well as hue.
Every folder row ends in a chevron that points along the flow; an expanded folder's sits in a filled
disc. `chevronZone(NodeBox)` / `chevronHit(NodeBox, x)` give the strip that takes the collapse/expand
click, as `previewHit` does for the eye that opens a file's preview.

The selected row is filled with the accent and ringed only while the surface has keyboard focus; without
focus (and in Print/PDF output) it is a tinted row. Tooltips show the full normalized path, type, file
size (binary units), modification time, and relevant open, unsaved, or Git status from the loaded
snapshot — preceded, over the eye, a chevron or a note badge, by what a click there does. Hover does no
filesystem work.

## Layout and rendering

`ProjectMapView.MapSurface` owns the canvas coordinate system. Node and column geometry is computed
in world coordinates, transformed by zoom and pan, stored as immutable hit boxes, and then painted.
Connectors are drawn before nodes. The live map submits connectors only for destination rows that intersect
the viewport plus a small overscan, so one visible parent cannot make a tall offscreen column rasterize every
Bezier curve. Nodes use the viewport itself, while the overview summarizes the complete laid-out content.
Continuous pan, zoom, and column-drag events update their state immediately but coalesce Canvas rendering to
one repaint per JavaFX pulse.

Print and PDF output render every laid-out column and connector independent of the live viewport. The
buttons hand the window a deferred `ProjectMapOutput`; nothing is rendered until the page size is known,
so a cancelled PDF dialog costs nothing and print re-renders for the layout chosen in the print dialog.
`ProjectMapOutputPlan` (pure) decides the pages: the map goes on one page while a row label stays at
least 7 pt tall, on a landscape page when it is wider than tall; a larger map keeps that scale and is
tiled across a grid of pages, cut between columns and rows where a gap is in reach, and pages nothing
falls on are left out. The status bar says when the map was scaled down or tiled. A per-page dimension
cap (8,192 px) and a total pixel budget (12 Mpx) remain as a safety net; a map that cannot keep 96 dpi
inside them is still output, with a warning that small labels may be unreadable.

`MapSurface.renderOutput` paints each page on an off-scene `Canvas` — `paint()` draws on the `canvas`
field, which points at it for the duration — so the live Canvas, pan, zoom and hover state are never
touched. A Canvas is backed by one texture of its size times the highest screen scale, and Prism caps a
texture at 4,096 px by default, so a page is assembled from renders of at most 2,048 px (less on a
denser screen). Output always uses a light palette (`outputPalette`, plus looked-up colour overrides
for the rasterized row icons), whatever the live theme; it preserves the active flow, filters, open
branches and manual column positions, and omits the interactive column controls, the overview, focus and hover;
`outputPaint` is set while it paints, which also collapses every header to its title line. Labels
are bitmap, so the PDF is not searchable.

Column cards are content-sized rather than uniform. For each branch column, the map measures every
loaded entry name at the drawing font and reserves enough width for the full label, icon, status
marks, directory arrow, and padding. The minimum node width is 164 pixels. Measuring the underlying
loaded entries—not only the currently filtered rows—keeps widths stable when a filter or Hidden
checkbox is toggled. In a language whose checkbox label is long, branch columns are widened so the header
row still holds the filter, the labelled checkbox and the lock at 100%.

Along the flow, every depth has a band of its own: a column starts beyond its parent card and beyond the
widest card of the parent's depth, so columns at different depths cannot overlap (manual offsets are kept
out of the band, so dragging one column does not move other branches). Across the flow, a column is
centred on the row that opened it while it is short and hangs from that row once it is long
(`MAX_COLUMN_LEAD`), so its header, its first rows and its parent row can be on screen together. Columns
of one depth are then packed along the perpendicular axis so parallel branches never overlap. In the two
vertical flows a connector stops at the card's edge above (or leaves from the card's edge below) the row
instead of crossing the header.

Column filters, hidden-file checkboxes, lock buttons, and close buttons are ordinary child controls
positioned over the painted column headers after each layout. As zoom takes header space away, the
checkbox first drops its label (it keeps its tooltip and accessible name) and the filter takes a shorter
prompt; when the detail controls no longer fit at all they are hidden and the header collapses to its
title line, so the rows move up instead of leaving an empty band. They return when space is available.
The header title is elided, and the count gives way first, rather than the two colliding at low zoom.
Controls that would reach into the overview are hidden too: they are children above the canvas and would
paint over it and take its clicks. The controls are not drawn into the canvas, which preserves native
text editing, focus traversal, and accessibility.

The overview appears only while some content is off-screen, sits beside the zoom bar (it is left out when
the surface is too narrow for both), and clamps its viewport rectangle to its frame.

## Architecture and data flow

```mermaid
flowchart LR
    ProjectPanel[ProjectPanel] -->|root, query, Git and editor state| View[ProjectMapView]
    View -->|background request plus generation| Model[ProjectMapModel]
    Model -->|bounded entry snapshot| View
    View --> Surface[MapSurface Canvas]
    View --> Preview[ProjectMapPreview]
    View -->|open files and declarations| Structure[ProjectMapStructure]
    Structure -->|StructureOutline, off the FX thread| View
    ProjectPanel -->|shared factory| Menu[Project tree context menu]
    Surface -->|selected file| Preview
    Surface -->|activate file| Editor[normal editor tab]
```

Responsibilities are split as follows:

- `ProjectPanel` owns the Map (it builds the Map window's content: search row and map), the window's
  open state and its persistence, the filesystem watcher (watching the tree's and the map's open folders
  together), root, editor state, Git state, open-file action, and construction of the traditional context
  menu. It tells the map when its window is open (`setActive`), forwards the hidden-files setting, and
  reports in-app renames and moves (`pathRenamed`).
- `ProjectMapWindow` owns only the stage: building it, copying the editor window's look, placement from
  the remembered bounds (or large and centred on the editor window), closing with the editor window, and
  telling a close by the user (remembered) from one by the editor window or the project (not).
- `ProjectMapView` owns expansion (manual and per-search), row limits, selection history, breadcrumbs,
  global controls, async reloads, the deferred print/PDF job, and the bounded collection of floating
  cards: opening, placement, eviction, refresh scheduling, and closing the cards of a folder that is no
  longer expanded.
- `ProjectMapModel` is JavaFX-free. It loads bounded snapshots with per-directory facts, maintains
  independent branch expansions, groups entries by owning parent, applies ordering and filters,
  classifies file types, and determines emphasized ancestor paths.
- `ProjectMapView.MapSurface` owns paint, layout, transforms, hit-testing, pointer/keyboard input,
  column controls, icon snapshots, and accessibility text.
- `ProjectMapPreview` owns one bounded read-only RichTextFX card, off-thread file loading and syntax
  highlighting, in-place refresh, drag/resize behavior, and promotion to an editor tab.
- `ProjectMapStructure` owns the structure columns: which files and declarations have one open, the
  outline of each (built off the FX thread by `StructureOutline`, the Structure tool window's own outline
  builder), the made-up declaration paths, the rows they become, and their pruning.
- `ProjectMapNotePreview` owns one editable Personal Notes card: a text area per note, saving, and
  adopting the store's notes on refresh.
- `ProjectMapStickyNotes` owns one code card's sticky notes: their sheets, placement beside the card, following it,
  saving, adopting the store's notes, and drawing their connectors onto the view's connector canvas.
- `ProjectMapOutputPlan` is the JavaFX-free page plan for print/PDF; `ExportCoordinator` renders the
  `ProjectMapOutput` for the PDF page size or the print layout and reports how it was fitted.

The Project tree is the source of truth for file-management actions. `ProjectPanel` injects a
context-menu factory into the map and calls the same `contextMenuFor(...)` path used by tree cells.
New, Maven, rename, delete, reveal, terminal, local-history, and Git items therefore retain their
existing availability and behavior without a parallel command list.

The two output buttons are also the commands `projectMap.print` and `projectMap.exportPdf`
(`WindowCommandRegistrar`), which run the buttons' own handlers through `ProjectPanel.printMap()` /
`exportMapPdf()`. `Chrome.contextReason` enables them only while `ProjectPanel.isMapOutputAvailable()`:
Map mode, the tool window in the scene, and at least one column to draw.

## Threading, bounds, and lifecycle

Filesystem listing runs on the single daemon `project-map-loader` executor, created on the first load.
Each request captures the root, the expanded set, the hidden-files rules, the row limits and the pinned
paths. An atomic generation is checked when a queued request starts and between directories, so a burst of
requests lists the folders once, and obsolete results never reach the FX thread. A request made after
`dispose()` is ignored. Each child costs one attribute read (two for a symbolic link), which serves both
the ordering and the row. A load that outlasts 200 ms shows a "Loading…" label over the canvas, and an
empty map reads "Loading…" rather than "No project items" while its first load is in flight. Every action
still re-lists every expanded directory, and one hung listing still blocks later reloads for that window;
there is no per-directory cache or timeout.

`ProjectMapModel.load` reads only the root and the expanded directories, under two limits:

- **per directory**, `DIRECTORY_CHUNK` (300) rows. A longer directory ends in a "+N more…" row; activating
  it (click, Enter, or the arrow that expands) raises that directory's limit by another chunk.
- **overall**, `MAX_VISIBLE_ITEMS` (1,200) real rows. Pinned paths — the search's matches and a revealed
  file — are charged first, with the rows that lead to them, wherever they sort; the rest is handed out
  breadth-first.

Nothing is dropped silently. The snapshot carries `DirectoryFacts` (real child count, rows loaded,
unreadable) for every listed directory, and a column header shows "shown/total" whenever a filter or a
limit hides rows. A directory that got no rows at all is reported as skipped, is not drawn as expanded,
leaves the manual expansion set, and the status bar says the overall limit was reached; the same message
appears when "+N more…" cannot load anything. An expanded folder with nothing to list gets a column holding
one stub row, "Empty folder" or "Cannot read this folder". A failure to read one directory does not
discard the rest of the snapshot.

The "+N more…" and stub rows are placeholder entries (`Entry.isPlaceholder()`): they flow through layout,
selection and hit-testing like any row, report themselves as directories so file-only behaviour never
applies, are drawn as text by `drawPlaceholderRow`, have no context menu, and are exempt from column
filters. Code that acts on a folder entry must check `isPlaceholder()` first.

After a load the manual expansion set is pruned to folders that actually loaded, so a renamed, deleted or
replaced folder does not leave stale expansion (or watch keys) behind; folders beneath one that could not
be read are kept. An in-app rename or move remaps expansion, limits and the selection to the new path.
`revealPath` adds the target's ancestors to the expansion instead of replacing it, and a pending selection
that a reload does not contain is dropped, together with its selection-history entries.

Each open preview owns a daemon `project-map-preview-loader`; the eight-card limit bounds their total
number. Every loader queue is coalesced so stale work for that card does not accumulate. Closed text
reads are capped at 1,000,000 bytes, image reads at 20,000,000 bytes, displayed text at 400,000
characters, and syntax highlighting at 160,000 characters; neither cap splits a multi-byte sequence or
a surrogate pair. Images are decoded on the loader thread. Per-card generation checks guard both
loaded text and highlighting results. Binary, failed and missing reads produce explicit preview states.
Card refreshes are debounced (250 ms after a state or listing notification, at most once a second for
content-only disk changes and for following an open buffer) and stop while the Map is off screen.

All scene-graph mutation, paint, and control synchronization stays on the JavaFX application thread.
No paint, hover, or per-keystroke path accesses the filesystem. `dispose()` invalidates generations,
stops both executors, hides popups and tooltips, and clears control and measurement caches.

Changing roots clears expansion, selection history, per-column filters, positions, locks, zoom, and
preview state. The view remembers the open branches, row limits and selection of the last eight local
folders it showed, so in the window with no project — where the root follows the active tab's folder —
switching back to a folder restores its map instead of starting from the root; zoom and column positions
are not carried over. Workspace state persists whether the Map window is open (`projectViewMode`, schema
v13 — "MAP" meant the Map was the panel's view before v24, and means its window is open since), the
window's bounds (`projectMapWindowBounds`, v24), the flow, and the two navigation options; expansion, zoom
and the status/type filters are not persisted.

## Tests and change checklist

The focused coverage lives in:

- `ProjectMapModelTest` for hidden files, filter semantics, ancestor emphasis, independent branch
  expansion, sorting, and column filtering;
- `ProjectMapModelLoadingTest` for the per-directory and overall limits, "+N more" and stub rows,
  pinned paths, cancellation, expansion pruning, rename remapping, and type classification;
- `ProjectPanelMapWindowFxTest` for the separate window: never in the panel, unowned, styled like the
  editor window, closing with the editor window and the project, what the workspace remembers and
  reopens, the window's own search field, row actions, refresh and print while it is open;
- `ProjectMapDataFxTest` for deferred and re-opened loading, the tree kept while the Map is open, reloads during a
  search, search expansion and its status message, reveal, the hidden-files setting, chip emphasis of
  collapsed folders, folder markers, remembered mode and options, and per-folder memory;
- `ProjectMapInputFxTest` for keyboard and pointer input: real key events fired through a wired window
  under the Emacs, CUA, VS Code, IntelliJ, and Sublime keymaps, `/` by typed character, the keyboard
  context menu, row keys, Escape, history coalescing, header drags, hover, and wheel and pinch gestures;
- `ProjectMapOutputPlanTest` for the print/PDF page plan (fit, scale, tiling, cuts, budgets), the paged
  PDF writer, and preview decoding at the read caps;
- `ProjectMapCardsFxTest` for whole-map output beyond the viewport in a light palette, deferred PDF
  rendering, card placement in narrow panels without panning, late-load growth, decoding and CRLF
  highlighting, buffer/disk refresh and missing files, note-card saving and refresh, Escape/Tab, the
  shared card limit, closing cards on collapse, and keeping cards when the Map window is closed and reopened;
- `ProjectMapViewFxTest` for the Map button and window integration, native icon rasterization, open markers,
  tooltips, single-click expansion, multiple independent previews, shared context menus and dismissal, text-field
  key ownership, content-sized columns, hidden toggles, directional layouts and arrow semantics,
  complete-map output snapshots and live viewport restoration,
  movable/locked columns, overview navigation, and wheel zoom;
- `ProjectMapStructureTest` for which files get a Structure icon, declaration path segments, a card's lines
  (block, doc comment, headings, field), and the order of a structure column;
- `ProjectMapStructureFxTest` for the Structure icon, nested columns and the lone-class rule, declaration cards
  (their lines, gutter numbers, title, Open, Open Narrowed, two of one file), closing a column with its cards,
  following an open buffer's edits and a removed declaration, Markdown headings, the keyboard, the
  declaration menu and row keys, a deleted file, and Print output;
- `ProjectMapLayoutFxTest` for the first view, Fit and auto-fit anchoring, opening long folders, depth
  bands, header collapse, reserved areas, empty states, row painting (selection, focus ring, chevrons,
  status marks, contrast), the toolbar at tool-window widths in all six languages, breadcrumbs, the help
  popover, and accessible names.

When extending the map:

1. Keep filesystem and preview work off the FX thread and generation-guard every result.
2. Keep column state keyed by both depth and owning parent so sibling branches remain independent.
3. Reuse `ProjectPathOrder`, `FileIcons`, the Project context-menu factory, and registered commands.
4. Update connector geometry, arrow semantics, fit bounds, overview bounds, and tests together when
   changing layout.
5. Keep native controls for text entry and popups; ensure the map key filter does not consume their
   editing keys.
6. Add or update every message key in all six localization catalogs.
7. When adding a key, check it against every bundled keymap: a chord the keymap binds, or any unbound
   `Alt` chord, reaches the surface only if it is in `ProjectMapView.claimedChords`.
8. Run `mvn spotless:apply`, the focused test classes, `git diff --check`, and `mvn verify`.
