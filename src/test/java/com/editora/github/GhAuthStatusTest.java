package com.editora.github;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Samples are what gh 2.96 prints for {@code gh auth status --json hosts} in each situation. */
class GhAuthStatusTest {

    @Test
    void aConfirmedAccountIsSignedIn() {
        GhAuthStatus.Parsed p = GhAuthStatus.parse("{\"hosts\":{\"github.com\":[{\"state\":\"success\",\"active\":true,"
                + "\"host\":\"github.com\",\"login\":\"octocat\",\"tokenSource\":\"keyring\","
                + "\"scopes\":\"gist, repo\",\"gitProtocol\":\"https\"}]}}");

        assertEquals(GhAuthStatus.State.SIGNED_IN, p.state());
        assertEquals(List.of("github.com"), p.hosts());
        assertEquals("", p.detail());
        assertEquals(java.util.Map.of("github.com", "octocat"), p.accounts(), "the login gh uses there");
    }

    /** G1: with no network gh still knows the account; that is "could not check", not "not logged in". */
    @Test
    void anUnreachableHostIsUnverifiedNotSignedOut() {
        GhAuthStatus.Parsed p = GhAuthStatus.parse("{\"hosts\":{\"github.com\":[{\"state\":\"error\",\"error\":"
                + "\"Get \\\"https://api.github.com/\\\": dial tcp: lookup api.github.com: no such host\","
                + "\"active\":true,\"host\":\"github.com\",\"login\":\"octocat\",\"tokenSource\":\"keyring\","
                + "\"gitProtocol\":\"https\"}]}}");

        assertEquals(GhAuthStatus.State.UNVERIFIED, p.state());
        assertEquals(List.of("github.com"), p.hosts());
        assertTrue(p.detail().contains("no such host"), p.detail());
    }

    @Test
    void aTimedOutCheckIsUnverified() {
        GhAuthStatus.Parsed p = GhAuthStatus.parse(
                "{\"hosts\":{\"GHE.Example.com\":[{\"state\":\"timeout\",\"active\":true,\"host\":\"ghe.example.com\"}]}}");

        assertEquals(GhAuthStatus.State.UNVERIFIED, p.state());
        assertEquals(List.of("ghe.example.com"), p.hosts());
    }

    @Test
    void noHostsIsSignedOut() {
        GhAuthStatus.Parsed p = GhAuthStatus.parse("{\"hosts\":{}}");

        assertEquals(GhAuthStatus.State.SIGNED_OUT, p.state());
        assertEquals(List.of(), p.hosts());
    }

    @Test
    void aRefusedTokenIsRejectedAndItsHostUnusable() {
        GhAuthStatus.Parsed p = GhAuthStatus.parse("{\"hosts\":{\"github.com\":[{\"state\":\"error\",\"error\":"
                + "\"non-200 OK status code: 401 Unauthorized body: \\\"{\\\\r\\\\n  \\\\\\\"message\\\\\\\": "
                + "\\\\\\\"Bad credentials\\\\\\\"}\\\"\",\"active\":true,\"host\":\"github.com\"}]}}");

        assertEquals(GhAuthStatus.State.REJECTED, p.state());
        assertEquals(List.of(), p.hosts());
    }

    @Test
    void oneGoodHostIsEnoughAndTheActiveAccountCounts() {
        GhAuthStatus.Parsed p = GhAuthStatus.parse("{\"hosts\":{"
                + "\"github.com\":[{\"state\":\"error\",\"error\":\"HTTP 401: Bad credentials\",\"active\":true}],"
                + "\"ghe.corp.example\":[{\"state\":\"error\",\"error\":\"HTTP 401\",\"active\":false},"
                + "{\"state\":\"success\",\"active\":true}]}}");

        assertEquals(GhAuthStatus.State.SIGNED_IN, p.state());
        assertEquals(List.of("ghe.corp.example"), p.hosts());
        assertEquals(java.util.Map.of(), p.accounts(), "gh named no login: none is made up");
    }

    /** An older gh has no {@code --json} here; a stand-in prints nothing: the caller falls back. */
    @Test
    void anythingElseIsNotAnAnswer() {
        assertNull(GhAuthStatus.parse(null));
        assertNull(GhAuthStatus.parse("  "));
        assertNull(GhAuthStatus.parse("unknown flag: --json"));
        assertNull(GhAuthStatus.parse("[]"));
        assertNull(GhAuthStatus.parse("{\"hosts\":7}"));
    }

    /** {@code gh auth switch}: both accounts are logged in and one is active. */
    @Test
    void everyLoginOfAHostIsListedWithTheActiveOneFirst() {
        String json = "{\"hosts\":{\"github.com\":["
                + "{\"state\":\"success\",\"active\":false,\"login\":\"me\"},"
                + "{\"state\":\"success\",\"active\":true,\"login\":\"me-at-work\"}],"
                + "\"ghe.corp.example\":[{\"state\":\"success\",\"active\":true,\"login\":\"corp\"}]}}";

        assertEquals(List.of("me-at-work", "me"), GhAuthStatus.logins(json, "GitHub.com"));
        assertEquals(List.of("corp"), GhAuthStatus.logins(json, "ghe.corp.example"));
        assertEquals(List.of(), GhAuthStatus.logins(json, "gitlab.com"));
        assertEquals(List.of(), GhAuthStatus.logins("unknown flag: --json", "github.com"));
        assertEquals(List.of(), GhAuthStatus.logins(null, "github.com"));
    }
}
