package com.editora.github;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Parses {@code gh run list --json databaseId,displayTitle,workflowName,headBranch,status,conclusion,event,
 * createdAt,url} into {@link WorkflowRun} records. Same tolerant, throw-free {@code readTree} approach as
 * {@link PrListParser} (no POJO binding → no {@code module-info} {@code opens}); returns {@code List.of()} on
 * bad input. Pure — unit-tested.
 */
public final class RunListParser {

    private RunListParser() {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * A run's display state — {@code gh}'s {@code status} × {@code conclusion} pair collapsed to one value.
     *
     * <p>Deliberately <em>not</em> {@link ChecksParser.Overall}: that is a roll-up across many check runs
     * (whose {@code NONE} means "nothing meaningful ran"), while this is a per-item projection that needs
     * states the roll-up lacks — CANCELLED/SKIPPED must not render as a red ✗ (and must not offer a failure
     * log, which would be empty), and QUEUED vs RUNNING gate whether Cancel is offered.
     */
    public enum RunState {
        SUCCESS,
        FAILURE,
        RUNNING,
        QUEUED,
        CANCELLED,
        SKIPPED,
        UNKNOWN;

        /** Only a genuine failure offers "View Failure Log" / "Rerun Failed Jobs". */
        public boolean failed() {
            return this == FAILURE;
        }

        /** Queued or running — the states that can still be cancelled. */
        public boolean active() {
            return this == RUNNING || this == QUEUED;
        }

        /** Maps gh's {@code status}/{@code conclusion} pair (either may be null/blank) to a display state. */
        public static RunState of(String status, String conclusion) {
            String s = status == null ? "" : status.toLowerCase(Locale.ROOT);
            String c = conclusion == null ? "" : conclusion.toLowerCase(Locale.ROOT);
            // A run that hasn't completed has no conclusion yet, so status decides.
            switch (s) {
                case "queued", "waiting", "requested", "pending" -> {
                    return QUEUED;
                }
                case "in_progress" -> {
                    return RUNNING;
                }
                default -> {
                    // completed (or unknown) — fall through to the conclusion
                }
            }
            return switch (c) {
                case "success" -> SUCCESS;
                case "failure", "timed_out", "startup_failure", "action_required" -> FAILURE;
                case "cancelled", "stale" -> CANCELLED;
                case "skipped", "neutral" -> SKIPPED;
                default -> UNKNOWN;
            };
        }

        /** The row's state glyph (technical, not localized — like {@code GitFileStatus.letter()}). */
        public String glyph() {
            return switch (this) {
                case SUCCESS -> "✓";
                case FAILURE -> "✗";
                case RUNNING -> "●";
                case QUEUED -> "○";
                case CANCELLED -> "⊘";
                case SKIPPED -> "–";
                case UNKNOWN -> "·";
            };
        }

        /** The CSS class that colors the glyph (themed in {@code app.css}). */
        public String cssClass() {
            return "github-run-" + name().toLowerCase(Locale.ROOT);
        }
    }

    /** One workflow run from {@code gh run list --json …}. */
    public record WorkflowRun(
            // NOTE: a long — GitHub run ids are ~1e10, well past Integer.MAX_VALUE.
            long databaseId,
            String displayTitle,
            String workflowName,
            String headBranch,
            String status,
            String conclusion,
            String event,
            String createdAt,
            String url) {

        public RunState state() {
            return RunState.of(status, conclusion);
        }
    }

    /** Parses the {@code gh run list --json …} array. Never throws; {@code List.of()} on bad input. */
    public static List<WorkflowRun> parse(String json) {
        List<WorkflowRun> out = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return out;
        }
        try {
            JsonNode root = MAPPER.readTree(json);
            if (root == null || !root.isArray()) {
                return out;
            }
            for (JsonNode n : root) {
                out.add(new WorkflowRun(
                        n.path("databaseId").asLong(0),
                        text(n, "displayTitle"),
                        text(n, "workflowName"),
                        text(n, "headBranch"),
                        text(n, "status"),
                        text(n, "conclusion"),
                        text(n, "event"),
                        text(n, "createdAt"),
                        text(n, "url")));
            }
        } catch (Exception e) {
            return List.of();
        }
        return out;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return v != null && v.isTextual() ? v.asText() : "";
    }
}
