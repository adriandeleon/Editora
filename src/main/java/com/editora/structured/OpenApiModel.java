package com.editora.structured;

import java.util.List;

/**
 * A neutral, toolkit-free model of an OpenAPI/Swagger document, built by {@link OpenApiParser} from the
 * parsed JSON/YAML tree and rendered by the editor's {@code OpenApiDoc} into browsable API docs. Kept
 * deliberately shallow (the fields a reader scans — title, servers, endpoints, params, responses,
 * schemas); {@code $ref}s are shown by their simple name rather than deep-resolved.
 */
public record OpenApiModel(
        String title,
        String version,
        String description,
        List<Server> servers,
        List<PathItem> paths,
        List<Schema> schemas) {

    public record Server(String url, String description) {}

    public record PathItem(String path, List<Operation> operations) {}

    public record Operation(
            String method, // upper-case HTTP verb
            String summary,
            String description,
            boolean deprecated,
            List<Param> parameters,
            List<Response> responses) {}

    public record Param(String name, String in, boolean required, String type, String description) {}

    public record Response(String code, String description) {}

    public record Schema(String name, String type, List<Property> properties) {}

    public record Property(String name, String type, boolean required) {}
}
