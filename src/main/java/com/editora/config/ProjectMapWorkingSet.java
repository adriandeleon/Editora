package com.editora.config;

import org.jspecify.annotations.Nullable;

/**
 * A named view of the Project Map, kept per workspace (workspace schema v29): what {@link ProjectMapState} holds —
 * the open folders, the selection, the zoom and pan, and the open code cards with their Structure columns — saved
 * under a name with ⋯ → Working sets ▸ Save current as…, and shown again by picking it, as a restart shows the
 * last session's state. The state's Back/Forward places are not kept: switching to a set is one more place.
 */
public class ProjectMapWorkingSet {

    private String name = "";
    private ProjectMapState state = new ProjectMapState();

    public ProjectMapWorkingSet() {}

    public ProjectMapWorkingSet(String name, ProjectMapState state) {
        setName(name);
        setState(state);
    }

    /** The name the ⋯ menu lists it under; unique within a workspace, compared ignoring case. */
    public String getName() {
        return name;
    }

    public void setName(@Nullable String name) {
        this.name = name == null ? "" : name;
    }

    /** What the Map showed when the set was saved; its root is the folder it belongs to. */
    public ProjectMapState getState() {
        return state;
    }

    public void setState(@Nullable ProjectMapState state) {
        this.state = state == null ? new ProjectMapState() : state;
    }
}
