package nativeimage;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Ends a child process together with everything it started. The Python scripts gave each child its own
 * session and signalled the whole process group; Java cannot create a session, so this signals the child's
 * descendants, snapshotted before the child dies (afterwards they are reparented and no longer found).
 */
final class ProcessTree {

    private ProcessTree() {}

    /** Kills the process and its descendants at once (SIGKILL), then waits for the process. */
    static void kill(Process process) throws InterruptedException {
        List<ProcessHandle> tree = tree(process);
        tree.forEach(ProcessHandle::destroyForcibly);
        process.waitFor();
    }

    /**
     * Asks the process and its descendants to end (SIGTERM on POSIX), and kills them if the process is still
     * running after {@code graceSeconds}.
     */
    static void terminate(Process process, long graceSeconds) throws InterruptedException {
        List<ProcessHandle> tree = tree(process);
        tree.forEach(ProcessHandle::destroy);
        if (!process.waitFor(graceSeconds, TimeUnit.SECONDS)) {
            tree.addAll(process.descendants().toList());
            tree.forEach(ProcessHandle::destroyForcibly);
            process.waitFor();
        }
    }

    /** The java launcher running this script, for starting another script on the same JDK. */
    static String javaLauncher() {
        return ProcessHandle.current()
                .info()
                .command()
                .orElseGet(() -> Path.of(System.getProperty("java.home"), "bin", "java").toString());
    }

    private static List<ProcessHandle> tree(Process process) {
        var tree = new ArrayList<ProcessHandle>(process.descendants().toList());
        tree.add(process.toHandle());
        return tree;
    }
}
