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
 * {@link HttpEnvFiles#envDir} finds), the {@code .env} beside it, and the document text. The check runs on its
 * own worker thread after an edit settles (the buffer asks), and again when the environment choice or the env
 * files change (the coordinator tells {@link #refresh}); the result is applied on the FX thread.
 */
final class HttpVariableHints {

    /** Where a buffer's environment comes from (the HTTP Client coordinator). */
    interface Source {
        /** The environment selected for the buffer's file ({@code ""}: none). */
        String environment(EditorBuffer buffer);

        /** The folder whose env files apply to the buffer, or null. */
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
        buffer.setHttpVariableContext(() -> contexts.getOrDefault(buffer, HttpVariableIndex.Context.EMPTY));
        buffer.setHttpVariableLint((text, done) -> check(buffer, text, done));
    }

    /** Checks {@code buffer} again if its environment, env files or {@code .env} changed since the last check. */
    void refresh(EditorBuffer buffer) {
        if (buffer != null && buffer.isHttpFile() && !fingerprint(buffer).equals(fingerprints.get(buffer))) {
            buffer.refreshHttpVariableLint();
        }
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
        Path envDir = source.envDir(buffer);
        Path dir = buffer.getPath() == null
                ? null
                : buffer.getPath().toAbsolutePath().getParent();
        fingerprints.put(buffer, fingerprint(buffer));
        worker.execute(() -> {
            HttpVariableIndex.Context ctx = context(env, envDir, dir);
            List<MarkdownLint.Diagnostic> marks =
                    diagnostics(HttpVariableIndex.unresolved(text, ctx, ZonedDateTime.now()));
            Platform.runLater(() -> {
                contexts.put(buffer, ctx);
                done.accept(marks);
            });
        });
    }

    /** The context for environment {@code env} of the env files in {@code envDir}, with {@code $dotenv} in {@code dir}. */
    static HttpVariableIndex.Context context(String env, Path envDir, Path dir) {
        if (envDir == null || env == null || env.isEmpty()) {
            return new HttpVariableIndex.Context(Map.of(), dir);
        }
        return new HttpVariableIndex.Context(
                HttpEnv.merged(
                        read(envDir.resolve(HttpEnv.PUBLIC_FILE)), read(envDir.resolve(HttpEnv.PRIVATE_FILE)), env),
                dir);
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

    /** The environment name and the modification times of the files the check reads. */
    private String fingerprint(EditorBuffer buffer) {
        Path envDir = source.envDir(buffer);
        Path dir = buffer.getPath() == null
                ? null
                : buffer.getPath().toAbsolutePath().getParent();
        return source.environment(buffer)
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

    private static String read(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.readString(file) : null;
        } catch (IOException e) {
            return null;
        }
    }
}
