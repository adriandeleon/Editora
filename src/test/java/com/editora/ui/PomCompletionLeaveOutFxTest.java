package com.editora.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.editora.editor.EditorBuffer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A degraded Maven coordinate completion takes the root's poms that are malformed on disk out of the server's
 * workspace and asks again (review 2, E3); an honest empty answer changes nothing; a failure with nothing malformed
 * any more, a left-out pom saved or fixed on disk, restarts that root's server alone (review 3, E21–E24).
 */
@Tag("fx")
class PomCompletionLeaveOutFxTest {

    @TempDir
    Path tmp;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private final List<String> statuses = new CopyOnWriteArrayList<>();
    private final List<Path> shutDown = new CopyOnWriteArrayList<>();

    private static final String GOOD = "<project><artifactId>a</artifactId></project>";

    /** A Maven server per root: its left-out poms and logged failures. */
    private final class FakeMaven implements PomServerSupport.MavenWorkspace {
        final Map<Path, List<Path>> out = new ConcurrentHashMap<>();
        final AtomicInteger failures = new AtomicInteger();
        final List<Path> roots = new CopyOnWriteArrayList<>();

        @Override
        public @Nullable Path root(Path file) {
            return roots.stream().filter(file::startsWith).findFirst().orElse(null);
        }

        @Override
        public List<Path> leftOut(Path file) {
            Path r = root(file);
            return r == null ? List.of() : List.copyOf(out.getOrDefault(r, List.of()));
        }

        @Override
        public List<Path> leaveOut(Path file, List<Path> poms) {
            Path r = root(file);
            List<Path> mine = out.computeIfAbsent(r, k -> new CopyOnWriteArrayList<>());
            List<Path> fresh = poms.stream().filter(p -> !mine.contains(p)).toList();
            mine.addAll(fresh);
            return fresh;
        }

        @Override
        public int completionFailures(Path file) {
            return failures.get();
        }

        @Override
        public void shutdown(Path root) {
            shutDown.add(root);
            out.remove(root);
        }
    }

    private final FakeMaven maven = new FakeMaven();
    private final List<EditorBuffer> synced = new CopyOnWriteArrayList<>();

    private PomServerSupport support() {
        return new PomServerSupport(statuses::add, maven, each -> {}, b -> null, synced::add);
    }

    private Path root(String name) throws IOException {
        Path r = Files.createDirectories(tmp.resolve(name));
        maven.roots.add(r);
        Files.writeString(r.resolve("pom.xml"), GOOD);
        return r;
    }

    private static Path pom(Path root, String dir, String text) throws IOException {
        return Files.writeString(Files.createDirectories(root.resolve(dir)).resolve("pom.xml"), text);
    }

    /** Runs one completion through {@code support}; returns "again", "deliver" or "none" (not taken over). */
    private String complete(PomServerSupport support, Path file, List<String> labels, boolean failed) throws Exception {
        var ran = new String[] {"none"};
        var done = new CountDownLatch(1);
        boolean[] taken = new boolean[1];
        FxTestSupport.runOnFx(() -> {
            int before = support.failuresBefore(file);
            if (failed) {
                maven.failures.incrementAndGet(); // the server logged its workspace failure for this request
            }
            taken[0] = support.onCompletion(
                    "artifactId",
                    labels,
                    file,
                    before,
                    () -> {
                        ran[0] = "again";
                        done.countDown();
                    },
                    () -> {
                        ran[0] = "deliver";
                        done.countDown();
                    });
        });
        if (taken[0]) {
            assertTrue(done.await(10, TimeUnit.SECONDS));
        }
        return ran[0];
    }

    private static final List<String> MARKUP = List.of("<!--", "<![CDATA[");

    /** Lets the search threads and the FX queue settle. */
    private static void settle() throws Exception {
        for (int i = 0; i < 20; i++) {
            Thread.sleep(25);
            FxTestSupport.runOnFx(() -> {});
        }
    }

    @Test
    void theMalformedPomsOnDiskAreLeftOutAndTheCompletionAskedAgain() throws Exception {
        Path r = root("reactor");
        Path a = pom(r, "a", "<project><artifactId>a\n</project>");
        Path b = pom(r, "b", "<project>");

        assertEquals("again", complete(support(), r.resolve("pom.xml"), MARKUP, true));

        assertEquals(java.util.Set.of(a, b), java.util.Set.copyOf(maven.out.get(r)));
        String first = PomServerSupport.displayPath(r, maven.out.get(r).get(0));
        assertTrue(statuses.get(0).startsWith("Left out " + first), statuses.get(0));
        assertTrue(statuses.get(0).contains("one more pom"), "plural-safe: " + statuses.get(0));
        assertTrue(shutDown.isEmpty());
    }

    @Test
    void anHonestEmptyAnswerLeavesNothingOutAndSaysNothing() throws Exception {
        Path r = root("reactor");
        pom(r, "app", GOOD); // an open tab may hold half-typed text: the disk is what counts (E21 falsepos)

        assertEquals("deliver", complete(support(), r.resolve("pom.xml"), MARKUP, false));

        assertTrue(maven.out.isEmpty());
        assertTrue(statuses.isEmpty(), statuses.toString());
        assertTrue(shutDown.isEmpty());
    }

    @Test
    void aFailureWithNothingMalformedOnDiskRestartsThatRootOnce() throws Exception {
        Path r = root("reactor");
        root("other");
        PomServerSupport support = support();

        assertEquals("deliver", complete(support, r.resolve("pom.xml"), MARKUP, true));
        settle();

        assertEquals(List.of(r), shutDown, "only that root (E23)");
        assertEquals(tr("status.maven.restartStale", "reactor"), statuses.get(statuses.size() - 1));

        // the restart cleared the root's throttle: the next failure is searched at once, and not restarted again
        assertEquals("deliver", complete(support, r.resolve("pom.xml"), MARKUP, true));
        settle();
        assertEquals(List.of(r), shutDown, "not again within the guard: no restart loop");
    }

    @Test
    void theThrottleIsPerRoot() throws Exception {
        Path r1 = root("one");
        Path r2 = root("two");
        pom(r1, "x", "<project>");
        pom(r2, "y", "<project>");
        PomServerSupport support = support();

        assertEquals("again", complete(support, r1.resolve("pom.xml"), MARKUP, true));
        assertEquals("again", complete(support, r2.resolve("pom.xml"), MARKUP, true), "another root: searched too");
        assertEquals("none", complete(support, r1.resolve("pom.xml"), MARKUP, true), "the same root: throttled");
    }

    @Test
    void aRealAnswerIsNotTakenOver() throws Exception {
        Path r = root("reactor");
        pom(r, "a", "<project>");
        assertEquals("none", complete(support(), r.resolve("pom.xml"), List.of("commons-lang3"), false));
        settle();
        assertTrue(maven.out.isEmpty());
        assertTrue(statuses.isEmpty());
    }

    @Test
    void aLeftOutPomFixedOnDiskIsTakenBackAtTheNextCompletion() throws Exception {
        Path r = root("reactor");
        Path a = pom(r, "a", GOOD); // fixed by a checkout after it was left out
        maven.out.put(r, new CopyOnWriteArrayList<>(List.of(a)));

        assertEquals("none", complete(support(), r.resolve("pom.xml"), List.of("commons-lang3"), false));
        settle();

        assertEquals(List.of(r), shutDown, "E24");
        assertEquals(tr("status.maven.pomBack", PomServerSupport.displayPath(r, a)), statuses.get(0));
    }

    @Test
    void aLeftOutPomSavedWellFormedRestartsOnlyItsRoot() throws Exception {
        Path r = root("reactor");
        root("outside");
        Path a = pom(r, "a", "<project>");
        maven.out.put(r, new CopyOnWriteArrayList<>(List.of(a)));
        PomServerSupport support = support();
        boolean[] restarted = new boolean[2];

        FxTestSupport.runOnFx(() -> {
            restarted[0] = support.saved(a, "<project>");
            restarted[1] = support.saved(a, GOOD);
        });

        assertFalse(restarted[0], "still malformed");
        assertTrue(restarted[1]);
        assertEquals(List.of(r), shutDown, "not the outside reactor's server (E23, A23)");
    }

    @Test
    void aLeftOutPomTheWatcherSeesFixedRestartsItsRoot() throws Exception {
        Path r = root("reactor");
        Path a = pom(r, "a", GOOD);
        Path b = pom(r, "b", "<project>");
        maven.out.put(r, new CopyOnWriteArrayList<>(List.of(a, b)));
        PomServerSupport support = support();

        FxTestSupport.runOnFx(() -> support.filesChanged(List.of(b, r.resolve("src/A.java"))));
        settle();
        assertTrue(shutDown.isEmpty(), "b is still malformed");

        FxTestSupport.runOnFx(() -> support.filesChanged(new ArrayList<>(List.of(a))));
        settle();
        assertEquals(List.of(r), shutDown, "E24");
    }
}
