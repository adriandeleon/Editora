package com.editora.agent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * A stand-in ACP agent for tests: a child JVM that speaks the newline-delimited JSON-RPC of the Agent Client
 * Protocol on stdin/stdout and never contacts a model. The first argument picks how the session behaves; the
 * text of each prompt picks what the turn does (see {@link #prompt}). Start it with {@link #command}.
 *
 * <p>Scenarios: {@code ok} (models and modes as catalogs), {@code bare} (a session with neither),
 * {@code configopts} (models and modes as {@code configOptions}), {@code nosession} ({@code session/new}
 * answers without an id), {@code newfail} / {@code resumefail} (the request is refused), {@code exit}
 * (the process ends at once).
 */
public final class FakeAcpAgent {

    private static final ObjectMapper M = new ObjectMapper();

    private FakeAcpAgent() {}

    /** The command line that runs this class in a child JVM with the test classpath. */
    public static List<String> command(String scenario) {
        return List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                System.getProperty("java.class.path"),
                FakeAcpAgent.class.getName(),
                scenario);
    }

    /** {@link #command} as one quoted string, for a setting that holds a command line. */
    public static String commandLine(String scenario) {
        StringBuilder sb = new StringBuilder();
        for (String token : command(scenario)) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append('"').append(token).append('"');
        }
        return sb.toString();
    }

    public static void main(String[] args) throws Exception {
        String scenario = args.length > 0 ? args[0] : "ok";
        if (scenario.equals("exit")) {
            System.exit(3);
        }
        System.err.println("fake agent ready"); // an agent's stderr is drained, never parsed
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String session = "sess-1";
        String model = "m1";
        String mode = "ask";
        JsonNode promptId = null; // the turn that is waiting for something
        for (String line; (line = in.readLine()) != null; ) {
            JsonNode msg = M.readTree(line);
            String method = msg.path("method").asText("");
            JsonNode params = msg.path("params");
            if (method.isEmpty()) {
                // The editor's answer to a request this agent made during a turn: report it and end the turn.
                if (msg.has("error")) {
                    chunk(
                            session,
                            "REFUSED[" + msg.path("error").path("code").asInt() + "]:"
                                    + msg.path("error").path("message").asText());
                } else {
                    chunk(session, "ANSWER:" + msg.path("result"));
                }
                endTurn(promptId, "end_turn");
                promptId = null;
                continue;
            }
            if (!msg.has("id")) {
                if (method.equals("session/cancel") && promptId != null) {
                    endTurn(promptId, "cancelled");
                    promptId = null;
                }
                continue;
            }
            JsonNode id = msg.get("id");
            switch (method) {
                case "initialize" -> result(id, M.createObjectNode().put("protocolVersion", 1));
                case "session/new" -> {
                    if (scenario.equals("newfail")) {
                        error(id, "not logged in");
                    } else if (scenario.equals("nosession")) {
                        result(id, M.createObjectNode());
                    } else {
                        result(id, sessionInfo(scenario, session, model, mode));
                    }
                }
                case "session/resume" -> {
                    if (scenario.equals("resumefail")) {
                        error(id, "unknown session");
                    } else {
                        session = params.path("sessionId").asText();
                        result(id, sessionInfo(scenario, session, model, mode));
                    }
                }
                case "session/set_model" -> {
                    String wanted = params.path("modelId").asText();
                    if (wanted.equals("m-broken")) {
                        error(id, "no such model");
                    } else {
                        model = wanted;
                        result(id, M.createObjectNode());
                    }
                }
                case "session/set_mode" -> {
                    String wanted = params.path("modeId").asText();
                    if (wanted.equals("locked")) {
                        error(id, "mode is locked");
                    } else {
                        mode = wanted;
                        result(id, M.createObjectNode());
                    }
                }
                case "session/set_config_option" -> {
                    if (params.path("configId").asText().equals("model-option")) {
                        model = params.path("value").asText();
                    } else {
                        mode = params.path("value").asText();
                    }
                    result(id, sessionInfo(scenario, session, model, mode));
                }
                case "session/prompt" -> {
                    String text = params.path("prompt").path(0).path("text").asText();
                    if (!prompt(session, id, text)) {
                        promptId = id; // answered later: by a cancel, or by the reply to a request of ours
                    }
                }
                default -> error(id, "unsupported: " + method);
            }
        }
    }

    /**
     * Plays one turn, chosen by a word in the prompt. Returns false when the turn stays open (it waits for a
     * cancel, or for the editor's answer to a request made here).
     */
    private static boolean prompt(String session, JsonNode id, String text) {
        if (text.contains("#stream")) {
            chunk(session, "Hello, ");
            chunk(session, "world.");
            update(
                    session,
                    "agent_thought_chunk",
                    u -> u.putObject("content").put("type", "text").put("text", "hmm"));
            update(session, "tool_call", u -> u.put("title", "ls -la"));
            update(session, "tool_call_update", u -> u.put("status", "completed"));
            update(session, "tool_call_update", u -> u.put("status", "failed"));
            update(session, "plan", u -> {
                ArrayNode entries = u.putArray("entries");
                entries.addObject().put("content", "Read the file").put("status", "completed");
                entries.addObject().put("content", "Change it").put("status", "in_progress");
                entries.addObject().put("content", "Run the tests").put("status", "pending");
            });
            update(session, "current_mode_update", u -> u.put("currentModeId", "code"));
            update(session, "something_new", u -> u.put("x", 1));
            // Things a client must shrug off: blank and unparseable lines, a value that is not an object, an
            // answer nobody asked for, and a notification it does not know.
            System.out.println();
            System.out.println("this is not json");
            System.out.println("[1,2,3]");
            System.out.println("{\"jsonrpc\":\"2.0\",\"id\":424242,\"result\":{}}");
            System.out.println("{\"jsonrpc\":\"2.0\",\"id\":\"abc\",\"result\":{}}");
            System.out.println("{\"jsonrpc\":\"2.0\",\"method\":\"telemetry/event\",\"params\":{}}");
            chunk(session, " Done.");
        } else if (text.contains("#echo")) {
            chunk(session, "PROMPT[[" + text.replace("\n", "⏎") + "]]");
        } else if (text.contains("#hang")) {
            chunk(session, "working");
            return false;
        } else if (text.contains("#die")) {
            System.exit(7);
        } else if (text.contains("#fail-bare")) {
            ObjectNode n = M.createObjectNode().put("jsonrpc", "2.0");
            n.set("id", id);
            n.putObject("error").put("code", -32000);
            System.out.println(n);
            return true;
        } else if (text.contains("#fail")) {
            error(id, "model overloaded");
            return true;
        } else if (text.contains("#nostop")) {
            result(id, M.createObjectNode());
            return true;
        } else if (text.contains("#config-other")) {
            configUpdate("some-other-session", "m2", "code");
        } else if (text.contains("#config")) {
            configUpdate(session, "m2", "code");
        } else if (text.contains("#read ")) {
            ObjectNode p = M.createObjectNode().put("sessionId", session).put("path", argument(text, "#read "));
            request(700, "fs/read_text_file", p);
            return false;
        } else if (text.contains("#write ")) {
            ObjectNode p = M.createObjectNode()
                    .put("sessionId", session)
                    .put("path", argument(text, "#write "))
                    .put("content", "written by the agent\n");
            request(701, "fs/write_text_file", p);
            return false;
        } else if (text.contains("#terminal")) {
            request(703, "terminal/create", M.createObjectNode().put("sessionId", session));
            return false;
        }
        endTurn(id, "end_turn");
        return true;
    }

    /** The rest of the line after {@code keyword}. */
    private static String argument(String text, String keyword) {
        String rest = text.substring(text.indexOf(keyword) + keyword.length());
        int nl = rest.indexOf('\n');
        return (nl >= 0 ? rest.substring(0, nl) : rest).strip();
    }

    private static ObjectNode sessionInfo(String scenario, String session, String model, String mode) {
        ObjectNode info = M.createObjectNode().put("sessionId", session);
        if (scenario.equals("bare")) {
            return info;
        }
        if (scenario.equals("configopts")) {
            ArrayNode options = info.putArray("configOptions");
            ObjectNode modelOption = options.addObject()
                    .put("type", "select")
                    .put("category", "model")
                    .put("id", "model-option")
                    .put("currentValue", model);
            ArrayNode modelValues = modelOption.putArray("options");
            modelValues.addObject().put("value", "m1").put("name", "Model One");
            modelValues.addObject().put("value", "m2").put("name", "Model Two");
            ObjectNode modeOption = options.addObject()
                    .put("type", "select")
                    .put("category", "mode")
                    .put("id", "mode-option")
                    .put("currentValue", mode);
            ArrayNode modeValues = modeOption.putArray("options");
            modeValues.addObject().put("value", "ask").put("name", "Ask");
            modeValues.addObject().put("value", "code").put("name", "Code");
            return info;
        }
        ObjectNode models = info.putObject("models").put("currentModelId", model);
        ArrayNode availableModels = models.putArray("availableModels");
        availableModels
                .addObject()
                .put("modelId", "m1")
                .put("name", "Model One")
                .put("description", "fast");
        availableModels
                .addObject()
                .put("modelId", "m2")
                .put("name", "Model Two")
                .put("description", "careful");
        availableModels
                .addObject()
                .put("modelId", "m-broken")
                .put("name", "Broken")
                .put("description", "refused");
        ObjectNode modes = info.putObject("modes").put("currentModeId", mode);
        ArrayNode availableModes = modes.putArray("availableModes");
        availableModes.addObject().put("id", "ask").put("name", "Ask").put("description", "asks first");
        availableModes.addObject().put("id", "code").put("name", "Code").put("description", "edits");
        availableModes.addObject().put("id", "locked").put("name", "Locked").put("description", "refused");
        return info;
    }

    private static void configUpdate(String session, String model, String mode) {
        ObjectNode message = M.createObjectNode().put("jsonrpc", "2.0").put("method", "session/update");
        ObjectNode params = message.putObject("params").put("sessionId", session);
        ObjectNode update = sessionInfo("configopts", session, model, mode);
        update.put("sessionUpdate", "config_option_update");
        params.set("update", update);
        System.out.println(message);
    }

    private static void chunk(String session, String text) {
        update(
                session,
                "agent_message_chunk",
                u -> u.putObject("content").put("type", "text").put("text", text));
    }

    private static void update(String session, String kind, java.util.function.Consumer<ObjectNode> fill) {
        ObjectNode message = M.createObjectNode().put("jsonrpc", "2.0").put("method", "session/update");
        ObjectNode params = message.putObject("params").put("sessionId", session);
        ObjectNode update = params.putObject("update").put("sessionUpdate", kind);
        fill.accept(update);
        System.out.println(message);
    }

    private static void request(long id, String method, ObjectNode params) {
        ObjectNode message =
                M.createObjectNode().put("jsonrpc", "2.0").put("id", id).put("method", method);
        message.set("params", params);
        System.out.println(message);
    }

    private static void endTurn(JsonNode id, String stopReason) {
        if (id != null) {
            result(id, M.createObjectNode().put("stopReason", stopReason));
        }
    }

    private static void result(JsonNode id, ObjectNode result) {
        ObjectNode message = M.createObjectNode().put("jsonrpc", "2.0");
        message.set("id", id);
        message.set("result", result);
        System.out.println(message);
    }

    private static void error(JsonNode id, String text) {
        ObjectNode message = M.createObjectNode().put("jsonrpc", "2.0");
        message.set("id", id);
        message.putObject("error").put("code", -32000).put("message", text);
        System.out.println(message);
    }
}
