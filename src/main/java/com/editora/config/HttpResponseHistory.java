package com.editora.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import com.editora.config.migration.ConfigLoadProblem;
import com.editora.config.migration.ConfigMigrations;
import com.editora.config.migration.ConfigSchema;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The HTTP Client's remembered state per {@code .http} file, in {@code <configDir>/http-history/<hash>.json}:
 * the environment chosen for that file and its last {@link #MAX_RESPONSES} responses, so reopening the file
 * (or restarting Editora) shows what it last returned instead of an empty half-window.
 *
 * <p>What is kept is deliberately less than what was received: bodies longer than {@link #MAX_BODY_CHARS} are
 * cut, binary bodies are not kept at all, and the values of credential-bearing headers ({@link
 * #isCredentialHeader}) are masked, on the request and the response side. A request marked {@code # @no-log}
 * is never recorded — the caller leaves it out. How each request went out ({@link Sent}: the body file, the
 * form fields, the Digest user — never its password —, redirects, the HTTP version) is kept, so Copy as cURL
 * of a restored entry sends what was sent, not the history's one-line summary of the body.
 *
 * <p>Several windows can show the same file. Each saves through {@link #saveMerged}, which merges what the
 * window holds with what this process last knew of the file, so one window's save never drops another's
 * entries; an entry a window has seen and no longer holds was removed there (Clear, eviction) and stays gone,
 * and {@link Stored#clearedAt} keeps a Clear from being undone by a window still showing older entries.
 *
 * <p>The file lives in Editora's config folder, never beside the {@code .http} file, so nothing ends up in the
 * user's repository. Thread-safe: {@link #load} and {@link #saveMerged} block (call them off the FX thread),
 * and the write itself is handed to the sink.
 */
public final class HttpResponseHistory {

    /** v1 → v2: + protocol, charset and how the request was sent per response, + clearedAt (all additive). */
    public static final int SCHEMA_VERSION = 2;

    /** Responses remembered per {@code .http} file (newest first). */
    public static final int MAX_RESPONSES = 10;

    /** The longest body kept, in characters (about 1 MB of text). */
    public static final int MAX_BODY_CHARS = 1024 * 1024;

    /** What a masked header value is replaced with. */
    public static final String MASK = "••••••";

    static final String DIR_NAME = "http-history";

    /** How long a history file whose {@code .http} file is gone (with its folder) is kept before it is dropped. */
    static final long ORPHAN_GRACE_MILLIS = TimeUnit.DAYS.toMillis(90);

    /** A header as stored ({@code [name, value]} pairs do not serialize readably). */
    public record Header(String name, String value) {}

    /** One multipart field as it went out: an inline {@code value}, or a {@code file} (absolute path). */
    public record FormField(String name, String value, String file, String filename, String contentType) {}

    /**
     * How a request was sent, beyond its method, URL, headers and body text: the {@code < file} it streamed
     * (absolute path), its multipart fields, the Digest user (the password is never stored), whether redirects
     * were followed, the HTTP version it asked for, and the URL it ended at after redirects.
     */
    public record Sent(
            String bodyFile,
            List<FormField> formParts,
            String digestUser,
            boolean followRedirects,
            String httpVersion,
            String finalUrl) {}

    /**
     * One remembered exchange: when it ran ({@code at}, epoch millis), the request's {@code name} (its
     * {@code # @name} or {@code ###} title, may be null) and position in the file, the resolved request, and the
     * response. {@code bodyCut} marks a body cut to {@link #MAX_BODY_CHARS}; {@code binary} one that was not kept.
     * {@code protocol} is the version the response came in ({@code HTTP/1.1}), {@code charset} the one its body
     * was decoded with (so the bytes can be rebuilt), and {@code sent} how the request went out — all null in an
     * entry saved by a version before schema 2.
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
            boolean truncated,
            String protocol,
            String charset,
            Sent sent) {

        /** An entry without the schema-2 fields (as a version-1 file holds them). */
        public Response(
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
                boolean truncated) {
            this(
                    at,
                    name,
                    requestIndex,
                    method,
                    url,
                    requestHeaders,
                    requestBody,
                    status,
                    headers,
                    body,
                    bodyCut,
                    binary,
                    contentType,
                    elapsedMs,
                    sizeBytes,
                    error,
                    warnings,
                    truncated,
                    null,
                    null,
                    null);
        }

        /** This entry with {@code other}'s body: a window that dropped a body for memory must not blank it on disk. */
        Response withBodyOf(Response other) {
            return new Response(
                    at,
                    name,
                    requestIndex,
                    method,
                    url,
                    requestHeaders,
                    requestBody,
                    status,
                    headers,
                    other.body,
                    other.bodyCut,
                    binary,
                    contentType,
                    elapsedMs,
                    sizeBytes,
                    error,
                    warnings,
                    truncated,
                    protocol,
                    charset,
                    sent);
        }
    }

    /** Serialized form of one history file. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Stored {
        public int schemaVersion = SCHEMA_VERSION;
        /** The {@code .http} file this history belongs to (for a human looking in the folder). */
        public String file = "";
        /** The environment chosen for the file ({@code ""} = none). */
        public String environment = "";
        /** When the history was last cleared (epoch millis): no entry from before it is kept by any window. */
        public long clearedAt;

        public List<Response> responses = new ArrayList<>();

        /** Entries of the file that could not be read and were left out (not saved). */
        @JsonIgnore
        public int skipped;

        /** What could not be read as written when this was loaded (not saved); see {@link #load}. */
        @JsonIgnore
        public List<ConfigLoadProblem> problems = new ArrayList<>();

        /**
         * Reads the entries one by one, so one damaged entry (a hand edit, a value of the wrong type) loses only
         * itself instead of the whole history.
         */
        @JsonSetter("responses")
        public void readResponses(JsonNode node) {
            responses = new ArrayList<>();
            skipped = 0;
            if (node == null || !node.isArray()) {
                return;
            }
            for (JsonNode entry : node) {
                try {
                    Response r = ENTRY_READER.treeToValue(entry, Response.class);
                    if (r != null) {
                        responses.add(r);
                    }
                } catch (IOException | IllegalArgumentException damaged) {
                    skipped++;
                }
            }
        }
    }

    private static final ObjectMapper ENTRY_READER = new ObjectMapper()
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final Path dir;
    private final ConfigWriter.Sink sink;
    private final Consumer<ConfigLoadProblem> problems;
    private final ObjectMapper mapper = new ObjectMapper();

    /** The latest state of each file some window holds open ({@link #acquire}), as this process last saved or read it. */
    private final Map<Path, Stored> latest = new HashMap<>();

    private final Map<Path, Integer> holders = new HashMap<>();

    /** A standalone history that writes on the calling thread (tests, tools). The app uses {@link SharedConfig}. */
    public HttpResponseHistory(Path configDir) {
        this(configDir, ConfigWriter.DIRECT, problem -> {});
    }

    HttpResponseHistory(Path configDir, ConfigWriter.Sink sink, Consumer<ConfigLoadProblem> problems) {
        this.dir = configDir.resolve(DIR_NAME);
        this.sink = sink;
        this.problems = problems;
    }

    /**
     * A window shows {@code httpFile}'s history: its latest state is kept in memory until the matching {@link
     * #release}, so the windows' saves merge with each other's even before their writes reach the disk.
     */
    public synchronized void acquire(Path httpFile) {
        holders.merge(fileFor(httpFile), 1, Integer::sum);
    }

    /** The window no longer shows {@code httpFile}'s history (see {@link #acquire}). */
    public synchronized void release(Path httpFile) {
        Path file = fileFor(httpFile);
        Integer left = holders.computeIfPresent(file, (k, n) -> n > 1 ? n - 1 : null);
        if (left == null) {
            latest.remove(file);
        }
    }

    /**
     * The stored state of {@code httpFile} (empty when nothing was saved). Blocking: off the FX thread. What
     * could not be read is in {@link Stored#problems} and {@link Stored#skipped}, for the window to say so.
     */
    public synchronized Stored load(Path httpFile) {
        Path file = fileFor(httpFile);
        List<ConfigLoadProblem> found = new ArrayList<>();
        Stored stored = ConfigMigrations.readVersioned(file, mapper, new Stored(), ConfigSchema.HTTP_HISTORY, p -> {
            found.add(p);
            problems.accept(p);
        });
        Stored clean = new Stored();
        clean.file = stored.file == null ? "" : stored.file;
        clean.environment = stored.environment == null ? "" : stored.environment;
        clean.clearedAt = stored.clearedAt;
        if (stored.responses != null) {
            stored.responses.stream()
                    .filter(r -> r != null && (clean.clearedAt == 0 || r.at() > clean.clearedAt))
                    .limit(MAX_RESPONSES)
                    .forEach(clean.responses::add);
        }
        Stored baseline = copy(clean);
        sink.loaded(file, () -> mapper.writeValueAsBytes(baseline));
        if (holders.containsKey(file)) {
            latest.put(file, copy(clean));
        }
        clean.skipped = stored.skipped;
        clean.problems = List.copyOf(found);
        return clean;
    }

    /** Persists {@code state} for {@code httpFile} as is (trimmed to {@link #MAX_RESPONSES}); the write is queued. */
    public synchronized void save(Path httpFile, Stored state) {
        Stored snapshot = copy(state);
        snapshot.file = httpFile.toAbsolutePath().normalize().toString();
        Path file = fileFor(httpFile);
        if (holders.containsKey(file)) {
            latest.put(file, copy(snapshot));
        }
        sink.write(file, () -> mapper.writeValueAsBytes(snapshot));
    }

    /**
     * Saves one window's {@code mine} for {@code httpFile}, merged with the latest state this process knows of
     * the file (read from disk when no window holds it): {@code mine}'s entries, then every other entry not in
     * {@code seen} (the keys, {@link #entryKey}, of the entries that window has held — one it held and no
     * longer has was removed there). Blocking: off the FX thread. Returns what was saved.
     */
    public synchronized Stored saveMerged(Path httpFile, Stored mine, Set<String> seen) {
        Path file = fileFor(httpFile);
        Stored base = latest.get(file);
        if (base == null) {
            base = load(httpFile);
        }
        Stored merged = merge(base, mine, seen);
        save(httpFile, merged);
        return merged;
    }

    /**
     * {@code mine} merged into {@code base}: the newer {@code clearedAt}; {@code mine}'s environment; {@code
     * mine}'s entries, each keeping {@code base}'s body when it was dropped here for memory; then the entries
     * only {@code base} has that {@code seen} does not list; nothing from before {@code clearedAt}; newest first
     * (ties keep their order), at most {@link #MAX_RESPONSES}. Pure.
     */
    static Stored merge(Stored base, Stored mine, Set<String> seen) {
        Stored out = new Stored();
        out.clearedAt = Math.max(base == null ? 0 : base.clearedAt, mine.clearedAt);
        out.environment = mine.environment == null ? "" : mine.environment;
        out.file = mine.file == null ? "" : mine.file;
        Map<String, Response> theirs = new LinkedHashMap<>();
        if (base != null && base.responses != null) {
            for (Response r : base.responses) {
                if (r != null) {
                    theirs.putIfAbsent(entryKey(r), r);
                }
            }
        }
        List<Response> all = new ArrayList<>();
        java.util.Set<String> taken = new java.util.HashSet<>();
        for (Response r : mine.responses) {
            String key = entryKey(r);
            if (r == null || !taken.add(key)) {
                continue;
            }
            Response other = theirs.get(key);
            boolean dropped = (r.body() == null || r.body().isEmpty()) && !r.binary();
            all.add(
                    dropped
                                    && other != null
                                    && other.body() != null
                                    && !other.body().isEmpty()
                            ? r.withBodyOf(other)
                            : r);
        }
        for (Map.Entry<String, Response> e : theirs.entrySet()) {
            if (!taken.contains(e.getKey()) && (seen == null || !seen.contains(e.getKey()))) {
                taken.add(e.getKey());
                all.add(e.getValue());
            }
        }
        if (out.clearedAt > 0) {
            all.removeIf(r -> r.at() <= out.clearedAt);
        }
        all.sort(java.util.Comparator.comparingLong(Response::at).reversed()); // stable: a Run All keeps its order
        out.responses = retain(all, MAX_RESPONSES);
        return out;
    }

    /**
     * {@code newestFirst} cut to at most {@code max} entries, keeping each request's newest entry first (its name,
     * else its method and URL), so one long Run All cannot push out every other request's only response; the rest
     * fills by time. The disk-side twin of {@code ui.HttpHistoryRetention}. Pure.
     */
    static List<Response> retain(List<Response> newestFirst, int max) {
        if (newestFirst.size() <= max) {
            return new ArrayList<>(newestFirst);
        }
        java.util.Set<Integer> chosen = new java.util.LinkedHashSet<>();
        java.util.Set<String> requests = new java.util.HashSet<>();
        for (int i = 0; i < newestFirst.size() && chosen.size() < max; i++) {
            Response r = newestFirst.get(i);
            String request = r.name() != null
                    ? "name:" + r.name()
                    : "url:" + (r.method() == null ? "" : r.method().toUpperCase(java.util.Locale.ROOT)) + " "
                            + r.url();
            if (requests.add(request)) {
                chosen.add(i);
            }
        }
        for (int i = 0; i < newestFirst.size() && chosen.size() < max; i++) {
            chosen.add(i);
        }
        List<Response> out = new ArrayList<>(chosen.size());
        for (int i = 0; i < newestFirst.size(); i++) {
            if (chosen.contains(i)) {
                out.add(newestFirst.get(i));
            }
        }
        return out;
    }

    /**
     * What tells two saved entries apart: when the run was, the request's position, name, method and URL (the
     * entries of one Run All share their time). Pure.
     */
    public static String entryKey(Response r) {
        return r.at() + "\n" + r.requestIndex() + "\n" + r.name() + "\n" + r.method() + "\n" + r.url();
    }

    /** Forgets {@code httpFile}'s history: its pending write is dropped and its file deleted. */
    public synchronized void delete(Path httpFile) {
        Path file = fileFor(httpFile);
        latest.remove(file);
        sink.delete(file);
    }

    /**
     * Deletes the histories of {@code .http} files that no longer exist: at once when the file's folder is still
     * there (the file was deleted, renamed or moved), and after {@link #ORPHAN_GRACE_MILLIS} without a save when
     * the folder is gone too (it may be an unmounted drive). A history file whose {@code file} cannot be read is
     * left alone. Blocking: off the FX thread. Returns how many were deleted.
     */
    public int pruneOrphans(long now) {
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        List<Path> candidates = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.json")) {
            files.forEach(candidates::add);
        } catch (IOException | RuntimeException unreadable) {
            return 0;
        }
        int deleted = 0;
        for (Path history : candidates) {
            String recorded = recordedFile(history);
            if (recorded == null || recorded.isBlank()) {
                continue;
            }
            Path httpFile;
            try {
                httpFile = Path.of(recorded);
            } catch (RuntimeException notAPath) {
                continue;
            }
            if (!httpFile.isAbsolute() || Files.exists(httpFile) || !history.equals(fileFor(httpFile))) {
                continue;
            }
            boolean folderThere = httpFile.getParent() != null && Files.isDirectory(httpFile.getParent());
            long age;
            try {
                age = now - Files.getLastModifiedTime(history).toMillis();
            } catch (IOException gone) {
                continue;
            }
            synchronized (this) {
                if (holders.containsKey(history)) {
                    continue; // a window shows it (an untitled buffer saved there a moment ago)
                }
            }
            if (folderThere || age > ORPHAN_GRACE_MILLIS) {
                delete(httpFile);
                deleted++;
            }
        }
        return deleted;
    }

    /** The {@code file} a history file names, read without loading its responses; null when unreadable. */
    private String recordedFile(Path history) {
        try (JsonParser p = mapper.getFactory().createParser(history.toFile())) {
            if (p.nextToken() != JsonToken.START_OBJECT) {
                return null;
            }
            while (p.nextToken() == JsonToken.FIELD_NAME) {
                String field = p.currentName();
                JsonToken value = p.nextToken();
                if ("file".equals(field)) {
                    return value == JsonToken.VALUE_STRING ? p.getText() : null;
                }
                p.skipChildren();
            }
            return null;
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
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
        c.clearedAt = s.clearedAt;
        c.responses = new ArrayList<>(
                s.responses == null ? List.of() : s.responses.subList(0, Math.min(MAX_RESPONSES, s.responses.size())));
        return c;
    }

    // --- what is kept ----------------------------------------------------------------------------------

    /**
     * Whether {@code name} carries a credential, so its value must not be written to disk: {@code
     * Authorization}, {@code Proxy-Authorization}, {@code Authentication}, {@code Cookie}, {@code Set-Cookie(2)},
     * {@code X-Auth}, any header whose name mentions a token, a secret, a password, a session, a credential or a
     * signature ({@code X-Auth-Token}, {@code X-Session-Id}, …), and any whose name ends in "key" ({@code X-Api-Key},
     * Cloudflare's {@code X-Auth-Key}, Azure's {@code Ocp-Apim-Subscription-Key}, {@code X-Access-Key}) — except
     * an {@code Idempotency-Key}, which is a request id. Masking a harmless header costs a value in the saved
     * history; missing a key leaks it, so the test is broad.
     */
    public static boolean isCredentialHeader(String name) {
        if (name == null) {
            return false;
        }
        String n = name.strip().toLowerCase(Locale.ROOT);
        if (n.endsWith("idempotency-key")) {
            return false;
        }
        return n.equals("authorization")
                || n.equals("proxy-authorization")
                || n.equals("authentication")
                || n.equals("cookie")
                || n.equals("set-cookie")
                || n.equals("set-cookie2")
                || n.equals("x-auth")
                || n.contains("token")
                || n.contains("secret")
                || n.contains("password")
                || n.contains("passwd")
                || n.contains("session")
                || n.contains("credential")
                || n.contains("signature")
                || n.contains("api-key")
                || n.contains("apikey")
                || n.contains("api_key")
                || n.endsWith("key");
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
