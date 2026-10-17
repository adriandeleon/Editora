package com.editora.terminal;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;

/**
 * The working directory of a running process, read from the OS.
 *
 * <p>This exists because a terminal cannot rely on the shell to tell it where it is. The
 * conventional channel is an escape sequence — OSC 0 for the title, OSC 7 for the directory — but
 * emitting one is the <em>prompt's</em> job, and a prompt that does not bother is entirely normal:
 * Debian's stock {@code ~/.bashrc} adds the title escape to {@code PS1}, and any prompt framework
 * that replaces {@code PS1} wholesale (Oh My Bash, Starship, powerlevel10k) silently drops it.
 * Asking the OS instead works whatever the user's prompt does, and needs no setup from them.
 *
 * <p>Every call is best-effort and non-throwing: the process may have exited between the caller
 * deciding to ask and the read landing, which is ordinary rather than exceptional.
 */
public final class ProcessCwd {

    private ProcessCwd() {}

    private static final boolean LINUX =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");
    private static final boolean MAC =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("mac");

    /** True where {@link #of(long)} can ever return a value, so callers can skip polling entirely. */
    public static boolean isSupported() {
        return LINUX || MAC;
    }

    /**
     * @param pid the process to ask about — for us, the shell attached to the PTY
     * @return its current working directory, or empty if this platform cannot say
     */
    public static Optional<String> of(long pid) {
        if (pid <= 0) return Optional.empty();
        try {
            if (LINUX) return linux(pid);
            if (MAC) return mac(pid);
        } catch (Throwable t) {
            // Includes UnsatisfiedLinkError from the macOS path on a system that has moved libproc.
            // A terminal that cannot read a cwd shows a less useful title; it does not fail.
            return Optional.empty();
        }
        return Optional.empty();
    }

    private static Optional<String> linux(long pid) {
        Path link = Path.of("/proc", Long.toString(pid), "cwd");
        if (!Files.isSymbolicLink(link)) return Optional.empty();
        String target;
        try {
            target = Files.readSymbolicLink(link).toString();
        } catch (Exception e) {
            return Optional.empty();
        }
        // The kernel appends this when the directory has been unlinked underneath the process.
        // It is a description, not part of any path, and showing it in a tab reads as corruption.
        if (target.endsWith(DELETED)) target = target.substring(0, target.length() - DELETED.length());
        return target.isBlank() ? Optional.empty() : Optional.of(target);
    }

    private static final String DELETED = " (deleted)";

    // --- macOS ------------------------------------------------------------------------------
    //
    // proc_pidinfo(PROC_PIDVNODEPATHINFO) fills a `struct proc_vnodepathinfo`, whose first member
    // is the current directory. Rather than map the struct in JNA — twenty-odd fields, every one
    // of them a chance to get an offset wrong — the path is read at its fixed offset:
    //
    //   struct proc_vnodepathinfo { struct vnode_info_path pvi_cdir; pvi_rdir; };   // 2 x 1176
    //   struct vnode_info_path    { struct vnode_info vip_vi;  char vip_path[1024]; };
    //   struct vnode_info         { struct vinfo_stat (136); int vi_type; int vi_pad; fsid_t (8); }
    //
    // so vip_path of the current directory begins 152 bytes in. These are ABI-stable public
    // structures in <sys/proc_info.h>; they have not changed since 10.5.

    private static final int PROC_PIDVNODEPATHINFO = 9;
    private static final int VNODE_PATH_INFO_SIZE = 2352;
    private static final int CDIR_PATH_OFFSET = 152;
    private static final int MAX_PATH_LEN = 1024;

    /**
     * Loaded lazily so that the JNA classes are never touched off macOS — a plain static field
     * would run {@code Native.load} at class initialisation on every platform.
     */
    private interface LibProc extends Library {
        LibProc INSTANCE = Native.load("proc", LibProc.class);

        int proc_pidinfo(int pid, int flavor, long arg, Pointer buffer, int buffersize);
    }

    private static Optional<String> mac(long pid) {
        try (Memory buffer = new Memory(VNODE_PATH_INFO_SIZE)) {
            buffer.clear();
            int written =
                    LibProc.INSTANCE.proc_pidinfo((int) pid, PROC_PIDVNODEPATHINFO, 0, buffer, VNODE_PATH_INFO_SIZE);
            // A short write means the struct we were given is not the one we are reading.
            if (written < CDIR_PATH_OFFSET + 1) return Optional.empty();
            String path = buffer.getString(CDIR_PATH_OFFSET);
            return path == null || path.isBlank() || path.length() > MAX_PATH_LEN
                    ? Optional.empty()
                    : Optional.of(path);
        }
    }
}
