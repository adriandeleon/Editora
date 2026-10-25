package com.editora.deploy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * The few POSIX {@code sh} scripts a deploy runs on the host, and the reading of what they print. Each is run
 * as {@code sh -c <script> editora-deploy <args…>}, so a path is always an argument and never part of the
 * script. Written for GNU, BSD (macOS) and BusyBox userlands alike. Pure.
 */
public final class HostScripts {

    private HostScripts() {}

    /** The {@code $0} each script is run under. */
    public static final String NAME = "editora-deploy";

    /** The first line the listing prints, before any file. */
    static final String MARK = "EDITORA-DEPLOY 1";

    /**
     * Lists the files under the folder {@code $1}: a first line {@code EDITORA-DEPLOY 1 <state> tar=<0|1>
     * gzip=<0|1>} ({@code state} is {@code missing}, {@code notdir} or {@code present}), then one line per file,
     * {@code <size> <modified seconds> <path from the folder>}. Folders named in {@code prunedNames} are not
     * entered: what the host builds or installs for itself ({@code target}, {@code node_modules}) is not listed.
     */
    public static String listing(List<String> prunedNames) {
        String prune = prune(prunedNames);
        return """
                root=$1
                t=0; command -v tar >/dev/null 2>&1 && t=1
                z=0; command -v gzip >/dev/null 2>&1 && z=1
                if [ ! -e "$root" ]; then echo "%1$s missing tar=$t gzip=$z"; exit 0; fi
                if [ ! -d "$root" ]; then echo "%1$s notdir tar=$t gzip=$z"; exit 0; fi
                cd "$root" || exit 3
                echo "%1$s present tar=$t gzip=$z"
                if find . -maxdepth 0 -printf '' >/dev/null 2>&1; then
                  find . %2$s -type f -printf '%%s %%T@ %%P\\n'
                elif stat -f %%z . >/dev/null 2>&1; then
                  find . %2$s -type f -exec stat -f '%%z %%m %%N' {} +
                else
                  find . %2$s -type f -exec stat -c '%%s %%Y %%n' {} +
                fi
                """.formatted(MARK, prune);
    }

    private static String prune(List<String> names) {
        if (names == null || names.isEmpty()) {
            return "";
        }
        StringBuilder s = new StringBuilder("\\( -type d \\( ");
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) {
                s.append(" -o ");
            }
            s.append("-name ").append(quote(names.get(i)));
        }
        return s.append(" \\) \\) -prune -o").toString();
    }

    /**
     * Unpacks a tar stream from stdin into the folder {@code $1}, creating it first. {@code $2} is {@code 1}
     * when the stream is gzip-compressed. Files keep the modified time in the archive (the local file's), which
     * is how the next deploy tells the host's copy is still the one it sent; owners are the account's.
     */
    public static String unpack() {
        return """
                mkdir -p "$1" && cd "$1" || exit 3
                if [ "$2" = 1 ]; then gzip -dc | tar -xof -; else tar -xof -; fi
                """;
    }

    /**
     * Removes, in the folder {@code $1}, the files named one per line on stdin, then — after a line
     * {@code ::dirs} — the folders named after it that are empty now. A file that is already gone is no error.
     */
    public static String remove() {
        return """
                cd "$1" || exit 3
                mode=f
                while IFS= read -r f; do
                  if [ "$f" = "::dirs" ]; then mode=d; continue; fi
                  if [ "$mode" = f ]; then rm -f "./$f"; else rmdir "./$f" 2>/dev/null; fi
                done
                exit 0
                """;
    }

    /** {@code value} in single quotes for {@code sh}. */
    static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    /** What the host said about the folder: whether it is there, its files, and the tools a deploy uses. */
    public record Listing(State state, boolean tar, boolean gzip, Map<String, RemoteFile> files) {
        public enum State {
            MISSING,
            NOT_A_FOLDER,
            PRESENT
        }
    }

    /** A file on the host: its length and modified time in whole seconds. */
    public record RemoteFile(long size, long seconds) {}

    /** {@link #listing}'s output, or null when it is not that (the script did not run, or failed). */
    public static @Nullable Listing parseListing(String output) {
        if (output == null) {
            return null;
        }
        String[] lines = output.split("\n");
        int first = 0;
        while (first < lines.length && !lines[first].startsWith(MARK)) {
            first++; // a login banner or profile noise before the script's own first line
        }
        if (first == lines.length) {
            return null;
        }
        String[] head = lines[first].substring(MARK.length()).strip().split(" ");
        Listing.State state = switch (head.length > 0 ? head[0] : "") {
            case "missing" -> Listing.State.MISSING;
            case "notdir" -> Listing.State.NOT_A_FOLDER;
            case "present" -> Listing.State.PRESENT;
            default -> null;
        };
        if (state == null) {
            return null;
        }
        boolean tar = false;
        boolean gzip = false;
        for (String field : head) {
            tar |= field.equals("tar=1");
            gzip |= field.equals("gzip=1");
        }
        Map<String, RemoteFile> files = new HashMap<>();
        for (int i = first + 1; i < lines.length; i++) {
            String line = lines[i];
            int a = line.indexOf(' ');
            int b = a < 0 ? -1 : line.indexOf(' ', a + 1);
            if (b < 0) {
                continue;
            }
            try {
                long size = Long.parseLong(line.substring(0, a));
                String time = line.substring(a + 1, b);
                int dot = time.indexOf('.');
                long seconds = Long.parseLong(dot < 0 ? time : time.substring(0, dot));
                String path = line.substring(b + 1);
                if (path.startsWith("./")) {
                    path = path.substring(2);
                }
                // macOS's tar (libarchive) writes names decomposed (NFD), as HFS+ wanted: "Ñandú" sent composed
                // comes back as other bytes, and would look missing on every deploy. Both sides compare composed.
                path = java.text.Normalizer.normalize(path, java.text.Normalizer.Form.NFC);
                if (LocalFiles.acceptable(path)) {
                    files.put(path, new RemoteFile(size, seconds));
                }
            } catch (NumberFormatException e) {
                // not a file line: skipped
            }
        }
        return new Listing(state, tar, gzip, files);
    }
}
