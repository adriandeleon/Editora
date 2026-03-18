package org.adriandeleon.editora.plugins;

public interface EditoraPlugin {
    String getId();

    String getDisplayName();

    void onLoad(EditoraContext context);

    default void onUnload(EditoraContext context) {
    }

    default String getApiVersion() {
        return "1";
    }
}

