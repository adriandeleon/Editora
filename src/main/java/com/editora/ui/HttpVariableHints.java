package com.editora.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javafx.application.Platform;

import com.editora.editor.EditorBuffer;
import com.editora.http.HttpEnv;
import com.editora.http.HttpVariableIndex;
import com.editora.markdown.MarkdownLint;

/**
 * Feeds a {@code .http} buffer's unresolved-variable squiggles and its {@code {{} completion from
 * {@link HttpVariableIndex}: the selected environment of the file (both env files of the folder
 * {@link HttpEnvironments} finds), the {@code .env} beside it, and the document text. The check runs on its
 * own worker thread after an edit settles (the buffer asks), and again when the environment choice or the env
 * files change (the coordinator tells {@link #refresh}); the result is applied on the FX thread.
 */
final class HttpVariableHints {

    /** Where a buffer's environment comes from (the HTTP Client coordinator). */
    interface Source {
        /** The environment selected for the buffer's file ({@code ""}: none). */
        String environment(EditorBuffer buffer);

        /** The folder whose env files apply to the buffer as last read, or null; FX thread, no file access. */
        Path envDir(EditorBuffer buffer);
    }

    private final Source source;

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "http-variable-hints");
        t.setDaemon(true);
        return t;
    });

    /** Each buffer's last context (FX thread only) — what completion offers until the next check. */
    private final Map<EditorBuffer, HttpVariableIndex.Context> contexts = new WeakHashMap<>();

    /** What each buffer was last checked against, so a refresh that changes nothing does not check again. */
    private final Map<EditorBuffer, String> fingerprints = new WeakHashMap<>();

    HttpVariableHints(Source source) {
        this.source = source;
    }

    /** Gives {@code buffer} its check and its completion context (the buffer itself decides when it is on). */
    void wire(EditorBuffer buffer) {
        buffer.setHttpHooks(buffer.httpHooks()
                .withVariables(
                        (text, done) -> check(buffer, text, done),
                        () -> contexts.getOrDefault(buffer, HttpVariableIndex.Context.EMPTY)));
    }

    /**
     * Checks {@code buffer} again if its environment, env files or {@code .env} changed since the last check. The
     * files are looked at on the worker (after any check already queued), the answer acted on on the FX thread.
     */
    void refresh(EditorBuffer buffer) {
        if (buffer == null || !buffer.isHttpFile()) {
            return;
        }
        String env = source.environment(buffer);
        Path envDir = source.envDir(buffer);
        Path dir = folderOf(buffer);
        worker.execute(() -> {
            String now = fingerprint(env, envDir, dir);
            Platform.runLater(() -> {
                if (!now.equals(fingerprints.get(buffer))) {
                    buffer.refreshHttpVariableLint();
                }
            });
        });
    }

    void forget(EditorBuffer buffer) {
        contexts.remove(buffer);
        fingerprints.remove(buffer);
    }

    void shutdown() {
        worker.shutdownNow();
    }

    private void check(
            EditorBuffer buffer, String text, java.util.function.Consumer<List<MarkdownLint.Diagnostic>> done) {
        String env = source.environment(buffer);
        Path envDir = source.envDir(buffer); // as last read: no file access here
        Path dir = folderOf(buffer);
        worker.execute(() -> {
            String checkedAgainst = fingerprint(env, envDir, dir); // what this check reads, before it reads it
            HttpVariableIndex.Context ctx = HttpVariableIndex.Context.EMPTY;
            List<MarkdownLint.Diagnostic> marks;
            try {
                ctx = context(env, envDir, dir, text);
                marks = diagnostics(HttpVariableIndex.unresolved(text, ctx, ZonedDateTime.now()));
            } catch (Throwable unexpected) { // also an Error (a StackOverflowError on a pathological file)
                // Never leave the previous marks standing as if they were current: clear them instead (the
                // completion keeps the context, if that much was read).
                java.util.logging.Logger.getLogger(HttpVariableHints.class.getName())
                        .log(java.util.logging.Level.WARNING, "{{variable}} check failed", unexpected);
                marks = List.of();
            }
            HttpVariableIndex.Context checked = ctx;
            List<MarkdownLint.Diagnostic> result = marks;
            Platform.runLater(() -> {
                fingerprints.put(buffer, checkedAgainst);
                contexts.put(buffer, checked);
                done.accept(result);
            });
        });
    }

    /**
     * The context for environment {@code env} of the env files in {@code envDir}, with {@code $dotenv} in {@code dir}
     * and the names the script files {@code text} runs set (resolved in {@code dir}). Reads files: the worker's.
     */
    static HttpVariableIndex.Context context(String env, Path envDir, Path dir, String text) {
        HttpEnvironments.EnvFiles files = HttpEnvironments.read(envDir);
        boolean selected = env != null && !env.isEmpty();
        return new HttpVariableIndex.Context(
                selected ? files.variables(env) : Map.of(),
                dir,
                HttpVariableIndex.Context.readDotenvKeys(dir),
                selected ? Map.of() : definedIn(files),
                files.problems(),
                HttpVariableIndex.scriptFileNames(text, dir));
    }

    /** Which environments of {@code files} define each variable name (for "no environment selected" marks). */
    static Map<String, List<String>> definedIn(HttpEnvironments.EnvFiles files) {
        Map<String, List<String>> out = new java.util.HashMap<>();
        for (String name : files.names()) {
            for (String key : files.variables(name).keySet()) {
                out.computeIfAbsent(key, k -> new ArrayList<>()).add(name);
            }
        }
        return out;
    }

    /** One amber squiggle per problem (lint diagnostics are 1-based); the tooltip reads {@code {{name}}: reason}. */
    static List<MarkdownLint.Diagnostic> diagnostics(List<HttpVariableIndex.Problem> problems) {
        List<MarkdownLint.Diagnostic> out = new ArrayList<>(problems.size());
        for (HttpVariableIndex.Problem p : problems) {
            out.add(new MarkdownLint.Diagnostic(
                    p.line() + 1, p.column() + 1, p.length(), "warning", p.reference(), p.reason()));
        }
        return out;
    }

    private static Path folderOf(EditorBuffer buffer) {
        return buffer.getPath() == null
                ? null
                : buffer.getPath().toAbsolutePath().getParent();
    }

    /** The environment name and the modification times of the files a check reads. Stats files: on the worker. */
    private static String fingerprint(String env, Path envDir, Path dir) {
        return env
                + "|" + envDir
                + "|" + stamp(envDir == null ? null : envDir.resolve(HttpEnv.PUBLIC_FILE))
                + "|" + stamp(envDir == null ? null : envDir.resolve(HttpEnv.PRIVATE_FILE))
                + "|" + stamp(dir == null ? null : dir.resolve(".env"));
    }

    private static String stamp(Path file) {
        try {
            return file != null && Files.isRegularFile(file)
                    ? Files.getLastModifiedTime(file) + "/" + Files.size(file)
                    : "-";
        } catch (IOException e) {
            return "?";
        }
    }
}
