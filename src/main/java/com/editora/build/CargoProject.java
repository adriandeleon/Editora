package com.editora.build;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.toml.TomlMapper;

/**
 * A parsed {@code Cargo.toml} — the bits the build-tool integration needs: the {@code [package] name} (for the
 * Settings "Found: …" status; {@code null} for a virtual {@code [workspace]} manifest with no package), and
 * the explicit {@code [[bin]]}/{@code [[example]]} target names (each runnable as {@code cargo run --bin X} /
 * {@code --example X}). Parsed with the existing Jackson {@code TomlMapper} via {@code readTree} (no POJO
 * binding → no {@code module-info} {@code opens}). Pure. Implicit autobins ({@code src/main.rs},
 * {@code src/bin/*.rs}) are deliberately not listed — the plain {@code cargo run} covers the default binary.
 */
public record CargoProject(String packageName, List<String> binNames, List<String> exampleNames) {

    private static final TomlMapper MAPPER = new TomlMapper();

    /** Parses {@code Cargo.toml} text. Throws if the TOML is malformed (the caller reports it distinctly). */
    public static CargoProject parse(String cargoTomlText) {
        try {
            JsonNode root = MAPPER.readTree(cargoTomlText);
            String name = null;
            if (root != null) {
                JsonNode pkgName = root.path("package").path("name");
                if (pkgName.isTextual() && !pkgName.asText().isBlank()) {
                    name = pkgName.asText();
                }
            }
            return new CargoProject(name, targetNames(root, "bin"), targetNames(root, "example"));
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid Cargo.toml: " + e.getMessage(), e);
        }
    }

    /** The {@code name} of each table in a {@code [[bin]]}/{@code [[example]]} array of tables, in order. */
    private static List<String> targetNames(JsonNode root, String key) {
        List<String> out = new ArrayList<>();
        if (root == null) {
            return out;
        }
        JsonNode arr = root.get(key);
        if (arr != null && arr.isArray()) {
            for (JsonNode table : arr) {
                JsonNode n = table.path("name");
                if (n.isTextual() && !n.asText().isBlank()) {
                    out.add(n.asText());
                }
            }
        }
        return List.copyOf(out);
    }
}
