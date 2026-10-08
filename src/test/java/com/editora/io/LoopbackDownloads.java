package com.editora.io;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * A stand-in for the internet, for tests of the code that downloads things: a JDK {@link HttpServer} bound to
 * {@code 127.0.0.1} on a free port, plus an {@link HttpClient} that sends <em>every</em> request to it.
 *
 * <p>The production code is handed real-looking URLs ({@code https://download.eclipse.org/…}), so its own URL
 * rules — HTTPS only, the install catalog's host list — run exactly as they do for a user. The client returned
 * by {@link #client()} then rewrites {@code https://host/path} to {@code http://127.0.0.1:port/host/path}
 * before sending, so nothing ever leaves the machine; a URL nobody registered answers 404, and a redirect is
 * issued to the loopback form of its target. {@link #requests()} lists what was asked for, in order.
 */
public final class LoopbackDownloads implements AutoCloseable {

    @FunctionalInterface
    private interface Route {
        void answer(HttpExchange exchange) throws IOException;
    }

    private final HttpServer server;
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "loopback-downloads");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, Route> routes = new ConcurrentHashMap<>();
    private final List<String> requests = new CopyOnWriteArrayList<>();

    public LoopbackDownloads() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(pool);
        server.createContext("/", this::handle);
        server.start();
    }

    /** Answers {@code url} with 200 and {@code body}. */
    public LoopbackDownloads serve(String url, byte[] body) {
        routes.put(url, exchange -> respond(exchange, 200, body));
        return this;
    }

    /** Answers {@code url} with 200 and {@code text} as UTF-8. */
    public LoopbackDownloads serve(String url, String text) {
        return serve(url, text.getBytes(StandardCharsets.UTF_8));
    }

    /** Answers {@code url} with {@code status} and a short body. */
    public LoopbackDownloads status(String url, int status) {
        routes.put(url, exchange -> respond(exchange, status, ("status " + status).getBytes(StandardCharsets.UTF_8)));
        return this;
    }

    /** Answers {@code url} with a 302 to {@code target} (another URL of this fixture). */
    public LoopbackDownloads redirect(String url, String target) {
        routes.put(url, exchange -> {
            exchange.getResponseHeaders()
                    .add("Location", loopback(URI.create(target)).toString());
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        return this;
    }

    /**
     * Answers {@code url} with 200 and a {@code Content-Length} of {@code declaredLength}, sends only
     * {@code sent}, then drops the connection — a transfer cut off part-way.
     */
    public LoopbackDownloads cutOff(String url, byte[] sent, int declaredLength) {
        routes.put(url, exchange -> {
            exchange.sendResponseHeaders(200, declaredLength);
            try {
                OutputStream out = exchange.getResponseBody();
                out.write(sent);
                out.flush();
            } finally {
                try {
                    exchange.close(); // short of the declared length: the server closes the connection
                } catch (RuntimeException ignored) {
                    // the close itself reports the missing bytes; the client sees the dropped connection
                }
            }
        });
        return this;
    }

    /** Answers {@code url} with 200 and {@code body}, but only once {@code release} has been counted down. */
    public LoopbackDownloads serveWhenReleased(String url, byte[] body, CountDownLatch release) {
        routes.put(url, exchange -> {
            try {
                if (!release.await(30, TimeUnit.SECONDS)) {
                    respond(exchange, 504, new byte[0]);
                    return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                exchange.close();
                return;
            }
            respond(exchange, 200, body);
        });
        return this;
    }

    /** The URLs requested so far (in their original {@code https://host/…} form), oldest first. */
    public List<String> requests() {
        return List.copyOf(requests);
    }

    /** A client whose every request is answered by this fixture. Each call builds its own, closed by its owner. */
    public LazyHttpClient client() {
        return new LazyHttpClient(() -> new Rewriting(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .proxy(HttpClient.Builder.NO_PROXY)
                .build()));
    }

    @Override
    public void close() {
        server.stop(0);
        pool.shutdownNow();
    }

    private void handle(HttpExchange exchange) throws IOException {
        URI uri = exchange.getRequestURI();
        String url =
                "https://" + uri.getRawPath().substring(1) + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        requests.add(url);
        Route route = routes.get(url);
        try {
            if (route == null) {
                respond(exchange, 404, "not found".getBytes(StandardCharsets.UTF_8));
            } else {
                route.answer(exchange);
            }
        } catch (IOException clientWentAway) {
            exchange.close();
        }
    }

    private static void respond(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
        exchange.close();
    }

    private URI loopback(URI uri) throws IOException {
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw new IOException("the loopback fixture only answers https URLs, not " + uri);
        }
        String port = uri.getPort() == -1 ? "" : ":" + uri.getPort();
        String query = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/" + uri.getHost() + port
                + (uri.getRawPath() == null ? "" : uri.getRawPath()) + query);
    }

    /** Sends everything to the loopback server; the rest is the wrapped client's behaviour. */
    private final class Rewriting extends HttpClient {
        private final HttpClient inner;

        Rewriting(HttpClient inner) {
            this.inner = inner;
        }

        private HttpRequest rewrite(HttpRequest request) throws IOException {
            return HttpRequest.newBuilder(request, (name, value) -> true)
                    .uri(loopback(request.uri()))
                    .build();
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
                throws IOException, InterruptedException {
            return inner.send(rewrite(request), handler);
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            try {
                return inner.sendAsync(rewrite(request), handler);
            } catch (IOException e) {
                return CompletableFuture.failedFuture(e);
            }
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request, HttpResponse.BodyHandler<T> handler, HttpResponse.PushPromiseHandler<T> push) {
            return sendAsync(request, handler);
        }

        @Override
        public void shutdownNow() {
            inner.shutdownNow();
        }

        @Override
        public void close() {
            inner.close();
        }

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return inner.cookieHandler();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return inner.connectTimeout();
        }

        @Override
        public Redirect followRedirects() {
            return inner.followRedirects();
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return inner.proxy();
        }

        @Override
        public SSLContext sslContext() {
            return inner.sslContext();
        }

        @Override
        public SSLParameters sslParameters() {
            return inner.sslParameters();
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return inner.authenticator();
        }

        @Override
        public Version version() {
            return inner.version();
        }

        @Override
        public Optional<Executor> executor() {
            return inner.executor();
        }
    }
}
