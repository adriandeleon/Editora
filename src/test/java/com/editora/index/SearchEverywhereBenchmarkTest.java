package com.editora.index;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import com.editora.search.FuzzyMatch;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * What one keystroke in Search Everywhere costs, as a function of corpus size.
 *
 * <p>The picker's counterpart to {@code scripts/measure-startup.sh} and {@code TypingLatencyBenchmarkTest}:
 * #876 was opened without one, so the first question ("is this actually slow, and where?") could not be
 * answered without writing throwaway code. Both sources run on the FX thread, debounced but per keystroke,
 * so their cost is frame budget.
 *
 * <p>Not an assertion test — it prints a distribution, like {@code bareCodeAreaControl}. Run it with:
 *
 * <pre>./mvnw test -Dtest=SearchEverywhereBenchmarkTest \
 *     -Djunit.jupiter.conditions.deactivate='org.junit.*DisabledCondition'</pre>
 *
 * <p>The second flag is needed as well as the first: {@code -Dtest=} selects the class but JUnit still
 * honours {@code @Disabled}, so without it the run reports success having executed nothing.
 *
 * <p><b>Synthetic, and that is a real limitation.</b> Names are drawn from a small vocabulary, so how much
 * of the corpus a short query matches — the thing that actually drives the cost — is a property of this
 * generator, not of any real project. Use it to compare a change against the same generator, which is what
 * it is for; do not quote the absolute numbers as measurements of a real codebase.
 *
 * <p>The file half is <em>mirrored</em> rather than driven through {@code IndexCoordinator}, which needs an
 * FX window and a filesystem walk. It reproduces that method's scan and ranking, so it tracks the real one
 * only as long as someone keeps it in step — {@code IndexCoordinatorFxTest} is what pins the real
 * behaviour.
 */
class SearchEverywhereBenchmarkTest {

    private static final String[] WORDS = {
        "get", "set", "build", "render", "parse", "user", "name", "index", "node", "tree", "handle", "apply", "resolve",
        "config", "buffer", "token", "span", "value", "editor", "window", "panel", "command", "search", "symbol",
        "file", "path", "line", "column", "state", "action"
    };
    private static final String[] DIRS = {
        "ui", "editor", "index", "search", "config", "command", "git", "lsp", "dap", "run"
    };

    /** Symbols per file — near this repo's own average. */
    private static final int SYMBOLS_PER_FILE = 24;
    /** What the picker asks each source for. */
    private static final int LIMIT = 40;

    private static final int WARMUP = 200;
    private static final int MEASURE = 100;

    @Test
    @Disabled("measurement harness, not a check; run explicitly with -Dtest=SearchEverywhereBenchmarkTest")
    void searchLatencyByCorpusSize() {
        System.out.printf("%-8s %-9s | %10s %10s | %10s%n", "files", "query", "symbols", "files", "TOTAL");
        System.out.println("-".repeat(57));
        for (int files : new int[] {650, 6_500, 20_000}) {
            Corpus c = corpus(files);
            for (String q : new String[] {"e", "ge", "user", "getUser"}) {
                for (int i = 0; i < WARMUP; i++) {
                    c.index().search(q, LIMIT);
                    scanFiles(c, q);
                }
                long[] sym = new long[MEASURE];
                long[] fil = new long[MEASURE];
                for (int i = 0; i < MEASURE; i++) {
                    long t0 = System.nanoTime();
                    c.index().search(q, LIMIT);
                    long t1 = System.nanoTime();
                    scanFiles(c, q);
                    long t2 = System.nanoTime();
                    sym[i] = t1 - t0;
                    fil[i] = t2 - t1;
                }
                double s = median(sym) / 1e6;
                double f = median(fil) / 1e6;
                System.out.printf("%-8d %-9s | %7.2f ms %7.2f ms | %7.2f ms%n", files, "\"" + q + "\"", s, f, s + f);
            }
            System.out.println();
        }
    }

    /** A generated project: the real {@link SymbolIndex}, plus the file corpus as the coordinator holds it. */
    private record Corpus(SymbolIndex index, List<String> relativePaths) {}

    private static Corpus corpus(int files) {
        Random r = new Random(42);
        SymbolIndex index = new SymbolIndex();
        List<String> rels = new ArrayList<>(files);
        for (int f = 0; f < files; f++) {
            String cls = cap(WORDS[r.nextInt(WORDS.length)]) + cap(WORDS[r.nextInt(WORDS.length)]);
            String rel = DIRS[r.nextInt(DIRS.length)] + "/" + DIRS[r.nextInt(DIRS.length)] + "/" + cls + f + ".java";
            rels.add(rel);
            List<Symbol> syms = new ArrayList<>(SYMBOLS_PER_FILE);
            for (int i = 0; i < SYMBOLS_PER_FILE; i++) {
                syms.add(new Symbol(name(r), SymbolKind.METHOD, i + 1, 0, cls));
            }
            index.put(Path.of("/proj").resolve(rel), syms);
        }
        return new Corpus(index, List.copyOf(rels));
    }

    /** The scoring half of {@code IndexCoordinator.searchFiles} — the part that is linear in the corpus. */
    private static int scanFiles(Corpus c, String query) {
        int matched = 0;
        for (String rel : c.relativePaths()) {
            if (FuzzyMatch.scoreOfPath(rel, query) != FuzzyMatch.NO_SCORE) {
                matched++;
            }
        }
        return matched;
    }

    private static String name(Random r) {
        StringBuilder sb = new StringBuilder(WORDS[r.nextInt(WORDS.length)]);
        for (int e = 0, n = 1 + r.nextInt(2); e < n; e++) {
            sb.append(cap(WORDS[r.nextInt(WORDS.length)]));
        }
        return sb.toString();
    }

    private static String cap(String s) {
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static long median(long[] a) {
        long[] b = a.clone();
        Arrays.sort(b);
        return b[b.length / 2];
    }
}
