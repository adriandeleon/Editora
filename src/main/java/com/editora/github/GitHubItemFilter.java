package com.editora.github;

import java.util.Locale;

import com.editora.github.IssueListParser.Issue;
import com.editora.github.PrListParser.PullRequest;
import com.editora.github.RunListParser.WorkflowRun;

/**
 * The GitHub tool window's filter predicate: a case-insensitive substring match over the fields a row
 * actually shows (or puts in its tooltip), so what the user can read is what they can search. Pure and
 * toolkit-free — {@code GitHubPanel} only wires it to a {@code FilteredList}.
 *
 * <p>A leading {@code #} on a query is dropped so both {@code 42} and {@code #42} find issue/PR 42.
 */
public final class GitHubItemFilter {

    private GitHubItemFilter() {}

    /**
     * Whether {@code item} (a {@link PullRequest}, {@link Issue} or {@link WorkflowRun}) matches
     * {@code query}. A blank query matches everything; an unknown item type is kept rather than hidden.
     */
    public static boolean matches(Object item, String query) {
        String q = normalize(query);
        if (q.isEmpty()) {
            return true;
        }
        if (item instanceof PullRequest pr) {
            return contains(String.valueOf(pr.number()), q)
                    || contains(pr.title(), q)
                    || contains(pr.authorLogin(), q)
                    || contains(pr.headRefName(), q)
                    || contains(pr.baseRefName(), q)
                    || contains(pr.state(), q);
        }
        if (item instanceof Issue issue) {
            return contains(String.valueOf(issue.number()), q)
                    || contains(issue.title(), q)
                    || contains(issue.authorLogin(), q)
                    || contains(issue.state(), q)
                    || issue.labels().stream().anyMatch(l -> contains(l, q));
        }
        if (item instanceof WorkflowRun run) {
            return contains(run.workflowName(), q)
                    || contains(run.displayTitle(), q)
                    || contains(run.headBranch(), q)
                    || contains(run.event(), q)
                    || contains(run.status(), q)
                    || contains(run.conclusion(), q);
        }
        return true;
    }

    /** Lower-cased, trimmed, with a leading {@code #} dropped so "#42" and "42" behave the same. */
    private static String normalize(String query) {
        String q = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        return q.startsWith("#") ? q.substring(1) : q;
    }

    private static boolean contains(String s, String lowerQuery) {
        return s != null && s.toLowerCase(Locale.ROOT).contains(lowerQuery);
    }
}
