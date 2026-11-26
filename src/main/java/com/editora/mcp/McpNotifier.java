package com.editora.mcp;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jspecify.annotations.Nullable;

/**
 * The server's notifications: the event streams agents hold open, and the watch that tells them when the resources
 * change. A legacy client holds a GET stream and subscribes with {@code resources/subscribe}; a modern one holds the
 * response of {@code subscriptions/listen}, which names what it wants and gets every notification tagged with that
 * request's id.
 *
 * <p>The watch runs only while a stream is open: once a second it asks the window for each open file's change stamp
 * (a number, no text) and, for a subscribed diagnostics or Git status resource, reads that resource. Nothing runs, and
 * nothing is asked of the FX thread, while no agent listens.
 */
final class McpNotifier {

    private static final Logger LOG = Logger.getLogger(McpNotifier.class.getName());

    /** How often the watch looks. */
    static final long WATCH_MILLIS = 1_000;

    /** A comment line this often keeps an idle stream from being taken for a dead one. */
    static final long KEEP_ALIVE_MILLIS = 15_000;

    /** One open event stream and what it asked for. */
    final class Stream {
        private final BlockingQueue<ObjectNode> queue = new LinkedBlockingQueue<>();
        /** The {@code subscriptions/listen} request id (modern), or null for a legacy GET stream. */
        final @Nullable JsonNode subscriptionId;

        final boolean resourcesListChanged;
        /** The URIs it watches: a modern stream's own, or, for a legacy one, null (the shared legacy set). */
        final @Nullable Set<String> uris;

        private volatile boolean closing;

        Stream(@Nullable JsonNode subscriptionId, boolean resourcesListChanged, @Nullable Set<String> uris) {
            this.subscriptionId = subscriptionId;
            this.resourcesListChanged = resourcesListChanged;
            this.uris = uris;
        }

        Set<String> watched() {
            return uris != null ? uris : legacySubscriptions;
        }

        void offer(ObjectNode notification) {
            queue.offer(notification);
        }

        /**
         * Writes this stream's events to {@code out} until the client goes away or the server stops; {@code
         * last}, if any, is written before a stop (a modern stream's final response). Blocks the calling thread.
         */
        void pump(OutputStream out, @Nullable ObjectNode first, Supplier<@Nullable ObjectNode> last) {
            try {
                if (first != null) {
                    writeEvent(out, first);
                }
                while (!closing) {
                    ObjectNode next = queue.poll(KEEP_ALIVE_MILLIS, TimeUnit.MILLISECONDS);
                    if (closing) {
                        break;
                    }
                    if (next == null) {
                        out.write(": keep-alive\n\n".getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    } else {
                        writeEvent(out, next);
                    }
                }
                ObjectNode end = last.get();
                if (end != null) {
                    writeEvent(out, end);
                }
            } catch (IOException e) {
                // the client closed the stream: for a modern one that is the end of the subscription
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                remove(this);
            }
        }

        private void close() {
            closing = true;
            queue.offer(mapper.createObjectNode()); // wakes the pump
        }
    }

    private final ObjectMapper mapper;
    private final Supplier<McpTools> tools;
    private final CopyOnWriteArrayList<Stream> streams = new CopyOnWriteArrayList<>();
    /** What legacy clients subscribed to with {@code resources/subscribe}: one set, the server has no sessions. */
    private final Set<String> legacySubscriptions = ConcurrentHashMap.newKeySet();

    private final ScheduledExecutorService watch = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "mcp-watch");
        t.setDaemon(true);
        return t;
    });
    private @Nullable ScheduledFuture<?> watching;

    /** What the watch saw last; touched only on the watch thread. */
    private @Nullable Set<String> lastUris;

    private final Map<String, String> lastStamps = new HashMap<>();

    McpNotifier(ObjectMapper mapper, Supplier<McpTools> tools) {
        this.mapper = mapper;
        this.tools = tools;
    }

    /** Opens a stream; the watch starts with the first one. */
    Stream open(@Nullable JsonNode subscriptionId, boolean resourcesListChanged, @Nullable Set<String> uris) {
        Stream s = new Stream(subscriptionId, resourcesListChanged, uris);
        streams.add(s);
        startWatch();
        return s;
    }

    void subscribeLegacy(String uri) {
        legacySubscriptions.add(uri);
    }

    void unsubscribeLegacy(String uri) {
        legacySubscriptions.remove(uri);
    }

    int streamCount() {
        return streams.size();
    }

    /** Ends every stream (each writes its final event) and stops the watch. */
    synchronized void closeAll() {
        for (Stream s : streams) {
            s.close();
        }
        stopWatch();
    }

    /** Tells every stream that the resource list may have changed (the window behind the server changed). */
    void resourcesChanged() {
        for (Stream s : streams) {
            if (s.resourcesListChanged) {
                s.offer(notification(s, "notifications/resources/list_changed", null));
            }
        }
    }

    private void remove(Stream s) {
        streams.remove(s);
        if (streams.isEmpty()) {
            stopWatch();
        }
    }

    private synchronized void startWatch() {
        if (watching == null || watching.isDone()) {
            watching = watch.scheduleWithFixedDelay(this::tick, 0, WATCH_MILLIS, TimeUnit.MILLISECONDS);
        }
    }

    private synchronized void stopWatch() {
        if (watching != null && streams.isEmpty()) {
            watching.cancel(false);
            watching = null;
            watch.execute(() -> {
                lastUris = null;
                lastStamps.clear();
            });
        }
    }

    /** One look: list changes and updates, to the streams that asked. Package-private for tests. */
    void tick() {
        if (streams.isEmpty()) {
            return;
        }
        try {
            McpTools t = tools.get();
            Map<String, Long> stamps = t.bufferStamps();
            Set<String> uris = t.resourceUris(stamps);
            if (lastUris != null && !lastUris.equals(uris)) {
                resourcesChanged();
            }
            lastUris = uris;
            Set<String> watched = new java.util.TreeSet<>(legacySubscriptions);
            for (Stream s : streams) {
                if (s.uris != null) {
                    watched.addAll(s.uris);
                }
            }
            lastStamps.keySet().retainAll(watched);
            for (String uri : watched) {
                String stamp = t.resourceStamp(uri, stamps);
                String before = lastStamps.put(uri, stamp == null ? "" : stamp);
                if (before != null && !Objects.equals(before, stamp == null ? "" : stamp)) {
                    for (Stream s : streams) {
                        if (s.watched().contains(uri)) {
                            ObjectNode params = mapper.createObjectNode().put("uri", uri);
                            s.offer(notification(s, "notifications/resources/updated", params));
                        }
                    }
                }
            }
        } catch (RuntimeException e) {
            LOG.log(Level.FINE, "MCP resource watch failed", e); // a window closing mid-look: try again next time
        }
    }

    /** A notification for {@code s}: a modern stream's carry its subscription id in {@code _meta}. */
    ObjectNode notification(Stream s, String method, @Nullable ObjectNode params) {
        return McpProtocol.notification(mapper, method, params, s.subscriptionId);
    }

    private void writeEvent(OutputStream out, ObjectNode event) throws IOException {
        out.write(("data: " + mapper.writeValueAsString(event) + "\n\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }
}
