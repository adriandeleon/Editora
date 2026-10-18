package com.editora.deploy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Writes a POSIX tar stream ({@code ustar}, with a {@code pax} header for a long or non-ASCII name or a huge
 * file) of regular files — what a deploy sends to the host's {@code tar -x}, GNU, BSD or BusyBox. Only what a
 * deploy needs: regular files with a mode and a modified time; owners are left for the host to choose.
 */
public final class TarWriter implements AutoCloseable {

    private static final int BLOCK = 512;
    private static final long MAX_OCTAL_SIZE = 077777777777L; // 11 octal digits

    private final OutputStream out;
    private int paxCount;
    private boolean finished;

    public TarWriter(OutputStream out) {
        this.out = out;
    }

    /**
     * Adds the file {@code name} ({@code /}-separated, relative): {@code size} bytes read from {@code content},
     * zero-padded if it ends early (a file shrinking while it is sent; the next deploy sends it again).
     *
     * @return the SHA-256 of the bytes actually sent, hex
     */
    public String addFile(String name, long size, long modifiedSeconds, boolean executable, InputStream content)
            throws IOException {
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        boolean longName = nameBytes.length > 100 || !isAscii(name);
        boolean hugeFile = size > MAX_OCTAL_SIZE;
        if (longName || hugeFile) {
            StringBuilder records = new StringBuilder();
            if (longName) {
                records.append(paxRecord("path", name));
            }
            if (hugeFile) {
                records.append(paxRecord("size", Long.toString(size)));
            }
            byte[] body = records.toString().getBytes(StandardCharsets.UTF_8);
            writeHeader(asciiName("PaxHeaders/" + (++paxCount)), body.length, modifiedSeconds, 0644, 'x');
            out.write(body);
            pad(body.length);
        }
        writeHeader(
                longName ? asciiName("editora-long-name-" + paxCount) : nameBytes,
                hugeFile ? 0 : size,
                modifiedSeconds,
                executable ? 0755 : 0644,
                '0');
        MessageDigest md = sha256();
        long sent = 0;
        byte[] buffer = new byte[64 * 1024];
        try (InputStream in = new DigestInputStream(content, md)) {
            while (sent < size) {
                int n = in.read(buffer, 0, (int) Math.min(buffer.length, size - sent));
                if (n < 0) {
                    break;
                }
                out.write(buffer, 0, n);
                sent += n;
            }
        }
        if (sent < size) {
            byte[] zeros = new byte[(int) Math.min(buffer.length, size - sent)];
            while (sent < size) {
                int n = (int) Math.min(zeros.length, size - sent);
                out.write(zeros, 0, n);
                md.update(zeros, 0, n);
                sent += n;
            }
        }
        pad(size);
        return HexFormat.of().formatHex(md.digest());
    }

    /** Ends the archive: two empty blocks. Does not close the underlying stream. */
    public void finish() throws IOException {
        if (!finished) {
            finished = true;
            out.write(new byte[BLOCK * 2]);
            out.flush();
        }
    }

    /** {@link #finish}es the archive (once); the underlying stream stays open. */
    @Override
    public void close() throws IOException {
        finish();
    }

    private void writeHeader(byte[] name, long size, long mtime, int mode, char type) throws IOException {
        byte[] h = new byte[BLOCK];
        System.arraycopy(name, 0, h, 0, Math.min(name.length, 100));
        octal(h, 100, 8, mode);
        octal(h, 108, 8, 0);
        octal(h, 116, 8, 0);
        octal(h, 124, 12, size);
        octal(h, 136, 12, Math.max(0, mtime));
        for (int i = 148; i < 156; i++) {
            h[i] = ' ';
        }
        h[156] = (byte) type;
        System.arraycopy("ustar\0".getBytes(StandardCharsets.US_ASCII), 0, h, 257, 6);
        h[263] = '0';
        h[264] = '0';
        long sum = 0;
        for (byte b : h) {
            sum += b & 0xff;
        }
        String chk = String.format("%06o", sum);
        System.arraycopy(chk.getBytes(StandardCharsets.US_ASCII), 0, h, 148, 6);
        h[154] = 0;
        h[155] = ' ';
        out.write(h);
    }

    /** {@code value} as {@code width - 1} zero-padded octal digits and a NUL, at {@code offset}. */
    private static void octal(byte[] h, int offset, int width, long value) {
        String digits = Long.toOctalString(value);
        int len = width - 1;
        if (digits.length() > len) {
            throw new IllegalArgumentException("too large for a tar header: " + value);
        }
        String padded = "0".repeat(len - digits.length()) + digits;
        System.arraycopy(padded.getBytes(StandardCharsets.US_ASCII), 0, h, offset, len);
        h[offset + len] = 0;
    }

    private void pad(long written) throws IOException {
        int rest = (int) (written % BLOCK);
        if (rest != 0) {
            out.write(new byte[BLOCK - rest]);
        }
    }

    /** A pax record: {@code "<length> <key>=<value>\n"}, where the length counts itself. */
    static String paxRecord(String key, String value) {
        int body = (" " + key + "=" + value + "\n").getBytes(StandardCharsets.UTF_8).length;
        int length = body + Integer.toString(body).length();
        if (Integer.toString(length).length() != Integer.toString(body).length()) {
            length++; // adding the length's own digits carried it over to one more digit
        }
        return length + " " + key + "=" + value + "\n";
    }

    private static byte[] asciiName(String name) {
        return name.getBytes(StandardCharsets.US_ASCII);
    }

    private static boolean isAscii(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) >= 0x80) {
                return false;
            }
        }
        return true;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
