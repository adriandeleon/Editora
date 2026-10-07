package com.editora.github;

import java.util.List;

import com.editora.github.GitHubListQuery.State;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitHubListQueryTest {

    @Test
    void asksForOneRowMoreThanItShowsSoTruncationIsVisible() {
        GitHubListQuery q = GitHubListQuery.open(50);
        List<String> args = q.prArgs();
        assertEquals("51", args.get(args.indexOf("--limit") + 1));

        List<Integer> fetched = java.util.stream.IntStream.range(0, 51).boxed().toList();
        GitHubListQuery.Page<Integer> page = q.page(fetched);
        assertEquals(50, page.items().size());
        assertTrue(page.more(), "gh had a 51st row");

        GitHubListQuery.Page<Integer> exact = q.page(fetched.subList(0, 50));
        assertEquals(50, exact.items().size());
        assertFalse(exact.more(), "exactly the limit is the whole list");
        assertFalse(q.page(null).more());
    }

    @Test
    void loadMoreRaisesTheLimitAndKeepsTheFilter() {
        GitHubListQuery q = new GitHubListQuery(State.CLOSED, true, 50).grownBy(50);
        assertEquals(100, q.limit());
        assertEquals(State.CLOSED, q.state());
        assertTrue(q.mine());
    }

    @Test
    void pullRequestArgumentsCarryTheStateAndMine() {
        assertEquals(
                List.of("pr", "list", "--state", "merged", "--author", "@me", "--limit", "51", "--json"),
                new GitHubListQuery(State.MERGED, true, 50).prArgs().subList(0, 9));
        List<String> all = new GitHubListQuery(State.ALL, false, 10).prArgs();
        assertEquals("all", all.get(3));
        assertFalse(all.contains("--author"));
    }

    @Test
    void anIssueIsNeverMergedSoThatStateListsTheClosedOnes() {
        assertEquals(
                "closed",
                new GitHubListQuery(State.MERGED, false, 50).issueArgs().get(3));
        assertEquals("open", new GitHubListQuery(null, false, 50).issueArgs().get(3));
        assertTrue(new GitHubListQuery(State.OPEN, true, 50).issueArgs().containsAll(List.of("--author", "@me")));
    }

    @Test
    void runArgumentsOnlyCarryTheLimit() {
        List<String> args = new GitHubListQuery(State.CLOSED, true, 30).runArgs();
        assertEquals(List.of("run", "list", "--limit", "31", "--json"), args.subList(0, 5));
        assertFalse(args.contains("--state"));
    }

    @Test
    void aLimitIsAtLeastOne() {
        assertEquals(1, new GitHubListQuery(State.OPEN, false, 0).limit());
    }
}
