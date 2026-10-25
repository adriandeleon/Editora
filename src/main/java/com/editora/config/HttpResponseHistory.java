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
import org.jspecify.annotations.Nullable;

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

    /**
     * v1 → v2: + protocol, charset and how the request was sent per response, + clearedAt. v2 → v3: + when each
     * response landed, its request's URL as written and a fingerprint of the request (all additive). v3 → v4: + what
     * each response's {@code @assert} checks and response handlers found ({@link Tests}; additive).
     */
    public static final int SCHEMA_VERSION = 5;

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
    public record Header(String name, @Nullable String value) {}

    /** One multipart field as it went out: an inline {@code value}, or a {@code file} (absolute path). */
    public record FormField(
            @Nullable String name,
            @Nullable String value,
            @Nullable String file,
            @Nullable String filename,
            @Nullable String contentType) {}

    /**
     * How a request was sent, beyond its method, URL, headers and body text: the {@code < file} it streamed
     * (absolute path), its multipart fields, the Digest user (the password is never stored), whether redirects
     * were followed, the HTTP version it asked for, and the URL it ended at after redirects.
     */
    public record Sent(
            @Nullable String bodyFile,
            List<FormField> formParts,
            @Nullable String digestUser,
            boolean followRedirects,
            @Nullable String httpVersion,
            @Nullable String finalUrl) {}

    /** One test of a response: its name, whether it passed, and why not. */
    public record TestResult(
            @Nullable String name, boolean passed, @Nullable String message) {}

    /**
     * What a response's {@code @assert} checks and response handlers found: the tests in the order they ran, the
     * handlers' log lines, and the error a handler stopped with (null when none did). Null in an entry that ran none,
     * and in every entry saved before schema 4.
     */
    public record Tests(
            List<TestResult> tests,
            List<String> log,
            @Nullable String error) {}

    /**
     * One remembered exchange: when it ran ({@code at}, epoch millis), the request's {@code name} (its
     * {@code # @name} or {@code ###} title, may be null) and position in the file, the resolved request, and the
     * response. {@code bodyCut} marks a body cut to {@link #MAX_BODY_CHARS}; {@code binary} one that was not kept.
     * {@code protocol} is the version the response came in ({@code HTTP/1.1}), {@code charset} the one its body
     * was decoded with (so the bytes can be rebuilt), and {@code sent} how the request went out — all null in an
     * entry saved by a version before schema 2. {@code landedAt} is when the response arrived (0 before schema 3:
     * {@code at} stands in), which a Clear is compared with; {@code urlTemplate} the request's URL as written
     * ({@code {{…}}} kept) and {@code fingerprint} a digest of its text, by which Run finds it again. {@code tests}
     * are what its checks and handlers found (null when it ran none, and before schema 4). {@code environment} is the
     * environment the request was sent in ({@code ""}: none; null when unknown, and before schema 5).
     */
    public record Response(
            long at,
            @Nullable String name,
            int requestIndex,
            String method,
            String url,
            @Nullable List<Header> requestHeaders,
            String requestBody,
            int status,
            @Nullable List<Header> headers,
            String body,
            boolean bodyCut,
            boolean binary,
            String contentType,
            long elapsedMs,
            long sizeBytes,
            @Nullable String error,
            List<String> warnings,
            boolean truncated,
            @Nullable String protocol,
            @Nullable String charset,
            @Nullable Sent sent,
            long landedAt,
            @Nullable String urlTemplate,
            @Nullable String fingerprint,
            @Nullable Tests tests,
            @Nullable String environment) {

        /** An entry without its environment (as a version-4 file holds them). */
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
                boolean truncated,
                @Nullable String protocol,
                @Nullable String charset,
                @Nullable Sent sent,
                long landedAt,
                @Nullable String urlTemplate,
                @Nullable String fingerprint,
                @Nullable Tests tests) {
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
                    protocol,
                    charset,
                    sent,
                    landedAt,
                    urlTemplate,
                    fingerprint,
                    tests,
                    null);
        }

        /** An entry without test results (as a version-3 file holds them). */
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
                boolean truncated,
                String protocol,
                String charset,
                Sent sent,
                long landedAt,
                String urlTemplate,
                String fingerprint) {
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
                    protocol,
                    charset,
                    sent,
                    landedAt,
                    urlTemplate,
                    fingerprint,
                    null);
        }

        /** An entry without the schema-3 fields (as a version-2 file holds them). */
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
                boolean truncated,
                String protocol,
                String charset,
                Sent sent) {
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
                    protocol,
                    charset,
                    sent,
                    0,
                    null,
                    null,
                    null);
        }

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
                    null,
                    0,
                    null,
                    null,
                    null);
        }

        /** When this response arrived: {@link #landedAt}, or {@link #at} for an entry saved before schema 3. */
        public long landed() {
            return landedAt > 0 ? landedAt : at;
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
                    sent,
                    landedAt,
                    urlTemplate,
                    fingerprint,
                    tests,
                    environment);
        }

        /** This entry with {@code requestHeaders} and {@code headers} masked again by today's rules. */
        Response remasked() {
            return new Response(
                    at,
                    name,
                    requestIndex,
                    method,
                    url,
                    remask(requestHeaders),
                    requestBody,
                    status,
                    remask(headers),
                    body,
                    bodyCut,
                    binary,
                    contentType,
                    elapsedMs,
                    sizeBytes,
                    error,
                    warnings,
                    truncated,
                    protocol,
                    charset,
                    sent,
                    landedAt,
                    urlTemplate,
                    fingerprint,
                    tests,
                    environment);
        }

        private static @Nullable List<Header> remask(@Nullable List<Header> headers) {
            if (headers == null) {
                return null;
            }
            List<Header> out = new ArrayList<>(headers.size());
            for (Header h : headers) {
                out.add(
                        h != null && isCredentialHeader(h.name()) && !MASK.equals(h.value())
                                ? new Header(h.name(), MASK)
                                : h);
            }
            return out;
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

        /** Where the file was copied before its unreadable entries go on the next save (not saved); null if none. */
        @JsonIgnore
        public @Nullable Path damagedCopy;

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
            if (node == null || node.isNull()) {
                return;
            }
            if (!node.isArray()) {
                skipped = Math.max(1, node.size()); // not a list (a hand edit): nothing in it can be read
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

    /** Waits for the reads and saves windows have queued for this store (see {@link #setPendingWork}). */
    private volatile Runnable pendingWork = () -> {};

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
     * Who knows how to wait for the history work windows have queued but not yet handed to {@link #saveMerged}
     * (the windows' background thread): {@link #awaitPendingWork} runs it before the app's config writer stops,
     * so a save queued just before quitting still reaches the disk.
     */
    public void setPendingWork(Runnable awaitIdle) {
        this.pendingWork = awaitIdle == null ? () -> {} : awaitIdle;
    }

    /** Blocks until the queued history work has run (shutdown, export). Never throws. */
    public void awaitPendingWork() {
        try {
            pendingWork.run();
        } catch (RuntimeException ignored) {
            // a timeout or an interrupted wait: what was queued is lost either way, the rest still shuts down
        }
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
        boolean remasked = false;
        if (stored.responses != null) {
            for (Response r : stored.responses) {
                if (r == null || (clean.clearedAt != 0 && r.landed() < clean.clearedAt)) {
                    continue;
                }
                if (clean.responses.size() == MAX_RESPONSES) {
                    break;
                }
                // An older build masked fewer headers: what it left in clear is masked now, and saved so below.
                Response masked = r.remasked();
                remasked |= !masked.equals(r);
                clean.responses.add(masked);
            }
        }
        Path damagedCopy = null;
        if (stored.skipped > 0) {
            // The entries that cannot be read go on the next save: keep the file as it was, as a whole-file
            // problem does, so a hand edit can still be recovered.
            damagedCopy = ConfigMigrations.keepCopyOfDamaged(file);
        }
        Stored baseline = copy(clean);
        sink.loaded(file, () -> mapper.writeValueAsBytes(baseline));
        if (holders.containsKey(file)) {
            latest.put(file, copy(clean));
        }
        if (remasked) {
            save(httpFile, clean);
        }
        clean.skipped = stored.skipped;
        clean.damagedCopy = damagedCopy;
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
            // By when each response arrived, not when its request started: a run in flight during the Clear (a
            // slow request, a cancelled Run All's finished requests) lands after it and is kept, as it is shown.
            all.removeIf(r -> r.landed() < out.clearedAt);
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
            if (requests.add(requestKey(r.name(), r.requestIndex(), r.method(), r.url()))) {
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
     * Which request of the file an entry came from, for keeping each request's newest response: its name; else
     * its position and method — an unnamed request whose URL varies ({@code {{id}}}, {@code {{$timestamp}}}) is
     * still one request —; else, with no position known, its method and URL. Shared with {@code
     * ui.HttpHistoryRetention}. Pure.
     */
    public static String requestKey(@Nullable String name, int requestIndex, String method, String url) {
        String m = method == null ? "" : method.toUpperCase(java.util.Locale.ROOT);
        if (name != null && !name.isBlank()) {
            return "name:" + name.strip();
        }
        return requestIndex >= 0 ? "index:" + requestIndex + " " + m : "url:" + m + " " + url;
    }

    /**
     * What tells two saved entries apart: when the run was, the request's position, name, method and URL (the
     * entries of one Run All share their time). Pure.
     */
    public static String entryKey(Response r) {
        return entryKey(r.at(), r.requestIndex(), r.name(), r.method(), r.url());
    }

    /** {@link #entryKey(Response)} from its parts. Pure. */
    public static String entryKey(
            long at, int requestIndex, @Nullable String name, String method, @Nullable String url) {
        return at + "\n" + requestIndex + "\n" + name + "\n" + method + "\n" + url;
    }

    /**
     * {@code from} was renamed or moved to {@code to} — a {@code .http} file, or a folder (then every history whose
     * file was under it): each history is saved under its new path, merged with one already there, and the old one
     * deleted. A history a window holds moves from what that window last saved, not from the disk, which may lag.
     * Blocking: off the FX thread. Returns how many histories moved.
     */
    public int move(Path from, Path to) {
        Path src = from.toAbsolutePath().normalize();
        Path dst = to.toAbsolutePath().normalize();
        if (!Files.isDirectory(dst)) {
            return moveOne(src, dst) ? 1 : 0;
        }
        List<Path> candidates = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.json")) {
            files.forEach(candidates::add);
        } catch (IOException | RuntimeException unreadable) {
            return 0;
        }
        int moved = 0;
        for (Path history : candidates) {
            String recorded = recordedFile(history);
            Path file;
            try {
                file = recorded == null || recorded.isBlank()
                        ? null
                        : Path.of(recorded).normalize();
            } catch (RuntimeException notAPath) {
                file = null;
            }
            if (file != null && file.isAbsolute() && file.startsWith(src) && history.equals(fileFor(file))) {
                if (moveOne(file, dst.resolve(src.relativize(file)))) {
                    moved++;
                }
            }
        }
        return moved;
    }

    private synchronized boolean moveOne(Path fromFile, Path toFile) {
        Path oldHistory = fileFor(fromFile);
        if (oldHistory.equals(fileFor(toFile))) {
            return false;
        }
        Stored held = latest.get(oldHistory);
        if (held == null && !Files.exists(oldHistory)) {
            return false;
        }
        Stored mine = held != null ? copy(held) : load(fromFile);
        Path newHistory = fileFor(toFile);
        Stored base = latest.containsKey(newHistory)
                ? latest.get(newHistory)
                : Files.exists(newHistory) ? load(toFile) : new Stored();
        Stored merged = merge(base, mine, Set.of());
        if (merged.environment.isEmpty()) {
            merged.environment = base.environment == null ? "" : base.environment;
        }
        save(toFile, merged);
        delete(fromFile);
        return true;
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
    private @Nullable String recordedFile(Path history) {
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
     * Whether {@code name} carries a credential, so its value must not be written to disk: any header whose name
     * mentions authorization ({@code Proxy-Authorization}, {@code X-Authorization}, {@code
     * X-Forwarded-Authorization}) or a cookie ({@code Cookie2}, {@code Set-Cookie}), {@code Authentication}, {@code
     * X-Auth}, any whose name mentions a token, a secret, a pass(word), a pwd, a session, a credential, a signature,
     * a JWT, an assertion or OIDC data ({@code X-Auth-Token}, {@code X-Session-Id}, {@code X-JWT-Assertion},
     * {@code X-Amzn-Oidc-Data}, …), and any whose name ends in "key" ({@code X-Api-Key},
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
        return n.contains("authorization") // also X-Authorization, X-Forwarded-Authorization
                || n.equals("authentication")
                || n.contains("cookie") // Cookie, Cookie2, Set-Cookie, Set-Cookie2
                || n.equals("x-auth")
                || n.contains("jwt")
                || n.contains("assertion") // X-JWT-Assertion, X-Goog-IAP-JWT-Assertion
                || n.contains("oidc") // X-Amzn-Oidc-Data (a signed token)
                || n.contains("pass") // X-Pass, password, passwd, passphrase
                || n.contains("pwd")
                || n.contains("token")
                || n.contains("secret")
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
    public static String cappedBody(@Nullable String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= MAX_BODY_CHARS ? body : body.substring(0, MAX_BODY_CHARS);
    }
}
