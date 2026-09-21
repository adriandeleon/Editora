package com.editora.agent.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class AgentSessionStoreTest {
    @TempDir
    Path root;

    @Test
    void restartRetainsCompleteHistoryButDetectsChangedFilesAndDropsAuthority() throws Exception {
        Path repo = Files.createDirectory(root.resolve("repo"));
        Files.writeString(repo.resolve("A.java"), "before");
        var context = new AgentContext();
        context.add(List.of(AgentModel.Message.text("user", "fix A")));
        var call = new AgentModel.Call("read1", "read_file", "{\"path\":\"A.java\"}");
        context.add(List.of(
                new AgentModel.Message("assistant", "", List.of(call), null, false),
                AgentModel.Message.observation("read1", "before", false)));
        var store = new AgentSessionStore(root.resolve("config"));
        var json = new ObjectMapper();
        var memory = json.createObjectNode();
        memory.putArray("files")
                .addObject()
                .put("path", "A.java")
                .put("hash", AgentSessionStore.hash("before".getBytes()));
        String id = UUID.randomUUID().toString();
        store.save(
                new AgentSessionStore.Entry(id, "fix A", repo.toRealPath().toString(), "OPENAI", "local", 1),
                new AgentContext.Saved(context.save().exchanges(), List.of(), 0, true),
                memory);
        assertEquals(1, store.list().size());
        Files.writeString(repo.resolve("A.java"), "user change");
        var loaded = store.load(id, new AgentWorkspace(repo));
        assertEquals(List.of("A.java"), loaded.changedFiles());
        assertTrue(loaded.context().needsVerification());
        var restored = new AgentContext();
        restored.restore(loaded.context());
        var request = restored.request("system", List.of(), 10000);
        assertTrue(request.messages().getLast().text().contains("Permissions have reset"));
        assertFalse(loaded.memory().has("permissions"));
        assertFalse(loaded.memory().has("leases"));
        assertThrows(IllegalArgumentException.class, () -> store.load(id, new AgentWorkspace(root)));
    }

    @Test
    void corruptAndFutureCheckpointsArePreservedAndSkipped() throws Exception {
        Path directory = Files.createDirectory(root.resolve("native-agent-sessions"));
        String id = UUID.randomUUID().toString();
        Path file = directory.resolve(id + ".json");
        Files.writeString(file, "{\"schemaVersion\":999}");
        var store = new AgentSessionStore(root);
        assertTrue(store.list().isEmpty());
        assertTrue(Files.exists(file));
        assertThrows(Exception.class, () -> store.load(id, new AgentWorkspace(root)));
        assertThrows(IllegalArgumentException.class, () -> store.load("../anything", new AgentWorkspace(root)));
    }
}
