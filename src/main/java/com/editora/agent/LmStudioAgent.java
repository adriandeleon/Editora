package com.editora.agent;

import java.net.URI;
import java.util.Map;

import com.editora.ai.AiEndpoints;
import com.editora.ai.AiProvider;
import com.fasterxml.jackson.databind.ObjectMapper;

import static com.editora.i18n.Messages.tr;

/** Per-process OpenCode configuration for the LM Studio/Bionic ACP preset; never writes config files. */
public final class LmStudioAgent {
    private static final String PROVIDER = "editora-lmstudio";
    private static final String KEY_ENV = "EDITORA_LMSTUDIO_API_KEY";

    private LmStudioAgent() {}

    public static Map<String, String> environment(String endpoint, String model, String apiKey) {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException(tr("status.agent.lmstudioModelRequired"));
        }
        String resolved = AiEndpoints.resolve(AiProvider.LMSTUDIO, endpoint);
        URI uri;
        try {
            uri = URI.create(resolved);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(tr("status.agent.lmstudioEndpointInvalid"));
        }
        if (uri.getHost() == null
                || uri.getUserInfo() != null
                || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || !resolved.endsWith("/chat/completions")) {
            throw new IllegalArgumentException(tr("status.agent.lmstudioEndpointInvalid"));
        }
        String key = apiKey == null ? "" : apiKey.strip();
        if (!key.isEmpty() && AiEndpoints.isCleartextRemote(resolved)) {
            throw new IllegalArgumentException(tr("status.agent.lmstudioHttpsRequired"));
        }
        var config = new ObjectMapper().createObjectNode();
        String modelId = model.strip();
        config.put("model", PROVIDER + "/" + modelId);
        config.put("small_model", PROVIDER + "/" + modelId);
        config.putArray("enabled_providers").add(PROVIDER);
        var provider = config.putObject("provider").putObject(PROVIDER);
        provider.put("npm", "@ai-sdk/openai-compatible");
        provider.put("name", "LM Studio / Bionic");
        var options = provider.putObject("options");
        options.put("baseURL", resolved.substring(0, resolved.length() - "/chat/completions".length()));
        options.put("apiKey", "{env:" + KEY_ENV + "}");
        provider.putObject("models").putObject(modelId).put("name", modelId);
        return Map.of("OPENCODE_CONFIG_CONTENT", config.toString(), KEY_ENV, key);
    }
}
