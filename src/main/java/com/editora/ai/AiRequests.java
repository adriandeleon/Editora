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
        return streamingRequest(m, model, system, user, MAX_TOKENS, java.util.List.of());
    }

    /** The full form: an explicit output cap + optional stop sequences (inline completion stops at EOL). */
    public static ObjectNode streamingRequest(
            ObjectMapper m,
            String model,
            String system,
            String user,
            int maxTokens,
            java.util.List<String> stopSequences) {
        ObjectNode msg = m.createObjectNode();
        msg.put("role", "user");
        msg.put("content", user);
        ArrayNode messages = m.createArrayNode();
        messages.add(msg);
        ObjectNode r = m.createObjectNode();
        r.put("model", model);
        r.put("max_tokens", maxTokens);
        r.put("stream", true);
        r.put("system", system);
        r.set("messages", messages);
        if (!stopSequences.isEmpty()) {
            ArrayNode stops = m.createArrayNode();
            stopSequences.forEach(stops::add);
            r.set("stop_sequences", stops);
        }
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

    /** The request body for {@code provider}'s dialect (one system prompt + one user turn, streamed). */
    public static ObjectNode requestFor(
            ObjectMapper m,
            AiProvider provider,
            String model,
            String system,
            String user,
            int maxTokens,
            java.util.List<String> stopSequences) {
        return provider == AiProvider.OPENAI
                ? openAiStreamingRequest(m, model, system, user, maxTokens, stopSequences)
                : streamingRequest(m, model, system, user, maxTokens, stopSequences);
    }

    /**
     * An OpenAI-compatible chat-completions request (LM Studio, Ollama, vLLM): the system prompt is a
     * leading {@code system} message and stops go under {@code stop}. A blank {@code model} is omitted
     * entirely — LM Studio then serves the currently loaded model (Ollama requires an explicit name).
     */
    public static ObjectNode openAiStreamingRequest(
            ObjectMapper m,
            String model,
            String system,
            String user,
            int maxTokens,
            java.util.List<String> stopSequences) {
        ObjectNode sys = m.createObjectNode();
        sys.put("role", "system");
        sys.put("content", system);
        ObjectNode msg = m.createObjectNode();
        msg.put("role", "user");
        msg.put("content", user);
        ArrayNode messages = m.createArrayNode();
        messages.add(sys);
        messages.add(msg);
        ObjectNode r = m.createObjectNode();
        if (model != null && !model.isBlank()) {
            r.put("model", model.strip());
        }
        r.put("max_tokens", maxTokens);
        r.put("stream", true);
        r.set("messages", messages);
        if (!stopSequences.isEmpty()) {
            ArrayNode stops = m.createArrayNode();
            stopSequences.forEach(stops::add);
            r.set("stop", stops);
        }
        return r;
    }

    /** A minimal, <em>non-streaming</em> request for the Settings connection check (dialect-aware). */
    public static ObjectNode pingRequest(ObjectMapper m, AiProvider provider, String model) {
        ObjectNode r =
                requestFor(m, provider, model, "You are a health check.", "Reply with OK.", 1, java.util.List.of());
        r.put("stream", false);
        return r;
    }

    /** Output cap for inline completion — one short line, never an essay. */
    public static final int COMPLETION_MAX_TOKENS = 128;

    public static String completionSystem() {
        return "You are an inline code-completion engine. The user gives text before and after a <CURSOR>"
                + " marker. Reply with ONLY the text to insert at the cursor — no explanation, no markdown"
                + " fences, no repetition of the surrounding text. Prefer completing the current line or"
                + " statement. Reply with nothing when no useful continuation exists.";
    }

    public static String completionUser(String language, String prefix, String suffix) {
        return "Language: " + (language == null || language.isEmpty() ? "plain text" : language) + "\n\n" + prefix
                + "<CURSOR>" + suffix;
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
