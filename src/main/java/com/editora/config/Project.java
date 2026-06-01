package com.editora.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A named project: a single root folder whose session state (open files, layout, folds) is persisted
 * separately under {@code ~/.editora/projects/<id>.json}. See {@link ProjectManager}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Project(String id, String name, String root) {

    public Project {
        id = id == null ? "" : id;
        name = name == null ? "" : name;
        root = root == null ? "" : root;
    }

    // Jackson needs a no-arg-friendly shape for records; the canonical constructor + @JsonProperty-less
    // record components deserialize fine with jackson-databind 2.12+.
}
