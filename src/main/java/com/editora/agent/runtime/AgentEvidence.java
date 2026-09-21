package com.editora.agent.runtime;

import java.util.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Payload-free facts minted by native adapters, never from model or MCP claims. */
public record AgentEvidence(
        String id,
        Kind kind,
        String subject,
        String revision,
        long generation,
        Freshness freshness,
        Strength strength,
        String source,
        Map<String, String> facts,
        String invalidatedBy) {
    public enum Kind {
        FILE_CHANGED,
        FILE_CREATED,
        SYMBOL_CHANGED,
        SYMBOL_EXISTS,
        SEMANTIC_REFERENCE,
        TEST_ADDED,
        TEST_EXISTS,
        TEST_EXECUTED,
        TEST_PASSED,
        TEST_PROVEN_TO_DETECT_OLD_FAILURE,
        BUILD_PASSED,
        DIAGNOSTICS_CURRENT,
        VALIDATION_RESULT,
        DIFF_REVIEWED,
        COMMAND_RESULT,
        FILE_READ,
        SEARCH_ABSENCE,
        SEARCH_OBSERVATION,
        USER_CONFIRMATION,
        CONSTRAINT_CHECK
    }

    public enum Freshness {
        CURRENT,
        STALE,
        HISTORICAL
    }

    public enum Strength {
        SUPPORTED,
        PARTIALLY_SUPPORTED,
        UNVERIFIED,
        UNKNOWN
    }

    public AgentEvidence {
        facts = Map.copyOf(facts);
    }

    AgentEvidence freshness(Freshness value, String reason) {
        return new AgentEvidence(id, kind, subject, revision, generation, value, strength, source, facts, reason);
    }

    ObjectNode toJson() {
        var out = new ObjectMapper()
                .createObjectNode()
                .put("id", id)
                .put("kind", kind.name())
                .put("subject", subject)
                .put("revision", revision)
                .put("generation", generation)
                .put("freshness", freshness.name())
                .put("strength", strength.name())
                .put("source", source)
                .put("invalidatedBy", invalidatedBy);
        var detail = out.putObject("facts");
        facts.forEach(detail::put);
        return out;
    }

    /** Bounded session facts. Historical records never regain authority merely by matching a hash. */
    public static final class Ledger {
        private final Map<String, AgentEvidence> entries = new LinkedHashMap<>();
        private int sequence;
        private long generation;

        public AgentEvidence record(
                Kind kind,
                String subject,
                String revision,
                Strength strength,
                String source,
                Map<String, String> facts) {
            for (var previous : entries.values())
                if (previous.kind() == kind
                        && previous.subject().equals(subject)
                        && previous.revision().equals(revision)
                        && previous.freshness() == Freshness.CURRENT
                        && previous.facts().equals(facts)
                        && previous.source().equals(source)) return previous;
            entries.replaceAll((id, previous) -> previous.kind() == kind
                            && previous.subject().equals(subject)
                            && previous.source().equals(source)
                            && previous.freshness() == Freshness.CURRENT
                    ? previous.freshness(Freshness.STALE, "Superseded by a newer observation")
                    : previous);
            var evidence = new AgentEvidence(
                    "E" + ++sequence,
                    kind,
                    subject,
                    revision,
                    generation,
                    Freshness.CURRENT,
                    strength,
                    source,
                    facts,
                    "");
            entries.put(evidence.id(), evidence);
            while (entries.size() > 768) {
                String oldest = entries.values().stream()
                        .filter(e -> e.freshness() != Freshness.CURRENT)
                        .map(AgentEvidence::id)
                        .findFirst()
                        .orElse(entries.keySet().iterator().next());
                entries.remove(oldest);
            }
            return evidence;
        }

        public void invalidateExecution() {
            generation++;
            entries.replaceAll((id, e) -> switch (e.kind()) {
                case BUILD_PASSED,
                        VALIDATION_RESULT,
                        TEST_ADDED,
                        TEST_EXISTS,
                        TEST_EXECUTED,
                        TEST_PASSED,
                        TEST_PROVEN_TO_DETECT_OLD_FAILURE,
                        SYMBOL_CHANGED,
                        SYMBOL_EXISTS,
                        SEMANTIC_REFERENCE,
                        SEARCH_ABSENCE,
                        SEARCH_OBSERVATION,
                        DIAGNOSTICS_CURRENT,
                        CONSTRAINT_CHECK ->
                    e.freshness(Freshness.STALE, "External execution may have changed workspace state");
                default -> e;
            });
        }

        /** A document edit invalidates build/test authority and workspace-wide reference queries.
         *  File reads and symbols in other documents remain independent. */
        public void invalidateDocumentEdit(String path) {
            generation++;
            entries.replaceAll((id, e) -> switch (e.kind()) {
                case BUILD_PASSED,
                        VALIDATION_RESULT,
                        TEST_EXECUTED,
                        TEST_PASSED,
                        TEST_ADDED,
                        TEST_PROVEN_TO_DETECT_OLD_FAILURE,
                        SYMBOL_CHANGED,
                        SEMANTIC_REFERENCE,
                        SEARCH_ABSENCE,
                        SEARCH_OBSERVATION,
                        DIAGNOSTICS_CURRENT,
                        CONSTRAINT_CHECK -> e.freshness(Freshness.STALE, "Document edit: " + path);
                default -> e;
            });
            stale(path, "Document revision changed: " + path);
        }

        public void invalidate(String id) {
            invalidate(id, "Current source no longer matches the observation");
        }

        public void invalidate(String id, String reason) {
            entries.computeIfPresent(id, (key, e) -> e.freshness(Freshness.STALE, reason));
        }

        public void stale(String subject) {
            stale(subject, "Document revision changed: " + subject);
        }

        public void stale(String subject, String reason) {
            entries.replaceAll((id, e) ->
                    (e.subject().equals(subject) || subject.equals(e.facts().get("path")))
                            ? e.freshness(Freshness.STALE, reason)
                            : e);
        }

        public List<AgentEvidence> current(Kind kind) {
            return entries.values().stream()
                    .filter(e -> e.kind() == kind && e.freshness() == Freshness.CURRENT)
                    .toList();
        }

        public AgentEvidence get(String id) {
            return entries.get(id);
        }

        public List<AgentEvidence> entries() {
            return List.copyOf(entries.values());
        }

        public JsonNode toJson() {
            var out = new ObjectMapper().createArrayNode();
            entries.values().forEach(e -> out.add(e.toJson()));
            return out;
        }

        public void restore(JsonNode data) {
            if (!entries.isEmpty() || !data.isArray() || data.size() > 768)
                throw new IllegalArgumentException("Invalid evidence checkpoint");
            for (var e : data) {
                String id = e.path("id").asText();
                if (!id.matches("E[1-9][0-9]{0,8}") || entries.containsKey(id))
                    throw new IllegalArgumentException("Invalid evidence identity");
                sequence = Math.max(sequence, Integer.parseInt(id.substring(1)));
                Map<String, String> facts = new LinkedHashMap<>();
                e.path("facts")
                        .fields()
                        .forEachRemaining(f -> facts.put(
                                f.getKey(), AgentContext.bounded(f.getValue().asText(), 500)));
                if (facts.size() > 20) throw new IllegalArgumentException("Evidence details exceed limit");
                entries.put(
                        id,
                        new AgentEvidence(
                                id,
                                Kind.valueOf(e.path("kind").asText()),
                                AgentContext.bounded(e.path("subject").asText(), 1000),
                                e.path("revision").asText(),
                                e.path("generation").asLong(),
                                Freshness.HISTORICAL,
                                Strength.valueOf(e.path("strength").asText()),
                                e.path("source").asText(),
                                facts,
                                "Session restored; reobserve current state"));
            }
        }
    }
}
