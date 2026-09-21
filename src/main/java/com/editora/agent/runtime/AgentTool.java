package com.editora.agent.runtime;

import java.time.Duration;
import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;

/** A provider-independent capability. Origin metadata never confers trust. */
public record AgentTool(Spec spec, Handler handler) {
    public enum Effect {
        READ,
        WORKSPACE_WRITE,
        DESTRUCTIVE,
        EXTERNAL
    }

    public record Spec(
            String name,
            String description,
            JsonNode inputSchema,
            JsonNode outputSchema,
            Effect effect,
            Duration timeout,
            boolean cancellable,
            String origin) {
        public Spec {
            Objects.requireNonNull(name);
            Objects.requireNonNull(description);
            Objects.requireNonNull(effect);
            Objects.requireNonNull(timeout);
            if (!name.matches("[a-zA-Z0-9_-]{1,64}")
                    || timeout.isNegative()
                    || timeout.isZero()
                    || !inputSchema.isObject()) {
                throw new IllegalArgumentException("Invalid tool specification");
            }
            inputSchema = inputSchema.deepCopy();
            outputSchema = outputSchema == null ? null : outputSchema.deepCopy();
        }

        @Override
        public JsonNode inputSchema() {
            return inputSchema.deepCopy();
        }

        @Override
        public JsonNode outputSchema() {
            return outputSchema == null ? null : outputSchema.deepCopy();
        }
    }

    public record Result(String text, boolean error, boolean changed) {
        public static Result ok(String text) {
            return new Result(text, false, false);
        }

        public static Result failure(String text) {
            return new Result(text, true, false);
        }
    }

    @FunctionalInterface
    public interface Handler {
        Result execute(JsonNode arguments, AgentCancellation cancellation) throws Exception;
    }

    public AgentTool {
        Objects.requireNonNull(spec);
        Objects.requireNonNull(handler);
    }
}
