package com.editora.dap;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * A Linux core file as small as one can be: a 64-bit little-endian ELF header of type core, and one note segment
 * holding what Editora reads from a real one — the process's name ({@code NT_PRPSINFO}) and the files it had
 * mapped ({@code NT_FILE}).
 */
public final class FakeCoreFile {

    private FakeCoreFile() {}

    /** Writes the core of process {@code process} that had {@code mapped} mapped, in that order, to {@code file}. */
    public static Path write(Path file, String process, List<String> mapped) throws IOException {
        ByteArrayOutputStream notes = new ByteArrayOutputStream();
        byte[] info = new byte[136];
        byte[] name = process.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(name, 0, info, 40, Math.min(15, name.length));
        note(notes, 3, info);
        ByteArrayOutputStream files = new ByteArrayOutputStream();
        ByteBuffer head = le(16 + mapped.size() * 24L);
        head.putLong(mapped.size()).putLong(4096);
        for (int i = 0; i < mapped.size(); i++) {
            head.putLong(0x400000L + i * 0x10000L)
                    .putLong(0x401000L + i * 0x10000L)
                    .putLong(0);
        }
        files.write(head.array());
        for (String m : mapped) {
            files.write(m.getBytes(StandardCharsets.UTF_8));
            files.write(0);
        }
        note(notes, 0x46494c45, files.toByteArray());

        ByteBuffer elf = le(64 + 56 + notes.size());
        elf.put(new byte[] {0x7f, 'E', 'L', 'F', 2, 1, 1, 0}).put(new byte[8]);
        elf.putShort((short) 4).putShort((short) 62).putInt(1); // ET_CORE, x86-64, version
        elf.putLong(0).putLong(64).putLong(0); // entry, phoff, shoff
        elf.putInt(0).putShort((short) 64).putShort((short) 56).putShort((short) 1); // flags, ehsize, phentsize, phnum
        elf.putShort((short) 0).putShort((short) 0).putShort((short) 0);
        elf.putInt(4).putInt(0).putLong(120).putLong(0).putLong(0); // PT_NOTE at 120
        elf.putLong(notes.size()).putLong(0).putLong(4);
        elf.put(notes.toByteArray());
        return Files.write(file, elf.array());
    }

    private static void note(ByteArrayOutputStream out, int type, byte[] desc) throws IOException {
        ByteBuffer b = le(12 + 8 + ((desc.length + 3) & ~3));
        b.putInt(5).putInt(desc.length).putInt(type);
        b.put("CORE".getBytes(StandardCharsets.US_ASCII)).put(new byte[4]);
        b.put(desc);
        out.write(b.array());
    }

    private static ByteBuffer le(long size) {
        return ByteBuffer.allocate((int) size).order(ByteOrder.LITTLE_ENDIAN);
    }
}
