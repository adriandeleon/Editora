package com.editora.http;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;

/**
 * Writes a response body to the request's {@code >>} / {@code >>!} targets, and says what it did.
 *
 * <p>A redirect is the one place a {@code .http} file — often one that arrived inside a cloned repository —
 * writes to disk on its own, so the rules are deliberately narrow:
 *
 * <ul>
 *   <li>{@code >> file} creates the file and never touches an existing one (exclusive create, so a file that
 *       appears between the check and the write is safe too).
 *   <li>{@code >>! file} may replace an existing file, but only with a complete, successful response: an error
 *       status or a body that was cut short (size cap, deadline) leaves the file alone. Before the replace the
 *       {@link Guard} is asked to preserve the previous content (Local History); a refusal leaves the file
 *       alone. The replacement is staged beside the target and renamed over it, and only if the file still
 *       holds the bytes the guard preserved.
 *   <li>Every outcome is reported as a line for the response view — which files were written or replaced,
 *       which were not and why. Nothing is skipped in silence.
 * </ul>
 *
 * <p>Containment (a target must stay inside the request file's folder and is never written through a symbolic
 * link) is {@link HttpPaths#containedForWrite}'s; this class adds the overwrite policy.
 */
public final class ResponseRedirects {

    /** What the {@link Guard} decided about one existing file a {@code >>!} is about to replace. */
    public enum Verdict {
        /** The previous content is recorded in Local History; replace the file. */
        KEPT_IN_HISTORY,
        /** Nothing recorded (Local History is off, or the file is binary or too large for it); replace. */
        NOT_KEPT,
        /** The file is open in the editor with unsaved changes; leave it alone. */
        REFUSED_UNSAVED_CHANGES,
        /** The previous content could not be preserved; leave the file alone. */
        REFUSED_NOT_PRESERVED
    }

    /**
     * A {@link Verdict} plus, when known, the exact bytes that were preserved: the replace then only happens
     * while the file still holds them.
     */
    public record Decision(Verdict verdict, byte[] expectedBytes) {

        public Decision {
            verdict = verdict == null ? Verdict.REFUSED_NOT_PRESERVED : verdict;
            expectedBytes = expectedBytes == null ? null : expectedBytes.clone();
        }

        public static Decision of(Verdict verdict) {
            return new Decision(verdict, null);
        }

        boolean allowed() {
            return verdict == Verdict.KEPT_IN_HISTORY || verdict == Verdict.NOT_KEPT;
        }
    }

    /** Asked, on the worker thread, before an existing file is replaced. */
    @FunctionalInterface
    public interface Guard {
        /** Replaces without preserving anything: for a caller that has no history to record into. */
        Guard UNPROTECTED = target -> Decision.of(Verdict.NOT_KEPT);

        Decision beforeOverwrite(Path target);
    }

    private ResponseRedirects() {}

    /**
     * Applies {@code redirects} for a completed response and reports one line per target: a file that was
     * written goes to {@code written}, one that was not goes to {@code warnings} with the reason.
     *
     * @param baseDir   the request file's folder; null (an unsaved buffer) writes nothing
     * @param status    the response status code
     * @param truncated whether {@code body} is only part of the response
     */
    static void write(
            List<HttpFile.Redirect> redirects,
            Path baseDir,
            int status,
            byte[] body,
            boolean truncated,
            Guard guard,
            List<String> written,
            List<String> warnings) {
        if (baseDir == null || redirects == null) {
            return;
        }
        byte[] bytes = body == null ? new byte[0] : body;
        Guard effective = guard == null ? Guard.UNPROTECTED : guard;
        for (HttpFile.Redirect redirect : redirects) {
            String shown = redirect.path() == null ? "" : redirect.path().strip();
            try {
                Path target = HttpPaths.containedForWrite(baseDir, redirect.path());
                if (target == null) {
                    // a ">> ../../x" (or a symlink) must not write outside the request file's folder
                    warnings.add("response not saved to " + shown
                            + ": the path must be a regular file inside the request file's folder");
                    continue;
                }
                shown = display(baseDir, target);
                Line line = writeOne(target, shown, redirect.force(), status, bytes, truncated, effective);
                (line.written() ? written : warnings).add(line.text());
            } catch (IOException | RuntimeException failure) {
                // a failed redirect write never aborts the response — but it is reported
                warnings.add("response not saved to " + shown + ": " + reason(failure));
            }
        }
    }

    /** One report line; {@code written} tells a file that now holds the response from one that was left alone. */
    private record Line(boolean written, String text) {
        static Line skipped(String text) {
            return new Line(false, text);
        }

        static Line saved(String text) {
            return new Line(true, text);
        }
    }

    private static Line writeOne(
            Path target, String shown, boolean force, int status, byte[] body, boolean truncated, Guard guard)
            throws IOException {
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return createNew(target, shown, body);
        }
        if (!force) {
            return Line.skipped("response not saved to " + shown + ": the file already exists (>>! replaces it)");
        }
        if (truncated) {
            return Line.skipped(shown + " not replaced: the response is incomplete");
        }
        if (status < 200 || status > 299) {
            return Line.skipped(shown + " not replaced: the response is an error (HTTP " + status + ")");
        }
        Decision decision = guard.beforeOverwrite(target);
        if (decision == null) {
            decision = Decision.of(Verdict.REFUSED_NOT_PRESERVED);
        }
        if (!decision.allowed()) {
            return Line.skipped(
                    decision.verdict() == Verdict.REFUSED_UNSAVED_CHANGES
                            ? shown + " not replaced: it is open with unsaved changes"
                            : shown + " not replaced: its current content could not be kept in Local History");
        }
        if (!replace(target, decision.expectedBytes(), body)) {
            return Line.skipped(shown + " not replaced: it changed while the response was being saved");
        }
        return Line.saved(
                decision.verdict() == Verdict.KEPT_IN_HISTORY
                        ? "response saved to " + shown + ", replacing the file (previous version in Local History)"
                        : "response saved to " + shown + ", replacing the file (previous version not kept)");
    }

    private static Line createNew(Path target, String shown, byte[] body) throws IOException {
        if (target.getParent() != null) {
            Files.createDirectories(target.getParent());
        }
        try {
            // Raw bytes, exclusive create: a file (or a link) that appeared since the checks is never written.
            Files.write(target, body, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (FileAlreadyExistsException appeared) {
            return Line.skipped(
                    "response not saved to " + shown + ": a file appeared there while the request was running");
        }
        return Line.saved("response saved to " + shown);
    }

    /**
     * Replaces {@code target} with {@code body} through a staging file beside it. With {@code expected} bytes
     * the rename happens only while the target still holds them. The rename replaces the directory entry, so
     * a symbolic link swapped in since the containment check is replaced, never followed.
     */
    private static boolean replace(Path target, byte[] expected, byte[] body) throws IOException {
        if (target.getFileSystem() != java.nio.file.FileSystems.getDefault()) {
            // A remote (SFTP) folder: the shared writer knows that provider's staging and rename rules.
            return expected == null
                    ? com.editora.io.AtomicFileWrite.writeIf(target, body, () -> true)
                    : com.editora.io.AtomicFileWrite.replaceIfUnchanged(target, expected, body, () -> true);
        }
        Path dir = target.toAbsolutePath().getParent();
        if (dir == null) {
            throw new IOException("no folder to stage the file in");
        }
        Path staged = Files.createTempFile(dir, "." + stem(target) + ".", ".editora-tmp");
        try {
            Files.write(staged, body);
            keepPermissions(target, staged);
            if (Files.isSymbolicLink(target) || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                return false;
            }
            if (expected != null && !Arrays.equals(expected, Files.readAllBytes(target))) {
                return false;
            }
            try {
                Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException notAtomic) {
                Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    private static void keepPermissions(Path target, Path staged) {
        try {
            Files.setPosixFilePermissions(staged, Files.getPosixFilePermissions(target, LinkOption.NOFOLLOW_LINKS));
        } catch (IOException | RuntimeException notPosix) {
            // not a POSIX file system: the default permissions stand
        }
    }

    /** A short, file-name-safe prefix for the staging file. */
    private static String stem(Path target) {
        String name = target.getFileName().toString();
        return name.length() > 40 ? name.substring(0, 40) : name;
    }

    private static String display(Path baseDir, Path target) {
        try {
            return baseDir.toAbsolutePath()
                    .normalize()
                    .relativize(target)
                    .toString()
                    .replace('\\', '/');
        } catch (RuntimeException unrelated) {
            return target.toString();
        }
    }

    private static String reason(Exception failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }
}
