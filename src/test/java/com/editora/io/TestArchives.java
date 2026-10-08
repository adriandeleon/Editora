package com.editora.io;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Small in-memory archives for install tests: zips (which is also what a {@code .vsix} is) and tarballs,
 * including the entries an honest archive never has — {@code ..} segments, absolute names, and links that
 * point out of the tree. Nothing here touches the disk.
 */
public final class TestArchives {

    private TestArchives() {}

    /** A zip of {@code name, content, name, content, …}; a name ending in {@code /} is a directory entry. */
    public static byte[] zip(String... nameThenContent) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (int i = 0; i < nameThenContent.length; i += 2) {
                zip.putNextEntry(new ZipEntry(nameThenContent[i]));
                if (!nameThenContent[i].endsWith("/")) {
                    zip.write(nameThenContent[i + 1].getBytes(StandardCharsets.UTF_8));
                }
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    /** Starts a tar archive. */
    public static Tar tar() {
        return new Tar();
    }

    /** {@code data}, gzip-compressed. */
    public static byte[] gzip(byte[] data) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bytes)) {
            gz.write(data);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    /** A ustar writer: just enough of the format for regular files, directories and links. */
    public static final class Tar {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        private Tar() {}

        /** A regular file, mode 0644. */
        public Tar file(String name, String content) {
            return entry(name, '0', "", content.getBytes(StandardCharsets.UTF_8), 0644);
        }

        /** A regular file, mode 0755. */
        public Tar executable(String name, String content) {
            return entry(name, '0', "", content.getBytes(StandardCharsets.UTF_8), 0755);
        }

        /** A directory. */
        public Tar dir(String name) {
            return entry(name.endsWith("/") ? name : name + "/", '5', "", new byte[0], 0755);
        }

        /** A symbolic link {@code name} pointing at {@code target}. */
        public Tar symlink(String name, String target) {
            return entry(name, '2', target, new byte[0], 0777);
        }

        /** A hard link {@code name} to the existing file {@code target}. */
        public Tar hardlink(String name, String target) {
            return entry(name, '1', target, new byte[0], 0644);
        }

        /** The finished archive, uncompressed. */
        public byte[] bytes() {
            ByteArrayOutputStream done = new ByteArrayOutputStream();
            done.writeBytes(out.toByteArray());
            done.writeBytes(new byte[1024]); // the two empty blocks that end an archive
            return done.toByteArray();
        }

        /** The finished archive as a {@code .tar.gz}. */
        public byte[] gz() {
            return gzip(bytes());
        }

        private Tar entry(String name, char type, String link, byte[] data, int mode) {
            byte[] header = new byte[512];
            byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
            if (nameBytes.length <= 100) {
                put(header, 0, 100, name);
            } else {
                // A long name is split at a slash into the ustar prefix (155) + name (100) fields.
                int cut = -1;
                for (int i = name.indexOf('/', 1); i >= 0; i = name.indexOf('/', i + 1)) {
                    if (i <= 155 && name.length() - i - 1 <= 100) {
                        cut = i;
                        break;
                    }
                }
                if (cut < 0 || nameBytes.length != name.length()) {
                    throw new IllegalArgumentException("name does not fit a ustar header: " + name);
                }
                put(header, 345, 155, name.substring(0, cut));
                put(header, 0, 100, name.substring(cut + 1));
            }
            octal(header, 100, 8, mode);
            octal(header, 108, 8, 0);
            octal(header, 116, 8, 0);
            octal(header, 124, 12, data.length);
            octal(header, 136, 12, 0);
            header[156] = (byte) type;
            put(header, 157, 100, link);
            put(header, 257, 6, "ustar");
            header[263] = '0';
            header[264] = '0';
            java.util.Arrays.fill(header, 148, 156, (byte) ' ');
            int sum = 0;
            for (byte b : header) {
                sum += b & 0xFF;
            }
            byte[] checksum = String.format("%06o", sum).getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(checksum, 0, header, 148, 6);
            header[154] = 0;
            header[155] = ' ';
            out.writeBytes(header);
            out.writeBytes(data);
            out.writeBytes(new byte[(512 - data.length % 512) % 512]);
            return this;
        }

        private static void put(byte[] header, int offset, int length, String value) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > length) {
                throw new IllegalArgumentException("too long for a ustar field: " + value);
            }
            System.arraycopy(bytes, 0, header, offset, bytes.length);
        }

        private static void octal(byte[] header, int offset, int length, long value) {
            byte[] digits = String.format("%0" + (length - 1) + "o", value).getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(digits, 0, header, offset, digits.length);
        }
    }
}
