#!/usr/bin/env python3
"""Generate src/lib/commands.ts from the app's real sources:
   - command ids from MainController.registerCommands()
   - titles from i18n messages.properties (command.<id>)
   - default keys from keymaps/emacs.json
Re-run after commands or bindings change:  python3 website/scripts/gen-commands.py
"""
import re, json, os

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
OUT = os.path.join(os.path.dirname(__file__), "..", "src", "lib", "commands.ts")

mc = open(os.path.join(ROOT, "src/main/java/com/editora/ui/MainController.java")).read()
ids = sorted(set(re.findall(r'Command\.of\("([^"]+)"', mc)))

props = {}
for line in open(os.path.join(ROOT, "src/main/resources/com/editora/i18n/messages.properties"), encoding="utf-8"):
    line = line.rstrip("\n")
    if line.startswith("command.") and "=" in line:
        k, v = line.split("=", 1)
        props[k[len("command."):]] = v

km = json.load(open(os.path.join(ROOT, "src/main/resources/com/editora/keymaps/emacs.json")))
key_for = {}
for k, v in km.items():
    key_for.setdefault(v, k)

ORDER = [
    "Files", "Editing", "Navigation", "Search", "Buffers & tabs",
    "View & display", "Folding", "Splits & zoom", "Markdown",
    "Code intelligence (LSP)", "Run", "Debugging", "Git & diff",
    "HTTP client", "Diagrams", "Bookmarks", "Personal notes",
    "Snippets & templates", "Projects", "Remote (SFTP)", "Spell check",
    "Appearance", "Tool windows", "Window", "Application",
]

def category(cid):
    p = cid.split(".")[0]
    if cid in ("editor.exportPdf", "editor.print", "preview.exportPdf", "preview.print"):
        return "Files"
    if cid in ("file.run", "file.runWithArgs"):
        return "Run"
    if p in ("file", "recent"):
        return "Files"
    if p == "edit":
        return "Editing"
    if p == "nav":
        return "Navigation"
    if p in ("find", "search"):
        return "Search"
    if p in ("buffer", "switcher"):
        return "Buffers & tabs"
    if p == "markdown" or cid.startswith("view.markdown") or p == "preview" or cid == "view.toggleMarkdownPreviewTheme":
        return "Markdown"
    if cid in ("view.fold", "view.foldAll", "view.unfold", "view.unfoldAll", "view.toggleFold"):
        return "Folding"
    if cid in ("view.splitHorizontal", "view.splitVertical", "view.unsplit",
               "view.textZoomIn", "view.textZoomOut", "view.textZoomReset"):
        return "Splits & zoom"
    if cid in ("view.settings", "view.welcome", "view.messageLog", "view.debugLog"):
        return "Application"
    if p == "view":
        return "View & display"
    if p in ("git", "diff", "merge"):
        return "Git & diff"
    if p == "debug":
        return "Debugging"
    if p == "lsp":
        return "Code intelligence (LSP)"
    if p == "http":
        return "HTTP client"
    if p == "mermaid":
        return "Diagrams"
    if p == "run":
        return "Run"
    if p == "bookmarks":
        return "Bookmarks"
    if p == "notes":
        return "Personal notes"
    if p in ("snippets", "template"):
        return "Snippets & templates"
    if p == "project":
        return "Projects"
    if p == "remote":
        return "Remote (SFTP)"
    if p == "spell":
        return "Spell check"
    if p == "theme":
        return "Appearance"
    if p == "tool":
        return "Tool windows"
    if p == "window":
        return "Window"
    return "Application"

# Curated descriptions where they add info beyond the title (plain text).
DESC = {
    "edit.cut": "Cut the selection (or region) to the clipboard.",
    "edit.copy": "Copy the selection (or region).",
    "edit.paste": "Paste (yank) the clipboard contents.",
    "edit.deleteChar": "Delete the character after the caret.",
    "edit.killWord": "Delete from the caret to the end of the word.",
    "edit.killLine": "Delete from the caret to the end of the line.",
    "edit.setMark": "Drop the mark; movement then extends the selection.",
    "edit.exchangePointAndMark": "Swap the caret and the mark.",
    "edit.toggleComment": "Comment/uncomment the line or selection.",
    "edit.cancel": "Abort the current action and clear the mark.",
    "edit.completion": "Manually trigger autocomplete at the caret.",
    "edit.addCaretNextOccurrence": "Add a caret at the next match of the selection.",
    "edit.addCaretAbove": "Add a caret on the line above.",
    "edit.addCaretBelow": "Add a caret on the line below.",
    "edit.collapseCarets": "Collapse multiple carets back to one.",
    "edit.transposeChars": "Swap the two characters around the caret.",
    "edit.transposeWords": "Swap the two words around the caret.",
    "edit.transposeLines": "Swap the current line with the one above.",
    "find.show": "Incremental find (case / regex / whole-word).",
    "find.showBackward": "Incremental find, searching backward.",
    "find.replace": "Find and replace in the current file.",
    "search.inFiles": "Project-wide find (and replace) across files.",
    "nav.aceJump": "Jump the caret to any visible spot by typing a label.",
    "nav.goToLine": "Jump to a line, or line:column.",
    "nav.recenter": "Scroll so the caret line is centered.",
    "lsp.gotoDefinition": "Jump to the definition of the symbol at the caret.",
    "lsp.findReferences": "List all references to the symbol at the caret.",
    "lsp.hover": "Show type/documentation for the symbol at the caret.",
    "lsp.restartServers": "Restart all running language servers.",
    "git.stageFile": "Stage the current file's changes.",
    "git.switchBranch": "Open the branch dropdown to check out a branch.",
    "git.commit": "Open the Commit tool window and focus the message.",
    "debug.start": "Start debugging, or continue when paused.",
    "debug.runToCursor": "Resume and stop at the caret line.",
    "debug.jumpToLine": "Move the execution pointer to the caret (no code run).",
    "debug.toggleBreakpoint": "Add/remove a breakpoint on the caret line.",
    "debug.toggleExceptionBreakpoints": "Break on thrown/uncaught exceptions.",
    "http.runRequest": "Send the .http request at the caret.",
    "http.runFile": "Send every request in the file in order.",
    "diff.vsHead": "Diff the file against the last commit (HEAD).",
    "diff.compareWith": "Diff the file against another file you pick.",
    "diff.vsCommit": "Diff the file against a commit you pick.",
    "merge.resolve": "Open the merge-conflict resolver for the file.",
    "notes.add": "Attach a personal note to the caret line/selection.",
    "notes.jump": "Jump to a note across files.",
    "notes.search": "Search note bodies, tags, and files.",
    "template.new": "Create a new file from a template (untitled).",
    "template.newInFolder": "Create a template file written to a folder.",
    "run.rerun": "Re-run the last file with the same arguments.",
    "file.run": "Run the current file (Java/Python/shell).",
    "file.runWithArgs": "Run the current file with program arguments.",
    "remote.connect": "Open a file over SFTP from a remote host.",
    "mermaid.export": "Export the Mermaid diagram to SVG/PNG/PDF.",
    "switcher.show": "Most-recently-used open-file switcher.",
    "buffer.jump": "Fuzzy picker over the open tabs.",
    "recent.jump": "Fuzzy picker over recently opened files.",
    "structure.jump": "Fuzzy-jump to a symbol in the active file.",
    "bookmarks.jump": "Cross-file fuzzy bookmark picker.",
    "tool.jump": "Fuzzy picker over the tool windows.",
    "palette.show": "Open the fuzzy command palette.",
    "view.toggleReadOnly": "Make the buffer read-only / editable.",
    "view.toggleZen": "Distraction-free mode — hide all chrome.",
    "config.export": "Zip the active config directory to your home folder.",
    "editor.exportPdf": "Export the file to a PDF (light theme).",
    "editor.print": "Print the file (with a preview).",
    "preview.exportPdf": "Export the Markdown/Mermaid preview to PDF.",
    "preview.print": "Print the Markdown/Mermaid preview.",
    "window.other": "Cycle focus between the editor and tool windows.",
}

def js(s):
    return s.replace("\\", "\\\\").replace('"', '\\"')

groups = {g: [] for g in ORDER}
for cid in ids:
    groups[category(cid)].append(cid)

lines = []
lines.append("// AUTO-GENERATED by website/scripts/gen-commands.py — do not edit by hand.")
lines.append("// Sources: MainController.registerCommands(), messages.properties, emacs.json.")
lines.append("// Re-run the script after commands or keybindings change.")
lines.append("")
lines.append("export type Cmd = { title: string; id: string; keys?: string; desc?: string };")
lines.append("export type CmdGroup = { title: string; commands: Cmd[] };")
lines.append("")
lines.append("export const commandGroups: CmdGroup[] = [")
for g in ORDER:
    if not groups[g]:
        continue
    lines.append("  {")
    lines.append(f'    title: "{js(g)}",')
    lines.append("    commands: [")
    for cid in groups[g]:
        title = js(props.get(cid, cid))
        key = key_for.get(cid, "")
        desc = js(DESC.get(cid, ""))
        parts = [f'title: "{title}"', f'id: "{cid}"']
        if key:
            parts.append(f'keys: "{js(key)}"')
        if desc:
            parts.append(f'desc: "{desc}"')
        lines.append("      { " + ", ".join(parts) + " },")
    lines.append("    ],")
    lines.append("  },")
lines.append("];")
lines.append("")
lines.append("export const commandCount = commandGroups.reduce(")
lines.append("  (n, g) => n + g.commands.length,")
lines.append("  0,")
lines.append(");")
lines.append("")

open(OUT, "w").write("\n".join(lines))
total = sum(len(v) for v in groups.values())
print(f"Wrote {OUT}: {total} commands in {sum(1 for g in ORDER if groups[g])} groups")
