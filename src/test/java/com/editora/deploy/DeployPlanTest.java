package com.editora.deploy;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.editora.deploy.HostScripts.RemoteFile;
import com.editora.deploy.LocalFiles.LocalFile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeployPlanTest {

    private static final long T = 1_700_000_000_123L;

    private static LocalFile file(String rel, long size, long modified) {
        return new LocalFile(rel, Path.of("/p").resolve(rel), size, modified, false);
    }

    private static DeployPlan.Hasher hashes(Map<String, String> byName, AtomicInteger calls) {
        return f -> {
            calls.incrementAndGet();
            return byName.getOrDefault(f.relative(), "h-" + f.relative());
        };
    }

    private static List<String> names(List<LocalFile> files) {
        return files.stream().map(LocalFile::relative).toList();
    }

    @Test
    void aFirstDeployToAMissingFolderSendsEverythingAndHashesOnlyToRecord() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        DeployPlan plan = DeployPlan.of(
                List.of(file("a.txt", 3, T), file("src/b.txt", 4, T)),
                new DeployManifest(),
                Map.of(),
                true,
                hashes(Map.of(), calls));
        assertEquals(List.of("a.txt", "src/b.txt"), names(plan.uploads()));
        assertEquals(0, calls.get(), "nothing hashed to decide");
        DeployManifest after = plan.manifestAfter(hashes(Map.of(), calls));
        assertEquals(T / 1000, after.get("a.txt").remoteSeconds(), "the host keeps the archive's second");
        assertEquals("h-a.txt", after.get("a.txt").sha256());
    }

    @Test
    void anUnchangedFileIsNotSentOrHashed() throws Exception {
        DeployManifest m = DeployManifest.of(Map.of("a.txt", new DeployManifest.Entry(3, T, "h", T / 1000)));
        AtomicInteger calls = new AtomicInteger();
        DeployPlan plan = DeployPlan.of(
                List.of(file("a.txt", 3, T)),
                m,
                Map.of("a.txt", new RemoteFile(3, T / 1000)),
                true,
                hashes(Map.of(), calls));
        assertTrue(plan.upToDate());
        assertEquals(1, plan.unchanged());
        assertEquals(0, calls.get());
    }

    @Test
    void aTouchedButIdenticalFileIsNotSent() throws Exception {
        DeployManifest m = DeployManifest.of(Map.of("a.txt", new DeployManifest.Entry(3, T, "same", T / 1000)));
        AtomicInteger calls = new AtomicInteger();
        DeployPlan plan = DeployPlan.of(
                List.of(file("a.txt", 3, T + 60_000)),
                m,
                Map.of("a.txt", new RemoteFile(3, T / 1000)),
                true,
                hashes(Map.of("a.txt", "same"), calls));
        assertTrue(plan.upToDate(), "a checkout that only moved the time sends nothing");
        DeployManifest after = plan.manifestAfter(hashes(Map.of(), calls));
        assertEquals(T + 60_000, after.get("a.txt").modified(), "the new local time is remembered");
        assertEquals(T / 1000, after.get("a.txt").remoteSeconds(), "the host copy is as it was");
    }

    @Test
    void anEditedFileIsSent() throws Exception {
        DeployManifest m = DeployManifest.of(Map.of("a.txt", new DeployManifest.Entry(3, T, "old", T / 1000)));
        DeployPlan plan = DeployPlan.of(
                List.of(file("a.txt", 5, T + 1000)),
                m,
                Map.of("a.txt", new RemoteFile(3, T / 1000)),
                true,
                hashes(Map.of(), new AtomicInteger()));
        assertEquals(List.of("a.txt"), names(plan.uploads()));
    }

    @Test
    void aFileChangedOnTheHostIsAConflictNotAnOverwrite() throws Exception {
        DeployManifest m = DeployManifest.of(Map.of("a.txt", new DeployManifest.Entry(3, T, "h", T / 1000)));
        DeployPlan plan = DeployPlan.of(
                List.of(file("a.txt", 3, T)),
                m,
                Map.of("a.txt", new RemoteFile(9, T / 1000 + 300)),
                true,
                hashes(Map.of(), new AtomicInteger()));
        assertEquals(List.of("a.txt"), names(plan.conflicts()));
        assertTrue(plan.uploads().isEmpty());
        assertEquals(List.of("a.txt"), names(plan.overwritingConflicts().uploads()));
        DeployPlan kept = plan.keepingConflicts();
        assertTrue(kept.upToDate());
        assertTrue(kept.manifestAfter(hashes(Map.of(), new AtomicInteger())).isEmpty(), "no longer Editora's file");
    }

    @Test
    void aFileGoneFromTheProjectIsRemovedOnlyIfEditoraSentItAndItIsUnchanged() throws Exception {
        Map<String, DeployManifest.Entry> sent = new HashMap<>();
        sent.put("old/gone.txt", new DeployManifest.Entry(3, T, "h", T / 1000));
        sent.put("edited-there.txt", new DeployManifest.Entry(3, T, "h", T / 1000));
        Map<String, RemoteFile> remote = new HashMap<>();
        remote.put("old/gone.txt", new RemoteFile(3, T / 1000));
        remote.put("edited-there.txt", new RemoteFile(7, T / 1000 + 5));
        remote.put("app.log", new RemoteFile(100, T / 1000)); // the host's own file
        DeployPlan plan =
                DeployPlan.of(List.of(), DeployManifest.of(sent), remote, true, hashes(Map.of(), new AtomicInteger()));
        assertEquals(List.of("old/gone.txt"), plan.deletes());
        assertEquals(List.of("old"), plan.emptiedFolders());

        DeployPlan keep =
                DeployPlan.of(List.of(), DeployManifest.of(sent), remote, false, hashes(Map.of(), new AtomicInteger()));
        assertTrue(keep.deletes().isEmpty(), "deleteRemoved off removes nothing");
    }

    @Test
    void aFirstDeployIntoAFullFolderAdoptsIdenticalFilesAndCountsTheOverwrites() throws Exception {
        DeployPlan plan = DeployPlan.of(
                List.of(file("same.txt", 3, T), file("differs.txt", 3, T)),
                new DeployManifest(),
                Map.of("same.txt", new RemoteFile(3, T / 1000), "differs.txt", new RemoteFile(8, T / 1000)),
                true,
                hashes(Map.of(), new AtomicInteger()));
        assertEquals(List.of("differs.txt"), names(plan.uploads()));
        assertEquals(List.of("differs.txt"), plan.overwritten());
        assertEquals(1, plan.unchanged());
    }

    @Test
    void aFileTheHostLostIsSentAgain() throws Exception {
        DeployManifest m = DeployManifest.of(Map.of("a.txt", new DeployManifest.Entry(3, T, "h", T / 1000)));
        DeployPlan plan =
                DeployPlan.of(List.of(file("a.txt", 3, T)), m, Map.of(), true, hashes(Map.of(), new AtomicInteger()));
        assertEquals(List.of("a.txt"), names(plan.uploads()));
    }

    @Test
    void emptiedFoldersComeDeepestFirst() throws Exception {
        Map<String, DeployManifest.Entry> sent = new HashMap<>();
        Map<String, RemoteFile> remote = new HashMap<>();
        for (String f : List.of("a/b/c/x.txt", "a/y.txt", "d/z.txt")) {
            sent.put(f, new DeployManifest.Entry(1, T, "h", T / 1000));
            remote.put(f, new RemoteFile(1, T / 1000));
        }
        DeployPlan plan =
                DeployPlan.of(List.of(), DeployManifest.of(sent), remote, true, hashes(Map.of(), new AtomicInteger()));
        assertEquals(List.of("a/b/c", "a/b", "a", "d"), plan.emptiedFolders());
    }
}
