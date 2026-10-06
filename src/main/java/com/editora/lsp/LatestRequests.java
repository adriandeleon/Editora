package com.editora.lsp;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * At most one unanswered request per document and request kind.
 *
 * <p>The editor re-asks for the same things every time typing pauses — diagnostics, the outline, folding
 * ranges, semantic tokens, inlay hints — and again on scroll and caret rest. A reply is only ever applied
 * when it is the latest one, so an older request still running on the server is pure load: a slow server
 * (jdtls while it indexes) ends up with a queue of requests whose answers will all be thrown away, and each
 * new one waits behind them. This class keeps the latest request per {@code (uri, kind)} and decides, for
 * the next one, between three outcomes:
 *
 * <ul>
 *   <li>nothing is running: send it;
 *   <li>one is running for the <em>same</em> {@code stamp} (same session, document version and arguments):
 *       send nothing — the answer on its way is the answer to this request too;
 *   <li>one is running for a different stamp: cancel it — which is what tells the server to stop
 *       ({@code $/cancelRequest}) — and send the new one. The cancelled request's handler is never called.
 * </ul>
 *
 * <p>Version and generation guards on the reply stay where they were; this only stops traffic that those
 * guards would have discarded.
 */
final class LatestRequests {

    private record Key(String uri, String kind) {}

    private static final class Slot<T> {
        final CompletableFuture<T> future;
        final Object stamp;
        BiConsumer<? super T, ? super Throwable> handler;
        boolean superseded;

        Slot(CompletableFuture<T> future, Object stamp, BiConsumer<? super T, ? super Throwable> handler) {
            this.future = future;
            this.stamp = stamp;
            this.handler = handler;
        }
    }

    private final Map<Key, Slot<?>> slots = new java.util.HashMap<>();

    /**
     * Issues the request unless an identical one is still unanswered.
     *
     * @param stamp what the answer depends on; equal stamps mean equal answers
     * @param latestHandlerWins when an identical request is running, whether {@code onReply} replaces its
     *     handler (the caller's guards were captured later, so they are the ones that should judge the
     *     reply) or is dropped (the running request's handler carries state this one does not have)
     * @return whether a request was sent
     */
    <T> boolean issue(
            String uri,
            String kind,
            Object stamp,
            boolean latestHandlerWins,
            Supplier<CompletableFuture<T>> send,
            BiConsumer<? super T, ? super Throwable> onReply) {
        Key key = new Key(uri, kind);
        Slot<T> slot;
        synchronized (this) {
            Slot<?> running = slots.get(key);
            if (running != null) {
                if (running.stamp.equals(stamp)) {
                    if (latestHandlerWins) {
                        @SuppressWarnings("unchecked") // equal stamps name the same request, so the same reply type
                        Slot<T> same = (Slot<T>) running;
                        same.handler = onReply;
                    }
                    return false;
                }
                supersede(key, running);
            }
            slot = new Slot<>(send.get(), stamp, onReply);
            slots.put(key, slot);
        }
        slot.future.whenComplete((result, error) -> {
            BiConsumer<? super T, ? super Throwable> handler;
            synchronized (this) {
                slots.remove(key, slot);
                handler = slot.superseded ? null : slot.handler;
            }
            if (handler != null) {
                handler.accept(result, error);
            }
        });
        return true;
    }

    /** Cancels the unanswered request of {@code kind} for {@code uri}, if any; its handler is not called. */
    synchronized void cancel(String uri, String kind) {
        Key key = new Key(uri, kind);
        Slot<?> running = slots.get(key);
        if (running != null) {
            supersede(key, running);
        }
    }

    /**
     * Cancels every unanswered request for {@code uri}: the document was closed, or its server said that
     * what it computes has changed, so an answer already on its way no longer stands for the next request.
     */
    synchronized void cancelAll(String uri) {
        for (var entry : Map.copyOf(slots).entrySet()) {
            if (entry.getKey().uri().equals(uri)) {
                supersede(entry.getKey(), entry.getValue());
            }
        }
    }

    /** Forgets every request without cancelling: their sessions are being disposed and fail them anyway. */
    synchronized void clear() {
        slots.values().forEach(slot -> slot.superseded = true);
        slots.clear();
    }

    /** Unanswered requests — package-private so a test can show the number stays bounded. */
    synchronized int inFlight() {
        return slots.size();
    }

    private void supersede(Key key, Slot<?> slot) {
        slot.superseded = true;
        slots.remove(key, slot);
        slot.future.cancel(true); // reaches the JSON-RPC future, which sends $/cancelRequest
    }
}
