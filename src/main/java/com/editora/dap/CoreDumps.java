package com.editora.dap;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * Core dumps: the memory of a program at the moment it crashed (or was dumped with {@code gcore}), opened in the
 * debugger to look at its threads, stacks and variables. Delve opens a Go program's ({@code "mode": "core"}),
 * CodeLLDB a C, C++ or Rust program's ({@code target create … --core …}). Nothing runs: a core cannot be resumed or
 * stepped.
 *
 * <p>A Linux core (ELF) names the files the program had mapped, the program first ({@code NT_FILE}); that is the
 * program offered. Other cores (a macOS Mach-O one, a Windows minidump) do not, and the program is asked for.
 */
public final class CoreDumps {

    private CoreDumps() {}

    private static final int ET_CORE = 4;
    private static final int PT_NOTE = 4;
    private static final int NT_PRPSINFO = 3;
    private static final int NT_FILE = 0x46494c45;
    /** More notes than a core of any program has: a file claiming more is not read. */
    private static final long MAX_NOTES = 64L * 1024 * 1024;

    /**
     * The program {@code core} is the memory of: the first file its {@code NT_FILE} note lists whose name begins
     * with the process's (which {@code NT_PRPSINFO} gives, cut to 15 characters), else the first file. Null when
     * {@code core} is not a Linux core, or names none.
     */
    public static @Nullable Path programOf(Path core) {
        try (FileChannel ch = FileChannel.open(core)) {
            return programOf(ch);
        } catch (IOException | RuntimeException e) {
            return null; // not a core, or one cut short
        }
    }

    private static @Nullable Path programOf(FileChannel ch) throws IOException {
        ByteBuffer ident = read(ch, 0, 64);
        if (ident.limit() < 52
                || ident.get(0) != 0x7f
                || ident.get(1) != 'E'
                || ident.get(2) != 'L'
                || ident.get(3) != 'F') {
            return null;
        }
        boolean wide = ident.get(4) == 2;
        ident.order(ident.get(5) == 2 ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN);
        if (Short.toUnsignedInt(ident.getShort(16)) != ET_CORE) {
            return null;
        }
        long phoff = wide ? ident.getLong(32) : Integer.toUnsignedLong(ident.getInt(28));
        int phentsize = Short.toUnsignedInt(ident.getShort(wide ? 54 : 42));
        int phnum = Short.toUnsignedInt(ident.getShort(wide ? 56 : 44));
        List<String> files = List.of();
        String process = "";
        for (int i = 0; i < phnum; i++) {
            ByteBuffer ph = read(ch, phoff + (long) i * phentsize, phentsize).order(ident.order());
            if (ph.limit() < (wide ? 40 : 20) || ph.getInt(0) != PT_NOTE) {
                continue;
            }
            long offset = wide ? ph.getLong(8) : Integer.toUnsignedLong(ph.getInt(4));
            long size = wide ? ph.getLong(32) : Integer.toUnsignedLong(ph.getInt(16));
            if (size <= 0 || size > MAX_NOTES) {
                continue;
            }
            ByteBuffer notes = read(ch, offset, (int) size).order(ident.order());
            int at = 0;
            while (at + 12 <= notes.limit()) {
                int namesz = notes.getInt(at);
                int descsz = notes.getInt(at + 4);
                int type = notes.getInt(at + 8);
                int desc = at + 12 + align4(namesz);
                if (namesz < 0 || descsz < 0 || desc + (long) descsz > notes.limit()) {
                    break;
                }
                if (type == NT_FILE && files.isEmpty()) {
                    files = mappedFiles(notes.slice(desc, descsz).order(ident.order()), wide);
                } else if (type == NT_PRPSINFO) {
                    int name = wide ? 40 : 28;
                    if (descsz >= name + 16) {
                        process = cString(notes.slice(desc + name, 16));
                    }
                }
                at = desc + align4(descsz);
            }
        }
        return pick(files, process);
    }

    /** The program among {@code files}: the first named as the process was, else the first. Pure. */
    static @Nullable Path pick(List<String> files, String process) {
        String chosen = null;
        for (String f : files) {
            String name = f.substring(f.lastIndexOf('/') + 1);
            if (!process.isEmpty() && name.startsWith(process)) {
                chosen = f;
                break;
            }
        }
        if (chosen == null && !files.isEmpty()) {
            chosen = files.getFirst();
        }
        try {
            return chosen == null || chosen.isBlank() ? null : Path.of(chosen);
        } catch (java.nio.file.InvalidPathException e) {
            return null;
        }
    }

    /** {@code NT_FILE}'s file names: a count, the page size, the count's mappings, then their names. Pure. */
    static List<String> mappedFiles(ByteBuffer desc, boolean wide) {
        int word = wide ? 8 : 4;
        if (desc.limit() < 2 * word) {
            return List.of();
        }
        long count = wide ? desc.getLong(0) : Integer.toUnsignedLong(desc.getInt(0));
        long names = 2L * word + count * 3 * word;
        if (count <= 0 || names > desc.limit()) {
            return List.of();
        }
        List<String> files = new ArrayList<>();
        int at = (int) names;
        for (long i = 0; i < count && at < desc.limit(); i++) {
            int end = at;
            while (end < desc.limit() && desc.get(end) != 0) {
                end++;
            }
            byte[] b = new byte[end - at];
            desc.get(at, b);
            files.add(new String(b, StandardCharsets.UTF_8));
            at = end + 1;
        }
        return files;
    }

    private static String cString(ByteBuffer b) {
        int end = 0;
        while (end < b.limit() && b.get(end) != 0) {
            end++;
        }
        byte[] bytes = new byte[end];
        b.get(0, bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static int align4(int n) {
        return (n + 3) & ~3;
    }

    private static ByteBuffer read(FileChannel ch, long at, int size) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(Math.max(0, size));
        while (b.hasRemaining()) {
            if (ch.read(b, at + b.position()) < 0) {
                break;
            }
        }
        return b.flip();
    }

    /** What every Go program since Go 1.13 carries: the build information's header. */
    private static final byte[] GO_BUILD_INFO = "ÿ Go buildinf:".getBytes(StandardCharsets.ISO_8859_1);

    /** Where a Rust program's standard library was built: in its panic messages and debug information. */
    private static final byte[] RUST_LIBRARY = "/rustc/".getBytes(StandardCharsets.ISO_8859_1);

    /**
     * The language whose debugger opens {@code program}'s cores: Go, when it carries Go's build information; Rust,
     * when it names Rust's library; else C and C++ (whose debugger, CodeLLDB, is Rust's too).
     */
    public static DebugLanguage languageOf(Path program) {
        boolean rust = false;
        try (InputStream in = Files.newInputStream(program)) {
            byte[] buf = new byte[1 << 20];
            int keep = 0;
            int n;
            while ((n = in.read(buf, keep, buf.length - keep)) > 0) {
                int len = keep + n;
                if (indexOf(buf, len, GO_BUILD_INFO) >= 0) {
                    return DebugLanguages.GO;
                }
                rust |= indexOf(buf, len, RUST_LIBRARY) >= 0;
                keep = Math.min(len, GO_BUILD_INFO.length);
                System.arraycopy(buf, len - keep, buf, 0, keep);
            }
        } catch (IOException e) {
            // unreadable: CodeLLDB will say so
        }
        return rust ? DebugLanguages.RUST : DebugLanguages.CPP;
    }

    static int indexOf(byte[] buf, int len, byte[] what) {
        outer:
        for (int i = 0; i + what.length <= len; i++) {
            for (int j = 0; j < what.length; j++) {
                if (buf[i + j] != what[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    /** The request that opens {@code core} of {@code program} in {@code language}'s debugger. */
    public static Map<String, Object> launch(DebugLanguage language, Path program, Path core) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (language == DebugLanguages.GO) {
            m.put("type", "go");
            m.put("request", "launch");
            m.put("mode", "core");
            m.put("program", program.toString());
            m.put("coreFilePath", core.toString());
            return m;
        }
        m.put("type", "lldb");
        m.put("request", "launch");
        m.put("program", program.toString());
        m.put(
                "targetCreateCommands",
                List.of("target create " + lldbQuoted(program.toString()) + " --core " + lldbQuoted(core.toString())));
        m.put("processCreateCommands", List.of()); // the core is the process: nothing is started
        m.put("sourceLanguages", List.of(language.id()));
        return m;
    }

    /** {@code s} as one LLDB command argument. Pure. */
    static String lldbQuoted(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /**
     * Whether {@code launch} opens a core dump rather than running a program: Delve's {@code "mode": "core"},
     * CodeLLDB's {@code target create} with {@code --core} or {@code -c}, cppdbg's {@code coreDumpPath}. Pure.
     */
    public static boolean isCore(Map<String, ?> launch) {
        if ("core".equals(launch.get("mode"))) {
            return true;
        }
        if (launch.get("coreDumpPath") instanceof String s && !s.isBlank()) {
            return true;
        }
        if (launch.get("targetCreateCommands") instanceof List<?> commands) {
            for (Object c : commands) {
                String text = " " + c + " ";
                if (text.contains(" --core ") || text.contains(" -c ")) {
                    return true;
                }
            }
        }
        return false;
    }
}
