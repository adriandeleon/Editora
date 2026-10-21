package com.editora.editor.http;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

import com.editora.editor.CodeAction;
import com.editora.editor.EditorBuffer;
import com.editora.http.HttpVariableIndex;
import com.editora.markdown.MarkdownLint;
import org.jspecify.annotations.Nullable;

/**
 * What a {@code .http} buffer asks of the window, injected once ({@link EditorBuffer#setHttpHooks}): run the request
 * at a line (its gutter ▶ and the editor menu), run every request, cancel the running one (its ■), the ▶ tooltip,
 * the unresolved-{@code {{variable}}} check and what {@code {{} completion offers. A null hook is replaced by one that
 * does nothing, so every accessor is non-null; {@link #checksVariables} tells whether there is a real check.
 *
 * @param run             runs the request whose section holds the given 0-based line
 * @param runAll          runs every request of the file
 * @param cancel          cancels the buffer's running request
 * @param runTooltip      the ▶ hover text, built when hovered so it names the current chord (null = none)
 * @param variableLint    checks the text, then hands the marks to the callback (null = no marks: {@link #NO_LINT})
 * @param variableContext the environment and folder {@code {{} completion resolves against (null = none: it supplies
 *     null)
 * @param variableFixes   the quick fixes for the {@code {{variable}}} marks at one spot ("Use environment dev"), each
 *     action's token a {@link Runnable} that applies it (null = none)
 */
public record HttpHooks(
        IntConsumer run,
        Runnable runAll,
        Runnable cancel,
        Supplier<@Nullable String> runTooltip,
        BiConsumer<String, Consumer<List<MarkdownLint.Diagnostic>>> variableLint,
        @Nullable Supplier<HttpVariableIndex.Context> variableContext,
        Function<List<MarkdownLint.Diagnostic>, List<CodeAction>> variableFixes) {

    /** The check of a buffer that has none: no marks are shown (see {@link #checksVariables}). */
    public static final BiConsumer<String, Consumer<List<MarkdownLint.Diagnostic>>> NO_LINT =
            (text, done) -> done.accept(List.of());

    /** No hooks: nothing runs, no tooltip, no marks, no completion context. */
    public static final HttpHooks NONE =
            new HttpHooks(line -> {}, () -> {}, () -> {}, () -> null, NO_LINT, null, marks -> List.of());

    public HttpHooks {
        run = run == null ? line -> {} : run;
        runAll = runAll == null ? () -> {} : runAll;
        cancel = cancel == null ? () -> {} : cancel;
        runTooltip = runTooltip == null ? () -> null : runTooltip;
        variableLint = variableLint == null ? NO_LINT : variableLint;
        variableContext = variableContext == null ? () -> null : variableContext;
        variableFixes = variableFixes == null ? marks -> List.of() : variableFixes;
    }

    /** Hooks without quick fixes for the {@code {{variable}}} marks. */
    public HttpHooks(
            IntConsumer run,
            Runnable runAll,
            Runnable cancel,
            Supplier<@Nullable String> runTooltip,
            BiConsumer<String, Consumer<List<MarkdownLint.Diagnostic>>> variableLint,
            @Nullable Supplier<HttpVariableIndex.Context> variableContext) {
        this(run, runAll, cancel, runTooltip, variableLint, variableContext, marks -> List.of());
    }

    /** Whether the {@code {{variable}}} check is a real one (the marks are shown), not {@link #NO_LINT}. */
    public boolean checksVariables() {
        return variableLint != NO_LINT;
    }

    /** These hooks with the {@code {{variable}}} check and completion context replaced. */
    public HttpHooks withVariables(
            BiConsumer<String, Consumer<List<MarkdownLint.Diagnostic>>> lint,
            Supplier<HttpVariableIndex.Context> context) {
        return new HttpHooks(run, runAll, cancel, runTooltip, lint, context, variableFixes);
    }

    /** These hooks with the quick fixes for the {@code {{variable}}} marks replaced. */
    public HttpHooks withVariableFixes(Function<List<MarkdownLint.Diagnostic>, List<CodeAction>> fixes) {
        return new HttpHooks(run, runAll, cancel, runTooltip, variableLint, variableContext, fixes);
    }
}
