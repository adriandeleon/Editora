package com.editora.editor;

import com.editora.editor.EditorBuffer.MarkdownViewMode;
import com.editora.editor.http.HttpHooks;
import com.editora.editor.http.HttpLineResult;
import com.editora.editor.http.HttpResultMarks;
import com.editora.editor.http.HttpVariableFixes;
import com.editora.editor.http.HttpVariableMarks;
import com.editora.editor.lsp.CodeAction;
import org.fxmisc.richtext.CodeArea;
import org.jspecify.annotations.Nullable;

/**
 * The HTTP Client side of a {@code .http} buffer: turning the feature on, the injected hooks, the running and
 * finished requests' gutter glyphs and inline results, the {@code {{variable}}} checks and their fixes, and showing
 * the response panel.
 *
 * <p>Moved out of {@code EditorBuffer} for size.
 */
final class BufferHttp {

    private final EditorBuffer b;

    BufferHttp(EditorBuffer b) {
        this.b = b;
    }

    /** Enables/disables the HTTP Client Run glyphs for a {@code .http} buffer (gated by the feature, Simple UI
     *  mode and a local file). */
    void setHttpEnabled(boolean enabled) {
        if (enabled != b.httpFeatureEnabled) {
            b.httpFeatureEnabled = enabled;
            b.runGutter.recomputeRun();
        }
        b.httpVariableMarks.update();
    }
    /**
     * Injects what this {@code .http} buffer asks of the window ({@link HttpHooks}): running, Run All, cancel, the ▶
     * tooltip, the {@code {{variable}}} check and the {@code {{} completion context. Null clears them.
     */
    void setHttpHooks(HttpHooks hooks) {
        b.httpGutter.setHooks(hooks);
        HttpHooks set = b.httpGutter.hooks();
        b.httpVariableMarks.setValidator(set.checksVariables() ? set.variableLint() : null);
        b.completionActions.setHttpVariables(set.variableContext());
    }
    /**
     * A language's own check on the same squiggle overlay: HTML markup while no server serves the page (HTML review
     * D5), JSON Lines' one-value-per-line check.
     */
    void addMarkupCheck(HttpVariableMarks.MarkupCheck check) {
        b.httpVariableMarks.addMarkupCheck(check);
    }
    /** Offers the variable fixes at the caret in the code-action popup (Code Actions); false when there are none. */
    boolean showHttpVariableFixes() {
        CodeArea view = b.getFocusedArea();
        java.util.List<CodeAction> fixes = b.httpVariableFixesAt(view, view.getCaretPosition());
        if (!fixes.isEmpty()) {
            b.showCodeActions(fixes, HttpVariableFixes::apply);
        }
        return !fixes.isEmpty();
    }
    /**
     * Shows request {@code index} (file order; -1 = none) as running: its ▶ becomes a ■ that cancels the run.
     * Cleared by the caller when the run ends, and here when the requests move (the mark would be stale).
     */
    void setHttpRunning(int index) {
        b.httpGutter.setRunning(index, this::paragraphTextOrNull, b::isFoldedLine, b::refreshGutterLine);
    }
    /**
     * As {@link #setHttpRunning(int)} for request {@code index} of the text a run started with, whose method/URL
     * line read {@code requestLine}: when the requests moved since, the one with that line is marked.
     */
    void setHttpRunning(int index, String requestLine) {
        setHttpRunning(b.httpGutter.follow(index, requestLine, this::paragraphTextOrNull));
    }
    /** Each request's last result after its line, and its ▶ coloured by it ({@link HttpResultMarks}); empty: none. */
    void setHttpResults(java.util.List<HttpLineResult> results) {
        if (!b.httpResults.isEmpty() || (results != null && !results.isEmpty())) {
            repaintHttpResults(b.httpResults.set(results, this::paragraphTextOrNull));
        }
    }

    void repaintHttpResults(java.util.@Nullable Set<Integer> lines) {
        if (lines != null) {
            b.inlayDecor.ensureInlayFactory();
            b.inlayDecor.refreshInlayAreas();
            lines.forEach(b::refreshGutterLine);
        }
    }

    @Nullable
    String paragraphTextOrNull(int line) {
        return line >= 0 && line < b.area.getParagraphs().size()
                ? b.area.getParagraph(line).getText()
                : null;
    }
    /**
     * Shows the HTTP response preview beside the source, used when a request is run from the gutter ▶ while
     * the buffer is still in EDITOR mode. A buffer already in SPLIT or PREVIEW is left alone, so the per-file
     * mode the user last chose (persisted in {@code WorkspaceState.markdownViewModes}) wins over this nudge.
     */
    void revealHttpPreview() {
        if (b.hasHttpPreview() && b.markdownViewMode == MarkdownViewMode.EDITOR) {
            b.setMarkdownViewMode(MarkdownViewMode.SPLIT);
        }
    }
}
