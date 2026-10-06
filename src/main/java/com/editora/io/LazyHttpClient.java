package com.editora.io;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.function.Supplier;

/**
 * An {@link HttpClient} built on first use and released on {@link #close}.
 *
 * <p>{@code HttpClient.newBuilder().build()} loads the {@code java.net.http} and TLS stacks and starts a
 * selector thread that lives as long as the client. A service that builds one in a field initializer pays
 * that for every window at startup — seven idle {@code HttpClient-N-SelectorManager} threads in an empty
 * window — for features most sessions never touch (an AI request, a plugin install, a language-server
 * download). Holding the client here keeps construction free and makes the owner's shutdown the one place
 * the thread and its connections are given back.
 *
 * <p>Thread-safe: services use the client from their worker threads and close it from the FX thread.
 */
public final class LazyHttpClient implements AutoCloseable {

    private final Supplier<HttpClient> factory;
    private HttpClient client;

    public LazyHttpClient(Supplier<HttpClient> factory) {
        this.factory = factory;
    }

    /** A client that follows redirects, the configuration every download in the editor uses. */
    public static LazyHttpClient following(Duration connectTimeout) {
        return new LazyHttpClient(() -> HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    /** The client, built now if this is its first use (or its first since {@link #close}). */
    public synchronized HttpClient get() {
        if (client == null) {
            client = factory.get();
        }
        return client;
    }

    /** Whether a client currently exists — false until the first {@link #get} and again after {@link #close}. */
    public synchronized boolean isBuilt() {
        return client != null;
    }

    /**
     * Releases the client's selector thread and connections without waiting for requests in flight (the
     * owner's worker is being interrupted alongside). A later {@link #get} would build a fresh one.
     */
    @Override
    public synchronized void close() {
        if (client != null) {
            client.shutdownNow();
            client = null;
        }
    }
}
