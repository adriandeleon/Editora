package com.editora.lsp;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * The wire shape of jdtls's {@code java.edit.smartSemicolonDetection} (#746): typing {@code ;} part-way
 * through an expression should put it at the end of the statement instead, so {@code compute(1, 2|)}
 * becomes {@code compute(1, 2);|} rather than {@code compute(1, 2;)}.
 *
 * <p>Established <b>empirically</b> against a live jdtls ({@code JdtlsPasteProbeTest}), because two things
 * about it are invisible from the protocol:
 *
 * <ul>
 *   <li>The params are a <b>stringified JSON</b> {@code {uri, position}} — the same encoding
 *       {@link JdtlsPaste} needs, and passing an object instead fails with a gson type error.
 *   <li>The handler answers <b>{@code null} until {@code java.edit.smartSemicolonDetection.enabled} is
 *       pushed via {@code didChangeConfiguration}</b> — the {@code signatureHelp.enabled} (#674) and
 *       {@code provideFormatter} (#468) pattern again: advertised unconditionally, inert by preference.
 *       Before the push every argument shape answered null, which is indistinguishable from "nothing to
 *       do here" and would have read as the wrong params.
 * </ul>
 *
 * Pure; unit-tested against the captured shapes.
 */
public final class JdtlsSmartSemicolon {

    /** The delegate command id, as advertised in {@code executeCommandProvider.commands}. */
    public static final String COMMAND = "java.edit.smartSemicolonDetection";

    private JdtlsSmartSemicolon() {}

    /** Whether {@code caps} advertises the command (only jdtls does today). */
    public static boolean supported(org.eclipse.lsp4j.ServerCapabilities caps) {
        return caps != null
                && caps.getExecuteCommandProvider() != null
                && caps.getExecuteCommandProvider().getCommands() != null
                && caps.getExecuteCommandProvider().getCommands().contains(COMMAND);
    }

    /** The stringified {@code {uri, position}} params, built with gson so a URI never needs escaping care. */
    public static String paramsJson(String uri, int line, int character) {
        JsonObject position = new JsonObject();
        position.addProperty("line", line);
        position.addProperty("character", character);
        JsonObject params = new JsonObject();
        params.addProperty("uri", uri);
        params.add("position", position);
        return params.toString();
    }

    /**
     * The {@code position} of the answer as {@code {line, character}}, or {@code null} when the server
     * answered nothing (the ordinary case — the caret is already at the statement end, or the preference
     * is off). Defensive rather than throwing: the reply crosses a version boundary we don't control, and
     * a malformed one must degrade to "type the semicolon normally".
     *
     * <p>The numbers arrive as JSON doubles through gson's untyped mapping, so they are read as such and
     * truncated — reading them as ints throws on a {@code 29.0}.
     */
    public static int[] answeredPosition(Object rawResult) {
        if (!(rawResult instanceof JsonElement json) || !json.isJsonObject()) {
            return null;
        }
        JsonElement pos = json.getAsJsonObject().get("position");
        if (pos == null || !pos.isJsonObject()) {
            return null;
        }
        JsonObject p = pos.getAsJsonObject();
        JsonElement line = p.get("line");
        JsonElement character = p.get("character");
        if (line == null || character == null || !line.isJsonPrimitive() || !character.isJsonPrimitive()) {
            return null;
        }
        try {
            int l = (int) line.getAsDouble();
            int c = (int) character.getAsDouble();
            return l < 0 || c < 0 ? null : new int[] {l, c};
        } catch (RuntimeException malformed) {
            return null;
        }
    }
}
