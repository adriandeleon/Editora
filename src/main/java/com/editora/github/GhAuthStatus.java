package com.editora.github;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Reads {@code gh auth status --json hosts} (gh 2.81+). Unlike the command's exit code — which is 1 both for
 * "never logged in" and for "could not reach GitHub to validate the token" — the JSON says which hosts have
 * an account and why a check failed, so being offline is not mistaken for being signed out. Never throws.
 * Pure — unit-tested.
 */
public final class GhAuthStatus {

    private GhAuthStatus() {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** What is known about {@code gh}'s sign-in. */
    public enum State {
        /** An account's token was accepted by its host. */
        SIGNED_IN,
        /** An account exists, but its host could not be reached (offline, proxy, timeout) to check it. */
        UNVERIFIED,
        /** No account on any host: {@code gh auth login} has never been run (or was undone). */
        SIGNED_OUT,
        /** Every account's token was refused by its host (HTTP 401): it must be refreshed. */
        REJECTED
    }

    /**
     * The parsed status: the overall {@code state}; the {@code hosts} gh has a usable-looking account on (not
     * the rejected ones), lower-cased; {@code detail}, gh's own error text for the first failed check; and
     * {@code accounts}, the login gh uses on each of those hosts (a host whose login gh did not say is absent).
     */
    public record Parsed(State state, List<String> hosts, String detail, Map<String, String> accounts) {}

    /**
     * Parses the JSON document; {@code null} when {@code json} is not one (an older gh that has no
     * {@code --json} here, a stand-in) — the caller then falls back to the exit code.
     */
    public static Parsed parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        JsonNode hostsNode;
        try {
            JsonNode root = MAPPER.readTree(json);
            hostsNode = root == null ? null : root.get("hosts");
        } catch (Exception malformed) {
            return null;
        }
        if (hostsNode == null || !hostsNode.isObject()) {
            return null;
        }
        List<String> hosts = new ArrayList<>();
        Map<String, String> accounts = new LinkedHashMap<>();
        boolean anySignedIn = false;
        boolean anyUnverified = false;
        boolean anyRejected = false;
        String detail = "";
        for (var entry : hostsNode.properties()) {
            JsonNode account = activeAccount(entry.getValue());
            if (account == null) {
                continue;
            }
            String state = account.path("state").asText("");
            String error = account.path("error").asText("");
            if ("success".equals(state)) {
                anySignedIn = true;
            } else if (rejected(error)) {
                anyRejected = true;
                if (detail.isEmpty()) {
                    detail = error;
                }
                continue; // gh cannot use this host until the token is refreshed
            } else {
                anyUnverified = true; // "timeout", or an "error" that is not the host refusing the token
                if (detail.isEmpty()) {
                    detail = error;
                }
            }
            String host = entry.getKey().strip().toLowerCase(Locale.ROOT);
            hosts.add(host);
            String login = account.path("login").asText("").strip();
            if (!login.isEmpty()) {
                accounts.put(host, login);
            }
        }
        State overall = anySignedIn
                ? State.SIGNED_IN
                : anyUnverified ? State.UNVERIFIED : anyRejected ? State.REJECTED : State.SIGNED_OUT;
        return new Parsed(
                overall,
                List.copyOf(hosts),
                overall == State.SIGNED_IN ? "" : firstLine(detail),
                java.util.Collections.unmodifiableMap(accounts));
    }

    /**
     * Every login gh has on {@code host}, the active one first; empty when {@code json} is not the document
     * or names none. A user who works with two accounts ({@code gh auth switch}) has both here.
     */
    public static List<String> logins(String json, String host) {
        if (json == null || json.isBlank() || host == null) {
            return List.of();
        }
        JsonNode hostsNode;
        try {
            JsonNode root = MAPPER.readTree(json);
            hostsNode = root == null ? null : root.get("hosts");
        } catch (Exception malformed) {
            return List.of();
        }
        if (hostsNode == null || !hostsNode.isObject()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (var entry : hostsNode.properties()) {
            if (!entry.getKey().strip().equalsIgnoreCase(host.strip())
                    || !entry.getValue().isArray()) {
                continue;
            }
            for (JsonNode account : entry.getValue()) {
                String login = account.path("login").asText("").strip();
                if (login.isEmpty() || out.contains(login)) {
                    continue;
                }
                if (account.path("active").asBoolean(false)) {
                    out.add(0, login);
                } else {
                    out.add(login);
                }
            }
        }
        return List.copyOf(out);
    }

    /** The host's active account (the one gh uses), else its first; {@code null} when it has none. */
    private static JsonNode activeAccount(JsonNode accounts) {
        if (accounts == null || !accounts.isArray() || accounts.isEmpty()) {
            return null;
        }
        for (JsonNode account : accounts) {
            if (account.path("active").asBoolean(false)) {
                return account;
            }
        }
        return accounts.get(0);
    }

    /** Whether a failed check's error text is the host answering "bad credentials" rather than not answering. */
    static boolean rejected(String error) {
        String e = error == null ? "" : error.toLowerCase(Locale.ROOT);
        return e.contains("401") || e.contains("bad credentials");
    }

    private static String firstLine(String s) {
        int nl = s.indexOf('\n');
        return (nl >= 0 ? s.substring(0, nl) : s).strip();
    }
}
