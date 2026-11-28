package com.editora.ui;

import com.editora.editor.EditorBuffer;
import org.jspecify.annotations.Nullable;

/**
 * What {@link HttpVariableHints} reads from a window's HTTP Client, resolved from {@link MainController} when
 * called. It lived inside {@code MainController} as an anonymous class; it is a file of its own only because of
 * that file's size (see {@link WindowCommandHost}).
 */
final class HttpVariableSourceHost implements HttpVariableHints.Source {

    private final MainController mc;

    HttpVariableSourceHost(MainController mc) {
        this.mc = mc;
    }

    @Override
    public String environment(EditorBuffer buffer) {
        return mc.httpClient.selectedEnvironment(buffer);
    }

    @Override
    public java.nio.file.@Nullable Path envDir(EditorBuffer buffer) {
        return mc.httpClient.envDirFor(buffer);
    }

    @Override
    public void choose(EditorBuffer buffer, String environment) {
        mc.httpClient.chooseEnvironment(buffer, environment); // the "Use environment dev" quick fix
    }
}
