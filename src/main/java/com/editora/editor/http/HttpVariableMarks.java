package com.editora.editor.http;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.editora.editor.EditorBuffer;
import com.editora.editor.MarkdownLintOverlay;
import com.editora.markdown.MarkdownLint;
import org.jspecify.annotations.Nullable;

/**
 * The squiggles under the {@code {{variables}}} of a {@code .http} buffer that cannot resolve. The analysis is
 * the controller's (an asynchronous validator, like Markdown lint's, fed from {@code http.HttpVariableIndex}); this
 * keeps the buffer side out of {@link EditorBuffer}: when it is on, and handing the result to the squiggle
 * overlay. It reuses the Markdown-lint overlay and hover: a buffer is one language, so the two never share it.
 * Runs on the settled-edit pause, never per keystroke.
 */
public final class HttpVariableMarks {

    private final Supplier<MarkdownLintOverlay> overlay;
    private final Supplier<String> text;
    private final BooleanSupplier eligible;
    /** Whether Markdown lint, which shares the overlay, still needs it when the marks turn off. */
    private final BooleanSupplier sharedUse;

    private @Nullable BiConsumer<String, Consumer<List<MarkdownLint.Diagnostic>>> validator;
    private boolean enabled;
    /** Bumped on every request and on/off switch: a result for an older one is dropped. */
    private int generation;
    /** The marks last handed to the overlay (null when none are), so switching off can take back only its own. */
    private @Nullable List<MarkdownLint.Diagnostic> shown;

    /**
     * @param overlay  the buffer's (lazily attached) squiggle overlay
     * @param text     the document text, read when a check starts
     * @param eligible whether the buffer is an {@code .http} file with the HTTP Client on (and not a huge file)
     * @param sharedUse whether Markdown lint is using the same overlay (it is left on for it when the marks go off)
     */
    public HttpVariableMarks(
            Supplier<MarkdownLintOverlay> overlay,
            Supplier<String> text,
            BooleanSupplier eligible,
            BooleanSupplier sharedUse) {
        this.overlay = overlay;
        this.text = text;
        this.eligible = eligible;
        this.sharedUse = sharedUse;
    }

    /** Injects the analysis ({@code accept(text, diagnostics -> …)}); null turns the marks off. */
    public void setValidator(@Nullable BiConsumer<String, Consumer<List<MarkdownLint.Diagnostic>>> validator) {
        this.validator = validator;
        update();
    }

    /** Re-evaluates whether the marks are on (language, feature switch, validator) and checks again if so. */
    public void update() {
        boolean on = validator != null && eligible.getAsBoolean();
        if (on != enabled) {
            enabled = on;
            generation++;
            if (!on) {
                MarkdownLintOverlay o = overlay.get();
                if (shown != null && o.diagnostics() == shown) {
                    // A .http file saved as Markdown: its {{variable}} marks would stay until Markdown lint's
                    // first result replaced them, and with lint off they would stay for good.
                    o.setDiagnostics(List.of());
                }
                shown = null;
                o.setActive(sharedUse.getAsBoolean());
            }
        }
        if (on) {
            // Re-asserted on every update, not only when the marks switch on: the overlay is shared, and
            // something else (a Markdown-lint pass over every buffer) may have switched it off meanwhile.
            overlay.get().setActive(true);
        }
        schedule();
    }

    public boolean enabled() {
        return enabled;
    }

    /** Checks the document again (an edit settled, or the environment / env files changed). */
    public void schedule() {
        if (!enabled || validator == null) {
            return;
        }
        int asked = ++generation;
        validator.accept(text.get(), diags -> {
            if (enabled && asked == generation) {
                shown = diags;
                overlay.get().setDiagnostics(diags);
            }
        });
    }

    /** The marks at 0-based {@code paragraph} and {@code column} (empty while off). */
    List<MarkdownLint.Diagnostic> at(int paragraph, int column) {
        return enabled ? overlay.get().at(paragraph, column) : List.of();
    }

    /** The marks currently shown (empty while off). */
    public List<MarkdownLint.Diagnostic> diagnostics() {
        return enabled ? overlay.get().diagnostics() : List.of();
    }
}
