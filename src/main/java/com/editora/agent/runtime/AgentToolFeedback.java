package com.editora.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Recovery advice is application-owned, separate from the untrusted error message. */
final class AgentToolFeedback {
    private AgentToolFeedback() {}

    static String failure(String tool, String message) {
        tool = java.util.Objects.toString(tool, "");
        message = java.util.Objects.toString(message, "Tool failed");
        String text = message.toLowerCase(java.util.Locale.ROOT),
                code = "TOOL_FAILED",
                next = "Inspect the observation and choose a different bounded action.";
        if (text.contains("stale") || text.contains("revision") || text.contains("changed during")) {
            code = "STALE_DOCUMENT";
            next =
                    "Reread every affected file. Preserve newer user changes and prepare a new edit from current revisions.";
        } else if (text.contains("unsaved")) {
            code = "UNSAVED_DOCUMENTS";
            next =
                    "Call save_files with {} for agent edits. Ask the user to save unrelated dirty buffers before disk validation.";
        } else if (tool.equals("apply_edits") && text.contains("old_text")) {
            code = "EDIT_MATCH_MISMATCH";
            next =
                    "Reread the named file. Copy exact current text including whitespace, with unique surrounding context and the current revision; do not repeat the rejected replacement.";
        } else if (tool.startsWith("semantic_")
                && (text.contains("position") || text.contains("outside line") || text.contains("range"))) {
            code = "SEMANTIC_POSITION";
            next =
                    "Use semantic_query operation=symbols to obtain exact zero-based UTF-16 selection ranges, then retry with the current revision.";
        } else if (tool.startsWith("semantic_")
                && (text.contains("unavailable") || text.contains("initializ") || text.contains("synchroniz"))) {
            code = "SEMANTICS_NOT_READY";
            next =
                    "Inspect semantic_capabilities for this file and retry only when the required operation is advertised. Read/search can continue while the server initializes.";
        } else if (tool.equals("run_validation") && text.contains("test selector")) {
            code = "INVALID_VALIDATION_SCOPE";
            next =
                    "Use type=TARGETED_TEST with a test selector, or type=TEST without the test key. The module is a directory, not a build file.";
        } else if (tool.equals("update_plan")) {
            code = "INVALID_PLAN";
            next =
                    "Use steps containing text and status. Valid statuses: pending, in_progress, completed, cancelled. Keep the plan concise.";
        } else if (text.contains("argument") || text.contains("schema") || text.contains("enum")) {
            code = "INVALID_ARGUMENTS";
            next =
                    "Use the advertised input schema exactly; omit unsupported keys. File-read line numbers start at 1; LSP positions start at 0.";
        } else if (tool.equals("run_validation")) {
            code = "VALIDATION_UNAVAILABLE";
            next =
                    "Inspect validation_profiles and saved buffer state. ISOLATED never silently falls back; HOST_REDUCED requires a separate explicit approval.";
        }
        return new ObjectMapper()
                .createObjectNode()
                .put("code", code)
                .put("message", AgentContext.bounded(message, 1000))
                .put("nextAction", next)
                .toString();
    }
}
