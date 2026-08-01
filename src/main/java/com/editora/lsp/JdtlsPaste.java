package com.editora.lsp;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * The wire shapes of jdtls's {@code java.edit.handlePasteEvent} (#742) — auto-import for pasted code,
 * the one paste behaviour nothing else in the stack can approximate (it needs the type resolver).
 *
 * <p>Both shapes were established <b>empirically</b> against a live jdtls ({@code JdtlsPasteProbeTest}),
 * because both defy the obvious guess:
 *
 * <ul>
 *   <li><b>The params must be sent as a stringified JSON</b>, not a JSON object. An object argument
 *       deserializes to {@code null} server-side and the command answers an internal error naming
 *       {@code PasteEventParams.getLocation()} — the same delegate-command quirk family #746 records
 *       ({@code projectConfigurationUpdate} hangs as a request, {@code organizeImports} silently no-ops
 *       on a position).
 *   <li><b>The answer is not a {@code WorkspaceEdit}</b> — it is VS Code's {@code DocumentPasteEdit}
 *       shape, {@code {insertText, additionalEdit}}. Editora invokes the command <em>after</em> the paste
 *       has landed (unlike VS Code's paste provider, which intercepts it), so only {@code additionalEdit}
 *       — the import insertion — is applied; {@code insertText} just echoes the pasted text.
 * </ul>
 *
 * Pure; unit-tested against the captured shapes.
 */
public final class JdtlsPaste {

    /** The delegate command id, as advertised in {@code executeCommandProvider.commands}. */
    public static final String COMMAND = "java.edit.handlePasteEvent";

    private JdtlsPaste() {}

    /** Whether {@code caps} advertises the paste-event command (only jdtls does today). */
    public static boolean supportsPasteEvent(org.eclipse.lsp4j.ServerCapabilities caps) {
        return caps != null
                && caps.getExecuteCommandProvider() != null
                && caps.getExecuteCommandProvider().getCommands() != null
                && caps.getExecuteCommandProvider().getCommands().contains(COMMAND);
    }

    /**
     * The stringified {@code PasteEventParams}: the <b>post-paste</b> range of the inserted text, the
     * pasted text itself, and the buffer's formatting options. Built with gson (not string concat) so the
     * pasted text's quotes/newlines/unicode are escaped correctly.
     */
    public static String paramsJson(
            String uri,
            int startLine,
            int startChar,
            int endLine,
            int endChar,
            String pastedText,
            int tabSize,
            boolean insertSpaces) {
        JsonObject start = new JsonObject();
        start.addProperty("line", startLine);
        start.addProperty("character", startChar);
        JsonObject end = new JsonObject();
        end.addProperty("line", endLine);
        end.addProperty("character", endChar);
        JsonObject range = new JsonObject();
        range.add("start", start);
        range.add("end", end);
        JsonObject location = new JsonObject();
        location.addProperty("uri", uri);
        location.add("range", range);
        JsonObject fmt = new JsonObject();
        fmt.addProperty("tabSize", tabSize);
        fmt.addProperty("insertSpaces", insertSpaces);
        JsonObject params = new JsonObject();
        params.add("location", location);
        params.addProperty("text", pastedText);
        params.add("formattingOptions", fmt);
        return params.toString();
    }

    /**
     * The {@code additionalEdit} member of the answer — the {@code WorkspaceEdit}-shaped import insertion
     * — or {@code null} when the answer carries none (nothing to import) or isn't the expected shape (a
     * defensive null rather than a throw: the reply crosses a version boundary we don't control).
     */
    public static JsonElement additionalEdit(Object rawResult) {
        if (!(rawResult instanceof JsonElement json) || !json.isJsonObject()) {
            return null;
        }
        JsonElement edit = json.getAsJsonObject().get("additionalEdit");
        return edit == null || edit.isJsonNull() || !edit.isJsonObject() ? null : edit;
    }
}
