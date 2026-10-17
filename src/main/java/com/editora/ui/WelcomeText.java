package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import com.editora.config.Project;
import com.editora.vfs.RemoteConnection;

/**
 * The text decisions behind the Welcome page's rows, kept pure so they are unit-tested: how a recent file's
 * folder is written, which projects count as recent and in what order, and what a saved site shows beside
 * its name.
 */
final class WelcomeText {

    /** The SFTP port a site has unless it says otherwise; not worth printing. */
    static final int DEFAULT_SSH_PORT = 22;

    private WelcomeText() {}

    /**
     * The folder shown after a recent file's name. Inside a project it is relative to the project root (the
     * project chip already names the root; a file at the root shows nothing). Under the home directory it starts
     * with {@code ~}. Anywhere else it is the full folder.
     */
    static String folderText(Path file, Path projectRoot, Path home) {
        Path parent = file == null ? null : file.getParent();
        if (parent == null) {
            return "";
        }
        if (projectRoot != null && sameFileSystem(parent, projectRoot) && parent.startsWith(projectRoot)) {
            return projectRoot.relativize(parent).toString();
        }
        return dirText(parent, home);
    }

    /** A folder written for the UI: under the home directory it starts with {@code ~}, else in full. */
    static String dirText(Path dir, Path home) {
        if (dir == null) {
            return "";
        }
        if (home != null && sameFileSystem(dir, home) && dir.startsWith(home)) {
            Path rest = home.relativize(dir);
            return rest.toString().isEmpty() ? "~" : "~" + dir.getFileSystem().getSeparator() + rest;
        }
        return dir.toString();
    }

    /**
     * Projects in the order the user last touched them: a project comes as early as its most recent file in
     * {@code recentFiles}; projects with no recent file follow in their saved order. {@code owner} finds the
     * project a file belongs to (or null).
     */
    static List<Project> recentProjects(List<Project> projects, List<Path> recentFiles, Function<Path, Project> owner) {
        Set<Project> ordered = new LinkedHashSet<>();
        for (Path file : recentFiles) {
            Project p = owner.apply(file);
            if (p != null && projects.contains(p)) {
                ordered.add(p);
            }
        }
        ordered.addAll(projects);
        return new ArrayList<>(ordered);
    }

    /** {@code user@host:port} without the port when it is the SSH default. */
    static String withoutDefaultPort(String authority) {
        if (authority == null) {
            return "";
        }
        String suffix = ":" + DEFAULT_SSH_PORT;
        return authority.endsWith(suffix) ? authority.substring(0, authority.length() - suffix.length()) : authority;
    }

    /**
     * What a saved site shows beside its name, or "" when it would only repeat it: a labelled site shows where it
     * points ({@code user@host}, plus a port that is not 22); an unlabelled site, whose name already is
     * {@code user@host}, shows only a non-default port.
     */
    static String siteDetail(RemoteConnection site) {
        boolean labelled = site.label() != null && !site.label().isBlank();
        String port = site.port() == DEFAULT_SSH_PORT ? "" : ":" + site.port();
        if (!labelled) {
            return port;
        }
        String user = site.user() == null || site.user().isEmpty() ? "" : site.user() + "@";
        return user + site.host() + port;
    }

    private static boolean sameFileSystem(Path a, Path b) {
        return a.getFileSystem().equals(b.getFileSystem());
    }
}
