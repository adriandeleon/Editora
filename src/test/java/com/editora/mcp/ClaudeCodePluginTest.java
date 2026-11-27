package com.editora.mcp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Claude Code plugin in {@code integrations/claude-code} keeps up with the MCP server: it starts {@code editora
 * mcp}, every Editora tool its skills name exists, and the tools a skill pre-approves only read.
 */
class ClaudeCodePluginTest {

    private static final Path MARKETPLACE = Path.of("integrations/claude-code");
    private static final Path PLUGIN = MARKETPLACE.resolve("editora");
    private static final String PREFIX = "mcp__plugin_editora_editora__";

    private final ObjectMapper m = new ObjectMapper();

    private Map<String, Boolean> readOnlyByTool() {
        Map<String, Boolean> tools = new HashMap<>();
        for (JsonNode t : new McpToolCatalog(m).listToolsResult().get("tools")) {
            tools.put(t.get("name").asText(), t.at("/annotations/readOnlyHint").asBoolean());
        }
        return tools;
    }

    @Test
    void theMarketplaceAndPluginNameEachOtherAndTheServerIsEditoraMcp() throws Exception {
        JsonNode market = m.readTree(
                MARKETPLACE.resolve(".claude-plugin/marketplace.json").toFile());
        JsonNode entry = market.get("plugins").get(0);
        JsonNode plugin =
                m.readTree(PLUGIN.resolve(".claude-plugin/plugin.json").toFile());
        assertEquals(plugin.get("name").asText(), entry.get("name").asText());
        assertEquals("./editora", entry.get("source").asText());
        assertEquals("MIT", plugin.get("license").asText());
        JsonNode server = m.readTree(PLUGIN.resolve(".mcp.json").toFile()).at("/mcpServers/editora");
        assertEquals("${user_config.editora_bin}", server.get("command").asText());
        assertEquals(McpStdio.COMMAND, server.get("args").get(0).asText(), "the stdio relay");
        assertEquals("editora", plugin.at("/userConfig/editora_bin/default").asText());
    }

    @Test
    void everyToolASkillNamesExistsAndThoseItPreApprovesOnlyRead() throws Exception {
        Map<String, Boolean> tools = readOnlyByTool();
        Pattern allowed = Pattern.compile("(?m)^allowed-tools:(.*)$");
        Pattern named = Pattern.compile("`([a-z_]+)`");
        int skills = 0;
        try (var dirs = Files.list(PLUGIN.resolve("skills"))) {
            for (Path dir : dirs.toList()) {
                String text = Files.readString(dir.resolve("SKILL.md"));
                skills++;
                assertTrue(text.startsWith("---\nname: " + dir.getFileName() + "\n"), dir + ": frontmatter names it");
                Matcher a = allowed.matcher(text);
                assertTrue(a.find(), dir + ": allowed-tools");
                for (String tool : a.group(1).strip().split("\\s+")) {
                    assertTrue(tool.startsWith(PREFIX), tool);
                    String name = tool.substring(PREFIX.length());
                    assertTrue(tools.containsKey(name), dir + " pre-approves a tool Editora has not: " + name);
                    assertTrue(tools.get(name), dir + " pre-approves " + name + ", which is not read-only");
                }
                Matcher n = named.matcher(text);
                while (n.find()) {
                    String word = n.group(1);
                    if (word.contains("_")
                            && word.matches(".*(review|buffer|file|selection|diagnostics|symbols|"
                                    + "status|scan|command|tabs|commands).*")
                            && !word.equals("thread_id")
                            && !word.equals("start_line")
                            && !word.equals("end_line")
                            && !word.equals("old_text")
                            && !word.equals("new_text")) {
                        assertTrue(tools.containsKey(word), dir + " names a tool Editora has not: " + word);
                    }
                }
            }
        }
        assertEquals(2, skills);
    }
}
