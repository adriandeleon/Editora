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
}
