package com.editora.editor;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

import com.editora.http.HttpVariableIndex;
import com.editora.markdown.MarkdownLint;

/**
 * What a {@code .http} buffer asks of the window, injected once ({@link EditorBuffer#setHttpHooks}): run the request
 * at a line (its gutter ▶ and the editor menu), run every request, cancel the running one (its ■), the ▶ tooltip,
 * the unresolved-{@code {{variable}}} check and what {@code {{} completion offers. Any of them may be null (none).
 *
 * @param run             runs the request whose section holds the given 0-based line
 * @param runAll          runs every request of the file
 * @param cancel          cancels the buffer's running request
 * @param runTooltip      the ▶ hover text, built when hovered so it names the current chord (null = none)
 * @param variableLint    checks the text, then hands the marks to the callback (null = no marks)
 * @param variableContext the environment and folder {@code {{} completion resolves against (null = none)
 */
public record HttpHooks(
        IntConsumer run,
        Runnable runAll,
        Runnable cancel,
        Supplier<String> runTooltip,
        BiConsumer<String, Consumer<List<MarkdownLint.Diagnostic>>> variableLint,
        Supplier<HttpVariableIndex.Context> variableContext) {

    /** No hooks: nothing runs, no tooltip, no marks, no completion context. */
    public static final HttpHooks NONE = new HttpHooks(null, null, null, null, null, null);

    public HttpHooks {
        run = run == null ? line -> {} : run;
        runAll = runAll == null ? () -> {} : runAll;
        cancel = cancel == null ? () -> {} : cancel;
        runTooltip = runTooltip == null ? () -> null : runTooltip;
    }

    /** These hooks with the {@code {{variable}}} check and completion context replaced. */
    public HttpHooks withVariables(
            BiConsumer<String, Consumer<List<MarkdownLint.Diagnostic>>> lint,
            Supplier<HttpVariableIndex.Context> context) {
        return new HttpHooks(run, runAll, cancel, runTooltip, lint, context);
    }
}
