package com.editora.config;

import com.editora.vfs.RemoteConnection;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Where a local project is copied to so it can run on another machine: an SSH host and a folder there. A run
 * configuration names one in {@link RunConfiguration#deployment()} ("Run on"); before it starts, Editora brings
 * the folder up to date with the project and then runs the program there, in the copy (see
 * {@code deploy/Deployer} and {@code docs/subsystems/deployment.md}).
 *
 * <p>The connection is described as a saved site is ({@code host}, {@code port}, {@code user}, {@code auth},
 * {@code keyPath}): never a secret. {@code remoteRoot} is the absolute folder on the host. {@code excludes} are
 * extra patterns, one per line, on top of the built-in ones ({@code deploy/DeployExcludes}); with
 * {@code useGitignore} a Git project sends only what Git would track. {@code deleteRemoved} lets a deploy remove
 * files that Editora itself put on the host and that have since gone from the project (nothing else on the host
 * is ever removed). {@code uploadOnSave} sends a saved file at once instead of waiting for the next run.
 *
 * <p>Persisted per project in {@code WorkspaceState.deployments}, beside the run configurations. Jackson
 * round-trips this record; the compact constructor defaults every field so an older or partial entry loads.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Deployment(
        String name,
        String host,
        int port,
        String user,
        RemoteConnection.AuthMethod auth,
        String keyPath,
        String remoteRoot,
        String excludes,
        Boolean useGitignore,
        Boolean deleteRemoved,
        boolean uploadOnSave) {

    public static final int DEFAULT_PORT = 22;

    public Deployment {
        name = name == null ? "" : name;
        host = host == null ? "" : host.strip();
        port = port <= 0 ? DEFAULT_PORT : port;
        user = user == null ? "" : user.strip();
        auth = auth == null ? RemoteConnection.AuthMethod.DEFAULT_KEYS : auth;
        keyPath = keyPath == null ? "" : keyPath;
        remoteRoot = remoteRoot == null ? "" : remoteRoot.strip();
        excludes = excludes == null ? "" : excludes;
        // Absent from an older or hand-written entry, both default on: the safe, expected behaviour.
        useGitignore = useGitignore == null || useGitignore;
        deleteRemoved = deleteRemoved == null || deleteRemoved;
    }

    /** A new, empty deployment called {@code name}: the current user, automatic login, the defaults. */
    public static Deployment named(String name) {
        return new Deployment(
                name,
                "",
                DEFAULT_PORT,
                System.getProperty("user.name", ""),
                RemoteConnection.AuthMethod.DEFAULT_KEYS,
                "",
                "",
                "",
                true,
                true,
                false);
    }

    /** The connection to the host, as a saved site describes it; its folder is {@link #remoteRoot}. */
    @JsonIgnore
    public RemoteConnection connection() {
        return new RemoteConnection(host, port, user, auth, keyPath, null, remoteRoot.isEmpty() ? null : remoteRoot);
    }

    /** The host's id ({@code user@host:port}): what its agreement to run commands and its tools are kept by. */
    @JsonIgnore
    public String connectionId() {
        return connection().id();
    }

    /** {@code user@host:/folder}, for messages and the Run console. */
    @JsonIgnore
    public String displayTarget() {
        return (user.isEmpty() ? "" : user + "@") + host + (port == DEFAULT_PORT ? "" : ":" + port) + ":" + remoteRoot;
    }

    /** Whether the host and folder are filled in: the least a deploy needs. */
    @JsonIgnore
    public boolean complete() {
        return !host.isEmpty() && !user.isEmpty() && !remoteRoot.isEmpty();
    }

    public Deployment withName(String newName) {
        return new Deployment(
                newName,
                host,
                port,
                user,
                auth,
                keyPath,
                remoteRoot,
                excludes,
                useGitignore,
                deleteRemoved,
                uploadOnSave);
    }
}
