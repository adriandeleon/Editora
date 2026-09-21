package com.editora.agent.runtime;

/** Token-count provenance travels with accounting. Adapters can supply a reliable tokenizer. */
public final class AgentTokens {
    private AgentTokens() {}

    public enum Provenance {
        EXACT,
        PROVIDER_REPORTED,
        TOKENIZER_ESTIMATED,
        HEURISTIC
    }

    public record Count(long tokens, Provenance provenance) {
        public Count {
            if (tokens < 0) throw new IllegalArgumentException("Negative token count");
        }
    }

    @FunctionalInterface
    public interface Counter {
        Count count(String text);
    }

    public static final Counter CONSERVATIVE = text -> new Count(AgentContext.cost(text), Provenance.HEURISTIC);
}
