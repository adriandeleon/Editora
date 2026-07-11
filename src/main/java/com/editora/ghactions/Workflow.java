package com.editora.ghactions;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.editora.cron.CronExpression;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;

/**
 * Parses a GitHub Actions workflow YAML into a neutral model for the preview: the workflow name, the
 * human-readable trigger phrases (from the {@code on:} key — reusing {@link CronExpression} to decode a
 * {@code schedule:} cron), and each job (its runner, dependencies, condition, and step list). The only place
 * this package touches Jackson (via {@link YAMLMapper}, an existing dependency) — read into a {@code JsonNode}
 * and walked into the toolkit-free model, so no {@code module-info} opens are needed. Pure, unit-tested.
 */
public final class Workflow {

    private static final YAMLMapper MAPPER = new YAMLMapper();

    /** One step: an action reference ({@code uses}) or a shell command ({@code run}), with an optional name. */
    public record Step(String name, String uses, String run) {

        /** The best display label: the step's name, else the short action name, else the command's first line. */
        public String label() {
            if (name != null && !name.isBlank()) {
                return name;
            }
            if (uses != null && !uses.isBlank()) {
                return shortAction(uses);
            }
            if (run != null && !run.isBlank()) {
                return "run: " + firstLine(run);
            }
            return "(empty step)";
        }
    }

    public record Job(
            String id,
            String name,
            String runsOn,
            List<String> needs,
            String ifCond,
            boolean matrix,
            List<Step> steps) {}

    private final String name;
    private final List<String> triggers;
    private final List<Job> jobs;
    private final String error;

    private Workflow(String name, List<String> triggers, List<Job> jobs, String error) {
        this.name = name;
        this.triggers = triggers;
        this.jobs = jobs;
        this.error = error;
    }

    public String name() {
        return name;
    }

    public List<String> triggers() {
        return triggers;
    }

    public List<Job> jobs() {
        return jobs;
    }

    public String error() {
        return error;
    }

    public boolean ok() {
        return error == null;
    }

    public static Workflow parse(String text) {
        JsonNode root;
        try {
            root = MAPPER.readTree(text == null ? "" : text);
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage().split("\n")[0] : e.toString();
            return new Workflow(null, List.of(), List.of(), msg);
        }
        if (root == null || !root.isObject()) {
            return new Workflow(null, List.of(), List.of(), "not a YAML mapping");
        }
        String name = text(root.get("name"));
        // YAML 1.1 resolves the bare key `on` to boolean true, which some readers stringify to "true".
        JsonNode on = root.has("on") ? root.get("on") : root.get("true");
        List<String> triggers = triggers(on);
        List<Job> jobs = jobs(root.get("jobs"));
        return new Workflow(name, triggers, jobs, null);
    }

    private static List<String> triggers(JsonNode on) {
        List<String> out = new ArrayList<>();
        if (on == null || on.isNull()) {
            return out;
        }
        if (on.isTextual()) {
            out.add(prettyEvent(on.asText()));
        } else if (on.isArray()) {
            for (JsonNode e : on) {
                out.add(prettyEvent(e.asText()));
            }
        } else if (on.isObject()) {
            for (Map.Entry<String, JsonNode> e : iterate(on)) {
                out.add(triggerPhrase(e.getKey(), e.getValue()));
            }
        }
        return out;
    }

    private static String triggerPhrase(String event, JsonNode filter) {
        switch (event.toLowerCase(Locale.ROOT)) {
            case "push":
            case "pull_request":
            case "pull_request_target":
                String verb = event.startsWith("push") ? "push" : "pull request";
                String branches = refList(filter, "branches");
                String tags = refList(filter, "tags");
                String paths = refList(filter, "paths");
                StringBuilder sb = new StringBuilder(verb);
                if (branches != null) {
                    sb.append(" to ").append(branches);
                }
                if (tags != null) {
                    sb.append(" of tag ").append(tags);
                }
                if (paths != null) {
                    sb.append(" touching ").append(paths);
                }
                return sb.toString();
            case "schedule":
                return schedulePhrase(filter);
            case "workflow_dispatch":
                return "manual dispatch";
            case "workflow_call":
                return "called by another workflow";
            case "release":
                String types = refList(filter, "types");
                return types != null ? "release (" + types + ")" : "release";
            default:
                return prettyEvent(event);
        }
    }

    private static String schedulePhrase(JsonNode schedule) {
        if (schedule == null || !schedule.isArray() || schedule.isEmpty()) {
            return "on a schedule";
        }
        List<String> parts = new ArrayList<>();
        for (JsonNode entry : schedule) {
            String cron = text(entry.get("cron"));
            if (cron == null) {
                continue;
            }
            CronExpression.Parsed p = CronExpression.parse(cron);
            parts.add(p.ok() ? lower(p.expr().describe()) : cron);
        }
        return parts.isEmpty() ? "on a schedule" : "on a schedule (" + String.join("; ", parts) + ")";
    }

    private static String refList(JsonNode filter, String key) {
        if (filter == null || !filter.isObject()) {
            return null;
        }
        JsonNode n = filter.get(key);
        if (n == null) {
            return null;
        }
        if (n.isTextual()) {
            return n.asText();
        }
        if (n.isArray()) {
            List<String> vals = new ArrayList<>();
            for (JsonNode e : n) {
                vals.add(e.asText());
            }
            return vals.isEmpty() ? null : String.join(", ", vals);
        }
        return null;
    }

    private static List<Job> jobs(JsonNode jobsNode) {
        List<Job> out = new ArrayList<>();
        if (jobsNode == null || !jobsNode.isObject()) {
            return out;
        }
        for (Map.Entry<String, JsonNode> e : iterate(jobsNode)) {
            out.add(job(e.getKey(), e.getValue()));
        }
        return out;
    }

    private static Job job(String id, JsonNode j) {
        String name = text(j.get("name"));
        String runsOn = scalarOrList(j.get("runs-on"));
        List<String> needs = new ArrayList<>();
        JsonNode needsNode = j.get("needs");
        if (needsNode != null) {
            if (needsNode.isTextual()) {
                needs.add(needsNode.asText());
            } else if (needsNode.isArray()) {
                for (JsonNode n : needsNode) {
                    needs.add(n.asText());
                }
            }
        }
        String ifCond = text(j.get("if"));
        boolean matrix = j.path("strategy").path("matrix").isObject();
        List<Step> steps = new ArrayList<>();
        JsonNode stepsNode = j.get("steps");
        if (stepsNode != null && stepsNode.isArray()) {
            for (JsonNode s : stepsNode) {
                steps.add(new Step(text(s.get("name")), text(s.get("uses")), text(s.get("run"))));
            }
        }
        return new Job(id, name, runsOn, needs, ifCond, matrix, steps);
    }

    private static String scalarOrList(JsonNode n) {
        if (n == null) {
            return null;
        }
        if (n.isTextual()) {
            return n.asText();
        }
        if (n.isArray()) {
            List<String> vals = new ArrayList<>();
            for (JsonNode e : n) {
                vals.add(e.asText());
            }
            return String.join(", ", vals);
        }
        return n.toString();
    }

    /** {@code actions/checkout@v4} → {@code checkout}; {@code docker://x} / {@code ./local} shown as-is. */
    static String shortAction(String uses) {
        String u = uses.strip();
        if (u.startsWith("./") || u.startsWith("docker://")) {
            return u;
        }
        int at = u.indexOf('@');
        String path = at >= 0 ? u.substring(0, at) : u;
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private static String firstLine(String s) {
        String t = s.strip();
        int nl = t.indexOf('\n');
        String first = nl >= 0 ? t.substring(0, nl) : t;
        return first.length() > 80 ? first.substring(0, 77) + "…" : first;
    }

    private static String prettyEvent(String event) {
        return event == null ? "" : event.replace('_', ' ');
    }

    private static String text(JsonNode n) {
        return n != null && n.isValueNode() ? n.asText() : null;
    }

    private static String lower(String s) {
        return s.isEmpty() ? s : Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    private static Iterable<Map.Entry<String, JsonNode>> iterate(JsonNode obj) {
        List<Map.Entry<String, JsonNode>> entries = new ArrayList<>();
        obj.fields().forEachRemaining(entries::add);
        return entries;
    }
}
