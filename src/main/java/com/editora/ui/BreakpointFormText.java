package com.editora.ui;

import java.nio.file.Path;

import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * Pure text of the Edit Breakpoint form: its title names the breakpoint's file and line (the card covers the editor,
 * often that very line), and its hit-count hint offers only what the file's debugger takes (review 2 C106).
 */
final class BreakpointFormText {

    private BreakpointFormText() {}

    /** "Edit Breakpoint — Main.java:27" ({@code line0} is 0-based); the plain title without a file. */
    static String title(@Nullable Path file, int line0) {
        if (file == null || file.getFileName() == null) {
            return tr("dialog.debug.breakpointTitle");
        }
        return tr("dialog.debug.breakpointTitleAt", file.getFileName().toString(), line0 + 1);
    }

    /** The Add Logpoint form's title: the file and 1-based line, or a bare title for an unsaved buffer. */
    static String logpointTitle(@Nullable Path file, int line0) {
        if (file == null || file.getFileName() == null) {
            return tr("dialog.debug.logpointTitle");
        }
        return tr("dialog.debug.logpointTitleAt", file.getFileName().toString(), line0 + 1);
    }

    /**
     * The hit-count field's hint: debugpy also takes {@code >= 3} and {@code % 2}; java-debug and the others only a
     * count. The hint used to name Python in a Java file's form.
     */
    static String hitConditionPrompt(@Nullable String language) {
        return "python".equals(language)
                ? tr("dialog.debug.hitConditionPromptPython")
                : tr("dialog.debug.hitConditionPromptNumber");
    }

    /** The breakpoint's line as the form shows it: one line, trimmed, cut at {@code max} characters. */
    static String lineText(@Nullable String text, int max) {
        return DebugPanelText.oneLine(text == null ? "" : text, max);
    }
}
