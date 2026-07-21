package com.editora.github;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrCreateArgsTest {

    @Test
    void buildsTitleBodyBaseDraft() {
        List<String> args = PrCreateArgs.build("My PR", "Body text", "main", true);
        assertEquals(
                List.of("pr", "create", "--title", "My PR", "--body", "Body text", "--base", "main", "--draft"), args);
    }

    @Test
    void blankBodyStillPassesEmptyBodyFlag() {
        // gh must never drop into an interactive editor (ProcessRunner closes stdin) — always pass --body.
        List<String> args = PrCreateArgs.build("t", "", "", false);
        assertEquals(List.of("pr", "create", "--title", "t", "--body", ""), args);
        assertFalse(args.contains("--base"));
        assertFalse(args.contains("--draft"));
    }

    @Test
    void emitsOneRepeatedFlagPerReviewerAssigneeAndLabel() {
        List<String> args = PrCreateArgs.build("t", "b", "", false, "alice, bob", "carol", "bug, ui");
        assertEquals(
                List.of(
                        "pr",
                        "create",
                        "--title",
                        "t",
                        "--body",
                        "b",
                        "--reviewer",
                        "alice",
                        "--reviewer",
                        "bob",
                        "--assignee",
                        "carol",
                        "--label",
                        "bug",
                        "--label",
                        "ui"),
                args);
    }

    @Test
    void blankPeopleFieldsOmitTheirFlagsEntirely() {
        List<String> args = PrCreateArgs.build("t", "b", "", false, "", "   ", null);
        assertFalse(args.contains("--reviewer"));
        assertFalse(args.contains("--assignee"));
        assertFalse(args.contains("--label"));
    }

    @Test
    void splitTrimsBlanksAndDedupesCaseInsensitively() {
        assertEquals(List.of("a", "b"), PrCreateArgs.split(" a , ,b,  A "));
        assertEquals(List.of(), PrCreateArgs.split(null));
    }

    @Test
    void handlesDropALeadingAtButKeepAtMe() {
        // gh wants the bare handle; @me is its own token for the authenticated user.
        assertEquals(List.of("octocat", "org/team", "@me"), PrCreateArgs.handles("@octocat, org/team, @me"));
    }

    @Test
    void labelsKeepALeadingAtSinceTheyAreNotHandles() {
        List<String> args = PrCreateArgs.build("t", "b", "", false, null, null, "@release");
        assertEquals("@release", args.get(args.indexOf("--label") + 1));
    }

    @Test
    void nullTitleAndBodyBecomeEmptyStrings() {
        List<String> args = PrCreateArgs.build(null, null, null, false);
        assertTrue(args.contains("--title"));
        assertEquals("", args.get(args.indexOf("--title") + 1));
        assertEquals("", args.get(args.indexOf("--body") + 1));
    }
}
