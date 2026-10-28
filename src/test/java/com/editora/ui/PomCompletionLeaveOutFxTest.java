package com.editora.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A degraded Maven coordinate completion (only {@code <!--}, {@code <![CDATA[}) takes the project's malformed poms out
 * of the server's workspace and asks again (review 2, E3); with nothing to leave out the answer is delivered as is.
 */
@Tag("fx")
class PomCompletionLeaveOutFxTest {

    @TempDir
    Path root;

    private final List<String> statuses = new CopyOnWriteArrayList<>();
    private final List<List<Path>> leftOut = new CopyOnWriteArrayList<>();

    private Path pom(String dir, String text) throws IOException {
        return Files.writeString(Files.createDirectories(root.resolve(dir)).resolve("pom.xml"), text);
    }

    /** Runs one degraded completion through {@code support}; returns "again" or "deliver", whichever ran. */
    private String degraded(PomServerSupport support, List<Path> leaveOutAnswer) throws Exception {
        var ran = new String[1];
        var done = new CountDownLatch(1);
        boolean[] taken = new boolean[1];
        FxTestSupport.runOnFx(() -> taken[0] = support.onCompletion(
                "artifactId",
                List.of("<!--", "<![CDATA["),
                root,
                Map::of,
                poms -> {
                    leftOut.add(poms);
                    return leaveOutAnswer;
                },
                () -> {
                    ran[0] = "again";
                    done.countDown();
                },
                () -> {
                    ran[0] = "deliver";
                    done.countDown();
                }));
        assertTrue(taken[0], "a degraded answer is taken over");
        assertTrue(done.await(10, TimeUnit.SECONDS));
        return ran[0];
    }

    @Test
    void theMalformedPomsAreLeftOutAndTheCompletionAskedAgain() throws Exception {
        pom(".", "<project><artifactId>r</artifactId></project>");
        Path a = pom("a", "<project><artifactId>a\n</project>");
        Path b = pom("b", "<project>");
        PomServerSupport support = new PomServerSupport(statuses::add);

        assertEquals("again", degraded(support, List.of(a, b)));

        assertEquals(java.util.Set.of(a, b), java.util.Set.copyOf(leftOut.get(0)));
        String first = PomServerSupport.displayPath(root, leftOut.get(0).get(0));
        assertTrue(statuses.get(0).startsWith(first), statuses.get(0));
        assertTrue(statuses.get(0).contains(" 1 "), "names how many more: " + statuses.get(0));
    }

    @Test
    void nothingNewlyLeftOutDeliversTheAnswerAndSaysWhy() throws Exception {
        pom(".", "<project><artifactId>r</artifactId></project>");
        pom("a", "<project><artifactId>a\n</project>");
        PomServerSupport support = new PomServerSupport(statuses::add);

        assertEquals("deliver", degraded(support, List.of()));

        assertEquals(
                tr(
                        "status.maven.completionMalformed",
                        PomServerSupport.displayPath(root, root.resolve("a/pom.xml")),
                        "2"),
                statuses.get(0));
    }

    @Test
    void aRealAnswerIsNotTakenOver() throws Exception {
        PomServerSupport support = new PomServerSupport(statuses::add);
        boolean[] taken = new boolean[1];
        FxTestSupport.runOnFx(() -> taken[0] = support.onCompletion(
                "artifactId", List.of("commons-lang3"), root, Map::of, poms -> poms, () -> {}, () -> {}));
        assertFalse(taken[0]);
        assertTrue(leftOut.isEmpty());
    }
}
