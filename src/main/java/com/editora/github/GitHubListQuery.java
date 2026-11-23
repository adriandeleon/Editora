package com.editora.github;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What one list of the GitHub tool window (or a picker) asks {@code gh} for: which state, whether only the
 * user's own items, how many, and — when the filter box found nothing among the rows already listed — a search
 * GitHub runs over all of them ({@code --search}; pull requests and issues only). The {@code gh} argument lists are built here so they can be tested without
 * running anything.
 *
 * <p>{@code gh} has no "are there more?" answer, so a list asks for {@link #fetchLimit() one more} than it
 * shows and {@link #page} cuts the extra one off again: its presence is the answer. Pure — unit-tested.
 */
public record GitHubListQuery(State state, boolean mine, int limit, String search) {

    /** The state filter of {@code gh pr list} / {@code gh issue list}. */
    public enum State {
        OPEN,
        CLOSED,
        MERGED,
        ALL;

        String flag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** The first page of a pull-request or issue list. */
    public static final int DEFAULT_LIMIT = 50;

    /** The first page of the workflow-run list. */
    public static final int DEFAULT_RUN_LIMIT = 30;

    /** What a picker asks for: it has no "load more" row, so it lists generously. */
    public static final int PICKER_LIMIT = 200;

    public GitHubListQuery {
        state = state == null ? State.OPEN : state;
        limit = Math.max(1, limit);
        search = search == null ? "" : search.strip();
    }

    public GitHubListQuery(State state, boolean mine, int limit) {
        this(state, mine, limit, "");
    }

    /** The same query, searching GitHub for {@code text} (blank: no search). */
    public GitHubListQuery searching(String text) {
        return new GitHubListQuery(state, mine, limit, text);
    }

    /** Open items, everyone's, {@code limit} of them. */
    public static GitHubListQuery open(int limit) {
        return new GitHubListQuery(State.OPEN, false, limit);
    }

    /** One more than is shown — see the class comment. */
    public int fetchLimit() {
        return limit + 1;
    }

    /** The same query with {@code more} further rows (the "load more" row). */
    public GitHubListQuery grownBy(int more) {
        return new GitHubListQuery(state, mine, limit + Math.max(1, more), search);
    }

    /** {@code gh pr list} arguments. */
    public List<String> prArgs() {
        List<String> args = new ArrayList<>(List.of("pr", "list", "--state", state.flag()));
        if (mine) {
            args.addAll(List.of("--author", "@me"));
        }
        args.addAll(List.of(
                "--limit",
                String.valueOf(fetchLimit()),
                "--json",
                "number,title,author,headRefName,baseRefName,state,isDraft,updatedAt,url"));
        addSearch(args);
        return args;
    }

    /** {@code gh issue list} arguments. An issue is never "merged": that state lists the closed ones. */
    public List<String> issueArgs() {
        State s = state == State.MERGED ? State.CLOSED : state;
        List<String> args = new ArrayList<>(List.of("issue", "list", "--state", s.flag()));
        if (mine) {
            args.addAll(List.of("--author", "@me"));
        }
        args.addAll(List.of(
                "--limit", String.valueOf(fetchLimit()), "--json", "number,title,author,state,labels,updatedAt,url"));
        addSearch(args);
        return args;
    }

    private void addSearch(List<String> args) {
        if (!search.isEmpty()) {
            args.add("--search");
            args.add(search);
        }
    }

    /**
     * The number a filter text names on its own ({@code #1234} or {@code 1234}), or 0 when it is anything else —
     * the tool window opens that item directly instead of searching for it. Pure.
     */
    public static int itemNumber(String text) {
        String t = text == null ? "" : text.strip();
        if (t.startsWith("#")) {
            t = t.substring(1);
        }
        if (t.isEmpty() || t.length() > 9 || !t.chars().allMatch(Character::isDigit)) {
            return 0;
        }
        return Integer.parseInt(t);
    }

    /** {@code gh run list} arguments (runs have no state filter here; {@code mine} is not applied). */
    public List<String> runArgs() {
        return List.of(
                "run",
                "list",
                "--limit",
                String.valueOf(fetchLimit()),
                "--json",
                "databaseId,displayTitle,workflowName,headBranch,status,conclusion,event,createdAt,url");
    }

    /** The rows to show, and whether {@code gh} had more than that. */
    public record Page<T>(List<T> items, boolean more) {}

    /** Cuts what {@code gh} returned for this query down to {@link #limit()} rows. */
    public <T> Page<T> page(List<T> fetched) {
        List<T> all = fetched == null ? List.of() : fetched;
        return all.size() > limit ? new Page<>(List.copyOf(all.subList(0, limit)), true) : new Page<>(all, false);
    }
}
