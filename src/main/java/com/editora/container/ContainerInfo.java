package com.editora.container;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * What {@code docker inspect} / {@code podman inspect} says about a container, as far as running commands in
 * it goes: its name, whether it runs, its bind mounts, and the user a dev container's tools run as.
 *
 * @param user the user to run commands as: a dev container's {@code remoteUser} (from its
 *     {@code devcontainer.metadata} label), else the image's own ({@code Config.User}), else "" for the
 *     container's default
 */
public record ContainerInfo(
        String id, String name, boolean running, ContainerMounts mounts, String user, Map<String, String> labels) {

    /** The label the Dev Containers CLI and VS Code put on a container: the folder it was started for. */
    public static final String LOCAL_FOLDER_LABEL = "devcontainer.local_folder";

    /** The label holding the dev container's merged configuration, a JSON array. */
    public static final String METADATA_LABEL = "devcontainer.metadata";

    /** Parses {@code inspect} output (a JSON array of one object); null when it is not one. Pure. */
    public static ContainerInfo parse(String json) {
        try {
            JsonElement root = JsonParser.parseString(json == null ? "" : json.strip());
            JsonObject c = root.isJsonArray() && !root.getAsJsonArray().isEmpty()
                    ? root.getAsJsonArray().get(0).getAsJsonObject()
                    : root.isJsonObject() ? root.getAsJsonObject() : null;
            if (c == null) {
                return null;
            }
            String id = string(c, "Id");
            String name = string(c, "Name").replaceFirst("^/", "");
            JsonObject state = object(c, "State");
            boolean running = state != null
                    && state.has("Running")
                    && state.get("Running").getAsBoolean();
            JsonObject config = object(c, "Config");
            Map<String, String> labels = new LinkedHashMap<>();
            JsonObject l = config == null ? null : object(config, "Labels");
            if (l != null) {
                l.entrySet()
                        .forEach(e -> labels.put(
                                e.getKey(),
                                e.getValue().isJsonNull() ? "" : e.getValue().getAsString()));
            }
            List<ContainerMounts.Mount> mounts = new ArrayList<>();
            JsonArray ms =
                    c.has("Mounts") && c.get("Mounts").isJsonArray() ? c.getAsJsonArray("Mounts") : new JsonArray();
            for (JsonElement m : ms) {
                JsonObject mount = m.getAsJsonObject();
                if ("bind".equals(string(mount, "Type"))
                        && !string(mount, "Source").isEmpty()
                        && string(mount, "Destination").startsWith("/")) {
                    mounts.add(
                            new ContainerMounts.Mount(Path.of(string(mount, "Source")), string(mount, "Destination")));
                }
            }
            String user = remoteUser(labels.get(METADATA_LABEL));
            if (user.isEmpty() && config != null) {
                user = string(config, "User");
            }
            return new ContainerInfo(
                    id, name.isEmpty() ? id : name, running, new ContainerMounts(mounts), user, Map.copyOf(labels));
        } catch (RuntimeException notInspectOutput) {
            return null;
        }
    }

    /**
     * The {@code remoteUser} (else {@code containerUser}) a dev container's tools run as: the last entry of its
     * metadata array that names one wins, as the Dev Containers CLI merges them. "" when none does. Pure.
     */
    static String remoteUser(String metadata) {
        if (metadata == null || metadata.isBlank()) {
            return "";
        }
        try {
            JsonElement parsed = JsonParser.parseString(metadata);
            JsonArray entries = parsed.isJsonArray() ? parsed.getAsJsonArray() : new JsonArray();
            if (parsed.isJsonObject()) {
                entries.add(parsed);
            }
            String user = "";
            for (JsonElement e : entries) {
                if (!e.isJsonObject()) {
                    continue;
                }
                JsonObject o = e.getAsJsonObject();
                if (o.has("remoteUser")) {
                    user = o.get("remoteUser").getAsString();
                } else if (o.has("containerUser") && user.isEmpty()) {
                    user = o.get("containerUser").getAsString();
                }
            }
            return user;
        } catch (RuntimeException notJson) {
            return "";
        }
    }

    private static String string(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    private static JsonObject object(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonObject() ? o.getAsJsonObject(key) : null;
    }
}
