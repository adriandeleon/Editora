package com.editora.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/**
 * What one recorded save costs the config writer with a large Local History index (perf finding F6). Opt-in:
 * {@code -Dhistory.probe=true}. Prints the median and the 90th percentile of the time from {@code saveHistory}
 * to the write being durable, and the bytes the history files took per save.
 */
@Tag("probe")
@EnabledIfSystemProperty(named = "history.probe", matches = "true")
class HistoryIndexWriteProbeTest {

    @TempDir
    Path dir;

    @Test
    void perSaveCostWithASevenMegabyteIndex() throws Exception {
        SharedConfig config = new SharedConfig(dir, false);
        config.load();
        // About 7 MB of index: 2,000 files of one project, 14 revisions each.
        Map<String, List<HistoryRevision>> bucket = config.historyBucket("big-project");
        for (int f = 0; f < 2_000; f++) {
            String path =
                    "/home/user/src/big-project/src/main/java/com/example/module" + (f % 40) + "/File" + f + ".java";
            List<HistoryRevision> rows = new ArrayList<>();
            for (int r = 0; r < 14; r++) {
                rows.add(new HistoryRevision(
                        path,
                        1_700_000_000_000L + f * 1_000L - r,
                        4_096 + r,
                        String.format("%064x", (long) f * 100 + r),
                        HistoryRevision.REASON_SAVE));
            }
            bucket.put(path, rows);
        }
        config.saveHistory();
        config.flushWrites();
        config.shutdown();

        // A fresh start on that index, as a user with a long history has it.
        SharedConfig again = new SharedConfig(dir, false);
        again.load();
        Path index = dir.resolve("history/index.json");
        System.out.printf("index.json: %,d bytes%n", Files.size(index));
        int saves = 60;
        long[] nanos = new long[saves];
        long[] bytes = new long[saves];
        Map<String, List<HistoryRevision>> live = again.historyBucket("big-project");
        for (int i = 0; i < saves; i++) {
            String path = "/home/user/src/big-project/src/main/java/com/example/module" + (i % 40) + "/File" + (i * 7)
                    + ".java";
            HistoryRevision row = new HistoryRevision(
                    path, 1_800_000_000_000L + i, 5_000, String.format("%064x", 9_000_000L + i), "SAVE");
            List<HistoryRevision> rows = new ArrayList<>();
            rows.add(row);
            rows.addAll(live.getOrDefault(path, List.of()));
            live.put(path, rows);
            long before = writtenBytes();
            long start = System.nanoTime();
            again.saveHistory();
            again.flushWrites();
            nanos[i] = System.nanoTime() - start;
            bytes[i] = writtenBytes() - before;
        }
        // And the whole write that folds the delta in, as before a blob collection (about every ten minutes).
        again.historyService().requestGc();
        long compactStart = System.nanoTime();
        long compactBefore = writtenBytes();
        again.saveHistory();
        again.flushWrites();
        System.out.printf(
                "compaction: %.1f ms, %,d bytes written%n",
                (System.nanoTime() - compactStart) / 1e6, writtenBytes() - compactBefore);
        again.shutdown();
        long[] warm = Arrays.copyOfRange(nanos, 5, saves);
        Arrays.sort(warm);
        long[] wrote = Arrays.copyOfRange(bytes, 5, saves);
        Arrays.sort(wrote);
        System.out.printf(
                "per save (n=%d): median %.1f ms, p90 %.1f ms; bytes written median %,d, max %,d%n",
                warm.length,
                warm[warm.length / 2] / 1e6,
                warm[warm.length * 9 / 10] / 1e6,
                wrote[wrote.length / 2],
                wrote[wrote.length - 1]);
    }

    /** {@code wchar} of this process (bytes handed to write()), from /proc; 0 where there is none. */
    private static long writtenBytes() {
        try {
            for (String line : Files.readAllLines(Path.of("/proc/self/io"))) {
                if (line.startsWith("wchar:")) {
                    return Long.parseLong(line.substring(6).trim());
                }
            }
        } catch (Exception none) {
            // not Linux
        }
        return 0;
    }
}
