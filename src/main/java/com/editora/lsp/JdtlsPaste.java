package com.editora.lsp;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jspecify.annotations.Nullable;

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
    public static boolean supportsPasteEvent(org.eclipse.lsp4j.@Nullable ServerCapabilities caps) {
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
    public static @Nullable JsonElement additionalEdit(@Nullable Object rawResult) {
        if (!(rawResult instanceof JsonElement json) || !json.isJsonObject()) {
            return null;
        }
        JsonElement edit = json.getAsJsonObject().get("additionalEdit");
        return edit == null || edit.isJsonNull() || !edit.isJsonObject() ? null : edit;
    }

    private static final java.util.regex.Pattern IMPORT =
            java.util.regex.Pattern.compile("^\\s*import\\s+(?:static\\s+)?[\\w.$]+\\.([\\w$]+|\\*)\\s*;\\s*$");
    private static final java.util.regex.Pattern IDENTIFIER = java.util.regex.Pattern.compile("[A-Za-z_$][\\w$]*");

    /**
     * {@code edit} (an {@link #additionalEdit}) keeping only the imports of names the pasted text uses. jdtls
     * resolves every missing import of the whole file, so pasting {@code int z = 1;} into a file with an
     * unresolved {@code LocalDate} elsewhere imported it (Java review A11). Only insertions are narrowed — an
     * edit that replaces text is left whole, since dropping a line of it could delete an existing import — and
     * an insertion left with nothing but blank lines is dropped. Null when nothing remains.
     */
    public static @Nullable JsonElement keepPastedImports(@Nullable JsonElement edit, String pastedText) {
        if (edit == null || !edit.isJsonObject()) {
            return edit;
        }
        java.util.Set<String> names = new java.util.HashSet<>();
        java.util.regex.Matcher m = IDENTIFIER.matcher(pastedText == null ? "" : pastedText);
        while (m.find()) {
            names.add(m.group());
        }
        JsonObject out = edit.getAsJsonObject().deepCopy();
        int kept = 0;
        if (out.has("changes") && out.get("changes").isJsonObject()) {
            for (var entry : out.getAsJsonObject("changes").entrySet()) {
                kept += narrow(entry.getValue(), names);
            }
        }
        if (out.has("documentChanges") && out.get("documentChanges").isJsonArray()) {
            for (JsonElement change : out.getAsJsonArray("documentChanges")) {
                if (change.isJsonObject() && change.getAsJsonObject().has("edits")) {
                    kept += narrow(change.getAsJsonObject().get("edits"), names);
                } else {
                    kept++; // a file operation: not ours to judge
                }
            }
        }
        return kept == 0 ? null : out;
    }

    /** Narrows the insertions in {@code edits} (a JSON array of TextEdits) in place; returns how many remain. */
    private static int narrow(JsonElement edits, java.util.Set<String> names) {
        if (!edits.isJsonArray()) {
            return 0;
        }
        com.google.gson.JsonArray array = edits.getAsJsonArray();
        for (int i = array.size() - 1; i >= 0; i--) {
            JsonElement e = array.get(i);
            if (!e.isJsonObject() || !e.getAsJsonObject().has("newText") || !isInsertion(e.getAsJsonObject())) {
                continue;
            }
            String text = e.getAsJsonObject().get("newText").getAsString();
            StringBuilder keep = new StringBuilder();
            for (String line : text.split("\n", -1)) {
                java.util.regex.Matcher im = IMPORT.matcher(line);
                if (im.matches() && !im.group(1).equals("*") && !names.contains(im.group(1))) {
                    continue;
                }
                if (!keep.isEmpty()) {
                    keep.append('\n');
                }
                keep.append(line);
            }
            String narrowed = keep.toString();
            if (!narrowed.equals(text) && narrowed.isBlank()) {
                array.remove(i); // only blank lines left: the imports were all someone else's
            } else {
                e.getAsJsonObject().addProperty("newText", narrowed);
            }
        }
        return array.size();
    }

    private static boolean isInsertion(JsonObject edit) {
        JsonElement range = edit.get("range");
        if (range == null || !range.isJsonObject()) {
            return false;
        }
        JsonElement start = range.getAsJsonObject().get("start");
        return start != null && start.equals(range.getAsJsonObject().get("end"));
    }
}
