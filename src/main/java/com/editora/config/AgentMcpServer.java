package com.editora.config;

/** User-configured stdio server; repository/model content cannot populate this trusted configuration. */
public record AgentMcpServer(String id, String command, boolean enabled) {
    public AgentMcpServer {
        if (id == null
                || !id.matches("[a-zA-Z0-9_-]{1,24}")
                || command == null
                || command.isBlank()
                || command.length() > 4000)
            throw new IllegalArgumentException("MCP server requires a short identifier and command");
    }
}
