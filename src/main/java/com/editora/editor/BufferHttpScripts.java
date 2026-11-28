package com.editora.editor;

import org.jspecify.annotations.Nullable;

/**
 * Which lines of a {@code .http} document are its scripts ({@link com.editora.http.HttpScriptLines}), worked out
 * off the FX thread once per document version, and the callers waiting for that answer.
 *
 * <p>Moved out of {@code EditorBuffer} for size. The last answer stays on the buffer, where tests read it.
 */
final class BufferHttpScripts {

    private final EditorBuffer b;

    BufferHttpScripts(EditorBuffer b) {
        this.b = b;
    }

    /**
     * This {@code .http} document's scripts ({@link com.editora.http.HttpScriptLines#mapOf}) for its current text, or
     * null while they are being worked out: parsed off the FX thread once per document version (PERF-01, Pauta 6.2.0).
     * A caller treats null as "may be a script" (a breakpoint is allowed, a Debug Request row offered).
     */
    com.editora.http.HttpScriptLines.@Nullable Map httpScriptMap() {
        long version = b.docVersion();
        com.editora.http.HttpScriptLines.Map kept = b.httpScripts;
        if (kept != null && httpScriptsVersion == version) {
            return kept;
        }
        if (httpScriptsAsked != version) {
            httpScriptsAsked = version;
            String text = b.documentTextSnapshot();
            Thread.ofVirtual().name("http-script-lines").start(() -> {
                com.editora.perf.BlockingSites.enter("EditorBuffer.httpScriptMap");
                com.editora.http.HttpScriptLines.Map map = com.editora.http.HttpScriptLines.mapOf(text);
                javafx.application.Platform.runLater(() -> {
                    if (b.docVersion() == version) {
                        b.httpScripts = map;
                        httpScriptsVersion = version;
                        java.util.List<Runnable> waiting = new java.util.ArrayList<>(httpScriptWaiters);
                        httpScriptWaiters.clear();
                        waiting.forEach(Runnable::run);
                    } else if (!httpScriptWaiters.isEmpty()) {
                        httpScriptMap(); // the text moved on meanwhile: worked out again for the waiters
                    }
                });
            });
        }
        return null;
    }
    /**
     * Runs {@code then} on the FX thread once {@link #httpScriptMap} knows the current text: now when it does, else
     * when the parse off the FX thread lands. For a command that must not decide on a guess.
     */
    void whenHttpScriptMap(Runnable then) {
        if (httpScriptMap() != null) {
            then.run();
            return;
        }
        httpScriptWaiters.add(then);
    }

    private final java.util.List<Runnable> httpScriptWaiters = new java.util.ArrayList<>();
    private long httpScriptsVersion = -1;
    private long httpScriptsAsked = -1;
}
