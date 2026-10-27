package com.editora.terminal;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;

/**
 * The direct children of a process, asked of the OS by parent rather than found by search.
 *
 * <p>{@link ProcessHandle#children()} answers by walking the whole process table: on Linux it reads
 * every entry under {@code /proc} to learn each one's parent, and on macOS it copies the table out
 * with {@code sysctl}. Measured on a desktop with 610 processes that was 3.4ms a call — and the
 * watcher makes one per terminal every 750ms, on a single thread, so twenty tabs spent about a tenth of
 * a core asking which program each shell was running. Both kernels can answer the question by
 * parent directly, which on Linux measured 4.3µs.
 *
 * <p>The table walk stays as the fallback: {@code /proc/<pid>/task/<tid>/children} exists only on
 * kernels built with {@code CONFIG_PROC_CHILDREN}, which mainstream distributions are but nothing
 * guarantees, and Windows has neither.
 */
final class ChildProcesses {

    private ChildProcesses() {}

    private static final String OS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    private static final boolean LINUX = OS.contains("linux");
    private static final boolean MAC = OS.startsWith("mac");

    /** The children's pids, in no particular order; empty when there are none or the process is gone. */
    static List<Long> of(long pid) {
        if (pid <= 0) return List.of();
        try {
            Optional<List<Long>> fast = LINUX ? linux(pid) : MAC ? mac(pid) : Optional.empty();
            if (fast.isPresent()) return fast.get();
        } catch (Throwable t) {
            // An UnsatisfiedLinkError from libproc, a /proc that is not what it should be: the walk
            // below still answers, just more slowly.
        }
        return ProcessHandle.of(pid)
                .map(handle -> handle.children().map(ProcessHandle::pid).toList())
                .orElse(List.of());
    }

    /**
     * Pids from a {@code children} file: decimal numbers separated by spaces, with a trailing one.
     * Anything that is not a positive number is skipped rather than trusted.
     */
    static List<Long> parse(String text) {
        List<Long> pids = new ArrayList<>();
        if (text == null) return pids;
        for (String token : text.trim().split("\\s+")) {
            if (token.isEmpty()) continue;
            try {
                long pid = Long.parseLong(token);
                if (pid > 0) pids.add(pid);
            } catch (NumberFormatException ignored) {
                // Not a pid; nothing a well-formed file contains.
            }
        }
        return pids;
    }

    // --- Linux ------------------------------------------------------------------------------
    //
    // One file per thread, listing the children that thread forked. A shell is almost always one
    // thread, but a child started from another thread is listed only under that thread, so all of
    // them are read.

    private static Optional<List<Long>> linux(long pid) throws IOException {
        Path tasks = Path.of("/proc", Long.toString(pid), "task");
        // The kernel lacks the file altogether, as against the process having gone: only the
        // first is a reason to fall back.
        if (!Files.exists(tasks.resolve(Long.toString(pid)).resolve("children"))) {
            return Files.isDirectory(tasks) ? Optional.empty() : Optional.of(List.of());
        }
        List<Long> children = new ArrayList<>();
        try (DirectoryStream<Path> threads = Files.newDirectoryStream(tasks)) {
            for (Path thread : threads) {
                try {
                    children.addAll(parse(Files.readString(thread.resolve("children"))));
                } catch (NoSuchFileException e) {
                    // The thread ended between the listing and the read.
                }
            }
        } catch (NoSuchFileException e) {
            return Optional.of(List.of());
        }
        return Optional.of(List.copyOf(children));
    }

    // --- macOS ------------------------------------------------------------------------------
    //
    // proc_listpids(PROC_PPID_ONLY, ppid, …) fills the buffer with the pids whose parent is ppid
    // and returns the number of bytes it wrote. A full buffer may mean it was cut short, in which
    // case the walk answers instead.

    private static final int PROC_PPID_ONLY = 6;
    private static final int MAX_CHILDREN = 256;

    /** Loaded lazily so that JNA never touches libproc off macOS. */
    private interface LibProc extends Library {
        LibProc INSTANCE = Native.load("proc", LibProc.class);

        int proc_listpids(int type, int typeinfo, Pointer buffer, int buffersize);
    }

    private static Optional<List<Long>> mac(long pid) {
        int size = MAX_CHILDREN * Integer.BYTES;
        try (Memory buffer = new Memory(size)) {
            int written = LibProc.INSTANCE.proc_listpids(PROC_PPID_ONLY, (int) pid, buffer, size);
            if (written < 0 || written >= size || written % Integer.BYTES != 0) return Optional.empty();
            List<Long> children = new ArrayList<>();
            for (int offset = 0; offset < written; offset += Integer.BYTES) {
                int child = buffer.getInt(offset);
                if (child > 0 && child != pid) children.add((long) child);
            }
            return Optional.of(List.copyOf(children));
        }
    }
}
