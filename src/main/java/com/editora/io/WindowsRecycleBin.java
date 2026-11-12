package com.editora.io;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ToIntFunction;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.WString;
import com.sun.jna.win32.StdCallLibrary;
import org.jspecify.annotations.NullUnmarked;
import org.jspecify.annotations.Nullable;

/**
 * The Windows Recycle Bin, reached through the shell's {@code SHFileOperationW} with "allow undo": what Explorer
 * does for Delete, so the file can be restored from the bin Explorer shows.
 *
 * <p>Only a fixed local drive has a Recycle Bin. A file on a network share, a WSL distribution or a removable
 * drive is not {@linkplain #accepts accepted}, so the delete that follows says truthfully that it is for good.
 * And where Windows would destroy the file all the same (too large for the bin, the bin switched off by
 * policy), it asks first ({@code FOF_WANTNUKEWARNING}): this class never deletes silently.
 */
final class WindowsRecycleBin implements Trash.Bin {

    static final int FO_DELETE = 3;
    static final short FOF_SILENT = 0x0004;
    static final short FOF_NOCONFIRMATION = 0x0010;
    static final short FOF_ALLOWUNDO = 0x0040;
    static final short FOF_NOERRORUI = 0x0400;
    static final short FOF_WANTNUKEWARNING = 0x4000;

    /** Recycle, without Explorer's own "are you sure" or its progress window, but with its warning before a real delete. */
    static final short FLAGS =
            (short) (FOF_ALLOWUNDO | FOF_NOCONFIRMATION | FOF_SILENT | FOF_NOERRORUI | FOF_WANTNUKEWARNING);

    /** {@code GetDriveTypeW}'s answer for a fixed local drive. */
    static final int DRIVE_FIXED = 3;

    /** {@code SHFILEOPSTRUCTW}, aligned as it is on 64-bit Windows. */
    // Native bindings: nullness is the C API's, so NullAway does not check them.
    @NullUnmarked
    @Structure.FieldOrder({
        "hwnd",
        "wFunc",
        "pFrom",
        "pTo",
        "fFlags",
        "fAnyOperationsAborted",
        "hNameMappings",
        "lpszProgressTitle"
    })
    public static class FileOperation extends Structure {
        public Pointer hwnd;
        public int wFunc;
        public Pointer pFrom;
        public Pointer pTo;
        public short fFlags;
        public int fAnyOperationsAborted;
        public Pointer hNameMappings;
        public Pointer lpszProgressTitle;
    }

    @NullUnmarked
    interface Shell32 extends StdCallLibrary {
        int SHFileOperationW(FileOperation operation);
    }

    @NullUnmarked
    interface Kernel32 extends StdCallLibrary {
        int GetDriveTypeW(WString rootPath);
    }

    /** Sends one absolute path to the bin; answers Windows' result code, with -1 for "the user said no". */
    interface Recycler {
        int recycle(String absolutePath);
    }

    private final Recycler recycler;
    private final ToIntFunction<String> driveType;
    private final Map<String, Boolean> fixedDrives = new ConcurrentHashMap<>();

    WindowsRecycleBin(Recycler recycler, ToIntFunction<String> driveType) {
        this.recycler = recycler;
        this.driveType = driveType;
    }

    /** The Recycle Bin of this machine, or null where the shell cannot be reached (any OS but Windows). */
    static @Nullable WindowsRecycleBin system() {
        try {
            Shell32 shell = Native.load("shell32", Shell32.class);
            Kernel32 kernel = Native.load("kernel32", Kernel32.class);
            return new WindowsRecycleBin(path -> recycle(shell, path), root -> kernel.GetDriveTypeW(new WString(root)));
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            return null;
        }
    }

    private static int recycle(Shell32 shell, String absolutePath) {
        FileOperation operation = new FileOperation();
        operation.wFunc = FO_DELETE;
        operation.pFrom = doubleNulTerminated(absolutePath);
        operation.fFlags = FLAGS;
        int result = shell.SHFileOperationW(operation);
        return result == 0 && operation.fAnyOperationsAborted != 0 ? -1 : result;
    }

    /** {@code path} as the list of one the shell wants: UTF-16, ended by a NUL, the list by another. */
    static Memory doubleNulTerminated(String path) {
        Memory memory = new Memory((path.length() + 2L) * Native.WCHAR_SIZE);
        memory.clear();
        memory.setWideString(0, path);
        return memory;
    }

    @Override
    public boolean accepts(Path file) {
        if (!Trash.trashable(file)) {
            return false;
        }
        Path root = file.toAbsolutePath().getRoot();
        if (root == null) {
            return false;
        }
        return fixedDrives.computeIfAbsent(root.toString(), r -> driveType.applyAsInt(r) == DRIVE_FIXED);
    }

    @Override
    public void trash(Path file) throws IOException {
        Path source = Trash.requireTrashable(file);
        if (!accepts(source)) {
            throw new IOException("The Recycle Bin cannot take files from this drive: " + source);
        }
        int result = recycler.recycle(source.toString());
        if (result == -1) {
            throw new IOException("Not moved to the Recycle Bin: " + source);
        }
        if (result != 0) {
            throw new IOException("The Recycle Bin refused " + source + " (Windows error " + result + ")");
        }
    }
}
