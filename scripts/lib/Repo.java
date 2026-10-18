package lib;

import java.net.URISyntaxException;
import java.nio.file.Path;

/** Where the repository is, whatever directory a script was started from. */
public final class Repo {

    private Repo() {}

    /** The repository root: this file is {@code <root>/scripts/lib/Repo.java}. */
    public static Path root() {
        return scripts().getParent();
    }

    /** The {@code scripts/} directory. */
    public static Path scripts() {
        try {
            // The source launcher reports each class's own source file as its code source.
            Path self = Path.of(Repo.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return self.toAbsolutePath().getParent().getParent();
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }
}
