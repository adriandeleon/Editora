package com.editora.ai;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.BooleanSupplier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * A minimal streaming client for the Anthropic Messages API over the JDK's {@link HttpClient} —
 * deliberately dependency-free (the official SDK would drag OkHttp/Okio into the jlink/JPMS build).
 * Blocking; {@link AiService} runs it on its daemon executor. The SSE stream is parsed with
 * {@link SseParser} and only {@code text_delta} content is surfaced.
 */
public final class AiClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    /** Generous default response-headers timeout (a reasoning model can be slow to first token). */
    static final Duration DEFAULT_RESPONSE_TIMEOUT = Duration.ofSeconds(120);
    /** Short timeout for the Settings connection check — it must resolve quickly, not hang. */
    public static final Duration PING_TIMEOUT = Duration.ofSeconds(30);

    /** Receives the streamed response (on the calling thread — the service marshals to FX). */
    public interface Listener {
        void onText(String delta);

        /** The turn finished; {@code stopReason} e.g. {@code end_turn}/{@code max_tokens}/{@code refusal}. */
        void onDone(String stopReason);

        void onError(String message);
    }

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();

    /**
     * Sends {@code request} (an {@link AiRequests#requestFor} body matching {@code provider}'s dialect)
     * to {@code endpoint} and streams the reply into {@code listener}, polling {@code cancelled}
     * between events so a stale generation stops reading. For {@link AiProvider#OPENAI} the key is
     * optional (local servers) and sent as a bearer token when present.
     */
    public void stream(
            AiProvider provider,
            String endpoint,
            String apiKey,
            JsonNode request,
            BooleanSupplier cancelled,
            Listener listener) {
        stream(provider, endpoint, apiKey, request, DEFAULT_RESPONSE_TIMEOUT, cancelled, listener);
    }

    /**
     * As {@link #stream(AiProvider, String, String, JsonNode, BooleanSupplier, Listener)}, with an explicit
     * response timeout. The timeout bounds the wait for the response <em>headers</em> (not the streamed
     * body), so a dead / slow-to-start endpoint fails fast instead of hanging forever — while a long,
     * healthy generation still streams to completion. {@code onError} fires on timeout.
     */
    public void stream(
            AiProvider provider,
            String endpoint,
            String apiKey,
            JsonNode request,
            Duration responseTimeout,
            BooleanSupplier cancelled,
            Listener listener) {
        HttpRequest req;
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(responseTimeout)
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(request)));
            if (provider == AiProvider.OPENAI) {
                if (apiKey != null && !apiKey.isBlank()) {
                    b.header("Authorization", "Bearer " + apiKey);
                }
            } else {
                b.header("x-api-key", apiKey).header("anthropic-version", "2023-06-01");
            }
            req = b.build();
        } catch (Exception e) {
            listener.onError(AiErrors.describe(e));
            return;
        }
        try {
            HttpResponse<java.io.InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() != 200) {
                listener.onError(errorBody(resp));
                return;
            }
            String stopReason = "end_turn";
            SseParser parser = new SseParser();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (cancelled.getAsBoolean()) {
                        listener.onDone("cancelled");
                        return;
                    }
                    SseParser.Event event = parser.feed(line);
                    if (event == null) {
                        continue;
                    }
                    if (provider == AiProvider.OPENAI) {
                        if (OpenAiSse.isDone(event.data())) {
                            listener.onDone(stopReason);
                            return;
                        }
                        JsonNode chunk = mapper.readTree(event.data());
                        String error = OpenAiSse.errorMessage(chunk);
                        if (error != null) {
                            listener.onError(error);
                            return;
                        }
                        String delta = OpenAiSse.textDelta(chunk);
                        if (delta != null && !delta.isEmpty()) {
                            listener.onText(delta);
                        }
                        String finish = OpenAiSse.finishReason(chunk);
                        if (finish != null) {
                            stopReason = finish;
                        }
                        continue;
                    }
                    JsonNode data = mapper.readTree(event.data());
                    String type = data.hasNonNull("type") ? data.get("type").asText() : event.name();
                    switch (type) {
                        case "content_block_delta" -> {
                            JsonNode delta = data.get("delta");
                            if (delta != null
                                    && "text_delta".equals(delta.path("type").asText())
                                    && delta.hasNonNull("text")) {
                                listener.onText(delta.get("text").asText());
                            }
                        }
                        case "message_delta" -> {
                            JsonNode sr = data.path("delta").path("stop_reason");
                            if (!sr.isMissingNode() && !sr.isNull()) {
                                stopReason = sr.asText();
                            }
                        }
                        case "error" -> {
                            listener.onError(data.path("error").path("message").asText("stream error"));
                            return;
                        }
                        default -> {
                            // message_start / content_block_start / ping / message_stop: nothing to surface
                        }
                    }
                }
            }
            listener.onDone(stopReason);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            listener.onError(AiErrors.describe(e));
        }
    }

    /**
     * A blocking, <em>non-streaming</em> health check: POSTs {@code request} and returns null on HTTP 200,
     * else a human-readable error. Unlike {@link #stream}, the {@code timeout} bounds the <em>entire</em>
     * exchange (a non-streaming body handler receives the whole response before {@code send} returns), so
     * a slow local server warming up a model can't leave the caller hanging — it fails with a clear
     * "timed out" instead. Used by the Settings connection check.
     */
    public String check(
            AiProvider provider, String endpoint, String apiKey, JsonNode request, java.time.Duration timeout) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(timeout)
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(request)));
            if (provider == AiProvider.OPENAI) {
                if (apiKey != null && !apiKey.isBlank()) {
                    b.header("Authorization", "Bearer " + apiKey);
                }
            } else {
                b.header("x-api-key", apiKey).header("anthropic-version", "2023-06-01");
            }
            HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200 ? null : errorBodyString(resp.statusCode(), resp.body());
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return AiErrors.describe(e);
        }
    }

    /** Parses an {@code error.message} out of a String error body (Anthropic + OpenAI share the shape). */
    private String errorBodyString(int status, String body) {
        try {
            String msg = mapper.readTree(body).path("error").path("message").asText("");
            return "HTTP " + status + (msg.isEmpty() ? "" : ": " + msg);
        } catch (Exception e) {
            return "HTTP " + status;
        }
    }

    private String errorBody(HttpResponse<java.io.InputStream> resp) {
        try (var in = resp.body()) {
            JsonNode body = mapper.readTree(in);
            String msg = body.path("error").path("message").asText("");
            return "HTTP " + resp.statusCode() + (msg.isEmpty() ? "" : ": " + msg);
        } catch (Exception e) {
            return "HTTP " + resp.statusCode();
        }
    }
}
