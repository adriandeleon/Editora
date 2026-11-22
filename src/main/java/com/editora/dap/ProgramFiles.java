package com.editora.dap;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * What a program file is, from its section names — what Attach to Process lists a Go or native program by, since its
 * command line says nothing (a Go program is named after its package, a native one after anything). Whether it was
 * built by Go ({@link #isGo}), and whether a native program carries the debug information a debugger needs
 * ({@link #kind}). The Go linker gives every binary a section of its own: {@code
 * .go.buildinfo} (ELF, Linux) or {@code __go_buildinfo} (Mach-O, macOS), and {@code .gopclntab} / {@code __gopclntab}
 * before Go 1.18. Only the headers and section names are read — a few kilobytes, not the program. Windows programs
 * are not read (their Go sections have no names of their own).
 */
public final class ProgramFiles {

    private ProgramFiles() {}

    private static final Set<String> GO_SECTIONS =
            Set.of(".go.buildinfo", ".gopclntab", "__go_buildinfo", "__gopclntab");

    /** More section headers or load commands than this is no program worth reading. */
    private static final int MAX_ENTRIES = 4096;

    /** A section-name table larger than this is not read. */
    private static final int MAX_NAMES = 1 << 20;

    /** What a program file holds that a debugger cares about. */
    public enum Kind {
        /** Built by Go: Delve debugs it. */
        GO,
        /** Native, with DWARF debug information, built by Rust. */
        RUST,
        /** Native, with DWARF debug information (C, C++, …). */
        NATIVE
    }

    /** Whether {@code exe} is a Go program; false when it is not, or cannot be read. */
    public static boolean isGo(Path exe) {
        return kind(exe) == Kind.GO;
    }

    /**
     * What {@code exe} is: a Go program; a native one with DWARF — Rust's when rustc's {@code .debug_gdb_scripts} or
     * {@code .rustc} is there too; null for anything else (no debug information, not a program, unreadable). DWARF is
     * a {@code .debug_info} section (ELF) or {@code __debug_info} (Mach-O); a macOS program usually keeps it beside
     * itself instead, in {@code <program>.dSYM}, which counts too.
     */
    public static @Nullable Kind kind(Path exe) {
        Set<String> sections = sections(exe);
        if (sections.stream().anyMatch(GO_SECTIONS::contains)) {
            return Kind.GO;
        }
        // by its magic number, not its section names: glibc's and LLVM's ELF sections start with "__" too
        // (__libc_freeres_fn, __llvm_prf_cnts), which took such a program for a Mach-O one without its DWARF (review
        // B5)
        boolean machO = isMachO(exe);
        Set<String> dwarf = sections;
        if (machO && !sections.contains("__debug_info")) {
            dwarf = dsymSections(exe);
        }
        if (!dwarf.contains(".debug_info") && !dwarf.contains("__debug_info")) {
            return null;
        }
        return isRust(sections) || isRust(dwarf) ? Kind.RUST : Kind.NATIVE;
    }

    /** Whether {@code exe} starts as a Mach-O program does (32 or 64 bit, either byte order); false when unreadable. */
    static boolean isMachO(Path exe) {
        try (FileChannel ch = FileChannel.open(exe, StandardOpenOption.READ)) {
            ByteBuffer head = read(ch, 0, 4);
            if (head.remaining() < 4) {
                return false;
            }
            int magic = head.order(ByteOrder.LITTLE_ENDIAN).getInt(0);
            return magic == 0xfeedfacf || magic == 0xfeedface || magic == 0xcffaedfe || magic == 0xcefaedfe;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** Whether rustc's own sections are among {@code sections} (a Mach-O name is cut to 16 characters). */
    private static boolean isRust(Set<String> sections) {
        return sections.contains(".debug_gdb_scripts")
                || sections.contains(".rustc")
                || sections.contains("__debug_gdb_scri")
                || sections.contains("__rustc");
    }

    /**
     * The sections of the DWARF file in {@code exe}'s {@code .dSYM} bundle ({@code
     * <program>.dSYM/Contents/Resources/DWARF/<program>}), or none when it has no bundle. A bundle without that file
     * still says the program was built with debug information: it counts as {@code __debug_info}.
     */
    private static Set<String> dsymSections(Path exe) {
        Path name = exe.getFileName();
        Path parent = exe.toAbsolutePath().getParent();
        if (name == null || parent == null) {
            return Set.of();
        }
        Path bundle = parent.resolve(name + ".dSYM");
        if (!java.nio.file.Files.isDirectory(bundle)) {
            return Set.of();
        }
        Path dwarf = bundle.resolve("Contents/Resources/DWARF").resolve(name.toString());
        Set<String> sections = java.nio.file.Files.isRegularFile(dwarf) ? sections(dwarf) : Set.of();
        return sections.contains("__debug_info") ? sections : Set.of("__debug_info");
    }

    /** The names of {@code exe}'s sections (ELF, Mach-O 64), or none when it is neither or cannot be read. */
    public static Set<String> sections(Path exe) {
        try (FileChannel ch = FileChannel.open(exe, StandardOpenOption.READ)) {
            ByteBuffer head = read(ch, 0, 64);
            if (head.remaining() < 32) {
                return Set.of();
            }
            Set<String> out = new java.util.HashSet<>();
            if (head.get(0) == 0x7f && head.get(1) == 'E' && head.get(2) == 'L' && head.get(3) == 'F') {
                elf(ch, head, out);
            } else if (head.order(ByteOrder.LITTLE_ENDIAN).getInt(0) == 0xfeedfacf) {
                machO(ch, head, out);
            }
            return out;
        } catch (IOException | RuntimeException e) {
            return Set.of(); // unreadable, or not shaped as its header says
        }
    }

    private static void elf(FileChannel ch, ByteBuffer head, Set<String> out) throws IOException {
        boolean is64 = head.get(4) == 2;
        head.order(head.get(5) == 2 ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN);
        long shoff = is64 ? head.getLong(0x28) : Integer.toUnsignedLong(head.getInt(0x20));
        int shentsize = Short.toUnsignedInt(head.getShort(is64 ? 0x3A : 0x2E));
        int shnum = Short.toUnsignedInt(head.getShort(is64 ? 0x3C : 0x30));
        int shstrndx = Short.toUnsignedInt(head.getShort(is64 ? 0x3E : 0x32));
        if (shoff <= 0 || shnum == 0 || shnum > MAX_ENTRIES || shstrndx >= shnum || shentsize < (is64 ? 0x28 : 0x18)) {
            return;
        }
        ByteBuffer headers = read(ch, shoff, shnum * shentsize).order(head.order());
        if (headers.remaining() < shnum * shentsize) {
            return;
        }
        int str = shstrndx * shentsize;
        long namesAt = is64 ? headers.getLong(str + 0x18) : Integer.toUnsignedLong(headers.getInt(str + 0x10));
        long namesSize = is64 ? headers.getLong(str + 0x20) : Integer.toUnsignedLong(headers.getInt(str + 0x14));
        if (namesAt <= 0 || namesSize <= 0 || namesSize > MAX_NAMES) {
            return;
        }
        ByteBuffer names = read(ch, namesAt, (int) namesSize);
        for (int i = 0; i < shnum; i++) {
            int name = headers.getInt(i * shentsize);
            if (name >= 0) {
                out.add(cString(names, name));
            }
        }
    }

    private static void machO(FileChannel ch, ByteBuffer head, Set<String> out) throws IOException {
        int ncmds = head.getInt(16);
        int sizeofcmds = head.getInt(20);
        if (ncmds <= 0 || ncmds > MAX_ENTRIES || sizeofcmds <= 0 || sizeofcmds > MAX_NAMES) {
            return;
        }
        ByteBuffer cmds = read(ch, 32, sizeofcmds).order(ByteOrder.LITTLE_ENDIAN);
        int at = 0;
        for (int i = 0; i < ncmds && at + 8 <= cmds.limit(); i++) {
            int cmd = cmds.getInt(at);
            int size = cmds.getInt(at + 4);
            if (size < 8) {
                return;
            }
            if (cmd == 0x19) { // LC_SEGMENT_64: its sections follow its 72 bytes, 80 bytes each
                int nsects = cmds.getInt(at + 64);
                for (int s = 0; s < nsects && at + 72 + (s + 1) * 80 <= cmds.limit(); s++) {
                    out.add(fixedString(cmds, at + 72 + s * 80, 16));
                }
            }
            at += size;
        }
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
