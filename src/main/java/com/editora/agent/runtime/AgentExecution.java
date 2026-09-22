package com.editora.agent.runtime;

import java.nio.charset.StandardCharsets;
import java.util.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Session-thread working state. Observations guide strategy but never grant evidence or permission. */
public final class AgentExecution {
    public enum Phase {
        ORIENTATION,
        INVESTIGATION,
        IMPLEMENTATION,
        VALIDATION,
        ACCEPTANCE,
        COMPLETION
    }

    public enum Activity {
        ORIENT,
        DISCOVER,
        INSPECT,
        PLAN,
        EDIT,
        SAVE,
        VALIDATE,
        REFRESH_EVIDENCE,
        RECOVER,
        COMPLETE,
        REDUNDANT
    }

    private record Range(int start, int end) {}

    private static final class FileState {
        String revision = "";
        boolean historical;
        final List<Range> ranges = new ArrayList<>();
    }

    private final ObjectMapper json = new ObjectMapper();
    private final LinkedHashMap<String, FileState> files = new LinkedHashMap<>();
    private final Set<String> observations = new LinkedHashSet<>();
    private final Deque<String> recent = new ArrayDeque<>();
    private final Deque<String> failures = new ArrayDeque<>();
    private final EnumSet<Activity> activities = EnumSet.noneOf(Activity.class);
    private ObjectNode facts = json.createObjectNode();
    private Phase phase = Phase.ORIENTATION;
    private boolean progress, ready, changed, validated, reopened;
    private int idleRounds, readyDiscoveryRounds, grace, replans, generation, verifiedGeneration = -1;
    private int rounds, calls, repeated, noProgressEvents, firstMutationRound = -1, lastMutationRound = -1;
    private int callsBeforeMutation, tailReads, tailValidations;
    private long started, firstMutationMs = -1;
    private String debtSignature = "", nextTool = "", planWarning = "";

    public void beginTurn() {
        idleRounds = readyDiscoveryRounds = grace = replans = rounds = calls = repeated = noProgressEvents = 0;
        firstMutationRound = lastMutationRound = -1;
        callsBeforeMutation = tailReads = tailValidations = 0;
        firstMutationMs = -1;
        started = System.nanoTime();
        ready = reopened = validated = changed = false;
        verifiedGeneration = -1;
        debtSignature = nextTool = planWarning = "";
        facts = json.createObjectNode();
        observations.clear();
        phase = Phase.ORIENTATION;
        files.values().forEach(f -> f.historical = true);
    }

    public void beginRound() {
        rounds++;
        progress = false;
        activities.clear();
    }

    public static Activity classify(String tool, JsonNode args, boolean changed, boolean error) {
        if (error) return Activity.RECOVER;
        if (changed) return Activity.EDIT;
        return switch (tool) {
            case "read_file" -> Activity.INSPECT;
            case "find_files", "list_files", "search_text", "semantic_capabilities" -> Activity.DISCOVER;
            case "semantic_query" ->
                args.path("operation").asText().equals("workspace_symbols") ? Activity.DISCOVER : Activity.INSPECT;
            case "update_plan", "execution_control" -> Activity.PLAN;
            case "save_files" -> Activity.SAVE;
            case "run_validation" -> Activity.VALIDATE;
            case "run_command" ->
                args.path("purpose").asText().equals("validate") ? Activity.VALIDATE : Activity.ORIENT;
            case "task_contract", "task_evidence", "review_changes", "diagnostics" -> Activity.REFRESH_EVIDENCE;
            case "completion_claims" -> Activity.COMPLETE;
            default -> Activity.ORIENT;
        };
    }

    public void observe(String tool, JsonNode args, AgentTool.Result result) {
        calls++;
        if (firstMutationRound < 0) callsBeforeMutation++;
        var activity = classify(tool, args, result.changed(), result.error());
        activities.add(activity);
        remember(recent, tool, 6);
        if (result.error()) {
            // Payloads can contain repository/MCP instructions or secrets; retain tool names only.
            remember(failures, tool, 4);
            repeated++;
            return;
        }
        if (result.changed()) {
            progress = changed = true;
            ready = validated = false;
            generation++;
            if (firstMutationRound < 0) {
                firstMutationRound = rounds;
                callsBeforeMutation--;
                firstMutationMs = (System.nanoTime() - started) / 1_000_000;
            }
            lastMutationRound = rounds;
            tailReads = tailValidations = 0;
        } else if (lastMutationRound >= 0) {
            if (activity == Activity.INSPECT || activity == Activity.DISCOVER) tailReads++;
            if (activity == Activity.VALIDATE) tailValidations++;
        }
        if (tool.equals("update_plan")) {
            boolean complete = args.path("steps").size() > 0
                    && java.util.stream.StreamSupport.stream(args.path("steps").spliterator(), false)
                            .allMatch(step -> step.path("status").asText().equals("completed"));
            planWarning = complete && facts.path("pending_requirements").size() > 0
                    ? "PLAN_COMPLETE_WITH_PENDING_REQUIREMENTS"
                    : "";
        }
        JsonNode data;
        try {
            data = result.text().length() <= 64_000 ? json.readTree(result.text()) : null;
        } catch (Exception malformed) {
            data = null;
        }
        // save_files has a plain-text success result; payload format is not progress authority.
        boolean novel = tool.equals("save_files") && changed && rememberObservation(generation + tool);
        if (data != null) {
            if (tool.equals("read_file") && data.has("revision") && data.has("text")) {
                String path = data.path("path").asText(args.path("path").asText());
                var file = file(path);
                if (file != null) {
                    String revision = data.path("revision").asText();
                    if (!revision.equals(file.revision) || file.historical) {
                        file.ranges.clear();
                        file.revision = revision;
                        file.historical = false;
                    }
                    int start = data.path("line").asInt(1),
                            end = data.path("endLine").asInt(start);
                    novel = end >= start && file.ranges.stream().noneMatch(r -> r.start <= start && r.end >= end);
                    if (novel) {
                        var ranges = new ArrayList<>(file.ranges);
                        ranges.add(new Range(start, end));
                        ranges.sort(Comparator.comparingInt(Range::start));
                        file.ranges.clear();
                        for (var r : ranges) {
                            if (!file.ranges.isEmpty() && file.ranges.getLast().end + 1 >= r.start) {
                                var previous = file.ranges.removeLast();
                                file.ranges.add(new Range(previous.start, Math.max(previous.end, r.end)));
                            } else file.ranges.add(r);
                        }
                        if (file.ranges.size() > 32) {
                            file.ranges.clear();
                            file.ranges.add(new Range(start, end));
                        }
                    }
                }
            } else if (tool.equals("find_files")) {
                for (var path : data.path("paths")) novel |= discover(path.asText());
            } else if (tool.equals("list_files") && data.isArray()) {
                for (var item : data)
                    if (!item.path("directory").asBoolean())
                        novel |= discover(item.path("path").asText());
            } else if (tool.equals("search_text")) {
                for (var item : data.path("matches")) {
                    novel |= discover(item.path("path").asText());
                    novel |= rememberObservation("match:" + item.path("path") + item.path("line") + item.path("text"));
                }
            } else if (tool.equals("semantic_query")) {
                // Ignore timing and request ids; only scoped semantic items constitute discovery.
                novel = data.path("items").size() > 0
                        && rememberObservation(tool
                                + args.path("operation")
                                + args.path("path")
                                + args.path("line")
                                + args.path("character")
                                + data.path("items"));
            } else if (tool.equals("run_validation")) {
                // Repeating the same command/report at one mutation generation is not progress.
                novel = rememberObservation(generation + tool + args);
            }
        }
        // Unknown plugin/MCP result shapes may still be useful. Bounded result novelty guides
        // continuation only; no opaque payload can populate native files, evidence or readiness.
        boolean opaque =
                switch (tool) {
                    case "apply_edits",
                            "create_file",
                            "read_file",
                            "find_files",
                            "list_files",
                            "search_text",
                            "semantic_query",
                            "save_files",
                            "run_validation",
                            "update_plan",
                            "get_plan",
                            "execution_control",
                            "task_contract",
                            "task_evidence",
                            "completion_claims",
                            "review_changes",
                            "diagnostics" -> false;
                    default -> true;
                };
        if (opaque && !result.text().isBlank())
            novel = rememberObservation("opaque:" + tool + AgentContext.bounded(result.text(), 64_000));
        if (!novel && !result.changed()) repeated++;
        progress |= novel;
    }

    private boolean discover(String path) {
        boolean absent = !files.containsKey(path);
        return file(path) != null && absent;
    }

    private FileState file(String path) {
        if (path.isBlank() || path.length() > 300) return null;
        if (files.size() >= 64 && !files.containsKey(path)) files.pollFirstEntry();
        return files.computeIfAbsent(path, p -> new FileState());
    }

    private boolean rememberObservation(String value) {
        String hash;
        try {
            hash = AgentSessionStore.hash(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception unavailable) {
            return false;
        }
        boolean novel = observations.add(hash);
        if (!novel) {
            observations.remove(hash);
            observations.add(hash);
        }
        if (observations.size() > 128)
            observations.remove(observations.iterator().next());
        return novel;
    }

    private static void remember(Deque<String> queue, String text, int limit) {
        if (queue.size() == limit) queue.removeFirst();
        queue.addLast(text);
    }

    /** Native acceptance facts, obtained directly by the runtime, never from a tool response. */
    public void reconcile(ObjectNode value) {
        facts = value.deepCopy();
        String signature = value.path("pending_requirements").toString();
        if (!debtSignature.isEmpty() && !signature.equals(debtSignature)) progress = true;
        debtSignature = signature;
        if (!value.path("requirements_satisfied").asBoolean()) {
            ready = false;
            verifiedGeneration = -1;
        }
        validated = value.path("validation").size() > 0;
        changed |= value.path("changed_files").size() > 0;
    }

    public boolean canComplete() {
        return facts.path("requirements_satisfied").asBoolean();
    }

    public boolean verificationNeeded() {
        return verifiedGeneration != generation;
    }

    public void verified(boolean passed) {
        verifiedGeneration = passed ? generation : -1;
    }

    public void invalidateVerification() {
        verifiedGeneration = -1;
    }

    public ObjectNode endRound(boolean completionReady) {
        boolean wasReady = ready;
        ready = completionReady;
        if (ready && !wasReady) progress = true;
        boolean discovery = activities.contains(Activity.DISCOVER)
                || activities.contains(Activity.INSPECT)
                || activities.contains(Activity.ORIENT)
                || activities.contains(Activity.REFRESH_EVIDENCE);
        if (ready && wasReady && discovery && !reopened) readyDiscoveryRounds++;
        else if (!ready) readyDiscoveryRounds = 0;
        if (progress) {
            idleRounds = replans = 0;
            grace = 0;
            if (!reopened) nextTool = "";
        } else idleRounds++;
        if (grace > 0) grace--;
        if (grace == 0) reopened = false;
        boolean missingWork = java.util.stream.StreamSupport.stream(
                        facts.path("evidence_debt").spliterator(), false)
                .anyMatch(d -> d.path("progress").asText().equals("TASK_INCOMPLETE"));
        phase = ready && !reopened
                ? Phase.COMPLETION
                : reopened
                        ? Phase.INVESTIGATION
                        : activities.contains(Activity.RECOVER)
                                ? Phase.INVESTIGATION
                                : changed && missingWork
                                        ? Phase.IMPLEMENTATION
                                        : changed
                                                ? validated ? Phase.ACCEPTANCE : Phase.VALIDATION
                                                : files.values().stream()
                                                                                .filter(f -> !f.historical
                                                                                        && !f.ranges.isEmpty())
                                                                                .count()
                                                                        >= 2
                                                                && facts.path("mutation_requested")
                                                                        .asBoolean()
                                                        ? Phase.IMPLEMENTATION
                                                        : files.isEmpty() ? Phase.ORIENTATION : Phase.INVESTIGATION;
        if (idleRounds == 2
                || idleRounds == 4
                || idleRounds == 6
                || readyDiscoveryRounds == 1
                || readyDiscoveryRounds == 2) noProgressEvents++;
        return state();
    }

    public String block(String tool) {
        if (tool.equals("execution_control") || tool.equals("completion_claims") || grace > 0) return null;
        if (readyDiscoveryRounds >= 2)
            return "COMPLETION_READY: submit a final response or execution_control(action=reopen, reason=..., next_tool=...) before reopening investigation.";
        if (idleRounds >= 6)
            return "NO_PROGRESS: submit an incomplete final response or execution_control(action=replan, reason=..., next_tool=...) to try a different strategy.";
        return null;
    }

    public boolean exhausted() {
        return idleRounds >= 9 && grace == 0;
    }

    public AgentTool.Result control(JsonNode args, Set<String> catalog) {
        String action = args.path("action").asText("inspect");
        if (!action.equals("inspect")) {
            String tool = args.path("next_tool").asText();
            if (!catalog.contains(tool) || tool.equals("execution_control"))
                return AgentTool.Result.failure("Choose an advertised tool family for the next action.");
            if (args.path("reason").asText().strip().length() < 8)
                return AgentTool.Result.failure(
                        "Explain the unresolved question or new evidence requiring this strategy.");
            if (replans >= 2)
                return AgentTool.Result.failure("Two strategy changes made no progress; report the blocker.");
            replans++;
            grace = 3;
            reopened = action.equals("reopen");
            readyDiscoveryRounds = 0;
            nextTool = tool;
        }
        return AgentTool.Result.ok(state().toString());
    }

    public ObjectNode state() {
        var out = json.createObjectNode()
                .put("execution_phase", phase.name())
                .put("completion_ready", ready)
                .put("no_progress_rounds", idleRounds)
                .put(
                        "recovery_level",
                        idleRounds >= 6 || readyDiscoveryRounds >= 2
                                ? 3
                                : idleRounds >= 4 ? 2 : idleRounds >= 2 ? 1 : 0)
                .put(
                        "progress_state",
                        ready && !reopened ? "COMPLETION_READY" : idleRounds >= 2 ? "NO_PROGRESS" : "PROGRESS")
                .put("new_state_observed", progress)
                .put(
                        "next_action",
                        ready && !reopened
                                ? "Submit completion; recognized evidence is current. Final verification still applies."
                                : !nextTool.isEmpty()
                                        ? nextTool
                                        : idleRounds >= 4
                                                ? "Change tool family: semantic symbols/references, filename discovery, then a targeted read; replan if blocked."
                                                : phase == Phase.IMPLEMENTATION
                                                        ? "Relevant files inspected; resolve material uncertainty or implement the pending requirement."
                                                        : phase == Phase.VALIDATION
                                                                ? "Save agent changes, then run the narrowest credible validation."
                                                                : "Follow the pending requirement; inspect implementation before editing.");
        var known = out.putArray("known_files");
        files.entrySet().stream()
                .sorted(java.util.Comparator.comparing(e -> e.getValue().ranges.isEmpty()))
                .limit(8)
                .forEach(e -> {
                    var f = e.getValue();
                    var item = known.addObject()
                            .put("path", e.getKey())
                            .put(
                                    "state",
                                    f.historical ? "HISTORICAL" : f.ranges.isEmpty() ? "DISCOVERED" : "INSPECTED");
                    var ranges = item.putArray("read_ranges");
                    f.ranges.stream()
                            .limit(4)
                            .forEach(r -> ranges.addArray().add(r.start).add(r.end));
                });
        out.put("plan_warning", planWarning);
        List<String> suggestions =
                switch (phase) {
                    case ORIENTATION -> List.of("editor_context", "find_files", "semantic_capabilities");
                    case INVESTIGATION -> List.of("semantic_query", "read_file", "search_text");
                    case IMPLEMENTATION -> List.of("apply_edits", "semantic_query", "read_file");
                    case VALIDATION -> List.of("save_files", "run_validation", "review_changes");
                    case ACCEPTANCE -> List.of("semantic_query", "run_validation", "task_contract");
                    case COMPLETION -> List.of();
                };
        out.set("suggested_tools", json.valueToTree(suggestions));
        out.put("known_file_count", files.size());
        for (String key : List.of("pending_requirements", "changed_files", "validation", "evidence_debt"))
            if (facts.has(key)) out.set(key, facts.get(key).deepCopy());
        out.set("recent_actions", json.valueToTree(recent));
        out.set("failed_tool_families", json.valueToTree(failures));
        out.set("round_activity", json.valueToTree(activities));
        return out;
    }

    public ObjectNode metrics() {
        return json.createObjectNode()
                .put("noProgressEvents", noProgressEvents)
                .put("nonProgressCalls", repeated)
                .put("callsBeforeFirstMutation", callsBeforeMutation)
                .put("firstMutationElapsedMs", firstMutationMs)
                .put("roundsBeforeFirstMutation", firstMutationRound < 0 ? rounds : firstMutationRound - 1)
                .put("roundsAfterLastMutation", lastMutationRound < 0 ? 0 : rounds - lastMutationRound)
                .put("readsAfterLastMutation", tailReads)
                .put("validationCallsAfterLastMutation", tailValidations);
    }

    /** Restored file hints are historical and cannot carry current ranges, validation or readiness. */
    public void restore(AgentContext.Saved saved) {
        for (var exchange : saved.exchanges())
            for (var message : exchange)
                for (var call : message.calls()) {
                    if (!call.name().equals("read_file")) continue;
                    try {
                        var f = file(
                                json.readTree(call.arguments()).path("path").asText());
                        if (f != null) f.historical = true;
                    } catch (Exception ignored) {
                        /* Invalid history is not a current observation. */
                    }
                }
    }
}
