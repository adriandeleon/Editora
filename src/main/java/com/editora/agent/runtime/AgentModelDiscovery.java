package com.editora.agent.runtime;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

import com.editora.ai.AiProvider;
import com.editora.config.AgentModelProfileConfig;
import com.fasterxml.jackson.databind.*;

import static com.editora.agent.runtime.AgentModelProfile.*;

/** Read-only, same-origin discovery. No loading, downloads, guessed capabilities or redirects. */
public final class AgentModelDiscovery {
    private AgentModelDiscovery() {}

    public static AgentModelProfile discover(
            AiProvider provider,
            String endpoint,
            String key,
            String model,
            int fallbackContext,
            AgentModelProfileConfig config,
            AgentCancellation cancellation) {
        JsonNode data = new ObjectMapper().createObjectNode();
        String status = "NOT_REQUESTED", server = "UNKNOWN";
        cancellation.check();
        if (key != null && !key.isBlank() && com.editora.ai.AiEndpoints.isCleartextRemote(endpoint))
            return resolve(
                    provider.id(), model, fallbackContext, config, data, "UNKNOWN", "REFUSED_CLEARTEXT_CREDENTIAL");
        cancellation.check();
        try {
            URI uri = URI.create(endpoint);
            boolean local = Set.of("localhost", "127.0.0.1", "::1", "[::1]").contains(uri.getHost());
            if (config.discovery() && (provider == AiProvider.LMSTUDIO || (provider.usesOpenAiApi() && local))) {
                // Only the configured origin receives its configured credential.
                URI models = new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), "/api/v1/models", null, null);
                var request = HttpRequest.newBuilder(models)
                        .timeout(Duration.ofSeconds(4))
                        .GET();
                if (key != null && !key.isBlank()) request.header("Authorization", "Bearer " + key);
                try (var client = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(3))
                        .build()) {
                    var future = client.sendAsync(request.build(), ignored -> new LimitedBody());
                    try (var hook = cancellation.onCancel(() -> future.cancel(true))) {
                        var response = future.get(5, java.util.concurrent.TimeUnit.SECONDS);
                        if (response.statusCode() == 200) {
                            data = new ObjectMapper().readTree(response.body());
                            if (data != null && data.path("models").isArray()) {
                                status = "AVAILABLE";
                                server = "LM_STUDIO_API_V1";
                            } else {
                                data = new ObjectMapper().createObjectNode();
                                status = "INVALID_METADATA";
                            }
                        } else status = "UNAVAILABLE";
                    } finally {
                        future.cancel(true);
                    }
                }
            }
        } catch (Exception unavailable) {
            status = "UNAVAILABLE";
        }
        cancellation.check();
        return resolve(provider.id(), model, fallbackContext, config, data, server, status);
    }

    public static AgentModelProfile resolve(
            String provider,
            String model,
            int fallbackContext,
            AgentModelProfileConfig config,
            JsonNode data,
            String server,
            String status) {
        JsonNode match = null;
        for (var candidate : data.path("models")) {
            if (!"llm".equals(candidate.path("type").asText())) continue;
            boolean selected = model.equals(candidate.path("key").asText());
            for (var loaded : candidate.path("loaded_instances"))
                selected |= model.equals(loaded.path("id").asText());
            if (selected) {
                if (match != null) {
                    match = null;
                    break;
                }
                match = candidate;
            }
        }
        int modelContext = 0, loadedContext = 0;
        var features = new EnumMap<AgentModel.Feature, Value<AgentModel.Support>>(AgentModel.Feature.class);
        Value<AgentModel.Support> toolSupport = value(AgentModel.Support.UNKNOWN, Provenance.UNKNOWN);
        if (match != null) {
            modelContext = limit(match.path("max_context_length"));
            // Multiple loaded instances may have different limits. Use the explicit id or the safest known bound.
            for (var loaded : match.path("loaded_instances")) {
                int n = limit(loaded.path("config").path("context_length"));
                if (n > 0) loadedContext = loadedContext == 0 ? n : Math.min(loadedContext, n);
            }
            JsonNode caps = match.path("capabilities");
            if (caps.path("trained_for_tool_use").isBoolean())
                toolSupport = value(
                        caps.path("trained_for_tool_use").asBoolean()
                                ? AgentModel.Support.SUPPORTED
                                : AgentModel.Support.UNSUPPORTED,
                        Provenance.DISCOVERED);
            JsonNode reasoning = caps.path("reasoning").path("allowed_options");
            if (reasoning.isArray() && !reasoning.isEmpty()) {
                boolean enabled = false, onlyOff = true;
                for (var option : reasoning) {
                    enabled |= Set.of("on", "low", "medium", "high").contains(option.asText());
                    onlyOff &= "off".equals(option.asText());
                }
                if (enabled || onlyOff)
                    features.put(
                            AgentModel.Feature.REASONING,
                            value(
                                    enabled ? AgentModel.Support.SUPPORTED : AgentModel.Support.UNSUPPORTED,
                                    Provenance.DISCOVERED));
            }
        }
        var effective = config.contextTokens() > 0
                ? value(config.contextTokens(), Provenance.CONFIGURED)
                : loadedContext > 0
                        ? value(loadedContext, Provenance.DISCOVERED)
                        : value(
                                Math.min(fallbackContext, modelContext > 0 ? modelContext : fallbackContext),
                                Provenance.HEURISTIC);
        if (!"UNKNOWN".equals(config.tools()))
            toolSupport = value(AgentModel.Support.valueOf(config.tools()), Provenance.CONFIGURED);
        int recommended = features.getOrDefault(
                                        AgentModel.Feature.REASONING,
                                        value(AgentModel.Support.UNKNOWN, Provenance.UNKNOWN))
                                .value()
                        == AgentModel.Support.SUPPORTED
                ? 8192
                : 4096;
        int ceiling = Math.min(
                config.maxOutputTokens() > 0 ? config.maxOutputTokens() : 16384, Math.max(256, effective.value() / 2));
        int preferred = Math.min(ceiling, config.outputTokens() > 0 ? config.outputTokens() : recommended);
        return new AgentModelProfile(
                provider,
                model,
                server,
                value(modelContext, modelContext > 0 ? Provenance.DISCOVERED : Provenance.UNKNOWN),
                value(loadedContext, loadedContext > 0 ? Provenance.DISCOVERED : Provenance.UNKNOWN),
                effective,
                value(preferred, config.outputTokens() > 0 ? Provenance.CONFIGURED : Provenance.HEURISTIC),
                value(ceiling, config.maxOutputTokens() > 0 ? Provenance.CONFIGURED : Provenance.HEURISTIC),
                toolSupport,
                features,
                status,
                config,
                2);
    }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final java.util.concurrent.CompletableFuture<byte[]> result =
                new java.util.concurrent.CompletableFuture<>();
        private final java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        private java.util.concurrent.Flow.Subscription subscription;

        public java.util.concurrent.CompletionStage<byte[]> getBody() {
            return result;
        }

        public void onSubscribe(java.util.concurrent.Flow.Subscription s) {
            subscription = s;
            s.request(1);
        }

        public void onNext(List<java.nio.ByteBuffer> chunks) {
            for (var chunk : chunks) {
                if (bytes.size() + (long) chunk.remaining() > 262144) {
                    subscription.cancel();
                    result.completeExceptionally(new java.io.IOException("Discovery response too large"));
                    return;
                }
                byte[] part = new byte[chunk.remaining()];
                chunk.get(part);
                bytes.writeBytes(part);
            }
            subscription.request(1);
        }

        public void onError(Throwable error) {
            result.completeExceptionally(error);
        }

        public void onComplete() {
            result.complete(bytes.toByteArray());
        }
    }

    private static int limit(JsonNode n) {
        return n.canConvertToInt() && n.asInt() >= 1024 && n.asInt() <= 2_000_000 ? n.asInt() : 0;
    }
}
