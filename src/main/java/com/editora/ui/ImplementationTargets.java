package com.editora.ui;

import java.nio.file.Path;

import com.editora.lsp.LspManager;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * Picker text for Go to Implementation and Go to Super Method targets: a project file or a library class file,
 * named and placed for the row. Moved out of {@link LspCoordinator} to keep that file under its size limit.
 */
final class ImplementationTargets {

    private ImplementationTargets() {}

    /** An implementation's picker row: its class (a library's {@code ArrayList}) or its file's name. */
    static String implementationLabel(LspManager.Target t) {
        if (t.file() != null) {
            return t.file().getFileName().toString();
        }
        String title = LspManager.classFileTitle(t.classFileUri());
        return title.replaceFirst("\\.(class|java)$", "");
    }

    /** Where it is: the library class's package, or the file relative to the project, with its line. */
    static String implementationDetail(LspManager.Target t, @Nullable Path base) {
        if (t.file() != null) {
            Path file = t.file();
            String where = base != null && file.startsWith(base)
                    ? base.relativize(file).toString()
                    : file.toString();
            return where + ":" + (t.line() + 1);
        }
        // the line tells apart the classes one file holds: AbstractList's SubList, its iterators (third review BN4)
        String pkg = libraryPackage(t);
        String line = tr("structure.line", t.line() + 1);
        return pkg.isEmpty() ? line : pkg + "  ·  " + line;
    }

    /** The package of a library target's class file ({@code java.util}), or "". */
    static String libraryPackage(LspManager.Target t) {
        String uri = t.classFileUri() == null ? "" : t.classFileUri();
        int q = uri.indexOf('?');
        String path = q >= 0 ? uri.substring(0, q) : uri;
        int last = path.lastIndexOf('/');
        int before = last > 0 ? path.lastIndexOf('/', last - 1) : -1;
        return before >= 0 ? path.substring(before + 1, last) : "";
    }
}
