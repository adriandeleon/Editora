package com.editora.agent.runtime;

import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import static com.editora.agent.runtime.AgentEvidence.*;
import static com.editora.agent.runtime.AgentTaskContract.*;

/** Native evidence adapter and acceptance predicates. It grants neither permissions nor document leases. */
public final class AgentAcceptance {
    private final AgentWorkspace workspace;
    private final AgentDocuments documents;
    private final AgentAcceptanceRecipes recipes;
    private final AgentTaskContract contract = new AgentTaskContract();
    private final Ledger ledger = new Ledger();
    private final Map<String, String> leases = new LinkedHashMap<>();
    private final Map<String, Set<String>> newTestNames = new LinkedHashMap<>();
    private final Map<String, Set<String>> changedTestNames = new LinkedHashMap<>();
    private final Set<String> changed = new LinkedHashSet<>();

    private record Receipt(
            String beforeHash, String afterHash, boolean created, Set<String> tests, Set<String> updatedTests) {
        Receipt {
            tests = Set.copyOf(tests);
            updatedTests = Set.copyOf(updatedTests);
        }
    }

    private final Map<String, Receipt> receipts = new LinkedHashMap<>();
    private final Set<String> pendingReceipts = new LinkedHashSet<>();

    public boolean hasChanges() {
        return !changed.isEmpty();
    }

    private final Set<String> observedCallers = new LinkedHashSet<>();
    private final Map<String, Set<String>> referenceScopes = new LinkedHashMap<>();
    private List<AgentAcceptanceRecipes.Debt> evidenceDebt = List.of();
    private List<AgentCompletion.Claim> claims = List.of();
    private ObjectNode lastCompletion;
    private long verificationNanos;
    private long reconciliationNanos;
    private long declarationNanos;
    private int suppressedDrafts;
    private int checks, rejectedClaims, reconciliations;

    public AgentAcceptance(AgentWorkspace workspace, AgentDocuments documents) {
        this.workspace = workspace;
        this.documents = documents;
        this.recipes = new AgentAcceptanceRecipes(workspace);
    }

    public AgentTaskContract contract() {
        return contract;
    }

    public Ledger ledger() {
        return ledger;
    }

    public void user(String goal) {
        contract.user(goal);
        claims = List.of();
        lastCompletion = null;
        refreshDebt();
    }

    public String reminder() {
        StringBuilder out = new StringBuilder(contract.reminder());
        if (!evidenceDebt.isEmpty()) {
            out.append(
                    "Remaining acceptance evidence (runtime guidance; workspace recipes cannot grant permissions):\n");
            for (var debt : evidenceDebt.stream().limit(8).toList()) {
                out.append(debt.requirement())
                        .append(' ')
                        .append(debt.progress())
                        .append(": ")
                        .append(debt.missing());
                if (!debt.invalidatedBy().isBlank())
                    out.append("; stale because ").append(debt.invalidatedBy());
                if (!debt.steps().isEmpty())
                    out.append("; next ")
                            .append(debt.steps().getFirst().tool())
                            .append(" / ")
                            .append(debt.steps().getLast().tool());
                out.append('\n');
            }
        } else if (!contract.requirements().isEmpty() && contract.complete()) {
            out.append(
                    "All recognized requirements have current evidence. If the original goal is complete, finish now; do not rediscover satisfied guards.\n");
        }
        return out.toString();
    }

    public void read(AgentDocuments.Snapshot snapshot) {
        String path = path(snapshot.path());
        lease(path, snapshot.revision());
        ledger.record(Kind.FILE_READ, path, snapshot.revision(), Strength.SUPPORTED, "native_document", Map.of());
        refreshSavedState(path, snapshot);
        if (pendingReceipts.contains(path)) {
            var receipt = receipts.get(path);
            if (receipt.afterHash().equals(hash(snapshot.text()))) {
                pendingReceipts.remove(path);
                var names = AgentJavaDeclarations.methods(snapshot.text());
                newTestNames.put(
                        path,
                        receipt.tests().stream().filter(names::contains).collect(java.util.stream.Collectors.toSet()));
                changedTestNames.put(
                        path,
                        receipt.updatedTests().stream()
                                .filter(names::contains)
                                .collect(java.util.stream.Collectors.toSet()));
                ledger.record(
                        receipt.created() ? Kind.FILE_CREATED : Kind.FILE_CHANGED,
                        path,
                        snapshot.revision(),
                        Strength.SUPPORTED,
                        "historical_diff_refreshed_by_document_read",
                        Map.of("saved", Boolean.toString(!snapshot.dirty())));
            }
        }
    }

    private void refreshSavedState(String path, AgentDocuments.Snapshot snapshot) {
        for (var kind : List.of(Kind.FILE_CHANGED, Kind.FILE_CREATED))
            for (var e : ledger.current(kind))
                if (e.subject().equals(path)
                        && e.revision().equals(snapshot.revision())
                        && !Boolean.toString(!snapshot.dirty()).equals(e.facts().get("saved"))) {
                    var facts = new HashMap<>(e.facts());
                    facts.put("saved", Boolean.toString(!snapshot.dirty()));
                    ledger.record(e.kind(), e.subject(), e.revision(), e.strength(), e.source(), facts);
                }
    }

    private void lease(String path, String revision) {
        if (leases.size() >= 256 && !leases.containsKey(path))
            throw new IllegalStateException("Acceptance file limit reached");
        String old = leases.put(path, revision);
        if (old != null && !old.equals(revision)) ledger.invalidateDocumentEdit(path);
    }

    public void changed(String before, AgentDocuments.Snapshot snapshot, boolean created) throws Exception {
        String path = path(snapshot.path());
        lastCompletion = null;
        ledger.invalidateDocumentEdit(path);
        lease(path, snapshot.revision());
        boolean different = created || !before.equals(snapshot.text());
        if (!different) {
            changed.remove(path);
            newTestNames.remove(path);
            changedTestNames.remove(path);
            receipts.remove(path);
            pendingReceipts.remove(path);
            return;
        }
        changed.add(path);
        ledger.record(
                created ? Kind.FILE_CREATED : Kind.FILE_CHANGED,
                path,
                snapshot.revision(),
                Strength.SUPPORTED,
                "document_transaction",
                Map.of(
                        "test",
                        Boolean.toString(testFile(path)),
                        "documentation",
                        Boolean.toString(documentation(path)),
                        "saved",
                        Boolean.toString(!snapshot.dirty())));
        if (testFile(path) && path.endsWith(".java")) {
            var added = new LinkedHashSet<String>();
            long started = System.nanoTime();
            var methods = AgentJavaDeclarations.bodies(snapshot.text());
            var oldMethods = AgentJavaDeclarations.bodies(before);
            var updated = methods.entrySet().stream()
                    .filter(e ->
                            oldMethods.containsKey(e.getKey()) && !e.getValue().equals(oldMethods.get(e.getKey())))
                    .map(Map.Entry::getKey)
                    .collect(java.util.stream.Collectors.toSet());
            changedTestNames.put(path, updated);
            declarationNanos += System.nanoTime() - started;
            for (var method : methods.keySet()) if (!oldMethods.containsKey(method)) added.add(method);
            newTestNames.put(path, Set.copyOf(added));
        }
        receipts.put(
                path,
                new Receipt(
                        hash(before),
                        hash(snapshot.text()),
                        created,
                        newTestNames.getOrDefault(path, Set.of()),
                        changedTestNames.getOrDefault(path, Set.of())));
        pendingReceipts.remove(path);
    }

    public void executionStarted() {
        contract.invalidateEvidence();
        lastCompletion = null;
        ledger.invalidateExecution();
    }

    public void validation(ObjectNode result, List<AgentValidationReports.TestCase> cases) {
        String scope =
                result.path("system").asText() + " " + result.path("operation").asText() + " "
                        + result.path("module").asText();
        String module = result.path("module").asText(".");
        var tests = result.path("tests");
        Map<String, String> counts = Map.of(
                "tests",
                tests.path("tests").asText(),
                "failed",
                tests.path("failed").asText(),
                "skipped",
                tests.path("skipped").asText(),
                "passed",
                Boolean.toString(result.path("passed").asBoolean()),
                "scope",
                result.path("testScope").asText(),
                "passedTests",
                Integer.toString(tests.path("tests").asInt()
                        - tests.path("failed").asInt()
                        - tests.path("skipped").asInt()));

        for (var t : cases.stream()
                .sorted(java.util.Comparator.comparing((AgentValidationReports.TestCase t) -> {
                    var identity = AgentTestIdentity.from(t, module);
                    return !newTestNames.entrySet().stream()
                                    .anyMatch(e -> e.getValue().stream()
                                            .anyMatch(name -> identity.matchesSource(e.getKey(), name)))
                            && !changedTestNames.entrySet().stream()
                                    .anyMatch(e -> e.getValue().stream()
                                            .anyMatch(name -> identity.matchesSource(e.getKey(), name)));
                }))
                .limit(128)
                .toList()) {
            var test = AgentTestIdentity.from(t, module);
            String identity = test.evidenceSubject();
            String file = leases.keySet().stream()
                    .filter(path -> test.matchesSource(path, test.sourceMethod()))
                    .findFirst()
                    .orElse("");
            var facts = Map.of(
                    "path",
                    file,
                    "framework",
                    test.framework(),
                    "method",
                    test.sourceMethod(),
                    "report",
                    test.report());
            ledger.record(Kind.TEST_EXISTS, identity, "", Strength.SUPPORTED, "fresh_junit_report", facts);
            if (t.skipped()) continue;
            ledger.record(Kind.TEST_EXECUTED, identity, "", Strength.SUPPORTED, "fresh_junit_report", facts);
            if (!t.failed())
                ledger.record(Kind.TEST_PASSED, identity, "", Strength.SUPPORTED, "fresh_junit_report", facts);

            for (var entry : newTestNames.entrySet())
                if (entry.getValue().stream().anyMatch(name -> test.matchesSource(entry.getKey(), name)))
                    ledger.record(
                            Kind.TEST_ADDED,
                            identity,
                            leases.getOrDefault(entry.getKey(), ""),
                            Strength.SUPPORTED,
                            "new_declaration_and_fresh_test_report",
                            Map.of("path", entry.getKey(), "oldFailureDetection", "UNVERIFIED"));
            for (var entry : changedTestNames.entrySet())
                if (entry.getValue().stream().anyMatch(name -> test.matchesSource(entry.getKey(), name)))
                    ledger.record(
                            Kind.SYMBOL_CHANGED,
                            identity,
                            leases.getOrDefault(entry.getKey(), ""),
                            Strength.SUPPORTED,
                            "changed_test_body_and_fresh_report",
                            Map.of("path", entry.getKey()));
        }
        ledger.record(Kind.VALIDATION_RESULT, scope, "", Strength.SUPPORTED, "fresh_junit_reports", counts);
        if (result.path("passed").asBoolean())
            ledger.record(Kind.BUILD_PASSED, scope, "", Strength.SUPPORTED, "structured_validation", counts);
    }

    public void reviewed() {
        for (var path : changed)
            ledger.record(
                    Kind.DIFF_REVIEWED,
                    path,
                    leases.getOrDefault(path, ""),
                    Strength.SUPPORTED,
                    "native_diff",
                    Map.of());
    }

    public void semantic(String operation, JsonNode result, AgentDocuments.Snapshot source) {
        semantic(operation, result, source, null);
    }

    public void semantic(String operation, JsonNode result, AgentDocuments.Snapshot source, JsonNode arguments) {
        String path = path(source.path());
        lease(path, source.revision());
        if (operation.equals("symbols")) symbols(result.path("items"), path, source.revision(), 0);
        if (operation.equals("references") && !result.path("truncated").asBoolean()) {
            String scope = path + ":"
                    + (arguments == null
                            ? "default"
                            : arguments.path("line").asInt(-1) + ":"
                                    + arguments.path("character").asInt(-1));
            var callers = new LinkedHashSet<String>();
            for (var item : result.path("items")) {
                try {
                    callers.add(path(workspace.resolve(
                            Path.of(java.net.URI.create(item.path("uri").asText()))
                                    .toString())));
                } catch (Exception external) {
                    /* External locations do not establish a complete workspace scope. */
                }
            }
            referenceScopes.put(scope, Set.copyOf(callers));
            observedCallers.clear();
            referenceScopes.values().forEach(observedCallers::addAll);
            ledger.record(
                    Kind.SEMANTIC_REFERENCE,
                    scope,
                    source.revision(),
                    Strength.PARTIALLY_SUPPORTED,
                    "unversioned_lsp_response",
                    Map.of("path", path, "observedFiles", Integer.toString(callers.size())));
        }
    }

    private void symbols(JsonNode items, String path, String revision, int depth) {
        if (depth > 12 || !items.isArray()) return;
        for (var item : items) {
            if (item.hasNonNull("name"))
                ledger.record(
                        Kind.SYMBOL_EXISTS,
                        path + "#" + item.path("name").asText(),
                        revision,
                        Strength.PARTIALLY_SUPPORTED,
                        "unversioned_lsp_response",
                        Map.of("path", path));
            symbols(item.path("children"), path, revision, depth + 1);
        }
    }

    public void diagnostics(String path, AgentDocuments.Diagnostics d) {
        if (d.available() && "CURRENT".equals(d.freshness()))
            ledger.record(
                    Kind.DIAGNOSTICS_CURRENT,
                    path,
                    leases.getOrDefault(path, ""),
                    Strength.SUPPORTED,
                    "versioned_diagnostics",
                    Map.of("errors", Integer.toString(d.errors()), "serverGeneration", Long.toString(d.generation())));
    }

    public void search(String query, Path scope, JsonNode result) {
        var observedPaths = new LinkedHashSet<String>();
        for (var item : result.path("matches")) {
            String observed = item.path("path").asText();
            if (!observedPaths.add(observed)) continue;
            if (observedPaths.size() > 8) break;
            try {
                workspace.resolve(observed);
                ledger.record(
                        Kind.SEARCH_OBSERVATION,
                        observed,
                        "",
                        Strength.PARTIALLY_SUPPORTED,
                        "bounded_native_workspace_search",
                        Map.of("query", AgentContext.bounded(query, 200)));
            } catch (Exception invalid) {
                // A malformed location is not native evidence.
            }
        }
        if (scope.equals(workspace.root())
                && !result.path("truncated").asBoolean()
                && result.path("matches").isArray()
                && result.path("matches").isEmpty())
            ledger.record(
                    Kind.SEARCH_ABSENCE,
                    query,
                    "",
                    Strength.PARTIALLY_SUPPORTED,
                    "bounded_native_workspace_search",
                    Map.of("scope", ".", "case", "literal"));
    }

    public void reconcile(AgentCancellation c) throws Exception {
        long start = System.nanoTime();
        try {
            reconcileCurrent(c);
        } finally {
            reconciliationNanos += System.nanoTime() - start;
        }
    }

    private void reconcileCurrent(AgentCancellation c) throws Exception {
        reconciliations++;
        var beforeStates = documents.states(c);
        boolean currentFiles = pendingReceipts.isEmpty();
        for (var entry : List.copyOf(leases.entrySet())) {
            c.check();
            try {
                var now = documents.read(workspace.resolve(entry.getKey()), c);
                if (!entry.getValue().equals(now.revision())) {
                    currentFiles = false;
                    ledger.invalidateDocumentEdit(entry.getKey());
                } else refreshSavedState(entry.getKey(), now);
            } catch (java.util.concurrent.CancellationException cancelled) {
                throw cancelled;
            } catch (Exception unavailable) {
                currentFiles = false;
                ledger.invalidateDocumentEdit(entry.getKey());
            }
        }
        for (var evidence : ledger.current(Kind.DIAGNOSTICS_CURRENT)) {
            c.check();
            try {
                var now = documents.diagnostics(workspace.resolve(evidence.subject()), c);
                if (!now.available()
                        || !"CURRENT".equals(now.freshness())
                        || !Integer.toString(now.errors())
                                .equals(evidence.facts().get("errors"))
                        || !Long.toString(now.generation())
                                .equals(evidence.facts().get("serverGeneration"))) ledger.invalidate(evidence.id());
            } catch (java.util.concurrent.CancellationException cancelled) {
                throw cancelled;
            } catch (Exception unavailable) {
                ledger.invalidate(evidence.id());
            }
        }
        if (!beforeStates.equals(documents.states(c))) {
            currentFiles = false;
            ledger.invalidateExecution();
            leases.keySet().forEach(ledger::stale);
        }
        var deltas = new ArrayList<AgentEvidence>();
        deltas.addAll(ledger.current(Kind.FILE_CHANGED));
        deltas.addAll(ledger.current(Kind.FILE_CREATED));
        var builds = ledger.current(Kind.BUILD_PASSED);
        var added = ledger.current(Kind.TEST_ADDED);
        for (var r : contract.requirements()) {
            if (r.state() == State.SUPERSEDED) continue;
            List<String> links = new ArrayList<>();
            boolean passed =
                    switch (r.check()) {
                        case CHANGE ->
                            collect(
                                    deltas.stream()
                                            .filter(e -> !testFile(e.subject()) && !documentation(e.subject()))
                                            .toList(),
                                    links);
                        case TEST_ADDED_EXECUTED -> collect(added, links) && collect(builds, links);
                        case TEST_CHANGED_EXECUTED -> {
                            var tests = new ArrayList<>(ledger.current(Kind.SYMBOL_CHANGED));
                            tests.addAll(added);
                            yield collect(tests, links) && collect(builds, links);
                        }
                        case DOCUMENTATION_CHANGED -> {
                            var docs = deltas.stream()
                                    .filter(e -> documentation(e.subject()))
                                    .toList();
                            yield collect(docs, links) && namedDocumentationCovered(r.text(), docs);
                        }
                        case VALIDATED -> collect(builds, links);
                        case TESTS_VALIDATED ->
                            collect(builds, links) && collect(ledger.current(Kind.TEST_EXECUTED), links);
                        case TARGETED_TEST_VALIDATED ->
                            collect(
                                            builds.stream()
                                                    .filter(e -> e.subject().contains("TARGETED_TEST")
                                                            && targetedTestCovered(
                                                                    r.text(),
                                                                    e.facts().get("scope")))
                                                    .toList(),
                                            links)
                                    && collect(
                                            ledger.current(Kind.TEST_EXECUTED).stream()
                                                    .filter(e -> targetedCaseExecuted(r.text(), e.subject()))
                                                    .toList(),
                                            links);
                        case CHECKED ->
                            collect(
                                    builds.stream()
                                            .filter(e -> e.subject().startsWith("MAVEN CHECK "))
                                            .toList(),
                                    links);
                        case OLD_FAILURE_PROVEN ->
                            collect(ledger.current(Kind.TEST_PROVEN_TO_DETECT_OLD_FAILURE), links);
                        case INSPECTED -> collect(ledger.current(Kind.FILE_READ), links);
                        case TRACE_INSPECTED -> {
                            var reads = ledger.current(Kind.FILE_READ);
                            boolean multiple = reads.stream()
                                            .map(AgentEvidence::subject)
                                            .distinct()
                                            .count()
                                    >= 2;
                            boolean navigation = collect(
                                            ledger.current(Kind.SEMANTIC_REFERENCE).stream()
                                                    .filter(e -> reads.stream()
                                                            .anyMatch(read -> read.subject()
                                                                    .equals(e.facts()
                                                                            .get("path"))))
                                                    .toList(),
                                            links)
                                    || collect(
                                            ledger.current(Kind.SEARCH_OBSERVATION).stream()
                                                    .filter(e -> reads.stream()
                                                            .anyMatch(read -> read.subject()
                                                                    .equals(e.subject())))
                                                    .toList(),
                                            links);
                            yield multiple && navigation && collect(reads, links);
                        }
                        case NO_TEST_CHANGES ->
                            constraint(currentFiles && changed.stream().noneMatch(AgentAcceptance::testFile), r, links);
                        case NO_DOCUMENTATION_CHANGES ->
                            constraint(
                                    currentFiles && changed.stream().noneMatch(AgentAcceptance::documentation),
                                    r,
                                    links);
                        case JAVA_ONLY ->
                            constraint(currentFiles && changed.stream().allMatch(p -> p.endsWith(".java")), r, links);
                        case NO_CHANGES -> constraint(currentFiles && deltas.isEmpty() && changed.isEmpty(), r, links);
                        case OBSERVED_CALLERS_CHANGED ->
                            !observedCallers.isEmpty()
                                    && collect(ledger.current(Kind.SEMANTIC_REFERENCE), links)
                                    && changed.containsAll(observedCallers)
                                    && collect(builds, links)
                                    && collect(deltas, links);
                        case NO_REFERENCES ->
                            collect(
                                    ledger.current(Kind.SEARCH_ABSENCE).stream()
                                            .filter(e -> e.subject().equals(requestedReference(r.text())))
                                            .toList(),
                                    links);
                        case MANUAL -> false;
                    };
            contract.assess(
                    r.id(),
                    passed ? State.SATISFIED : links.isEmpty() ? State.EVIDENCE_PENDING : State.PARTIALLY_SATISFIED,
                    links,
                    passed
                            ? scope(r.check())
                            : "Current evidence does not establish " + r.check()
                                    + "; inspect task_contract and task_evidence");
        }
        refreshDebt();
    }

    private void refreshDebt() {
        evidenceDebt = contract.requirements().stream()
                .filter(r -> r.state() != State.SATISFIED && r.state() != State.SUPERSEDED)
                .map(r -> recipes.debt(r, ledger, changed, newTestNames, changedTestNames, observedCallers))
                .toList();
    }

    private com.fasterxml.jackson.databind.node.ArrayNode debtJson() {
        var mapper = new ObjectMapper();
        var out = mapper.createArrayNode();
        evidenceDebt.forEach(debt -> out.add(debt.toJson(mapper)));
        return out;
    }

    private boolean constraint(boolean passed, Requirement r, List<String> links) {
        if (passed)
            links.add(
                    ledger.record(Kind.CONSTRAINT_CHECK, r.id(), "", Strength.SUPPORTED, "current_agent_diff", Map.of())
                            .id());
        return passed;
    }

    private static boolean collect(List<AgentEvidence> evidence, List<String> ids) {
        evidence.stream().limit(32).forEach(e -> ids.add(e.id()));
        return !evidence.isEmpty();
    }

    private static boolean namedDocumentationCovered(String request, List<AgentEvidence> docs) {
        var matcher = java.util.regex.Pattern.compile(
                        "(?<![A-Za-z0-9_./-])([A-Za-z0-9_./-]+\\.(?:md|rst))(?![A-Za-z0-9_])",
                        java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(request);
        while (matcher.find()) {
            String requested = matcher.group(1);
            if (docs.stream()
                    .noneMatch(e -> e.subject().equals(requested) || e.subject().endsWith("/" + requested)))
                return false;
        }
        return true;
    }

    private static boolean targetedTestCovered(String request, String scope) {
        var matcher = java.util.regex.Pattern.compile(
                        "(?i)\\b(?:just|only)\\s+(?:run\\s+)?([A-Za-z_$][A-Za-z0-9_.$]*Test(?:#[A-Za-z_$][A-Za-z0-9_$]*)?)\\b")
                .matcher(request);
        return matcher.find() && matcher.group(1).equalsIgnoreCase(scope);
    }

    private static boolean targetedCaseExecuted(String request, String subject) {
        var matcher = java.util.regex.Pattern.compile(
                        "(?i)\\b(?:just|only)\\s+(?:run\\s+)?([A-Za-z_$][A-Za-z0-9_.$]*Test)(?:#([A-Za-z_$][A-Za-z0-9_$]*))?\\b")
                .matcher(request);
        if (!matcher.find()) return false;
        int marker = subject.indexOf('#');
        if (marker < 0) return false;
        String testClass = matcher.group(1);
        String reportClass = subject.substring(0, marker);
        if (!(reportClass.equalsIgnoreCase(testClass)
                || reportClass.toLowerCase(Locale.ROOT).endsWith("." + testClass.toLowerCase(Locale.ROOT))))
            return false;
        return matcher.group(2) == null
                || subject.substring(marker + 1).equalsIgnoreCase(matcher.group(2))
                || subject.substring(marker + 1)
                        .toLowerCase(Locale.ROOT)
                        .startsWith(matcher.group(2).toLowerCase(Locale.ROOT) + "[")
                || subject.substring(marker + 1)
                        .toLowerCase(Locale.ROOT)
                        .startsWith(matcher.group(2).toLowerCase(Locale.ROOT) + "(");
    }

    private static String requestedReference(String request) {
        var matcher = java.util.regex.Pattern.compile("(?i)\\bno references? to ([A-Za-z_$][A-Za-z0-9_$]*) remain\\b")
                .matcher(request);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String scope(Check check) {
        return switch (check) {
            case TEST_ADDED_EXECUTED ->
                "New test declaration matched a fresh executed test; old-failure detection unproven";
            case TEST_CHANGED_EXECUTED -> "Test source changed and current tests executed; test quality unproven";
            case OBSERVED_CALLERS_CHANGED ->
                "All observed workspace reference files changed and validation passed; whole-program coverage unproven";
            case INSPECTED -> "Current files inspected; causal interpretation remains an inference";
            case TRACE_INSPECTED ->
                "Multiple current files and a navigation observation inspected; causal interpretation remains an inference";
            case NO_REFERENCES ->
                "No literal matches in the bounded accessible workspace search; semantic and external scope unproven";
            case CHANGE -> "Production diff observed; behavior requires relevant validation";
            default -> "Supported by current " + check + " evidence";
        };
    }

    public AgentCompletion.Result finish(String draft, AgentCancellation c) throws Exception {
        long start = System.nanoTime();
        try {
            reconcile(c);
            checks++;
            if (!draft.isBlank()) suppressedDrafts++;
            var result = AgentCompletion.assemble(contract, ledger, claims, draft);
            rejectedClaims += result.data().path("unsupportedClaims").asInt();
            var data = result.data();
            data.set("evidenceDebt", debtJson());
            boolean taskIncomplete = evidenceDebt.stream()
                    .anyMatch(d -> d.progress() == AgentAcceptanceRecipes.Progress.TASK_INCOMPLETE);
            boolean userAction = evidenceDebt.stream()
                    .anyMatch(d -> d.progress() == AgentAcceptanceRecipes.Progress.USER_ACTION_REQUIRED);
            data.put(
                    "completionState",
                    result.accepted()
                            ? "ACCEPTED"
                            : taskIncomplete
                                    ? "TASK_INCOMPLETE"
                                    : userAction ? "USER_ACTION_REQUIRED" : "EVIDENCE_INCOMPLETE");
            String rendered = result.accepted()
                    ? result.rendered()
                    : (taskIncomplete
                                    ? "Requested work is still incomplete.\n"
                                    : userAction
                                            ? "User input is required before acceptance can finish.\n"
                                            : "The work may be complete, but current acceptance evidence is incomplete.\n")
                            + result.rendered();
            lastCompletion = data;
            return new AgentCompletion.Result(result.accepted(), result.claims(), data, rendered);
        } finally {
            verificationNanos += System.nanoTime() - start;
        }
    }

    public ObjectNode metrics() {
        var out = new ObjectMapper()
                .createObjectNode()
                .put("checks", checks)
                .put("verificationNanos", verificationNanos)
                .put("reconciliationNanos", reconciliationNanos)
                .put("javaDeclarationNanos", declarationNanos)
                .put("unstructuredCandidatesHandled", suppressedDrafts)
                .put("reconciliations", reconciliations)
                .put("unsupportedStructuredClaims", rejectedClaims)
                .put(
                        "taskIncomplete",
                        evidenceDebt.stream()
                                .filter(d -> d.progress() == AgentAcceptanceRecipes.Progress.TASK_INCOMPLETE)
                                .count())
                .put(
                        "evidenceIncomplete",
                        evidenceDebt.stream()
                                .filter(d -> d.progress() == AgentAcceptanceRecipes.Progress.EVIDENCE_INCOMPLETE)
                                .count());
        out.set("requirements", view().path("requirements"));
        if (lastCompletion != null) out.set("completion", lastCompletion.deepCopy());
        for (var r : out.path("requirements")) ((ObjectNode) r).remove("text");
        for (var r : out.path("completion").path("requirements")) ((ObjectNode) r).remove("text");
        for (var claim : out.path("completion").path("claims"))
            if (claim.path("support").asText().equals("UNVERIFIED")
                    || claim.path("support").asText().equals("UNKNOWN")) ((ObjectNode) claim).remove("subject");
        return out;
    }

    public ObjectNode view() {
        var out = contract.toJson();
        out.remove("userMessages");
        for (var r : out.path("requirements"))
            ((ObjectNode) r).put("text", AgentContext.bounded(r.path("text").asText(), 250));
        out.set("evidenceDebt", debtJson());
        out.set("projectRecipes", recipes.projectView(new ObjectMapper()));
        return out;
    }

    public ObjectNode save() {
        var out = new ObjectMapper().createObjectNode();
        out.set("contract", contract.toJson());
        out.set("evidence", ledger.toJson());
        var stored = out.putArray("changes");
        receipts.forEach((path, receipt) -> {
            var entry = stored.addObject()
                    .put("path", path)
                    .put("beforeHash", receipt.beforeHash())
                    .put("afterHash", receipt.afterHash())
                    .put("created", receipt.created());
            receipt.tests().forEach(entry.putArray("newTests")::add);
            receipt.updatedTests().forEach(entry.putArray("updatedTests")::add);
        });
        return out;
    }

    public void restore(JsonNode data) {
        contract.restore(data.path("contract"));
        ledger.restore(data.path("evidence"));
        if (data.path("changes").size() > 128) throw new IllegalArgumentException("Too many persisted changes");
        for (var entry : data.path("changes")) {
            String path = entry.path("path").asText(),
                    before = entry.path("beforeHash").asText(),
                    after = entry.path("afterHash").asText();
            try {
                workspace.resolve(path);
            } catch (Exception invalid) {
                throw new IllegalArgumentException("Invalid persisted path");
            }
            if (!before.matches("[a-f0-9]{64}")
                    || !after.matches("[a-f0-9]{64}")
                    || entry.path("newTests").size() > 2048)
                throw new IllegalArgumentException("Invalid change receipt");
            var names = new LinkedHashSet<String>();
            entry.path("newTests").forEach(n -> names.add(n.asText()));
            var updated = new LinkedHashSet<String>();
            entry.path("updatedTests").forEach(n -> updated.add(n.asText()));
            if (updated.size() > 2048) throw new IllegalArgumentException("Too many updated tests");
            receipts.put(path, new Receipt(before, after, entry.path("created").asBoolean(), names, updated));
            pendingReceipts.add(path);
            changed.add(path);
        }
        refreshDebt();
    }

    public void register(AgentTools tools) throws Exception {
        add(
                tools,
                "task_contract",
                "Inspect persistent requirements and actionable evidence debt. Optional add/supersede applies ONLY to agent hypotheses. User requirements can be corrected in the user-facing Acceptance panel. Workspace recipes are guidance, not permissions.",
                "{\"action\":{\"type\":\"string\",\"enum\":[\"inspect\",\"add\",\"supersede\"]},\"id\":{\"type\":\"string\"},\"text\":{\"type\":\"string\",\"maxLength\":500},\"type\":{\"type\":\"string\"},\"check\":{\"type\":\"string\"}}",
                List.of(),
                (a, c) -> {
                    switch (a.path("action").asText("inspect")) {
                        case "add" ->
                            contract.derived(
                                    Type.valueOf(a.path("type").asText()),
                                    a.path("text").asText(),
                                    Check.valueOf(a.path("check").asText()));
                        case "supersede" ->
                            contract.supersedeDerived(a.path("id").asText());
                    }
                    reconcile(c);
                    var page = view();
                    var items = page.withArray("requirements");
                    int total = items.size(),
                            offset = Math.min(total, a.path("offset").asInt());
                    for (int i = 0; i < offset; i++) items.remove(0);
                    while (items.size() > 8) items.remove(items.size() - 1);
                    page.put("requirementCount", total);
                    if (offset + items.size() < total) page.put("nextOffset", offset + items.size());
                    return AgentTool.Result.ok(page.toString());
                });
        add(
                tools,
                "task_evidence",
                "Page current runtime evidence identities for concrete completion claims. Historical or stale observations cannot support completion; task_contract explains how to refresh them. Source bodies are omitted.",
                "{\"offset\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":100000}}",
                List.of(),
                (a, c) -> {
                    reconcile(c);
                    var items = ledger.entries().stream()
                            .filter(e -> e.freshness() == Freshness.CURRENT)
                            .toList();
                    int offset = Math.min(items.size(), a.path("offset").asInt());
                    var out = new ObjectMapper().createObjectNode();
                    var page = out.putArray("items");
                    for (var e : items.stream().skip(offset).limit(12).toList()) page.add(e.toJson());
                    out.put("total", items.size());
                    if (offset + page.size() < items.size()) out.put("nextOffset", offset + page.size());
                    return AgentTool.Result.ok(out.toString());
                });
        add(
                tools,
                "completion_claims",
                "Optionally nominate concrete facts for final rendering using task_evidence ids. Unsupported claims are excluded. No free-form success claims or self-grading; final completion also checks every active requirement.",
                "{\"claims\":{\"type\":\"array\",\"maxItems\":24,\"items\":{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"kind\",\"subject\",\"evidence\"],\"properties\":{\"kind\":{\"type\":\"string\",\"enum\":[\"FILE_CHANGED\",\"FILE_CREATED\",\"SYMBOL_EXISTS\",\"TEST_ADDED\",\"TEST_PASSED\",\"TEST_COUNT\",\"VALIDATION\",\"DIAGNOSTICS_CLEAN\",\"ALL_CALLERS_UPDATED\"]},\"subject\":{\"type\":\"string\",\"maxLength\":1000},\"evidence\":{\"type\":\"string\"},\"count\":{\"type\":\"integer\",\"minimum\":0}}}}}",
                List.of("claims"),
                (a, c) -> {
                    var requested = new ArrayList<AgentCompletion.Claim>();
                    for (var item : a.path("claims"))
                        requested.add(new AgentCompletion.Claim(
                                AgentCompletion.ClaimKind.valueOf(
                                        item.path("kind").asText()),
                                item.path("subject").asText(),
                                item.path("evidence").asText(),
                                item.has("count") ? item.path("count").asLong() : null));
                    claims = List.copyOf(requested);
                    var result = finish("", c).data();
                    var response = new ObjectMapper()
                            .createObjectNode()
                            .put("accepted", result.path("accepted").asBoolean())
                            .put(
                                    "unsupportedClaims",
                                    result.path("unsupportedClaims").asInt())
                            .put(
                                    "remainingRequirements",
                                    result.path("remaining").size());
                    response.set("evidenceDebt", result.path("evidenceDebt"));
                    var statuses = response.putArray("claims");
                    for (var claim : result.path("claims"))
                        statuses.addObject()
                                .put("kind", claim.path("kind").asText())
                                .put("support", claim.path("support").asText())
                                .put("evidence", claim.path("evidence").asText());
                    return AgentTool.Result.ok(response.toString());
                });
    }

    private static void add(
            AgentTools tools,
            String name,
            String description,
            String properties,
            List<String> required,
            AgentTool.Handler handler)
            throws Exception {
        var json = new ObjectMapper();
        var schema = json.createObjectNode().put("type", "object").put("additionalProperties", false);
        schema.set("properties", json.readTree(properties));
        if (name.equals("task_contract")) {
            ((ObjectNode) schema.path("properties"))
                    .putObject("offset")
                    .put("type", "integer")
                    .put("minimum", 0)
                    .put("maximum", 64);
            var p = (ObjectNode) schema.path("properties");
            var types = ((ObjectNode) p.path("type")).putArray("enum");
            for (var type : Type.values()) types.add(type.name());
            var checks = ((ObjectNode) p.path("check")).putArray("enum");
            for (var check : Check.values()) checks.add(check.name());
        }
        required.forEach(schema.putArray("required")::add);
        tools.register(new AgentTool(
                new AgentTool.Spec(
                        name,
                        description,
                        schema,
                        null,
                        AgentTool.Effect.READ,
                        Duration.ofSeconds(30),
                        true,
                        "editora"),
                handler));
    }

    private static String hash(String text) {
        try {
            return AgentSessionStore.hash(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception unavailable) {
            throw new IllegalStateException("SHA-256 unavailable", unavailable);
        }
    }

    private String path(Path path) {
        return workspace.root().relativize(path).toString().replace('\\', '/');
    }

    private static boolean testFile(String path) {
        return path.contains("/test/") || path.startsWith("test/") || path.startsWith("tests/");
    }

    private static boolean documentation(String path) {
        return path.endsWith(".md") || path.endsWith(".rst") || path.startsWith("docs/");
    }
}
