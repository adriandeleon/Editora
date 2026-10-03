package com.editora.editor;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import javafx.application.Platform;

import com.editora.markdown.MarkdownLint;

/**
 * Runs the pure {@link MarkdownLint} off the FX thread and posts results back via
 * {@link Platform#runLater}, mirroring {@code MermaidService.validate}. The scan is cheap, but a
 * single daemon executor + a generation guard keep the FX thread clean and drop stale results while
 * the user is typing in a large document.
 *
 * <p>The guard is <b>per requester</b>: a request only supersedes an earlier one from the same requester
 * (the same buffer, the same tool window). One counter for everyone meant that linting several buffers in
 * one tick — toggling a rule with a few Markdown tabs open — delivered only the last buffer's result and
 * left the others showing stale squiggles.
 */
public final class MarkdownLintService {

    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "markdown-lint");
        t.setDaemon(true);
        return t;
    });

    private final AtomicLong gen = new AtomicLong();
    /** The latest request number of each requester; weak keys, so a closed buffer is not kept alive. */
    private final Map<Object, Long> latest = Collections.synchronizedMap(new WeakHashMap<>());

    /** Lints {@code source} off-thread; delivers diagnostics on the FX thread (latest request wins). */
    public void validate(String source, Consumer<List<MarkdownLint.Diagnostic>> onResult) {
        validate(source, java.util.Set.of(), onResult);
    }

    /** As {@link #validate(String, Consumer)} but with a set of rule codes to suppress. */
    public void validate(
            String source, java.util.Set<String> disabled, Consumer<List<MarkdownLint.Diagnostic>> onResult) {
        validate(this, source, disabled, onResult);
    }

    /**
     * Lints {@code source} for {@code requester}; the result is delivered unless the same requester has
     * asked again since. Requests from different requesters never cancel one another.
     */
    public void validate(
            Object requester,
            String source,
            java.util.Set<String> disabled,
            Consumer<List<MarkdownLint.Diagnostic>> onResult) {
        Object key = requester == null ? this : requester;
        long mine = gen.incrementAndGet();
        latest.put(key, mine);
        java.util.Set<String> off = disabled == null ? java.util.Set.of() : java.util.Set.copyOf(disabled);
        exec.submit(() -> {
            if (!isLatest(key, mine)) {
                return; // superseded while queued: skip the scan as well as the delivery
            }
            List<MarkdownLint.Diagnostic> diags = MarkdownLint.lint(source, off);
            if (isLatest(key, mine)) {
                Platform.runLater(() -> {
                    if (isLatest(key, mine)) {
                        onResult.accept(diags);
                    }
                });
            }
        });
    }

    private boolean isLatest(Object key, long request) {
        Long current = latest.get(key);
        return current != null && current == request;
    }

    public void shutdown() {
        exec.shutdownNow();
    }
}
