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

    /**
     * v151→152: the path a pre-pin XML install wrote into the command is blanked (blank runs that same copy), so it
     * stops counting as a modified setting; a command of the user's own is kept (XML/pom review 2, A7).
     */
    @Test
    void aPrePinXmlCommandIsBlankedAndAnyOtherKept() throws Exception {
        for (String stale : java.util.List.of(
                "/home/u/.editora/plugins/lsp/xml/lemminx-linux-x86_64",
                "\"/home/u/my files/.editora/plugins/lsp/xml/lemminx-osx-aarch_64\"",
                "'/home/u/.editora/plugins/lsp/xml/lemminx-linux-x86_64'",
                "/home/u/my files/.editora/plugins/lsp/xml/lemminx-linux-x86_64",
                "C:\\Users\\u\\.editora\\plugins\\lsp\\xml\\lemminx-win32.exe")) {
            ObjectNode in = mapper.createObjectNode().put("schemaVersion", 150).put("xmlLspCommand", stale);
            ObjectNode out = ConfigMigrations.upgrade(ConfigSchema.SETTINGS, in, mapper);
            assertEquals("", out.get("xmlLspCommand").asText(), stale);
        }
        for (String own : java.util.List.of(
                "/opt/lemminx/lemminx-linux-x86_64 --log",
                "/usr/local/bin/lemminx",
                "java -cp \"/x/*\" Main",
                "",
                // the user's own argument after Editora's pre-pin copy is theirs to keep (XML/pom review 3, A25)
                "/home/u/.editora/plugins/lsp/xml/lemminx-linux-x86_64 -Xmx512m",
                "\"/home/u/my files/.editora/plugins/lsp/xml/lemminx-linux-x86_64\" -Xmx512m",
                "/home/u/.editora/plugins/lsp/xml/lemminx-linux-x86_64 \"-Dx=a b\"")) {
            ObjectNode in = mapper.createObjectNode().put("schemaVersion", 150).put("xmlLspCommand", own);
            ObjectNode out = ConfigMigrations.upgrade(ConfigSchema.SETTINGS, in, mapper);
            assertEquals(own, out.get("xmlLspCommand").asText(), own);
        }
    }

    @Test
    void codexProviderUpgradePreservesExistingAiChoices() throws Exception {
        ObjectNode previous = mapper.createObjectNode()
                .put("schemaVersion", 103)
                .put("aiProvider", "openai")
                .put("aiModel", "local-model")
                .put("aiEndpoint", "http://localhost:1234/v1/chat/completions")
                .put("aiApiKeyOpenai", "local-key")
                .put("codexAgentCommand", "/custom/codex-acp");
        ObjectNode expected = previous.deepCopy().put("schemaVersion", ConfigSchema.SETTINGS.currentVersion());
        expected.put("pdfPageSize", "letter"); // v115→116 writes down the size a file without the key was using
        // v126→127 gives the OpenAI-compatible provider its own endpoint and model.
        expected.put("aiModel", "").put("aiModelOpenai", "local-model");
        expected.put("aiEndpoint", "").put("aiEndpointOpenai", "http://localhost:1234/v1/chat/completions");
        expected.put("aiModelCodex", ""); // v133→134 copies aiModel, which v126→127 had moved to the OpenAI field
        assertEquals(expected, ConfigMigrations.upgrade(ConfigSchema.SETTINGS, previous, mapper));
    }

    @Test
    void openaiEndpointAndModelsMoveToTheirOwnFields() throws Exception {
        ObjectNode openai = (ObjectNode) mapper.readTree("""
                {"aiProvider":"openai","aiEndpoint":"https://openrouter.example/v1/chat/completions",
                 "aiModel":"m","aiCompletionModel":"small","aiApiKey":"sk-ant-x","aiApiKeyOpenai":"or-key"}""");
        ObjectNode out = (ObjectNode) ConfigMigrations.splitOpenaiEndpoint(openai);
        assertEquals(
                "https://openrouter.example/v1/chat/completions",
                out.get("aiEndpointOpenai").asText());
        assertEquals("m", out.get("aiModelOpenai").asText());
        assertEquals("small", out.get("aiCompletionModelOpenai").asText());
        assertEquals("", out.get("aiEndpoint").asText(), "the Anthropic key no longer goes to that server");
        assertEquals("", out.get("aiModel").asText());
        assertEquals(out.deepCopy(), ConfigMigrations.splitOpenaiEndpoint(out), "safe to repeat");
    }

    @Test
    void aScrollbackAboveTheNewMaximumIsClampedAndASmallerOneKept() throws Exception {
        ObjectNode big = (ObjectNode) mapper.readTree("{\"terminalScrollback\":100000}");
        assertEquals(
                50_000,
                ConfigMigrations.clampTerminalScrollback(big)
                        .get("terminalScrollback")
                        .asInt());
        ObjectNode small = (ObjectNode) mapper.readTree("{\"terminalScrollback\":8000}");
        assertEquals(
                8000,
                ConfigMigrations.clampTerminalScrollback(small)
                        .get("terminalScrollback")
                        .asInt());
        ObjectNode none = (ObjectNode) mapper.readTree("{}");
        assertFalse(ConfigMigrations.clampTerminalScrollback(none).has("terminalScrollback"));
    }

    @Test
    void anOpenaiShapedLeftoverEndpointLeavesAnthropicButAProxyStays() throws Exception {
        ObjectNode leftover = (ObjectNode) mapper.readTree("""
                {"aiProvider":"anthropic","aiEndpoint":"http://192.168.1.5:8080/v1/chat/completions/",
                 "aiModel":"claude-x"}""");
        ObjectNode out = (ObjectNode) ConfigMigrations.splitOpenaiEndpoint(leftover);
        assertEquals("", out.get("aiEndpoint").asText());
        assertEquals(
                "http://192.168.1.5:8080/v1/chat/completions/",
                out.get("aiEndpointOpenai").asText());
        assertEquals("claude-x", out.get("aiModel").asText(), "an Anthropic model stays Anthropic's");
        assertFalse(out.has("aiModelOpenai"));

        ObjectNode proxy = (ObjectNode) mapper.readTree("""
                {"aiEndpoint":"https://proxy.example/v1/messages","aiModel":"claude-x"}""");
        assertEquals(proxy.deepCopy(), ConfigMigrations.splitOpenaiEndpoint(proxy));
    }

    /** v133→134: Codex gets its own model field, starting from the value it shared with Anthropic. */
    @Test
    void codexKeepsTheModelItSharedWithAnthropic() throws Exception {
        ObjectNode shared = (ObjectNode) mapper.readTree("{\"aiProvider\":\"codex\",\"aiModel\":\"gpt-5-codex\"}");
        ObjectNode out = (ObjectNode) ConfigMigrations.splitCodexModel(shared);
        assertEquals("gpt-5-codex", out.get("aiModelCodex").asText());
        assertEquals("gpt-5-codex", out.get("aiModel").asText(), "Anthropic keeps its own copy");
        out.put("aiModelCodex", "later");
        assertEquals(
                "later",
                ConfigMigrations.splitCodexModel(out).get("aiModelCodex").asText(),
                "safe to repeat");
        com.editora.config.Settings s = mapper.treeToValue(out, com.editora.config.Settings.class);
        s.setAiModelFor(com.editora.ai.AiProvider.ANTHROPIC, "claude-x");
        assertEquals("later", s.getAiModelFor(com.editora.ai.AiProvider.CODEX), "no sharing any more");
    }

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
        // Derived from the current version rather than written as a literal: a hardcoded "newer" version
        // stops being newer the moment someone bumps the schema, and the test then passes by not throwing.
        int tooNewVersion = ConfigSchema.SETTINGS.currentVersion() + 1;
        ObjectNode tooNew = mapper.createObjectNode().put("schemaVersion", tooNewVersion);
        NewerThanSupportedException ex = assertThrows(
                NewerThanSupportedException.class,
                () -> ConfigMigrations.upgrade(ConfigSchema.SETTINGS, tooNew, mapper));
        assertEquals(tooNewVersion, ex.storedVersion());
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
        Path f = dir.resolve("settings.json");
        Files.writeString(f, "new");
        Path bak = dir.resolve("settings.json.v2.bak");
        Files.writeString(bak, "original-backup");
        ConfigMigrations.backup(f, 2);
        assertEquals("original-backup", Files.readString(bak), "first backup is preserved");
    }

    @Test
    void seedOpenToolWindowsCarriesTheSingleIdFieldsIntoThePerSideLists() {
        ObjectNode v7 = mapper.createObjectNode();
        v7.put("openLeftToolWindow", "project");
        v7.put("openRightToolWindow", "");
        v7.put("openBottomToolWindow", "problems");

        JsonNode out = ConfigMigrations.seedOpenToolWindows(v7);

        JsonNode open = out.get("openToolWindows");
        assertEquals(1, open.get("LEFT").size());
        assertEquals("project", open.get("LEFT").get(0).asText());
        assertEquals("problems", open.get("BOTTOM").get(0).asText());
        assertFalse(open.has("RIGHT"), "a side with nothing open needs no entry");
    }

    @Test
    void seedOpenToolWindowsLeavesAnAlreadySeededFileAlone() {
        // Re-running the step must not overwrite a real v8 layout with the stale single-id fields.
        ObjectNode v8 = mapper.createObjectNode();
        v8.put("openLeftToolWindow", "project");
        v8.putObject("openToolWindows").putArray("LEFT").add("structure");

        JsonNode out = ConfigMigrations.seedOpenToolWindows(v8);

        assertEquals("structure", out.get("openToolWindows").get("LEFT").get(0).asText());
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
    void splitKeybindingsMovesTheMapIntoTheMacSlotAndClearsTheBaseSlot() throws Exception {
        // A config edited on macOS: the overrides are Cmd-based and the UNBIND suppressor ("") keys to Cmd-S.
        JsonNode in = mapper.readTree("{\"keybindings\":{\"Cmd-S\":\"\",\"Cmd-K\":\"file.save\"},\"fontSize\":14}");
        ObjectNode out = ConfigMigrations.splitKeybindings((ObjectNode) in, true);
        // Moved into the mac slot verbatim…
        assertEquals("", out.get("keybindingsMac").get("Cmd-S").asText());
        assertEquals("file.save", out.get("keybindingsMac").get("Cmd-K").asText());
        // …and the base (Windows/Linux) slot is emptied, so a later non-mac load of this synced file sees no
        // stale Cmd-chord UNBINDs and keeps its own Ctrl defaults — no double-bind (#439).
        assertTrue(out.get("keybindings").isObject());
        assertEquals(0, out.get("keybindings").size());
        assertEquals(14, out.get("fontSize").asInt(), "unrelated fields untouched");
    }

    @Test
    void splitKeybindingsIsIdentityOnNonMac() throws Exception {
        // On Windows/Linux the existing map already IS the Ctrl slot; keybindingsMac defaults empty on load.
        JsonNode in = mapper.readTree("{\"keybindings\":{\"C-S\":\"\",\"C-K\":\"file.save\"}}");
        ObjectNode out = ConfigMigrations.splitKeybindings((ObjectNode) in, false);
        assertEquals("file.save", out.get("keybindings").get("C-K").asText());
        assertFalse(out.has("keybindingsMac"));
    }

    @Test
    void splitKeybindingsOnMacWithNoExistingMapYieldsTwoEmptyMaps() throws Exception {
        ObjectNode out = ConfigMigrations.splitKeybindings((ObjectNode) mapper.readTree("{}"), true);
        assertTrue(out.get("keybindingsMac").isObject());
        assertEquals(0, out.get("keybindingsMac").size());
        assertEquals(0, out.get("keybindings").size());
    }

    /**
     * The whole point of the step: the default change alone reaches nobody who has ever run Editora, because
     * Jackson stores every modelled field and theirs says false.
     */
    @Test
    void enableProjectSupportTurnsItOnForAnExistingInstall() throws Exception {
        JsonNode in = mapper.readTree("{\"projectSupport\":false,\"tabSize\":4}");
        ObjectNode out = (ObjectNode) ConfigMigrations.enableProjectSupport(in);
        assertTrue(out.get("projectSupport").asBoolean());
        assertEquals(4, out.get("tabSize").asInt(), "unrelated settings untouched");
    }

    @Test
    void enableProjectSupportLeavesAnAlreadyEnabledConfigAlone() throws Exception {
        JsonNode in = mapper.readTree("{\"projectSupport\":true}");
        ObjectNode out = (ObjectNode) ConfigMigrations.enableProjectSupport(in);
        assertTrue(out.get("projectSupport").asBoolean());
    }

    /**
     * The step is registered and actually runs — a migration nobody wired into {@code ConfigSchema} would
     * pass its own unit test and change nothing on disk.
     */
    @Test
    void upgradingASettingsFileFromV88TurnsProjectsOn() throws Exception {
        JsonNode stored = mapper.readTree("{\"schemaVersion\":88,\"projectSupport\":false,\"tabSize\":4}");
        ObjectNode out = ConfigMigrations.upgrade(ConfigSchema.SETTINGS, stored, mapper);
        assertTrue(out.get("projectSupport").asBoolean(), "existing installs get Projects");
        assertEquals(
                com.editora.config.Settings.SCHEMA_VERSION,
                out.get("schemaVersion").asInt(),
                "and are stamped to the current schema");
        assertEquals(4, out.get("tabSize").asInt());
    }

    /** Absent means the field never existed in that file; the POJO default (now true) supplies it. */
    @Test
    void enableProjectSupportDoesNotInventTheFieldWhenItIsAbsent() throws Exception {
        JsonNode in = mapper.readTree("{\"tabSize\":2}");
        ObjectNode out = (ObjectNode) ConfigMigrations.enableProjectSupport(in);
        assertFalse(out.has("projectSupport"));
    }

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

    // ---- v100→101: Recent moved from the toolbar's fixed tail into the customizable cluster -----------

    private java.util.List<String> layoutAfterMigration(String json) throws Exception {
        ObjectNode out = (ObjectNode) ConfigMigrations.restoreRecentToToolbarLayout(mapper.readTree(json));
        java.util.List<String> l = new java.util.ArrayList<>();
        out.path("toolbarLayout").forEach(n -> l.add(n.asText()));
        return l;
    }

    @Test
    void restoreRecentInsertsItAfterSaveAsInACustomizedLayout() throws Exception {
        assertEquals(
                java.util.List.of("file.new", "file.save", "file.saveAs", "toolbar.recent", "|", "edit.undo"),
                layoutAfterMigration(
                        "{\"toolbarLayout\":[\"file.new\",\"file.save\",\"file.saveAs\",\"|\",\"edit.undo\"]}"),
                "a saved layout is used verbatim, so Recent has to be put back where the default puts it");
    }

    @Test
    void restoreRecentAppendsWhenTheUserRemovedSaveAs() throws Exception {
        assertEquals(
                java.util.List.of("file.new", "|", "edit.undo", "toolbar.recent"),
                layoutAfterMigration("{\"toolbarLayout\":[\"file.new\",\"|\",\"edit.undo\"]}"),
                "no anchor to insert after, but the button must still be reachable");
    }

    @Test
    void restoreRecentLeavesAnUncustomizedOrAlreadyCorrectLayoutAlone() throws Exception {
        // Empty means "use the shipped default", which already contains Recent — inserting would pin the
        // whole default into the user's settings and freeze it against future changes.
        assertTrue(layoutAfterMigration("{\"toolbarLayout\":[]}").isEmpty());
        // Idempotent, and it never MOVES a Recent the user has already placed somewhere of their own.
        assertEquals(
                java.util.List.of("toolbar.recent", "file.saveAs"),
                layoutAfterMigration("{\"toolbarLayout\":[\"toolbar.recent\",\"file.saveAs\"]}"));
    }

    @Test
    void restoreRecentToleratesAMissingOrMalformedLayout() throws Exception {
        assertFalse(((ObjectNode) ConfigMigrations.restoreRecentToToolbarLayout(mapper.readTree("{\"x\":1}")))
                .has("toolbarLayout"));
        // A non-array under the key must be passed through, not crash the whole settings read.
        assertEquals(
                "nope",
                ((ObjectNode) ConfigMigrations.restoreRecentToToolbarLayout(
                                mapper.readTree("{\"toolbarLayout\":\"nope\"}")))
                        .get("toolbarLayout")
                        .asText());
    }
    // --- a settings file without a schemaVersion marker ------------------------------------------------

    /**
     * A current-shape file whose marker was deleted by hand used to be treated as v1 and have every
     * migration replayed over it, which undid the user's own choices.
     */
    @Test
    void aCurrentShapeFileWithoutAMarkerIsNotMigratedFromTheBaseline() throws Exception {
        ObjectNode current = mapper.valueToTree(new com.editora.config.Settings());
        current.remove("schemaVersion");
        current.put("projectSupport", false); // Projects deliberately turned off
        current.putArray("toolbarLayout").add("file.new").add("file.saveAs"); // Recent deliberately removed
        current.putArray("todoPatterns"); // every TODO keyword deliberately removed
        current.putObject("keybindings").put("C-k", "file.save");
        current.putObject("keybindingsMac").put("Cmd-k", "file.save");
        ObjectNode expected = current.deepCopy();
        expected.put("schemaVersion", ConfigSchema.SETTINGS.currentVersion());

        assertEquals(127, ConfigSchema.SETTINGS.versionWithoutMarker(current), "its newest key dates it");
        ObjectNode out = ConfigMigrations.upgrade(ConfigSchema.SETTINGS, current.deepCopy(), mapper);

        assertEquals(expected, out, "nothing but the marker changes");
    }

    /**
     * v112→113: the per-file-type spell switch is new. The master switch stays exactly as the user left it,
     * and a file that never had the list gets the default one (data and configuration formats off).
     */
    @Test
    void theSpellFileTypeListArrivesWithoutTouchingTheMasterSwitch() throws Exception {
        for (boolean master : new boolean[] {true, false}) {
            JsonNode v112 = mapper.readTree(
                    "{\"schemaVersion\":112,\"spellCheck\":" + master + ",\"spellLanguage\":\"es_MX\"}");
            ObjectNode out = ConfigMigrations.upgrade(ConfigSchema.SETTINGS, v112.deepCopy(), mapper);
            assertEquals(
                    ConfigSchema.SETTINGS.currentVersion(),
                    out.get("schemaVersion").asInt());
            assertEquals(master, out.get("spellCheck").asBoolean(), "the global on/off choice is kept");
            assertEquals("es_MX", out.get("spellLanguage").asText());
            assertFalse(out.has("spellDisabledLanguages"), "left to the default");
            com.editora.config.Settings loaded = mapper.treeToValue(out, com.editora.config.Settings.class);
            assertEquals(master, loaded.isSpellCheck());
            assertEquals(
                    com.editora.config.Settings.DEFAULT_SPELL_DISABLED_LANGUAGES, loaded.getSpellDisabledLanguages());
            assertTrue(loaded.getSpellDisabledLanguages().contains("json"));
            assertFalse(loaded.getSpellDisabledLanguages().contains("markdown"));
        }
        // A list the user has edited (JSON turned back on, Java turned off) round-trips as written.
        JsonNode chosen = mapper.readTree("{\"schemaVersion\":113,\"spellDisabledLanguages\":[\"yaml\",\"java\"]}");
        com.editora.config.Settings kept = mapper.treeToValue(
                ConfigMigrations.upgrade(ConfigSchema.SETTINGS, chosen.deepCopy(), mapper),
                com.editora.config.Settings.class);
        assertEquals(java.util.List.of("yaml", "java"), kept.getSpellDisabledLanguages());
    }

    /** v127→128: httpInlineResults is new and on — results show after the request lines; nothing else moves. */
    @Test
    void httpInlineResultsArriveOnWithoutTouchingAnythingElse() throws Exception {
        JsonNode v127 = mapper.readTree(
                "{\"schemaVersion\":127,\"httpClientSupport\":false,\"httpScriptsEnabled\":false,\"fontSize\":15}");

        ObjectNode out = ConfigMigrations.upgrade(ConfigSchema.SETTINGS, v127.deepCopy(), mapper);

        assertEquals(
                com.editora.config.Settings.SCHEMA_VERSION,
                out.get("schemaVersion").asInt());
        assertFalse(out.has("httpInlineResults"), "left to the default");
        com.editora.config.Settings loaded = mapper.treeToValue(out, com.editora.config.Settings.class);
        assertTrue(loaded.isHttpInlineResults(), "on for everyone, as nobody had a choice yet");
        assertFalse(loaded.isHttpClientSupport(), "the other HTTP choices are kept");
        assertFalse(loaded.isHttpScriptsEnabled());
        assertEquals(15, loaded.getFontSize());
        JsonNode chosen = mapper.readTree("{\"schemaVersion\":128,\"httpInlineResults\":false}");
        assertFalse(mapper.treeToValue(
                        ConfigMigrations.upgrade(ConfigSchema.SETTINGS, chosen.deepCopy(), mapper),
                        com.editora.config.Settings.class)
                .isHttpInlineResults());
    }

    /** v119→120: remoteExecution is new — nobody's editor starts running commands on a server on upgrade. */
    @Test
    void remoteExecutionArrivesWithoutTouchingAnythingElse() throws Exception {
        JsonNode v119 = mapper.readTree("{\"schemaVersion\":119,\"fontSize\":15}");

        ObjectNode out = ConfigMigrations.upgrade(ConfigSchema.SETTINGS, v119.deepCopy(), mapper);

        assertEquals(15, out.get("fontSize").asInt());
        assertFalse(out.has("remoteExecution"), "left to the default");
        // On since v122 (see remoteExecutionIsTurnedOnOnce…); each host is still asked first.
        assertTrue(mapper.treeToValue(out, com.editora.config.Settings.class).isRemoteExecution());
        JsonNode chosen = mapper.readTree("{\"schemaVersion\":120,\"remoteExecution\":true}");
        assertTrue(mapper.treeToValue(chosen, com.editora.config.Settings.class).isRemoteExecution());
    }

    /** v113→114: snippetTabExpansion is new — Tab expanded triggers for everyone before, and still does. */
    @Test
    void theSnippetTabExpansionSettingArrivesOnWithoutTouchingAnythingElse() throws Exception {
        JsonNode v112 = mapper.readTree("{\"schemaVersion\":112,\"autocompleteSnippets\":false}");
        ObjectNode out = ConfigMigrations.upgrade(ConfigSchema.SETTINGS, v112.deepCopy(), mapper);
        assertEquals(
                com.editora.config.Settings.SCHEMA_VERSION,
                out.get("schemaVersion").asInt());
        assertFalse(out.get("autocompleteSnippets").asBoolean(), "the popup switch is a different setting");
        assertFalse(out.has("snippetTabExpansion"), "left to the default");
        com.editora.config.Settings loaded = mapper.treeToValue(out, com.editora.config.Settings.class);
        assertTrue(loaded.isSnippetTabExpansion(), "on for everyone who never chose");

        JsonNode chosen = mapper.readTree("{\"schemaVersion\":114,\"snippetTabExpansion\":false}");
        assertFalse(
                mapper.treeToValue(chosen, com.editora.config.Settings.class).isSnippetTabExpansion());
    }

    /** v111→112: crashRecovery is new — nothing else in the file changes, and it starts on. */
    @Test
    void theCrashRecoverySettingArrivesOnWithoutTouchingAnythingElse() throws Exception {
        JsonNode v111 = mapper.readTree("{\"schemaVersion\":111,\"localHistory\":false,\"autoSave\":\"afterDelay\"}");
        ObjectNode out = ConfigMigrations.upgrade(ConfigSchema.SETTINGS, v111.deepCopy(), mapper);
        assertEquals(
                ConfigSchema.SETTINGS.currentVersion(), out.get("schemaVersion").asInt());
        assertFalse(out.get("localHistory").asBoolean());
        assertEquals("afterDelay", out.get("autoSave").asText());
        assertFalse(out.has("crashRecovery"), "left to the default");
        com.editora.config.Settings loaded = mapper.treeToValue(out, com.editora.config.Settings.class);
        assertTrue(loaded.isCrashRecovery(), "on for everyone who never chose");

        JsonNode chosen = mapper.readTree("{\"schemaVersion\":112,\"crashRecovery\":false}");
        assertFalse(
                mapper.treeToValue(chosen, com.editora.config.Settings.class).isCrashRecovery());
    }

    /** v110→111: the automatic fetch is new — and off: nobody's editor starts talking to a remote on upgrade. */
    @Test
    void theAutomaticFetchArrivesSwitchedOffForExistingUsers() throws Exception {
        JsonNode v110 = mapper.readTree("{\"schemaVersion\":110,\"gitSupport\":true,\"gitPullMode\":\"rebase\"}");
        ObjectNode out = ConfigMigrations.upgrade(ConfigSchema.SETTINGS, v110.deepCopy(), mapper);
        assertEquals(
                com.editora.config.Settings.SCHEMA_VERSION,
                out.get("schemaVersion").asInt(),
                "stamped current");
        assertEquals("rebase", out.get("gitPullMode").asText(), "nothing else changes");
        assertFalse(out.has("gitAutoFetch"), "left to the default");
        com.editora.config.Settings loaded = mapper.treeToValue(out, com.editora.config.Settings.class);
        assertFalse(loaded.isGitAutoFetch(), "off unless the user switches it on");
        assertEquals(10, loaded.getGitAutoFetchMinutes());

        JsonNode chosen = mapper.readTree("{\"schemaVersion\":111,\"gitAutoFetch\":true,\"gitAutoFetchMinutes\":3}");
        com.editora.config.Settings kept = mapper.treeToValue(chosen, com.editora.config.Settings.class);
        assertTrue(kept.isGitAutoFetch());
        assertEquals(3, kept.getGitAutoFetchMinutes());
        // A hand-edited zero or a huge value is clamped rather than fetching in a tight loop.
        kept.setGitAutoFetchMinutes(0);
        assertEquals(1, kept.getGitAutoFetchMinutes());
        kept.setGitAutoFetchMinutes(1_000_000);
        assertEquals(com.editora.config.Settings.MAX_GIT_AUTO_FETCH_MINUTES, kept.getGitAutoFetchMinutes());
    }

    /** v109→110: gitPullMode is new — an existing user's pull stays fast-forward only. */
    @Test
    void thePullModeSettingArrivesAsFastForwardOnlyForExistingUsers() throws Exception {
        JsonNode v109 = mapper.readTree("{\"schemaVersion\":109,\"gitSupport\":true,\"gitPath\":\"/opt/git\"}");
        ObjectNode out = ConfigMigrations.upgrade(ConfigSchema.SETTINGS, v109.deepCopy(), mapper);
        assertEquals(
                com.editora.config.Settings.SCHEMA_VERSION,
                out.get("schemaVersion").asInt(),
                "stamped current");
        assertEquals("/opt/git", out.get("gitPath").asText(), "nothing else changes");
        assertFalse(out.has("gitPullMode"), "left to the default");
        com.editora.config.Settings loaded = mapper.treeToValue(out, com.editora.config.Settings.class);
        assertEquals("ff-only", loaded.getGitPullMode(), "what pull did before the setting existed");
        assertEquals(com.editora.git.GitPullMode.FF_ONLY, com.editora.git.GitPullMode.of(loaded.getGitPullMode()));

        JsonNode chosen = mapper.readTree("{\"schemaVersion\":110,\"gitPullMode\":\"rebase\"}");
        assertEquals(
                "rebase",
                mapper.treeToValue(chosen, com.editora.config.Settings.class).getGitPullMode());
        // A hand-edited blank or null does not leave the field unusable.
        com.editora.config.Settings blank = new com.editora.config.Settings();
        blank.setGitPullMode(" ");
        assertEquals("ff-only", blank.getGitPullMode());
        blank.setGitPullMode(null);
        assertEquals("ff-only", blank.getGitPullMode());
    }

    /** v108→109: codeLens is new and off — nothing else in the file changes. */
    @Test
    void theCodeLensSettingArrivesOffWithoutTouchingAnythingElse() throws Exception {
        JsonNode v108 = mapper.readTree("{\"schemaVersion\":108,\"inlayHints\":true,\"lspEnabled\":true}");
        ObjectNode out = ConfigMigrations.upgrade(ConfigSchema.SETTINGS, v108.deepCopy(), mapper);
        assertEquals(
                com.editora.config.Settings.SCHEMA_VERSION,
                out.get("schemaVersion").asInt());
        assertTrue(out.get("inlayHints").asBoolean());
        assertFalse(out.has("codeLens"), "left to the default");
        assertFalse(mapper.treeToValue(out, com.editora.config.Settings.class).isCodeLens());

        JsonNode chosen = mapper.readTree("{\"schemaVersion\":109,\"codeLens\":true}");
        assertTrue(mapper.treeToValue(chosen, com.editora.config.Settings.class).isCodeLens());
    }

    /** v107→108: debugProgramConsole is new — nothing else in the file changes, and it starts at its default. */
    @Test
    void theProgramConsoleSettingArrivesWithoutTouchingAnythingElse() throws Exception {
        JsonNode v107 = mapper.readTree("{\"schemaVersion\":107,\"debugSupport\":true,\"javaDebugPluginPath\":\"/x\"}");
        ObjectNode out = ConfigMigrations.upgrade(ConfigSchema.SETTINGS, v107.deepCopy(), mapper);
        assertEquals(
                com.editora.config.Settings.SCHEMA_VERSION,
                out.get("schemaVersion").asInt(),
                "stamped current");
        assertTrue(out.get("debugSupport").asBoolean());
        assertEquals("/x", out.get("javaDebugPluginPath").asText());
        assertFalse(out.has("debugProgramConsole"), "left to the default");
        com.editora.config.Settings loaded = mapper.treeToValue(out, com.editora.config.Settings.class);
        assertTrue(loaded.isDebugProgramConsole(), "on for everyone who never chose");

        JsonNode chosen = mapper.readTree("{\"schemaVersion\":108,\"debugProgramConsole\":false}");
        assertFalse(
                mapper.treeToValue(chosen, com.editora.config.Settings.class).isDebugProgramConsole());
    }

    /** v106→107: a built-in URL frozen into the file goes back to blank; a URL the user chose is kept. */
    @Test
    void frozenDefaultUrlsAreBlankedButChosenOnesAreKept() throws Exception {
        JsonNode frozen = mapper.readTree("{\"schemaVersion\":106,"
                + "\"pluginRegistryUrl\":\" " + ConfigMigrations.FROZEN_PLUGIN_REGISTRY + "\","
                + "\"mavenArchetypeCatalogUrl\":\"" + ConfigMigrations.FROZEN_MAVEN_ARCHETYPE_CATALOG + "\"}");
        ObjectNode out = ConfigMigrations.upgrade(ConfigSchema.SETTINGS, frozen, mapper);
        assertEquals(
                com.editora.config.Settings.SCHEMA_VERSION,
                out.get("schemaVersion").asInt());
        assertEquals("", out.get("pluginRegistryUrl").asText());
        assertEquals("", out.get("mavenArchetypeCatalogUrl").asText());

        JsonNode chosen = mapper.readTree("{\"schemaVersion\":106,"
                + "\"pluginRegistryUrl\":\"https://plugins.example/index.json\","
                + "\"mavenArchetypeCatalogUrl\":\"https://nexus.example/archetype-catalog.xml\"}");
        ObjectNode kept = ConfigMigrations.upgrade(ConfigSchema.SETTINGS, chosen.deepCopy(), mapper);
        assertEquals(
                "https://plugins.example/index.json",
                kept.get("pluginRegistryUrl").asText());
        assertEquals(
                "https://nexus.example/archetype-catalog.xml",
                kept.get("mavenArchetypeCatalogUrl").asText());

        // Neither key present (a hand-trimmed file), or not a string: left exactly as it is.
        JsonNode bare = mapper.readTree("{\"schemaVersion\":106,\"pluginRegistryUrl\":7}");
        ObjectNode same = ConfigMigrations.upgrade(ConfigSchema.SETTINGS, bare.deepCopy(), mapper);
        assertEquals(7, same.get("pluginRegistryUrl").asInt());
        assertFalse(same.has("mavenArchetypeCatalogUrl"));
    }

    /** v105→106: overrides became per-keymap; an existing file's stay in place, under the keymap it names. */
    @Test
    void perKeymapOverridesStepKeepsExistingKeybindingsWhereTheyAre() throws Exception {
        JsonNode stored = mapper.readTree(
                "{\"schemaVersion\":105,\"keymap\":\"cua\","
                        + "\"keybindings\":{\"C-f\":\"\",\"<f7>\":\"find.show\"},\"keybindingsMac\":{\"Cmd-k\":\"file.save\"}}");

        ObjectNode out = ConfigMigrations.upgrade(ConfigSchema.SETTINGS, stored, mapper);

        assertEquals(
                ConfigSchema.SETTINGS.currentVersion(), out.get("schemaVersion").asInt());
        assertEquals("cua", out.get("keymap").asText());
        assertEquals(stored.get("keybindings"), out.get("keybindings"));
        assertEquals(stored.get("keybindingsMac"), out.get("keybindingsMac"));
        com.editora.config.Settings loaded = mapper.treeToValue(out, com.editora.config.Settings.class);
        assertEquals("find.show", loaded.keybindingsFor(false).get("<f7>"));
        assertTrue(loaded.getKeymapKeybindings().isEmpty(), "nothing is parked for the other keymaps yet");
    }

    @Test
    void aFileWithoutAMarkerResumesAfterTheNewestStepItsKeysProve() throws Exception {
        // bracketColors first appeared in v90, so the v88→89 "turn Projects on" step has already run for
        // this file — but the later v100→101 toolbar step has not.
        JsonNode stored = mapper.readTree(
                "{\"bracketColors\":true,\"projectSupport\":false,\"toolbarLayout\":[\"file.saveAs\"]}");
        assertEquals(90, ConfigSchema.SETTINGS.versionWithoutMarker(stored));

        ObjectNode out = ConfigMigrations.upgrade(ConfigSchema.SETTINGS, stored, mapper);

        assertFalse(out.get("projectSupport").asBoolean(), "an explicit off is not flipped back on");
        assertEquals(
                "[\"file.saveAs\",\"toolbar.recent\"]", out.get("toolbarLayout").toString());
    }

    @Test
    void aFileWithNoDatingKeysIsStillAssumedToBeTheBaseline() throws Exception {
        JsonNode legacy = mapper.readTree("{\"fontSize\":20,\"projectSupport\":false}");
        assertEquals(1, ConfigSchema.SETTINGS.versionWithoutMarker(legacy));
        assertTrue(ConfigMigrations.upgrade(ConfigSchema.SETTINGS, legacy, mapper)
                .get("projectSupport")
                .asBoolean());
        // Files with no evidence table keep the plain assumed-legacy rule.
        assertEquals(1, ConfigSchema.WORKSPACE.versionWithoutMarker(mapper.readTree("{\"x\":1}")));
    }

    @Test
    void splitKeybindingsLeavesAnAlreadySplitFileAlone() throws Exception {
        JsonNode in = mapper.readTree(
                "{\"keybindings\":{\"C-k\":\"file.save\"},\"keybindingsMac\":{\"Cmd-k\":\"edit.cut\"}}");
        ObjectNode out = ConfigMigrations.splitKeybindings((ObjectNode) in, true);
        assertEquals("edit.cut", out.get("keybindingsMac").get("Cmd-k").asText(), "the Cmd overrides are kept");
        assertEquals("file.save", out.get("keybindings").get("C-k").asText(), "and the Ctrl map is not emptied");
    }

    // --- v115→116: the default PDF page size follows the region; existing files keep theirs ---------------

    @Test
    void keepLegacyPdfPageSizeWritesLetterOnlyWhereNoSizeIsStored() throws Exception {
        assertEquals(
                "letter",
                ConfigMigrations.keepLegacyPdfPageSize(mapper.readTree("{\"tabSize\":4}"))
                        .get("pdfPageSize")
                        .asText());
        for (String stored : new String[] {"a4", "letter"}) {
            JsonNode out =
                    ConfigMigrations.keepLegacyPdfPageSize(mapper.readTree("{\"pdfPageSize\":\"" + stored + "\"}"));
            assertEquals(stored, out.get("pdfPageSize").asText(), "a stored size is the user's");
        }
        // Safe to repeat.
        JsonNode once = ConfigMigrations.keepLegacyPdfPageSize(mapper.readTree("{}"));
        assertEquals(once.deepCopy(), ConfigMigrations.keepLegacyPdfPageSize(once));
    }

    @Test
    void upgradingASettingsFileFromV115KeepsItsPdfPageSize() throws Exception {
        ObjectNode a4 = ConfigMigrations.upgrade(
                ConfigSchema.SETTINGS, mapper.readTree("{\"schemaVersion\":115,\"pdfPageSize\":\"a4\"}"), mapper);
        assertEquals("a4", a4.get("pdfPageSize").asText());
        ObjectNode none =
                ConfigMigrations.upgrade(ConfigSchema.SETTINGS, mapper.readTree("{\"schemaVersion\":115}"), mapper);
        assertEquals("letter", none.get("pdfPageSize").asText(), "the step is registered and runs");
        assertEquals(
                ConfigSchema.SETTINGS.currentVersion(),
                none.get("schemaVersion").asInt());
    }

    // --- v117→118: + wordWrapProse and the Markdown preview/paste/TOC keys (additive) --------------------

    @Test
    void upgradingASettingsFileFromV117KeepsTheWordWrapChoiceAndWritesNoNewKeys() throws Exception {
        for (boolean wrap : new boolean[] {true, false}) {
            ObjectNode up = ConfigMigrations.upgrade(
                    ConfigSchema.SETTINGS,
                    mapper.readTree("{\"schemaVersion\":117,\"wordWrap\":" + wrap + "}"),
                    mapper);
            assertEquals(
                    com.editora.config.Settings.SCHEMA_VERSION,
                    up.get("schemaVersion").asInt(),
                    "the step is registered");
            assertEquals(wrap, up.get("wordWrap").asBoolean(), "the stored switch is kept; it now means code");
            // Absent means wrapped prose, remote images on, "assets", levels 1–6: the new defaults.
            for (String key : new String[] {
                "wordWrapProse",
                "markdownRemoteImages",
                "markdownAssetsFolder",
                "markdownTocMinLevel",
                "markdownTocMaxLevel"
            }) {
                assertFalse(up.has(key), key);
            }
        }
    }

    // --- v120→121: markdownRemoteImages (boolean) → markdownRemoteImagePolicy ----------------------------

    @Test
    void remoteImagesOffBecomesThePolicyNeverAndTheOldKeyGoes() throws Exception {
        JsonNode off = ConfigMigrations.remoteImagesToPolicy(mapper.readTree("{\"markdownRemoteImages\":false}"));
        assertEquals("never", off.get("markdownRemoteImagePolicy").asText());
        assertFalse(off.has("markdownRemoteImages"));

        JsonNode on = ConfigMigrations.remoteImagesToPolicy(mapper.readTree("{\"markdownRemoteImages\":true}"));
        assertFalse(on.has("markdownRemoteImagePolicy"), "on is the default: nothing is written");
        assertFalse(on.has("markdownRemoteImages"));

        JsonNode absent = ConfigMigrations.remoteImagesToPolicy(mapper.readTree("{\"tabSize\":4}"));
        assertEquals(mapper.readTree("{\"tabSize\":4}"), absent);

        JsonNode both = ConfigMigrations.remoteImagesToPolicy(
                mapper.readTree("{\"markdownRemoteImages\":false,\"markdownRemoteImagePolicy\":\"ask\"}"));
        assertEquals("ask", both.get("markdownRemoteImagePolicy").asText(), "a policy already stored wins");

        // Safe to repeat.
        assertEquals(off.deepCopy(), ConfigMigrations.remoteImagesToPolicy(off));
    }

    @Test
    void remoteExecutionIsTurnedOnOnceWhereAnEarlierBuildStoredItsOldDefault() throws Exception {
        JsonNode stored = ConfigMigrations.remoteExecutionOn(mapper.readTree("{\"remoteExecution\":false}"));
        assertTrue(stored.get("remoteExecution").asBoolean());

        JsonNode absent = ConfigMigrations.remoteExecutionOn(mapper.readTree("{\"tabSize\":4}"));
        assertEquals(mapper.readTree("{\"tabSize\":4}"), absent, "absent is the new default already");

        ObjectNode up = ConfigMigrations.upgrade(
                ConfigSchema.SETTINGS, mapper.readTree("{\"schemaVersion\":121,\"remoteExecution\":false}"), mapper);
        assertTrue(up.get("remoteExecution").asBoolean());
        assertTrue(new com.editora.config.Settings().isRemoteExecution(), "on for a new install too");
    }

    @Test
    void upgradingASettingsFileFromV120CarriesRemoteImagesOffIntoTheLoadedSettings() throws Exception {
        ObjectNode up = ConfigMigrations.upgrade(
                ConfigSchema.SETTINGS,
                mapper.readTree("{\"schemaVersion\":120,\"markdownRemoteImages\":false}"),
                mapper);
        assertEquals(
                com.editora.config.Settings.SCHEMA_VERSION,
                up.get("schemaVersion").asInt());
        assertEquals("never", up.get("markdownRemoteImagePolicy").asText());
        com.editora.config.Settings loaded = mapper.treeToValue(up, com.editora.config.Settings.class);
        assertFalse(loaded.isMarkdownRemoteImages());
        // And from the version that introduced the boolean, through every step in between.
        ObjectNode older = ConfigMigrations.upgrade(
                ConfigSchema.SETTINGS,
                mapper.readTree("{\"schemaVersion\":118,\"markdownRemoteImages\":false}"),
                mapper);
        assertEquals("never", older.get("markdownRemoteImagePolicy").asText());
    }

    // --- v116→117: + pdfOrientation / pdfMargins / pdfCodeFontSize (additive) ---------------------------

    @Test
    void upgradingASettingsFileFromV116AddsNoPdfPageKeysAndKeepsStoredOnes() throws Exception {
        ObjectNode plain = ConfigMigrations.upgrade(
                ConfigSchema.SETTINGS, mapper.readTree("{\"schemaVersion\":116,\"pdfPageSize\":\"a4\"}"), mapper);
        assertEquals(
                ConfigSchema.SETTINGS.currentVersion(),
                plain.get("schemaVersion").asInt(),
                "the step is registered");
        assertEquals("a4", plain.get("pdfPageSize").asText());
        // Absent means portrait, the writers' own margins and 9 pt — nothing is written for the upgrade.
        for (String key : new String[] {"pdfOrientation", "pdfMargins", "pdfCodeFontSize"}) {
            assertFalse(plain.has(key), key);
        }
        JsonNode stored = mapper.readTree(
                "{\"schemaVersion\":116,\"pdfOrientation\":\"landscape\",\"pdfMargins\":\"wide\",\"pdfCodeFontSize\":12}");
        ObjectNode kept = ConfigMigrations.upgrade(ConfigSchema.SETTINGS, stored.deepCopy(), mapper);
        assertEquals("landscape", kept.get("pdfOrientation").asText());
        assertEquals("wide", kept.get("pdfMargins").asText());
        assertEquals(12, kept.get("pdfCodeFontSize").asInt());
    }

    // --- v104→105: authorName persists its raw value; dead keys dropped ---------------------------------

    @Test
    void retireUnusedSettingsKeysRestoresABlankAuthorNameTheOldBuildFroze() throws Exception {
        // Written by the first save of a v104 build: authorName is the resolved OS user, the junk key still
        // records that the configured value was blank.
        JsonNode in = mapper.readTree(
                "{\"authorName\":\"adl\",\"authorNameRaw\":\"\",\"ijhttpCommand\":\"ijhttp\",\"tabSize\":4}");
        ObjectNode out = (ObjectNode) ConfigMigrations.retireUnusedSettingsKeys(in);
        assertEquals("", out.get("authorName").asText(), "blank = follow the OS user again");
        assertFalse(out.has("authorNameRaw"));
        assertFalse(out.has("ijhttpCommand"));
        assertEquals(4, out.get("tabSize").asInt());
    }

    @Test
    void retireUnusedSettingsKeysDoesNotGuessAtAnAuthorNameThatWasConfigured() throws Exception {
        // Both keys carry a name: the file cannot say whether it was typed or frozen, so it is kept.
        JsonNode in = mapper.readTree("{\"authorName\":\"adl\",\"authorNameRaw\":\"adl\"}");
        ObjectNode out = (ObjectNode) ConfigMigrations.retireUnusedSettingsKeys(in);
        assertEquals("adl", out.get("authorName").asText());
        assertFalse(out.has("authorNameRaw"));
        // And a file that never had the junk key is untouched.
        assertEquals(
                "x",
                ((ObjectNode) ConfigMigrations.retireUnusedSettingsKeys(mapper.readTree("{\"authorName\":\"x\"}")))
                        .get("authorName")
                        .asText());
    }

    @Test
    void upgradingASettingsFileFromV104DropsTheRetiredKeys() throws Exception {
        JsonNode stored = mapper.readTree("{\"schemaVersion\":104,\"authorName\":\"adl\",\"authorNameRaw\":\"\"}");
        ObjectNode out = ConfigMigrations.upgrade(ConfigSchema.SETTINGS, stored, mapper);
        assertEquals("", out.get("authorName").asText());
        assertFalse(out.has("authorNameRaw"));
        assertEquals(
                com.editora.config.Settings.SCHEMA_VERSION,
                out.get("schemaVersion").asInt());
    }

    /** plugins.json v1 → v2: every enabled plugin keeps its consent until the next launch fingerprints it. */
    @Test
    void pluginConsentIsCarriedAsAdoptForEveryEnabledPlugin() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode v1 = mapper.readTree("{\"schemaVersion\":1,\"enabled\":{\"a\":true,\"b\":false}}");
        JsonNode v2 = ConfigMigrations.pluginConsentAdopt(v1);
        assertEquals("*", v2.get("consent").get("a").asText());
        assertFalse(v2.get("consent").has("b"), "a plugin that was off has nothing to carry");
        assertTrue(v2.get("enabled").get("a").asBoolean());
        com.editora.config.PluginStore store = mapper.treeToValue(v2, com.editora.config.PluginStore.class);
        assertTrue(store.allows("a", "whatever is installed"), "nobody's plugins switch off on the upgrade");
        assertFalse(store.allows("b", "x"));
    }
}
