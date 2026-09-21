package com.editora.agent.runtime;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

/** IDE semantic boundary. Implementations flush and validate request-time document snapshots. */
public interface AgentSemantics {
    JsonNode capabilities(AgentDocuments.Snapshot source, AgentCancellation cancellation) throws Exception;

    /** Result ranges use zero-based UTF-16 positions. Errors and stale responses must fail explicitly. */
    JsonNode request(
            String operation,
            JsonNode arguments,
            AgentDocuments.Snapshot source,
            List<AgentDocuments.Snapshot> preimages,
            AgentCancellation cancellation)
            throws Exception;
}
