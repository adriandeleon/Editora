package com.editora.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Pure builders for the Anthropic Messages API requests + the feature prompts (commit message /
 * explain / rewrite). JSON is mapped by hand (Jackson tree API), so no {@code module-info opens};
 * everything here is side-effect-free and unit-tested. {@link AiClient} sends the request.
 */
public final class AiRequests {

    /** Output cap per response — generous for explanations, far above any commit message. */
    public static final int MAX_TOKENS = 8192;

    /** Diffs/selections larger than this are truncated so a huge staged diff can't blow the request. */
    public static final int MAX_INPUT_CHARS = 120_000;

    private AiRequests() {}

    /** A streaming Messages API request: one system prompt + one user turn. */
    public static ObjectNode streamingRequest(ObjectMapper m, String model, String system, String user) {
        ObjectNode msg = m.createObjectNode();
        msg.put("role", "user");
        msg.put("content", user);
        ArrayNode messages = m.createArrayNode();
        messages.add(msg);
        ObjectNode r = m.createObjectNode();
        r.put("model", model);
        r.put("max_tokens", MAX_TOKENS);
        r.put("stream", true);
        r.put("system", system);
        r.set("messages", messages);
        return r;
    }

    // --- feature prompts ------------------------------------------------------------------------------

    public static String commitMessageSystem() {
        return "You write git commit messages. Reply with ONLY the commit message: an imperative subject"
                + " line under 72 characters, then (only when the change genuinely needs it) a blank line"
                + " and a short body wrapped at 72 columns explaining what and why. No markdown, no code"
                + " fences, no quotes, no trailing commentary.";
    }

    public static String commitMessageUser(String stagedDiff) {
        return "Write the commit message for this staged diff:\n\n" + truncate(stagedDiff);
    }

    public static String explainSystem() {
        return "You are a senior engineer explaining code to a colleague. Explain what the code does, how"
                + " it works, and anything surprising (edge cases, pitfalls, hidden costs). Be concrete and"
                + " concise; use Markdown with short sections.";
    }

    public static String explainUser(String language, String code) {
        return "Explain this " + (language == null || language.isEmpty() ? "code" : language) + " snippet:\n\n```"
                + safeLang(language) + "\n" + truncate(code) + "\n```";
    }

    public static String rewriteSystem() {
        return "You rewrite code exactly as instructed. Reply with ONLY the rewritten code — no markdown"
                + " fences, no explanation, no leading or trailing commentary. Preserve the original"
                + " indentation style and only change what the instruction requires.";
    }

    public static String rewriteUser(String language, String instruction, String code) {
        return "Instruction: " + instruction + "\n\nRewrite this "
                + (language == null || language.isEmpty() ? "code" : language) + ":\n\n" + truncate(code);
    }

    // --- helpers --------------------------------------------------------------------------------------

    /** Strips one surrounding Markdown code fence if the model added one anyway (defensive). */
    public static String stripCodeFence(String text) {
        String t = text == null ? "" : text.strip();
        if (!t.startsWith("```")) {
            return t;
        }
        int firstNewline = t.indexOf('\n');
        int lastFence = t.lastIndexOf("```");
        if (firstNewline < 0 || lastFence <= firstNewline) {
            return t;
        }
        return t.substring(firstNewline + 1, lastFence).stripTrailing();
    }

    /** Caps huge inputs (a giant staged diff / selection) with an explicit truncation marker. */
    static String truncate(String text) {
        String t = text == null ? "" : text;
        return t.length() <= MAX_INPUT_CHARS ? t : t.substring(0, MAX_INPUT_CHARS) + "\n…(truncated)";
    }

    private static String safeLang(String language) {
        return language == null ? "" : language.replaceAll("[^a-zA-Z0-9+#-]", "");
    }
}
