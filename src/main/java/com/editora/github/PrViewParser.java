package com.editora.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Parses a single {@code gh pr view <n> --json number,title,body,author,baseRefName,headRefName,state,url,
 * additions,deletions} object into a {@link PrDetail}. Same tolerant, {@code readTree}-based approach as
 * {@link PrListParser}; returns {@code null} on bad input. Pure — unit-tested.
 */
public final class PrViewParser {

    private PrViewParser() {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A pull request's detail, for the diff-tab header / (slice 2) the panel tooltip. */
    public record PrDetail(
            int number,
            String title,
            String body,
            String authorLogin,
            String baseRefName,
            String headRefName,
            String state,
            String url,
            int additions,
            int deletions) {}

    /** Parses the {@code gh pr view --json …} object, or {@code null} on bad/empty input. */
    public static PrDetail parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode n = MAPPER.readTree(json);
            if (n == null || !n.isObject()) {
                return null;
            }
            return new PrDetail(
                    n.path("number").asInt(0),
                    text(n, "title"),
                    text(n, "body"),
                    text(n.path("author"), "login"),
                    text(n, "baseRefName"),
                    text(n, "headRefName"),
                    text(n, "state"),
                    text(n, "url"),
                    n.path("additions").asInt(0),
                    n.path("deletions").asInt(0));
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return v != null && v.isTextual() ? v.asText() : "";
    }
}
