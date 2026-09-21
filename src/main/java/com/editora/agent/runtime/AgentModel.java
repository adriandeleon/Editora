package com.editora.agent.runtime;

import java.util.List;
import java.util.function.Consumer;

/** Model boundary; adapters own protocol dialects, the runtime owns tool execution. */
public interface AgentModel {
    enum Feature {
        PARALLEL_CALLS,
        REASONING,
        STRUCTURED_OUTPUT,
        USAGE,
        PROMPT_CACHING,
        TOKENIZER
    }

    enum Support {
        SUPPORTED,
        UNSUPPORTED,
        UNKNOWN
    }

    record Capabilities(
            boolean tools,
            boolean streaming,
            int contextTokens,
            int outputTokens,
            java.util.Map<Feature, Support> features) {
        public Capabilities(boolean tools, boolean streaming, int contextTokens, int outputTokens) {
            this(tools, streaming, contextTokens, outputTokens, java.util.Map.of());
        }

        public Capabilities {
            features = java.util.Map.copyOf(features);
            if (contextTokens < 1024 || outputTokens < 1 || outputTokens >= contextTokens) {
                throw new IllegalArgumentException("Invalid model context/output limits");
            }
        }

        public Support support(Feature feature) {
            return features.getOrDefault(feature, Support.UNKNOWN);
        }
    }

    record Call(String id, String name, String arguments) {}

    record Message(String role, String text, List<Call> calls, String callId, boolean error) {
        public Message {
            calls = List.copyOf(calls);
            text = text == null ? "" : text;
        }

        public static Message text(String role, String text) {
            return new Message(role, text, List.of(), null, false);
        }

        public static Message observation(String id, String text, boolean error) {
            return new Message("tool", text, List.of(), id, error);
        }
    }

    record Request(String system, List<Message> messages, List<AgentTool.Spec> tools, int outputTokens) {
        public Request(String system, List<Message> messages, List<AgentTool.Spec> tools) {
            this(system, messages, tools, 0);
        }

        public Request {
            messages = List.copyOf(messages);
            tools = List.copyOf(tools);
        }
    }

    record Response(String text, List<Call> calls, String stopReason, long inputTokens, long outputTokens) {
        public Response {
            text = text == null ? "" : text;
            calls = List.copyOf(calls);
        }
    }

    Capabilities capabilities();

    default AgentModelProfile profile() {
        return AgentModelProfile.fixed(capabilities());
    }

    default void prepare(AgentCancellation cancellation) {
        cancellation.check();
    }

    /** A complete provider envelope reporting truncation. No partial calls are available for execution. */
    final class OutputLimit extends java.io.IOException {
        public OutputLimit() {
            super("Model output limit reached; incomplete tool calls were discarded");
        }
    }

    default AgentTokens.Counter tokenCounter() {
        return AgentTokens.CONSERVATIVE;
    }

    /** Blocking, off FX. Throw on interrupted/incomplete streams; never execute a partial tool call. */
    Response respond(Request request, AgentCancellation cancellation, Consumer<String> text) throws Exception;
}
