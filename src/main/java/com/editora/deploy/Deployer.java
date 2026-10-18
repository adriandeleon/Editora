package com.editora.deploy;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.zip.Deflater;
import java.util.zip.GZIPOutputStream;

import com.editora.config.Deployment;
import com.editora.deploy.LocalFiles.LocalFile;
import com.editora.process.ExecSpec;
import com.editora.process.ExecTarget;
import com.editora.process.ProcessRunner;

/**
 * Brings a deployment's folder on its host up to date with the local project: lists both, works out a
 * {@link DeployPlan}, asks about files changed on the host, sends what changed as one gzip-compressed tar stream
 * over an exec channel of the existing SSH connection (file by file over SFTP when the host has no {@code tar}),
 * removes what the project no longer has, and keeps a {@link DeployManifest} for next time. No {@code rsync} and
 * no system {@code ssh}: everything goes over the connection Editora already holds (ADR 0015).
 *
 * <p>Blocking: run it off the FX thread. {@link Decisions} are asked from that thread too.
 */
public final class Deployer {

    private Deployer() {}

    /** What a deploy needs: the project, the deployment, its folder on the host, and the host. */
    public record Request(
            Path localRoot,
            Deployment deployment,
            Path remoteRoot,
            ExecTarget target,
            String home,
            Path manifestFile,
            BooleanSupplier cancelled) {}

    /** What the user decides during a deploy. Called off the FX thread; may block for an answer. */
    public interface Decisions {

        /** What to do with files changed on the host since they were sent. */
        enum Conflicts {
            OVERWRITE,
            KEEP_HOST,
            CANCEL
        }

        Conflicts conflicts(List<String> files);

        /** Whether a first deploy may write over {@code files}, which the folder already holds. */
        boolean overwriteExisting(List<String> files);
    }

    /** How a deploy is going, for the Run console. Called off the FX thread. */
    public interface Listener {
        default void listed(int localFiles) {}

        default void planned(DeployPlan plan) {}

        default void sending(int done, int total, long bytes) {}
    }

    /** How a deploy ended. */
    public enum Status {
        OK,
        CANCELLED,
        /** The folder is not one a deploy may use ({@link #rootProblem}). */
        REFUSED,
        FAILED
    }

    /** The result: counts on success, the reason otherwise. */
    public record Outcome(
            Status status,
            int sent,
            long bytes,
            int deleted,
            int unchanged,
            int keptOnHost,
            DeployRoot.Problem rootProblem,
            String error) {

        public boolean ok() {
            return status == Status.OK;
        }

        static Outcome failed(String error) {
            return new Outcome(Status.FAILED, 0, 0, 0, 0, 0, null, error);
        }

        static Outcome cancelled() {
            return new Outcome(Status.CANCELLED, 0, 0, 0, 0, 0, null, null);
        }
    }

    private static final Duration LIST_TIMEOUT = Duration.ofMinutes(2);

    /** Runs one deploy. Never throws: a failure is an {@link Outcome}. */
    public static Outcome deploy(Request request, Listener listener, Decisions decisions) {
        try {
            return run(request, listener == null ? new Listener() {} : listener, decisions);
        } catch (IOException | RuntimeException e) {
            String message = e.getMessage();
            return Outcome.failed(message == null || message.isBlank() ? e.toString() : message);
        }
    }

    private static Outcome run(Request request, Listener listener, Decisions decisions) throws IOException {
        Deployment d = request.deployment();
        String root = DeployRoot.normalize(d.remoteRoot());
        DeployRoot.Problem problem = DeployRoot.problem(root, request.home());
        if (problem != null) {
            return new Outcome(Status.REFUSED, 0, 0, 0, 0, 0, problem, null);
        }
        DeployExcludes excludes = DeployExcludes.of(d.excludes());
        List<LocalFile> local = LocalFiles.list(request.localRoot(), excludes, d.useGitignore());
        listener.listed(local.size());
        if (cancelled(request)) {
            return Outcome.cancelled();
        }
        Path hostRoot = request.remoteRoot().getFileSystem().getPath("/");
        ProcessRunner.Result listed = ProcessRunner.run(
                hostRoot,
                LIST_TIMEOUT,
                List.of("sh", "-c", HostScripts.listing(excludes.prunableNames()), HostScripts.NAME, root));
        HostScripts.Listing listing = HostScripts.parseListing(listed.out());
        if (!listed.ok() || listing == null) {
            return Outcome.failed(firstLine(listed.err(), "could not list " + root + " on the host"));
        }
        if (listing.state() == HostScripts.Listing.State.NOT_A_FOLDER) {
            return Outcome.failed(root + " is a file on the host, not a folder");
        }
        DeployManifest manifest = DeployManifest.load(request.manifestFile());
        DeployPlan.Hasher hasher = f -> Hashing.sha256(f.path());
        DeployPlan plan = DeployPlan.of(local, manifest, listing.files(), d.deleteRemoved(), hasher);
        if (!plan.conflicts().isEmpty()) {
            Decisions.Conflicts choice = decisions == null
                    ? Decisions.Conflicts.CANCEL
                    : decisions.conflicts(
                            plan.conflicts().stream().map(LocalFile::relative).toList());
            switch (choice) {
                case OVERWRITE -> plan = plan.overwritingConflicts();
                case KEEP_HOST -> plan = plan.keepingConflicts(hasher);
                default -> {
                    return Outcome.cancelled();
                }
            }
        }
        if (manifest.isEmpty()
                && !plan.overwritten().isEmpty()
                && (decisions == null || !decisions.overwriteExisting(plan.overwritten()))) {
            return Outcome.cancelled();
        }
        listener.planned(plan);
        long bytes = 0;
        if (!plan.uploads().isEmpty()) {
            bytes = listing.tar()
                    ? sendArchive(request, root, plan, listing.gzip(), listener)
                    : sendEachFile(request, plan, listener);
            if (bytes < 0) {
                return Outcome.cancelled();
            }
        }
        if (!plan.deletes().isEmpty()) {
            remove(hostRoot, root, plan);
        }
        plan.manifestAfter(hasher).save(request.manifestFile());
        return new Outcome(
                Status.OK,
                plan.uploads().size(),
                bytes,
                plan.deletes().size(),
                plan.unchanged(),
                plan.keptOnHost(),
                null,
                null);
    }

    /** Sends the plan's files as one tar stream into the host's {@code tar}; -1 when cancelled. */
    private static long sendArchive(Request request, String root, DeployPlan plan, boolean gzip, Listener listener)
            throws IOException {
        ExecSpec spec = ExecSpec.of(
                List.of("sh", "-c", HostScripts.unpack(), HostScripts.NAME, root, gzip ? "1" : "0"),
                null,
                Map.of(),
                // The host's own locale: GNU tar turns a pax (UTF-8) name into the locale's characters, and in
                // the C locale it cannot.
                ExecSpec.Locale.USER);
        Process process = request.target().start(spec);
        StringBuilder err = new StringBuilder();
        Thread errors = drain(process.getErrorStream(), err);
        Thread output = drain(process.getInputStream(), new StringBuilder());
        List<LocalFile> files = plan.uploads();
        long bytes = 0;
        boolean cancelled = false;
        try (OutputStream raw = new BufferedOutputStream(process.getOutputStream(), 64 * 1024);
                OutputStream body = gzip ? fastGzip(raw) : raw) {
            TarWriter tar = new TarWriter(body);
            long lastReport = 0;
            for (int i = 0; i < files.size(); i++) {
                if (cancelled(request)) {
                    cancelled = true;
                    break;
                }
                LocalFile f = files.get(i);
                try (InputStream in = Files.newInputStream(f.path())) {
                    plan.knowHash(
                            f.relative(), tar.addFile(f.relative(), f.size(), f.modified() / 1000, f.executable(), in));
                }
                bytes += f.size();
                long now = System.nanoTime();
                if (now - lastReport > 250_000_000L || i == files.size() - 1) {
                    lastReport = now;
                    listener.sending(i + 1, files.size(), bytes);
                }
            }
            if (!cancelled) {
                tar.finish();
            }
        } catch (IOException e) {
            process.destroy();
            join(errors);
            throw new IOException(firstLine(err.toString(), e.getMessage()), e);
        }
        if (cancelled) {
            process.destroy();
            return -1;
        }
        int exit;
        try {
            if (!process.waitFor(10, TimeUnit.MINUTES)) {
                process.destroy();
                throw new IOException("the host did not finish unpacking the files");
            }
            exit = process.exitValue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroy();
            throw new IOException("interrupted", e);
        }
        join(errors);
        join(output);
        if (exit != 0) {
            throw new IOException(firstLine(err.toString(), "tar on the host failed (exit " + exit + ")"));
        }
        return bytes;
    }

    private static OutputStream fastGzip(OutputStream raw) throws IOException {
        return new GZIPOutputStream(raw, 64 * 1024) {
            {
                def.setLevel(Deflater.BEST_SPEED); // the network is the slow part, not the compression
            }
        };
    }

    /** Sends the plan's files one by one over SFTP, for a host without {@code tar}; -1 when cancelled. */
    private static long sendEachFile(Request request, DeployPlan plan, Listener listener) throws IOException {
        List<LocalFile> files = plan.uploads();
        long bytes = 0;
        for (int i = 0; i < files.size(); i++) {
            if (cancelled(request)) {
                return -1;
            }
            LocalFile f = files.get(i);
            Path target = request.remoteRoot().resolve(f.relative());
            Files.createDirectories(target.getParent());
            Path part = target.resolveSibling(target.getFileName() + ".editora-part");
            Files.copy(f.path(), part, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.setPosixFilePermissions(
                        part, PosixFilePermissions.fromString(f.executable() ? "rwxr-xr-x" : "rw-r--r--"));
            } catch (UnsupportedOperationException | IOException e) {
                // the server keeps its own mode: still a usable copy
            }
            Files.setLastModifiedTime(part, FileTime.fromMillis(f.modified() / 1000 * 1000));
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING); // an SFTP rename
            plan.knowHash(f.relative(), Hashing.sha256(f.path()));
            bytes += f.size();
            listener.sending(i + 1, files.size(), bytes);
        }
        return bytes;
    }

    /** Removes the plan's deleted files, then the folders that left empty. */
    private static void remove(Path hostRoot, String root, DeployPlan plan) throws IOException {
        StringBuilder input = new StringBuilder();
        for (String file : plan.deletes()) {
            input.append(file).append('\n');
        }
        input.append("::dirs\n");
        for (String dir : plan.emptiedFolders()) {
            input.append(dir).append('\n');
        }
        ProcessRunner.Result result = ProcessRunner.runWithInput(
                hostRoot,
                LIST_TIMEOUT,
                List.of("sh", "-c", HostScripts.remove(), HostScripts.NAME, root),
                Map.of(),
                input.toString().getBytes(StandardCharsets.UTF_8));
        if (!result.ok()) {
            throw new IOException(firstLine(result.err(), "could not remove files on the host"));
        }
    }

    private static boolean cancelled(Request request) {
        return request.cancelled() != null && request.cancelled().getAsBoolean();
    }

    private static Thread drain(InputStream in, StringBuilder into) {
        return Thread.ofVirtual().name("deploy-drain").start(() -> {
            byte[] buffer = new byte[8192];
            try (in) {
                int n;
                while ((n = in.read(buffer)) > 0) {
                    synchronized (into) {
                        if (into.length() < 64 * 1024) {
                            into.append(new String(buffer, 0, n, StandardCharsets.UTF_8));
                        }
                    }
                }
            } catch (IOException e) {
                // the channel closed: what was read is all there is
            }
        });
    }

    private static void join(Thread t) {
        try {
            t.join(5_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** The first non-blank line of {@code text}, else {@code fallback}. */
    static String firstLine(String text, String fallback) {
        if (text != null) {
            List<String> lines = new ArrayList<>(
                    text.lines().map(String::strip).filter(s -> !s.isEmpty()).toList());
            if (!lines.isEmpty()) {
                return lines.get(0);
            }
        }
        return fallback;
    }
}
