package com.editora.config.migration;

import java.util.List;
import java.util.function.UnaryOperator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The migration steps on the shapes a damaged or hand-edited file can have: what each leaves alone. */
class ConfigMigrationsBranchesTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private ObjectNode json(String s) throws Exception {
        return (ObjectNode) mapper.readTree(s);
    }

    @Test
    void everyStepLeavesANonObjectAsItIs() {
        List<UnaryOperator<JsonNode>> steps = List.of(
                ConfigMigrations::blankPrePinXmlCommand,
                ConfigMigrations::seedOpenToolWindows,
                ConfigMigrations::raiseLargeFileThreshold,
                ConfigMigrations::resetRightToLeftMapFlow,
                ConfigMigrations::macrosGainIds,
                ConfigMigrations::pluginConsentAdopt,
                ConfigMigrations::seedOpenProjectIds,
                ConfigMigrations::growTodoDefaultKeywords,
                ConfigMigrations::splitKeybindingsByPlatform,
                ConfigMigrations::keyBindingsByCommand,
                ConfigMigrations::enableProjectSupport,
                ConfigMigrations::restoreRecentToToolbarLayout,
                ConfigMigrations::retireUnusedSettingsKeys,
                ConfigMigrations::blankFrozenDefaultUrls,
                ConfigMigrations::keepLegacyPdfPageSize,
                ConfigMigrations::remoteImagesToPolicy,
                ConfigMigrations::extendedWindowOn,
                ConfigMigrations::keymapChosenForExistingInstalls,
                ConfigMigrations::remoteExecutionOn,
                ConfigMigrations::spellSkipJsonLinesLikeJson,
                ConfigMigrations::clampTerminalScrollback,
                ConfigMigrations::splitOpenaiEndpoint,
                ConfigMigrations::splitCodexModel,
                ConfigMigrations::splitAiApiKeyByProvider,
                ConfigMigrations::addDefaultAgentIdToSessions);
        ArrayNode array = JsonNodeFactory.instance.arrayNode().add(1);
        for (UnaryOperator<JsonNode> step : steps) {
            assertSame(array, step.apply(array));
            assertEquals("[1]", array.toString());
        }
    }

    @Test
    void recentFilesOfAnUnexpectedShapeStartEmpty() throws Exception {
        assertEquals(
                "{\"files\":[]}",
                ConfigMigrations.wrapRecentFilesArray(json("{\"x\":1}")).toString());
        assertEquals(
                "{\"files\":[]}", ConfigMigrations.wrapRecentFilesArray(null).toString());
        assertEquals(
                "{\"files\":[\"a\"]}",
                ConfigMigrations.wrapRecentFilesArray(mapper.readTree("[\"a\"]"))
                        .toString());
    }

    @Test
    void anXmlCommandThatIsNotTextIsKept() throws Exception {
        ObjectNode o = json("{\"xmlLspCommand\": 5}");
        ConfigMigrations.blankPrePinXmlCommand(o);
        assertEquals(5, o.get("xmlLspCommand").asInt());
        assertFalse(ConfigMigrations.isPrePinXmlCommand("lemminx"));
        assertFalse(ConfigMigrations.isPrePinXmlCommand("\"\""));
    }

    @Test
    void toolWindowsSeedOnlyFromNonEmptyText() throws Exception {
        ObjectNode o =
                json("{\"openLeftToolWindow\": \"\", \"openRightToolWindow\": 3, \"openBottomToolWindow\": \"t\"}");
        ConfigMigrations.seedOpenToolWindows(o);
        assertEquals("{\"BOTTOM\":[\"t\"]}", o.get("openToolWindows").toString());
        ObjectNode seeded = json("{\"openToolWindows\": {\"LEFT\": [\"x\"]}, \"openBottomToolWindow\": \"t\"}");
        ConfigMigrations.seedOpenToolWindows(seeded);
        assertEquals("{\"LEFT\":[\"x\"]}", seeded.get("openToolWindows").toString(), "already seeded");
    }

    @Test
    void onlyTheOldDefaultsAreRaisedOrReset() throws Exception {
        ObjectNode o = json("{\"largeFileThreshold\": 20000, \"projectMapFlow\": \"TOP_DOWN\"}");
        ConfigMigrations.raiseLargeFileThreshold(o);
        ConfigMigrations.resetRightToLeftMapFlow(o);
        assertEquals(20000, o.get("largeFileThreshold").asInt());
        assertEquals("TOP_DOWN", o.get("projectMapFlow").asText());
        ObjectNode text = json("{\"largeFileThreshold\": \"10000\", \"projectMapFlow\": 1}");
        ConfigMigrations.raiseLargeFileThreshold(text);
        ConfigMigrations.resetRightToLeftMapFlow(text);
        assertEquals("{\"largeFileThreshold\":\"10000\",\"projectMapFlow\":1}", text.toString());
    }

    @Test
    void macrosOfOddShapes() throws Exception {
        assertEquals(
                "{\"macros\":3}",
                ConfigMigrations.macrosGainIds(json("{\"macros\": 3}")).toString());
        ObjectNode o = json("{\"macros\": [7, {\"name\": 5, \"steps\": [{\"kind\": \"key\", \"value\": \"a\\tb\"},"
                + " {\"value\": 9}, {\"kind\": 2, \"value\": \"x\\r\\ny\\rz\"}]}]}");
        ConfigMigrations.macrosGainIds(o);
        JsonNode m = o.get("macros").get(1);
        assertTrue(m.get("id").isTextual());
        assertEquals("", o.get("lastId").asText(), "no unnamed macro");
        ArrayNode steps = (ArrayNode) m.get("steps");
        assertEquals("key", steps.get(0).get("kind").asText(), "a key step is kept whole");
        assertEquals("a\tb", steps.get(0).get("value").asText());
        assertEquals(9, steps.get(1).get("value").asInt());
        List<String> rest = new java.util.ArrayList<>();
        for (int i = 2; i < steps.size(); i++) {
            rest.add(steps.get(i).get("kind").asText() + ":"
                    + steps.get(i).get("value").asText());
        }
        assertEquals(List.of("text:x", "key:ENTER", "text:y", "key:ENTER", "text:z"), rest);
    }

    @Test
    void theLastUnnamedMacroIsTheLiveOne() throws Exception {
        ObjectNode o = json("{\"macros\": [{\"name\": \"macro sin nombre\"}, {\"name\": \"unnamed macro\"}]}");
        ConfigMigrations.macrosGainIds(o);
        assertEquals("", o.get("macros").get(1).get("name").asText());
        assertEquals("macro sin nombre", o.get("macros").get(0).get("name").asText());
        assertEquals(o.get("macros").get(1).get("id").asText(), o.get("lastId").asText());
    }

    @Test
    void pluginConsentKeepsWhatIsThereAndSkipsDisabled() throws Exception {
        ObjectNode o = json(
                "{\"consent\": {\"a\": \"fp\"}, \"enabled\": {\"a\": true, \"b\": false, \"c\": \"yes\", \"d\": true}}");
        ConfigMigrations.pluginConsentAdopt(o);
        assertEquals("{\"a\":\"fp\",\"d\":\"*\"}", o.get("consent").toString());
        ObjectNode none = json("{\"enabled\": []}");
        ConfigMigrations.pluginConsentAdopt(none);
        assertEquals("{}", none.get("consent").toString());
    }

    @Test
    void openProjectIdsSeedOnce() throws Exception {
        ObjectNode blank = json("{\"activeProjectId\": \"\"}");
        ConfigMigrations.seedOpenProjectIds(blank);
        assertEquals("[]", blank.get("openProjectIds").toString());
        ObjectNode seeded = json("{\"activeProjectId\": \"p\", \"openProjectIds\": [\"q\"]}");
        ConfigMigrations.seedOpenProjectIds(seeded);
        assertEquals("[\"q\"]", seeded.get("openProjectIds").toString());
        ObjectNode number = json("{\"activeProjectId\": 4}");
        ConfigMigrations.seedOpenProjectIds(number);
        assertEquals("[]", number.get("openProjectIds").toString());
    }

    @Test
    void todoKeywordsGrowOnlyAnArray() throws Exception {
        ObjectNode absent = json("{}");
        ConfigMigrations.growTodoDefaultKeywords(absent);
        assertFalse(absent.has("todoPatterns"));
        ObjectNode notArray = json("{\"todoPatterns\": {}}");
        ConfigMigrations.growTodoDefaultKeywords(notArray);
        assertEquals("{}", notArray.get("todoPatterns").toString());
        ObjectNode odd = json("{\"todoPatterns\": [{\"name\": 1}, {\"pattern\": \"x\"}]}");
        ConfigMigrations.growTodoDefaultKeywords(odd);
        int defaults = com.editora.todo.TodoPatterns.defaults().size();
        assertEquals(2 + defaults, odd.get("todoPatterns").size(), "names that are not text name nothing");
    }

    @Test
    void splitKeybindingsOnAMacWithoutAMap() throws Exception {
        ObjectNode o = json("{\"keybindings\": \"junk\"}");
        ConfigMigrations.splitKeybindings(o, true);
        assertEquals("{}", o.get("keybindingsMac").toString());
        assertEquals("{}", o.get("keybindings").toString());
        ObjectNode split = json("{\"keybindings\": {\"Ctrl-S\": \"x\"}, \"keybindingsMac\": {}}");
        ConfigMigrations.splitKeybindings(split, true);
        assertEquals("{\"Ctrl-S\":\"x\"}", split.get("keybindings").toString(), "already split");
        ObjectNode linux = json("{\"keybindings\": {\"Ctrl-S\": \"x\"}}");
        ConfigMigrations.splitKeybindings(linux, false);
        assertFalse(linux.has("keybindingsMac"));
    }

    @Test
    void keyBindingsByCommandDropsWhatIsNotABinding() throws Exception {
        ObjectNode o = json("{\"keybindings\": {\"\": \"file.save\", \"Ctrl-Alt-F12\": 4},"
                + " \"keymapKeybindings\": {\"\": {\"Ctrl-1\": \"x\"}, \"other-keymap\": 3},"
                + " \"keymapKeybindingsMac\": [1]}");
        ConfigMigrations.keyBindingsByCommand(o);
        for (String old : List.of("keybindings", "keybindingsMac", "keymapKeybindings", "keymapKeybindingsMac")) {
            assertFalse(o.has(old), old);
        }
        assertEquals("{}", o.get("keyBindings").toString());
        assertEquals("{}", o.get("keyBindingsMacOnly").toString());
        ObjectNode current = json("{\"keyBindings\": {\"default\": {}}}");
        assertSame(current, ConfigMigrations.keyBindingsByCommand(current));
        assertEquals("{\"keyBindings\":{\"default\":{}}}", current.toString(), "nothing of the old shape");
    }

    /** v189→190: an existing install has used its keymap, so it is not offered the first-run card; a choice is kept. */
    @Test
    void keymapChosenIsOnForAFileWithoutItAndAStoredValueIsKept() throws Exception {
        ObjectNode old = json("{\"keymap\": \"emacs\"}");
        ConfigMigrations.keymapChosenForExistingInstalls(old);
        assertTrue(old.get("keymapChosen").asBoolean());
        ConfigMigrations.keymapChosenForExistingInstalls(old); // safe to repeat
        assertTrue(old.get("keymapChosen").asBoolean());
        ObjectNode fresh = json("{\"keymapChosen\": false}");
        ConfigMigrations.keymapChosenForExistingInstalls(fresh);
        assertEquals("{\"keymapChosen\":false}", fresh.toString(), "a stored value is the user's state");
    }

    @Test
    void projectSupportAndExtendedWindowTurnOnOnlyFromFalse() throws Exception {
        ObjectNode o = json("{\"projectSupport\": \"false\", \"extendedWindow\": \"no\"}");
        ConfigMigrations.enableProjectSupport(o);
        ConfigMigrations.extendedWindowOn(o);
        assertEquals("{\"projectSupport\":\"false\",\"extendedWindow\":\"no\"}", o.toString());
        ObjectNode on = json("{\"projectSupport\": true, \"extendedWindow\": true}");
        ConfigMigrations.enableProjectSupport(on);
        ConfigMigrations.extendedWindowOn(on);
        assertTrue(on.get("projectSupport").asBoolean());
        ObjectNode none = json("{}");
        ConfigMigrations.extendedWindowOn(none);
        ConfigMigrations.remoteExecutionOn(none);
        assertEquals("{}", none.toString());
    }

    @Test
    void recentIsAppendedWhenThereIsNoSaveAs() throws Exception {
        ObjectNode o = json("{\"toolbarLayout\": [\"file.open\", 3]}");
        ConfigMigrations.restoreRecentToToolbarLayout(o);
        assertEquals(
                "[\"file.open\",3,\"toolbar.recent\"]", o.get("toolbarLayout").toString());
        ObjectNode notArray = json("{\"toolbarLayout\": \"x\"}");
        ConfigMigrations.restoreRecentToToolbarLayout(notArray);
        assertEquals("\"x\"", notArray.get("toolbarLayout").toString());
        ObjectNode twice = json("{\"toolbarLayout\": [\"file.saveAs\", \"file.saveAs\"]}");
        ConfigMigrations.restoreRecentToToolbarLayout(twice);
        assertEquals(
                "[\"file.saveAs\",\"toolbar.recent\",\"file.saveAs\"]",
                twice.get("toolbarLayout").toString());
    }

    @Test
    void retiredKeysOnlyRestoreABlankAuthor() throws Exception {
        ObjectNode o = json("{\"authorNameRaw\": \"Ada\", \"authorName\": \"Ada\", \"ijhttpCommand\": \"x\"}");
        ConfigMigrations.retireUnusedSettingsKeys(o);
        assertEquals("{\"authorName\":\"Ada\"}", o.toString());
        ObjectNode number = json("{\"authorNameRaw\": 1, \"authorName\": \"Ada\"}");
        ConfigMigrations.retireUnusedSettingsKeys(number);
        assertEquals("{\"authorName\":\"Ada\"}", number.toString());
    }

    @Test
    void frozenUrlsMatchOnlyAsText() throws Exception {
        ObjectNode o = json("{\"pluginRegistryUrl\": 1, \"mavenArchetypeCatalogUrl\": \" "
                + ConfigMigrations.FROZEN_MAVEN_ARCHETYPE_CATALOG + " \"}");
        ConfigMigrations.blankFrozenDefaultUrls(o);
        assertEquals(1, o.get("pluginRegistryUrl").asInt());
        assertEquals("", o.get("mavenArchetypeCatalogUrl").asText());
    }

    @Test
    void remoteImagesPolicyIsKeptWhenThereAlready() throws Exception {
        ObjectNode o = json("{\"markdownRemoteImages\": true, \"markdownRemoteImagePolicy\": \"ask\"}");
        ConfigMigrations.remoteImagesToPolicy(o);
        assertEquals("{\"markdownRemoteImagePolicy\":\"ask\"}", o.toString());
        ObjectNode text = json("{\"markdownRemoteImages\": \"yes\"}");
        ConfigMigrations.remoteImagesToPolicy(text);
        assertEquals("{}", text.toString());
        ObjectNode on = json("{\"markdownRemoteImages\": true}");
        ConfigMigrations.remoteImagesToPolicy(on);
        assertEquals("always", on.get("markdownRemoteImagePolicy").asText());
        ObjectNode pdf = json("{\"pdfPageSize\": null}");
        ConfigMigrations.keepLegacyPdfPageSize(pdf);
        assertEquals("letter", pdf.get("pdfPageSize").asText());
    }

    @Test
    void spellListsWithoutJsonOrWithJsonLinesAreKept() throws Exception {
        ObjectNode both = json("{\"spellDisabledLanguages\": [\"json\", \"jsonl\"]}");
        ConfigMigrations.spellSkipJsonLinesLikeJson(both);
        assertEquals(2, both.get("spellDisabledLanguages").size());
        ObjectNode neither = json("{\"spellDisabledLanguages\": [\"yaml\"]}");
        ConfigMigrations.spellSkipJsonLinesLikeJson(neither);
        assertEquals(1, neither.get("spellDisabledLanguages").size());
        ObjectNode notList = json("{\"spellDisabledLanguages\": \"json\"}");
        ConfigMigrations.spellSkipJsonLinesLikeJson(notList);
        assertEquals("\"json\"", notList.get("spellDisabledLanguages").toString());
    }

    @Test
    void scrollbackWithinTheMaximumOrNotANumberIsKept() throws Exception {
        ObjectNode o = json("{\"terminalScrollback\": 1000}");
        ConfigMigrations.clampTerminalScrollback(o);
        assertEquals(1000, o.get("terminalScrollback").asInt());
        ObjectNode text = json("{\"terminalScrollback\": \"999999\"}");
        ConfigMigrations.clampTerminalScrollback(text);
        assertEquals("999999", text.get("terminalScrollback").asText());
        ObjectNode none = json("{}");
        ConfigMigrations.clampTerminalScrollback(none);
        assertEquals("{}", none.toString());
    }

    @Test
    void openaiEndpointMovesOnlyIntoABlankField() throws Exception {
        ObjectNode anthropic = json("{\"aiProvider\": \"anthropic\", \"aiEndpoint\": \"https://proxy/v1/messages\"}");
        ConfigMigrations.splitOpenaiEndpoint(anthropic);
        assertEquals("https://proxy/v1/messages", anthropic.get("aiEndpoint").asText());
        ObjectNode leftover = json(
                "{\"aiProvider\": 3, \"aiEndpoint\": \"https://h/v1/chat/completions//\"," + " \"aiModel\": \"m\"}");
        ConfigMigrations.splitOpenaiEndpoint(leftover);
        assertEquals("", leftover.get("aiEndpoint").asText());
        assertEquals("m", leftover.get("aiModel").asText(), "the model stays under another provider");
        ObjectNode taken = json("{\"aiProvider\": \" OpenAI \", \"aiModel\": \"m\", \"aiModelOpenai\": \"n\","
                + " \"aiCompletionModel\": \" \"}");
        ConfigMigrations.splitOpenaiEndpoint(taken);
        assertEquals("m", taken.get("aiModel").asText());
        assertEquals("n", taken.get("aiModelOpenai").asText());
        assertFalse(taken.has("aiCompletionModelOpenai"));
    }

    @Test
    void codexModelCopiesOnlyText() throws Exception {
        ObjectNode o = json("{\"aiModel\": 3}");
        ConfigMigrations.splitCodexModel(o);
        assertEquals("", o.get("aiModelCodex").asText());
        ObjectNode has = json("{\"aiModel\": \"m\", \"aiModelCodex\": \"c\"}");
        ConfigMigrations.splitCodexModel(has);
        assertEquals("c", has.get("aiModelCodex").asText());
    }

    @Test
    void apiKeysMoveOnlyUnderOpenaiIntoABlankField() throws Exception {
        ObjectNode set = json("{\"aiProvider\": \"openai\", \"aiApiKey\": \"k\", \"aiApiKeyOpenai\": \"o\"}");
        ConfigMigrations.splitAiApiKeyByProvider(set);
        assertEquals("k", set.get("aiApiKey").asText());
        ObjectNode blank = json("{\"aiProvider\": \"openai\", \"aiApiKey\": \" \"}");
        ConfigMigrations.splitAiApiKeyByProvider(blank);
        assertFalse(blank.has("aiApiKeyOpenai"));
        ObjectNode moved = json("{\"aiProvider\": \"openai\", \"aiApiKey\": \"k\", \"aiApiKeyOpenai\": 5}");
        ConfigMigrations.splitAiApiKeyByProvider(moved);
        assertEquals("k", moved.get("aiApiKeyOpenai").asText());
        ObjectNode notText = json("{\"aiProvider\": 1, \"aiApiKey\": 2}");
        ConfigMigrations.splitAiApiKeyByProvider(notText);
        assertEquals(2, notText.get("aiApiKey").asInt());
    }

    @Test
    void agentIdsBackfillOnlyWhereMissing() throws Exception {
        ObjectNode o =
                json("{\"sessions\": [1, {\"agentId\": \"gemini\"}, {\"agentId\": \" \"}, {\"agentId\": 4}, {}]}");
        ConfigMigrations.addDefaultAgentIdToSessions(o);
        assertEquals(
                "[1,{\"agentId\":\"gemini\"},{\"agentId\":\"claude\"},{\"agentId\":\"claude\"},{\"agentId\":\"claude\"}]",
                o.get("sessions").toString());
        ObjectNode none = json("{\"sessions\": {}}");
        ConfigMigrations.addDefaultAgentIdToSessions(none);
        assertEquals("{}", none.get("sessions").toString());
        ObjectNode absent = json("{}");
        ConfigMigrations.addDefaultAgentIdToSessions(absent);
        assertEquals("{}", absent.toString());
    }

    @Test
    void versionOfEveryShape() throws Exception {
        assertEquals(7, ConfigMigrations.versionOf(null, 7));
        assertEquals(7, ConfigMigrations.versionOf(com.fasterxml.jackson.databind.node.MissingNode.getInstance(), 7));
        assertEquals(7, ConfigMigrations.versionOf(JsonNodeFactory.instance.nullNode(), 7));
        assertEquals(0, ConfigMigrations.versionOf(JsonNodeFactory.instance.arrayNode(), 7));
        assertEquals(7, ConfigMigrations.versionOf(json("{\"schemaVersion\": \"3\"}"), 7));
        assertEquals(3, ConfigMigrations.versionOf(json("{\"schemaVersion\": 3}"), 7));
        assertEquals(7, ConfigMigrations.versionOf(JsonNodeFactory.instance.textNode("x"), 7));
    }

    @Test
    void aStoreIsAnObjectOrTheOneLegacyArray() throws Exception {
        assertFalse(ConfigMigrations.isStoreShape(null, ConfigSchema.SETTINGS));
        assertFalse(ConfigMigrations.isStoreShape(
                com.fasterxml.jackson.databind.node.MissingNode.getInstance(), ConfigSchema.SETTINGS));
        assertTrue(ConfigMigrations.isStoreShape(json("{}"), ConfigSchema.SETTINGS));
        assertFalse(ConfigMigrations.isStoreShape(JsonNodeFactory.instance.textNode("x"), ConfigSchema.SETTINGS));
    }
}
