package com.editora.editor;

import com.editora.markdown.MarkdownLint;
import org.jspecify.annotations.Nullable;

/**
 * Live linting of one buffer: Mermaid diagrams through the injected maid validator and Markdown through the
 * injected Markdown linter, each run when typing settles and painted as squiggles, an overview stripe and minimap
 * ticks.
 *
 * <p>Moved out of {@code EditorBuffer} for size.
 */
final class BufferLint {

    private final EditorBuffer b;

    BufferLint(EditorBuffer b) {
        this.b = b;
    }

    /** Async maid validator (text, callback) injected by the controller; null = no linting. */
    private java.util.function.@Nullable BiConsumer<
                    String, java.util.function.Consumer<java.util.List<com.editora.mermaid.MaidOutput.Diagnostic>>>
            mermaidValidator;
    /** Async Markdown linter (text, callback) injected by the controller; null = no linting. */
    private java.util.function.@Nullable BiConsumer<
                    String, java.util.function.Consumer<java.util.List<MarkdownLint.Diagnostic>>>
            markdownLintValidator;
    /** What the controller last asked for Markdown lint (the setting), re-applied when the language changes. */
    boolean markdownLintWanted;
    /** Injects the async maid validator: {@code accept(text, diagnostics->…)}; null disables linting. */
    void setMermaidValidator(
            java.util.function.BiConsumer<
                            String,
                            java.util.function.Consumer<java.util.List<com.editora.mermaid.MaidOutput.Diagnostic>>>
                    validator) {
        this.mermaidValidator = validator;
    }
    /** Turns live linting on/off for this buffer (controller gates on mermaid enabled + maid detected). */
    void setMermaidLintEnabled(boolean on) {
        b.mermaidLintEnabled = on && b.isDiagram();
        if (b.mermaidLintEnabled || b.lintOverlay != null) {
            b.lintOverlay().setActive(b.mermaidLintEnabled);
        }
        if (b.mermaidLintEnabled) {
            scheduleMermaidLint();
        }
    }

    void scheduleMermaidLint() {
        if (!b.mermaidLintEnabled || !b.isDiagram() || b.hugeFile || mermaidValidator == null) {
            return;
        }
        mermaidValidator.accept(b.documentTextSnapshot(), b.lintOverlay()::setDiagnostics);
    }
    /** Injects the async Markdown linter: {@code accept(text, diagnostics->…)}; null disables linting. */
    void setMarkdownLintValidator(
            java.util.function.BiConsumer<String, java.util.function.Consumer<java.util.List<MarkdownLint.Diagnostic>>>
                    validator) {
        this.markdownLintValidator = validator;
    }
    /** Turns Markdown linting on/off for this buffer (controller gates on the setting; Markdown buffers only). */
    void setMarkdownLintEnabled(boolean on) {
        this.markdownLintWanted = on;
        b.markdownLintEnabled = on && b.isMarkdown() && !b.hugeFile;
        if (b.markdownLintEnabled || b.mdLintOverlay != null) {
            // The overlay is shared with a .http buffer's {{variable}} marks: keep it on while they are.
            b.mdLintOverlay().setActive(b.markdownLintEnabled || b.httpVariableMarks.enabled());
        }
        b.minimap.setLintEnabled(b.markdownLintEnabled);
        b.updateMarkdownLintStripe();
        if (b.markdownLintEnabled) {
            scheduleMarkdownLint();
        } else {
            if (b.mdLintStripe != null) {
                b.mdLintStripe.setDiagnostics(java.util.List.of());
            }
            b.minimap.setLintMarks(java.util.List.of());
        }
    }

    void scheduleMarkdownLint() {
        if (!b.markdownLintEnabled || !b.isMarkdown() || b.hugeFile || markdownLintValidator == null) {
            return;
        }
        markdownLintValidator.accept(b.documentTextSnapshot(), this::applyMarkdownLintDiagnostics);
    }
    /** Pushes fresh lint diagnostics to the squiggle overlay + the scrollbar stripe + the minimap ticks. */
    private void applyMarkdownLintDiagnostics(java.util.List<MarkdownLint.Diagnostic> diags) {
        if (!b.markdownLintEnabled) {
            return; // a result that arrived after linting was turned off (the overlay may not even exist)
        }
        b.mdLintOverlay().setDiagnostics(diags);
        b.mdLintStripe().setDiagnostics(diags);
        b.minimap.setLintMarks(diags);
    }
    /** The diagnostics currently underlined in this buffer (empty while linting is off). */
    java.util.List<MarkdownLint.Diagnostic> markdownLintDiagnostics() {
        return b.markdownLintEnabled && b.mdLintOverlay != null ? b.mdLintOverlay.diagnostics() : java.util.List.of();
    }
}
