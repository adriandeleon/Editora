package com.editora.install;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jspecify.annotations.Nullable;

/**
 * Where the newest release of each server or tool Editora installs at "latest" is published, and the lookup that
 * asks: the npm registry, PyPI, the Go module proxy, GitHub releases and HashiCorp's release API. Doctor's Check
 * for Newer Versions is the only caller — nothing here runs unless the user asks, and nothing is sent but the
 * package's name. The pinned installs ({@link PinnedVersions}) are deliberately not looked up: a newer release of
 * those is adopted by a release of Editora, after testing.
 *
 * <p>Everything but {@link #fetch} is pure.
 */
public final class LatestVersions {

    private LatestVersions() {}

    /** Where a release is published. */
    public enum Registry {
        NPM,
        PYPI,
        GO,
        GITHUB,
        HASHICORP
    }

    /** One published package: an npm package, a PyPI project, a Go module, a GitHub repository, a HashiCorp product. */
    public record Source(Registry registry, String name) {

        /** A stable key for remembering what a lookup found. */
        public String key() {
            return registry.name().toLowerCase(java.util.Locale.ROOT) + ":" + name;
        }

        /** The endpoint that answers with the latest release. */
        public String url() {
            return switch (registry) {
                case NPM -> "https://registry.npmjs.org/" + name.replace("/", "%2F") + "/latest";
                case PYPI -> "https://pypi.org/pypi/" + name + "/json";
                case GO -> "https://proxy.golang.org/" + name + "/@latest";
                case GITHUB -> "https://api.github.com/repos/" + name + "/releases/latest";
                case HASHICORP -> "https://api.releases.hashicorp.com/v1/releases/" + name + "/latest";
            };
        }
    }

    /**
     * The published package an installable server comes from, for the servers whose installer takes the latest
     * release (npm packages, the Go module, the GitHub and HashiCorp archives); empty for a pinned one, for one
     * installed through a toolchain that updates it itself ({@code rustup}, {@code gem}, {@code dotnet},
     * {@code composer}), and for the XML server (its releases are named for the VS Code extension, not lemminx).
     */
    public static Optional<Source> forServer(@Nullable String serverId) {
        if (serverId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(
                switch (serverId) {
                    case "python" -> new Source(Registry.NPM, "pyright");
                    case "typescript" -> new Source(Registry.NPM, "typescript-language-server");
                    case "yaml" -> new Source(Registry.NPM, "yaml-language-server");
                    case "dockerfile" -> new Source(Registry.NPM, "dockerfile-language-server-nodejs");
                    case "toml" -> new Source(Registry.NPM, "@taplo/cli");
                    case "astro" -> new Source(Registry.NPM, "@astrojs/language-server");
                    case "go" -> new Source(Registry.GO, "golang.org/x/tools/gopls");
                    case "clangd" -> new Source(Registry.GITHUB, "clangd/clangd");
                    case "lua" -> new Source(Registry.GITHUB, "LuaLS/lua-language-server");
                    case "typst" -> new Source(Registry.GITHUB, "Myriad-Dreamin/tinymist");
                    case "terraform" -> new Source(Registry.HASHICORP, "terraform-ls");
                    default -> null;
                });
    }

    /** The published package of the Mermaid CLI ({@code mmdc}), installed from npm at its latest release. */
    public static final Source MERMAID_CLI = new Source(Registry.NPM, "@mermaid-js/mermaid-cli");

    /**
     * The release a registry's answer names: npm's {@code version}, PyPI's {@code info.version}, the Go proxy's
     * {@code Version}, GitHub's {@code tag_name}, HashiCorp's {@code version}; null for anything else (an error
     * page, a draft). Pure.
     */
    public static @Nullable String parse(Registry registry, @Nullable String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode root = MAPPER.readTree(json);
            JsonNode v = switch (registry) {
                case NPM, HASHICORP -> root.path("version");
                case PYPI -> root.path("info").path("version");
                case GO -> root.path("Version");
                case GITHUB ->
                    root.path("draft").asBoolean(false)
                                    || root.path("prerelease").asBoolean(false)
                            ? com.fasterxml.jackson.databind.node.MissingNode.getInstance()
                            : root.path("tag_name");
            };
            String text = v.isTextual() ? v.asText().strip() : "";
            return text.isEmpty() || Versions.firstNumber(text) == null ? null : text;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Whether {@code installed} is older than {@code latest}. A version that cannot be read is not called out of
     * date: a wrong "update available" is worse than a missed one. Pure.
     */
    public static boolean isNewer(@Nullable String latest, @Nullable String installed) {
        String a = Versions.firstNumber(latest);
        String b = Versions.firstNumber(installed);
        return a != null && b != null && Versions.compare(b, a) < 0;
    }

    /** What a lookup found: the latest release by {@link Source#key()}, and how many sources could not be asked. */
    public record Found(Map<String, String> latest, int failed) {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    /**
     * Asks every source (each once, in parallel) for its latest release. Blocking — call it off the FX thread. A
     * source that cannot be reached, or answers with anything but a release, is counted in {@link Found#failed}.
     */
    public static Found fetch(Collection<Source> sources, String userAgent) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        List<Source> unique = new ArrayList<>(new LinkedHashSet<>(sources));
        List<CompletableFuture<@Nullable String>> pending = new ArrayList<>();
        for (Source source : unique) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(source.url()))
                    .timeout(TIMEOUT)
                    .header("Accept", "application/json")
                    .header("User-Agent", userAgent) // GitHub refuses a request without one
                    .GET()
                    .build();
            pending.add(client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenApply(r -> r.statusCode() == 200 ? parse(source.registry(), r.body()) : null)
                    .exceptionally(e -> null));
        }
        Map<String, String> latest = new HashMap<>();
        int failed = 0;
        for (int i = 0; i < unique.size(); i++) {
            String version = pending.get(i).join();
            if (version == null) {
                failed++;
            } else {
                latest.put(unique.get(i).key(), version);
            }
        }
        client.close();
        return new Found(Map.copyOf(latest), failed);
    }
}
