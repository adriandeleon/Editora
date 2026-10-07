package com.editora.lsp;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * The jdtls source-generation prompts (#741) — Generate toString / hashCode+equals / Constructors /
 * Getters and Setters / Delegate Methods, and Override-Implement Methods.
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
                "methods"),
        /** The check response is a bare array of accessor fields, so there is no items field. */
        ACCESSORS(
                "java.action.generateAccessorsPrompt",
                "java/resolveUnimplementedAccessors",
                "java/generateAccessors",
                null),
        /** Two levels — a field, then that field's methods; see {@link #delegateFields}. */
        DELEGATE_METHODS(
                "java.action.generateDelegateMethodsPrompt",
                "java/checkDelegateMethodsStatus",
                "java/generateDelegateMethods",
                "delegateFields");

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

        /** The array in the check response holding the choosable candidates; null when the response is the array. */
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
        if (kind == Kind.ACCESSORS) {
            return accessorCandidates(checkResponse);
        }
        if (kind == null || kind == Kind.DELEGATE_METHODS || checkResponse == null || !checkResponse.isJsonObject()) {
            return out; // delegate methods are chosen per field: delegateFields
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
     * The fields a {@code java/resolveUnimplementedAccessors} response lists, all pre-selected: the action
     * without a prompt generates every one, so the picker starts from the same result and lets the user
     * take fields out. The label names which accessors the field is still missing.
     */
    private static List<Candidate> accessorCandidates(JsonElement checkResponse) {
        List<Candidate> out = new ArrayList<>();
        if (checkResponse == null || !checkResponse.isJsonArray()) {
            return out;
        }
        for (JsonElement e : checkResponse.getAsJsonArray()) {
            if (e == null || !e.isJsonObject()) {
                continue;
            }
            JsonObject o = e.getAsJsonObject();
            String name = string(o, "fieldName");
            StringBuilder sb = new StringBuilder(name == null || name.isBlank() ? "?" : name);
            String type = string(o, "typeName");
            if (type != null && !type.isBlank()) {
                sb.append(" : ").append(type);
            }
            boolean getter = bool(o, "generateGetter");
            boolean setter = bool(o, "generateSetter");
            if (getter || setter) {
                sb.append("  (")
                        .append(getter ? "get" : "")
                        .append(getter && setter ? ", " : "")
                        .append(setter ? "set" : "")
                        .append(')');
            }
            out.add(new Candidate(sb.toString(), true, e));
        }
        return out;
    }

    /** A field whose methods can be delegated to: its label, its untouched JSON, and its methods. */
    public record DelegateField(String label, JsonElement field, List<Candidate> methods) {}

    /**
     * The fields a {@code java/checkDelegateMethodsStatus} response offers, each with the methods not yet
     * delegated. A field with no method left is dropped, so an empty list means there is nothing to generate.
     */
    public static List<DelegateField> delegateFields(JsonElement checkResponse) {
        List<DelegateField> out = new ArrayList<>();
        if (checkResponse == null || !checkResponse.isJsonObject()) {
            return out;
        }
        JsonElement arr = checkResponse.getAsJsonObject().get(Kind.DELEGATE_METHODS.itemsField());
        if (arr == null || !arr.isJsonArray()) {
            return out;
        }
        for (JsonElement e : arr.getAsJsonArray()) {
            if (e == null || !e.isJsonObject()) {
                continue;
            }
            JsonElement field = e.getAsJsonObject().get("field");
            JsonElement methods = e.getAsJsonObject().get("delegateMethods");
            if (field == null || !field.isJsonObject() || methods == null || !methods.isJsonArray()) {
                continue;
            }
            List<Candidate> rows = new ArrayList<>();
            for (JsonElement m : methods.getAsJsonArray()) {
                if (m != null && m.isJsonObject()) {
                    rows.add(new Candidate(label(m.getAsJsonObject()), false, m));
                }
            }
            if (!rows.isEmpty()) {
                out.add(new DelegateField(label(field.getAsJsonObject()), field, List.copyOf(rows)));
            }
        }
        return out;
    }

    /** The parameter of {@code java/generateDelegateMethods}: one entry per chosen method of {@code field}. */
    public static JsonObject delegateParams(JsonElement actionParams, DelegateField field, List<Candidate> chosen) {
        JsonArray entries = new JsonArray();
        for (Candidate c : chosen) {
            JsonObject entry = new JsonObject();
            entry.add("field", field.field());
            entry.add("delegateMethod", c.raw());
            entries.add(entry);
        }
        JsonObject params = new JsonObject();
        params.add("context", actionParams);
        params.add("delegateEntries", entries);
        return params;
    }

    /** A check response together with the choices rendered from it. Constructors need the response's
     *  separate {@code constructors} array when the generate request is assembled. */
    public record Plan(List<Candidate> candidates, JsonElement status) {}

    public static Plan plan(Kind kind, JsonElement checkResponse) {
        return new Plan(List.copyOf(candidates(kind, checkResponse)), checkResponse);
    }

    /**
     * The super constructors a {@code java/checkConstructorsStatus} response offers, in the server's order,
     * the first one pre-selected. Each selected one yields one generated constructor, so with several on
     * offer (an exception subclass has five) the user has to choose — sending them all back generated five.
     */
    public static List<Candidate> constructorCandidates(JsonElement checkResponse) {
        List<Candidate> out = new ArrayList<>();
        if (checkResponse == null || !checkResponse.isJsonObject()) {
            return out;
        }
        JsonElement arr = checkResponse.getAsJsonObject().get("constructors");
        if (arr == null || !arr.isJsonArray()) {
            return out;
        }
        for (JsonElement e : arr.getAsJsonArray()) {
            if (e != null && e.isJsonObject()) {
                out.add(new Candidate(label(e.getAsJsonObject()), out.isEmpty(), e));
            }
        }
        return out;
    }

    /** {@link #generateParams(Kind, JsonElement, List, JsonElement, List)} with every super constructor. */
    public static JsonObject generateParams(
            Kind kind, JsonElement actionParams, List<Candidate> chosen, JsonElement checkResponse) {
        return generateParams(kind, actionParams, chosen, checkResponse, null);
    }

    /**
     * The single object parameter JDT LS expects for a generate request. Each handler receives one DTO,
     * rather than positional JSON-RPC parameters; using an array makes LSP4J reject the request while parsing.
     * {@code chosenConstructors} (constructors only) limits the super constructors to the user's choice;
     * null sends every one the check response listed.
     */
    public static JsonObject generateParams(
            Kind kind,
            JsonElement actionParams,
            List<Candidate> chosen,
            JsonElement checkResponse,
            List<Candidate> chosenConstructors) {
        JsonArray picked = new JsonArray();
        for (Candidate c : chosen) {
            picked.add(c.raw());
        }
        JsonObject params = new JsonObject();
        params.add("context", actionParams);
        switch (kind) {
            case TO_STRING -> params.add("fields", picked);
            case HASH_CODE_EQUALS -> {
                params.add("fields", picked);
                params.addProperty("regenerate", false); // never silently replace existing methods
            }
            case CONSTRUCTORS -> {
                JsonElement constructors = checkResponse != null && checkResponse.isJsonObject()
                        ? checkResponse.getAsJsonObject().get("constructors")
                        : null;
                if (chosenConstructors != null) {
                    JsonArray only = new JsonArray();
                    chosenConstructors.forEach(c -> only.add(c.raw()));
                    constructors = only;
                }
                params.add(
                        "constructors",
                        constructors != null && constructors.isJsonArray() ? constructors : new JsonArray());
                params.add("fields", picked);
            }
            case OVERRIDE_METHODS -> params.add("overridableMethods", picked);
            case ACCESSORS -> params.add("accessors", picked);
            case DELEGATE_METHODS ->
                throw new IllegalArgumentException("delegate methods are assembled by delegateParams");
        }
        return params;
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
