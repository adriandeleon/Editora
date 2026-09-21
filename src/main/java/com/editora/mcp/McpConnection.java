package com.editora.mcp;

import java.time.Duration;

import com.editora.agent.runtime.AgentCancellation;
import com.fasterxml.jackson.databind.JsonNode;

/** Transport contract: each request has a deadline and cancellation; failed calls are never replayed. */
public interface McpConnection extends AutoCloseable {
    JsonNode request(String method, JsonNode arguments, Duration timeout, AgentCancellation cancellation)
            throws Exception;

    void notification(String method, JsonNode arguments) throws Exception;

    boolean alive();

    boolean catalogChanged();

    @Override
    void close();
}
