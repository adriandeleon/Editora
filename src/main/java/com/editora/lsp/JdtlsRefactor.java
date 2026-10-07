package com.editora.lsp;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * The jdtls refactorings the <em>client</em> has to carry out: Move (a file to another package, a type, a
 * static member, an instance method), Extract Interface and Change Signature.
 *
 * <p>jdtls answers these code actions with the command {@code java.action.applyRefactoringCommand} and the
 * arguments {@code [name, CodeActionParams, info?]}. Like the generate prompts ({@link JdtlsGenerate}) that
 * command is not something the server executes: the client asks for the possible destinations, lets the user
 * choose, and then requests the edit with a custom {@code java/…} request. Move and Extract Interface are
 * offered only once {@code extendedClientCapabilities} declares {@code moveRefactoringSupport} and
 * {@code extractInterfaceSupport}; Change Signature is offered regardless.
 *
 * <p>The shapes here were read off jdtls 1.61 ({@code MoveHandler}, {@code GetRefactorEditHandler},
 * {@code ChangeSignatureHandler}) and checked against the running server by {@code JdtlsRefactorProbeTest}.
 * Server objects that are sent back (a package node, a symbol, a binding) travel verbatim.
 *
 * <p>Pure: no toolkit, no I/O.
 */
public final class JdtlsRefactor {

    private JdtlsRefactor() {}

    /** The client-side command every one of these code actions carries. */
    public static final String COMMAND = "java.action.applyRefactoringCommand";

    public static final String MOVE_FILE = "moveFile";
    public static final String MOVE_INSTANCE_METHOD = "moveInstanceMethod";
    public static final String MOVE_STATIC_MEMBER = "moveStaticMember";
    public static final String MOVE_TYPE = "moveType";
    public static final String EXTRACT_INTERFACE = "extractInterface";
    public static final String CHANGE_SIGNATURE = "changeSignature";

    /** One refactoring command: its name, the {@code CodeActionParams} it was offered for, and its info. */
    public record Request(String name, JsonElement params, JsonObject info) {

        /** A string field of the info object, or null. */
        public String info(String key) {
            return info == null ? null : string(info, key);
        }

        /** The document the action was offered in. */
        public String documentUri() {
            if (params == null || !params.isJsonObject()) {
                return null;
            }
            JsonElement doc = params.getAsJsonObject().get("textDocument");
            return doc != null && doc.isJsonObject() ? string(doc.getAsJsonObject(), "uri") : null;
        }

        /** Whether a Move Type action may go to {@code kind} ({@code newFile} or {@code class}). */
        public boolean supportsDestination(String kind) {
            JsonElement kinds = info == null ? null : info.get("supportedDestinationKinds");
            if (kinds == null || !kinds.isJsonArray()) {
                return false;
            }
            for (JsonElement e : kinds.getAsJsonArray()) {
                if (e.isJsonPrimitive() && kind.equals(e.getAsString())) {
                    return true;
                }
            }
            return false;
        }
    }

    /** The request a command carries, or null when it is not {@link #COMMAND} or has no usable arguments. */
    public static Request parse(String commandId, List<JsonElement> arguments) {
        if (!COMMAND.equals(commandId) || arguments == null || arguments.size() < 2) {
            return null;
        }
        JsonElement name = arguments.get(0);
        if (name == null || !name.isJsonPrimitive()) {
            return null;
        }
        JsonElement info = arguments.size() > 2 ? arguments.get(2) : null;
        return new Request(
                name.getAsString(),
                arguments.get(1),
                info != null && info.isJsonObject() ? info.getAsJsonObject() : null);
    }

    /** A place something can be moved to: what the user sees, and the server object naming it. */
    public record Destination(String label, String detail, JsonElement raw) {}

    /** The {@code errorMessage} of a destinations or refactor-edit answer, or null. */
    public static String errorMessage(JsonElement response) {
        if (response == null || !response.isJsonObject()) {
            return null;
        }
        String message = string(response.getAsJsonObject(), "errorMessage");
        return message == null || message.isBlank() ? null : message;
    }

    /**
     * The packages of a {@code java/getMoveDestinations} answer (or the {@code destinationResponse} of an
     * Extract Interface check). {@code includeCurrent} keeps the package the file is already in — a move
     * there does nothing, but it is the usual home of an extracted interface, so it is then listed first.
     */
    public static List<Destination> packages(JsonElement response, boolean includeCurrent) {
        List<Destination> out = new ArrayList<>();
        for (JsonElement e : destinations(response)) {
            if (!e.isJsonObject()) {
                continue;
            }
            JsonObject o = e.getAsJsonObject();
            String name = string(o, "displayName");
            if (name == null) {
                continue;
            }
            boolean current = bool(o, "isParentOfSelectedFile");
            if (current && !includeCurrent) {
                continue;
            }
            Destination d = new Destination(name, string(o, "path"), e);
            if (current) {
                out.add(0, d);
            } else {
                out.add(d);
            }
        }
        return out;
    }

    /** The parameters and fields an instance method can move to ({@code name : Type}). */
    public static List<Destination> instanceTargets(JsonElement response) {
        List<Destination> out = new ArrayList<>();
        for (JsonElement e : destinations(response)) {
            if (!e.isJsonObject()) {
                continue;
            }
            JsonObject o = e.getAsJsonObject();
            String name = string(o, "name");
            if (name == null) {
                continue;
            }
            String type = string(o, "type");
            out.add(new Destination(type == null ? name : name + " : " + type, bool(o, "isField") ? "field" : "", e));
        }
        return out;
    }

    /**
     * The types a {@code java/searchSymbols} answer lists, without {@code excludeQualifiedName} — the type
     * a member already lives in.
     */
    public static List<Destination> types(JsonElement response, String excludeQualifiedName) {
        List<Destination> out = new ArrayList<>();
        if (response == null || !response.isJsonArray()) {
            return out;
        }
        for (JsonElement e : response.getAsJsonArray()) {
            if (e == null || !e.isJsonObject()) {
                continue;
            }
            JsonObject o = e.getAsJsonObject();
            String name = string(o, "name");
            if (name == null) {
                continue;
            }
            String container = string(o, "containerName");
            String qualified = container == null || container.isBlank() ? name : container + "." + name;
            if (!qualified.equals(excludeQualifiedName)) {
                out.add(new Destination(name, container == null ? "" : container, e));
            }
        }
        return out;
    }

    private static JsonArray destinations(JsonElement response) {
        if (response != null && response.isJsonObject()) {
            JsonElement arr = response.getAsJsonObject().get("destinations");
            if (arr != null && arr.isJsonArray()) {
                return arr.getAsJsonArray();
            }
        }
        return new JsonArray();
    }

    /**
     * The parameter of {@code java/getMoveDestinations} and {@code java/move}. {@code params} is null for a
     * file move, {@code destination} null when asking for destinations or moving a type to a new file.
     */
    public static JsonObject moveParams(
            String moveKind, String sourceUri, JsonElement params, JsonElement destination) {
        JsonObject out = new JsonObject();
        out.addProperty("moveKind", moveKind);
        JsonArray uris = new JsonArray();
        uris.add(sourceUri);
        out.add("sourceUris", uris);
        if (params != null) {
            out.add("params", params);
        }
        if (destination != null) {
            out.add("destination", destination);
        }
        out.addProperty("updateReferences", true);
        return out;
    }

    /** The parameter of {@code java/searchSymbols}: every source type of one project. */
    public static JsonObject searchTypesParams(String projectName) {
        JsonObject out = new JsonObject();
        out.addProperty("query", "*");
        if (projectName != null) {
            out.addProperty("projectName", projectName);
        }
        out.addProperty("sourceOnly", true);
        return out;
    }

    /** The parameter of {@code java/getRefactorEdit}. */
    public static JsonObject refactorEditParams(
            String command, JsonElement context, int tabSize, boolean insertSpaces, JsonArray commandArguments) {
        JsonObject options = new JsonObject();
        options.addProperty("tabSize", tabSize);
        options.addProperty("insertSpaces", insertSpaces);
        JsonObject out = new JsonObject();
        out.addProperty("command", command);
        out.add("context", context);
        out.add("options", options);
        out.add("commandArguments", commandArguments == null ? new JsonArray() : commandArguments);
        return out;
    }

    /**
     * The {@code WorkspaceEdit} inside an answer: {@code java/move} and {@code java/getRefactorEdit} wrap it
     * ({@code {edit, command, errorMessage}}), the generate requests answer with the edit itself.
     */
    public static JsonElement editOf(JsonElement response) {
        if (response == null || !response.isJsonObject()) {
            return null;
        }
        JsonObject o = response.getAsJsonObject();
        if (o.has("changes") || o.has("documentChanges")) {
            return o;
        }
        JsonElement edit = o.get("edit");
        return edit != null && edit.isJsonObject() ? edit : null;
    }

    // --- Extract Interface ------------------------------------------------------------------------

    /** The methods a {@code java/checkExtractInterfaceStatus} answer offers; {@code raw} is the handle id. */
    public static List<JdtlsGenerate.Candidate> interfaceMembers(JsonElement status) {
        List<JdtlsGenerate.Candidate> out = new ArrayList<>();
        JsonElement arr = status != null && status.isJsonObject()
                ? status.getAsJsonObject().get("members")
                : null;
        if (arr == null || !arr.isJsonArray()) {
            return out;
        }
        for (JsonElement e : arr.getAsJsonArray()) {
            if (e == null || !e.isJsonObject()) {
                continue;
            }
            JsonObject o = e.getAsJsonObject();
            JsonElement handle = o.get("handleIdentifier");
            if (handle == null || !handle.isJsonPrimitive()) {
                continue;
            }
            JsonObject shown = new JsonObject();
            shown.add("name", o.get("name"));
            shown.add("parameters", o.get("parameters"));
            shown.add("type", o.get("typeName"));
            out.add(new JdtlsGenerate.Candidate(JdtlsGenerate.label(shown), true, handle));
        }
        return out;
    }

    /** The class the interface is extracted from. */
    public static String subTypeName(JsonElement status) {
        return status != null && status.isJsonObject() ? string(status.getAsJsonObject(), "subTypeName") : null;
    }

    /** The packages the new interface can be created in, the class's own package first. */
    public static List<Destination> interfacePackages(JsonElement status) {
        JsonElement response = status != null && status.isJsonObject()
                ? status.getAsJsonObject().get("destinationResponse")
                : null;
        return packages(response, true);
    }

    /** {@code commandArguments} of the Extract Interface edit: member handles, interface name, package. */
    public static JsonArray extractInterfaceArguments(
            List<JdtlsGenerate.Candidate> members, String interfaceName, Destination pkg) {
        JsonArray handles = new JsonArray();
        members.forEach(m -> handles.add(m.raw()));
        JsonArray out = new JsonArray();
        out.add(handles);
        out.add(interfaceName);
        out.add(pkg.raw());
        return out;
    }

    /** Whether {@code name} can name a Java type. */
    public static boolean isTypeName(String name) {
        if (name == null || name.isEmpty() || !Character.isJavaIdentifierStart(name.charAt(0))) {
            return false;
        }
        return name.chars().allMatch(Character::isJavaIdentifierPart);
    }

    // --- Change Signature -------------------------------------------------------------------------

    private static final Set<String> VISIBILITY = Set.of("public", "protected", "private");

    /**
     * A method's signature as one editable line, built from a {@code java/getChangeSignatureInfo} answer:
     * {@code public String greet(Helper helper, int n) throws IOException}.
     */
    public static String signatureText(JsonElement info) {
        if (info == null || !info.isJsonObject()) {
            return "";
        }
        JsonObject o = info.getAsJsonObject();
        StringBuilder sb = new StringBuilder();
        for (String key : List.of("modifier", "returnType", "methodName")) {
            String v = string(o, key);
            if (v != null && !v.isBlank()) {
                sb.append(sb.isEmpty() ? "" : " ").append(v);
            }
        }
        sb.append('(');
        boolean first = true;
        for (JsonObject p : objects(o.get("parameters"))) {
            sb.append(first ? "" : ", ").append(string(p, "type")).append(' ').append(string(p, "name"));
            first = false;
        }
        sb.append(')');
        first = true;
        for (JsonObject e : objects(o.get("exceptions"))) {
            sb.append(first ? " throws " : ", ").append(string(e, "type"));
            first = false;
        }
        return sb.toString();
    }

    /**
     * {@code commandArguments} of the Change Signature edit for the line the user edited, or null when the
     * line is not a signature (no parameter list, no method name, a parameter without a name).
     *
     * <p>The server tells an existing parameter from a new one by its {@code originalIndex}, which a line of
     * text does not carry, so each parameter is matched back: first by name (its type may have changed),
     * then — for a renamed one — by an unclaimed original of the same type, the one at the same position
     * first. A parameter written with {@code = value} is always new, and so is anything left over; call
     * sites get that value, or {@code null} without one.
     */
    public static JsonArray changeSignatureArguments(JsonElement info, String edited) {
        if (info == null || !info.isJsonObject() || edited == null) {
            return null;
        }
        JsonObject o = info.getAsJsonObject();
        String text = edited.strip();
        int open = text.indexOf('(');
        int close = text.lastIndexOf(')');
        if (open < 0 || close < open) {
            return null;
        }
        List<String> head =
                new ArrayList<>(List.of(text.substring(0, open).strip().split("\\s+")));
        head.removeIf(String::isBlank);
        if (head.isEmpty()) {
            return null;
        }
        String name = head.remove(head.size() - 1);
        if (!isTypeName(name)) {
            return null;
        }
        String modifier = !head.isEmpty() && VISIBILITY.contains(head.get(0)) ? head.remove(0) : "";
        String oldReturnType = string(o, "returnType");
        if (head.isEmpty() && oldReturnType != null && !oldReturnType.isBlank()) {
            return null; // a method lost its return type (only a constructor has none)
        }
        String returnType = head.isEmpty() ? oldReturnType : String.join(" ", head);

        List<JsonObject> originals = objects(o.get("parameters"));
        List<String[]> typed = new ArrayList<>(); // {type, name, default-or-null}
        for (String part : splitTopLevel(text.substring(open + 1, close))) {
            String declaration = part;
            String value = null;
            int eq = part.indexOf('=');
            if (eq >= 0) {
                declaration = part.substring(0, eq);
                value = part.substring(eq + 1).strip();
            }
            declaration = declaration.strip();
            int split = lastWhitespace(declaration);
            if (split < 0) {
                return null;
            }
            String paramName = declaration.substring(split + 1);
            String paramType = declaration.substring(0, split).strip();
            if (!isTypeName(paramName) || paramType.isEmpty()) {
                return null;
            }
            typed.add(new String[] {paramType, paramName, value});
        }
        int[] index = new int[typed.size()];
        boolean[] claimed = new boolean[originals.size()];
        for (int i = 0; i < typed.size(); i++) {
            index[i] = -1;
            for (int j = 0; j < originals.size(); j++) {
                if (!claimed[j] && typed.get(i)[1].equals(string(originals.get(j), "name"))) {
                    index[i] = j;
                    claimed[j] = true;
                    break;
                }
            }
        }
        for (int i = 0; i < typed.size(); i++) {
            if (index[i] >= 0 || typed.get(i)[2] != null) {
                continue; // matched by name, or given a value: a new parameter
            }
            int match = i < originals.size() && !claimed[i] && sameType(typed.get(i), originals.get(i)) ? i : -1;
            for (int j = 0; match < 0 && j < originals.size(); j++) {
                if (!claimed[j] && sameType(typed.get(i), originals.get(j))) {
                    match = j;
                }
            }
            if (match >= 0) {
                index[i] = match;
                claimed[match] = true;
            }
        }
        JsonArray parameters = new JsonArray();
        for (int i = 0; i < typed.size(); i++) {
            JsonObject p = new JsonObject();
            p.addProperty("type", typed.get(i)[0]);
            p.addProperty("name", typed.get(i)[1]);
            String value = typed.get(i)[2];
            p.addProperty("defaultValue", index[i] >= 0 ? "" : value == null || value.isEmpty() ? "null" : value);
            p.addProperty("originalIndex", index[i] < 0 ? -1 : originalIndex(originals.get(index[i]), index[i]));
            parameters.add(p);
        }

        JsonArray exceptions = new JsonArray();
        String tail = text.substring(close + 1).strip();
        if (tail.startsWith("throws")) {
            List<JsonObject> known = objects(o.get("exceptions"));
            for (String type : splitTopLevel(tail.substring("throws".length()))) {
                String wanted = type.strip();
                JsonObject match = known.stream()
                        .filter(k -> wanted.equals(string(k, "type")))
                        .findFirst()
                        .orElse(null);
                if (match == null) {
                    match = new JsonObject();
                    match.addProperty("type", wanted);
                }
                exceptions.add(match);
            }
        } else if (!tail.isEmpty()) {
            return null;
        }

        JsonArray out = new JsonArray();
        out.add(string(o, "methodIdentifier"));
        out.add(false); // keep the old method as a delegate: no
        out.add(name);
        out.add(modifier);
        out.add(returnType == null ? "" : returnType);
        out.add(parameters);
        out.add(exceptions);
        out.add(false); // preview: no, the answer is the edit
        return out;
    }

    private static boolean sameType(String[] typed, JsonObject original) {
        return typed[0].equals(string(original, "type"));
    }

    private static int originalIndex(JsonObject original, int fallback) {
        JsonElement e = original.get("originalIndex");
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? e.getAsInt() : fallback;
    }

    /** Splits at commas outside {@code <>}, {@code ()} and {@code []}; blank pieces are dropped. */
    static List<String> splitTopLevel(String text) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i <= text.length(); i++) {
            char c = i < text.length() ? text.charAt(i) : ',';
            if (c == '<' || c == '(' || c == '[') {
                depth++;
            } else if (c == '>' || c == ')' || c == ']') {
                depth--;
            } else if (c == ',' && (depth <= 0 || i == text.length())) {
                String piece = text.substring(start, i).strip();
                if (!piece.isEmpty()) {
                    out.add(piece);
                }
                start = i + 1;
            }
        }
        return out;
    }

    /** The last whitespace outside {@code <>} — the gap between a parameter's type and its name. */
    private static int lastWhitespace(String declaration) {
        int depth = 0;
        for (int i = declaration.length() - 1; i >= 0; i--) {
            char c = declaration.charAt(i);
            if (c == '>') {
                depth++;
            } else if (c == '<') {
                depth--;
            } else if (Character.isWhitespace(c) && depth == 0) {
                return i;
            }
        }
        return -1;
    }

    private static List<JsonObject> objects(JsonElement array) {
        List<JsonObject> out = new ArrayList<>();
        if (array != null && array.isJsonArray()) {
            for (JsonElement e : array.getAsJsonArray()) {
                if (e != null && e.isJsonObject()) {
                    out.add(e.getAsJsonObject());
                }
            }
        }
        return out;
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
