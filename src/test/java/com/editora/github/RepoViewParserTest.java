package com.editora.github;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RepoViewParserTest {

    @Test
    void parsesTheResolvedRepository() {
        RepoViewParser.RepoInfo info = RepoViewParser.parse(
                "{\"defaultBranchRef\":{\"name\":\"master\"},\"nameWithOwner\":\"upstream-org/Editora\","
                        + "\"url\":\"https://github.com/upstream-org/Editora\"}");
        assertEquals("upstream-org/Editora", info.nameWithOwner());
        assertEquals("master", info.defaultBranch());
        assertEquals("https://github.com/upstream-org/Editora", info.url());
    }

    @Test
    void anEmptyRepositoryHasNoDefaultBranch() {
        RepoViewParser.RepoInfo info = RepoViewParser.parse("{\"nameWithOwner\":\"o/r\",\"defaultBranchRef\":null}");
        assertEquals("o/r", info.nameWithOwner());
        assertEquals("", info.defaultBranch());
    }

    @Test
    void badInputYieldsNull() {
        assertNull(RepoViewParser.parse(null));
        assertNull(RepoViewParser.parse(" "));
        assertNull(RepoViewParser.parse("[]"));
        assertNull(RepoViewParser.parse("{}"));
        assertNull(RepoViewParser.parse("not json"));
    }
}
