package com.editora.diff;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import com.editora.diff.ConflictParser.ConflictFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ThreeWayMerge} against the merge Git itself performs ({@code git merge-tree --write-tree}), over
 * random small files with many repeated lines — where the two line-alignment algorithms part ways.
 *
 * <p>The two are allowed to disagree. What must hold is what the conflict resolver promises: for a file Git
 * wrote with conflict markers, the conflicts it shows ({@link ThreeWayMerge#sourceFor}) always include a
 * conflict, are never put to the user as "the file changed", and reproduce Git's file when nothing is
 * resolved. Bounded and seeded, so it is the same run every time.
 */
class ThreeWayMergeGitOracleTest {

    private static final int CASES = 120;
    private static final long SEED = 20261006L;

    private Path repo;
    private Path emptyConfig;

    @Test
    void theResolverNeverHidesOrMisattributesAConflictGitReported(@TempDir Path dir) throws Exception {
        repo = Files.createDirectory(dir.resolve("repo"));
        emptyConfig = Files.writeString(dir.resolve("gitconfig"), "");
        Assumptions.assumeTrue(gitCanMergeTrees(), "git with merge-tree --write-tree (2.38+) is required");

        Random random = new Random(SEED);
        int gitConflicts = 0;
        for (int c = 0; c < CASES; c++) {
            int distinct = 2 + random.nextInt(5);
            String[] alphabet = new String[distinct + 3];
            for (int i = 0; i < distinct; i++) {
                alphabet[i] = String.valueOf((char) ('a' + i));
            }
            alphabet[distinct] = "X";
            alphabet[distinct + 1] = "Y";
            alphabet[distinct + 2] = "";
            List<String> base = new ArrayList<>();
            int length = random.nextInt(11);
            for (int i = 0; i < length; i++) {
                base.add(alphabet[random.nextInt(distinct)]);
            }
            String baseText = text(base);
            String oursText = text(mutate(random, base, alphabet, 1 + random.nextInt(3)));
            String theirsText = text(mutate(random, base, alphabet, 1 + random.nextInt(3)));

            Git merge = git(
                    null,
                    "merge-tree",
                    "--write-tree",
                    "--merge-base=" + tree(baseText),
                    tree(oursText),
                    tree(theirsText));
            if (merge.exit() != 1) {
                continue; // 0: Git merged cleanly, nothing for a resolver to do
            }
            gitConflicts++;
            String treeId = merge.out().lines().findFirst().orElseThrow();
            List<String> gitLines = DiffText.parse(
                            git(null, "cat-file", "-p", treeId + ":f").out())
                    .lines();
            ConflictFile written = ConflictParser.parse(gitLines);
            ConflictFile merged =
                    ThreeWayMerge.merge(baseText, oursText, theirsText).file();
            String where = "case " + c + "\nbase=" + baseText + "ours=" + oursText + "theirs=" + theirsText;

            assertTrue(written.hasConflicts(), "Git's conflict markers are recognised: " + where);
            ThreeWayMerge.Source source = ThreeWayMerge.sourceFor(merged, written);
            assertNotEquals(ThreeWayMerge.Source.ASK, source, "not a changed file: " + where);
            ConflictFile shown = source == ThreeWayMerge.Source.MERGE ? merged : written;
            assertTrue(shown.hasConflicts(), "a conflict Git reported is shown: " + where);
            if (source == ThreeWayMerge.Source.FILE_MARKERS) {
                assertEquals(gitLines, ConflictParser.resolve(written, List.of()), "left unresolved: " + where);
            }
        }
        // The run is only a regression test while it still reaches the cases it exists for.
        assertTrue(gitConflicts >= 10, "too few conflicting cases to mean anything: " + gitConflicts);
    }

    private static List<String> mutate(Random random, List<String> base, String[] alphabet, int edits) {
        List<String> out = new ArrayList<>(base);
        for (int i = 0; i < edits; i++) {
            int kind = random.nextInt(3);
            if (kind == 0 || out.isEmpty()) {
                out.add(random.nextInt(out.size() + 1), alphabet[random.nextInt(alphabet.length)]);
            } else if (kind == 1) {
                out.remove(random.nextInt(out.size()));
            } else {
                out.set(random.nextInt(out.size()), alphabet[random.nextInt(alphabet.length)]);
            }
        }
        return out;
    }

    private static String text(List<String> lines) {
        return lines.isEmpty() ? "" : String.join("\n", lines) + "\n";
    }

    private boolean gitCanMergeTrees() {
        try {
            if (git(null, "init", "-q").exit() != 0) {
                return false;
            }
            String empty = tree("");
            return git(null, "merge-tree", "--write-tree", "--merge-base=" + empty, empty, empty)
                            .exit()
                    == 0;
        } catch (IOException | InterruptedException | RuntimeException unavailable) {
            return false;
        }
    }

    /** The id of a tree holding {@code text} as the file {@code f}. */
    private String tree(String text) throws IOException, InterruptedException {
        String blob = git(text, "hash-object", "-w", "--stdin").out().strip();
        return git("100644 blob " + blob + "\tf\n", "mktree").out().strip();
    }

    private record Git(int exit, String out) {}

    private Git git(String stdin, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command).directory(repo.toFile());
        builder.environment().put("GIT_CONFIG_GLOBAL", emptyConfig.toString());
        builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process process = builder.start();
        try (var in = process.getOutputStream()) {
            if (stdin != null) {
                in.write(stdin.getBytes(StandardCharsets.UTF_8));
            }
        }
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new Git(process.waitFor(), out);
    }
}
