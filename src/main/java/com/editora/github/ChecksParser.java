package com.editora.github;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Parses {@code gh pr checks --json name,state,bucket,link,workflow} into {@link CheckRun}s + a rolled-up
 * {@link ChecksSummary}. <b>Must be parsed regardless of exit code:</b> {@code gh pr checks} exits non-zero
 * when checks are failing (1) or still pending (8), but the JSON report is still on stdout. Same tolerant,
 * {@code readTree}-based approach as {@link PrListParser}. Pure — unit-tested.
 */
public final class ChecksParser {

    private ChecksParser() {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** One check run. {@code bucket} is gh's normalized state: pass/fail/pending/skipping/cancel. */
    public record CheckRun(String name, String workflow, String bucket, String link) {}

    /** Overall status of a PR's checks, for the (slice 3) status-bar glyph. */
    public enum Overall {
        PASS,
        FAIL,
        PENDING,
        NONE
    }

    /** Roll-up counts across a PR's check runs. */
    public record ChecksSummary(int pass, int fail, int pending, int skipped) {
        public static ChecksSummary of(List<CheckRun> runs) {
            int p = 0;
            int f = 0;
            int pend = 0;
            int skip = 0;
            for (CheckRun r : runs) {
                switch (r.bucket() == null ? "" : r.bucket().toLowerCase(Locale.ROOT)) {
                    case "pass" -> p++;
                    case "fail", "cancel" -> f++;
                    case "pending" -> pend++;
                    case "skipping", "skip", "skipped" -> skip++;
                    default -> {}
                }
            }
            return new ChecksSummary(p, f, pend, skip);
        }

        /** A failing check dominates; then a pending one; then all-pass; else nothing meaningful ran. */
        public Overall overall() {
            if (fail > 0) {
                return Overall.FAIL;
            }
            if (pending > 0) {
                return Overall.PENDING;
            }
            if (pass > 0) {
                return Overall.PASS;
            }
            return Overall.NONE;
        }
    }

    /** Parses the {@code gh pr checks --json …} array. Never throws; {@code List.of()} on bad input. */
    public static List<CheckRun> parse(String json) {
        List<CheckRun> out = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return out;
        }
        try {
            JsonNode root = MAPPER.readTree(json);
            if (root == null || !root.isArray()) {
                return out;
            }
            for (JsonNode n : root) {
                out.add(new CheckRun(text(n, "name"), text(n, "workflow"), text(n, "bucket"), text(n, "link")));
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
