package com.editora.doctor;

/**
 * Pure display-text decisions for the Doctor screen's rows. A probed detail is very often an absolute path
 * that would otherwise blow past the report's column and force the row's own tool name to ellipsize, so the
 * pane shortens what it can before laying anything out: collapse {@code $HOME} to {@code ~}, drop a resolved
 * path that merely repeats the configured command, and drop a bare command the resolved path already ends
 * with. Kept out of {@code DoctorPane} so the decisions are unit-testable (the pane only does layout).
 */
public final class DoctorText {

    private DoctorText() {}

    /** True for text that reads as a filesystem path — worth ellipsizing from the <i>left</i> so the tail survives. */
    public static boolean isPathLike(String text) {
        if (text == null || text.length() < 2) {
            return false;
        }
        if (text.startsWith("/") || text.startsWith("~/") || text.startsWith("./")) {
            return true;
        }
        return text.length() > 2
                && Character.isLetter(text.charAt(0))
                && text.charAt(1) == ':'
                && (text.charAt(2) == '\\' || text.charAt(2) == '/');
    }

    /** True when the probed detail is the configured command verbatim (a command set to an absolute path). */
    public static boolean detailRepeatsCommand(String command, String detail) {
        if (command == null || detail == null) {
            return false;
        }
        String c = command.trim();
        String d = detail.trim();
        return !d.isEmpty() && d.equals(c);
    }

    /**
     * True when showing the command adds nothing over the probed path — a bare, argument-less command whose
     * name is exactly what the resolved path ends with ({@code jdtls} beside {@code ~/…/bin/jdtls}). Dropping
     * it is what usually lets the whole path fit untruncated.
     */
    public static boolean commandRepeatsDetail(String command, String detail) {
        if (command == null || detail == null) {
            return false;
        }
        String c = command.trim();
        if (c.isEmpty() || c.indexOf(' ') >= 0 || !isPathLike(detail)) {
            return false;
        }
        return fileName(c).equals(fileName(detail.trim()));
    }

    private static String fileName(String path) {
        int cut = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return cut < 0 ? path : path.substring(cut + 1);
    }
}
