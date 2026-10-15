package com.editora.config;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import com.editora.config.migration.ConfigLoadProblem;
import com.editora.config.migration.ConfigMigrations;
import com.editora.config.migration.ConfigSchema;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The HTTP Client's remembered state per {@code .http} file, in {@code <configDir>/http-history/<hash>.json}:
 * the environment chosen for that file and its last {@link #MAX_RESPONSES} responses, so reopening the file
 * (or restarting Editora) shows what it last returned instead of an empty half-window.
 *
 * <p>What is kept is deliberately less than what was received: bodies longer than {@link #MAX_BODY_CHARS} are
 * cut, binary bodies are not kept at all, and the values of credential-bearing headers ({@link
 * #isCredentialHeader}) are masked, on the request and the response side. A request marked {@code # @no-log}
 * is never recorded — the caller leaves it out.
 *
 * <p>The file lives in Editora's config folder, never beside the {@code .http} file, so nothing ends up in the
 * user's repository. One file per {@code .http} file keeps a large history from being rewritten as a whole
 * on every run. Thread-safe: {@link #load} reads on the calling thread (call it off the FX thread), {@link
 * #save} snapshots and hands the write to the sink.
 */
public final class HttpResponseHistory {

    public static final int SCHEMA_VERSION = 1;

    /** Responses remembered per {@code .http} file (newest first). */
    public static final int MAX_RESPONSES = 10;

    /** The longest body kept, in characters (about 1 MB of text). */
    public static final int MAX_BODY_CHARS = 1024 * 1024;

    /** What a masked header value is replaced with. */
    public static final String MASK = "••••••";

    static final String DIR_NAME = "http-history";

    /** A header as stored ({@code [name, value]} pairs do not serialize readably). */
    public record Header(String name, String value) {}

    /**
     * One remembered exchange: when it ran ({@code at}, epoch millis), the request's {@code name} (its
     * {@code # @name} or {@code ###} title, may be null) and position in the file, the resolved request, and the
     * response. {@code bodyCut} marks a body cut to {@link #MAX_BODY_CHARS}; {@code binary} one that was not kept.
     */
    public record Response(
            long at,
            String name,
            int requestIndex,
            String method,
            String url,
            List<Header> requestHeaders,
            String requestBody,
            int status,
            List<Header> headers,
            String body,
            boolean bodyCut,
            boolean binary,
            String contentType,
            long elapsedMs,
            long sizeBytes,
            String error,
            List<String> warnings,
            boolean truncated) {}

    /** Serialized form of one history file. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Stored {
        public int schemaVersion = SCHEMA_VERSION;
        /** The {@code .http} file this history belongs to (for a human looking in the folder). */
        public String file = "";
        /** The environment chosen for the file ({@code ""} = none). */
        public String environment = "";

        public List<Response> responses = new ArrayList<>();
    }

    private final Path dir;
    private final ConfigWriter.Sink sink;
    private final Consumer<ConfigLoadProblem> problems;
    private final ObjectMapper mapper = new ObjectMapper();

    /** A standalone history that writes on the calling thread (tests, tools). The app uses {@link SharedConfig}. */
    public HttpResponseHistory(Path configDir) {
        this(configDir, ConfigWriter.DIRECT, problem -> {});
    }

    HttpResponseHistory(Path configDir, ConfigWriter.Sink sink, Consumer<ConfigLoadProblem> problems) {
        this.dir = configDir.resolve(DIR_NAME);
        this.sink = sink;
        this.problems = problems;
    }

    /** The stored state of {@code httpFile} (empty when nothing was saved). Blocking: off the FX thread. */
    public Stored load(Path httpFile) {
        Path file = fileFor(httpFile);
        Stored stored = ConfigMigrations.readVersioned(file, mapper, new Stored(), ConfigSchema.HTTP_HISTORY, problems);
        Stored clean = new Stored();
        clean.file = stored.file == null ? "" : stored.file;
        clean.environment = stored.environment == null ? "" : stored.environment;
        if (stored.responses != null) {
            stored.responses.stream()
                    .filter(r -> r != null)
                    .limit(MAX_RESPONSES)
                    .forEach(clean.responses::add);
        }
        Stored baseline = copy(clean);
        sink.loaded(file, () -> mapper.writeValueAsBytes(baseline));
        return clean;
    }

    /** Persists {@code state} for {@code httpFile} (trimmed to {@link #MAX_RESPONSES}); the write is queued. */
    public void save(Path httpFile, Stored state) {
        Stored snapshot = copy(state);
        snapshot.file = httpFile.toAbsolutePath().normalize().toString();
        sink.write(fileFor(httpFile), () -> mapper.writeValueAsBytes(snapshot));
    }

    /** The history file for {@code httpFile}: a stable hash of its absolute path. */
    public Path fileFor(Path httpFile) {
        return dir.resolve(key(httpFile) + ".json");
    }

    static String key(Path httpFile) {
        String path = httpFile.toAbsolutePath().normalize().toString();
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(path.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d, 0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // every JDK has SHA-256
        }
    }

    private static Stored copy(Stored s) {
        Stored c = new Stored();
        c.file = s.file == null ? "" : s.file;
        c.environment = s.environment == null ? "" : s.environment;
        c.responses = new ArrayList<>(
                s.responses == null ? List.of() : s.responses.subList(0, Math.min(MAX_RESPONSES, s.responses.size())));
        return c;
    }

    // --- what is kept ----------------------------------------------------------------------------------

    /**
     * Whether {@code name} carries a credential, so its value must not be written to disk: {@code
     * Authorization}, {@code Proxy-Authorization}, {@code Cookie}, {@code Set-Cookie}, and any header whose name
     * mentions a token, a secret, an API key or a password ({@code X-Auth-Token}, {@code X-Api-Key}, …).
     */
    public static boolean isCredentialHeader(String name) {
        if (name == null) {
            return false;
        }
        String n = name.strip().toLowerCase(Locale.ROOT);
        return n.equals("authorization")
                || n.equals("proxy-authorization")
                || n.equals("cookie")
                || n.equals("set-cookie")
                || n.contains("token")
                || n.contains("secret")
                || n.contains("api-key")
                || n.contains("apikey")
                || n.contains("api_key")
                || n.contains("password");
    }

    /** {@code headers} as stored: credential values replaced by {@link #MASK}. */
    public static List<Header> maskedHeaders(List<String[]> headers) {
        List<Header> out = new ArrayList<>();
        if (headers != null) {
            for (String[] h : headers) {
                if (h == null || h.length < 2) {
                    continue;
                }
                out.add(new Header(h[0], isCredentialHeader(h[0]) ? MASK : h[1]));
            }
        }
        return out;
    }

    /** {@code body} cut to {@link #MAX_BODY_CHARS} (null → ""). */
    public static String cappedBody(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= MAX_BODY_CHARS ? body : body.substring(0, MAX_BODY_CHARS);
    }
}
