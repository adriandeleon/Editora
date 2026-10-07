package com.editora.logviewer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * Reads the tail of a (possibly huge, possibly still-growing) log file by byte offset, decoding UTF-8
 * and never re-reading the whole file. Two operations back the log viewer:
 *
 * <ul>
 *   <li>{@link #readTail(Path, long)} — open a big log at its <em>end</em> (the last {@code maxBytes}),
 *       dropping the partial first line, so a multi-GB log opens instantly read-only.</li>
 *   <li>{@link #readAppended(Path, long)} — the {@code tail -f} step: read only the bytes written since
 *       the last offset, holding back a trailing incomplete UTF-8 sequence so a half-written multibyte
 *       char is never decoded as a replacement glyph; detect truncation/rotation (file shrank).</li>
 * </ul>
 *
 * The UTF-8 boundary maths ({@link #completeEnd}, {@link #firstLineStart}) are pure and unit-tested.
 */
public final class LogTail {

    private LogTail() {}

    /** A tail snapshot: decoded {@code text} and the byte {@code offset} at its end (where a follow resumes). */
    public record Tail(String text, long offset) {}

    /**
     * An incremental read: {@code text} appended since the previous offset, the new {@code offset}, and
     * {@code reset} = true when the file was replaced or shrank (log rotation/truncation), in which case
     * {@code text} is the new file from its start. {@code size} and {@code modifiedMillis} are the file's at
     * the moment of the read, and {@code fileKey} identifies the file itself (null where the platform has
     * no such identity) so the next read can tell a replaced file from a grown one.
     */
    public record Append(String text, long offset, boolean reset, long size, long modifiedMillis, Object fileKey) {
        public Append(String text, long offset, boolean reset) {
            this(text, offset, reset, offset, 0, null);
        }
    }

    /**
     * Reads up to the last {@code maxBytes} of {@code file}. When the file is larger, the partial first
     * line of the slice is dropped (it would start mid-record). The returned offset is the file size, so
     * a subsequent {@link #readAppended} continues from the true end.
     */
    public static Tail readTail(Path file, long maxBytes) throws IOException {
        try (SeekableByteChannel ch = Files.newByteChannel(file)) {
            long size = ch.size();
            long start = Math.max(0, size - Math.max(0, maxBytes));
            int toRead = (int) Math.min(size - start, Integer.MAX_VALUE);
            byte[] bytes = readAt(ch, start, toRead);
            int from = start > 0 ? firstLineStart(bytes) : 0;
            int completeEnd = completeEnd(bytes);
            int end = Math.max(from, completeEnd);
            String text = new String(bytes, from, end - from, StandardCharsets.UTF_8);
            // Offset stays at the decoded end; any held-back trailing partial bytes are re-read by follow.
            return new Tail(text, start + end);
        }
    }

    /**
     * The most a follow step reads: the viewer's follow cap ({@code LogView.FOLLOW_CAP}). It keeps no more
     * than that much text, so anything read beyond it would be decoded, posted to the FX thread, appended
     * and then deleted again.
     */
    public static final long FOLLOW_BYTES = 12L * 1024 * 1024;

    /** {@link #readAppended(Path, long, long)} bounded by {@link #FOLLOW_BYTES}. */
    public static Append readAppended(Path file, long fromOffset) throws IOException {
        return readAppended(file, fromOffset, FOLLOW_BYTES);
    }

    /** {@link #readAppended(Path, long, long, Charset, Object)} for a UTF-8 file of unknown identity. */
    public static Append readAppended(Path file, long fromOffset, long maxBytes) throws IOException {
        return readAppended(file, fromOffset, maxBytes, StandardCharsets.UTF_8, null);
    }

    /**
     * Reads the bytes of {@code file} written after {@code fromOffset}, decoding as much of them as forms
     * complete characters in {@code charset} and leaving a trailing incomplete sequence for the next call.
     *
     * <p>The file was rotated when it is now smaller than {@code fromOffset}, or when it is a different file
     * than the one {@code knownKey} names (logrotate's default: rename the old file, create a new one — which
     * may already be larger than the old offset by the time it is looked at). Then {@code reset=true} and the
     * read starts over from the top of the new file.
     *
     * <p>Never more than the last {@code maxBytes} of what there is to read: a writer that produced gigabytes
     * between two polls, or a rotation to a file that is already huge, used to be read whole — up to 2 GB
     * into one array, decoded into a String of the same size and handed to the FX thread. When the read is
     * cut, it starts at the first line boundary inside the kept window.
     *
     * @param knownKey the {@link Append#fileKey()} of the previous read, or null when there is none
     */
    public static Append readAppended(Path file, long fromOffset, long maxBytes, Charset charset, Object knownKey)
            throws IOException {
        long cap = Math.max(1, Math.min(maxBytes, Integer.MAX_VALUE - 8));
        BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
        Object key = attrs.fileKey();
        long mtime = attrs.lastModifiedTime().toMillis();
        try (SeekableByteChannel ch = Files.newByteChannel(file)) {
            long size = ch.size();
            boolean replaced = knownKey != null && key != null && !knownKey.equals(key);
            boolean reset = replaced || size < fromOffset;
            if (size == fromOffset && !reset) {
                return new Append("", fromOffset, false, size, mtime, key);
            }
            long wanted = reset ? 0 : fromOffset;
            long start = Math.max(wanted, size - cap);
            boolean cut = start > wanted;
            int unit = codeUnitBytes(charset);
            if (cut) {
                start += (start - wanted) % unit == 0 ? 0 : unit - (start - wanted) % unit; // on a code unit
            }
            byte[] bytes = readAt(ch, start, (int) Math.max(0, size - start));
            // The decoder stops before a trailing sequence that is not complete yet, whatever the charset.
            CharsetDecoder decoder = charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE);
            ByteBuffer in = ByteBuffer.wrap(bytes);
            CharBuffer out =
                    CharBuffer.allocate((int) Math.ceil(bytes.length * (double) decoder.maxCharsPerByte()) + 1);
            decoder.decode(in, out, false);
            out.flip();
            String text = out.toString();
            if (cut) {
                int nl = text.indexOf('\n'); // do not begin mid-line (or mid-character)
                text = nl < 0 ? text : text.substring(nl + 1);
            } else if (start == 0 && !text.isEmpty() && text.charAt(0) == '\uFEFF') {
                text = text.substring(1); // the byte-order mark is not part of the log
            }
            return new Append(text, start + in.position(), reset, size, mtime, key);
        }
    }

    /** Bytes per code unit: where a read that starts mid-file must be aligned to decode at all. */
    private static int codeUnitBytes(Charset charset) {
        String name = charset.name();
        return name.startsWith("UTF-16") ? 2 : name.startsWith("UTF-32") ? 4 : 1;
    }

    private static byte[] readAt(SeekableByteChannel ch, long position, int length) throws IOException {
        ch.position(position);
        ByteBuffer buf = ByteBuffer.allocate(length);
        while (buf.hasRemaining() && ch.read(buf) != -1) {
            // keep reading
        }
        byte[] out = new byte[buf.position()];
        buf.flip();
        buf.get(out);
        return out;
    }

    /** Index just past the first {@code '\n'} in {@code bytes} (0 if none) — used to drop a partial first line. */
    public static int firstLineStart(byte[] bytes) {
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == '\n') {
                return i + 1;
            }
        }
        return 0;
    }

    /**
     * The length of the longest prefix of {@code bytes} that ends on a complete UTF-8 character — i.e.
     * trims a trailing incomplete multibyte sequence (a lead byte whose continuation bytes have not all
     * arrived yet). Returns {@code bytes.length} when the buffer already ends on a char boundary.
     */
    public static int completeEnd(byte[] bytes) {
        int n = bytes.length;
        if (n == 0) {
            return 0;
        }
        // Walk back over continuation bytes (10xxxxxx) to the last lead/ASCII byte, at most 3 of them.
        int i = n - 1;
        int continuations = 0;
        while (i >= 0 && (bytes[i] & 0xC0) == 0x80) {
            continuations++;
            i--;
            if (continuations > 3) {
                return n; // malformed run; don't trim arbitrarily
            }
        }
        if (i < 0) {
            return n; // all continuation bytes (malformed) — leave as-is
        }
        int lead = bytes[i] & 0xFF;
        int expected;
        if (lead < 0x80) {
            expected = 1; // ASCII
        } else if ((lead & 0xE0) == 0xC0) {
            expected = 2;
        } else if ((lead & 0xF0) == 0xE0) {
            expected = 3;
        } else if ((lead & 0xF8) == 0xF0) {
            expected = 4;
        } else {
            return n; // invalid lead byte — leave as-is, let the decoder substitute
        }
        int have = continuations + 1;
        // Sequence complete → keep everything; incomplete → trim back to the lead byte.
        return have >= expected ? n : i;
    }

    /** Convenience: the current size of {@code file} in bytes (the follow poll compares this to the offset). */
    public static long sizeOf(Path file) throws IOException {
        try (FileChannel ch = FileChannel.open(file)) {
            return ch.size();
        }
    }
}
