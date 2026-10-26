package com.editora.report;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * Strips what a problem report must not carry off the machine: the home folder and user name (they name the
 * person), e-mail addresses, credentials in URLs, authentication headers, and anything shaped like an API key
 * or token. The debug log records file paths, HTTP Client requests, SSH hosts and Git output, so any of these
 * can be in it.
 *
 * <p>Best-effort by nature — a secret with no recognisable shape gets through — which is why the report is
 * always shown, editable, before it leaves Editora. Pure; {@link #redact} counts what it replaced so the
 * preview can say so.
 */
public final class Redactor {

    /** Replacement text for a value that was removed. */
    static final String REDACTED = "<redacted>";

    /** A user name shorter than this is too likely to be an ordinary word or log fragment to replace. */
    private static final int MIN_USER_NAME = 3;

    /** Each rule rewrites its matches to {@code replacement} ({@code $1}…: groups kept from the match). */
    private record Rule(Pattern pattern, String replacement) {}

    private static final List<Rule> RULES = List.of(
            // A private key block, start to end.
            new Rule(
                    Pattern.compile(
                            "-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----.*?-----END [A-Z0-9 ]*PRIVATE KEY-----",
                            Pattern.DOTALL),
                    "<redacted private key>"),
            // user:password@ in a URL (https, ssh, git, ftp, a JDBC URL…): keep the scheme, drop the credentials.
            new Rule(Pattern.compile("([a-zA-Z][a-zA-Z0-9+.-]*://)[^\\s/@:]+:[^\\s/@]*@"), "$1" + REDACTED + "@"),
            // An authentication header, to the end of the line — the HTTP Client logs requests. A log line has a
            // timestamp in front, so the header name is matched anywhere, not only at the start of a line.
            new Rule(
                    Pattern.compile(
                            "(?im)\\b((?:proxy-)?(?:authorization|cookie|set-cookie|x-api-key|x-auth-token|api-key)"
                                    + "\\s*:\\s*)(?!<)\\S.*$"),
                    "$1" + REDACTED),
            new Rule(Pattern.compile("(?i)\\b(bearer)\\s+[A-Za-z0-9._~+/=-]{8,}"), "$1 " + REDACTED),
            // key=value with a secret-sounding key: a query string, a property line, a JSON-ish pair. A bare "key"
            // is not one — "unknown setting key: editor.tabSize" is exactly what a report is for.
            new Rule(
                    Pattern.compile(
                            "(?i)\\b((?:access_?|refresh_?|id_?|auth_?|client_?)?(?:token|secret|password|passwd"
                                    + "|pwd|apikey|api_key|private_key)\"?\\s*[=:]\\s*\"?)(?!<)[^\\s\"&,;}]{4,}"),
                    "$1" + REDACTED),
            // Token shapes: GitHub, OpenAI/Anthropic-style sk-, Slack, AWS access key, a JWT.
            new Rule(Pattern.compile("\\b(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,})"), REDACTED),
            new Rule(Pattern.compile("\\bsk-[A-Za-z0-9_-]{20,}"), REDACTED),
            new Rule(Pattern.compile("\\bxox[abposr]-[A-Za-z0-9-]{10,}"), REDACTED),
            new Rule(Pattern.compile("\\b(?:AKIA|ASIA)[0-9A-Z]{16}\\b"), REDACTED),
            new Rule(Pattern.compile("\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}"), REDACTED),
            // E-mail addresses (Git authors). git@github.com:owner/repo is a remote, not a person: kept.
            new Rule(
                    Pattern.compile(
                            "(?<![A-Za-z0-9._%+-])(?!git@)[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}"),
                    "<email>"));

    /** The redacted text and how many values were replaced. */
    public record Result(String text, int replaced) {}

    private final @Nullable String home;
    private final @Nullable String userName;

    /**
     * @param home the user's home folder ({@code user.home}); its paths become {@code ~}
     * @param userName the account name ({@code user.name}); other mentions of it become {@code <user>}
     */
    public Redactor(@Nullable String home, @Nullable String userName) {
        this.home = home == null || home.isBlank() ? null : stripTrailingSeparator(home.strip());
        this.userName = userName == null || userName.strip().length() < MIN_USER_NAME ? null : userName.strip();
    }

    /** A redactor for this machine's home folder and user. */
    public static Redactor forThisMachine() {
        return new Redactor(System.getProperty("user.home"), System.getProperty("user.name"));
    }

    public Result redact(String text) {
        if (text == null || text.isEmpty()) {
            return new Result("", 0);
        }
        int[] count = {0};
        String out = text;
        for (Rule rule : RULES) {
            out = replace(out, rule.pattern(), rule.replacement(), count);
        }
        String h = home;
        if (h != null) {
            // Both separators: a Windows home appears as C:\Users\me in paths and C:/Users/me in file: URIs.
            out = replaceLiteral(out, h, "~", count);
            if (h.indexOf('\\') >= 0) {
                out = replaceLiteral(out, h.replace('\\', '/'), "~", count);
            }
        }
        String user = userName;
        if (user != null) {
            out = replace(
                    out,
                    Pattern.compile("(?<![A-Za-z0-9_.-])" + Pattern.quote(user) + "(?![A-Za-z0-9_-])"),
                    "<user>",
                    count);
        }
        return new Result(out, count[0]);
    }

    private static String replace(String text, Pattern pattern, String replacement, int[] count) {
        Matcher m = pattern.matcher(text);
        if (!m.find()) {
            return text;
        }
        StringBuilder sb = new StringBuilder(text.length());
        do {
            count[0]++;
            m.appendReplacement(sb, replacement);
        } while (m.find());
        m.appendTail(sb);
        return sb.toString();
    }

    /** Replaces {@code literal} wherever a path separator or the end of a word follows it ({@code /home/me2} stays). */
    private static String replaceLiteral(String text, String literal, String replacement, int[] count) {
        return replace(text, Pattern.compile(Pattern.quote(literal) + "(?![A-Za-z0-9_.-])"), replacement, count);
    }

    private static String stripTrailingSeparator(String path) {
        return path.length() > 1 && (path.endsWith("/") || path.endsWith("\\"))
                ? path.substring(0, path.length() - 1)
                : path;
    }
}
