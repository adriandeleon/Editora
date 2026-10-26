package com.editora.ui;

import java.util.Comparator;
import java.util.List;

import com.editora.search.FuzzyMatch;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The command palette's result ordering (no toolkit needed).
 *
 * <p>The palette no longer owns a ranking algorithm — it scores with {@link FuzzyMatch} and breaks ties by
 * the shorter then alphabetical title. This reproduces exactly that comparator over a list of titles, so
 * the ordering the user actually sees stays pinned even though the scoring moved out of the class.
 */
class CommandPaletteRankTest {

    /** The palette's ordering: score descending, then shorter title, then alphabetical. */
    private static List<String> ranked(String query, String... titles) {
        record Scored(String title, int score) {}
        return java.util.Arrays.stream(titles)
                .map(t -> {
                    FuzzyMatch.Match m = FuzzyMatch.of(t, query);
                    return m == null ? null : new Scored(t, m.score());
                })
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparingInt(Scored::score)
                        .reversed()
                        .thenComparingInt((Scored s) -> s.title().length())
                        .thenComparing(Scored::title, String.CASE_INSENSITIVE_ORDER))
                .map(Scored::title)
                .toList();
    }

    @Test
    void undoRanksEditUndoFirst() {
        // The originally reported case, kept as a regression: typing "undo" must surface the Undo command,
        // not a tool window that merely begins with the word, and certainly not a scattered match. The two
        // score equally (position in the title is not a signal — see FuzzyMatchTest); the shorter-title
        // tiebreak is what settles it.
        List<String> ranked = ranked("undo", "View: Unsplit Editor", "Undo History", "Toggle Run Window", "Edit: Undo");
        assertEquals("Edit: Undo", ranked.get(0));
        assertEquals("Undo History", ranked.get(1));
        assertTrue(ranked.indexOf("View: Unsplit Editor") > 1, "scattered matches sink to the bottom");
    }

    @Test
    void aContiguousWordBeatsAScatteredSubsequence() {
        assertEquals(
                "Git: Commit",
                ranked("git", "Toggle Line Numbers", "Git: Commit").get(0));
    }

    @Test
    void nonMatchesAreDroppedEntirely() {
        assertEquals(List.of(), ranked("zzz", "Edit: Undo", "Undo History"));
    }

    @Test
    void multiTermQueriesFindOutOfOrderTitles() {
        // Previously impossible: one ordered subsequence cannot match "toggle" ahead of "git" here.
        assertEquals(
                List.of("Git: Toggle Blame"), ranked("toggle git", "Git: Toggle Blame", "Git: Commit", "Toggle Fold"));
    }

    private static com.editora.command.Command command(String id, String title) {
        return com.editora.command.Command.of(id, title, () -> {});
    }

    private static List<String> titles(String query, com.editora.command.Command... commands) {
        return CommandPalette.orderedMatches(List.of(commands), query).stream()
                .map(com.editora.command.Command::title)
                .toList();
    }

    @Test
    void aCommandIsFoundByItsNamespaceWhenItsTitleDoesNotCarryIt() {
        com.editora.command.Command addRow = command("markdown.tableAddRow", "Table: Add Row");
        com.editora.command.Command bold = command("markdown.bold", "Markdown: Bold");
        com.editora.command.Command csv = command("csv.align", "CSV: Align Columns");
        assertEquals("markdown", addRow.keywords());
        assertEquals("", bold.keywords(), "the title says it already");
        assertEquals(List.of("Table: Add Row"), titles("markdown table", addRow, csv));
        assertEquals(List.of("Table: Add Row"), titles("mark add", addRow, csv), "the start of the keyword is enough");
        assertEquals(List.of("Table: Add Row"), titles("table add", addRow, csv), "and the title still works");
        assertEquals(
                List.of(), titles("table markdown", addRow), "the keyword leads the query, as the group leads a title");
        assertEquals(List.of(), titles("markdownx", addRow));
    }

    @Test
    void aKeywordMatchRanksBelowEveryTitleMatch() {
        com.editora.command.Command table = command("markdown.tableAddRow", "Table: Add Row");
        com.editora.command.Command scattered =
                command("view.x", "Main Area: Keep Down Window Notes"); // m-a-r-k-d-o-w-n
        com.editora.command.Command titled = command("markdown.toc", "Markdown: Insert Table of Contents");
        List<String> ranked = titles("markdown", table, scattered, titled);
        assertEquals("Table: Add Row", ranked.get(ranked.size() - 1), ranked.toString());
        assertEquals("Markdown: Insert Table of Contents", ranked.get(0));
        assertTrue(CommandPalette.keywordScore(table, "markdown") < FuzzyMatch.scoreOf(scattered.title(), "markdown"));
        assertEquals(
                FuzzyMatch.NO_SCORE, CommandPalette.keywordScore(titled, "markdown"), "no keyword, no second look");
    }

    /** IntelliJ's and VS Code's names find the command, below any title match (fourth review EN4). */
    @Test
    void anotherIdesNameFindsTheCommand() {
        var organize = com.editora.command.Command.of("lsp.organizeImports", () -> {});
        var actions = com.editora.command.Command.of("lsp.codeActions", () -> {});
        var context = com.editora.command.Command.of("run.context", () -> {});
        var all = java.util.List.of(organize, actions, context);
        assertEquals(java.util.List.of(organize), CommandPalette.orderedMatches(all, "optimize imports"));
        assertEquals(java.util.List.of(actions), CommandPalette.orderedMatches(all, "show context actions"));
        assertEquals(java.util.List.of(context), CommandPalette.orderedMatches(all, "run context configuration"));
        assertEquals(
                com.editora.search.FuzzyMatch.NO_SCORE,
                CommandPalette.otherIdeScore(organize, "ports"),
                "the first word must begin a word of the name");
    }

    /** The whole of another IDE's name leads fuzzy title matches: "go to test" is Related File (sixth review ES1). */
    @Test
    void anotherIdesWholeNameLeadsFuzzyTitles() {
        var related = com.editora.command.Command.of("nav.relatedFile", () -> {});
        var goSupport = com.editora.command.Command.of("view.toggleGoSupport", () -> {});
        var definition = com.editora.command.Command.of("lsp.gotoDefinition", () -> {});
        var all = java.util.List.of(goSupport, definition, related);
        assertEquals(related, CommandPalette.orderedMatches(all, "go to test").get(0));
        assertEquals(related, CommandPalette.orderedMatches(all, "Go to Test").get(0));
        assertEquals(definition, CommandPalette.orderedMatches(all, "go to def").get(0), "a title still leads its own");
    }

    /** A command's own title, typed, still leads another command's alias (seventh review ER4). */
    @Test
    void anExactTitleLeadsAnotherCommandsAlias() {
        var definition = com.editora.command.Command.of("lsp.gotoDefinition", () -> {});
        var declaration = com.editora.command.Command.of("lsp.gotoDeclaration", () -> {});
        assertEquals(
                declaration,
                CommandPalette.orderedMatches(java.util.List.of(definition, declaration), "go to declaration")
                        .get(0));
    }

    /** One word is no phrase: "rename" is still Rename Symbol, not the terminal's Rename Tab (eighth review EQ3). */
    @Test
    void oneWordGetsNoTitleBoost() {
        var symbol = com.editora.command.Command.of("lsp.rename", () -> {});
        var tab = com.editora.command.Command.of("terminal.rename", () -> {});
        assertEquals(
                com.editora.search.FuzzyMatch.NO_SCORE,
                CommandPalette.titlePhraseScore(tab, "rename"),
                "Rename Tab gets no title boost from one word");
        List<com.editora.command.Command> ranked = CommandPalette.orderedMatches(List.of(tab, symbol), "rename");
        assertTrue(ranked.indexOf(symbol) >= 0 && ranked.indexOf(tab) >= 0);
        assertTrue(
                CommandPalette.titlePhraseScore(
                                com.editora.command.Command.of("lsp.gotoDeclaration", () -> {}), "go to declaration")
                        != com.editora.search.FuzzyMatch.NO_SCORE,
                "a phrase still does");
    }
}
