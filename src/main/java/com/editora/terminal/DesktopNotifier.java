package com.editora.terminal;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

import com.editora.process.ProcessRunner;
import org.jspecify.annotations.Nullable;

/**
 * Puts a program's notification (OSC 9, OSC 777) on the desktop.
 *
 * <p>JavaFX has no notification API, and AWT — which has {@code SystemTray} — is off the table (see {@code App.main}).
 * Every platform has a command that does it, so this runs that: {@code notify-send} on Linux, or the session bus
 * directly through {@code gdbus} where libnotify's tool is not installed; {@code osascript} on macOS; a PowerShell
 * toast on Windows.
 *
 * <p><b>The text never becomes code.</b> A notification is written by whatever program is running, which can be one on
 * the far side of an {@code ssh}, so its title and body are treated as hostile: they reach {@code notify-send} and
 * {@code osascript} as arguments of their own (osascript reads them from {@code argv} in a fixed script), and
 * PowerShell through environment variables a fixed script reads. For {@code gdbus}, which parses its arguments as
 * GVariant text, they are quoted as GVariant strings — data, in a format that has no way to run anything.
 *
 * <p>Off the FX thread, on one worker, so a slow or missing notification daemon delays nothing. Ported from Termina.
 */
public final class DesktopNotifier {

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "editora-desktop-notifier");
        thread.setDaemon(true);
        return thread;
    });

    private static final String APP = "Editora";

    /** The environment variables the Windows script reads the text from. */
    static final String TITLE_VARIABLE = "EDITORA_NOTIFY_TITLE";

    static final String BODY_VARIABLE = "EDITORA_NOTIFY_BODY";

    /**
     * Toasts need an application identity, which an unpackaged app does not have; PowerShell's own is registered on
     * every Windows 10 and 11 machine, so the toast is shown as PowerShell's.
     */
    private static final String WINDOWS_SCRIPT = String.join(
            "\n",
            "$ErrorActionPreference = 'Stop'",
            "[Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType = WindowsRuntime] > $null",
            "$template = [Windows.UI.Notifications.ToastNotificationManager]::GetTemplateContent([Windows.UI.Notifications.ToastTemplateType]::ToastText02)",
            "$text = $template.GetElementsByTagName('text')",
            "$text.Item(0).AppendChild($template.CreateTextNode($env:" + TITLE_VARIABLE + ")) > $null",
            "$text.Item(1).AppendChild($template.CreateTextNode($env:" + BODY_VARIABLE + ")) > $null",
            "$toast = [Windows.UI.Notifications.ToastNotification]::new($template)",
            "$app = '{1AC14E77-02E7-4E5D-B744-2EB1AE5198B7}\\WindowsPowerShell\\v1.0\\powershell.exe'",
            "[Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier($app).Show($toast)");

    /** How to post one notification: a command line, and environment variables to add to it. */
    record Command(List<String> argv, Map<String, String> environment) {}

    private DesktopNotifier() {}

    /**
     * The command that posts a notification on this platform, or null where there is none to run. Pure, so each
     * platform's command — and that the text stays out of the code — is tested.
     *
     * @param os the {@code os.name} property
     * @param find a program's absolute path, or null when it is not installed
     */
    static @Nullable Command command(
            @Nullable String os, String title, String body, Function<String, @Nullable Path> find) {
        String name = os == null ? "" : os.toLowerCase(Locale.ROOT);
        if (name.startsWith("mac")) {
            return new Command(
                    List.of(
                            "/usr/bin/osascript",
                            "-e",
                            "on run argv",
                            "-e",
                            "display notification (item 2 of argv) with title (item 1 of argv)",
                            "-e",
                            "end run",
                            title,
                            body),
                    Map.of());
        }
        if (name.startsWith("windows")) {
            String encoded = Base64.getEncoder().encodeToString(WINDOWS_SCRIPT.getBytes(StandardCharsets.UTF_16LE));
            return new Command(
                    List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded),
                    Map.of(TITLE_VARIABLE, title, BODY_VARIABLE, body));
        }
        // The freedesktop servers read a subset of HTML in the body; a program's text is text.
        body = markupEscaped(body);
        Path notifySend = find.apply("notify-send");
        if (notifySend != null) {
            // "--" so a title beginning with a dash is a title and not an option.
            return new Command(List.of(notifySend.toString(), "--app-name=" + APP, "--", title, body), Map.of());
        }
        Path gdbus = find.apply("gdbus");
        if (gdbus != null) {
            return new Command(
                    List.of(
                            gdbus.toString(),
                            "call",
                            "--session",
                            "--dest",
                            "org.freedesktop.Notifications",
                            "--object-path",
                            "/org/freedesktop/Notifications",
                            "--method",
                            "org.freedesktop.Notifications.Notify",
                            // Without it the timeout's "-1" is read as an option, and gdbus prints its usage and posts
                            // nothing — silently, from here.
                            "--",
                            "'" + APP + "'",
                            "0",
                            "''",
                            gvariantString(title),
                            gvariantString(body),
                            "@as []",
                            "@a{sv} {}",
                            "-1"),
                    Map.of());
        }
        return null;
    }

    /** Text for a freedesktop notification body, which a server may read as markup. */
    static String markupEscaped(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** A string as GVariant text: single-quoted, with the quote and the backslash escaped. */
    static String gvariantString(String text) {
        StringBuilder out = new StringBuilder(text.length() + 2).append('\'');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' || c == '\'') out.append('\\');
            out.append(c);
        }
        return out.append('\'').toString();
    }

    /** Posts a notification, later and off the FX thread. A failure is dropped: it was a courtesy. */
    public static void post(String title, String body) {
        WORKER.execute(() -> {
            Command command =
                    command(System.getProperty("os.name"), title.isBlank() ? APP : title, body, PrimarySelection::find);
            if (command == null) return;
            try {
                // The user's locale: the text is theirs to read, in whatever script it was written.
                ProcessRunner.runInUserLocale(null, Duration.ofSeconds(5), command.argv(), command.environment());
            } catch (RuntimeException e) {
                // No daemon, no tool, a sandbox that refuses the bus: the tab is still marked.
            }
        });
    }
}
