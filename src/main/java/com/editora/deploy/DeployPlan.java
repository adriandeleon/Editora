package com.editora.deploy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.editora.deploy.HostScripts.RemoteFile;
import com.editora.deploy.LocalFiles.LocalFile;

/**
 * What one deploy does, worked out from the project's files, the {@link DeployManifest} of the last deploy and
 * the host's listing. Pure apart from the hashing of a file whose size or time changed (which {@code hasher}
 * does).
 *
 * <p>The rules:
 *
 * <ul>
 *   <li>a file not on the host is sent;
 *   <li>a file whose host copy is still the one Editora sent is sent again only if the local file changed: its
 *       size or time moved <em>and</em> its contents did (a branch switch that only touched times sends nothing);
 *   <li>a file whose host copy changed since Editora sent it is a <b>conflict</b>: the caller asks before it is
 *       overwritten;
 *   <li>a file on the host that Editora never sent (a first deploy into a folder that already has files) is
 *       taken as the same when its size and second match, else sent over it — and counted, so a first deploy
 *       into a full folder can be confirmed;
 *   <li>a file Editora sent that is gone from the project is removed from the host, when the deployment allows
 *       it and the host copy is still the one sent. Nothing else on the host is ever removed.
 * </ul>
 */
public final class DeployPlan {

    /** Computes a local file's SHA-256 (may read the disk). */
    @FunctionalInterface
    public interface Hasher {
        String sha256(LocalFile file) throws IOException;
    }

    private final List<LocalFile> uploads = new ArrayList<>();
    private final List<LocalFile> conflicts = new ArrayList<>();
    private final List<String> overwritten = new ArrayList<>();
    private final List<String> deletes = new ArrayList<>();
    private final Map<String, DeployManifest.Entry> kept = new HashMap<>();
    private final Map<String, String> hashes = new HashMap<>();
    private int unchanged;
    private int keptOnHost;

    private DeployPlan() {}

    /**
     * The plan for sending {@code local} to a host whose folder holds {@code remote} (empty when the folder is
     * not there yet), after a deploy that left {@code manifest}.
     */
    public static DeployPlan of(
            List<LocalFile> local,
            DeployManifest manifest,
            Map<String, RemoteFile> remote,
            boolean deleteRemoved,
            Hasher hasher)
            throws IOException {
        DeployPlan plan = new DeployPlan();
        Set<String> present = new HashSet<>();
        for (LocalFile f : local) {
            present.add(f.relative());
            DeployManifest.Entry sent = manifest.get(f.relative());
            RemoteFile there = remote.get(f.relative());
            if (there == null) {
                plan.uploads.add(f);
            } else if (sent != null && sent.keptOnHost()) {
                // The user kept the host's version: left alone while the local file is what it was then.
                if (f.modified() == sent.modified() || plan.hash(f, hasher).equals(sent.sha256())) {
                    plan.keptOnHost++;
                    plan.kept.put(
                            f.relative(),
                            new DeployManifest.Entry(f.size(), f.modified(), sent.sha256(), there.seconds(), true));
                } else {
                    plan.conflicts.add(f); // edited here since: the two differ again, so ask again
                }
            } else if (sent != null && hostKept(sent, there)) {
                if (f.size() == sent.size() && f.modified() == sent.modified()) {
                    plan.unchanged++;
                    plan.kept.put(f.relative(), sent);
                } else if (f.size() == sent.size() && plan.hash(f, hasher).equals(sent.sha256())) {
                    // Same contents, a new time (a checkout, a touch): nothing to send. Remembered with the new
                    // local time, so the next deploy does not hash it again; the host's copy is as it was.
                    plan.unchanged++;
                    plan.kept.put(
                            f.relative(),
                            new DeployManifest.Entry(f.size(), f.modified(), sent.sha256(), sent.remoteSeconds()));
                } else {
                    plan.uploads.add(f);
                }
            } else if (sent != null) {
                plan.conflicts.add(f); // changed on the host since it was sent
            } else if (there.size() == f.size() && there.seconds() == f.modified() / 1000) {
                // Never sent by this manifest, but the host has the same file (a lost manifest, a copy made by
                // hand): adopted without sending it, its contents hashed so a later change is told apart.
                plan.unchanged++;
                plan.kept.put(
                        f.relative(),
                        new DeployManifest.Entry(f.size(), f.modified(), plan.hash(f, hasher), there.seconds()));
            } else {
                plan.uploads.add(f);
                plan.overwritten.add(f.relative());
            }
        }
        for (Map.Entry<String, DeployManifest.Entry> e : manifest.entries().entrySet()) {
            String relative = e.getKey();
            if (present.contains(relative)) {
                continue;
            }
            RemoteFile there = remote.get(relative);
            if (deleteRemoved && there != null && !e.getValue().keptOnHost() && hostKept(e.getValue(), there)) {
                plan.deletes.add(relative);
            }
            // Otherwise it is no longer Editora's: dropped from the manifest, left on the host.
        }
        return plan;
    }

    /** Whether the host's copy is still the one a deploy left there. */
    private static boolean hostKept(DeployManifest.Entry sent, RemoteFile there) {
        return there.size() == sent.size() && there.seconds() == sent.remoteSeconds();
    }

    private String hash(LocalFile f, Hasher hasher) throws IOException {
        String known = hashes.get(f.relative());
        if (known == null) {
            known = hasher.sha256(f);
            hashes.put(f.relative(), known);
        }
        return known;
    }

    /** Files to send, by path. */
    public List<LocalFile> uploads() {
        return List.copyOf(uploads);
    }

    /** Files changed on the host since they were sent, which the caller asks about before sending. */
    public List<LocalFile> conflicts() {
        return List.copyOf(conflicts);
    }

    /** Files to send over a host file no deploy put there (a first deploy into a folder with files). */
    public List<String> overwritten() {
        return List.copyOf(overwritten);
    }

    /** Files to remove from the host. */
    public List<String> deletes() {
        return List.copyOf(deletes);
    }

    /** Files already on the host as they are here. */
    public int unchanged() {
        return unchanged;
    }

    /** Whether the deploy has nothing to do. */
    public boolean upToDate() {
        return uploads.isEmpty() && conflicts.isEmpty() && deletes.isEmpty();
    }

    /** This plan with the conflicts sent as well (the user chose to overwrite the host's changes). */
    public DeployPlan overwritingConflicts() {
        DeployPlan copy = copy();
        copy.uploads.addAll(conflicts);
        copy.uploads.sort(java.util.Comparator.comparing(LocalFile::relative));
        copy.conflicts.clear();
        return copy;
    }

    /**
     * This plan leaving the conflicts as the host has them. Each is remembered as kept (with the local file as it
     * is now), so the next deploy leaves it alone too instead of taking it for a file it never sent.
     */
    public DeployPlan keepingConflicts(Hasher hasher) throws IOException {
        DeployPlan copy = copy();
        for (LocalFile f : conflicts) {
            copy.kept.put(
                    f.relative(), new DeployManifest.Entry(f.size(), f.modified(), copy.hash(f, hasher), 0, true));
            copy.keptOnHost++;
        }
        copy.conflicts.clear();
        return copy;
    }

    /** Files left as the host has them because the user kept the host's version (now or earlier). */
    public int keptOnHost() {
        return keptOnHost;
    }

    private DeployPlan copy() {
        DeployPlan copy = new DeployPlan();
        copy.uploads.addAll(uploads);
        copy.conflicts.addAll(conflicts);
        copy.overwritten.addAll(overwritten);
        copy.deletes.addAll(deletes);
        copy.kept.putAll(kept);
        copy.hashes.putAll(hashes);
        copy.unchanged = unchanged;
        copy.keptOnHost = keptOnHost;
        return copy;
    }

    /**
     * The folders, deepest first, that removing {@link #deletes} may leave empty — removed only if they are (a
     * folder the host put something else in stays).
     */
    public List<String> emptiedFolders() {
        TreeSet<String> folders = new TreeSet<>((a, b) -> {
            int depth = Long.compare(
                    b.chars().filter(c -> c == '/').count(),
                    a.chars().filter(c -> c == '/').count());
            return depth != 0 ? depth : a.compareTo(b);
        });
        for (String file : deletes) {
            String dir = file;
            int slash;
            while ((slash = dir.lastIndexOf('/')) > 0) {
                dir = dir.substring(0, slash);
                folders.add(dir);
            }
        }
        return List.copyOf(folders);
    }

    /**
     * The manifest after this plan ran: what was already there, plus each sent file with its hash and the
     * host time the archive gave it ({@code seconds} of the local time).
     */
    public DeployManifest manifestAfter(Hasher hasher) {
        Map<String, DeployManifest.Entry> next = new HashMap<>(kept);
        try {
            for (LocalFile f : uploads) {
                next.put(
                        f.relative(),
                        new DeployManifest.Entry(f.size(), f.modified(), hash(f, hasher), f.modified() / 1000));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return DeployManifest.of(next);
    }

    /** Remembers {@code sha256} for a file being sent (the archive writer hashes as it reads). */
    public void knowHash(String relative, String sha256) {
        hashes.put(relative, sha256);
    }
}
