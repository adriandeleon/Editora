package com.editora.process;

/**
 * A child running on another machine. It is a {@link Process} so the code that pumps output, waits for exit
 * and speaks a protocol over the streams needs no second path; what it cannot offer is anything tied to this
 * machine's process table. {@link #pid()}, {@link #toHandle()} and {@link #descendants()} keep
 * {@code Process}'s defaults and throw: the remote pid is a number in another namespace, and one mistaken for
 * a local pid would signal an unrelated process here. {@link ProcessRegistry} stops a remote tree through
 * {@link #signalTree} instead.
 */
public abstract class RemoteProcess extends Process {

    /** The {@link ExecTarget#id()} it runs on. */
    public abstract String targetId();

    /** Its pid on the target, or {@code -1} when it was never learned. */
    public abstract long remotePid();

    /**
     * Asks the target to stop the whole process group: politely, or with {@code force} for good. Must not
     * block; safe to call again and after the process has gone.
     */
    public abstract void signalTree(boolean force);

    /** True once the process ended because its connection did, so its exit code means nothing. */
    public abstract boolean connectionLost();

    /**
     * How the next Editora finds this program again and stops it, should this one die without doing so: the
     * {@linkplain ProcessRegistry#registerOrphanReaper reaper}'s kind, then what that reaper needs. {@code null}
     * when the target ends Editora's programs by itself when Editora goes (an SSH session's watchdog). May ask
     * the target: never call it on the FX thread.
     */
    public java.util.List<String> orphanRecord() {
        return null;
    }

    /**
     * {@link #signalTree}, returning only once the target has been asked, or after {@code millis}: for the
     * shutdown hook, whose last word must not be lost to a JVM that is already exiting.
     */
    public void signalTreeNow(boolean force, long millis) {
        signalTree(force);
    }

    @Override
    public final void destroy() {
        signalTree(false);
    }

    @Override
    public final Process destroyForcibly() {
        signalTree(true);
        return this;
    }
}
