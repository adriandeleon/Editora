package com.editora.update;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import javafx.application.Platform;

import com.editora.AppInfo;
import com.editora.plugin.PluginRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Checks GitHub for a newer release (the {@code GitService}/{@code PluginRegistry} idiom: one daemon executor,
 * results marshaled back to the FX thread via {@link Platform#runLater}). Fetches {@link AppInfo#LATEST_RELEASE_API}
 * over HTTPS, parses it with the pure {@link UpdateCheck}, and reports an {@link Outcome}. Reuses
 * {@link PluginRegistry#readCapped}/{@link PluginRegistry#isHttps} for the bounded HTTPS read; no new dependency.
 */
public final class UpdateService {

    private static final Logger LOG = Logger.getLogger(UpdateService.class.getName());
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);
    /** The releases-API JSON carries the whole asset list; cap the read defensively. */
    private static final long MAX_RELEASE_BYTES = 1_000_000;

    /** The result of a check: {@code available} = a newer release exists; {@code latest} = the newest published
     *  release (non-null on success even when it isn't newer, for "you're up to date" messaging); {@code error}
     *  = a short failure reason (null on success). */
    public record Outcome(boolean available, ReleaseInfo latest, String error) {
        public boolean ok() {
            return error == null;
        }
    }

    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "update-check");
        t.setDaemon(true);
        return t;
    });

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final ObjectMapper mapper = new ObjectMapper();

    /** Checks off-thread whether a release newer than {@code currentVersion} exists; {@code onResult} runs on the
     *  FX thread. */
    public void check(String currentVersion, Consumer<Outcome> onResult) {
        exec.submit(() -> {
            Outcome outcome = fetchSync(currentVersion);
            Platform.runLater(() -> onResult.accept(outcome));
        });
    }

    private Outcome fetchSync(String current) {
        String url = AppInfo.LATEST_RELEASE_API;
        if (!PluginRegistry.isHttps(url)) {
            return new Outcome(false, null, "update url must be https");
        }
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .header("User-Agent", "Editora/" + AppInfo.VERSION) // GitHub rejects a missing UA with 403
                    .GET()
                    .build();
            HttpResponse<InputStream> resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() / 100 != 2) {
                resp.body().close();
                return new Outcome(false, null, "HTTP " + resp.statusCode());
            }
            byte[] body;
            try (InputStream in = resp.body()) {
                body = PluginRegistry.readCapped(in, MAX_RELEASE_BYTES);
            }
            ReleaseInfo latest = UpdateCheck.parseLatest(mapper, body);
            if (latest == null) {
                return new Outcome(false, null, "no usable release in response");
            }
            return new Outcome(UpdateCheck.isNewer(current, latest.version()), latest, null);
        } catch (Exception e) {
            LOG.log(Level.FINE, "update check failed", e);
            return new Outcome(
                    false, null, e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()));
        }
    }

    public void shutdown() {
        exec.shutdownNow();
    }
}
