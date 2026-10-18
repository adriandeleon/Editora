package com.editora.deploy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DeployRootTest {

    @Test
    void anOrdinaryFolderIsFine() {
        assertNull(DeployRoot.problem("/srv/app", "/home/me"));
        assertNull(DeployRoot.problem("/home/me/apps/demo/", "/home/me"));
        assertNull(DeployRoot.problem("/tmp/demo", null));
    }

    @Test
    void theMachineTheHomesAndTheAccountAreRefused() {
        assertEquals(DeployRoot.Problem.TOO_WIDE, DeployRoot.problem("/", "/home/me"));
        assertEquals(DeployRoot.Problem.TOO_WIDE, DeployRoot.problem("/usr", "/home/me"));
        assertEquals(DeployRoot.Problem.TOO_WIDE, DeployRoot.problem("/home", "/home/me"));
        assertEquals(DeployRoot.Problem.TOO_WIDE, DeployRoot.problem("/home/me/", "/home/me"));
        assertEquals(DeployRoot.Problem.TOO_WIDE, DeployRoot.problem("/data", "/data/users/me"), "above the home");
        assertEquals(DeployRoot.Problem.TOO_WIDE, DeployRoot.problem("/Users", "/Users/me"));
    }

    @Test
    void aRelativeOrIrregularPathIsRefused() {
        assertEquals(DeployRoot.Problem.EMPTY, DeployRoot.problem("  ", "/home/me"));
        assertEquals(DeployRoot.Problem.NOT_ABSOLUTE, DeployRoot.problem("apps/demo", "/home/me"));
        assertEquals(DeployRoot.Problem.NOT_ABSOLUTE, DeployRoot.problem("~/demo", "/home/me"));
        assertEquals(DeployRoot.Problem.NOT_PLAIN, DeployRoot.problem("/srv/../etc", "/home/me"));
        assertEquals(DeployRoot.Problem.NOT_PLAIN, DeployRoot.problem("/srv//app", "/home/me"));
    }
}
