package com.editora.github;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
     * the rejected ones), lower-cased; and {@code detail}, gh's own error text for the first failed check.
     */
    public record Parsed(State state, List<String> hosts, String detail) {}

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
            hosts.add(entry.getKey().strip().toLowerCase(Locale.ROOT));
        }
        State overall = anySignedIn
                ? State.SIGNED_IN
                : anyUnverified ? State.UNVERIFIED : anyRejected ? State.REJECTED : State.SIGNED_OUT;
        return new Parsed(overall, List.copyOf(hosts), overall == State.SIGNED_IN ? "" : firstLine(detail));
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
