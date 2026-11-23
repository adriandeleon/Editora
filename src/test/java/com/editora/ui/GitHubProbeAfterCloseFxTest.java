package com.editora.ui;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.editora.github.GitHubService;
import com.editora.testkit.Await;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A {@code gh} probe that answers just as its window closes must not reach the closed window. Its answer used to be
 * posted to the FX thread all the same: the window's GitHub coordinator then enabled the closed window's GitHub tool
 * window and saved its settings, on a config writer that had already shut down — whose late write held the whole
 * window in memory ({@link WindowReleasedOnCloseFxTest#aWindowThatShowedTheProjectMapIsReleased} failed when it did).
 */
@Tag("fx")
class GitHubProbeAfterCloseFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void anAnswerQueuedWhenTheWindowClosesIsDropped() throws Exception {
        GitHubService service = new GitHubService();
        service.setCommand("editora-test-no-such-gh"); // not launchable: the probe answers at once
        AtomicInteger answers = new AtomicInteger();
        ThreadPoolExecutor probes = FxTestSupport.field(service, "probes");
        // The FX thread is held, so the probe's answer is queued behind the hold: the window closes with the
        // answer on its way, as it does when gh answers during the close.
        CountDownLatch release = new CountDownLatch(1);
        javafx.application.Platform.runLater(() -> {
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            service.detect(a -> answers.incrementAndGet());
            Await.until(() -> probes.getCompletedTaskCount() == 1, "the probe finished");
            service.shutdown();
        } finally {
            release.countDown();
        }
        FxTestSupport.drainFx();
        assertEquals(0, answers.get(), "the closed window was told the probe's answer");
    }

    @Test
    void aDetectAfterShutdownKeepsNoCallback() throws Exception {
        GitHubService service = new GitHubService();
        service.shutdown();
        service.detect(a -> {});
        assertNull(FxTestSupport.field(service, "probeWaiters"), "a closed window's callback is still held");
        ThreadPoolExecutor probes = FxTestSupport.field(service, "probes");
        assertTrue(probes.isShutdown());
        assertTrue(Await.holdsWithin(probes::isTerminated, Duration.ofSeconds(5)));
    }
}
