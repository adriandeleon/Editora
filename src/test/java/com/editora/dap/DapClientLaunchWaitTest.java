package com.editora.dap;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.eclipse.lsp4j.debug.services.IDebugProtocolServer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ElixirLS answers {@code launch} only once it has compiled the project and interpreted its modules: a fresh Phoenix
 * app's first F5 failed with "The debugger did not answer within 30 s." while {@code mix} was still compiling its
 * dependencies. The launch is waited for as long as its adapter needs, and a launch that does time out names the wait
 * that ran out.
 */
class DapClientLaunchWaitTest {

    /** An adapter that answers {@code launch} after {@code millis}. */
    private static final class SlowLaunch implements IDebugProtocolServer {
        final long millis;

        SlowLaunch(long millis) {
            this.millis = millis;
        }

        @Override
        public CompletableFuture<Void> launch(Map<String, Object> args) {
            return CompletableFuture.runAsync(
                    () -> {}, CompletableFuture.delayedExecutor(millis, TimeUnit.MILLISECONDS));
        }
    }

    private static final DapClient.Host SILENT_HOST = new DapClient.Host() {
        @Override
        public void onStopped(int threadId, String reason) {}

        @Override
        public void onContinued() {}

        @Override
        public void onOutput(String text, String category) {}

        @Override
        public void onTerminated() {}

        @Override
        public void onError(String message) {}
    };

    private static DapClient connected(IDebugProtocolServer server) throws Exception {
        DapClient c = new DapClient(SILENT_HOST);
        var f = DapClient.class.getDeclaredField("server");
        f.setAccessible(true);
        f.set(c, server);
        return c;
    }

    @Test
    void elixirLsIsGivenMinutesToAnswerItsLaunchTheOthersTheUsualWait() {
        assertTrue(
                DapManager.launchWait(DebugLanguages.ELIXIR).compareTo(Duration.ofMinutes(5)) >= 0,
                "a fresh Phoenix app's first mix compile, before ElixirLS answers launch");
        assertEquals(
                Duration.ofSeconds(DapClient.requestTimeoutSeconds()), DapManager.launchWait(DebugLanguages.FLUTTER));
    }

    @Test
    void aLaunchIsWaitedForAsLongAsItsWait() throws Exception {
        DapClient c = connected(new SlowLaunch(1_500));
        c.setLaunchWait(Duration.ofSeconds(5));
        c.launch(Map.of()).get(10, TimeUnit.SECONDS); // answered at 1.5 s: no timeout
    }

    @Test
    void aLaunchThatTimesOutNamesTheWaitThatRanOut() throws Exception {
        DapClient c = connected(new SlowLaunch(10_000));
        c.setLaunchWait(Duration.ofSeconds(1));
        ExecutionException e =
                assertThrows(ExecutionException.class, () -> c.launch(Map.of()).get(10, TimeUnit.SECONDS));
        DapClient.NoAnswer none = assertInstanceOf(DapClient.NoAnswer.class, e.getCause());
        assertEquals(1, none.seconds);
        assertEquals(
                com.editora.i18n.Messages.tr("status.debug.noAnswer", 600L),
                DapManager.msg(new CompletionException(new DapClient.NoAnswer(600))));
    }
}
