package com.editora.github;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Parses {@code gh issue list --json number,title,author,state,labels,updatedAt,url} into a neutral
 * {@link Issue} list (labels flattened from {@code [{name,color}]} to their names). Same tolerant, throw-free,
 * {@code readTree}-based approach as {@link PrListParser}. Pure — unit-tested.
 */
public final class IssueListParser {

    private IssueListParser() {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** One issue from {@code gh issue list --json …}. */
    public record Issue(
            int number,
            String title,
            String authorLogin,
            String state,
            List<String> labels,
            String updatedAt,
            String url) {}

    /** Parses the {@code gh issue list --json …} array. Never throws; {@code List.of()} on bad input. */
    public static List<Issue> parse(String json) {
        List<Issue> out = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return out;
        }
        try {
            JsonNode root = MAPPER.readTree(json);
            if (root == null || !root.isArray()) {
                return out;
            }
            for (JsonNode n : root) {
                List<String> labels = new ArrayList<>();
                JsonNode labelsNode = n.path("labels");
                if (labelsNode.isArray()) {
                    for (JsonNode l : labelsNode) {
                        String name = text(l, "name");
                        if (!name.isEmpty()) {
                            labels.add(name);
                        }
                    }
                }
                out.add(new Issue(
                        n.path("number").asInt(0),
                        text(n, "title"),
                        text(n.path("author"), "login"),
                        text(n, "state"),
                        List.copyOf(labels),
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
