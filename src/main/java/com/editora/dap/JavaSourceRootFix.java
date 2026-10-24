package com.editora.dap;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import com.editora.lsp.LspManager;
import org.jspecify.annotations.Nullable;

/**
 * Tells jdtls the source folder of a Java file in a folder with no build file, when its own guess is wrong.
 *
 * <p>jdtls keeps such a file in an "invisible project" whose source folder it guesses, and during the first
 * import it guesses the file's own folder: {@code src/demo/Main.java} declaring {@code package demo} is class
 * {@code Main} to it, so every file was red with "The declared package "demo" does not match the expected
 * package """ (Java review C6) and a launch named a class that does not exist. When the declared package says
 * the source folder is another one, the right folder is added and the wrong ones removed — jdtls's workspace
 * changes, never the user's files. Anything that cannot be corrected (no package, a misplaced file, a folder
 * outside the server's workspace, a file on another host) is left as it is.
 */
public final class JavaSourceRootFix {

    private JavaSourceRootFix() {}

    /** The package {@code text} (a Java file's source) declares, or "" for none. Pure. */
    public static String declaredPackage(String text) {
        return JavaLaunchSupport.declaredPackage(
                text == null ? List.of() : text.lines().limit(500).toList());
    }

    /** Where {@code file} declaring {@code declaredPackage} says its source folder is, or null. Pure. */
    public static @Nullable Path sourceRoot(Path file, String declaredPackage) {
        return declaredPackage == null || declaredPackage.isEmpty()
                ? null
                : JavaLaunchSupport.sourceRoot(file, declaredPackage);
    }

    /**
     * Corrects {@code file}'s source folder as the class comment says, then runs {@code then} on the FX thread —
     * with whether jdtls now has the right folder. {@code current} false stops it between requests.
     */
    public static void correct(
            LspManager lsp,
            Path file,
            String declaredPackage,
            BooleanSupplier current,
            java.util.function.Consumer<Boolean> then) {
        Path root = !com.editora.process.ExecTargets.runsHere(file) ? null : sourceRoot(file, declaredPackage);
        if (root == null) {
            then.accept(false);
            return;
        }
        lsp.executeCommand(file, "java.project.listSourcePaths", List.of(), (res, err) -> {
            if (!current.getAsBoolean()) {
                return;
            }
            List<Path> listed = err == null ? JavaLaunchSupport.sourcePaths(res) : List.of();
            List<Path> wrong = JavaLaunchSupport.wrongSourceRoots(listed, file, root);
            if (wrong.isEmpty()) {
                then.accept(listed.contains(root));
                return;
            }
            lsp.executeCommand(
                    file, "java.project.addToSourcePath", List.of(root.toUri().toString()), (added, e2) -> {
                        if (!current.getAsBoolean()) {
                            return;
                        }
                        if (e2 != null || !JavaLaunchSupport.statusOk(added)) {
                            then.accept(false); // the right folder is outside the server's workspace: leave it be
                            return;
                        }
                        remove(lsp, file, new ArrayList<>(wrong), current, then);
                    });
        });
    }

    private static void remove(
            LspManager lsp,
            Path file,
            List<Path> wrong,
            BooleanSupplier current,
            java.util.function.Consumer<Boolean> then) {
        if (wrong.isEmpty()) {
            then.accept(true);
            return;
        }
        Path next = wrong.remove(0);
        lsp.executeCommand(
                file, "java.project.removeFromSourcePath", List.of(next.toUri().toString()), (r, e) -> {
                    if (current.getAsBoolean()) {
                        remove(lsp, file, wrong, current, then);
                    }
                });
    }
}
