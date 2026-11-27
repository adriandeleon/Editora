package com.editora.mcp;

import java.util.List;

import org.jspecify.annotations.Nullable;

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
    record OpenFile(@Nullable String path, String title, String language, boolean dirty, boolean active) {}

    /** One tab in the strip — including non-editor tabs. {@code type} is {@code editor}/{@code image}/
     *  {@code hex}/{@code diff}/{@code merge}/{@code welcome}/{@code other}; {@code path} is null when the
     *  tab has no file (Welcome, an untitled buffer). */
    record TabInfo(String type, String title, @Nullable String path, boolean active) {}

    /** One scanned TODO/highlight match (1-based line/col). {@code keyword}/{@code tag}/{@code priority}
     *  are the parsed comment structure ({@code null} when absent). */
    record TodoItem(
            String file,
            int line,
            int col,
            String keyword,
            @Nullable String tag,
            @Nullable String priority,
            String text) {}

    /** A buffer's live (possibly unsaved) text plus identity. */
    record BufferContent(@Nullable String path, String title, String language, boolean dirty, String text) {}

    /** A flattened LSP diagnostic (1-based line/col for human/agent consumption). */
    record Diagnostic(int line, int col, String severity, String message, String origin) {}

    /** One find-in-files hit (1-based line/col). */
    record SearchMatch(String file, int line, int col, String lineText) {}

    /** A registered command the agent can run via {@code execute_command}. */
    record CommandInfo(String id, String title, String description) {}

    /** The active buffer's caret + selection (1-based lines/cols; with no selection the range collapses
     *  to the caret and {@code selectedText} is empty). */
    record Selection(
            @Nullable String path,
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
    record GitFileState(
            @Nullable String path,
            String index,
            String worktree,
            @Nullable String origPath) {}

    /** The Git status for the window's context; {@code repo} false when Git is off or there is no repo. */
    record GitState(
            boolean repo,
            @Nullable String root,
            @Nullable String branch,
            @Nullable String upstream,
            int ahead,
            int behind,
            List<GitFileState> files) {}

    /**
     * What the user decided about a proposed edit: {@code accepted} (applied, undoable), {@code rejected} (with the
     * user's {@code reason}, may be empty), {@code timeout} (no answer in time; nothing applied), or {@code refused}
     * (the proposal could not be made or applied; {@code reason} says why).
     */
    record ProposalOutcome(String outcome, String reason) {}

    /**
     * Shows the user an edit of the open buffer for {@code path} (the active one when null) as a diff to accept or
     * reject, and waits for the answer. The edit is {@code oldText} → {@code newText} as {@code edit_buffer} makes it
     * ({@code replaceAll}), or the whole text when {@code wholeBuffer}. {@code why} is shown with it. Default: refused.
     */
    default ProposalOutcome proposeEdit(
            @Nullable String path,
            @Nullable String oldText,
            String newText,
            boolean replaceAll,
            boolean wholeBuffer,
            @Nullable String why,
            String agent) {
        return new ProposalOutcome("refused", "Proposed edits are not available.");
    }

    /** Whether the user wants every agent edit proposed for approval (the {@code mcpEditsNeedApproval} setting). */
    default boolean editsNeedApproval() {
        return false;
    }

    /** One comment of a review thread, as an agent reads it. {@code authorKind}: {@code user}, {@code agent}, {@code github}. */
    record ReviewCommentInfo(
            String id,
            String authorKind,
            String author,
            String body,
            @Nullable String suggestion,
            long createdAt) {}

    /**
     * A review thread as an agent reads it: its file (absolute), its lines where they are now (1-based, inclusive;
     * the last known ones when {@code outdated}), its state ({@code open}, {@code resolved}, {@code dismissed}) and
     * why, an agent finding's severity and category, and its comments, oldest first.
     */
    record ReviewThreadInfo(
            String id,
            String path,
            int startLine,
            int endLine,
            boolean outdated,
            String state,
            @Nullable String stateNote,
            @Nullable String severity,
            @Nullable String category,
            List<ReviewCommentInfo> comments) {}

    /** A thread an agent started: its id and lines, or why it was refused ({@code error} set, {@code id} null). */
    record ReviewAdded(@Nullable String id, @Nullable String error, int startLine, int endLine) {}

    /**
     * The review threads of the window's repository (of {@code path}'s, when given, only those on that file), open
     * ones only unless {@code all}. Default: none (a bridge without review comments).
     */
    default List<ReviewThreadInfo> listReviews(@Nullable String path, boolean all) {
        return List.of();
    }

    /**
     * Starts a review thread on lines {@code startLine..endLine} (1-based) of {@code path}, written by {@code agent}.
     * Refused (an {@code error}) for a file outside the window's repository or project, or lines the file has not.
     */
    default ReviewAdded addReview(
            String path,
            int startLine,
            int endLine,
            String body,
            @Nullable String suggestion,
            @Nullable String severity,
            @Nullable String category,
            String agent) {
        return new ReviewAdded(null, "Review comments are not available.", 0, 0);
    }

    /** Replies to thread {@code threadId}; null when done, else why not. */
    default @Nullable String replyReview(String threadId, String body, @Nullable String suggestion, String agent) {
        return "Review comments are not available.";
    }

    /** Resolves thread {@code threadId} with {@code note} (what fixed it); null when done, else why not. */
    default @Nullable String resolveReview(String threadId, String note, String agent) {
        return "Review comments are not available.";
    }

    /**
     * Each open file's change stamp, by path: a number that changes whenever its text does (a buffer's document
     * version). Cheap — no text is copied — so the server can poll it while an agent watches resources. Default: the
     * open files with stamp 0 (a bridge that can't tell changes only reports files opening and closing).
     */
    default java.util.Map<String, Long> bufferStamps() {
        java.util.Map<String, Long> out = new java.util.TreeMap<>();
        for (OpenFile f : listOpenFiles()) {
            if (f.path() != null) {
                out.put(f.path(), 0L);
            }
        }
        return out;
    }

    /** All open buffers, with which one is active. */
    List<OpenFile> listOpenFiles();

    /** The live text of the open buffer for {@code path}, or the active buffer when {@code path} is null;
     *  null when there is no such buffer. */
    @Nullable
    BufferContent readBuffer(@Nullable String path);

    /** LSP diagnostics for {@code path} (or the active buffer's file when null); empty when none. */
    List<Diagnostic> getDiagnostics(@Nullable String path);

    /** Runs a multi-file search over the active project root + open buffers (unsaved text respected). */
    List<SearchMatch> findInFiles(String query, boolean caseSensitive, boolean regex, boolean wholeWord);

    /** Every registered command (id + localized title + description). */
    List<CommandInfo> listCommands();

    /** Runs the command with {@code id} on the FX thread; false when no such command exists. */
    boolean executeCommand(String id);

    /**
     * Why a client may not run command {@code id} (and does not see it in {@link #listCommands}), or null. Default:
     * none refused.
     */
    default @Nullable String commandRefusal(String id) {

        return null;
    }

    /**
     * Opens {@code path} in the editor and, when {@code line > 0}, moves the caret to the 1-based
     * {@code line}/{@code col}. Returns null when the file is open, else a human-readable error: the file
     * does not exist, or it is one this client may not bring into the editor (outside the window's project).
     */
    @Nullable
    String openFile(String path, int line, int col);

    /**
     * Replaces {@code oldText} — never null or empty — in the open buffer for {@code path} (or the active
     * buffer when null) with {@code newText}. {@code oldText} must occur exactly once in the <em>whole
     * document</em> (also when the buffer is narrowed) unless {@code replaceAll}. Returns null on success,
     * else a human-readable error.
     */
    @Nullable
    String editBuffer(@Nullable String path, String oldText, String newText, boolean replaceAll);

    /**
     * Replaces the entire text of the open buffer for {@code path} (or the active buffer when null) with
     * {@code newText}. Only ever called for an explicit {@code replace_whole_buffer} request; refused when
     * the buffer changed since the caller last read it. Returns null on success, else a human-readable error.
     */
    @Nullable
    String replaceBuffer(@Nullable String path, String newText);

    /**
     * Saves the open buffer for {@code path} (or the active buffer when null) to its file and waits for the
     * write. Returns null only when the bytes are on disk; else a human-readable error (untitled buffers
     * can't be saved over MCP).
     */
    @Nullable
    String saveBuffer(@Nullable String path);

    /** The active buffer's caret + selection, or null when there is no active buffer. */
    @Nullable
    Selection getSelection();

    /** The LSP symbol outline for {@code path} (or the active buffer's file when null); empty when the
     *  file isn't served by a ready language server. */
    List<Symbol> documentSymbols(@Nullable String path);

    /** The Git status for this window's context (branch, ahead/behind, changed files). */
    GitState gitStatus();

    /** Every open tab in the strip, including non-editor tabs (Welcome, image, hex, diff), with which one
     *  is active. */
    List<TabInfo> listTabs();

    /** Scans the window's project tree (plus open buffers' unsaved text) for the configured TODO/highlight
     *  patterns; empty when TODO highlighting is off. */
    List<TodoItem> todoScan();
}
