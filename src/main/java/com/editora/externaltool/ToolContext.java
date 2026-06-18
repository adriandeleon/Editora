package com.editora.externaltool;

/**
 * The values an {@link ExternalTool}'s macros and stdin can draw from, captured from the active buffer at
 * run time. Pure (no toolkit) so {@link ToolMacros} / {@link ToolInvocation} stay unit-testable; the UI
 * layer builds it. File fields are empty for an unsaved (path-less) buffer; line/column are 1-based.
 */
public record ToolContext(
        String filePath,
        String fileDir,
        String fileName,
        String fileNameWithoutExtension,
        String selectedText,
        int lineNumber,
        int columnNumber,
        String projectFileDir,
        String bufferText) {}
