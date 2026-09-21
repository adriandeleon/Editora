package com.editora.agent.runtime;

import java.util.*;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** User messages are immutable authority; interpretations are bounded, explicitly heuristic guards. */
public final class AgentTaskContract {
    public enum Type {
        BEHAVIOR,
        CODE_CHANGE,
        TEST,
        REFACTOR,
        API,
        DOCUMENTATION,
        CONFIGURATION,
        INVESTIGATION,
        VALIDATION,
        USER_CONSTRAINT
    }

    public enum Provenance {
        USER_EXPLICIT,
        USER_IMPLIED,
        PROJECT_INSTRUCTION,
        SKILL,
        AGENT_DERIVED
    }

    public enum State {
        UNASSESSED,
        IN_PROGRESS,
        EVIDENCE_PENDING,
        SATISFIED,
        PARTIALLY_SATISFIED,
        BLOCKED,
        NOT_SATISFIED,
        SUPERSEDED
    }

    public enum Check {
        CHANGE,
        TEST_ADDED_EXECUTED,
        TEST_CHANGED_EXECUTED,
        DOCUMENTATION_CHANGED,
        VALIDATED,
        TESTS_VALIDATED,
        TARGETED_TEST_VALIDATED,
        CHECKED,
        OLD_FAILURE_PROVEN,
        INSPECTED,
        TRACE_INSPECTED,
        NO_TEST_CHANGES,
        NO_DOCUMENTATION_CHANGES,
        JAVA_ONLY,
        NO_CHANGES,
        OBSERVED_CALLERS_CHANGED,
        NO_REFERENCES,
        MANUAL
    }

    public record UserMessage(String id, String text) {}

    public record Requirement(
            String id,
            Type type,
            Provenance provenance,
            String userMessage,
            String text,
            Check check,
            State state,
            List<String> evidence,
            String reason) {
        public Requirement {
            evidence = List.copyOf(evidence);
        }

        Requirement assessed(State next, List<String> links, String why) {
            return new Requirement(id, type, provenance, userMessage, text, check, next, links, why);
        }
    }

    private final List<UserMessage> messages = new ArrayList<>();
    private final Map<String, Requirement> requirements = new LinkedHashMap<>();
    private final List<String> interpretationWarnings = new ArrayList<>();
    private int sequence;

    /** The only ingress for USER_EXPLICIT provenance. Never pass retrieved context to this method. */
    public void user(String text) {
        if (messages.size() >= 64
                || messages.stream().mapToInt(m -> m.text().length()).sum() + text.length() > 150_000)
            throw new IllegalStateException("Task contract limit reached; start a new session");
        var message = new UserMessage("U" + (messages.size() + 1), text);
        messages.add(message);
        String lower = text.toLowerCase(Locale.ROOT);
        if (matches(lower, "\\b(?:except|instead|unless)\\b"))
            interpretationWarnings.add(message.id() + ": Scope qualification needs user review");
        var correction = Pattern.compile(
                        "(?i)^\\s*(?:remove|drop|cancel|correct|replace)\\s+(?:requirement\\s+)?(R[1-9][0-9]{0,2})\\b")
                .matcher(text);
        if (correction.find()) {
            var original = requirements.get(correction.group(1));
            if (original != null && original.state() != State.SUPERSEDED)
                requirements.put(
                        original.id(),
                        original.assessed(
                                State.SUPERSEDED, List.of(), "Corrected by actual user message " + message.id()));
        }
        boolean noTests = matches(
                lower,
                "(?:do not|don't|don’t|never|no longer)\\s+(?:add|modify|change|edit|update|write)\\s+(?:the\\s+)?tests?");
        boolean noDocs = matches(
                lower,
                "(?:do not|don't|don’t|never|no longer)\\s+(?:add|modify|change|edit|update|write)\\s+(?:the\\s+)?(?:documentation|docs|readme)");
        boolean readOnly = matches(
                lower, "(?:do not|don't|don’t) (?:edit|modify|change)(?: files| code| anything|\\.|$)|read.only");
        if (noTests) {
            supersedeType(Type.TEST, "Superseded by " + message.id());
            add(Type.USER_CONSTRAINT, Provenance.USER_EXPLICIT, message.id(), text, Check.NO_TEST_CHANGES);
        }
        if (noDocs) {
            supersedeCheck(Check.DOCUMENTATION_CHANGED, "Superseded by " + message.id());
            add(Type.USER_CONSTRAINT, Provenance.USER_EXPLICIT, message.id(), text, Check.NO_DOCUMENTATION_CHANGES);
        }
        if (readOnly) {
            for (var r : List.copyOf(requirements.values()))
                if (Set.of(
                                Check.CHANGE,
                                Check.TEST_ADDED_EXECUTED,
                                Check.TEST_CHANGED_EXECUTED,
                                Check.DOCUMENTATION_CHANGED)
                        .contains(r.check()))
                    requirements.put(r.id(), r.assessed(State.SUPERSEDED, List.of(), "Superseded by " + message.id()));
            add(Type.USER_CONSTRAINT, Provenance.USER_EXPLICIT, message.id(), text, Check.NO_CHANGES);
        }
        if (matches(lower, "only (?:change|modify|edit) (?:the )?java|(?:change|modify|edit) only (?:the )?java")) {
            supersedeCheck(Check.DOCUMENTATION_CHANGED, "Scope narrowed by " + message.id());
            add(Type.USER_CONSTRAINT, Provenance.USER_EXPLICIT, message.id(), text, Check.JAVA_ONLY);
        }
        if (!readOnly
                && !noTests
                && matches(lower, "\\b(?:add|write|create|include)\\b[^.!?\\n]{0,100}\\b(?:tests?|coverage)\\b")) {
            supersedeCheck(Check.NO_TEST_CHANGES, "Superseded by " + message.id());
            supersedeCheck(Check.NO_CHANGES, "Superseded by " + message.id());
            add(Type.TEST, Provenance.USER_EXPLICIT, message.id(), text, Check.TEST_ADDED_EXECUTED);
        } else if (!readOnly
                && !noTests
                && matches(lower, "\\b(?:updat(?:e|ing)|modify(?:ing)?|extend(?:ing)?)\\b[^.!?\\n]{0,80}\\btests?\\b"))
            add(Type.TEST, Provenance.USER_EXPLICIT, message.id(), text, Check.TEST_CHANGED_EXECUTED);
        if (!readOnly
                && !noDocs
                && matches(
                        lower,
                        "\\b(?:add|write|update|include|document)\\b[^.!?\\n]{0,100}\\b(?:documentation|readme|docs|guide)\\b")) {
            if (!lower.contains("only")) supersedeCheck(Check.JAVA_ONLY, "Scope expanded by " + message.id());
            supersedeCheck(Check.NO_CHANGES, "Superseded by " + message.id());
            add(Type.DOCUMENTATION, Provenance.USER_EXPLICIT, message.id(), text, Check.DOCUMENTATION_CHANGED);
        }
        if (!readOnly && matches(lower, "\\b(?:fix|implement|refactor|rename|repair)\\b")) {
            add(Type.CODE_CHANGE, Provenance.USER_EXPLICIT, message.id(), text, Check.CHANGE);
            add(
                    Type.VALIDATION,
                    Provenance.USER_IMPLIED,
                    message.id(),
                    "Validate the requested code change",
                    Check.VALIDATED);
        }
        if (matches(lower, "\\b(?:just|only)\\s+(?:run\\s+)?[a-z0-9_.$]+test(?:#[a-z0-9_$]+)?\\b"))
            add(Type.VALIDATION, Provenance.USER_EXPLICIT, message.id(), text, Check.TARGETED_TEST_VALIDATED);
        if (matches(lower, "\\b(?:mvn|maven)\\s+verify\\b"))
            add(Type.VALIDATION, Provenance.USER_EXPLICIT, message.id(), text, Check.CHECKED);
        else if (matches(lower, "\\b(?:run|rerun)\\b[^.!?\\n]{0,60}\\btests?\\b"))
            add(Type.VALIDATION, Provenance.USER_EXPLICIT, message.id(), text, Check.TESTS_VALIDATED);
        else if (matches(lower, "\\b(?:run|rerun)\\b[^.!?\\n]{0,60}\\b(?:validation|build|verify)\\b|\\bvalidate\\b"))
            add(Type.VALIDATION, Provenance.USER_EXPLICIT, message.id(), text, Check.VALIDATED);
        if (matches(lower, "\\bprove\\b[^.!?\\n]{0,120}\\b(?:old|original) (?:bug|failure)\\b"))
            add(Type.TEST, Provenance.USER_EXPLICIT, message.id(), text, Check.OLD_FAILURE_PROVEN);
        if (!readOnly && !lower.contains("except") && matches(lower, "\\b(?:all|every) (?:relevant )?callers?\\b"))
            add(Type.REFACTOR, Provenance.USER_EXPLICIT, message.id(), text, Check.OBSERVED_CALLERS_CHANGED);
        if (matches(lower, "\\bno references? to [A-Za-z_$][A-Za-z0-9_$]* remain\\b"))
            add(Type.USER_CONSTRAINT, Provenance.USER_EXPLICIT, message.id(), text, Check.NO_REFERENCES);
        if (readOnly || matches(lower, "\\b(?:explain|investigate|trace|find why|understand|inspect)\\b"))
            add(
                    Type.INVESTIGATION,
                    Provenance.USER_EXPLICIT,
                    message.id(),
                    text,
                    matches(lower, "\\btrace\\b|\\bexplain how\\b|\\bexecution path\\b")
                            ? Check.TRACE_INSPECTED
                            : Check.INSPECTED);
    }

    private static boolean matches(String text, String expression) {
        return Pattern.compile(expression).matcher(text).find();
    }

    private void supersedeCheck(Check check, String reason) {
        for (var r : List.copyOf(requirements.values()))
            if (r.check() == check) requirements.put(r.id(), r.assessed(State.SUPERSEDED, List.of(), reason));
    }

    private void supersedeType(Type type, String reason) {
        for (var r : List.copyOf(requirements.values()))
            if (r.type() == type) requirements.put(r.id(), r.assessed(State.SUPERSEDED, List.of(), reason));
    }

    private Requirement add(Type type, Provenance source, String user, String text, Check check) {
        for (var r : requirements.values()) {
            if (r.check() != check
                    || r.state() == State.SUPERSEDED
                    || !r.userMessage().equals(user)) continue;
            if (r.text().equals(text)) return r;
            if (r.provenance() == Provenance.USER_IMPLIED && source == Provenance.USER_EXPLICIT) {
                var explicit =
                        new Requirement(r.id(), type, source, user, text, check, r.state(), r.evidence(), r.reason());
                requirements.put(r.id(), explicit);
                return explicit;
            }
        }
        if (requirements.size() >= 64) throw new IllegalStateException("Task requirement limit reached");
        var r = new Requirement(
                "R" + ++sequence,
                type,
                source,
                user,
                text,
                check,
                State.UNASSESSED,
                List.of(),
                "Awaiting current evidence");
        requirements.put(r.id(), r);
        return r;
    }

    public void invalidateEvidence() {
        for (var r : List.copyOf(requirements.values()))
            if (r.state() != State.SUPERSEDED)
                requirements.put(
                        r.id(),
                        r.assessed(
                                State.EVIDENCE_PENDING,
                                List.of(),
                                "Observations changed; acceptance will be checked again"));
    }

    public Requirement derived(Type type, String text, Check check) {
        if (text.isBlank() || text.length() > 500)
            throw new IllegalArgumentException("Requirement text must be 1–500 characters");
        return add(type, Provenance.AGENT_DERIVED, "", text, check);
    }

    public void supersedeDerived(String id) {
        var r = require(id);
        if (r.provenance() != Provenance.AGENT_DERIVED)
            throw new IllegalArgumentException("Only a user correction may supersede a user requirement");
        requirements.put(id, r.assessed(State.SUPERSEDED, List.of(), "Agent revised a hypothesis"));
    }

    public Requirement require(String id) {
        var r = requirements.get(id);
        if (r == null) throw new IllegalArgumentException("Unknown requirement id");
        return r;
    }

    public void assess(String id, State state, List<String> evidence, String reason) {
        var r = require(id);
        if (r.state() == State.SUPERSEDED) return;
        if (state == State.SATISFIED && evidence.isEmpty())
            throw new IllegalArgumentException("Satisfaction requires evidence");
        requirements.put(id, r.assessed(state, evidence, reason));
    }

    public List<Requirement> requirements() {
        return List.copyOf(requirements.values());
    }

    public List<UserMessage> messages() {
        return List.copyOf(messages);
    }

    public boolean complete() {
        return requirements.values().stream()
                .allMatch(r -> r.state() == State.SATISFIED || r.state() == State.SUPERSEDED);
    }

    /** An actual user restriction on broad execution; repository/model text never reaches messages. */
    public boolean forbidsBroadValidation() {
        boolean forbidden = false;
        for (var message : messages) {
            String lower = message.text().toLowerCase(Locale.ROOT);
            if (matches(
                    lower,
                    "(?:do not|don't|don’t|never)\\s+run\\s+(?:the\\s+)?(?:full|entire)\\s+(?:test\\s+)?suite|(?:do not|don't|don’t|never)\\s+run\\s+all\\s+tests"))
                forbidden = true;
            else if (matches(
                    lower,
                    "\\b(?:run|rerun)\\s+(?:the\\s+)?(?:full|entire)\\s+(?:test\\s+)?suite\\b|\\bmvn\\s+verify\\b"))
                forbidden = false;
        }
        return forbidden;
    }

    public String reminder() {
        StringBuilder out = new StringBuilder(
                "Runtime task contract (heuristic coverage; original user messages remain authoritative):\n");
        for (var r : requirements.values())
            if (r.state() != State.SUPERSEDED)
                out.append(r.id())
                        .append(' ')
                        .append(r.check())
                        .append(' ')
                        .append(r.state())
                        .append('\n');
        interpretationWarnings.stream()
                .skip(Math.max(0, interpretationWarnings.size() - 3))
                .forEach(w -> out.append("Interpretation warning: ").append(w).append('\n'));
        if (forbidsBroadValidation())
            out.append("User constraint: use targeted validation; broad test/verify is denied.\n");
        return out.toString();
    }

    public ObjectNode toJson() {
        var json = new ObjectMapper();
        var out = json.createObjectNode().put("schemaVersion", 1).put("interpretation", "HEURISTIC_NOT_EXHAUSTIVE");
        var warnings = out.putArray("interpretationWarnings");
        interpretationWarnings.forEach(warnings::add);
        var users = out.putArray("userMessages");
        for (var m : messages) users.addObject().put("id", m.id()).put("text", m.text());
        var items = out.putArray("requirements");
        for (var r : requirements.values()) {
            var item = items.addObject()
                    .put("id", r.id())
                    .put("type", r.type().name())
                    .put("provenance", r.provenance().name())
                    .put("userMessage", r.userMessage())
                    .put("text", r.text())
                    .put("check", r.check().name())
                    .put("state", r.state().name())
                    .put("reason", r.reason());
            r.evidence().forEach(item.putArray("evidence")::add);
        }
        return out;
    }

    public void restore(JsonNode data) {
        if (!messages.isEmpty() || !requirements.isEmpty())
            throw new IllegalStateException("Restore requires an empty contract");
        if (data.path("schemaVersion").asInt() != 1
                || data.path("userMessages").size() > 64
                || data.path("requirements").size() > 64)
            throw new IllegalArgumentException("Invalid task contract checkpoint");
        for (var m : data.path("userMessages")) {
            if (!m.path("id").asText().equals("U" + (messages.size() + 1)))
                throw new IllegalArgumentException("Invalid user identity");
            messages.add(new UserMessage(m.path("id").asText(), m.path("text").asText()));
            if (matches(m.path("text").asText().toLowerCase(Locale.ROOT), "\\b(?:except|instead|unless)\\b"))
                interpretationWarnings.add(m.path("id").asText() + ": Scope qualification needs user review");
        }
        if (messages.stream().mapToInt(m -> m.text().length()).sum() > 150_000)
            throw new IllegalArgumentException("User history exceeds limit");
        for (var n : data.path("requirements")) {
            var source = Provenance.valueOf(n.path("provenance").asText());
            String user = n.path("userMessage").asText(), text = n.path("text").asText();
            if (source == Provenance.USER_EXPLICIT
                    && messages.stream()
                            .noneMatch(m -> m.id().equals(user) && m.text().equals(text)))
                throw new IllegalArgumentException("Explicit requirement must retain its exact user source");
            String id = n.path("id").asText();
            if (!id.matches("R[1-9][0-9]{0,2}") || requirements.containsKey(id))
                throw new IllegalArgumentException("Invalid requirement identity");
            sequence = Math.max(sequence, Integer.parseInt(id.substring(1)));
            var state = State.valueOf(n.path("state").asText()) == State.SUPERSEDED
                    ? State.SUPERSEDED
                    : State.EVIDENCE_PENDING;
            requirements.put(
                    id,
                    new Requirement(
                            id,
                            Type.valueOf(n.path("type").asText()),
                            source,
                            user,
                            text,
                            Check.valueOf(n.path("check").asText()),
                            state,
                            List.of(),
                            "Historical evidence requires refresh"));
        }
    }
}
