package com.editora.ui;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import com.editora.config.PathKeys;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A Rollback of many files copies them to Local History as one publication: every file is listed, and the index is
 * written once for the batch, not once per file (F3, perf review 2026-11-23).
 */
@Tag("fx")
@org.junit.jupiter.api.extension.ExtendWith(OffFxInPlace.class)
class HistoryBatchCaptureFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void manyOverwrittenFilesAreCapturedWithOneIndexWrite(@TempDir Path temp) throws Exception {
        Path dir = temp.toRealPath();
        List<Path> files = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            files.add(Files.writeString(dir.resolve("f" + i + ".txt"), "changed outside " + i + "\n"));
        }
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            HistoryCoordinator history = FxTestSupport.field(fx.controller, "historyCoordinator");
            AtomicInteger durableWrites = new AtomicInteger();
            countDurableWrites(history, durableWrites);

            CompletableFuture<Optional<Path>> done = new CompletableFuture<>();
            FxTestSupport.runOnFx(() -> history.captureAllBeforeOverwriteDurably(files, done::complete));
            assertEquals(Optional.empty(), async.await(done), "every file is safe to overwrite");

            assertEquals(1, durableWrites.get(), "one durable index write for the whole batch");
            for (Path file : files) {
                int rows = 0;
                for (var bucket : fx.shared.historyByProject().values()) {
                    rows += bucket.getOrDefault(PathKeys.normalizedKey(file), List.of())
                            .size();
                }
                assertEquals(1, rows, "a revision of " + file.getFileName());
            }
            assertTrue(fx.shared.flushWrites());
            String index = Files.readString(fx.shared.getHistoryFile());
            assertTrue(index.contains("f19.txt"), "and the written index holds them");
        }
    }

    /** Wraps the coordinator's config operations so each durable index write ({@code saveHistory(completion)}) counts. */
    private static void countDurableWrites(HistoryCoordinator history, AtomicInteger writes) throws Exception {
        java.lang.reflect.Field field = HistoryCoordinator.class.getDeclaredField("ops");
        field.setAccessible(true);
        Object real = field.get(history);
        InvocationHandler counting = (proxy, method, args) -> {
            if (method.getName().equals("saveHistory") && args != null && args.length == 1) {
                writes.incrementAndGet();
            }
            try {
                method.setAccessible(true);
                return method.invoke(real, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        };
        Object proxy = Proxy.newProxyInstance(
                HistoryCoordinator.class.getClassLoader(), new Class<?>[] {HistoryCoordinator.Ops.class}, counting);
        field.set(history, proxy);
    }
}
