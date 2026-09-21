package com.editora.agent.runtime;

import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** Explainable retrieval ordering, kept independent of UI and model providers. Scores are relevance, not trust. */
public final class AgentContextRanker {
    private AgentContextRanker() {}

    public enum Signal {
        USER_SELECTED(100),
        ACTIVE(80),
        CURRENT_SYMBOL(70),
        DEFINITION(65),
        REFERENCE(60),
        DIAGNOSTIC(55),
        UNSAVED(50),
        GIT_CHANGED(40),
        RECENTLY_EDITED(35),
        AGENT_INSPECTED(25),
        LEXICAL(10);
        final int weight;

        Signal(int weight) {
            this.weight = weight;
        }
    }

    public record Candidate(String path, Set<Signal> signals) {
        public Candidate {
            signals = Set.copyOf(signals);
        }

        public int score() {
            return signals.stream().mapToInt(s -> s.weight).sum();
        }
    }

    /** Session discoveries are bounded metadata. Current IDE flags are supplied freshly on each read. */
    public static final class Index {
        private final java.util.LinkedHashMap<String, Set<Signal>> entries = new java.util.LinkedHashMap<>();

        public synchronized void note(String path, Signal signal) {
            entries.computeIfAbsent(path, p -> java.util.EnumSet.noneOf(Signal.class))
                    .add(signal);
            if (entries.size() > 256) entries.remove(entries.keySet().iterator().next());
        }

        public synchronized List<Candidate> candidates() {
            return entries.entrySet().stream()
                    .map(e -> new Candidate(e.getKey(), e.getValue()))
                    .toList();
        }
    }

    public static List<Candidate> rank(List<Candidate> candidates, int limit) {
        if (limit < 0) throw new IllegalArgumentException("Negative context limit");
        var merged = new java.util.TreeMap<String, Set<Signal>>();
        for (var candidate : candidates)
            merged.computeIfAbsent(candidate.path(), p -> java.util.EnumSet.noneOf(Signal.class))
                    .addAll(candidate.signals());
        return merged.entrySet().stream()
                .map(e -> new Candidate(e.getKey(), e.getValue()))
                .sorted(Comparator.comparingInt(Candidate::score).reversed().thenComparing(Candidate::path))
                .limit(limit)
                .toList();
    }
}
