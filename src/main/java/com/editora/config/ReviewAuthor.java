package com.editora.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Who wrote a {@link ReviewComment}: the user, an agent (by its name, such as "Claude Code"), or a GitHub account
 * (a comment read from a pull request). A Jackson-serialized record.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReviewAuthor(Kind kind, String name) {

    /** Where the comment came from. */
    public enum Kind {
        /** The person using this editor. */
        USER,
        /** An agent, through MCP or the agent panel. */
        AGENT,
        /** A GitHub account, on a pull request. */
        GITHUB
    }

    public ReviewAuthor {
        kind = kind == null ? Kind.USER : kind;
        name = name == null ? "" : name;
    }

    /** The user, unnamed (the editor shows "You"). */
    public static ReviewAuthor user() {
        return new ReviewAuthor(Kind.USER, "");
    }

    /** An agent called {@code name}. */
    public static ReviewAuthor agent(String name) {
        return new ReviewAuthor(Kind.AGENT, name);
    }
}
