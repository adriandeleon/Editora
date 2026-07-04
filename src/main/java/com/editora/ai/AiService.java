package com.editora.ai;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import javafx.application.Platform;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The UI-facing AI facade (the {@code GitService} idiom): every request runs on one daemon executor,
 * streamed deltas + completion are posted to the FX thread, and a generation guard makes {@link #cancel}
 * (or a newer request) silently drop a stale stream. One in-flight request at a time is the intended
 * use — callers gate their own UI.
 */
public final class AiService {

    /** FX-thread callbacks for one streamed generation. */
    public interface Callbacks {
        void onText(String delta);

        void onDone(String stopReason);

        void onError(String message);
    }

    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ai-service");
        t.setDaemon(true);
        return t;
    });

    private final AiClient client = new AiClient();
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicLong generation = new AtomicLong();

    /** Streams one generation; a later {@link #cancel}/generate supersedes it (stale callbacks dropped). */
    public void generate(
            AiProvider provider,
            String endpoint,
            String apiKey,
            String model,
            String system,
            String user,
            Callbacks cb) {
        generate(provider, endpoint, apiKey, model, system, user, AiRequests.MAX_TOKENS, java.util.List.of(), cb);
    }

    /** The full form: an explicit output cap + stop sequences (inline completion stops at end-of-line). */
    public void generate(
            AiProvider provider,
            String endpoint,
            String apiKey,
            String model,
            String system,
            String user,
            int maxTokens,
            java.util.List<String> stopSequences,
            Callbacks cb) {
        long gen = generation.incrementAndGet();
        exec.submit(() -> client.stream(
                provider,
                endpoint,
                apiKey,
                AiRequests.requestFor(mapper, provider, model, system, user, maxTokens, stopSequences),
                () -> gen != generation.get(),
                new AiClient.Listener() {
                    @Override
                    public void onText(String delta) {
                        post(gen, () -> cb.onText(delta));
                    }

                    @Override
                    public void onDone(String stopReason) {
                        post(gen, () -> cb.onDone(stopReason));
                    }

                    @Override
                    public void onError(String message) {
                        post(gen, () -> cb.onError(message));
                    }
                }));
    }

    /** The result of a connection check: whether the endpoint accepted a minimal request, plus a message
     *  (a stop reason on success, or the HTTP/connection error on failure). */
    public record Ping(boolean ok, String message) {}

    /**
     * Sends a tiny (1-token) request to verify the provider/endpoint/key/model actually work — the
     * Settings green/red health check. Never touches the generation counter, so it can't cancel (or be
     * cancelled by) a real generation; the result is posted to the FX thread exactly once.
     */
    public void ping(
            AiProvider provider, String endpoint, String apiKey, String model, java.util.function.Consumer<Ping> cb) {
        exec.submit(() -> {
            String error = client.check(
                    provider, endpoint, apiKey, AiRequests.pingRequest(mapper, provider, model), AiClient.PING_TIMEOUT);
            Platform.runLater(() -> cb.accept(new Ping(error == null, error == null ? "" : error)));
        });
    }

    /** Cancels the in-flight generation (the reader stops at the next event boundary). */
    public void cancel() {
        generation.incrementAndGet();
    }

    /** Runs {@code action} on FX unless a newer generation superseded {@code gen}. */
    private void post(long gen, Runnable action) {
        if (gen == generation.get()) {
            Platform.runLater(() -> {
                if (gen == generation.get()) {
                    action.run();
                }
            });
        }
    }

    public void shutdown() {
        cancel();
        exec.shutdownNow();
    }
}
