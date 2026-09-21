package com.editora.agent.runtime;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

import com.editora.ai.AiClient;
import com.editora.ai.AiProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Tool-capable Anthropic and OpenAI-compatible adapters over the existing cancellable SSE transport. */
public final class HttpAgentModel implements AgentModel {
    private final AiProvider provider;
    private final String endpoint;
    private final String key;
    private final String model;
    private volatile Capabilities capabilities;
    private final AiClient client = new AiClient();
    private final ObjectMapper json = new ObjectMapper();

    public HttpAgentModel(AiProvider provider, String endpoint, String key, String model, Capabilities capabilities) {
        if (provider == AiProvider.CODEX) {
            throw new IllegalArgumentException("Select the Codex CLI agent to use its existing login");
        }
        this.provider = provider;
        this.endpoint = endpoint;
        this.key = key;
        this.model = model;
        this.capabilities = capabilities;
    }

    @Override
    public Capabilities capabilities() {
        return capabilities;
    }

    @Override
    public Response respond(Request request, AgentCancellation cancellation, Consumer<String> text) throws Exception {
        Collector collector = new Collector(provider, text);
        client.stream(
                provider,
                endpoint,
                key,
                body(request),
                Duration.ofSeconds(120),
                () -> cancellation.isCancelled() || collector.error != null,
                collector);
        cancellation.check();
        Response response = collector.response();
        var features = new java.util.EnumMap<Feature, Support>(Feature.class);
        features.putAll(capabilities.features());
        if (response.calls().size() > 1) features.put(Feature.PARALLEL_CALLS, Support.SUPPORTED);
        if (response.inputTokens() > 0 || response.outputTokens() > 0) features.put(Feature.USAGE, Support.SUPPORTED);
        capabilities = new Capabilities(
                capabilities.tools(),
                capabilities.streaming(),
                capabilities.contextTokens(),
                capabilities.outputTokens(),
                features);
        return response;
    }

    ObjectNode body(Request request) throws IOException {
        ObjectNode body = json.createObjectNode();
        if (model != null && !model.isBlank()) {
            body.put("model", model.strip());
        }
        body.put("stream", true);
        body.put("max_tokens", capabilities.outputTokens());
        boolean openai = provider.usesOpenAiApi();
        ArrayNode messages = body.putArray("messages");
        if (openai) {
            messages.addObject().put("role", "system").put("content", request.system());
        } else {
            body.put("system", request.system());
        }
        for (Message message : request.messages()) {
            if (openai) {
                ObjectNode item = messages.addObject();
                item.put("role", "observation".equals(message.role()) ? "user" : message.role());
                item.put("content", message.text());
                if ("tool".equals(message.role())) {
                    item.put("tool_call_id", message.callId());
                }
                if (!message.calls().isEmpty()) {
                    ArrayNode calls = item.putArray("tool_calls");
                    for (Call call : message.calls()) {
                        ObjectNode tool = calls.addObject().put("id", call.id()).put("type", "function");
                        tool.putObject("function").put("name", call.name()).put("arguments", call.arguments());
                    }
                }
            } else {
                String role = "assistant".equals(message.role()) ? "assistant" : "user";
                // Consecutive tool observations belong in one user message for Anthropic parallel calls.
                ObjectNode last = messages.isEmpty() ? null : (ObjectNode) messages.get(messages.size() - 1);
                ObjectNode item = last != null && role.equals(last.path("role").asText())
                        ? last
                        : messages.addObject().put("role", role);
                ArrayNode content = item.has("content") ? (ArrayNode) item.get("content") : item.putArray("content");
                if ("tool".equals(message.role())) {
                    content.addObject()
                            .put("type", "tool_result")
                            .put("tool_use_id", message.callId())
                            .put("content", message.text())
                            .put("is_error", message.error());
                } else {
                    if (!message.text().isEmpty()) {
                        content.addObject().put("type", "text").put("text", message.text());
                    }
                    for (Call call : message.calls()) {
                        ObjectNode tool = content.addObject()
                                .put("type", "tool_use")
                                .put("id", call.id())
                                .put("name", call.name());
                        JsonNode arguments;
                        try {
                            arguments = json.readTree(call.arguments());
                        } catch (IOException malformed) {
                            arguments = json.createObjectNode();
                        }
                        tool.set(
                                "input",
                                arguments != null && arguments.isObject() ? arguments : json.createObjectNode());
                    }
                }
            }
        }
        ArrayNode tools = body.putArray("tools");
        for (var spec : request.tools()) {
            ObjectNode definition = tools.addObject();
            if (openai) {
                definition.put("type", "function");
                definition = definition.putObject("function");
            }
            definition.put("name", spec.name()).put("description", spec.description());
            definition.set(openai ? "parameters" : "input_schema", spec.inputSchema());
        }
        return body;
    }

    static final class Collector implements AiClient.Listener {
        private static final int MAX_RESPONSE = 1_000_000;
        // SSE repeats protocol/model metadata for every token. Bound that separately from retained content.
        private static final int MAX_WIRE_CHARS = 16_000_000;
        private final AiProvider provider;
        private final Consumer<String> text;
        private final StringBuilder answer = new StringBuilder();
        private final Map<Integer, PendingCall> calls = new TreeMap<>();
        private String stop;
        private String error;
        private boolean complete;
        private long received;
        private long wireReceived;
        private long inputTokens;
        private long outputTokens;

        private static final class PendingCall {
            String id = "";
            String name = "";
            String initial = "{}";
            boolean structuredArguments;
            final StringBuilder arguments = new StringBuilder();
        }

        Collector(AiProvider provider, Consumer<String> text) {
            this.provider = provider;
            this.text = text;
        }

        @Override
        public void onText(String delta) {
            if (error != null) {
                return;
            }
            if (!acceptContent(delta.length())) return;
            answer.append(delta);
            text.accept(delta);
        }

        @Override
        public void onDone(String reason) {}

        @Override
        public void onError(String message) {
            error = message;
        }

        @Override
        public void onEvent(JsonNode event) {
            if (error != null) {
                return;
            }
            wireReceived += event.toString().length();
            if (wireReceived > MAX_WIRE_CHARS) {
                error = "Model stream envelope exceeds limit";
                return;
            }
            if (provider.usesOpenAiApi()) {
                JsonNode choice = event.path("choices").path(0);
                for (JsonNode part : choice.path("delta").path("tool_calls")) {
                    int index = part.path("index").asInt(-1);
                    if (index < 0 || index >= 128) {
                        error = "Invalid tool-call index";
                        return;
                    }
                    PendingCall call = calls.computeIfAbsent(index, unused -> new PendingCall());
                    String id = part.path("id").asText(""),
                            name = part.path("function").path("name").asText("");
                    if (!acceptContent(id.length() + name.length())) return;
                    call.id += id;
                    call.name += name;
                    JsonNode argumentPart = part.path("function").path("arguments");
                    if (argumentPart.isTextual()) {
                        if (!acceptContent(argumentPart.textValue().length())) return;
                        call.arguments.append(argumentPart.asText());
                    } else if (argumentPart.isObject()) {
                        if (call.structuredArguments || !call.arguments.isEmpty()) {
                            error = "Conflicting tool arguments";
                            return;
                        }
                        String initial = argumentPart.toString();
                        if (!acceptContent(initial.length())) return;
                        call.initial = initial;
                        call.structuredArguments = true;
                    }
                }
                if (choice.hasNonNull("finish_reason")) {
                    stop = choice.get("finish_reason").asText();
                    complete = true;
                }
                inputTokens = event.path("usage").path("prompt_tokens").asLong(inputTokens);
                outputTokens = event.path("usage").path("completion_tokens").asLong(outputTokens);
            } else {
                int index = event.path("index").asInt(-1);
                String type = event.path("type").asText();
                JsonNode block = event.path("content_block");
                if ("content_block_start".equals(type)
                        && "tool_use".equals(block.path("type").asText())) {
                    if (index < 0 || index >= 128 || calls.containsKey(index)) {
                        error = "Invalid tool-call index";
                        return;
                    }
                    PendingCall call = new PendingCall();
                    call.id = block.path("id").asText();
                    call.name = block.path("name").asText();
                    call.initial =
                            block.path("input").isObject() ? block.get("input").toString() : "{}";
                    if (!acceptContent(call.id.length() + call.name.length() + call.initial.length())) return;
                    call.structuredArguments = block.path("input").isObject()
                            && block.path("input").size() > 0;
                    calls.put(index, call);
                }
                if ("input_json_delta".equals(event.path("delta").path("type").asText())) {
                    PendingCall call = calls.get(index);
                    if (call == null) {
                        error = "Tool delta without a start";
                        return;
                    }
                    String delta = event.path("delta").path("partial_json").asText();
                    if (!acceptContent(delta.length())) return;
                    call.arguments.append(delta);
                }
                if ("message_start".equals(type)) {
                    inputTokens = event.path("message")
                            .path("usage")
                            .path("input_tokens")
                            .asLong();
                }
                if ("message_delta".equals(type)) {
                    stop = event.path("delta").path("stop_reason").asText();
                    outputTokens = event.path("usage").path("output_tokens").asLong();
                }
                if ("message_stop".equals(type)) {
                    complete = true;
                }
            }
        }

        private boolean acceptContent(int chars) {
            received += chars;
            if (received <= MAX_RESPONSE) return true;
            error = "Model response content exceeds limit";
            return false;
        }

        Response response() throws IOException {
            if (error != null) {
                throw new IOException(error);
            }
            if (!complete || stop == null) {
                throw new IOException("Model stream ended before a complete response");
            }
            if (!calls.isEmpty() && !"tool_calls".equals(stop) && !"tool_use".equals(stop)) {
                throw new IOException(
                        "length".equals(stop) || "max_tokens".equals(stop)
                                ? "Model output limit reached; incomplete tool calls were not executed. Retry with a smaller step or a model profile that fits the output budget."
                                : "Incomplete tool response: " + stop);
            }
            List<Call> result = new ArrayList<>();
            for (PendingCall call : calls.values()) {
                if (call.id.isBlank() || call.name.isBlank()) {
                    throw new IOException("Incomplete tool identity");
                }
                if (call.structuredArguments && !call.arguments.isEmpty()) {
                    throw new IOException("Conflicting structured and streamed tool arguments");
                }
                result.add(new Call(
                        call.id, call.name, call.arguments.isEmpty() ? call.initial : call.arguments.toString()));
            }
            return new Response(answer.toString(), result, stop, inputTokens, outputTokens);
        }
    }
}
