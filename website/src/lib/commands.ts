// Full command reference, transcribed from MainController.registerCommands()
// (id + title) and resources/com/editora/keymaps/emacs.json (default keys).
// Keep in sync when commands or bindings change.

export type Cmd = { title: string; id: string; keys?: string; desc: string };
export type CmdGroup = { title: string; commands: Cmd[] };

export const commandGroups: CmdGroup[] = [
  {
    title: "Files",
    commands: [
      { title: "New", id: "file.new", desc: "Create a new empty buffer." },
      {
        title: "Open…",
        id: "file.open",
        desc: "Open a file using the native OS file dialog.",
      },
      {
        title: "Find File…",
        id: "file.find",
        keys: "C-x C-f",
        desc: "Emacs find-file path popup with prefix autocomplete; descend folders, open, or create a file.",
      },
      { title: "Save", id: "file.save", keys: "C-x C-s", desc: "Save the active file." },
      {
        title: "Save As…",
        id: "file.saveAs",
        keys: "C-x C-w",
        desc: "Save the active file under a new name.",
      },
      {
        title: "Toggle Auto Save",
        id: "file.toggleAutoSave",
        keys: "C-c a",
        desc: "Cycle auto-save mode: off → after delay → on focus change.",
      },
      {
        title: "Clear Recent Files",
        id: "file.clearRecent",
        desc: "Empty the recent-files list (asks for confirmation).",
      },
      {
        title: "Recent Files: Jump…",
        id: "recent.jump",
        keys: "C-x C-r",
        desc: "Fuzzy picker over recently opened files.",
      },
    ],
  },
  {
    title: "Editing",
    commands: [
      { title: "Cut", id: "edit.cut", keys: "C-w", desc: "Cut the selection (or region) to the clipboard." },
      { title: "Copy", id: "edit.copy", keys: "M-w", desc: "Copy the selection (or region)." },
      { title: "Paste", id: "edit.paste", keys: "C-y", desc: "Paste (yank) the clipboard contents." },
      {
        title: "Delete Forward Char",
        id: "edit.deleteChar",
        keys: "C-d",
        desc: "Delete the character after the caret.",
      },
      {
        title: "Kill Forward Word",
        id: "edit.killWord",
        keys: "M-d",
        desc: "Delete from the caret to the end of the word.",
      },
      {
        title: "Kill Line",
        id: "edit.killLine",
        keys: "C-k",
        desc: "Delete from the caret to the end of the line.",
      },
      { title: "Undo", id: "edit.undo", keys: "C-/", desc: "Undo the last edit." },
      { title: "Redo", id: "edit.redo", keys: "C-S-/", desc: "Redo the last undone edit." },
      {
        title: "Set Mark",
        id: "edit.setMark",
        keys: "C-space",
        desc: "Drop the mark; subsequent movement extends the selection from it.",
      },
      {
        title: "Exchange Point and Mark",
        id: "edit.exchangePointAndMark",
        keys: "C-x C-x",
        desc: "Swap the caret and the mark.",
      },
      {
        title: "Toggle Comment",
        id: "edit.toggleComment",
        keys: "M-;",
        desc: "Comment or uncomment the current line or selection using the language's comment syntax.",
      },
      {
        title: "Cancel",
        id: "edit.cancel",
        keys: "C-g",
        desc: "Abort the current action and clear the mark.",
      },
    ],
  },
  {
    title: "Navigation",
    commands: [
      { title: "Forward Char", id: "nav.charForward", keys: "C-f", desc: "Move one character right." },
      { title: "Backward Char", id: "nav.charBackward", keys: "C-b", desc: "Move one character left." },
      { title: "Next Line", id: "nav.lineDown", keys: "C-n", desc: "Move down a line, preserving the goal column." },
      { title: "Previous Line", id: "nav.lineUp", keys: "C-p", desc: "Move up a line, preserving the goal column." },
      { title: "Forward Word", id: "nav.wordForward", keys: "M-f", desc: "Move to the next word." },
      { title: "Backward Word", id: "nav.wordBackward", keys: "M-b", desc: "Move to the previous word." },
      { title: "Line Start", id: "nav.lineStart", keys: "C-a", desc: "Move to the start of the line." },
      { title: "Line End", id: "nav.lineEnd", keys: "C-e", desc: "Move to the end of the line." },
      {
        title: "Back to Indentation",
        id: "nav.backToIndentation",
        keys: "M-m",
        desc: "Move to the first non-whitespace character on the line.",
      },
      { title: "Page Down", id: "nav.pageDown", keys: "C-v", desc: "Scroll down one page." },
      { title: "Page Up", id: "nav.pageUp", keys: "M-v", desc: "Scroll up one page." },
      { title: "Document Start", id: "nav.docStart", keys: "M-S-,", desc: "Jump to the start of the document." },
      { title: "Document End", id: "nav.docEnd", keys: "M-S-.", desc: "Jump to the end of the document." },
      { title: "Forward Paragraph", id: "nav.paragraphForward", keys: "M-S-]", desc: "Move to the next blank-line boundary." },
      { title: "Backward Paragraph", id: "nav.paragraphBackward", keys: "M-S-[", desc: "Move to the previous blank-line boundary." },
      { title: "Forward Sentence", id: "nav.sentenceForward", keys: "M-e", desc: "Move to the next sentence end." },
      { title: "Backward Sentence", id: "nav.sentenceBackward", keys: "M-a", desc: "Move to the previous sentence start." },
      { title: "Recenter on Caret", id: "nav.recenter", keys: "C-l", desc: "Scroll so the caret line is centered." },
      {
        title: "Go to Line…",
        id: "nav.goToLine",
        keys: "M-g g",
        desc: "Jump to a line, or line:column.",
      },
      {
        title: "Structure: Jump…",
        id: "structure.jump",
        keys: "M-g i",
        desc: "Fuzzy-jump to a symbol/structure node in the active file.",
      },
    ],
  },
  {
    title: "Search & replace",
    commands: [
      { title: "Find", id: "find.show", keys: "C-s", desc: "Open the find bar (case + regex options)." },
      { title: "Find Backward", id: "find.showBackward", keys: "C-r", desc: "Open the find bar searching backward." },
      { title: "Replace", id: "find.replace", keys: "M-S-5", desc: "Open find-and-replace." },
    ],
  },
  {
    title: "Buffers & tabs",
    commands: [
      { title: "Open Files: Jump…", id: "buffer.jump", keys: "C-x b", desc: "Fuzzy picker over the open tabs." },
      { title: "Switcher", id: "switcher.show", keys: "C-x C-b", desc: "Most-recently-used open-file switcher." },
      { title: "Switcher (Reverse)", id: "switcher.showReverse", desc: "Switcher cycling in reverse order." },
      { title: "Next", id: "buffer.next", desc: "Switch to the next tab." },
      { title: "Close", id: "buffer.close", keys: "C-x k", desc: "Close the active buffer." },
      { title: "Close Other Tabs", id: "buffer.closeOthers", desc: "Close every tab except the active one." },
      { title: "Close All Tabs", id: "buffer.closeAll", desc: "Close all tabs." },
      { title: "Close Unmodified Tabs", id: "buffer.closeUnmodified", desc: "Close tabs with no unsaved changes." },
      { title: "Close Tabs to the Left", id: "buffer.closeLeft", desc: "Close tabs left of the active one." },
      { title: "Close Tabs to the Right", id: "buffer.closeRight", desc: "Close tabs right of the active one." },
      { title: "Toggle Pin", id: "buffer.togglePin", desc: "Pin/unpin the tab (pinned tabs stay grouped at the front)." },
      { title: "Copy Path", id: "buffer.copyPath", desc: "Copy the active file's full path." },
      { title: "Rename File…", id: "buffer.rename", desc: "Rename the active file on disk (syncs the tab)." },
      { title: "Set Language…", id: "buffer.setLanguage", desc: "Override the syntax grammar for the active file." },
      { title: "Set Tab Size…", id: "buffer.setTabSize", desc: "Set the indentation width for the active file." },
      { title: "Convert Line Endings (LF/CRLF)…", id: "buffer.convertLineEndings", desc: "Switch the file's line-ending style." },
    ],
  },
  {
    title: "View & display",
    commands: [
      { title: "Toggle Line Numbers", id: "view.toggleLineNumbers", desc: "Show/hide the line-number gutter." },
      { title: "Toggle Minimap", id: "view.toggleMinimap", desc: "Show/hide the document minimap." },
      { title: "Toggle 80-Column Ruler", id: "view.toggleColumnRuler", desc: "Show/hide the 80-column guide line." },
      { title: "Toggle Current Line Highlight", id: "view.toggleLineHighlight", desc: "Highlight the caret's line." },
      { title: "Toggle Hidden Characters", id: "view.toggleWhitespace", keys: "C-c w", desc: "Render spaces, tabs, and line ends." },
      { title: "Toggle File Breadcrumb", id: "view.toggleBreadcrumb", keys: "C-c p", desc: "Show/hide the path breadcrumb bar." },
      { title: "Toggle Toolbar", id: "view.toggleToolbar", keys: "C-c t", desc: "Show/hide the toolbar." },
      { title: "Toggle Status Bar", id: "view.toggleStatusBar", keys: "C-c s", desc: "Show/hide the status bar." },
      { title: "Toggle Tab Bar", id: "view.toggleTabBar", keys: "C-c b", desc: "Show/hide the editor tab bar." },
      { title: "Toggle Zen Mode", id: "view.toggleZen", keys: "C-c z", desc: "Distraction-free mode — hide all chrome." },
      { title: "Toggle Read-Only (View Mode)", id: "view.toggleReadOnly", keys: "C-x C-q", desc: "Make the buffer read-only / editable." },
    ],
  },
  {
    title: "Folding",
    commands: [
      { title: "Fold", id: "view.fold", keys: "C-c C-f", desc: "Collapse the innermost region at the caret." },
      { title: "Unfold", id: "view.unfold", keys: "C-c C-u", desc: "Expand the region at the caret." },
      { title: "Toggle Fold", id: "view.toggleFold", keys: "C-c C-t", desc: "Fold/unfold the region at the caret." },
      { title: "Fold All", id: "view.foldAll", keys: "C-c f", desc: "Collapse every foldable region." },
      { title: "Unfold All", id: "view.unfoldAll", keys: "C-c u", desc: "Expand every region." },
    ],
  },
  {
    title: "Splits & zoom",
    commands: [
      { title: "Split Editor — Side by Side", id: "view.splitVertical", keys: "C-x 3", desc: "Show a second synced view side by side." },
      { title: "Split Editor — Stacked", id: "view.splitHorizontal", keys: "C-x 2", desc: "Show a second synced view stacked." },
      { title: "Unsplit Editor", id: "view.unsplit", keys: "C-x 1", desc: "Collapse back to a single view." },
      { title: "Zoom In Text", id: "view.textZoomIn", keys: "C-=", desc: "Scale the editor text up." },
      { title: "Zoom Out Text", id: "view.textZoomOut", keys: "C--", desc: "Scale the editor text down." },
      { title: "Reset Text Zoom", id: "view.textZoomReset", keys: "C-0", desc: "Reset text zoom to 100%." },
    ],
  },
  {
    title: "Markdown preview",
    commands: [
      { title: "Editor", id: "view.markdownEditor", desc: "Show only the editor." },
      { title: "Editor and Preview", id: "view.markdownSplit", desc: "Show editor and preview side by side." },
      { title: "Preview", id: "view.markdownPreview", desc: "Show only the rendered preview." },
      { title: "Zoom In Preview", id: "view.markdownZoomIn", desc: "Scale the preview text up." },
      { title: "Zoom Out Preview", id: "view.markdownZoomOut", desc: "Scale the preview text down." },
      { title: "Reset Preview Zoom", id: "view.markdownZoomReset", desc: "Reset preview zoom." },
    ],
  },
  {
    title: "Bookmarks",
    commands: [
      { title: "Toggle on Line", id: "bookmarks.toggle", keys: "C-c m", desc: "Add/remove a bookmark on the caret line." },
      { title: "Next in File", id: "bookmarks.next", keys: "C-c ]", desc: "Jump to the next bookmark in the file." },
      { title: "Previous in File", id: "bookmarks.previous", keys: "C-c [", desc: "Jump to the previous bookmark in the file." },
      { title: "Jump…", id: "bookmarks.jump", keys: "M-g b", desc: "Cross-file fuzzy bookmark picker." },
      { title: "Edit Note…", id: "bookmarks.editNote", desc: "Add or edit the note on a bookmark." },
      { title: "Clear in File", id: "bookmarks.clearFile", desc: "Remove all bookmarks in the active file." },
    ],
  },
  {
    title: "Snippets",
    commands: [
      { title: "Insert…", id: "snippets.insert", keys: "C-c i", desc: "Pick a snippet to expand at the caret." },
      { title: "Edit User Snippets…", id: "snippets.editUser", desc: "Open your user snippets file for the language." },
      { title: "Reload Snippets", id: "snippets.reload", desc: "Re-read snippet files from disk." },
    ],
  },
  {
    title: "Projects",
    commands: [
      { title: "Open Folder…", id: "project.open", keys: "C-x C-p", desc: "Open a folder as a project (keyboard picker)." },
      { title: "Switch…", id: "project.switch", keys: "C-x p", desc: "Save the current session and restore another project." },
      { title: "Close", id: "project.close", desc: "Return to the global session." },
      { title: "Delete…", id: "project.delete", desc: "Remove a project entry (files on disk are kept)." },
    ],
  },
  {
    title: "Tool windows & window",
    commands: [
      { title: "Tool Window: Project", id: "tool.project", keys: "M-1", desc: "Toggle the Project panel." },
      { title: "Tool Window: Bookmarks", id: "tool.bookmarks", keys: "M-2", desc: "Toggle the Bookmarks panel." },
      { title: "Tool Window: File Information", id: "tool.fileInformation", keys: "M-3", desc: "Toggle the File Information panel." },
      { title: "Tool Window: Structure", id: "tool.structure", keys: "M-7", desc: "Toggle the Structure panel." },
      { title: "Tool Windows: Jump…", id: "tool.jump", keys: "M-g t", desc: "Fuzzy picker over the tool windows." },
      { title: "Window: Other (Editor / Tool Window)", id: "window.other", keys: "C-x o", desc: "Cycle focus between the editor and tool windows." },
      { title: "Window: Toggle Maximize", id: "window.maximize", desc: "Maximize/restore the window." },
      { title: "Window: Toggle Full Screen", id: "window.fullScreen", desc: "Enter/exit full-screen." },
    ],
  },
  {
    title: "Appearance & spell check",
    commands: [
      { title: "Theme: Set App Theme…", id: "theme.setAppTheme", desc: "Pick the chrome theme (also switches the matching editor theme)." },
      { title: "Theme: Set Editor Theme…", id: "theme.setEditorTheme", desc: "Pick the editor color theme and pin it." },
      { title: "Toggle Spell Check", id: "view.toggleSpellCheck", desc: "Enable/disable spell checking." },
      { title: "Spell Check: Set Language…", id: "spell.setLanguage", desc: "Choose the dictionary for the active file." },
    ],
  },
  {
    title: "Application & help",
    commands: [
      { title: "Command Palette", id: "palette.show", keys: "M-x", desc: "Open the fuzzy command palette." },
      { title: "Settings", id: "view.settings", desc: "Open the Settings window." },
      { title: "About Editora", id: "help.about", desc: "Show version, build date, and config path." },
      { title: "Quit", id: "app.quit", keys: "C-x C-c", desc: "Quit Editora (asks to confirm)." },
    ],
  },
];

export const commandCount = commandGroups.reduce(
  (n, g) => n + g.commands.length,
  0,
);
