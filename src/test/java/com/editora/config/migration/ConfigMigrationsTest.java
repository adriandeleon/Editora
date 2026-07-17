package com.editora.config.migration;

import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigMigrationsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void versionOfHandlesArrayPresentAndMissing() {
        assertEquals(
                0,
                ConfigMigrations.versionOf(JsonNodeFactory.instance.arrayNode(), 1),
                "a bare array is the legacy v0 form");
        ObjectNode withVersion = mapper.createObjectNode().put("schemaVersion", 5);
        assertEquals(5, ConfigMigrations.versionOf(withVersion, 1));
        ObjectNode noVersion = mapper.createObjectNode().put("x", 1);
        assertEquals(1, ConfigMigrations.versionOf(noVersion, 1), "no marker ⇒ assumed legacy baseline");
    }

    @Test
    void applyStepsRunsEachStepInOrder() {
        // v1 → v4 chain; each step appends its target version to a "trace" array.
        java.util.function.IntFunction<Migration> stepFor = v -> input -> {
            ObjectNode o = (ObjectNode) input;
            ArrayNode trace = o.has("trace") ? (ArrayNode) o.get("trace") : o.putArray("trace");
            trace.add(v + 1);
            return o;
        };
        ObjectNode start = mapper.createObjectNode();
        JsonNode out = ConfigMigrations.applySteps(start, 1, 4, stepFor);
        assertEquals("[2,3,4]", out.get("trace").toString().replace(" ", ""));
    }

    @Test
    void applyStepsThrowsOnMissingStep() {
        assertThrows(
                IllegalStateException.class,
                () -> ConfigMigrations.applySteps(mapper.createObjectNode(), 1, 2, v -> null));
    }

    @Test
    void upgradeStampsCurrentVersionAndWrapsRecentArray() {
        ArrayNode legacy = JsonNodeFactory.instance.arrayNode();
        legacy.add("/a.txt");
        legacy.add("/b.txt");
        ObjectNode migrated = ConfigMigrations.upgrade(ConfigSchema.RECENT, legacy, mapper);
        assertEquals(1, migrated.get("schemaVersion").asInt());
        assertTrue(migrated.has("files"));
        assertEquals(2, migrated.get("files").size());
        assertEquals("/a.txt", migrated.get("files").get(0).asText());
    }

    @Test
    void upgradeThrowsWhenFileIsNewerThanSupported() {
        ObjectNode tooNew = mapper.createObjectNode().put("schemaVersion", 99);
        NewerThanSupportedException ex = assertThrows(
                NewerThanSupportedException.class,
                () -> ConfigMigrations.upgrade(ConfigSchema.SETTINGS, tooNew, mapper));
        assertEquals(99, ex.storedVersion());
        assertEquals(ConfigSchema.SETTINGS, ex.schema());
    }

    @Test
    void readVersionedBacksUpAndDefaultsWhenFileTooNew(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("workspace-state.json");
        Files.writeString(f, "{\"schemaVersion\":99,\"zenMode\":true}");
        ObjectNode defaults = mapper.createObjectNode().put("zenMode", false);
        JsonNode result = ConfigMigrations.readVersioned(f, mapper, defaults, ConfigSchema.WORKSPACE);
        assertFalse(result.get("zenMode").asBoolean(), "too-new file ⇒ defaults used");
        assertFalse(Files.exists(f), "the too-new file is moved aside");
        assertTrue(Files.exists(dir.resolve("workspace-state.json.v99.bak")), "backed up");
    }

    @Test
    void backupDoesNotClobberAnExistingBak(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("settings.toml");
        Files.writeString(f, "new");
        Path bak = dir.resolve("settings.toml.v2.bak");
        Files.writeString(bak, "original-backup");
        ConfigMigrations.backup(f, 2);
        assertEquals("original-backup", Files.readString(bak), "first backup is preserved");
    }

    @Test
    void growTodoDefaultKeywordsAppendsMissingKeywordsPreservingCustomEntries() {
        ObjectNode settings = mapper.createObjectNode();
        ArrayNode patterns = settings.putArray("todoPatterns");
        patterns.add(mapper.createObjectNode().put("name", "TODO").put("pattern", "\\bTODO\\b"));
        patterns.add(mapper.createObjectNode().put("name", "MINE").put("pattern", "\\bMINE\\b")); // custom, kept

        ObjectNode out = (ObjectNode) ConfigMigrations.growTodoDefaultKeywords(settings);
        ArrayNode result = (ArrayNode) out.get("todoPatterns");
        java.util.List<String> names = new java.util.ArrayList<>();
        result.forEach(n -> names.add(n.get("name").asText()));

        assertTrue(names.contains("MINE"), "a custom keyword is untouched");
        assertEquals(1, names.stream().filter("TODO"::equals).count(), "an existing default isn't duplicated");
        for (String kw : new String[] {"FIXME", "HACK", "NOTE", "XXX", "DONE"}) {
            assertTrue(names.contains(kw), "missing default keyword " + kw + " is appended");
        }
    }

    @Test
    void growTodoDefaultKeywordsNoOpsWhenPatternsAbsent() {
        ObjectNode settings = mapper.createObjectNode().put("x", 1); // no todoPatterns ⇒ defaults() supplies them
        assertFalse(ConfigMigrations.growTodoDefaultKeywords(settings).has("todoPatterns"));
    }

    @Test
    void addDefaultAgentIdBackfillsMissingEntries() throws Exception {
        JsonNode input = mapper.readTree(
                "{\"schemaVersion\":1,\"sessions\":[{\"sessionId\":\"s1\",\"cwd\":\"/p\",\"label\":\"A\",\"updatedAt\":1}]}");
        JsonNode out = ConfigMigrations.addDefaultAgentIdToSessions(input);
        assertEquals("claude", out.get("sessions").get(0).get("agentId").asText());
    }

    @Test
    void addDefaultAgentIdLeavesExistingAgentIdUntouched() throws Exception {
        JsonNode input = mapper.readTree(
                "{\"sessions\":[" + "{\"sessionId\":\"s1\",\"agentId\":\"gemini\"}," + "{\"sessionId\":\"s2\"}]}");
        JsonNode out = ConfigMigrations.addDefaultAgentIdToSessions(input);
        assertEquals("gemini", out.get("sessions").get(0).get("agentId").asText());
        assertEquals("claude", out.get("sessions").get(1).get("agentId").asText());
    }

    @Test
    void addDefaultAgentIdNoOpsWhenSessionsAbsent() throws Exception {
        JsonNode input = mapper.readTree("{\"x\":1}");
        assertFalse(ConfigMigrations.addDefaultAgentIdToSessions(input).has("sessions"));
    }

    // --- v77→78: split aiApiKey onto per-provider fields (#480) --------------------------------------

    @Test
    void splitAiApiKeyMovesTheKeyOffAnOpenAiActiveConfig() throws Exception {
        JsonNode in = mapper.readTree("{\"aiProvider\":\"openai\",\"aiApiKey\":\"sk-openrouter\"}");
        ObjectNode out = (ObjectNode) ConfigMigrations.splitAiApiKeyByProvider(in);
        // The key was an OpenAI-endpoint key; keeping it in aiApiKey would resurrect it as an Anthropic key.
        assertEquals("sk-openrouter", out.get("aiApiKeyOpenai").asText());
        assertEquals("", out.get("aiApiKey").asText());
    }

    @Test
    void splitAiApiKeyLeavesAnAnthropicActiveConfigUntouched() throws Exception {
        // Anthropic is the default provider, so the common case already lands in the right field.
        JsonNode in = mapper.readTree("{\"aiProvider\":\"anthropic\",\"aiApiKey\":\"sk-ant-REAL\"}");
        ObjectNode out = (ObjectNode) ConfigMigrations.splitAiApiKeyByProvider(in);
        assertEquals("sk-ant-REAL", out.get("aiApiKey").asText());
        assertFalse(out.has("aiApiKeyOpenai"));

        // A blank/absent provider defaults to Anthropic too.
        JsonNode blank = mapper.readTree("{\"aiApiKey\":\"sk-ant-REAL\"}");
        ObjectNode blankOut = (ObjectNode) ConfigMigrations.splitAiApiKeyByProvider(blank);
        assertEquals("sk-ant-REAL", blankOut.get("aiApiKey").asText());
        assertFalse(blankOut.has("aiApiKeyOpenai"));
    }

    @Test
    void splitAiApiKeyNoOpsWithNoKeyOrAnAlreadySplitConfig() throws Exception {
        // No key to move.
        JsonNode empty = mapper.readTree("{\"aiProvider\":\"openai\",\"aiApiKey\":\"\"}");
        assertEquals(
                "",
                ((ObjectNode) ConfigMigrations.splitAiApiKeyByProvider(empty))
                        .path("aiApiKeyOpenai")
                        .asText());
        // Already-populated OpenAI key must not be clobbered by a stale aiApiKey.
        JsonNode already =
                mapper.readTree("{\"aiProvider\":\"openai\",\"aiApiKey\":\"stale\",\"aiApiKeyOpenai\":\"sk-real\"}");
        ObjectNode out = (ObjectNode) ConfigMigrations.splitAiApiKeyByProvider(already);
        assertEquals("sk-real", out.get("aiApiKeyOpenai").asText());
        assertEquals("stale", out.get("aiApiKey").asText());
    }
}
