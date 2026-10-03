package com.editora.git;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The kill timers: short for background reads, never short for anything that rewrites the tree. */
class GitServiceLimitsTest {

    @Test
    void mutationsAndNetworkCommandsAreNotOnTheStatusTimer() {
        assertEquals(Duration.ofSeconds(10), GitService.QUICK, "background reads stay bounded");
        // A commit with pre-commit hooks or a pinentry, or a checkout of a large tree, was SIGTERMed after
        // the 10 s read timer and left half-updated.
        assertTrue(GitService.MUTATION.compareTo(Duration.ofMinutes(10)) >= 0, GitService.MUTATION.toString());
        assertTrue(GitService.NETWORK.compareTo(GitService.MUTATION) >= 0, GitService.NETWORK.toString());
    }
}
