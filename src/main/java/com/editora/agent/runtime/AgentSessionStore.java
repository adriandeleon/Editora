package com.editora.agent.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Local versioned conversation checkpoints. Historical text never restores security or edit authority. */
public final class AgentSessionStore {
    public record Entry(String id, String goal, String workspace, String provider, String model, long updated) {}

    public record Restored(Entry entry, AgentContext.Saved context, JsonNode memory, List<String> changedFiles) {}

    private static final int SCHEMA = 1;
    private final Path directory;
    private final ObjectMapper json = new ObjectMapper();

    public AgentSessionStore(Path configDirectory) {
        directory = configDirectory.resolve("native-agent-sessions");
    }

    public void save(Entry entry, AgentContext.Saved context, JsonNode memory) throws Exception {
        var data = json.createObjectNode()
                .put("schemaVersion", SCHEMA)
                .put("id", entry.id())
                .put("goal", AgentContext.bounded(entry.goal(), 1000))
                .put("workspace", entry.workspace())
                .put("provider", entry.provider())
                .put("model", entry.model())
                .put("updated", System.currentTimeMillis());
        data.set("memory", memory.deepCopy());
        data.put("compacted", context.compacted());
        data.put("needsVerification", context.needsVerification());
        var summaries = data.putArray("summaries");
        context.memory().forEach(summaries::add);
        var exchanges = data.putArray("exchanges");
        for (var exchange : context.exchanges()) {
            var items = exchanges.addArray();
            for (var m : exchange) {
                var item = items.addObject()
                        .put("role", m.role())
                        .put("text", m.text())
                        .put("callId", m.callId())
                        .put("error", m.error());
                var calls = item.putArray("calls");
                for (var c : m.calls())
                    calls.addObject().put("id", c.id()).put("name", c.name()).put("arguments", c.arguments());
            }
        }
        if (data.toString().length() > 2_000_000)
            throw new IllegalStateException("Session checkpoint exceeds storage limit");
        Files.createDirectories(directory);
        if (Files.isSymbolicLink(directory) || Files.isSymbolicLink(file(entry.id())))
            throw new IllegalArgumentException("Invalid checkpoint location");
        com.editora.config.ConfigWriter.writeAtomic(file(entry.id()), json, data);
    }

    public List<Entry> list() throws Exception {
        if (!Files.isDirectory(directory) || Files.isSymbolicLink(directory)) return List.of();
        List<Entry> entries = new ArrayList<>();
        try (var files = Files.list(directory)) {
            for (Path path : files.limit(200).toList()) {
                if (!path.getFileName().toString().matches("[a-f0-9-]{36}\\.json")) continue;
                try {
                    entries.add(entry(read(path)));
                } catch (Exception invalid) {
                    /* Ignore corrupt or future checkpoints, preserving their files. */
                }
            }
        }
        return entries.stream()
                .sorted(java.util.Comparator.comparingLong(Entry::updated).reversed())
                .limit(30)
                .toList();
    }

    public Restored load(String id, AgentWorkspace workspace) throws Exception {
        var data = read(file(id));
        var entry = entry(data);
        if (!workspace.root().toString().equals(entry.workspace()))
            throw new IllegalArgumentException("Checkpoint belongs to a different workspace");
        List<List<AgentModel.Message>> exchanges = new ArrayList<>();
        var ids = new java.util.HashSet<String>();
        if (!data.path("exchanges").isArray() || data.path("exchanges").size() > 2000)
            throw new IllegalArgumentException("Invalid checkpoint exchanges");
        for (var exchange : data.path("exchanges")) {
            List<AgentModel.Message> items = new ArrayList<>();
            for (var m : exchange) {
                String role = m.path("role").asText();
                if (!List.of("user", "assistant", "tool", "observation").contains(role))
                    throw new IllegalArgumentException("Invalid checkpoint role");
                List<AgentModel.Call> calls = new ArrayList<>();
                for (var c : m.path("calls")) {
                    String callId = c.path("id").asText();
                    if (callId.isBlank() || !ids.add(callId))
                        throw new IllegalArgumentException("Invalid checkpoint call identity");
                    calls.add(new AgentModel.Call(
                            callId, c.path("name").asText(), c.path("arguments").asText()));
                }
                if (!calls.isEmpty() && !role.equals("assistant"))
                    throw new IllegalArgumentException("Invalid checkpoint calls");
                items.add(new AgentModel.Message(
                        role,
                        m.path("text").asText(),
                        calls,
                        m.path("callId").isNull() ? null : m.path("callId").asText(),
                        m.path("error").asBoolean()));
            }
            exchanges.add(List.copyOf(items));
        }
        List<String> summaries = new ArrayList<>();
        data.path("summaries").forEach(s -> summaries.add(AgentContext.bounded(s.asText(), 320)));
        var context = new AgentContext.Saved(
                List.copyOf(exchanges),
                List.copyOf(summaries),
                data.path("compacted").asInt(),
                data.path("needsVerification").asBoolean());
        new AgentContext().restore(context); // Validate complete protocol groups before exposing any memory.
        List<String> changed = new ArrayList<>();
        JsonNode memory = data.path("memory");
        for (var f : memory.path("files")) {
            String relative = f.path("path").asText();
            try {
                Path path = workspace.resolve(relative);
                if (!Files.isRegularFile(path)
                        || Files.size(path) > AgentWorkspace.MAX_FILE_CHARS
                        || !hash(Files.readAllBytes(path)).equals(f.path("hash").asText())) changed.add(relative);
            } catch (Exception unavailable) {
                changed.add(relative);
            }
        }
        return new Restored(entry, context, memory.deepCopy(), List.copyOf(changed));
    }

    private Path file(String id) {
        if (id == null || !id.matches("[a-f0-9-]{36}")) throw new IllegalArgumentException("Invalid native session id");
        return directory.resolve(id + ".json");
    }

    private JsonNode read(Path file) throws Exception {
        if (Files.isSymbolicLink(directory) || Files.isSymbolicLink(file) || Files.size(file) > 4_000_000)
            throw new IllegalArgumentException("Invalid checkpoint file");
        var data = json.readTree(Files.readString(file));
        if (data == null || data.path("schemaVersion").asInt() != SCHEMA)
            throw new IllegalArgumentException("Unsupported checkpoint version");
        return data;
    }

    private Entry entry(JsonNode data) {
        return new Entry(
                data.path("id").asText(),
                data.path("goal").asText(),
                data.path("workspace").asText(),
                data.path("provider").asText(),
                data.path("model").asText(),
                data.path("updated").asLong());
    }

    public static String hash(byte[] data) throws Exception {
        return java.util.HexFormat.of()
                .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(data));
    }
}
