package com.editora.io;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

/**
 * A gzip-compressed text file, read decompressed: {@code app.log.2.gz}, {@code access.log.gz}. Logrotate
 * compresses old logs, so a log directory is half {@code .gz}. Pure (java.base only).
 *
 * <p>Only plain gzip: a {@code .tar.gz} or {@code .tgz} is an archive of several files, not one text.
 */
public final class GzipText {

    private GzipText() {}

    /** Whether {@code fileName} is one gzip-compressed file ({@code x.gz}, not {@code x.tar.gz}/{@code x.tgz}). */
    public static boolean isGzipName(String fileName) {
        if (fileName == null) {
            return false;
        }
        String name = fileName.toLowerCase(Locale.ROOT);
        return name.endsWith(".gz") && name.length() > 3 && !name.endsWith(".tar.gz");
    }

    /** Whether {@code path} names one gzip-compressed file (see {@link #isGzipName}). */
    public static boolean isGzip(Path path) {
        Path name = path == null ? null : path.getFileName();
        return name != null && isGzipName(name.toString());
    }

    /** {@code app.log.gz} → {@code app.log}; any other name as it is. */
    public static String innerName(String fileName) {
        return isGzipName(fileName) ? fileName.substring(0, fileName.length() - 3) : fileName;
    }

    /**
     * The decompressed bytes, at most {@code cap} of them: the first {@code cap}, or, with {@code keepEnd}, the
     * last {@code cap} from the start of a line (a log's newest lines are the ones that matter).
     *
     * @param bytes what was kept
     * @param total how many bytes the whole file decompresses to
     * @param cut whether {@code bytes} is only part of it
     */
    public record Read(byte[] bytes, long total, boolean cut) {}

    /** Decompresses {@code file} (see {@link Read}). Blocking: call it off the FX thread. */
    public static Read read(Path file, int cap, boolean keepEnd) throws IOException {
        try (InputStream in = new GZIPInputStream(Files.newInputStream(file), 64 * 1024)) {
            return read(in, cap, keepEnd);
        }
    }

    static Read read(InputStream in, int cap, boolean keepEnd) throws IOException {
        if (!keepEnd) {
            byte[] head = in.readNBytes(cap);
            long total = head.length + in.transferTo(java.io.OutputStream.nullOutputStream()); // counted, not kept
            return new Read(head, total, total > head.length);
        }
        // The last cap bytes, through a ring of that size.
        byte[] ring = new byte[Math.max(1, cap)];
        long total = 0;
        byte[] chunk = new byte[64 * 1024];
        for (int n = in.read(chunk); n >= 0; n = in.read(chunk)) {
            for (int off = 0; off < n; ) {
                int at = (int) (total % ring.length);
                int len = Math.min(n - off, ring.length - at);
                System.arraycopy(chunk, off, ring, at, len);
                off += len;
                total += len;
            }
        }
        if (total <= ring.length) {
            return new Read(Arrays.copyOf(ring, (int) total), total, false);
        }
        int start = (int) (total % ring.length);
        byte[] tail = new byte[ring.length];
        System.arraycopy(ring, start, tail, 0, ring.length - start);
        System.arraycopy(ring, 0, tail, ring.length - start, start);
        int line = 0;
        while (line < tail.length && tail[line] != '\n') {
            line++;
        }
        byte[] fromLine = line < tail.length ? Arrays.copyOfRange(tail, line + 1, tail.length) : tail;
        return new Read(fromLine, total, true);
    }
}
