package com.editora.editor;

/** Test access to {@link CodeActionBulb}'s package-private checks. */
public final class CodeActionBulbTestHooks {

    private CodeActionBulbTestHooks() {}

    public static boolean onScreen(javafx.scene.Node node) {
        return CodeActionBulb.onScreen(node);
    }
}
