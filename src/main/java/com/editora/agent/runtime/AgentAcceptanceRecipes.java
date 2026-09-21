package com.editora.agent.runtime;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import static com.editora.agent.runtime.AgentEvidence.*;
import static com.editora.agent.runtime.AgentTaskContract.*;

/** Bounded suggestions for gathering evidence. Recipes cannot create requirements or run tools. */
public final class AgentAcceptanceRecipes {
    public enum Progress {
        TASK_INCOMPLETE,
        EVIDENCE_INCOMPLETE,
        USER_ACTION_REQUIRED
    }

    public enum Source {
        BUILT_IN,
        WORKSPACE_CONFIGURATION,
        USER_EXPLICIT
    }

    public record Step(String tool, String hint, Map<String, String> input) {
        public Step {
            input = Map.copyOf(input);
        }
    }

    public record Debt(
            String requirement,
            Progress progress,
            String missing,
            String invalidatedBy,
            String path,
            Source recipeSource,
            List<Step> steps,
            List<String> staleEvidence) {
        public Debt {
            steps = List.copyOf(steps);
            staleEvidence = List.copyOf(staleEvidence);
        }

        ObjectNode toJson(ObjectMapper json) {
            var out = json.createObjectNode()
                    .put("requirement", requirement)
                    .put("progress", progress.name())
                    .put("missingEvidence", missing)
                    .put("reasonInvalidated", invalidatedBy)
                    .put("path", path)
                    .put("recipeSource", recipeSource.name())
                    .put("userActionRequired", progress == Progress.USER_ACTION_REQUIRED);
            var stale = out.putArray("staleEvidence");
            staleEvidence.forEach(stale::add);
            var actions = out.putArray("candidateTools");
            for (var step : steps) {
                var action = actions.addObject().put("tool", step.tool()).put("hint", step.hint());
                var input = action.putObject("input");
                step.input().forEach(input::put);
            }
            return out;
        }
    }

    public record ProjectRule(String pathPrefix, AgentValidationProfile.Operation validation, String note) {}

    private final List<ProjectRule> projectRules;

    public AgentAcceptanceRecipes(AgentWorkspace workspace) {
        projectRules = load(workspace);
    }

    public List<ProjectRule> projectRules() {
        return projectRules;
    }

    private static List<ProjectRule> load(AgentWorkspace workspace) {
        try {
            var path = workspace.resolve(".editora/acceptance.json");
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 16_384) return List.of();
            var value = new ObjectMapper().readTree(Files.readString(path));
            if (value.path("schemaVersion").asInt() != 1
                    || !value.path("rules").isArray()
                    || value.path("rules").size() > 32
                    || value.size() != 2) return List.of();
            var rules = new ArrayList<ProjectRule>();
            for (var rule : value.path("rules")) {
                if (rule.size() < 2 || rule.size() > 3) return List.of();
                var names = new HashSet<String>();
                rule.fieldNames().forEachRemaining(names::add);
                if (!Set.of("pathPrefix", "preferredValidation", "note").containsAll(names)
                        || !names.containsAll(Set.of("pathPrefix", "preferredValidation"))) return List.of();
                String prefix = rule.path("pathPrefix").asText();
                String operation = rule.path("preferredValidation").asText();
                String note = rule.path("note").asText("");
                if (prefix.isBlank()
                        || prefix.length() > 200
                        || prefix.startsWith("/")
                        || prefix.contains("..")
                        || prefix.contains("\\")
                        || note.length() > 200) return List.of();
                workspace.resolve(prefix);
                var preferred = AgentValidationProfile.Operation.valueOf(operation);
                if (preferred != AgentValidationProfile.Operation.TEST
                        && preferred != AgentValidationProfile.Operation.CHECK) return List.of();
                rules.add(new ProjectRule(prefix, preferred, note));
            }
            return List.copyOf(rules);
        } catch (Exception invalid) {
            return List.of();
        }
    }

    public ArrayNode projectView(ObjectMapper json) {
        var out = json.createArrayNode();
        projectRules.forEach(rule -> out.addObject()
                .put("pathPrefix", rule.pathPrefix())
                .put("preferredValidation", rule.validation().name())
                .put("note", rule.note())
                .put("provenance", Source.WORKSPACE_CONFIGURATION.name())
                .put("authority", "GUIDANCE_ONLY"));
        return out;
    }

    public Debt debt(
            Requirement requirement,
            Ledger ledger,
            Set<String> changed,
            Map<String, Set<String>> newTests,
            Map<String, Set<String>> updatedTests,
            Set<String> observedCallers) {
        String path = changed.stream().findFirst().orElse("");
        String stale = staleReason(requirement, ledger);
        Source source =
                switch (requirement.check()) {
                    case TARGETED_TEST_VALIDATED, NO_REFERENCES -> Source.USER_EXPLICIT;
                    default -> Source.BUILT_IN;
                };
        var guidance =
                switch (requirement.check()) {
                    case CHANGE ->
                        advice(
                                requirement,
                                changed.stream().anyMatch(p -> !testFile(p) && !documentation(p))
                                        ? Progress.EVIDENCE_INCOMPLETE
                                        : Progress.TASK_INCOMPLETE,
                                "Production change through the editor document model",
                                stale,
                                path,
                                source,
                                step("read_file", "Read the relevant implementation"),
                                step("apply_edits", "Make the requested change"));
                    case TEST_ADDED_EXECUTED, TEST_CHANGED_EXECUTED -> {
                        boolean added = requirement.check() == Check.TEST_ADDED_EXECUTED;
                        var names = added ? newTests : updatedTests;
                        String testPath = names.entrySet().stream()
                                .filter(e -> !e.getValue().isEmpty())
                                .map(Map.Entry::getKey)
                                .findFirst()
                                .orElse("");
                        boolean sourceExists = !testPath.isEmpty();
                        String selector = selector(testPath, names.getOrDefault(testPath, Set.of()));
                        yield advice(
                                requirement,
                                sourceExists ? Progress.EVIDENCE_INCOMPLETE : Progress.TASK_INCOMPLETE,
                                sourceExists
                                        ? "Save the changed test and observe its fresh passing execution"
                                        : added ? "Add a structural regression test" : "Change an existing test body",
                                stale,
                                testPath,
                                recipeSource(testPath, source),
                                sourceExists
                                        ? step("save_files", "Save current agent edits")
                                        : step(
                                                "find_files",
                                                "Locate the relevant test source",
                                                Map.of("query", "Test")),
                                sourceExists
                                        ? validationStep(selector)
                                        : step("apply_edits", "Change test source through the document model"));
                    }
                    case DOCUMENTATION_CHANGED -> {
                        boolean changedDoc = changed.stream().anyMatch(AgentAcceptanceRecipes::documentation)
                                && namedDocumentationCovered(requirement.text(), changed);
                        yield advice(
                                requirement,
                                changedDoc ? Progress.EVIDENCE_INCOMPLETE : Progress.TASK_INCOMPLETE,
                                changedDoc
                                        ? "Confirm current documentation scope and revision"
                                        : "Update the requested documentation path",
                                stale,
                                changedDoc
                                        ? changed.stream()
                                                .filter(AgentAcceptanceRecipes::documentation)
                                                .findFirst()
                                                .orElse(path)
                                        : path,
                                source,
                                step("read_file", "Inspect requested documentation"),
                                step("apply_edits", "Update missing documentation if needed"));
                    }
                    case VALIDATED, TESTS_VALIDATED, TARGETED_TEST_VALIDATED, CHECKED ->
                        advice(
                                requirement,
                                Progress.EVIDENCE_INCOMPLETE,
                                requirement.check() == Check.CHECKED
                                        ? "Fresh broad verify result"
                                        : "Fresh relevant validation result",
                                stale,
                                path,
                                recipeSource(path, source),
                                validationStep(
                                        requirement.check() == Check.CHECKED
                                                ? "CHECK"
                                                : requirement.check() == Check.TARGETED_TEST_VALIDATED
                                                        ? requestedSelector(requirement.text())
                                                        : "",
                                        path));
                    case OBSERVED_CALLERS_CHANGED -> {
                        var missing = new LinkedHashSet<>(observedCallers);
                        missing.removeAll(changed);
                        yield advice(
                                requirement,
                                missing.isEmpty() ? Progress.EVIDENCE_INCOMPLETE : Progress.TASK_INCOMPLETE,
                                observedCallers.isEmpty()
                                        ? "Fresh semantic references and observed caller scope"
                                        : missing.isEmpty()
                                                ? "Fresh references after the last edit and validation"
                                                : "Update observed caller files: "
                                                        + String.join(
                                                                ", ",
                                                                missing.stream()
                                                                        .limit(5)
                                                                        .toList()),
                                stale,
                                path,
                                source,
                                step("semantic_query", "Query references at the relevant symbol"),
                                step("run_validation", "Validate affected callers"));
                    }
                    case INSPECTED ->
                        advice(
                                requirement,
                                Progress.EVIDENCE_INCOMPLETE,
                                "Inspect entry points and relevant implementation files",
                                stale,
                                path,
                                source,
                                step("read_file", "Read relevant implementation"));
                    case TRACE_INSPECTED ->
                        advice(
                                requirement,
                                Progress.EVIDENCE_INCOMPLETE,
                                "Read at least two relevant files and follow a caller or boundary link",
                                stale,
                                path,
                                source,
                                step("read_file", "Inspect entry point and persistence or call boundary"),
                                step(
                                        "semantic_query",
                                        "Query references, or use literal search_text when LSP is unavailable"));
                    case NO_REFERENCES ->
                        advice(
                                requirement,
                                Progress.EVIDENCE_INCOMPLETE,
                                "Fresh untruncated workspace search for " + requestedReference(requirement.text()),
                                stale,
                                path,
                                source,
                                step(
                                        "search_text",
                                        "Search the accessible workspace",
                                        Map.of("query", requestedReference(requirement.text()), "path", ".")));
                    case OLD_FAILURE_PROVEN ->
                        advice(
                                requirement,
                                Progress.USER_ACTION_REQUIRED,
                                "An isolated old-failure probe is unavailable to the native agent",
                                stale,
                                path,
                                source,
                                step("task_contract", "Explain the proof limit and ask the user how to proceed"));
                    case NO_TEST_CHANGES, NO_DOCUMENTATION_CHANGES, JAVA_ONLY, NO_CHANGES -> {
                        boolean violated =
                                switch (requirement.check()) {
                                    case NO_TEST_CHANGES -> changed.stream().anyMatch(AgentAcceptanceRecipes::testFile);
                                    case NO_DOCUMENTATION_CHANGES ->
                                        changed.stream().anyMatch(AgentAcceptanceRecipes::documentation);
                                    case JAVA_ONLY -> changed.stream().anyMatch(p -> !p.endsWith(".java"));
                                    case NO_CHANGES -> !changed.isEmpty();
                                    default -> false;
                                };
                        yield advice(
                                requirement,
                                violated ? Progress.TASK_INCOMPLETE : Progress.EVIDENCE_INCOMPLETE,
                                violated
                                        ? "Resolve the changed files that violate the user's scope"
                                        : "Current workspace constraint observation",
                                stale,
                                path,
                                source,
                                step("review_changes", "Inspect current changed files"));
                    }
                    case MANUAL ->
                        advice(
                                requirement,
                                Progress.USER_ACTION_REQUIRED,
                                "User clarification or review",
                                stale,
                                path,
                                source,
                                step("task_contract", "Explain the unclear requirement"));
                };
        var old = relevantStale(requirement, ledger);
        return new Debt(
                guidance.requirement(),
                guidance.progress(),
                guidance.missing(),
                guidance.invalidatedBy(),
                guidance.path(),
                guidance.recipeSource(),
                guidance.steps(),
                old.stream()
                        .skip(Math.max(0, old.size() - 8))
                        .map(AgentEvidence::id)
                        .toList());
    }

    private Source recipeSource(String path, Source fallback) {
        if (fallback == Source.USER_EXPLICIT) return fallback;
        return matchingRule(path).isPresent() ? Source.WORKSPACE_CONFIGURATION : fallback;
    }

    private Optional<ProjectRule> matchingRule(String path) {
        return projectRules.stream()
                .filter(r -> !path.isBlank() && path.startsWith(r.pathPrefix()))
                .max(Comparator.comparingInt(r -> r.pathPrefix().length()));
    }

    private Step validationStep(String selector) {
        return validationStep(selector, "");
    }

    private Step validationStep(String selector, String path) {
        if (selector.equals("CHECK"))
            return step(
                    "run_validation",
                    "Broad verify; requires approval",
                    Map.of("type", "CHECK", "module", ".", "isolation", "ISOLATED"));
        if (!selector.isEmpty())
            return step(
                    "run_validation",
                    "Targeted test; requires approval",
                    Map.of("type", "TARGETED_TEST", "test", selector, "module", ".", "isolation", "ISOLATED"));
        var preferred = matchingRule(path).map(ProjectRule::validation).orElse(AgentValidationProfile.Operation.TEST);
        return step(
                "run_validation",
                "Preferred validation; requires approval",
                Map.of("type", preferred.name(), "module", ".", "isolation", "ISOLATED"));
    }

    private static String selector(String path, Set<String> names) {
        if (path.isBlank() || names.size() != 1 || !path.endsWith(".java")) return "";
        String className = path.substring(path.lastIndexOf('/') + 1, path.length() - 5);
        return className + "#" + names.iterator().next();
    }

    private static String requestedSelector(String text) {
        var matcher = java.util.regex.Pattern.compile(
                        "(?i)\\b(?:just|only)\\s+(?:run\\s+)?([A-Za-z_$][A-Za-z0-9_.$]*Test(?:#[A-Za-z_$][A-Za-z0-9_$]*)?)\\b")
                .matcher(text);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String requestedReference(String text) {
        var matcher = java.util.regex.Pattern.compile("(?i)\\bno references? to ([A-Za-z_$][A-Za-z0-9_$]*) remain\\b")
                .matcher(text);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static boolean testFile(String path) {
        return path.contains("/test/") || path.contains("/tests/") || path.endsWith("Test.java");
    }

    private static boolean documentation(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.endsWith(".md") || lower.endsWith(".rst") || lower.endsWith(".adoc");
    }

    private static boolean namedDocumentationCovered(String request, Set<String> changed) {
        var matcher = java.util.regex.Pattern.compile(
                        "(?<![A-Za-z0-9_./-])([A-Za-z0-9_./-]+\\.(?:md|rst))(?![A-Za-z0-9_])",
                        java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(request);
        while (matcher.find()) {
            String file = matcher.group(1);
            if (changed.stream().noneMatch(p -> p.equals(file) || p.endsWith("/" + file))) return false;
        }
        return true;
    }

    private static String staleReason(Requirement requirement, Ledger ledger) {
        return relevantStale(requirement, ledger).stream()
                .reduce((a, b) -> b)
                .map(AgentEvidence::invalidatedBy)
                .orElse("");
    }

    private static List<AgentEvidence> relevantStale(Requirement requirement, Ledger ledger) {
        var kinds =
                switch (requirement.check()) {
                    case VALIDATED, TESTS_VALIDATED, TARGETED_TEST_VALIDATED, CHECKED ->
                        Set.of(Kind.BUILD_PASSED, Kind.VALIDATION_RESULT);
                    case TEST_ADDED_EXECUTED -> Set.of(Kind.TEST_ADDED, Kind.TEST_PASSED, Kind.BUILD_PASSED);
                    case TEST_CHANGED_EXECUTED -> Set.of(Kind.SYMBOL_CHANGED, Kind.TEST_PASSED, Kind.BUILD_PASSED);
                    case OBSERVED_CALLERS_CHANGED -> Set.of(Kind.SEMANTIC_REFERENCE, Kind.BUILD_PASSED);
                    case INSPECTED -> Set.of(Kind.FILE_READ);
                    case TRACE_INSPECTED -> Set.of(Kind.FILE_READ, Kind.SEMANTIC_REFERENCE, Kind.SEARCH_OBSERVATION);
                    case NO_REFERENCES -> Set.of(Kind.SEARCH_ABSENCE);
                    default -> Set.<Kind>of();
                };
        return ledger.entries().stream()
                .filter(e -> kinds.contains(e.kind()) && e.freshness() != Freshness.CURRENT)
                .toList();
    }

    private static Step step(String tool, String arguments) {
        return new Step(tool, arguments, Map.of());
    }

    private static Step step(String tool, String hint, Map<String, String> input) {
        return new Step(tool, hint, input);
    }

    private static Debt advice(
            Requirement requirement,
            Progress progress,
            String missing,
            String stale,
            String path,
            Source source,
            Step... steps) {
        return new Debt(requirement.id(), progress, missing, stale, path, source, List.of(steps), List.of());
    }
}
