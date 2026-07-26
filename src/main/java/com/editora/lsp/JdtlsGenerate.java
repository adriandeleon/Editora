package com.editora.lsp;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * The jdtls source-generation prompts (#741) — Generate toString / hashCode+equals / Constructors, and
 * Override-Implement Methods.
 *
 * <p>These are <b>client-driven</b> flows, which is why they need code here at all. Once
 * {@code extendedClientCapabilities} declares the matching {@code *PromptSupport} flag, jdtls stops
 * answering the code action with an edit and instead returns a {@code java.action.*Prompt} command that
 * <em>the client</em> must carry out: run a "check" request to get the candidates, let the user choose, then
 * run a "generate" request with the chosen ones. Editora never sends these as
 * {@code workspace/executeCommand} — they are custom {@code java/…} JSON-RPC requests, the same shape as
 * {@code java/classFileContents} (#665), and they never appear in {@code executeCommandProvider.commands}.
 *
 * <p><b>Deliberately shape-agnostic.</b> The candidate objects are passed back to the generate request
 * <em>verbatim</em> rather than being parsed into DTOs and rebuilt. jdtls keys them by an opaque
 * {@code bindingKey}, and each family carries slightly different extra fields ({@code parameters},
 * {@code declaringClass}, …); round-tripping the original JSON means a field we never modelled cannot be
 * lost, and a jdtls version that adds one needs no change here. Only {@code name}/{@code type} are read, and
 * only to build the label the user sees.
 *
 * <p>Pure: no toolkit, no I/O. The caller performs the two requests and supplies the user's choice.
 */
public final class JdtlsGenerate {

    private JdtlsGenerate() {}

    /** The client-side command a code action carries, and the two requests that fulfil it. */
    public enum Kind {
        TO_STRING("java.action.generateToStringPrompt", "java/checkToStringStatus", "java/generateToString", "fields"),
        HASH_CODE_EQUALS(
                "java.action.hashCodeEqualsPrompt",
                "java/checkHashCodeEqualsStatus",
                "java/generateHashCodeEquals",
                "fields"),
        CONSTRUCTORS(
                "java.action.generateConstructorsPrompt",
                "java/checkConstructorsStatus",
                "java/generateConstructors",
                "fields"),
        OVERRIDE_METHODS(
                "java.action.overrideMethodsPrompt",
                "java/listOverridableMethods",
                "java/addOverridableMethods",
                "methods");

        private final String command;
        private final String checkRequest;
        private final String generateRequest;
        private final String itemsField;

        Kind(String command, String checkRequest, String generateRequest, String itemsField) {
            this.command = command;
            this.checkRequest = checkRequest;
            this.generateRequest = generateRequest;
            this.itemsField = itemsField;
        }

        public String command() {
            return command;
        }

        public String checkRequest() {
            return checkRequest;
        }

        public String generateRequest() {
            return generateRequest;
        }

        /** The array in the check response holding the choosable candidates. */
        public String itemsField() {
            return itemsField;
        }
    }

    /** The {@link Kind} for a code action's command id, or null when it isn't one of these prompts. */
    public static Kind forCommand(String command) {
        if (command == null) {
            return null;
        }
        for (Kind k : Kind.values()) {
            if (k.command().equals(command)) {
                return k;
            }
        }
        return null;
    }

    /**
     * One choosable candidate: the {@code label} shown to the user, whether jdtls pre-selected it, and the
     * untouched JSON handed back to the generate request.
     */
    public record Candidate(String label, boolean preselected, JsonElement raw) {}

    /**
     * The candidates in a check response, in the server's order.
     *
     * <p>Returns an empty list rather than throwing for any response that isn't the expected shape — a
     * malformed or unexpected answer must degrade into "nothing to offer", never an exception on the FX
     * thread.
     */
    public static List<Candidate> candidates(Kind kind, JsonElement checkResponse) {
        List<Candidate> out = new ArrayList<>();
        if (kind == null || checkResponse == null || !checkResponse.isJsonObject()) {
            return out;
        }
        JsonElement arr = checkResponse.getAsJsonObject().get(kind.itemsField());
        if (arr == null || !arr.isJsonArray()) {
            return out;
        }
        for (JsonElement e : arr.getAsJsonArray()) {
            if (e == null || !e.isJsonObject()) {
                continue;
            }
            JsonObject o = e.getAsJsonObject();
            out.add(new Candidate(label(o), bool(o, "isSelected"), e));
        }
        return out;
    }

    /**
     * A display label: {@code name(paramTypes) : type}, matching how the Structure outline renders a member.
     * Falls back to whatever is present, and to {@code "?"} for an object with no name at all, so a row is
     * never blank.
     */
    static String label(JsonObject o) {
        String name = string(o, "name");
        if (name == null || name.isBlank()) {
            return "?";
        }
        StringBuilder sb = new StringBuilder(name);
        JsonElement params = o.get("parameters");
        if (params != null && params.isJsonArray()) {
            sb.append('(');
            JsonArray a = params.getAsJsonArray();
            for (int i = 0; i < a.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(a.get(i).isJsonPrimitive() ? a.get(i).getAsString() : "?");
            }
            sb.append(')');
        }
        String type = string(o, "type");
        if (type != null && !type.isBlank()) {
            sb.append(" : ").append(type);
        }
        return sb.toString();
    }

    /**
     * The parameters for the generate request: the original {@code CodeActionParams} the prompt command
     * carried, plus the chosen candidates (and, for constructors, the constructors the check reported).
     *
     * <p>The argument order is positional and server-defined; each is what jdtls's handler for that request
     * expects. {@code java/generateConstructors} takes <em>two</em> lists — the constructors to base the
     * generated ones on, then the fields to assign — which is why {@code extras} exists.
     */
    public static List<Object> generateParams(Kind kind, JsonElement actionParams, List<Candidate> chosen) {
        List<Object> args = new ArrayList<>();
        args.add(actionParams);
        if (kind == Kind.CONSTRUCTORS) {
            args.add(new JsonArray()); // constructors: an empty list means "the default", matching VS Code
        }
        JsonArray picked = new JsonArray();
        for (Candidate c : chosen) {
            picked.add(c.raw());
        }
        args.add(picked);
        if (kind == Kind.HASH_CODE_EQUALS) {
            args.add(Boolean.FALSE); // regenerate: we never silently replace existing methods
        }
        return args;
    }

    private static String string(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    private static boolean bool(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() && e.getAsBoolean();
    }
}
