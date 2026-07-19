package com.editora.github;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Parses the JSON array emitted by
 * {@code gh pr list --json number,title,author,headRefName,baseRefName,state,isDraft,updatedAt,url} into a
 * neutral {@link PullRequest} list. Parsed with the existing Jackson {@code ObjectMapper} via {@code readTree}
 * (no POJO binding → no {@code module-info} {@code opens}). Tolerant of missing fields (absent → {@code ""}/
 * {@code 0}/{@code false}); returns an empty list on malformed input rather than throwing — the caller
 * distinguishes a failed {@code gh} invocation (non-zero exit) from a genuinely empty PR list. Pure —
 * unit-tested.
 */
public final class PrListParser {

    private PrListParser() {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** One pull request from {@code gh pr list --json …}. */
    public record PullRequest(
            int number,
            String title,
            String authorLogin,
            String headRefName,
            String baseRefName,
            String state,
            boolean draft,
            String updatedAt,
            String url) {}

    /** Parses the {@code gh pr list --json …} array. Never throws; returns {@code List.of()} on bad input. */
    public static List<PullRequest> parse(String json) {
        List<PullRequest> out = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return out;
        }
        try {
            JsonNode root = MAPPER.readTree(json);
            if (root == null || !root.isArray()) {
                return out;
            }
            for (JsonNode n : root) {
                out.add(new PullRequest(
                        n.path("number").asInt(0),
                        text(n, "title"),
                        text(n.path("author"), "login"),
                        text(n, "headRefName"),
                        text(n, "baseRefName"),
                        text(n, "state"),
                        n.path("isDraft").asBoolean(false),
                        text(n, "updatedAt"),
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
