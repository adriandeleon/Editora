package com.editora.agent.runtime;

import java.util.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import static com.editora.agent.runtime.AgentEvidence.*;

/** Concrete completion claims select facts; arbitrary model wording is never a verified statement. */
public final class AgentCompletion {
    public enum ClaimKind {
        FILE_CHANGED,
        FILE_CREATED,
        SYMBOL_EXISTS,
        TEST_ADDED,
        TEST_PASSED,
        TEST_COUNT,
        VALIDATION,
        DIAGNOSTICS_CLEAN,
        ALL_CALLERS_UPDATED
    }

    public record Claim(ClaimKind kind, String subject, String evidence, Long count) {}

    public record Checked(Claim claim, Strength support, String statement, String reason) {}

    public record Result(boolean accepted, List<Checked> claims, ObjectNode data, String rendered) {}

    private AgentCompletion() {}

    public static List<Checked> check(List<Claim> claims, Ledger ledger) {
        var checked = new ArrayList<Checked>();
        for (var claim : claims) {
            var e = ledger.get(claim.evidence());
            boolean current = e != null && e.freshness() == Freshness.CURRENT;
            String statement = "";
            if (current && claim.subject().equals(e.subject())) {
                statement = switch (claim.kind()) {
                    case FILE_CHANGED -> e.kind() == Kind.FILE_CHANGED ? "Changed " + quote(e.subject()) : "";
                    case FILE_CREATED -> e.kind() == Kind.FILE_CREATED ? "Created " + quote(e.subject()) : "";
                    case SYMBOL_EXISTS ->
                        e.kind() == Kind.SYMBOL_EXISTS
                                ? "Observed symbol " + quote(e.subject()) + " (" + e.source() + ")"
                                : "";
                    case TEST_ADDED ->
                        e.kind() == Kind.TEST_ADDED
                                ? "Added and executed test " + quote(e.subject())
                                        + "; detecting the old failure remains unproven."
                                : "";
                    case TEST_PASSED -> e.kind() == Kind.TEST_PASSED ? "Test report passed: " + quote(e.subject()) : "";
                    case TEST_COUNT ->
                        e.kind() == Kind.VALIDATION_RESULT
                                        && Objects.equals(
                                                String.valueOf(claim.count()),
                                                e.facts().get("passedTests"))
                                ? e.facts().get("passedTests") + " tests passed in " + quote(e.subject()) + "."
                                : "";
                    case VALIDATION -> e.kind() == Kind.BUILD_PASSED ? "Validation passed: " + quote(e.subject()) : "";
                    case DIAGNOSTICS_CLEAN ->
                        e.kind() == Kind.DIAGNOSTICS_CURRENT
                                        && "0".equals(e.facts().get("errors"))
                                ? "Current diagnostics reported zero errors for " + quote(e.subject()) + "."
                                : "";
                    // A reference query is scoped and often unversioned. It cannot prove whole-program completeness.
                    case ALL_CALLERS_UPDATED -> "";
                };
            }
            checked.add(new Checked(
                    claim,
                    statement.isEmpty() ? Strength.UNVERIFIED : e.strength(),
                    statement,
                    statement.isEmpty()
                            ? "No matching current evidence supports this concrete claim"
                            : "Bound to " + e.id()));
        }
        return List.copyOf(checked);
    }

    public static Result assemble(
            AgentTaskContract contract, Ledger ledger, List<Claim> claims, String interpretation) {
        var checked = check(claims, ledger);
        boolean accepted = contract.complete();
        var json = new ObjectMapper();
        var data = json.createObjectNode().put("accepted", accepted).put("coverage", "HEURISTIC_NOT_EXHAUSTIVE");
        data.set("requirements", contract.toJson().path("requirements"));
        var changes = data.putArray("changes");
        var paths = new TreeSet<String>();
        for (var kind : List.of(Kind.FILE_CHANGED, Kind.FILE_CREATED))
            for (var e : ledger.current(kind)) if (paths.add(e.subject())) changes.add(e.toJson());
        var validations = data.putArray("validation");
        ledger.current(Kind.VALIDATION_RESULT).forEach(e -> validations.add(e.toJson()));
        var remaining = data.putArray("remaining");
        for (var r : contract.requirements())
            if (r.state() != AgentTaskContract.State.SATISFIED && r.state() != AgentTaskContract.State.SUPERSEDED)
                remaining.add(r.id() + " " + r.check() + ": " + r.reason());
        var requested = data.putArray("claims");
        for (var c : checked)
            requested
                    .addObject()
                    .put("kind", c.claim().kind().name())
                    .put("subject", c.claim().subject())
                    .put("evidence", c.claim().evidence())
                    .put("support", c.support().name())
                    .put("statement", c.statement())
                    .put("reason", c.reason());
        data.put(
                "unsupportedClaims",
                checked.stream()
                        .filter(c -> c.support() == Strength.UNVERIFIED || c.support() == Strength.UNKNOWN)
                        .count());
        data.put("filesChanged", paths.size());
        StringBuilder text = new StringBuilder(
                accepted ? "Current completion evidence:\n" : "Acceptance incomplete. Current evidence:\n");
        if (!paths.isEmpty()) {
            text.append(paths.size())
                    .append(" files with tracked document changes: ")
                    .append(String.join(
                            ", ",
                            paths.stream().limit(12).map(AgentCompletion::quote).toList()))
                    .append(paths.size() > 12 ? " (first 12 shown).\n" : ".\n");
        }
        var inspected = new TreeSet<String>();
        ledger.current(Kind.FILE_READ).forEach(e -> inspected.add(e.subject()));
        var inspectedData = data.putArray("inspectedFiles");
        inspected.forEach(inspectedData::add);
        if (!inspected.isEmpty()
                && contract.requirements().stream()
                        .anyMatch(r -> r.check() == AgentTaskContract.Check.INSPECTED
                                || r.check() == AgentTaskContract.Check.TRACE_INSPECTED))
            text.append("Inspected ")
                    .append(inspected.size())
                    .append(" files: ")
                    .append(String.join(
                            ", ",
                            inspected.stream()
                                    .limit(12)
                                    .map(AgentCompletion::quote)
                                    .toList()))
                    .append(inspected.size() > 12 ? " (first 12 shown).\n" : ".\n");
        for (var e : ledger.current(Kind.VALIDATION_RESULT))
            text.append(quote(e.subject()))
                    .append(": ")
                    .append(e.facts().get("passedTests"))
                    .append(" passed, ")
                    .append(e.facts().get("failed"))
                    .append(" failed, ")
                    .append(e.facts().get("skipped"))
                    .append(" skipped.\n");
        for (var e : ledger.current(Kind.TEST_ADDED).stream().limit(12).toList())
            text.append("New executed test: ").append(quote(e.subject())).append(".\n");
        for (var c : checked)
            if (!c.statement().isEmpty()) text.append(c.statement()).append('\n');
        for (var r : contract.requirements())
            if (r.state() != AgentTaskContract.State.SUPERSEDED)
                text.append(r.state() == AgentTaskContract.State.SATISFIED ? "✓ " : "○ ")
                        .append(r.id())
                        .append(' ')
                        .append(r.check())
                        .append(": ")
                        .append(r.reason())
                        .append('\n');
        if (!ledger.current(Kind.TEST_ADDED).isEmpty())
            text.append("The new tests ran; whether they detect the old failure has not been proven.\n");
        if (contract.requirements().stream()
                        .anyMatch(r -> r.check() == AgentTaskContract.Check.INSPECTED
                                || r.check() == AgentTaskContract.Check.TRACE_INSPECTED)
                && !interpretation.isBlank())
            text.append("\nAgent interpretation (not independently verified):\n")
                    .append(quoteInterpretation(interpretation))
                    .append('\n');
        if (contract.requirements().isEmpty())
            text.append("No automatic acceptance guard was derived.\nAgent response (unverified):\n")
                    .append(quoteInterpretation(interpretation));
        text.append(
                "\nThese checks cover observed work; they do not prove every behavior or exhaustively interpret the request.");
        return new Result(accepted, checked, data, text.toString());
    }

    private static String quoteInterpretation(String value) {
        return AgentContext.bounded(value, 6000)
                .lines()
                .map(line -> "> " + line)
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private static String quote(String value) {
        return "`" + value.replace('`', '\'').replace('\n', ' ').replace('\r', ' ') + "`";
    }
}
