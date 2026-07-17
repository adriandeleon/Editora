package com.editora.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link AiEndpoints} — when attaching the API key to an endpoint would put it on the wire in cleartext. */
class AiEndpointsTest {

    @Test
    void httpsIsAlwaysSafe() {
        assertFalse(AiEndpoints.isCleartextRemote("https://api.anthropic.com/v1/messages"));
        assertFalse(AiEndpoints.isCleartextRemote("https://a-remote-host.example/v1"));
    }

    @Test
    void httpLoopbackIsSafe() {
        // The intended local-inference path (LM Studio / Ollama / vLLM) — never leaves the machine.
        assertFalse(AiEndpoints.isCleartextRemote("http://127.0.0.1:1234/v1/chat/completions"));
        assertFalse(AiEndpoints.isCleartextRemote("http://localhost:11434/v1"));
        assertFalse(AiEndpoints.isCleartextRemote("http://127.5.6.7:8080/x")); // all of 127.0.0.0/8
        assertFalse(AiEndpoints.isCleartextRemote("http://[::1]:1234/v1"));
    }

    @Test
    void httpRemoteHostIsCleartext() {
        assertTrue(AiEndpoints.isCleartextRemote("http://api.some-openai-host.example/v1"));
        assertTrue(AiEndpoints.isCleartextRemote("http://198.51.100.9:9000/v1")); // a remote IP
        // A deceptive hostname that merely *resolves* to loopback (nip.io-style) is still a remote host
        // string — we don't do DNS, and we won't send a key to it in cleartext.
        assertTrue(AiEndpoints.isCleartextRemote("http://evil.example.invalid.127.0.0.1.nip.io:8732/v1/messages"));
    }

    @Test
    void unparseableOrHostlessIsNotFlagged() {
        // The request's own URI error should surface, not a misleading "cleartext" message.
        assertFalse(AiEndpoints.isCleartextRemote("not a url"));
        assertFalse(AiEndpoints.isCleartextRemote(""));
        assertFalse(AiEndpoints.isCleartextRemote(null));
    }

    @Test
    void isLoopbackForms() {
        assertTrue(AiEndpoints.isLoopback("localhost"));
        assertTrue(AiEndpoints.isLoopback("LOCALHOST"));
        assertTrue(AiEndpoints.isLoopback("127.0.0.1"));
        assertTrue(AiEndpoints.isLoopback("127.255.255.254"));
        assertTrue(AiEndpoints.isLoopback("::1"));
        assertTrue(AiEndpoints.isLoopback("[::1]"));
        assertFalse(AiEndpoints.isLoopback("128.0.0.1"));
        assertFalse(AiEndpoints.isLoopback("example.com"));
        assertFalse(AiEndpoints.isLoopback("127.0.0.1.nip.io"));
    }

    @Test
    void hostOf() {
        assertEquals("api.anthropic.com", AiEndpoints.hostOf("https://api.anthropic.com/v1/messages"));
        assertEquals("some-host.example", AiEndpoints.hostOf("http://some-host.example:9000/v1"));
        assertEquals("not a url", AiEndpoints.hostOf("not a url")); // falls back to the raw string
    }
}
