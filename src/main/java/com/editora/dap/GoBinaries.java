package com.editora.dap;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;

/**
 * Whether a program file was built by Go — what Attach to Process lists a Go program by, since its command line says
 * nothing (it is named after its package). The Go linker gives every binary a section of its own: {@code
 * .go.buildinfo} (ELF, Linux) or {@code __go_buildinfo} (Mach-O, macOS), and {@code .gopclntab} / {@code __gopclntab}
 * before Go 1.18. Only the headers and section names are read — a few kilobytes, not the program. Windows programs
 * are not read (their Go sections have no names of their own).
 */
public final class GoBinaries {

    private GoBinaries() {}

    private static final Set<String> GO_SECTIONS =
            Set.of(".go.buildinfo", ".gopclntab", "__go_buildinfo", "__gopclntab");

    /** More section headers or load commands than this is no program worth reading. */
    private static final int MAX_ENTRIES = 4096;

    /** A section-name table larger than this is not read. */
    private static final int MAX_NAMES = 1 << 20;

    /** Whether {@code exe} is a Go program; false when it is not, or cannot be read. */
    public static boolean isGo(Path exe) {
        try (FileChannel ch = FileChannel.open(exe, StandardOpenOption.READ)) {
            ByteBuffer head = read(ch, 0, 64);
            if (head.remaining() < 32) {
                return false;
            }
            if (head.get(0) == 0x7f && head.get(1) == 'E' && head.get(2) == 'L' && head.get(3) == 'F') {
                return elf(ch, head);
            }
            int magic = head.order(ByteOrder.LITTLE_ENDIAN).getInt(0);
            if (magic == 0xfeedfacf) {
                return machO(ch, head);
            }
            return false;
        } catch (IOException | RuntimeException e) {
            return false; // unreadable, or not shaped as its header says
        }
    }

    private static boolean elf(FileChannel ch, ByteBuffer head) throws IOException {
        boolean is64 = head.get(4) == 2;
        head.order(head.get(5) == 2 ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN);
        long shoff = is64 ? head.getLong(0x28) : Integer.toUnsignedLong(head.getInt(0x20));
        int shentsize = Short.toUnsignedInt(head.getShort(is64 ? 0x3A : 0x2E));
        int shnum = Short.toUnsignedInt(head.getShort(is64 ? 0x3C : 0x30));
        int shstrndx = Short.toUnsignedInt(head.getShort(is64 ? 0x3E : 0x32));
        if (shoff <= 0 || shnum == 0 || shnum > MAX_ENTRIES || shstrndx >= shnum || shentsize < (is64 ? 0x28 : 0x18)) {
            return false;
        }
        ByteBuffer headers = read(ch, shoff, shnum * shentsize).order(head.order());
        if (headers.remaining() < shnum * shentsize) {
            return false;
        }
        int str = shstrndx * shentsize;
        long namesAt = is64 ? headers.getLong(str + 0x18) : Integer.toUnsignedLong(headers.getInt(str + 0x10));
        long namesSize = is64 ? headers.getLong(str + 0x20) : Integer.toUnsignedLong(headers.getInt(str + 0x14));
        if (namesAt <= 0 || namesSize <= 0 || namesSize > MAX_NAMES) {
            return false;
        }
        ByteBuffer names = read(ch, namesAt, (int) namesSize);
        for (int i = 0; i < shnum; i++) {
            int name = headers.getInt(i * shentsize);
            if (name >= 0 && GO_SECTIONS.contains(cString(names, name))) {
                return true;
            }
        }
        return false;
    }

    private static boolean machO(FileChannel ch, ByteBuffer head) throws IOException {
        int ncmds = head.getInt(16);
        int sizeofcmds = head.getInt(20);
        if (ncmds <= 0 || ncmds > MAX_ENTRIES || sizeofcmds <= 0 || sizeofcmds > MAX_NAMES) {
            return false;
        }
        ByteBuffer cmds = read(ch, 32, sizeofcmds).order(ByteOrder.LITTLE_ENDIAN);
        int at = 0;
        for (int i = 0; i < ncmds && at + 8 <= cmds.limit(); i++) {
            int cmd = cmds.getInt(at);
            int size = cmds.getInt(at + 4);
            if (size < 8) {
                return false;
            }
            if (cmd == 0x19) { // LC_SEGMENT_64: its sections follow its 72 bytes, 80 bytes each
                int nsects = cmds.getInt(at + 64);
                for (int s = 0; s < nsects && at + 72 + (s + 1) * 80 <= cmds.limit(); s++) {
                    int sect = at + 72 + s * 80;
                    if (GO_SECTIONS.contains(fixedString(cmds, sect, 16))) {
                        return true;
                    }
                }
            }
            at += size;
        }
        return false;
    }

    private static ByteBuffer read(FileChannel ch, long at, int size) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(Math.max(0, size));
        while (b.hasRemaining()) {
            int n = ch.read(b, at + b.position());
            if (n < 0) {
                break;
            }
        }
        b.flip();
        return b;
    }

    /** The NUL-ended string at {@code at} of {@code b}. */
    static String cString(ByteBuffer b, int at) {
        int end = at;
        while (end < b.limit() && b.get(end) != 0) {
            end++;
        }
        return at >= b.limit() ? "" : new String(bytes(b, at, end - at), StandardCharsets.US_ASCII);
    }

    /** The string in the {@code size} bytes at {@code at} of {@code b}, up to its first NUL. */
    static String fixedString(ByteBuffer b, int at, int size) {
        int len = 0;
        while (len < size && b.get(at + len) != 0) {
            len++;
        }
        return new String(bytes(b, at, len), StandardCharsets.US_ASCII);
    }

    private static byte[] bytes(ByteBuffer b, int at, int len) {
        byte[] out = new byte[len];
        for (int i = 0; i < len; i++) {
            out[i] = b.get(at + i);
        }
        return out;
    }
}
