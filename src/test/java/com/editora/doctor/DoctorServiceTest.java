package com.editora.doctor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DoctorService}'s generation guard, with results posted directly (no FX toolkit): a Refresh must
 * not leave the superseded run's queued probes to spawn subprocesses ahead of the new run's.
 */
class DoctorServiceTest {

    private static final int POOL_SIZE = 4;

    @Test
    void refreshSkipsTheSupersededRunsQueuedProbes() throws Exception {
        DoctorService service = new DoctorService(Runnable::run);
        try {
            // Run 1: enough probes to fill the pool and leave two queued behind the blocked ones.
            CountDownLatch firstStarted = new CountDownLatch(POOL_SIZE);
            CountDownLatch releaseFirst = new CountDownLatch(1);
            AtomicInteger staleProbesRun = new AtomicInteger();
            List<DoctorService.CheckSpec> first = new ArrayList<>();
            for (int i = 0; i < POOL_SIZE; i++) {
                first.add(spec("blocked" + i, base -> {
                    firstStarted.countDown();
                    await(releaseFirst);
                    return base.ok("");
                }));
            }
            for (int i = 0; i < 2; i++) {
                first.add(spec("queued" + i, base -> {
                    staleProbesRun.incrementAndGet();
                    return base.ok("");
                }));
            }
            List<DoctorCheck> firstResults = new ArrayList<>();
            service.run(first, firstResults::add, () -> {});
            assertTrue(firstStarted.await(10, TimeUnit.SECONDS));

            // Refresh: run 2 fills the whole pool once the blocked probes finish. When all of run 2's probes
            // are running, the queue ahead of them (run 1's two stragglers) has fully drained.
            CountDownLatch secondStarted = new CountDownLatch(POOL_SIZE);
            CountDownLatch releaseSecond = new CountDownLatch(1);
            CountDownLatch secondDone = new CountDownLatch(1);
            List<DoctorService.CheckSpec> second = new ArrayList<>();
            for (int i = 0; i < POOL_SIZE; i++) {
                second.add(spec("fresh" + i, base -> {
                    secondStarted.countDown();
                    await(releaseSecond);
                    return base.ok("v2");
                }));
            }
            List<DoctorCheck> secondResults = java.util.Collections.synchronizedList(new ArrayList<>());
            service.run(second, secondResults::add, secondDone::countDown);
            releaseFirst.countDown();
            assertTrue(secondStarted.await(10, TimeUnit.SECONDS));

            assertEquals(0, staleProbesRun.get(), "a superseded run's queued probes must not run");
            assertTrue(firstResults.isEmpty(), "a superseded run's results must be dropped");

            releaseSecond.countDown();
            assertTrue(secondDone.await(10, TimeUnit.SECONDS));
            assertEquals(POOL_SIZE, secondResults.size());
        } finally {
            service.shutdown();
        }
    }

    private static DoctorService.CheckSpec spec(String id, java.util.function.UnaryOperator<DoctorCheck> probe) {
        DoctorCheck placeholder = DoctorCheck.checking(id, "test", id, "");
        return new DoctorService.CheckSpec(placeholder, () -> probe.apply(placeholder));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
