package com.editora.mcp;

import java.util.List;

/**
 * The seam between the off-FX MCP server thread and the FX-thread editor state. Implemented by
 * {@code MainController}, so the {@code mcp} package never imports {@code ui} internals. Each method
 * is called from an HTTP worker thread and is responsible for marshaling its reads onto the JavaFX
 * thread (the controller does this with a blocking {@code Platform.runLater} helper).
 *
 * <p>The nested records are plain data carriers; {@link McpTools} maps them to JSON by hand (it never
 * reflects on these types), so no {@code module-info} {@code opens} is needed for the {@code mcp}
 * package.
 */
public interface McpBridge {

    /** One open tab/buffer. {@code path} is null for an unsaved (untitled) buffer. */
    record OpenFile(String path, String title, String language, boolean dirty, boolean active) {}

    /** A buffer's live (possibly unsaved) text plus identity. */
    record BufferContent(String path, String title, String language, boolean dirty, String text) {}

    /** A flattened LSP diagnostic (1-based line/col for human/agent consumption). */
    record Diagnostic(int line, int col, String severity, String message, String origin) {}

    /** One find-in-files hit (1-based line/col). */
    record SearchMatch(String file, int line, int col, String lineText) {}

    /** A registered command the agent can run via {@code execute_command}. */
    record CommandInfo(String id, String title, String description) {}

    /** The active buffer's caret + selection (1-based lines/cols; with no selection the range collapses
     *  to the caret and {@code selectedText} is empty). */
    record Selection(
            String path,
            String title,
            int caretLine,
            int caretCol,
            int selStartLine,
            int selStartCol,
            int selEndLine,
            int selEndCol,
            String selectedText) {}

    /** One node of a file's LSP symbol outline (1-based lines for agent consumption). */
    record Symbol(String name, String detail, String kind, int line, int endLine, List<Symbol> children) {}

    /** One changed file from {@code git status} (porcelain-v2 index/worktree letters as 1-char strings). */
    record GitFileState(String path, String index, String worktree, String origPath) {}

    /** The Git status for the window's context; {@code repo} false when Git is off or there is no repo. */
    record GitState(
            boolean repo,
            String root,
            String branch,
            String upstream,
            int ahead,
            int behind,
            List<GitFileState> files) {}

    /** All open buffers, with which one is active. */
    List<OpenFile> listOpenFiles();

    /** The live text of the open buffer for {@code path}, or the active buffer when {@code path} is null;
     *  null when there is no such buffer. */
    BufferContent readBuffer(String path);

    /** LSP diagnostics for {@code path} (or the active buffer's file when null); empty when none. */
    List<Diagnostic> getDiagnostics(String path);

    /** Runs a multi-file search over the active project root + open buffers (unsaved text respected). */
    List<SearchMatch> findInFiles(String query, boolean caseSensitive, boolean regex, boolean wholeWord);

    /** Every registered command (id + localized title + description). */
    List<CommandInfo> listCommands();

    /** Runs the command with {@code id} on the FX thread; false when no such command exists. */
    boolean executeCommand(String id);

    /** Opens {@code path} in the editor and, when {@code line > 0}, moves the caret to the 1-based
     *  {@code line}/{@code col}; false when the file doesn't exist. */
    boolean openFile(String path, int line, int col);

    /**
     * Applies an undoable text edit to the open buffer for {@code path} (or the active buffer when null).
     * An empty/absent {@code oldText} replaces the whole buffer with {@code newText}; otherwise
     * {@code oldText} must occur exactly once unless {@code replaceAll}. Returns null on success, else a
     * human-readable error.
     */
    String editBuffer(String path, String oldText, String newText, boolean replaceAll);

    /** Saves the open buffer for {@code path} (or the active buffer when null) to its file. Returns null
     *  on success, else a human-readable error (untitled buffers can't be saved over MCP). */
    String saveBuffer(String path);

    /** The active buffer's caret + selection, or null when there is no active buffer. */
    Selection getSelection();

    /** The LSP symbol outline for {@code path} (or the active buffer's file when null); empty when the
     *  file isn't served by a ready language server. */
    List<Symbol> documentSymbols(String path);

    /** The Git status for this window's context (branch, ahead/behind, changed files). */
    GitState gitStatus();
}
